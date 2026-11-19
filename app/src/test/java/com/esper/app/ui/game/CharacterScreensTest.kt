package com.esper.app.ui.game

import com.esper.engine.character.BestiaryEntry
import com.esper.engine.character.CharacterFactory
import com.esper.engine.character.CharacterState
import com.esper.engine.content.ContentCatalog
import com.esper.engine.content.JobDefinition
import com.esper.engine.content.MonsterDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The creator's job cards and the character sheet, from the fixture content of docs/VISUALS.md §5. */
class CharacterScreensTest {
    private fun job(id: String, name: String, hitDie: String, ac: Int, ability: String, damage: String, move: Int, prerequisites: List<String> = emptyList(), toMaster: Int = 100) =
        JobDefinition(
            schemaVersion = 1, id = id, displayName = name, hitDie = hitDie, baseArmorClass = ac,
            attackAbility = ability, damage = damage, speed = 8, moveRangeCells = move,
            prerequisites = prerequisites, jobPointsToMaster = toMaster,
        )

    private fun monster(id: String, name: String, text: String = "") = MonsterDefinition(
        schemaVersion = 1, id = id, displayName = name, maxHp = 9, armorClass = 12, attackBonus = 3,
        damage = "1d6+1", speed = 14, moveRangeCells = 5, xpReward = 10, jobPointsReward = 10, bestiaryText = text,
    )

    private val guard = job("fx_guard", "Guard", "1d10", 12, "str", "1d8", 4)
    private val warden = job("fx_warden", "Warden", "1d10", 13, "str", "1d8", 4, listOf("fx_guard"), toMaster = 200)
    private val scout = job("fx_scout", "Scout", "1d8", 13, "dex", "1d6", 5)
    private val catalog = ContentCatalog(
        jobs = listOf(guard, warden, scout),
        monsters = listOf(monster("fx_ring", "Ring", "A test shape. It rolls at whatever is nearest."), monster("fx_arrow", "Arrow")),
    )

    /** Ash: a Guard at level 2, XP 180 / 300, job points Guard 100 / 100 and Warden 40 / 200, bestiary Ring ×5 and Arrow ×1. */
    private val ash = CharacterState(
        name = "Ash",
        baseScores = CharacterFactory.STARTING_SCORES,
        currentJobId = "fx_guard",
        level = 2,
        xp = 180,
        jobPoints = mapOf("fx_guard" to 100, "fx_warden" to 40),
        unlockedJobIds = setOf("fx_warden"),
        bestiary = mapOf(
            "fx_ring" to BestiaryEntry("fx_ring", 5),
            "fx_arrow" to BestiaryEntry("fx_arrow", 1),
        ),
    )

    @Test fun `a job card's stat line is hit die, AC, attack ability, damage and move`() {
        assertEquals("d10 · AC 12 · STR · 1d8 · Move 4", jobStatLine(guard))
        assertEquals("d8 · AC 13 · DEX · 1d6 · Move 5", jobStatLine(scout))
    }

    @Test fun `the sheet header holds the name, job, level and XP toward the next level`() {
        val sheet = characterSheetState(ash, guard, catalog)
        assertEquals("Ash", sheet.name)
        assertEquals("Guard · Level 2", sheet.jobLine)
        assertEquals("XP 180 / 300", sheet.xpText)
        assertEquals(0.6f, sheet.xpFraction, 0.0001f)
    }

    @Test fun `at the highest level the XP bar is full and says max level`() {
        val sheet = characterSheetState(ash.copy(level = 5, xp = 2400), guard, catalog)
        assertEquals("XP 2400 · max level", sheet.xpText)
        assertEquals(1f, sheet.xpFraction, 0f)
    }

    @Test fun `stat tiles show move and range in metres, and every ability with its modifier`() {
        val sheet = characterSheetState(ash, guard, catalog)
        assertEquals(
            listOf("HP" to "18", "AC" to "13", "ATTACK" to "+4", "DAMAGE" to "1d8+2"),
            sheet.stats.map { it.label to it.value },
        )
        assertEquals(
            listOf("SPEED" to "8", "MOVE" to "4 m", "RANGE" to "1 m"),
            sheet.movement.map { it.label to it.value },
        )
        assertEquals(
            listOf("STR 14 +2", "DEX 12 +1", "CON 13 +1", "INT 10 +0", "WIS 10 +0", "CHA 10 +0"),
            sheet.abilities.map { "${it.label} ${it.value} ${it.note}" },
        )
    }

    @Test fun `jobs list the current job first, then those with points or unlocked, joined to their first prerequisite`() {
        val jobs = characterSheetState(ash, guard, catalog).jobs
        assertEquals(listOf("Guard", "Warden"), jobs.map { it.name })

        val (guardRow, wardenRow) = jobs
        assertTrue(guardRow.mastered)
        assertEquals("Job points 100 / 100", guardRow.pointsText)
        assertEquals(null, guardRow.from)
        assertFalse(guardRow.joinedAbove)

        assertFalse(wardenRow.mastered)
        assertEquals("Job points 40 / 200", wardenRow.pointsText)
        assertEquals(0.2f, wardenRow.fraction, 0.0001f)
        assertEquals("Guard", wardenRow.from)
        assertTrue(wardenRow.joinedAbove)
    }

    @Test fun `the bestiary lists each monster met with how many were defeated and its bestiary text`() {
        assertEquals(
            listOf(
                Triple("Ring", "defeated ×5", "A test shape. It rolls at whatever is nearest."),
                Triple("Arrow", "defeated ×1", ""),
            ),
            characterSheetState(ash, guard, catalog).bestiary.map { Triple(it.name, it.defeated, it.text) },
        )
    }
}
