package com.esper.server.character

import com.esper.engine.character.CharacterFactory
import com.esper.engine.character.CharacterState
import com.esper.engine.character.SaveCodec
import com.esper.engine.content.ContentCatalog
import com.esper.server.ApiError
import com.esper.server.Db
import com.esper.server.accountId
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CreateCharacterBody(val name: String, val startingJobId: String)

/** Pure checks for the character API (docs/SERVER.md §5). Throws [ApiError]. */
class CharacterRules(private val catalog: ContentCatalog) {
    private val abilityIds = catalog.jobs.flatMap { it.abilitiesGranted }.toSet()

    fun create(body: CreateCharacterBody): CharacterState {
        val name = body.name.trim()
        if (name.isEmpty() || name.length > MAX_NAME) {
            throw ApiError(HttpStatusCode.BadRequest, "invalid_name", "Name must be 1 to $MAX_NAME characters.")
        }
        val job = CharacterFactory.startingJobs(catalog.jobs).firstOrNull { it.id == body.startingJobId }
            ?: throw ApiError(HttpStatusCode.BadRequest, "invalid_job", "That is not a starting job.")
        return CharacterFactory.newCharacter(name, job)
    }

    /**
     * Decodes and checks a client-sent state against the stored one (null = no row yet).
     *
     * shortcut: client-authoritative progress, replaced when step 5 runs combat server-side and PUT goes away
     */
    fun update(text: String, stored: CharacterState?): CharacterState {
        try {
            Json.parseToJsonElement(text)
        } catch (e: IllegalArgumentException) {
            throw ApiError(HttpStatusCode.BadRequest, "bad_request", "Body is not valid JSON.")
        }
        val state = try {
            SaveCodec.decode(text)
        } catch (e: IllegalArgumentException) { // SerializationException is one
            throw invalidState("Character data is not a valid save.")
        }
        if (state.schemaVersion != CharacterState.CURRENT_SCHEMA_VERSION) {
            throw invalidState("Save schema version ${state.schemaVersion} is not supported.")
        }
        val jobIds = listOf(state.currentJobId) + state.unlockedJobIds + state.jobPoints.keys
        if (jobIds.any { catalog.job(it) == null }) {
            throw ApiError(HttpStatusCode.BadRequest, "invalid_job", "Character names a job that does not exist.")
        }
        if (!abilityIds.containsAll(state.learnedAbilityIds)) {
            throw ApiError(HttpStatusCode.BadRequest, "invalid_job", "Character names an ability that does not exist.")
        }
        if (stored == null) {
            // Import path skips create(), so the name is checked here; the job may be past the starting ones.
            if (state.name.isBlank() || state.name.length > MAX_NAME) throw invalidState("Name must be 1 to $MAX_NAME characters.")
        } else {
            if (state.name != stored.name) throw invalidState("A character cannot be renamed.")
            if (state.level < stored.level) throw invalidState("A character cannot lose levels.")
        }
        return state
    }

    private fun invalidState(message: String) = ApiError(HttpStatusCode.BadRequest, "invalid_state", message)

    companion object {
        const val MAX_NAME = 24
    }
}

/** One character per account; `state` is `SaveCodec.encode` output in a jsonb column. */
fun Route.characterRoutes(db: Db, rules: CharacterRules) {
    get("/v1/character") {
        val stored = load(db, call.accountId())
            ?: throw ApiError(HttpStatusCode.NotFound, "no_character", "No character yet.")
        // Re-encoded rather than returned raw: jsonb reorders keys, and decode runs migrations.
        call.respondText(SaveCodec.encode(stored), ContentType.Application.Json)
    }

    post("/v1/character") {
        val state = rules.create(call.receive<CreateCharacterBody>())
        val inserted = db.tx { c ->
            c.prepareStatement(
                "INSERT INTO characters (account_id, state) VALUES (?, ?::jsonb) ON CONFLICT (account_id) DO NOTHING",
            ).use {
                it.setObject(1, call.accountId())
                it.setString(2, SaveCodec.encode(state))
                it.executeUpdate() == 1
            }
        }
        if (!inserted) throw ApiError(HttpStatusCode.Conflict, "character_exists", "You already have a character.")
        call.respondText(SaveCodec.encode(state), ContentType.Application.Json, HttpStatusCode.Created)
    }

    put("/v1/character") {
        val accountId = call.accountId()
        val text = call.receiveText()
        db.tx { c ->
            // FOR UPDATE: two racing PUTs cannot both pass the level check against the same row.
            val stored = c.prepareStatement("SELECT state FROM characters WHERE account_id = ? FOR UPDATE").use {
                it.setObject(1, accountId)
                it.executeQuery().use { rs -> if (rs.next()) SaveCodec.decode(rs.getString(1)) else null }
            }
            val state = rules.update(text, stored)
            c.prepareStatement(
                """
                INSERT INTO characters (account_id, state) VALUES (?, ?::jsonb)
                ON CONFLICT (account_id) DO UPDATE SET state = EXCLUDED.state, updated_at = now()
                """.trimIndent(),
            ).use {
                it.setObject(1, accountId)
                it.setString(2, SaveCodec.encode(state))
                it.executeUpdate()
            }
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun load(db: Db, accountId: UUID): CharacterState? =
    db.tx { c ->
        c.prepareStatement("SELECT state FROM characters WHERE account_id = ?").use {
            it.setObject(1, accountId)
            it.executeQuery().use { rs -> if (rs.next()) SaveCodec.decode(rs.getString(1)) else null }
        }
    }
