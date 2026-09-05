package com.esper.engine.encounter

import com.esper.engine.character.CharacterFactory
import com.esper.engine.character.CharacterState
import com.esper.engine.content.ContentCatalog
import com.esper.engine.content.JobDefinition
import com.esper.engine.content.MonsterDefinition
import com.esper.engine.dice.DiceExpr
import com.esper.engine.geometry.GeoPoint
import com.esper.engine.geometry.HexCoord
import com.esper.engine.geometry.HexGrid
import com.esper.engine.stats.StatCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BattleBuilderTest {

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
    )

    private val apprentice = JobDefinition(
        schemaVersion = 1,
        id = "apprentice",
        displayName = "Apprentice",
        hitDie = "1d6",
        baseArmorClass = 10,
        attackAbility = "int",
        damage = "1d4",
        speed = 6,
        moveRangeCells = 4,
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
        maxHp = 11,
        armorClass = 13,
        attackBonus = 4,
        damage = "1d6+1",
        speed = 12,
        moveRangeCells = 5,
        xpReward = 60,
        jobPointsReward = 3,
    )

    private val catalog = ContentCatalog(jobs = listOf(squire, apprentice), monsters = listOf(goblin, wolf))

    private fun character(jobId: String = "squire", level: Int = 1) = CharacterFactory.newCharacter("Hero", squire)
        .copy(currentJobId = jobId, level = level)

    private fun anchor() = GeoPoint(lat = 47.6062, lon = -122.3321)

    @Test
    fun `player is placed at ORIGIN with the fixed player unit id`() {
        val encounter = Encounter(id = "enc-1", anchor = anchor(), monsterIds = listOf("goblin"))
        val battle = BattleBuilder.build(encounter, character(), catalog)

        val player = battle.units.single { it.id == BattleBuilder.PLAYER_UNIT_ID }
        assertEquals(HexCoord.ORIGIN, player.position)
        assertTrue(player.playerControlled)
    }

    @Test
    fun `player stats are derived from the character's job and level`() {
        val encounter = Encounter(id = "enc-1", anchor = anchor(), monsterIds = listOf("goblin"))
        val battle = BattleBuilder.build(encounter, character(level = 2), catalog)

        val player = battle.units.single { it.id == BattleBuilder.PLAYER_UNIT_ID }
        val expected = StatCalculator.derive(CharacterFactory.STARTING_SCORES, squire, 2)
        assertEquals(expected, player.stats)
        assertEquals(expected.maxHp, player.currentHp)
        assertEquals(squire.id, player.sourceId)
    }

    @Test
    fun `a missing current job falls back to the catalog's first job`() {
        val encounter = Encounter(id = "enc-1", anchor = anchor(), monsterIds = listOf("goblin"))
        val battle = BattleBuilder.build(encounter, character(jobId = "nonexistent-job"), catalog)

        val player = battle.units.single { it.id == BattleBuilder.PLAYER_UNIT_ID }
        val fallbackJob = catalog.jobs.first()
        val expected = StatCalculator.derive(CharacterFactory.STARTING_SCORES, fallbackJob, 1)
        assertEquals(expected, player.stats)
        assertEquals(fallbackJob.id, player.sourceId)
    }

    @Test
    fun `every monster gets a unique id even when two share a monster id`() {
        val encounter = Encounter(id = "enc-1", anchor = anchor(), monsterIds = listOf("goblin", "goblin"))
        val battle = BattleBuilder.build(encounter, character(), catalog)

        val monsterUnits = battle.units.filterNot { it.playerControlled }
        assertEquals(listOf("goblin#0", "goblin#1"), monsterUnits.map { it.id })
        assertEquals(monsterUnits.size, monsterUnits.map { it.id }.toSet().size)
    }

    @Test
    fun `monsters are placed on distinct cells within the board, on the start ring`() {
        val encounter = Encounter(id = "enc-1", anchor = anchor(), monsterIds = listOf("goblin", "wolf"))
        val battle = BattleBuilder.build(encounter, character(), catalog)

        val ring = HexGrid.ring(HexCoord.ORIGIN, BattleBuilder.MONSTER_START_RING_CELLS)
        val monsterUnits = battle.units.filterNot { it.playerControlled }
        val positions = monsterUnits.map { it.position }

        assertEquals(positions.size, positions.toSet().size, "monster positions must be distinct")
        positions.forEach { position ->
            assertTrue(position in ring, "$position is not on the start ring")
            assertTrue(battle.board.contains(position), "$position is not within the board")
        }
    }

    @Test
    fun `monster derived stats come straight from the monster definition`() {
        val encounter = Encounter(id = "enc-1", anchor = anchor(), monsterIds = listOf("goblin"))
        val battle = BattleBuilder.build(encounter, character(), catalog)

        val goblinUnit = battle.units.single { it.id == "goblin#0" }
        assertEquals(goblin.maxHp, goblinUnit.stats.maxHp)
        assertEquals(goblin.maxHp, goblinUnit.currentHp)
        assertEquals(goblin.armorClass, goblinUnit.stats.armorClass)
        assertEquals(goblin.attackBonus, goblinUnit.stats.attackBonus)
        assertEquals(DiceExpr.parse(goblin.damage), goblinUnit.stats.damage)
        assertEquals(goblin.speed, goblinUnit.stats.speed)
        assertEquals(goblin.moveRangeCells, goblinUnit.stats.moveRangeCells)
        assertEquals(goblin.attackRangeCells, goblinUnit.stats.attackRangeCells)
        assertEquals(goblin.id, goblinUnit.sourceId)
        assertTrue(!goblinUnit.playerControlled)
    }

    @Test
    fun `a full start ring still gets distinct cells and an overfull one is refused`() {
        val ringSize = HexGrid.ring(HexCoord.ORIGIN, BattleBuilder.MONSTER_START_RING_CELLS).size
        val full = Encounter(id = "enc-full", anchor = anchor(), monsterIds = List(ringSize) { "goblin" })
        val battle = BattleBuilder.build(full, character(), catalog)
        val positions = battle.units.filterNot { it.playerControlled }.map { it.position }
        assertEquals(ringSize, positions.size)
        assertEquals(ringSize, positions.toSet().size, "monster positions must be distinct")

        val overfull = Encounter(id = "enc-over", anchor = anchor(), monsterIds = List(ringSize + 1) { "goblin" })
        assertThrows<IllegalArgumentException> { BattleBuilder.build(overfull, character(), catalog) }
    }
}
