package com.esper.engine.encounter

import com.esper.engine.character.CharacterState
import com.esper.engine.combat.CombatUnit
import com.esper.engine.content.ContentCatalog
import com.esper.engine.content.MonsterDefinition
import com.esper.engine.dice.DiceExpr
import com.esper.engine.geometry.HexBoard
import com.esper.engine.geometry.HexCoord
import com.esper.engine.geometry.HexGrid
import com.esper.engine.stats.DerivedStats
import com.esper.engine.stats.StatCalculator

/** A board plus the units standing on it, ready to hand to a `CombatEngine`. */
data class Battle(val board: HexBoard, val units: List<CombatUnit>)

/**
 * The single engine-side entry point from (encounter + character + catalog) to a
 * ready battle, so the Android layer assembles nothing itself.
 */
object BattleBuilder {
    const val PLAYER_UNIT_ID: String = "player"

    const val MONSTER_START_RING_CELLS: Int = 4

    /** Player at ORIGIN; monsters spread deterministically around the ring at radius 4. */
    fun build(
        encounter: Encounter,
        character: CharacterState,
        catalog: ContentCatalog,
    ): Battle {
        val board = HexBoard(encounter.anchor, encounter.boardRadiusCells)

        // The character's current job should always resolve against a live catalog;
        // falling back to the first known job keeps a battle buildable even if a
        // save references a job id content no longer ships, rather than crashing.
        val job = catalog.job(character.currentJobId) ?: catalog.jobs.first()
        val playerStats = StatCalculator.derive(character.baseScores, job, character.level)
        val player = CombatUnit(
            id = PLAYER_UNIT_ID,
            displayName = character.name,
            playerControlled = true,
            sourceId = job.id,
            stats = playerStats,
            currentHp = playerStats.maxHp,
            position = HexCoord.ORIGIN,
        )

        val ringCells = HexGrid.ring(HexCoord.ORIGIN, MONSTER_START_RING_CELLS).toList()
        val monsterCount = encounter.monsterIds.size
        // Ring 4 has 24 cells. Past that, `ringCells.size / monsterCount` floors to 0
        // and every monster would be stacked on ringCells[0]; refuse loudly instead
        // of silently building a broken board. (build() already uses error() for an
        // unknown monster id, so failing fast here is the established idiom.)
        require(monsterCount <= ringCells.size) {
            "BattleBuilder: $monsterCount monsters exceed the ${ringCells.size} cells on ring " +
                "$MONSTER_START_RING_CELLS"
        }
        val step = if (monsterCount > 0) ringCells.size / monsterCount else 0
        val monsterUnits = encounter.monsterIds.mapIndexed { index, monsterId ->
            val definition = catalog.monster(monsterId)
                ?: error("BattleBuilder: unknown monster id \"$monsterId\" not in catalog")
            val cell = ringCells[(index * step) % ringCells.size]
            buildMonsterUnit(definition, index, cell)
        }

        return Battle(board = board, units = listOf(player) + monsterUnits)
    }

    private fun buildMonsterUnit(
        definition: MonsterDefinition,
        index: Int,
        position: HexCoord,
    ): CombatUnit {
        val stats = DerivedStats(
            maxHp = definition.maxHp,
            armorClass = definition.armorClass,
            attackBonus = definition.attackBonus,
            damage = DiceExpr.parse(definition.damage),
            speed = definition.speed,
            moveRangeCells = definition.moveRangeCells,
            attackRangeCells = definition.attackRangeCells,
        )
        return CombatUnit(
            id = "${definition.id}#$index",
            displayName = definition.displayName,
            playerControlled = false,
            sourceId = definition.id,
            stats = stats,
            currentHp = stats.maxHp,
            position = position,
        )
    }
}
