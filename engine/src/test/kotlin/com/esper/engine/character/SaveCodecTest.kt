package com.esper.engine.character

import com.esper.engine.stats.AbilityScores
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SaveCodecTest {

    private val fullyPopulated = CharacterState(
        schemaVersion = 1,
        name = "Ramza",
        baseScores = AbilityScores(str = 14, dex = 12, con = 13, int = 10, wis = 10, cha = 10),
        currentJobId = "knight",
        level = 3,
        xp = 350,
        jobPoints = mapOf("squire" to 100, "knight" to 40),
        unlockedJobIds = setOf("squire", "knight"),
        learnedAbilityIds = setOf("shield_bash", "guard"),
        bestiary = mapOf(
            "goblin" to BestiaryEntry("goblin", timesDefeated = 5),
            "wolf" to BestiaryEntry("wolf", timesDefeated = 1),
        ),
    )

    @Test
    fun `encode then decode round-trips a fully populated CharacterState exactly`() {
        val encoded = SaveCodec.encode(fullyPopulated)
        val decoded = SaveCodec.decode(encoded)
        assertEquals(fullyPopulated, decoded)
    }

    @Test
    fun `decode succeeds on JSON carrying an extra unknown field`() {
        val encoded = SaveCodec.encode(fullyPopulated)
        val withExtra = encoded.trimEnd().removeSuffix("}") + ""","totallyMadeUpField":"surprise"}"""
        val decoded = SaveCodec.decode(withExtra)
        assertEquals(fullyPopulated, decoded)
    }

    @Test
    fun `decode falls back to defaults for a missing optional field`() {
        val minimal = """
            {
              "schemaVersion": 1,
              "name": "Delita",
              "baseScores": {"str": 14, "dex": 12, "con": 13, "int": 10, "wis": 10, "cha": 10},
              "currentJobId": "squire"
            }
        """.trimIndent()
        val decoded = SaveCodec.decode(minimal)
        assertEquals("Delita", decoded.name)
        assertEquals("squire", decoded.currentJobId)
        assertEquals(1, decoded.level)
        assertEquals(0, decoded.xp)
        assertEquals(emptyMap<String, Int>(), decoded.jobPoints)
        assertEquals(emptySet<String>(), decoded.unlockedJobIds)
        assertEquals(emptySet<String>(), decoded.learnedAbilityIds)
        assertEquals(emptyMap<String, BestiaryEntry>(), decoded.bestiary)
    }

    @Test
    fun `a synthetic v0 fixture survives migrate without losing fields`() {
        // A "v0" save predates the schemaVersion field entirely.
        val v0Fixture = """
            {
              "name": "Agrias",
              "baseScores": {"str": 15, "dex": 11, "con": 14, "int": 9, "wis": 12, "cha": 13},
              "currentJobId": "knight",
              "level": 2,
              "xp": 150,
              "jobPoints": {"knight": 30},
              "unlockedJobIds": ["squire", "knight"],
              "learnedAbilityIds": ["shield_bash"],
              "bestiary": {"goblin": {"monsterId": "goblin", "timesDefeated": 3}}
            }
        """.trimIndent()

        val migrated = SaveMigrations.migrate(v0Fixture)
        assertTrue(migrated.contains("\"schemaVersion\":1"), "migrated JSON does not carry schemaVersion 1: $migrated")

        val decoded = SaveCodec.decode(v0Fixture)
        assertEquals(1, decoded.schemaVersion)
        assertEquals("Agrias", decoded.name)
        assertEquals(15, decoded.baseScores.str)
        assertEquals("knight", decoded.currentJobId)
        assertEquals(2, decoded.level)
        assertEquals(150, decoded.xp)
        assertEquals(mapOf("knight" to 30), decoded.jobPoints)
        assertEquals(setOf("squire", "knight"), decoded.unlockedJobIds)
        assertEquals(setOf("shield_bash"), decoded.learnedAbilityIds)
        assertEquals(3, decoded.bestiary["goblin"]?.timesDefeated)
    }

    @Test
    fun `migrate is a no-op on an already-current save`() {
        val encoded = SaveCodec.encode(fullyPopulated)
        val migrated = SaveMigrations.migrate(encoded)
        assertEquals(fullyPopulated, SaveCodec.decode(migrated))
    }
}
