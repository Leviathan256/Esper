package com.esper.engine.character

import com.esper.engine.content.JobDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CharacterFactoryTest {

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
    )

    @Test
    fun `newCharacter starts at level 1 with zero xp and the given job`() {
        val character = CharacterFactory.newCharacter("Ramza", squire)
        assertEquals(1, character.level)
        assertEquals(0, character.xp)
        assertEquals("squire", character.currentJobId)
        assertEquals("Ramza", character.name)
        assertEquals(CharacterFactory.STARTING_SCORES, character.baseScores)
        assertEquals(CharacterState.CURRENT_SCHEMA_VERSION, character.schemaVersion)
    }

    @Test
    fun `STARTING_SCORES is fixed, not rolled`() {
        assertEquals(14, CharacterFactory.STARTING_SCORES.str)
        assertEquals(12, CharacterFactory.STARTING_SCORES.dex)
        assertEquals(13, CharacterFactory.STARTING_SCORES.con)
        assertEquals(10, CharacterFactory.STARTING_SCORES.int)
        assertEquals(10, CharacterFactory.STARTING_SCORES.wis)
        assertEquals(10, CharacterFactory.STARTING_SCORES.cha)

        // Deterministic: two calls for the same job produce identical scores.
        val a = CharacterFactory.newCharacter("A", squire)
        val b = CharacterFactory.newCharacter("B", squire)
        assertEquals(a.baseScores, b.baseScores)
    }
}
