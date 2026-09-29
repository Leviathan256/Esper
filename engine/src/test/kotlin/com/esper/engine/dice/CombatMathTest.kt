package com.esper.engine.dice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A scripted [RandomSource] for tests that need an exact roll rather than a
 * seeded-but-opaque one. `nextInt` returns the next value from [ints] each call
 * (0-indexed, so a "natural 20" is scripted as 19).
 */
private class ScriptedRandomSource(private val ints: List<Int>) : RandomSource {
    private var index = 0

    override fun nextInt(bound: Int): Int {
        check(index < ints.size) { "ScriptedRandomSource exhausted its script" }
        return ints[index++]
    }

    override fun nextDouble(): Double = throw UnsupportedOperationException("not scripted")
}

class CombatMathTest {

    @Test
    fun `forced natural 20 is always a critical hit regardless of AC`() {
        val rng = ScriptedRandomSource(listOf(19)) // d20 = 19 + 1 = 20
        val roll = CombatMath.rollAttack(attackBonus = 0, targetArmorClass = 999, rng = rng)
        assertEquals(20, roll.d20)
        assertTrue(roll.critical)
        assertTrue(roll.hit)
        assertFalse(roll.fumble)
    }

    @Test
    fun `forced natural 1 is always a fumble regardless of attack bonus`() {
        val rng = ScriptedRandomSource(listOf(0)) // d20 = 0 + 1 = 1
        val roll = CombatMath.rollAttack(attackBonus = 50, targetArmorClass = 1, rng = rng)
        assertEquals(1, roll.d20)
        assertTrue(roll.fumble)
        assertFalse(roll.hit)
        assertFalse(roll.critical)
    }

    @Test
    fun `total is always d20 plus attackBonus`() {
        val rng = ScriptedRandomSource(listOf(9)) // d20 = 10
        val roll = CombatMath.rollAttack(attackBonus = 7, targetArmorClass = 15, rng = rng)
        assertEquals(17, roll.total)
    }

    @Test
    fun `ordinary roll hits iff total is at least the armor class`() {
        // d20 = 10, bonus 5 -> total 15. AC 15 -> hit (>=). AC 16 -> miss.
        val hitRng = ScriptedRandomSource(listOf(9))
        val hitRoll = CombatMath.rollAttack(attackBonus = 5, targetArmorClass = 15, rng = hitRng)
        assertTrue(hitRoll.hit)
        assertFalse(hitRoll.critical)
        assertFalse(hitRoll.fumble)

        val missRng = ScriptedRandomSource(listOf(9))
        val missRoll = CombatMath.rollAttack(attackBonus = 5, targetArmorClass = 16, rng = missRng)
        assertFalse(missRoll.hit)
    }

    @Test
    fun `rollCritical of 1d8 plus 2 lies in 4 to 18 across seeded rolls`() {
        val damage = DiceExpr.parse("1d8+2")
        val rng = SeededRandom(55L)
        repeat(5_000) {
            val result = CombatMath.rollDamage(damage, critical = true, rng = rng)
            assertTrue(result in 4..18, "critical damage $result out of [4,18]")
        }
    }

    @Test
    fun `rollDamage never returns below 0 even with a large negative modifier`() {
        val damage = DiceExpr(count = 1, sides = 4, modifier = -100)
        val rng = SeededRandom(3L)
        repeat(1_000) {
            assertTrue(CombatMath.rollDamage(damage, critical = false, rng = rng) >= 0)
            assertTrue(CombatMath.rollDamage(damage, critical = true, rng = rng) >= 0)
        }
    }

    @Test
    fun `rollDamage on a non-critical hit uses the plain roll not the critical one`() {
        // 1d1+0 always rolls exactly 1 on a normal hit, 2 on a critical (two dice).
        val damage = DiceExpr(count = 1, sides = 1, modifier = 0)
        val rng = SeededRandom(11L)
        assertEquals(1, CombatMath.rollDamage(damage, critical = false, rng = rng))
        assertEquals(2, CombatMath.rollDamage(damage, critical = true, rng = rng))
    }
}
