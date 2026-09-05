package com.esper.engine.stats

import kotlinx.serialization.Serializable

/** The six D&D ability scores. Saved with the character, so it is serializable. */
@Serializable
data class AbilityScores(
    val str: Int,
    val dex: Int,
    val con: Int,
    val int: Int,
    val wis: Int,
    val cha: Int,
) {
    /** @throws IllegalArgumentException on an unknown key. */
    fun score(key: String): Int = TODO("implemented by engine-character")

    fun modifier(key: String): Int = TODO("implemented by engine-character")

    /** Applies a job's `statGrowth` [times] over. */
    fun plus(growth: Map<String, Int>, times: Int): AbilityScores =
        TODO("implemented by engine-character")

    companion object {
        val KEYS: List<String> = listOf("str", "dex", "con", "int", "wis", "cha")

        /** `floorDiv`, so odd low scores round down the way D&D expects (7 -> -2). */
        fun modifierOf(score: Int): Int = TODO("implemented by engine-character")
    }
}
