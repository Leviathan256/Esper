package com.esper.engine.combat

import com.esper.engine.dice.SeededRandom
import com.esper.engine.geometry.HexCoord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The discrete ATB tick model: §3.1 of the spec, exactly. */
class AtbOrderTest {

    /** Advances one full turn (advance + Wait) and returns who acted. */
    private fun takeTurn(engine: CombatEngine): String {
        val actor = engine.advanceToNextActor()!!
        engine.submitAction(CombatAction.Wait)
        return actor.id
    }

    @Test
    fun `a unit at speed 12 acts roughly twice for every act of a unit at speed 6`() {
        val board = CombatFixtures.board()
        val fast = CombatFixtures.unit("A", true, stats = CombatFixtures.stats(speed = 12))
        val slow = CombatFixtures.unit("B", true, position = HexCoord(1, 0), stats = CombatFixtures.stats(speed = 6))
        val engine = CombatEngine(board, listOf(fast, slow), SeededRandom(1))

        val sequence = (1..30).map { takeTurn(engine) }

        val fastCount = sequence.count { it == "A" }
        val slowCount = sequence.count { it == "B" }
        assertEquals(30, fastCount + slowCount)
        // Speeds are exactly 2:1, so over a long enough run the ratio must match closely.
        val ratio = fastCount.toDouble() / slowCount
        assertTrue(ratio in 1.5..2.5, "expected roughly 2:1, got $fastCount:$slowCount")
    }

    @Test
    fun `acting subtracts GAUGE_MAX rather than resetting to zero — overflow carries`() {
        val board = CombatFixtures.board()
        val fast = CombatFixtures.unit(
            "A", true, stats = CombatFixtures.stats(speed = 37), atbGauge = 90.0,
        )
        val engine = CombatEngine(board, listOf(fast), SeededRandom(1))

        // 90 + 37 = 127 >= 100 on the very first tick.
        val actor = engine.advanceToNextActor()!!
        assertEquals("A", actor.id)
        assertEquals(127.0, actor.atbGauge, 1e-9)

        engine.submitAction(CombatAction.Wait)
        // 127 - 100 = 27, not 0.
        assertEquals(27.0, engine.unit("A")!!.atbGauge, 1e-9)
    }

    @Test
    fun `ties within the same tick break on the lowest unit id`() {
        val board = CombatFixtures.board()
        // Identical speed, identical starting gauge: they cross GAUGE_MAX on the same tick.
        val b = CombatFixtures.unit("B", true, stats = CombatFixtures.stats(speed = 100))
        val a = CombatFixtures.unit("A", true, position = HexCoord(1, 0), stats = CombatFixtures.stats(speed = 100))
        // Constructed with B before A to prove the tie-break is by id, not by list order.
        val engine = CombatEngine(board, listOf(b, a), SeededRandom(1))

        val actor = engine.advanceToNextActor()!!
        assertEquals("A", actor.id)
    }

    @Test
    fun `MAX_TICKS_PER_TURN returns null when every living unit has speed 0`() {
        val board = CombatFixtures.board()
        val a = CombatFixtures.unit("A", true, stats = CombatFixtures.stats(speed = 0))
        val b = CombatFixtures.unit("B", false, position = HexCoord(1, 0), stats = CombatFixtures.stats(speed = 0))
        val engine = CombatEngine(board, listOf(a, b), SeededRandom(1))

        assertNull(engine.advanceToNextActor())
    }

    @Test
    fun `a dead unit never becomes the actor and its gauge never advances`() {
        val board = CombatFixtures.board()
        val alive = CombatFixtures.unit("alive", true, stats = CombatFixtures.stats(speed = 1))
        val dead = CombatFixtures.unit(
            "dead", true, position = HexCoord(1, 0),
            stats = CombatFixtures.stats(speed = 1000), currentHp = 0,
        )
        val engine = CombatEngine(board, listOf(alive, dead), SeededRandom(1))

        repeat(5) {
            val actor = engine.advanceToNextActor()
            assertTrue(actor == null || actor.id == "alive")
            if (actor != null) engine.submitAction(CombatAction.Wait)
        }
        assertEquals(0.0, engine.unit("dead")!!.atbGauge, 1e-9)
    }

    @Test
    fun `gauge does not advance for anyone while an action resolves`() {
        val board = CombatFixtures.board()
        val a = CombatFixtures.unit("A", true, stats = CombatFixtures.stats(speed = 100))
        val b = CombatFixtures.unit("B", true, position = HexCoord(1, 0), stats = CombatFixtures.stats(speed = 5))
        val engine = CombatEngine(board, listOf(a, b), SeededRandom(1))

        val actor = engine.advanceToNextActor()!!
        assertEquals("A", actor.id)
        val bGaugeBeforeAction = engine.unit("B")!!.atbGauge
        engine.submitAction(CombatAction.Wait)
        // Only A's gauge changed; B's is untouched by A's action resolving.
        assertEquals(bGaugeBeforeAction, engine.unit("B")!!.atbGauge, 1e-9)
    }
}
