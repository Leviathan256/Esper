package com.esper.engine.content

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContentLoaderTest {

    private val validSquireJson = """
        {
          "schemaVersion": 1,
          "id": "squire",
          "displayName": "Squire",
          "description": "A front-line trainee.",
          "hitDie": "1d10",
          "baseArmorClass": 12,
          "attackAbility": "str",
          "damage": "1d8",
          "speed": 8,
          "moveRangeCells": 4,
          "attackRangeCells": 1,
          "statGrowth": { "str": 1, "con": 1 },
          "abilitiesGranted": ["shield_bash"],
          "prerequisites": [],
          "unlocks": ["knight"],
          "jobPointsToMaster": 100
        }
    """.trimIndent()

    private val validGoblinJson = """
        {
          "schemaVersion": 1,
          "id": "goblin",
          "displayName": "Goblin",
          "maxHp": 7,
          "armorClass": 13,
          "attackBonus": 4,
          "damage": "1d6+2",
          "speed": 7,
          "moveRangeCells": 3,
          "attackRangeCells": 1,
          "xpReward": 50,
          "jobPointsReward": 2,
          "bestiaryText": "A vicious but cowardly raider."
        }
    """.trimIndent()

    @Test
    fun `parseJob parses a well-formed job`() {
        val job = ContentLoader.parseJob("squire.json", validSquireJson)
        assertEquals("squire", job.id)
        assertEquals("Squire", job.displayName)
        assertEquals("1d10", job.hitDie)
        assertEquals(listOf("knight"), job.unlocks)
    }

    @Test
    fun `parseMonster parses a well-formed monster`() {
        val monster = ContentLoader.parseMonster("goblin.json", validGoblinJson)
        assertEquals("goblin", monster.id)
        assertEquals(7, monster.maxHp)
        assertEquals("1d6+2", monster.damage)
    }

    @Test
    fun `an extra unknown key parses identically to the file without it`() {
        val withExtra = validSquireJson.replace(
            "\"schemaVersion\": 1,",
            "\"schemaVersion\": 1, \"someFutureField\": \"ignored\",",
        )
        val plain = ContentLoader.parseJob("squire.json", validSquireJson)
        val withExtraParsed = ContentLoader.parseJob("squire-extra.json", withExtra)
        assertEquals(plain, withExtraParsed)
    }

    @Test
    fun `load succeeds and returns a catalog for valid content`() {
        val catalog = ContentLoader.load(
            mapOf("squire.json" to validSquireJson.replace("\"unlocks\": [\"knight\"]", "\"unlocks\": []")),
            mapOf("goblin.json" to validGoblinJson),
        )
        assertEquals(1, catalog.jobs.size)
        assertEquals(1, catalog.monsters.size)
        assertEquals("squire", catalog.job("squire")?.id)
        assertEquals("goblin", catalog.monster("goblin")?.id)
    }

    @Test
    fun `a missing required field is rejected`() {
        val missingHitDie = """
            {
              "schemaVersion": 1,
              "id": "squire",
              "displayName": "Squire",
              "baseArmorClass": 12,
              "attackAbility": "str",
              "damage": "1d8",
              "speed": 8,
              "moveRangeCells": 4
            }
        """.trimIndent()

        val ex = assertThrows(ContentValidationException::class.java) {
            ContentLoader.load(mapOf("squire.json" to missingHitDie), emptyMap())
        }
        assertTrue(ex.issues.isNotEmpty())
        assertTrue(ex.issues.any { it.source == "squire.json" })
    }

    @Test
    fun `a bad dice string is rejected through load`() {
        val badDamage = validGoblinJson.replace("\"1d6+2\"", "\"not-dice\"")
        val ex = assertThrows(ContentValidationException::class.java) {
            ContentLoader.load(emptyMap(), mapOf("goblin.json" to badDamage))
        }
        assertTrue(ex.issues.any { it.message.contains("damage") })
    }

    @Test
    fun `a duplicate id is rejected through load`() {
        val squireA = validSquireJson.replace("\"unlocks\": [\"knight\"]", "\"unlocks\": []")
        val ex = assertThrows(ContentValidationException::class.java) {
            ContentLoader.load(mapOf("a.json" to squireA, "b.json" to squireA), emptyMap())
        }
        assertTrue(ex.issues.any { it.message.contains("duplicate") })
    }

    @Test
    fun `a dangling prerequisite is rejected through load`() {
        val danglingPrereq = validSquireJson
            .replace("\"unlocks\": [\"knight\"]", "\"unlocks\": []")
            .replace("\"prerequisites\": []", "\"prerequisites\": [\"nonexistent\"]")
        val ex = assertThrows(ContentValidationException::class.java) {
            ContentLoader.load(mapOf("squire.json" to danglingPrereq), emptyMap())
        }
        assertTrue(ex.issues.any { it.message.contains("nonexistent") })
    }

    @Test
    fun `an out-of-range armor class is rejected through load`() {
        val badAc = validGoblinJson.replace("\"armorClass\": 13", "\"armorClass\": 99")
        val ex = assertThrows(ContentValidationException::class.java) {
            ContentLoader.load(emptyMap(), mapOf("goblin.json" to badAc))
        }
        assertTrue(ex.issues.any { it.message.contains("armorClass") })
    }

    @Test
    fun `a deliberate 3-node cycle is rejected through load with the full path named`() {
        fun jobJson(id: String, prereq: String?, unlock: String?) = """
            {
              "schemaVersion": 1,
              "id": "$id",
              "displayName": "$id",
              "hitDie": "1d10",
              "baseArmorClass": 12,
              "attackAbility": "str",
              "damage": "1d8",
              "speed": 8,
              "moveRangeCells": 4,
              "prerequisites": ${if (prereq != null) "[\"$prereq\"]" else "[]"},
              "unlocks": ${if (unlock != null) "[\"$unlock\"]" else "[]"}
            }
        """.trimIndent()

        val ex = assertThrows(ContentValidationException::class.java) {
            ContentLoader.load(
                mapOf(
                    "a.json" to jobJson("a", prereq = null, unlock = "b"),
                    "b.json" to jobJson("b", prereq = "a", unlock = "c"),
                    "c.json" to jobJson("c", prereq = "b", unlock = "a"),
                ),
                emptyMap(),
            )
        }
        val cycleIssue = ex.issues.single { it.message.contains("cycle") }
        assertTrue(cycleIssue.message.contains("->"))
    }

    @Test
    fun `multiple problems in one load produce multiple issues, not just the first`() {
        val brokenJob = """
            {
              "schemaVersion": 2,
              "id": "",
              "displayName": "",
              "hitDie": "not-dice",
              "baseArmorClass": -5,
              "attackAbility": "luck",
              "damage": "1d8",
              "speed": 8,
              "moveRangeCells": 4
            }
        """.trimIndent()

        val ex = assertThrows(ContentValidationException::class.java) {
            ContentLoader.load(mapOf("broken.json" to brokenJob), emptyMap())
        }
        assertTrue(ex.issues.size >= 5, "expected at least 5 issues, got ${ex.issues.size}: ${ex.issues}")
        assertTrue(ex.message!!.lines().size > 2, "the exception message must list every issue, not just the first")
    }
}
