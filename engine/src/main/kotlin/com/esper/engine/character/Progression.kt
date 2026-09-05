package com.esper.engine.character

import com.esper.engine.content.JobDefinition
import com.esper.engine.content.MonsterDefinition

/** XP, job points, unlocks and the bestiary. Every function here is pure. */
object Progression {
    /** Thresholds for levels 1..5. */
    val XP_THRESHOLDS: List<Int> = listOf(0, 100, 300, 600, 1000)

    fun levelForXp(xp: Int): Int = TODO("implemented by engine-character")

    /**
     * Adds xp, adds `jobPointsReward` to the CURRENT job, increments a bestiary
     * entry per defeated monster instance, and recomputes level. Returns a new state.
     */
    fun awardVictory(
        state: CharacterState,
        defeated: List<MonsterDefinition>,
    ): CharacterState = TODO("implemented by engine-character")

    /** A job unlocks once every prerequisite has jobPoints >= that job's jobPointsToMaster. */
    fun recomputeUnlocks(state: CharacterState, jobs: List<JobDefinition>): CharacterState =
        TODO("implemented by engine-character")
}
