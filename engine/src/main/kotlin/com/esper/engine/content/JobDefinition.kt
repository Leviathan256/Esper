package com.esper.engine.content

import kotlinx.serialization.Serializable

/**
 * One job, authored as `content/jobs/<id>.json`.
 *
 * Adding a job is adding one file — no index, no Kotlin. Every optional field has
 * a default so an older APK survives newer content, and the loader ignores unknown
 * keys for the same reason.
 */
@Serializable
data class JobDefinition(
    val schemaVersion: Int,
    val id: String,
    val displayName: String,
    val description: String = "",
    val hitDie: String,
    val baseArmorClass: Int,
    val attackAbility: String,
    val damage: String,
    val speed: Int,
    val moveRangeCells: Int,
    val attackRangeCells: Int = 1,
    val statGrowth: Map<String, Int> = emptyMap(),
    val abilitiesGranted: List<String> = emptyList(),
    val prerequisites: List<String> = emptyList(),
    val unlocks: List<String> = emptyList(),
    val jobPointsToMaster: Int = 100,
)
