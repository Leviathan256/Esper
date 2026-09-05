package com.esper.engine.character

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Save serialisation.
 *
 * Decoding tolerates unknown keys, so a build older than the save it is reading
 * degrades instead of crashing.
 */
object SaveCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(state: CharacterState): String = json.encodeToString(state)

    /** Runs [SaveMigrations.migrate] first. */
    fun decode(text: String): CharacterState {
        val migrated = SaveMigrations.migrate(text)
        return json.decodeFromString<CharacterState>(migrated)
    }
}
