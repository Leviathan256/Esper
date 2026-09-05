package com.esper.engine.combat

import com.esper.engine.dice.SeededRandom
import com.esper.engine.geometry.HexCoord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SimpleAiTest {

    @Test
    fun `attacks the lowest-HP living enemy in range`() {
        val board = CombatFixtures.board()
        val actor = CombatFixtures.unit("goblin#0", false, position = HexCoord.ORIGIN)
        val weakTarget = CombatFixtures.unit(
            "player", true, position = HexCoord(1, 0),
            stats = CombatFixtures.stats(maxHp = 20), currentHp = 5,
        )
        val strongTarget = CombatFixtures.unit(
            "ally", true, position = HexCoord(0, 1),
            stats = CombatFixtures.stats(maxHp = 20), currentHp = 15,
        )
        val engine = CombatEngine(board, listOf(actor, weakTarget, strongTarget), SeededRandom(1))

        val action = SimpleAi.chooseAction(engine, actor)

        assertEquals(CombatAction.Attack("player"), action)
    }

    @Test
    fun `attack ties on lowest HP are broken by lowest id`() {
        val board = CombatFixtures.board()
        val actor = CombatFixtures.unit("goblin#0", false, position = HexCoord.ORIGIN)
        val targetB = CombatFixtures.unit(
            "zzz", true, position = HexCoord(1, 0), stats = CombatFixtures.stats(maxHp = 20), currentHp = 5,
        )
        val targetA = CombatFixtures.unit(
            "aaa", true, position = HexCoord(0, 1), stats = CombatFixtures.stats(maxHp = 20), currentHp = 5,
        )
        val engine = CombatEngine(board, listOf(actor, targetB, targetA), SeededRandom(1))

        val action = SimpleAi.chooseAction(engine, actor)

        assertEquals(CombatAction.Attack("aaa"), action)
    }

    @Test
    fun `moves toward the nearest living enemy when none are in range`() {
        val board = CombatFixtures.board(radiusCells = 10)
        val actor = CombatFixtures.unit(
            "goblin#0", false, position = HexCoord.ORIGIN,
            stats = CombatFixtures.stats(moveRangeCells = 4, attackRangeCells = 1),
        )
        val farEnemy = CombatFixtures.unit("player", true, position = HexCoord(6, 0))
        val engine = CombatEngine(board, listOf(actor, farEnemy), SeededRandom(1))

        val action = SimpleAi.chooseAction(engine, actor)

        val move = action as CombatAction.Move
        // Moving straight along +q is the only way to strictly reduce distance to (6,0).
        assertEquals(HexCoord(4, 0), move.to)
        assertTrue(actor.position.distanceTo(move.to) <= actor.stats.moveRangeCells)
    }

    @Test
    fun `waits when already as close as reachable and out of attack range`() {
        val board = CombatFixtures.board(radiusCells = 10)
        // Boxed in by allies on every side, so the only reachable cell is its own.
        val actor = CombatFixtures.unit(
            "goblin#0", false, position = HexCoord.ORIGIN,
            stats = CombatFixtures.stats(moveRangeCells = 4, attackRangeCells = 1),
        )
        val wallers = HexCoord.ORIGIN.neighbors().mapIndexed { index, hex ->
            CombatFixtures.unit("wall#$index", false, position = hex)
        }
        val farEnemy = CombatFixtures.unit("player", true, position = HexCoord(6, 0))
        val engine = CombatEngine(board, listOf(actor, farEnemy) + wallers, SeededRandom(1))

        val action = SimpleAi.chooseAction(engine, actor)

        assertEquals(CombatAction.Wait, action)
    }

    @Test
    fun `waits when there are no living enemies`() {
        val board = CombatFixtures.board()
        val actor = CombatFixtures.unit("goblin#0", false, position = HexCoord.ORIGIN)
        val deadEnemy = CombatFixtures.unit("player", true, position = HexCoord(1, 0), currentHp = 0)
        val engine = CombatEngine(board, listOf(actor, deadEnemy), SeededRandom(1))

        assertEquals(CombatAction.Wait, SimpleAi.chooseAction(engine, actor))
    }

    @Test
    fun `chooseAction never returns an action submitAction rejects, across 250 randomised layouts`() {
        val masterRng = SeededRandom(20240905L)
        var testedLayouts = 0

        for (i in 0 until 400) {
            val board = CombatFixtures.board(radiusCells = 6)
            val cellPool = board.cells.shuffled(java.util.Random(i.toLong() * 31 + 7)).toMutableList()
            val unitCount = 2 + masterRng.nextInt(4) // 2..5 units total
            val units = mutableListOf<CombatUnit>()

            for (u in 0 until unitCount) {
                if (cellPool.isEmpty()) break
                val pos = cellPool.removeAt(0)
                val playerControlled = u == 0 // exactly one player-controlled unit per layout
                val hp = 1 + masterRng.nextInt(15)
                val speed = masterRng.nextInt(15) // may land on 0 — must still never crash the AI
                val moveRange = masterRng.nextInt(6)
                val attackRange = 1 + masterRng.nextInt(3)
                units += CombatFixtures.unit(
                    id = if (playerControlled) "player" else "m$u",
                    playerControlled = playerControlled,
                    position = pos,
                    stats = CombatFixtures.stats(
                        maxHp = hp, speed = speed, moveRangeCells = moveRange, attackRangeCells = attackRange,
                    ),
                    currentHp = hp,
                )
            }
            // Need both a player unit and at least one enemy to exercise attack/move/wait.
            if (units.none { it.playerControlled } || units.none { !it.playerControlled }) continue

            val engine = CombatEngine(board, units, SeededRandom(i.toLong() * 97 + 3))
            val actor = engine.advanceToNextActor() ?: continue // all-zero-speed layout: nothing to act
            testedLayouts++

            val action = SimpleAi.chooseAction(engine, actor)
            val events = engine.submitAction(action)

            assertTrue(
                events.none { it is CombatEvent.ActionRejected },
                "layout $i: SimpleAi chose $action for ${actor.id}, engine rejected it: $events",
            )
        }

        assertTrue(testedLayouts >= 200, "expected at least 200 exercised layouts, got $testedLayouts")
    }
}
