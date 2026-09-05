package com.esper.app.game

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.esper.engine.character.CharacterFactory
import com.esper.engine.character.CharacterState
import com.esper.engine.character.Progression
import com.esper.engine.combat.BattleResult
import com.esper.engine.combat.CombatEngine
import com.esper.engine.content.ContentCatalog
import com.esper.engine.dice.SystemRandom
import com.esper.engine.encounter.Battle
import com.esper.engine.encounter.BattleBuilder
import com.esper.engine.encounter.Encounter
import com.esper.engine.encounter.EncounterSeeder
import com.esper.engine.geometry.GeoPoint
import com.esper.engine.geometry.LocalTangentPlane
import kotlin.math.max

/**
 * App-scoped state for the core loop: location, the movement leash, the seeded
 * encounter and an in-flight battle.
 *
 * A singleton rather than a ViewModel, mirroring `core/ClaudeSession`, so an
 * in-flight battle survives navigating away to the map and back.
 *
 * `:app` never computes a game outcome here — every call below goes straight
 * through to an `:engine` entry point (`EncounterSeeder`, `BattleBuilder`,
 * `CombatEngine`, `Progression`, `SaveCodec` via [GameStorage]); this object only
 * decides *when* to call them and holds the results as UI state.
 */
object GameSession {

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

    /**
     * Ensures a character exists: loads a saved one, or creates and immediately
     * persists a fresh one starting as "squire". Sets [contentError] instead of
     * crashing if the catalog has no jobs at all.
     */
    fun ensureCharacter(context: Context) {
        if (character != null) return

        val catalog = ContentRepository.catalog(context)
        if (catalog.jobs.isEmpty()) {
            contentError = ContentRepository.lastError ?: "No jobs are available."
            return
        }
        contentError = null

        val storage = GameStorage(context)
        val loaded = storage.load()
        if (loaded != null) {
            character = loaded
            return
        }

        val startingJob = catalog.job("squire") ?: catalog.jobs.first()
        val created = CharacterFactory.newCharacter("Esper", startingJob)
        character = created
        storage.save(created)
    }

    /** Seeds one encounter near [radiusCenter], re-seeding if the leash has moved away from it. */
    fun ensureEncounter(context: Context) {
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

        val seeder = EncounterSeeder(catalog.monsters)
        // Keep the old encounter if seeding failed rather than dropping to null.
        encounter = seeder.seedNear(center, SystemRandom) ?: existing
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
        ensureCharacter(context)
        val currentCharacter = character ?: return

        val catalog = ContentRepository.catalog(context)
        val built = BattleBuilder.build(enc, currentCharacter, catalog)
        battle = built
        engine = CombatEngine(built.board, built.units, SystemRandom)
    }

    /**
     * Applies the battle's outcome: on [BattleResult.VICTORY], awards xp and job
     * points, recomputes unlocks, and persists immediately via [GameStorage] —
     * process death must not lose a won fight. Either way, clears the finished
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
            GameStorage(context).save(updated)
        }

        battle = null
        engine = null
        encounter = null
    }

    /** Re-seed once the encounter is further than this many leash radii away. */
    private const val RESEED_DISTANCE_FACTOR = 5.0
}
