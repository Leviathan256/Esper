package com.esper.engine.character

/**
 * Save serialisation.
 *
 * Decoding tolerates unknown keys, so a build older than the save it is reading
 * degrades instead of crashing.
 */
object SaveCodec {
    fun encode(state: CharacterState): String = TODO("implemented by engine-character")

    /** Runs [SaveMigrations.migrate] first. */
    fun decode(text: String): CharacterState = TODO("implemented by engine-character")
}
