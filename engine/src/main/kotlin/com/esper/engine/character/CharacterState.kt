package com.esper.engine.character

import com.esper.engine.stats.AbilityScores
import kotlinx.serialization.Serializable

/** One bestiary line: what it is, and how many of them you have put down. */
@Serializable
data class BestiaryEntry(val monsterId: String, val timesDefeated: Int = 0)

/**
 * The whole save file.
 *
 * Carries [schemaVersion] from day one so a schema change can migrate existing
 * characters instead of orphaning them — GAME_DESIGN's job-pipeline rule 5.
 * Abilities live on the character, not the job instance, FFT-style.
 */
@Serializable
data class CharacterState(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val name: String,
    val baseScores: AbilityScores,
    val currentJobId: String,
    val level: Int = 1,
    val xp: Int = 0,
    /** Per job id. */
    val jobPoints: Map<String, Int> = emptyMap(),
    val unlockedJobIds: Set<String> = emptySet(),
    /** Abilities live on the character, FFT-style, so they carry between jobs. */
    val learnedAbilityIds: Set<String> = emptySet(),
    val bestiary: Map<String, BestiaryEntry> = emptyMap(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
