package com.esper.engine.character

import com.esper.engine.content.JobDefinition
import com.esper.engine.content.MonsterDefinition

/** XP, job points, unlocks and the bestiary. Every function here is pure. */
object Progression {
    /** Thresholds for levels 1..5. */
    val XP_THRESHOLDS: List<Int> = listOf(0, 100, 300, 600, 1000)

    fun levelForXp(xp: Int): Int {
        var level = 1
        for (i in XP_THRESHOLDS.indices) {
            if (xp >= XP_THRESHOLDS[i]) level = i + 1
        }
        return level
    }

    /**
     * Adds xp, adds `jobPointsReward` to the CURRENT job, increments a bestiary
     * entry per defeated monster instance, and recomputes level. Returns a new state.
     */
    fun awardVictory(
        state: CharacterState,
        defeated: List<MonsterDefinition>,
    ): CharacterState {
        if (defeated.isEmpty()) return state

        val xpGained = defeated.sumOf { it.xpReward }
        val jobPointsGained = defeated.sumOf { it.jobPointsReward }

        val newXp = state.xp + xpGained
        val newJobPoints = state.jobPoints.toMutableMap()
        newJobPoints[state.currentJobId] = (newJobPoints[state.currentJobId] ?: 0) + jobPointsGained

        val newBestiary = state.bestiary.toMutableMap()
        for (monster in defeated) {
            val existing = newBestiary[monster.id]
            newBestiary[monster.id] = BestiaryEntry(
                monsterId = monster.id,
                timesDefeated = (existing?.timesDefeated ?: 0) + 1,
            )
        }

        return state.copy(
            xp = newXp,
            jobPoints = newJobPoints,
            bestiary = newBestiary,
            level = levelForXp(newXp),
        )
    }

    /** A job unlocks once every prerequisite has jobPoints >= that job's jobPointsToMaster. */
    fun recomputeUnlocks(state: CharacterState, jobs: List<JobDefinition>): CharacterState {
        val jobsById = jobs.associateBy { it.id }
        val newlyUnlocked = jobs.filter { job ->
            job.prerequisites.all { prereqId ->
                val prereqJob = jobsById[prereqId] ?: return@all false
                (state.jobPoints[prereqId] ?: 0) >= prereqJob.jobPointsToMaster
            }
        }.map { it.id }
        return state.copy(unlockedJobIds = state.unlockedJobIds + newlyUnlocked)
    }
}
