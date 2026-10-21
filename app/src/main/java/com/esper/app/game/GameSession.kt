package com.esper.app.game

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.esper.app.core.ServerClient
import com.esper.app.core.Settings
import com.esper.app.core.Unauthorized
import com.esper.engine.character.CharacterState
import com.esper.engine.character.Progression
import com.esper.engine.combat.BattleResult
import com.esper.engine.combat.CombatEngine
import com.esper.engine.content.ContentCatalog
import com.esper.engine.content.JobDefinition
import com.esper.engine.dice.SystemRandom
import com.esper.engine.encounter.Battle
import com.esper.engine.encounter.BattleBuilder
import com.esper.engine.encounter.Encounter
import com.esper.engine.encounter.EncounterSeeder
import com.esper.engine.encounter.GeodataSafetyChecker
import com.esper.engine.geometry.GeoPoint
import com.esper.engine.geometry.LocalTangentPlane
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.math.max

/**
 * App-scoped state for the core loop: the server session, location, the
 * movement leash, the seeded encounter and an in-flight battle.
 *
 * A singleton rather than a ViewModel, mirroring `core/ClaudeSession`, so an
 * in-flight battle survives navigating away to the map and back.
 *
 * `:app` never computes a game outcome here — every call below goes straight
 * through to an `:engine` entry point (`EncounterSeeder`, `BattleBuilder`,
 * `CombatEngine`, `Progression`, `SaveCodec` via [ServerClient]); this object only
 * decides *when* to call them and holds the results as UI state.
 *
 * The character lives on the Esper server (docs/SERVER.md); there is no offline
 * mode. A 401 from any call signs the player out.
 */
object GameSession {

    private var settings: Settings? = null

    /** Background server calls (progress sync, position upload); outlives any screen. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serialises progress PUTs so an older state can never land after a newer one. */
    private val syncMutex = Mutex()

    /** True while a session token is stored. The UI shows sign-in otherwise. */
    var signedIn by mutableStateOf(false)
        private set

    /** True while [loadCharacter] is talking to the server. */
    var loading by mutableStateOf(false)
        private set

    /** Why the character could not be loaded (e.g. server unreachable); null otherwise. */
    var loadError by mutableStateOf<String?>(null)
        private set

    /** Set when the last progress upload failed; cleared by the next one that succeeds. */
    var syncError by mutableStateOf<String?>(null)
        private set

    private var lastSentFix: GeoPoint? = null
    private var lastSentAtMillis = 0L

    /** Called once at startup with the app's [Settings]. */
    fun bind(settings: Settings) {
        this.settings = settings
        signedIn = settings.sessionToken != null
    }

    /** Stores the token a passkey ceremony returned. */
    fun signIn(token: String) {
        settings?.sessionToken = token
        clearSessionState()
        signedIn = true
    }

    /** Best-effort server logout, then forgets the token and the character either way. */
    suspend fun signOut() {
        client()?.logout()
        settings?.sessionToken = null
        clearSessionState()
        signedIn = false
    }

    private fun client(): ServerClient? = settings?.let { ServerClient(it.serverUrl, it.sessionToken) }

    /** Any 401 means the token is dead: drop it so the gate returns to sign-in. */
    private fun <T> Result<T>.checkAuth(): Result<T> = onFailure {
        if (it is Unauthorized) {
            settings?.sessionToken = null
            clearSessionState()
            signedIn = false
        }
    }

    private fun clearSessionState() {
        character = null
        needsCharacter = false
        loadError = null
        syncError = null
        battle = null
        engine = null
        encounter = null
        lastSentFix = null
    }

    /** Most recent GPS fix, or null if none has arrived. */
    var lastFix by mutableStateOf<GeoPoint?>(null)
        private set

    /** Accuracy Android reported for [lastFix], in metres. */
    var lastAccuracyMetres by mutableStateOf<Float?>(null)
        private set

    /** Centre of the movement leash. Moved with hysteresis, never on every fix. */
    var radiusCenter by mutableStateOf<GeoPoint?>(null)
        private set

    /** 12 m, the MVP default recorded in docs/GAME_DESIGN.md. */
    var radiusMetres by mutableStateOf(12.0)
        private set

    var encounter by mutableStateOf<Encounter?>(null)
        private set

    var battle by mutableStateOf<Battle?>(null)
        private set

    var engine by mutableStateOf<CombatEngine?>(null)
        private set

    var character by mutableStateOf<CharacterState?>(null)
        private set

    /** Why no encounter could be seeded, shown instead of "none nearby yet". */
    var encounterNotice by mutableStateOf<String?>(null)
        private set

    /** Non-null when content failed to load; surfaced instead of crashing. */
    var contentError by mutableStateOf<String?>(null)
        private set

    /** True once the player has declined the location permission. */
    var locationDenied by mutableStateOf(false)

    /**
     * Called whenever [LocationProvider] reports a fix.
     *
     * Hysteresis: [radiusCenter] only moves once the fix is further away than
     * the greater of the reported accuracy or 5 m, so a stationary player's
     * circle stays put instead of jittering with GPS noise.
     */
    fun onLocationFix(latitude: Double, longitude: Double, accuracyMetres: Float) {
        val fix = GeoPoint(latitude, longitude)
        lastFix = fix
        lastAccuracyMetres = accuracyMetres
        locationDenied = false
        uploadFix(fix, accuracyMetres)

        val current = radiusCenter
        if (current == null) {
            radiusCenter = fix
            return
        }
        val moved = LocalTangentPlane(current).distanceMetres(current, fix)
        val threshold = max(accuracyMetres.toDouble(), 5.0)
        if (moved > threshold) {
            radiusCenter = fix
        }
    }

    /**
     * Sends the fix per the location rule in docs/GAME_DESIGN.md ("Why the app
     * asks for location"): only real GPS fixes (never the map-view fallback), at
     * most every 30 s or after 50 m. Fire-and-forget; a failed send waits for
     * the next window.
     */
    private fun uploadFix(fix: GeoPoint, accuracyMetres: Float) {
        if (!signedIn) return
        val now = SystemClock.elapsedRealtime()
        if (!shouldSendFix(lastSentFix, lastSentAtMillis, fix, now)) return
        val server = client() ?: return
        lastSentFix = fix
        lastSentAtMillis = now
        scope.launch { server.postLocation(fix.lat, fix.lon, accuracyMetres.toDouble()).checkAuth() }
    }

    /**
     * Playable without a location grant: seeds [radiusCenter] from the map's
     * current view centre if nothing has set it yet. No-op once a real fix (or an
     * earlier fallback) has already established a centre — the avatar's leash is
     * never yanked back to the map's centre once it exists.
     */
    fun useFallbackCenter(point: GeoPoint) {
        if (radiusCenter == null) {
            radiusCenter = point
        }
    }

    /** True once [loadCharacter] found none on the server: the player must create one first. */
    var needsCharacter by mutableStateOf(false)
        private set

    /**
     * Loads the account's character from the server. With none, sets
     * [needsCharacter] so the UI shows the creator; see [createCharacter]. Sets
     * [loadError] when the server can't be reached, and [contentError] instead of
     * crashing if the catalog has no jobs at all.
     */
    suspend fun loadCharacter(context: Context) {
        if (character != null || loading) return

        val catalog = ContentRepository.catalog(context)
        if (catalog.jobs.isEmpty()) {
            contentError = ContentRepository.lastError ?: "No jobs are available."
            return
        }
        contentError = null

        val server = client() ?: return
        loading = true
        loadError = null
        val result = server.getCharacter().mapCatching { remote ->
            remote ?: importLocalSave(context, server)
        }.checkAuth()
        loading = false
        result
            .onSuccess {
                character = it
                needsCharacter = it == null
            }
            .onFailure { if (it !is Unauthorized) loadError = it.message ?: "Couldn't load your character." }
    }

    /**
     * One-time import of a character saved on the phone before the server
     * existed. The file is deleted only after the server has accepted it, so a
     * failed upload leaves it in place for the next attempt.
     */
    private suspend fun importLocalSave(context: Context, server: ServerClient): CharacterState? {
        val storage = GameStorage(context)
        val local = storage.load() ?: return null
        server.putCharacter(local).getOrThrow()
        storage.clear()
        return local
    }

    /** Creates the player's character on the server from the creator screen. */
    suspend fun createCharacter(name: String, startingJob: JobDefinition): Result<Unit> {
        val server = client() ?: return Result.failure(IOException("Not signed in."))
        return server.createCharacter(name, startingJob.id).checkAuth().map {
            character = it
            needsCharacter = false
        }
    }

    /**
     * Uploads the current character in the background. Always sends the latest
     * state at the time the lock is taken, so a retry also covers every earlier
     * failed upload.
     *
     * shortcut: client-authoritative progress, and a win made while the server is
     * unreachable is lost if the process dies before a retry succeeds; replaced
     * when step 5 runs combat server-side.
     */
    private fun syncCharacter() {
        val server = client() ?: return
        scope.launch {
            syncMutex.withLock {
                val state = character ?: return@withLock
                server.putCharacter(state).checkAuth()
                    .onSuccess { syncError = null }
                    .onFailure {
                        if (it !is Unauthorized) {
                            syncError = "Progress not saved to the server yet (${it.message}). It retries automatically."
                        }
                    }
            }
        }
    }

    /** Called when the map resumes: retries a failed progress upload. */
    fun retrySync() {
        if (syncError != null) syncCharacter()
    }

    /**
     * Seeds one encounter near [radiusCenter], re-seeding if the leash has moved away from it.
     *
     * Fails closed: if the surrounding roads, water and private land can't be
     * fetched, nothing is seeded, because seeding blind could put the encounter
     * on a road (docs/GAME_DESIGN.md, Combat safety rules).
     */
    suspend fun ensureEncounter(context: Context) {
        val center = radiusCenter ?: return

        val existing = encounter
        if (existing != null) {
            // Never move the encounter out from under a fight in progress.
            if (battle != null) return
            val distance = LocalTangentPlane(center).distanceMetres(center, existing.anchor)
            // A fallback centre replaced by a real fix can jump arbitrarily far;
            // an encounter left behind there is unreachable for the whole session.
            if (distance <= radiusMetres * RESEED_DISTANCE_FACTOR) return
        }

        val catalog = ContentRepository.catalog(context)
        if (catalog.monsters.isEmpty()) {
            contentError = ContentRepository.lastError ?: "No monsters are available."
            return
        }

        val hazards = try {
            HazardSource.around(center, HAZARD_QUERY_RADIUS_METRES)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            encounterNotice = "Can't check the area is safe right now (offline?), so no encounter yet."
            return
        }
        // A fight may have started while the request was in flight.
        if (battle != null) return

        val seeder = EncounterSeeder(catalog.monsters, GeodataSafetyChecker(hazards))
        val seeded = seeder.seedNear(center, SystemRandom)
        encounterNotice = if (seeded == null) "No safe spot nearby: try moving away from roads or water." else null
        // Keep the old encounter if seeding failed rather than dropping to null.
        encounter = seeded ?: existing
    }

    /** Straight-line distance from the leash centre to the active encounter, if any. */
    fun distanceToEncounterMetres(): Double? {
        val enc = encounter ?: return null
        val center = radiusCenter ?: lastFix ?: return null
        return LocalTangentPlane(center).distanceMetres(center, enc.anchor)
    }

    /**
     * Builds a [Battle] from the active encounter and constructs its [CombatEngine].
     *
     * An in-flight fight is resumed, never restarted: if [engine] already holds
     * one that has not ended, this returns without touching it. If it already
     * ended (the player left combat by system back rather than the result
     * card's button), it is settled first via [endBattle] so its rewards are
     * never silently lost.
     */
    fun beginBattle(context: Context) {
        val existing = engine
        if (existing != null) {
            val finished = existing.result()
            if (finished == null) return
            endBattle(finished, context)
        }

        val enc = encounter ?: return
        val currentCharacter = character ?: return

        val catalog = ContentRepository.catalog(context)
        val built = BattleBuilder.build(enc, currentCharacter, catalog)
        battle = built
        engine = CombatEngine(built.board, built.units, SystemRandom)
    }

    /**
     * Applies the battle's outcome: on [BattleResult.VICTORY], awards xp and job
     * points, recomputes unlocks, and uploads it to the server straight away
     * (see [syncCharacter]). Either way, clears the finished
     * battle so a new encounter can be seeded.
     */
    fun endBattle(result: BattleResult, context: Context) {
        val finishedEngine = engine
        val currentCharacter = character

        if (result == BattleResult.VICTORY && finishedEngine != null && currentCharacter != null) {
            val catalog: ContentCatalog = ContentRepository.catalog(context)
            val defeatedMonsters = finishedEngine.units
                .filter { !it.playerControlled && !it.alive }
                .mapNotNull { catalog.monster(it.sourceId) }

            var updated = Progression.awardVictory(currentCharacter, defeatedMonsters)
            updated = Progression.recomputeUnlocks(updated, catalog.jobs)
            character = updated
            syncCharacter()
        } else {
            retrySync()
        }

        battle = null
        engine = null
        encounter = null
    }

    /**
     * Covers the 10 m seeding ring plus the widest default buffered hazard (a
     * river: 10 m half-width + 2 m clearance) with room for wider `width` tags.
     */
    private const val HAZARD_QUERY_RADIUS_METRES = 40.0

    /** Re-seed once the encounter is further than this many leash radii away. */
    private const val RESEED_DISTANCE_FACTOR = 5.0
}

/** Location upload throttle (docs/GAME_DESIGN.md): first fix, then every 30 s or after 50 m. */
internal fun shouldSendFix(lastSent: GeoPoint?, lastSentAtMillis: Long, fix: GeoPoint, nowMillis: Long): Boolean {
    if (lastSent == null) return true
    if (nowMillis - lastSentAtMillis >= 30_000L) return true
    return LocalTangentPlane(lastSent).distanceMetres(lastSent, fix) >= 50.0
}
