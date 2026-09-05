package com.esper.engine.character

/**
 * schemaVersion-keyed migration chain.
 *
 * At v1 this is a documented no-op, but the hook exists from day one: the design
 * doc requires that a schema change ships with the migration that carries existing
 * characters across, and a chain nobody built yet is how that requirement gets
 * quietly dropped.
 */
object SaveMigrations {
    fun migrate(rawJson: String): String = TODO("implemented by engine-character")
}
