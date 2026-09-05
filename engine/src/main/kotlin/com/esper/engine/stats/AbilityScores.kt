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
    fun score(key: String): Int = when (key) {
        "str" -> str
        "dex" -> dex
        "con" -> con
        "int" -> int
        "wis" -> wis
        "cha" -> cha
        else -> throw IllegalArgumentException("unknown ability score key: \"$key\"")
    }

    fun modifier(key: String): Int = modifierOf(score(key))

    /** Applies a job's `statGrowth` [times] over. */
    fun plus(growth: Map<String, Int>, times: Int): AbilityScores {
        if (growth.isEmpty() || times == 0) return this
        return AbilityScores(
            str = str + (growth["str"] ?: 0) * times,
            dex = dex + (growth["dex"] ?: 0) * times,
            con = con + (growth["con"] ?: 0) * times,
            int = int + (growth["int"] ?: 0) * times,
            wis = wis + (growth["wis"] ?: 0) * times,
            cha = cha + (growth["cha"] ?: 0) * times,
        )
    }

    companion object {
        val KEYS: List<String> = listOf("str", "dex", "con", "int", "wis", "cha")

        /** `floorDiv`, so odd low scores round down the way D&D expects (7 -> -2). */
        fun modifierOf(score: Int): Int = Math.floorDiv(score - 10, 2)
    }
}
