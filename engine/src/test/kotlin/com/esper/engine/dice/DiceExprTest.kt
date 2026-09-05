package com.esper.engine.dice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DiceExprTest {

    @Test
    fun `parse handles standard dice notation`() {
        assertEquals(DiceExpr(2, 6, 3), DiceExpr.parse("2d6+3"))
        assertEquals(DiceExpr(1, 8, 0), DiceExpr.parse("1d8"))
        assertEquals(DiceExpr(1, 6, -1), DiceExpr.parse("1d6-1"))
        assertEquals(DiceExpr(0, 0, 0), DiceExpr.parse("0"))
        assertEquals(DiceExpr(3, 4, 2), DiceExpr.parse(" 3d4 + 2 "))
    }

    @Test
    fun `parse rejects malformed notation`() {
        assertThrows(IllegalArgumentException::class.java) { DiceExpr.parse("d6") }
        assertThrows(IllegalArgumentException::class.java) { DiceExpr.parse("2x6") }
        assertThrows(IllegalArgumentException::class.java) { DiceExpr.parse("") }
    }

    @Test
    fun `parseOrNull returns null for the same malformed inputs`() {
        assertNull(DiceExpr.parseOrNull("d6"))
        assertNull(DiceExpr.parseOrNull("2x6"))
        assertNull(DiceExpr.parseOrNull(""))
    }

    @Test
    fun `parseOrNull mirrors parse for valid inputs`() {
        assertEquals(DiceExpr.parse("2d6+3"), DiceExpr.parseOrNull("2d6+3"))
    }

    @Test
    fun `roll of 2d6+3 lands in range and hits both bounds across many seeded rolls`() {
        val expr = DiceExpr.parse("2d6+3")
        var sawMin = false
        var sawMax = false
        val rng = SeededRandom(1234L)
        repeat(10_000) {
            val result = expr.roll(rng)
            assertTrue(result in 5..15, "roll $result out of [5,15]")
            if (result == 5) sawMin = true
            if (result == 15) sawMax = true
        }
        assertTrue(sawMin, "never rolled the minimum (5) across 10000 rolls")
        assertTrue(sawMax, "never rolled the maximum (15) across 10000 rolls")
    }

    @Test
    fun `roll of ZERO always returns zero`() {
        val rng = SeededRandom(7L)
        repeat(100) {
            assertEquals(0, DiceExpr.ZERO.roll(rng))
        }
    }

    @Test
    fun `SeededRandom reproduces an identical roll sequence across runs`() {
        // Pinned against java.util.Random(42).nextInt(20) which is a JDK-specified
        // 48-bit LCG, so this sequence is stable forever. A change to the RNG
        // contract (e.g. swapping the backing generator) must fail this loudly.
        val expected = listOf(10, 3, 8, 4, 10, 5, 5, 18, 19, 13)
        val rng = SeededRandom(42L)
        val sequence = List(10) { rng.nextInt(20) }
        assertEquals(expected, sequence)

        // A fresh instance with the same seed reproduces the same sequence.
        val rngAgain = SeededRandom(42L)
        val sequenceAgain = List(10) { rngAgain.nextInt(20) }
        assertEquals(expected, sequenceAgain)
    }

    @Test
    fun `rollCritical of 1d8+2 lies in 4 to 18 never 3 to 20`() {
        val expr = DiceExpr.parse("1d8+2")
        val rng = SeededRandom(99L)
        repeat(5_000) {
            val result = expr.rollCritical(rng)
            assertTrue(result in 4..18, "critical roll $result out of [4,18]")
        }
    }

    @Test
    fun `average is exact for a known expression`() {
        // 2d6+3: average of one d6 is 3.5, two dice is 7.0, plus modifier 3 = 10.0
        assertEquals(10.0, DiceExpr.parse("2d6+3").average, 1e-9)
        assertEquals(0.0, DiceExpr.ZERO.average, 1e-9)
    }

    @Test
    fun `toString round-trips through parse`() {
        assertEquals("1d8+2", DiceExpr(1, 8, 2).toString())
        assertEquals("0", DiceExpr.ZERO.toString())
        assertEquals("1d6-1", DiceExpr(1, 6, -1).toString())
        assertEquals(DiceExpr(1, 8, 2), DiceExpr.parse(DiceExpr(1, 8, 2).toString()))
        assertEquals(DiceExpr.ZERO, DiceExpr.parse(DiceExpr.ZERO.toString()))
    }

    @Test
    fun `a modifier that overflows Int fails the parse instead of being dropped`() {
        assertNull(DiceExpr.parseOrNull("1d6+99999999999"))
        assertNull(DiceExpr.parseOrNull("1d6-99999999999"))
        assertThrows<IllegalArgumentException> { DiceExpr.parse("1d6+99999999999") }
        // Absent modifier still parses as 0.
        assertEquals(DiceExpr(1, 6, 0), DiceExpr.parse("1d6"))
    }

    @Test
    fun `PATTERN matches the documented dice notation`() {
        assertTrue(DiceExpr.PATTERN.matches("1d8+2"))
        assertFalse(DiceExpr.PATTERN.matches("d6"))
    }
}
