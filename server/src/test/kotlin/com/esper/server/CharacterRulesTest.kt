package com.esper.server

import com.esper.engine.character.CharacterState
import com.esper.engine.character.SaveCodec
import com.esper.server.character.CharacterRules
import com.esper.server.character.CreateCharacterBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Reads the shipped content/ (squire and apprentice start; knight needs squire). */
class CharacterRulesTest {
    private val catalog = Content.load(System.getProperty("esper.contentDir"))
    private val rules = CharacterRules(catalog)
    private val squire = rules.create(CreateCharacterBody("  Ramza ", "squire"))

    private fun code(block: () -> Unit) = assertThrows<ApiError> { block() }.code

    @Test
    fun `create trims the name and starts at the chosen job`() {
        assertEquals("Ramza", squire.name)
        assertEquals("squire", squire.currentJobId)
        assertEquals(1, squire.level)
    }

    @Test
    fun `create rejects blank, too long and non-starting job`() {
        assertEquals("invalid_name", code { rules.create(CreateCharacterBody("   ", "squire")) })
        assertEquals("invalid_name", code { rules.create(CreateCharacterBody("a".repeat(25), "squire")) })
        rules.create(CreateCharacterBody("a".repeat(24), "squire"))
        assertEquals("invalid_job", code { rules.create(CreateCharacterBody("Ramza", "knight")) })
        assertEquals("invalid_job", code { rules.create(CreateCharacterBody("Ramza", "nope")) })
    }

    @Test
    fun `update accepts a valid round trip and a level up`() {
        assertEquals(squire, rules.update(SaveCodec.encode(squire), squire))
        val up = squire.copy(level = 2, unlockedJobIds = setOf("knight"), jobPoints = mapOf("squire" to 10))
        assertEquals(up, rules.update(SaveCodec.encode(up), squire))
        // No stored row: the one-time import of a pre-server local save.
        assertEquals(up, rules.update(SaveCodec.encode(up), null))
    }

    @Test
    fun `update rejects rename, lower level, unknown ids, wrong schema, bad json`() {
        val stored = squire.copy(level = 3)
        assertEquals("invalid_state", code { rules.update(SaveCodec.encode(stored.copy(name = "Delita")), stored) })
        assertEquals("invalid_state", code { rules.update(SaveCodec.encode(stored.copy(level = 2)), stored) })
        assertEquals("invalid_job", code { rules.update(SaveCodec.encode(stored.copy(currentJobId = "nope")), stored) })
        assertEquals("invalid_job", code { rules.update(SaveCodec.encode(stored.copy(unlockedJobIds = setOf("nope"))), stored) })
        assertEquals("invalid_job", code { rules.update(SaveCodec.encode(stored.copy(jobPoints = mapOf("nope" to 1))), stored) })
        assertEquals("invalid_job", code { rules.update(SaveCodec.encode(stored.copy(learnedAbilityIds = setOf("nope"))), stored) })
        val future = stored.copy(schemaVersion = CharacterState.CURRENT_SCHEMA_VERSION + 1)
        assertEquals("invalid_state", code { rules.update(SaveCodec.encode(future), stored) })
        assertEquals("invalid_state", code { rules.update("""{"name":"x"}""", stored) })
        assertEquals("bad_request", code { rules.update("{not json", stored) })
    }

    @Test
    fun `import without a stored row still checks the name`() {
        assertEquals("invalid_state", code { rules.update(SaveCodec.encode(squire.copy(name = "a".repeat(25))), null) })
        assertEquals("invalid_state", code { rules.update(SaveCodec.encode(squire.copy(name = " ")), null) })
    }
}
