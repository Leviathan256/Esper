package com.esper.engine.stats

import com.esper.engine.content.JobDefinition
import com.esper.engine.dice.DiceExpr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StatCalculatorTest {

    private val job = JobDefinition(
        schemaVersion = 1,
        id = "test_job",
        displayName = "Test Job",
        hitDie = "1d10",
        baseArmorClass = 12,
        attackAbility = "str",
        damage = "1d8+1",
        speed = 8,
        moveRangeCells = 4,
        attackRangeCells = 1,
        statGrowth = mapOf("str" to 1, "con" to 1),
    )

    // str 14 (+2), dex 12 (+1), con 13 (+1), int/wis/cha 10 (+0)
    private val base = AbilityScores(str = 14, dex = 12, con = 13, int = 10, wis = 10, cha = 10)

    @Test
    fun `modifierOf matches D and D for scores 1 through 20`() {
        assertEquals(-5, AbilityScores.modifierOf(1))
        assertEquals(-1, AbilityScores.modifierOf(8))
        assertEquals(0, AbilityScores.modifierOf(10))
        assertEquals(0, AbilityScores.modifierOf(11))
        assertEquals(2, AbilityScores.modifierOf(14))
        assertEquals(5, AbilityScores.modifierOf(20))
    }

    @Test
    fun `scoresAtLevel applies growth times level minus one`() {
        assertEquals(base, StatCalculator.scoresAtLevel(base, job, level = 1))

        val atLevel2 = StatCalculator.scoresAtLevel(base, job, level = 2)
        assertEquals(15, atLevel2.str)
        assertEquals(14, atLevel2.con)
        assertEquals(12, atLevel2.dex)

        val atLevel5 = StatCalculator.scoresAtLevel(base, job, level = 5)
        assertEquals(18, atLevel5.str)
        assertEquals(17, atLevel5.con)
        assertEquals(12, atLevel5.dex)
    }

    @Test
    fun `derive matches a hand-computed fixture at level 1`() {
        val derived = StatCalculator.derive(base, job, level = 1)
        assertEquals(11, derived.maxHp)
        assertEquals(13, derived.armorClass)
        assertEquals(4, derived.attackBonus)
        assertEquals(DiceExpr(1, 8, 3), derived.damage)
        assertEquals(8, derived.speed)
        assertEquals(4, derived.moveRangeCells)
        assertEquals(1, derived.attackRangeCells)
    }

    @Test
    fun `derive matches a hand-computed fixture at level 2`() {
        val derived = StatCalculator.derive(base, job, level = 2)
        assertEquals(20, derived.maxHp)
        assertEquals(13, derived.armorClass)
        assertEquals(4, derived.attackBonus)
        assertEquals(DiceExpr(1, 8, 3), derived.damage)
    }

    @Test
    fun `derive matches a hand-computed fixture at level 5`() {
        val derived = StatCalculator.derive(base, job, level = 5)
        assertEquals(49, derived.maxHp)
        assertEquals(13, derived.armorClass)
        assertEquals(7, derived.attackBonus)
        assertEquals(DiceExpr(1, 8, 5), derived.damage)
    }

    @Test
    fun `derive maxHp is never below 1 even with a negative CON`() {
        val frailJob = job.copy(hitDie = "1d4", statGrowth = emptyMap())
        val frailBase = AbilityScores(str = 10, dex = 10, con = 1, int = 10, wis = 10, cha = 10)
        val derived = StatCalculator.derive(frailBase, frailJob, level = 1)
        assertTrue(derived.maxHp >= 1, "maxHp ${derived.maxHp} fell below the floor of 1")
        assertEquals(1, derived.maxHp)
    }

    @Test
    fun `proficiencyBonus is 2 at levels 1 through 4 and 3 at level 5`() {
        assertEquals(2, StatCalculator.proficiencyBonus(1))
        assertEquals(2, StatCalculator.proficiencyBonus(2))
        assertEquals(2, StatCalculator.proficiencyBonus(3))
        assertEquals(2, StatCalculator.proficiencyBonus(4))
        assertEquals(3, StatCalculator.proficiencyBonus(5))
    }
}
