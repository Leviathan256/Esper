package com.esper.engine.combat

import com.esper.engine.dice.DiceExpr
import com.esper.engine.dice.RandomSource
import com.esper.engine.dice.SeededRandom
import com.esper.engine.geometry.GeoPoint
import com.esper.engine.geometry.HexBoard
import com.esper.engine.geometry.HexCoord
import com.esper.engine.stats.DerivedStats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Shared fixtures for the combat package's test files (`AtbOrderTest`,
 * `SimpleAiTest` reference these too — same package, no import needed).
 */
object CombatFixtures {
    fun board(radiusCells: Int = 6): HexBoard = HexBoard(GeoPoint(0.0, 0.0), radiusCells)

    fun stats(
        maxHp: Int = 20,
        armorClass: Int = 12,
        attackBonus: Int = 5,
        damage: DiceExpr = DiceExpr(1, 6, 0),
        speed: Int = 10,
        moveRangeCells: Int = 4,
        attackRangeCells: Int = 1,
    ): DerivedStats = DerivedStats(
        maxHp = maxHp,
        armorClass = armorClass,
        attackBonus = attackBonus,
        damage = damage,
        speed = speed,
        moveRangeCells = moveRangeCells,
        attackRangeCells = attackRangeCells,
    )

    fun unit(
        id: String,
        playerControlled: Boolean,
        position: HexCoord = HexCoord.ORIGIN,
        stats: DerivedStats = stats(),
        currentHp: Int = stats.maxHp,
        atbGauge: Double = 0.0,
    ): CombatUnit = CombatUnit(
        id = id,
        displayName = id,
        playerControlled = playerControlled,
        sourceId = id,
        stats = stats,
        currentHp = currentHp,
        position = position,
        atbGauge = atbGauge,
    )

    /**
     * A [RandomSource] test double that returns a scripted d20 sequence (one entry
     * consumed per `nextInt(20)` call, the last entry repeating once exhausted) and
     * a fixed per-die value for every other roll (damage dice) — for tests that
     * need a guaranteed hit/crit/miss rather than gambling on a seed.
     */
    class ScriptedRandomSource(
        private val d20Rolls: List<Int>,
        private val perDieRoll: Int = 3,
    ) : RandomSource {
        private var d20Index = 0

        override fun nextInt(bound: Int): Int {
            if (bound == 20) {
                val roll = d20Rolls.getOrElse(d20Index) { d20Rolls.last() }
                d20Index++
                return (roll - 1).coerceIn(0, 19)
            }
            return (perDieRoll - 1).coerceIn(0, bound - 1)
        }

        override fun nextDouble(): Double = 0.5
    }
}

class CombatEngineTest {

    @Test
    fun `units is an immutable snapshot reflecting current state`() {
        val board = CombatFixtures.board()
        val player = CombatFixtures.unit("player", true, position = HexCoord.ORIGIN)
        val monster = CombatFixtures.unit("goblin#0", false, position = HexCoord(1, 0))
        val engine = CombatEngine(board, listOf(player, monster), SeededRandom(1))

        val snapshot = engine.units
        assertEquals(listOf("player", "goblin#0"), snapshot.map { it.id })

        // Mutating the returned list must not be possible to observe back on the engine.
        assertEquals(2, engine.units.size)
        assertEquals(player, engine.unit("player"))
        assertNull(engine.unit("nobody"))
    }

    @Test
    fun `submitAction with no current actor rejects for id none and mutates nothing`() {
        val board = CombatFixtures.board()
        val player = CombatFixtures.unit("player", true)
        val monster = CombatFixtures.unit("goblin#0", false, position = HexCoord(1, 0))
        val engine = CombatEngine(board, listOf(player, monster), SeededRandom(1))

        val before = engine.units
        val events = engine.submitAction(CombatAction.Wait)

        assertEquals(1, events.size)
        val rejected = events[0] as CombatEvent.ActionRejected
        assertEquals("none", rejected.unitId)
        assertEquals(before, engine.units)
    }

    @Test
    fun `Move to an out-of-range cell is rejected and mutates nothing`() {
        val board = CombatFixtures.board(radiusCells = 10)
        val player = CombatFixtures.unit(
            "player", true, position = HexCoord.ORIGIN,
            stats = CombatFixtures.stats(moveRangeCells = 2), atbGauge = CombatEngine.GAUGE_MAX,
        )
        val engine = CombatEngine(board, listOf(player), SeededRandom(1))
        engine.advanceToNextActor()

        val before = engine.units
        val events = engine.submitAction(CombatAction.Move(HexCoord(5, 0)))

        assertEquals(1, events.size)
        assertTrue(events[0] is CombatEvent.ActionRejected)
        assertEquals(before, engine.units)
    }

    @Test
    fun `Move to a cell occupied by a living unit is rejected`() {
        val board = CombatFixtures.board(radiusCells = 10)
        val player = CombatFixtures.unit(
            "player", true, position = HexCoord.ORIGIN, atbGauge = CombatEngine.GAUGE_MAX,
        )
        val ally = CombatFixtures.unit("ally", true, position = HexCoord(1, 0))
        val engine = CombatEngine(board, listOf(player, ally), SeededRandom(1))
        engine.advanceToNextActor()

        val before = engine.units
        val events = engine.submitAction(CombatAction.Move(HexCoord(1, 0)))

        assertTrue(events.single() is CombatEvent.ActionRejected)
        assertEquals(before, engine.units)
    }

    @Test
    fun `Move to an off-board cell is rejected`() {
        val board = CombatFixtures.board(radiusCells = 2)
        val player = CombatFixtures.unit(
            "player", true, position = HexCoord.ORIGIN,
            stats = CombatFixtures.stats(moveRangeCells = 20), atbGauge = CombatEngine.GAUGE_MAX,
        )
        val engine = CombatEngine(board, listOf(player), SeededRandom(1))
        engine.advanceToNextActor()

        val before = engine.units
        val events = engine.submitAction(CombatAction.Move(HexCoord(100, 0)))

        assertTrue(events.single() is CombatEvent.ActionRejected)
        assertEquals(before, engine.units)
    }

    @Test
    fun `Move behind a solid ring of blockers is rejected`() {
        val board = CombatFixtures.board(radiusCells = 6)
        val player = CombatFixtures.unit(
            "player", true, position = HexCoord.ORIGIN,
            stats = CombatFixtures.stats(moveRangeCells = 4), atbGauge = CombatEngine.GAUGE_MAX,
        )
        // A solid ring at radius 1 walls the player in.
        val wallers = HexCoord.ORIGIN.neighbors().mapIndexed { index, hex ->
            CombatFixtures.unit("wall#$index", true, position = hex)
        }
        val engine = CombatEngine(board, listOf(player) + wallers, SeededRandom(1))
        engine.advanceToNextActor()

        val before = engine.units
        // Just past the wall, well within nominal move range.
        val events = engine.submitAction(CombatAction.Move(HexCoord(2, 0)))

        assertTrue(events.single() is CombatEvent.ActionRejected)
        assertEquals(before, engine.units)
    }

    @Test
    fun `legal Move is accepted, updates position, and spends gauge`() {
        val board = CombatFixtures.board(radiusCells = 10)
        val player = CombatFixtures.unit(
            "player", true, position = HexCoord.ORIGIN, atbGauge = CombatEngine.GAUGE_MAX + 3.0,
        )
        val engine = CombatEngine(board, listOf(player), SeededRandom(1))
        engine.advanceToNextActor()

        val events = engine.submitAction(CombatAction.Move(HexCoord(1, 0)))

        val moved = events.single() as CombatEvent.Moved
        assertEquals("player", moved.unitId)
        assertEquals(HexCoord.ORIGIN, moved.from)
        assertEquals(HexCoord(1, 0), moved.to)
        assertEquals(HexCoord(1, 0), engine.unit("player")!!.position)
        // Overflow carries: gauge does not reset to zero.
        assertEquals(3.0, engine.unit("player")!!.atbGauge, 1e-9)
    }

    @Test
    fun `Attack on an out-of-range target is rejected and mutates nothing`() {
        val board = CombatFixtures.board(radiusCells = 10)
        val player = CombatFixtures.unit(
            "player", true, position = HexCoord.ORIGIN, atbGauge = CombatEngine.GAUGE_MAX,
        )
        val monster = CombatFixtures.unit("goblin#0", false, position = HexCoord(5, 0))
        val engine = CombatEngine(board, listOf(player, monster), SeededRandom(1))
        engine.advanceToNextActor()

        val before = engine.units
        val events = engine.submitAction(CombatAction.Attack("goblin#0"))

        assertTrue(events.single() is CombatEvent.ActionRejected)
        assertEquals(before, engine.units)
    }

    @Test
    fun `Attack on an unknown or dead target is rejected`() {
        val board = CombatFixtures.board(radiusCells = 10)
        val player = CombatFixtures.unit(
            "player", true, position = HexCoord.ORIGIN, atbGauge = CombatEngine.GAUGE_MAX,
        )
        val deadMonster = CombatFixtures.unit(
            "goblin#0", false, position = HexCoord(1, 0), currentHp = 0,
        )
        val engine = CombatEngine(board, listOf(player, deadMonster), SeededRandom(1))
        engine.advanceToNextActor()

        assertTrue(engine.submitAction(CombatAction.Attack("goblin#0")).single() is CombatEvent.ActionRejected)
        assertTrue(engine.submitAction(CombatAction.Attack("nobody")).single() is CombatEvent.ActionRejected)
    }

    @Test
    fun `in-range Attack emits Attacked with a consistent targetHpAfter, HP never negative`() {
        val board = CombatFixtures.board(radiusCells = 10)
        val player = CombatFixtures.unit(
            "player", true, position = HexCoord.ORIGIN,
            stats = CombatFixtures.stats(attackBonus = 5, damage = DiceExpr(1, 4, 0)),
            atbGauge = CombatEngine.GAUGE_MAX,
        )
        val monster = CombatFixtures.unit(
            "goblin#0", false, position = HexCoord(1, 0),
            stats = CombatFixtures.stats(armorClass = 5, maxHp = 3), currentHp = 3,
        )
        // A forced natural 20: always hits regardless of AC, and a fixed 4 per damage die.
        val engine = CombatEngine(
            board, listOf(player, monster),
            CombatFixtures.ScriptedRandomSource(d20Rolls = listOf(20), perDieRoll = 4),
        )
        engine.advanceToNextActor()

        val events = engine.submitAction(CombatAction.Attack("goblin#0"))
        val attacked = events.filterIsInstance<CombatEvent.Attacked>().single()

        assertEquals("player", attacked.attackerId)
        assertEquals("goblin#0", attacked.targetId)
        assertTrue(attacked.roll.critical)
        assertTrue(attacked.roll.hit)
        assertTrue(attacked.targetHpAfter >= 0)
        assertEquals(engine.unit("goblin#0")!!.currentHp, attacked.targetHpAfter)
        val expectedHp = maxOf(0, 3 - attacked.damage)
        assertEquals(expectedHp, attacked.targetHpAfter)
    }

    @Test
    fun `Defeated is emitted exactly when hp crosses to zero or below`() {
        val board = CombatFixtures.board(radiusCells = 10)
        val player = CombatFixtures.unit(
            "player", true, position = HexCoord.ORIGIN,
            stats = CombatFixtures.stats(attackBonus = 5, damage = DiceExpr(1, 8, 0)),
            atbGauge = CombatEngine.GAUGE_MAX,
        )
        val monster = CombatFixtures.unit(
            "goblin#0", false, position = HexCoord(1, 0),
            stats = CombatFixtures.stats(armorClass = 1, maxHp = 1), currentHp = 1,
        )
        // Forced natural 20 crit: always hits and guarantees lethal damage on 1 hp.
        val engine = CombatEngine(
            board, listOf(player, monster),
            CombatFixtures.ScriptedRandomSource(d20Rolls = listOf(20), perDieRoll = 5),
        )
        engine.advanceToNextActor()

        val events = engine.submitAction(CombatAction.Attack("goblin#0"))
        val attacked = events.filterIsInstance<CombatEvent.Attacked>().single()
        val defeated = events.filterIsInstance<CombatEvent.Defeated>()

        assertEquals(0, attacked.targetHpAfter)
        assertEquals(1, defeated.size)
        assertEquals("goblin#0", defeated.single().unitId)
        assertFalse(engine.unit("goblin#0")!!.alive)
    }

    @Test
    fun `a missed attack deals zero damage and leaves target hp unchanged`() {
        val board = CombatFixtures.board(radiusCells = 10)
        val player = CombatFixtures.unit(
            "player", true, position = HexCoord.ORIGIN,
            stats = CombatFixtures.stats(attackBonus = 0),
            atbGauge = CombatEngine.GAUGE_MAX,
        )
        val monster = CombatFixtures.unit(
            "goblin#0", false, position = HexCoord(1, 0),
            stats = CombatFixtures.stats(armorClass = 20, maxHp = 10), currentHp = 10,
        )
        // Forced natural 1: always misses regardless of bonus.
        val engine = CombatEngine(
            board, listOf(player, monster),
            CombatFixtures.ScriptedRandomSource(d20Rolls = listOf(1)),
        )
        engine.advanceToNextActor()

        val attacked = engine.submitAction(CombatAction.Attack("goblin#0"))
            .filterIsInstance<CombatEvent.Attacked>().single()

        assertTrue(attacked.roll.fumble)
        assertFalse(attacked.roll.hit)
        assertEquals(0, attacked.damage)
        assertEquals(10, attacked.targetHpAfter)
    }

    @Test
    fun `BattleEnded VICTORY fires exactly once and result stays stable afterwards`() {
        val board = CombatFixtures.board(radiusCells = 10)
        val player = CombatFixtures.unit(
            "player", true, position = HexCoord.ORIGIN,
            stats = CombatFixtures.stats(attackBonus = 30, damage = DiceExpr(4, 8, 10), speed = 20),
        )
        val monster = CombatFixtures.unit(
            "slime", false, position = HexCoord(1, 0),
            stats = CombatFixtures.stats(armorClass = 5, maxHp = 4, speed = 1, attackBonus = -10),
            currentHp = 4,
        )
        val engine = CombatEngine(board, listOf(player, monster), SeededRandom(99))

        var battleEndedCount = 0
        var iterations = 0
        while (!engine.isOver() && iterations < 1000) {
            iterations++
            val actor = engine.advanceToNextActor() ?: break
            val action = if (actor.playerControlled) {
                CombatAction.Attack("slime")
            } else {
                SimpleAi.chooseAction(engine, actor)
            }
            val events = engine.submitAction(action)
            battleEndedCount += events.count { it is CombatEvent.BattleEnded }
        }

        assertEquals(BattleResult.VICTORY, engine.result())
        assertEquals(1, battleEndedCount)
        assertTrue(engine.isOver())

        // Calling further is inert and result() stays the same.
        assertNull(engine.advanceToNextActor())
        assertEquals(BattleResult.VICTORY, engine.result())
        val rejected = engine.submitAction(CombatAction.Wait).single() as CombatEvent.ActionRejected
        assertEquals("none", rejected.unitId)
    }

    @Test
    fun `a hopeless matchup reaches DEFEAT`() {
        val board = CombatFixtures.board(radiusCells = 10)
        val player = CombatFixtures.unit(
            "player", true, position = HexCoord.ORIGIN,
            stats = CombatFixtures.stats(armorClass = 5, maxHp = 3, speed = 10, attackBonus = -10),
            currentHp = 3,
        )
        val monster = CombatFixtures.unit(
            "wolf", false, position = HexCoord(1, 0),
            stats = CombatFixtures.stats(attackBonus = 30, damage = DiceExpr(4, 8, 10), speed = 20),
        )
        val engine = CombatEngine(board, listOf(player, monster), SeededRandom(3))

        var iterations = 0
        while (!engine.isOver() && iterations < 1000) {
            iterations++
            val actor = engine.advanceToNextActor() ?: break
            val action = if (!actor.playerControlled) {
                SimpleAi.chooseAction(engine, actor)
            } else {
                CombatAction.Attack("wolf")
            }
            engine.submitAction(action)
        }

        assertEquals(BattleResult.DEFEAT, engine.result())
    }

    @Test
    fun `legalMoves and attackableTargets return empty for an unknown unit`() {
        val board = CombatFixtures.board()
        val engine = CombatEngine(board, listOf(CombatFixtures.unit("player", true)), SeededRandom(1))
        assertTrue(engine.legalMoves("nobody").isEmpty())
        assertTrue(engine.attackableTargets("nobody").isEmpty())
    }

    @Test
    fun `MAX_TICKS_PER_TURN guards an all-zero-speed deadlock`() {
        val board = CombatFixtures.board()
        val player = CombatFixtures.unit("player", true, stats = CombatFixtures.stats(speed = 0))
        val monster = CombatFixtures.unit(
            "goblin#0", false, position = HexCoord(1, 0), stats = CombatFixtures.stats(speed = 0),
        )
        val engine = CombatEngine(board, listOf(player, monster), SeededRandom(1))

        assertNull(engine.advanceToNextActor())
    }

    @Test
    fun `a dead unit is never returned by advanceToNextActor and never accrues gauge`() {
        val board = CombatFixtures.board()
        val player = CombatFixtures.unit("player", true, stats = CombatFixtures.stats(speed = 50))
        val deadAlly = CombatFixtures.unit(
            "ally", true, position = HexCoord(1, 0), stats = CombatFixtures.stats(speed = 50), currentHp = 0,
        )
        val monster = CombatFixtures.unit(
            "goblin#0", false, position = HexCoord(2, 0), stats = CombatFixtures.stats(speed = 1),
        )
        val engine = CombatEngine(board, listOf(player, deadAlly, monster), SeededRandom(1))

        val actor = engine.advanceToNextActor()
        assertNotNull(actor)
        assertEquals("player", actor!!.id)
        assertEquals(0.0, engine.unit("ally")!!.atbGauge, 1e-9)
    }
}
