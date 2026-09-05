package com.esper.engine.character

import com.esper.engine.content.JobDefinition
import com.esper.engine.content.MonsterDefinition
import com.esper.engine.stats.AbilityScores
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProgressionTest {

    private val baseScores = AbilityScores(str = 14, dex = 12, con = 13, int = 10, wis = 10, cha = 10)

    private val squire = JobDefinition(
        schemaVersion = 1,
        id = "squire",
        displayName = "Squire",
        hitDie = "1d10",
        baseArmorClass = 12,
        attackAbility = "str",
        damage = "1d8",
        speed = 8,
        moveRangeCells = 4,
        unlocks = listOf("knight"),
        jobPointsToMaster = 100,
    )

    private val knight = JobDefinition(
        schemaVersion = 1,
        id = "knight",
        displayName = "Knight",
        hitDie = "1d10",
        baseArmorClass = 15,
        attackAbility = "str",
        damage = "1d10+1",
        speed = 7,
        moveRangeCells = 4,
        prerequisites = listOf("squire"),
        jobPointsToMaster = 200,
    )

    private val goblin = MonsterDefinition(
        schemaVersion = 1,
        id = "goblin",
        displayName = "Goblin",
        maxHp = 7,
        armorClass = 13,
        attackBonus = 4,
        damage = "1d6+2",
        speed = 7,
        moveRangeCells = 3,
        xpReward = 50,
        jobPointsReward = 2,
    )

    private val wolf = MonsterDefinition(
        schemaVersion = 1,
        id = "wolf",
        displayName = "Wolf",
        maxHp = 9,
        armorClass = 12,
        attackBonus = 3,
        damage = "1d6+1",
        speed = 12,
        moveRangeCells = 6,
        xpReward = 30,
        jobPointsReward = 3,
    )

    private fun freshState(currentJobId: String = "squire") = CharacterState(
        name = "Ramza",
        baseScores = baseScores,
        currentJobId = currentJobId,
    )

    @Test
    fun `levelForXp matches thresholds and crosses exactly at the boundary`() {
        assertEquals(1, Progression.levelForXp(0))
        assertEquals(1, Progression.levelForXp(99))
        assertEquals(2, Progression.levelForXp(100))
        assertEquals(2, Progression.levelForXp(299))
        assertEquals(3, Progression.levelForXp(300))
        assertEquals(3, Progression.levelForXp(599))
        assertEquals(4, Progression.levelForXp(600))
        assertEquals(4, Progression.levelForXp(999))
        assertEquals(5, Progression.levelForXp(1000))
        assertEquals(5, Progression.levelForXp(50_000))
    }

    @Test
    fun `awardVictory sums xp, adds job points to the current job only, and records the bestiary`() {
        val state = freshState()
        val after = Progression.awardVictory(state, listOf(goblin, goblin, wolf))

        assertEquals(130, after.xp) // 50 + 50 + 30
        assertEquals(7, after.jobPoints["squire"]) // 2 + 2 + 3
        assertTrue(after.jobPoints.keys.none { it != "squire" }, "job points leaked to a job other than the current one")
        assertEquals(2, after.bestiary["goblin"]?.timesDefeated)
        assertEquals(1, after.bestiary["wolf"]?.timesDefeated)
        assertEquals(2, Progression.levelForXp(after.xp))
        assertEquals(after.level, Progression.levelForXp(after.xp))
    }

    @Test
    fun `awardVictory accumulates job points and bestiary counts across multiple calls`() {
        var state = freshState()
        state = Progression.awardVictory(state, listOf(goblin))
        state = Progression.awardVictory(state, listOf(goblin, wolf))

        assertEquals(2, state.bestiary["goblin"]?.timesDefeated)
        assertEquals(1, state.bestiary["wolf"]?.timesDefeated)
        assertEquals(7, state.jobPoints["squire"])
        assertEquals(130, state.xp)
    }

    @Test
    fun `awardVictory only credits the job the character is currently in`() {
        val state = freshState(currentJobId = "knight")
        val after = Progression.awardVictory(state, listOf(goblin))
        assertEquals(2, after.jobPoints["knight"])
        assertFalse(after.jobPoints.containsKey("squire"))
    }

    @Test
    fun `recomputeUnlocks unlocks knight only once squire job points reach jobPointsToMaster`() {
        val jobs = listOf(squire, knight)

        val notYet = freshState().copy(jobPoints = mapOf("squire" to 99))
        val stillLocked = Progression.recomputeUnlocks(notYet, jobs)
        assertFalse("knight" in stillLocked.unlockedJobIds, "knight unlocked before squire reached mastery")

        val exactlyAtThreshold = freshState().copy(jobPoints = mapOf("squire" to 100))
        val unlocked = Progression.recomputeUnlocks(exactlyAtThreshold, jobs)
        assertTrue("knight" in unlocked.unlockedJobIds, "knight not unlocked once squire reached mastery")
    }
}
