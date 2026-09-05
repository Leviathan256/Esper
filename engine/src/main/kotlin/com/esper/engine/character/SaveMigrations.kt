package com.esper.engine.character

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * schemaVersion-keyed migration chain.
 *
 * At v1 this is a documented no-op, but the hook exists from day one: the design
 * doc requires that a schema change ships with the migration that carries existing
 * characters across, and a chain nobody built yet is how that requirement gets
 * quietly dropped.
 */
object SaveMigrations {
    private val json = Json { ignoreUnknownKeys = true }

    fun migrate(rawJson: String): String {
        var element = json.parseToJsonElement(rawJson).jsonObject

        // v0 -> v1: no shape change, just stamp the version. The chain a real
        // migration slots into.
        if (schemaVersionOf(element) < 1) {
            element = withSchemaVersion(element, 1)
        }

        return element.toString()
    }

    private fun schemaVersionOf(element: JsonObject): Int {
        val raw = element["schemaVersion"] ?: return 0
        if (raw is JsonNull) return 0
        return raw.jsonPrimitive.int
    }

    private fun withSchemaVersion(element: JsonObject, version: Int): JsonObject {
        val mutable = LinkedHashMap<String, JsonElement>(element)
        mutable["schemaVersion"] = JsonPrimitive(version)
        return JsonObject(mutable)
    }
}
