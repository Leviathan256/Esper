package com.esper.engine.content

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContentValidatorTest {

    private fun job(
        id: String = "squire",
        schemaVersion: Int = 1,
        displayName: String = "Squire",
        hitDie: String = "1d10",
        baseArmorClass: Int = 12,
        attackAbility: String = "str",
        damage: String = "1d8",
        speed: Int = 8,
        moveRangeCells: Int = 4,
        attackRangeCells: Int = 1,
        statGrowth: Map<String, Int> = emptyMap(),
        prerequisites: List<String> = emptyList(),
        unlocks: List<String> = emptyList(),
        jobPointsToMaster: Int = 100,
    ) = JobDefinition(
        schemaVersion = schemaVersion,
        id = id,
        displayName = displayName,
        hitDie = hitDie,
        baseArmorClass = baseArmorClass,
        attackAbility = attackAbility,
        damage = damage,
        speed = speed,
        moveRangeCells = moveRangeCells,
        attackRangeCells = attackRangeCells,
        statGrowth = statGrowth,
        prerequisites = prerequisites,
        unlocks = unlocks,
        jobPointsToMaster = jobPointsToMaster,
    )

    private fun monster(
        id: String = "goblin",
        schemaVersion: Int = 1,
        displayName: String = "Goblin",
        maxHp: Int = 7,
        armorClass: Int = 13,
        attackBonus: Int = 4,
        damage: String = "1d6+2",
        speed: Int = 7,
        moveRangeCells: Int = 3,
        attackRangeCells: Int = 1,
        xpReward: Int = 50,
        jobPointsReward: Int = 2,
    ) = MonsterDefinition(
        schemaVersion = schemaVersion,
        id = id,
        displayName = displayName,
        maxHp = maxHp,
        armorClass = armorClass,
        attackBonus = attackBonus,
        damage = damage,
        speed = speed,
        moveRangeCells = moveRangeCells,
        attackRangeCells = attackRangeCells,
        xpReward = xpReward,
        jobPointsReward = jobPointsReward,
    )

    @Test
    fun `a valid job and monster produce zero issues`() {
        val issues = ContentValidator.validate(listOf(job()), listOf(monster()))
        assertEquals(emptyList<ContentIssue>(), issues)
    }

    @Test
    fun `wrong schemaVersion is rejected`() {
        val issues = ContentValidator.validate(listOf(job(schemaVersion = 2)), emptyList())
        assertTrue(issues.any { it.message.contains("schemaVersion") })
    }

    @Test
    fun `blank id is rejected`() {
        val issues = ContentValidator.validate(listOf(job(id = "")), emptyList())
        assertTrue(issues.any { it.message.contains("id") && it.message.contains("blank") })
    }

    @Test
    fun `uppercase id is rejected`() {
        val issues = ContentValidator.validate(listOf(job(id = "Squire")), emptyList())
        assertTrue(issues.any { it.message.contains("id") })
    }

    @Test
    fun `blank displayName is rejected`() {
        val issues = ContentValidator.validate(listOf(job(displayName = "")), emptyList())
        assertTrue(issues.any { it.message.contains("displayName") })
    }

    @Test
    fun `bad dice notation is rejected for hitDie and damage`() {
        val hitDieIssues = ContentValidator.validate(listOf(job(hitDie = "d10")), emptyList())
        assertTrue(hitDieIssues.any { it.message.contains("hitDie") })

        val damageIssues = ContentValidator.validate(listOf(job(damage = "2x6")), emptyList())
        assertTrue(damageIssues.any { it.message.contains("damage") })

        val monsterDamageIssues = ContentValidator.validate(emptyList(), listOf(monster(damage = "")))
        assertTrue(monsterDamageIssues.any { it.message.contains("damage") })
    }

    @Test
    fun `hitDie with more than one die is rejected`() {
        val issues = ContentValidator.validate(listOf(job(hitDie = "2d10")), emptyList())
        assertTrue(issues.any { it.message.contains("hitDie") && it.message.contains("count") })
    }

    @Test
    fun `damage with more than one die is allowed`() {
        val issues = ContentValidator.validate(listOf(job(damage = "3d6+1")), emptyList())
        assertEquals(emptyList<ContentIssue>(), issues)
    }

    @Test
    fun `duplicate job id is rejected`() {
        val issues = ContentValidator.validate(listOf(job(id = "squire"), job(id = "squire")), emptyList())
        assertTrue(issues.any { it.message.contains("duplicate") })
    }

    @Test
    fun `duplicate monster id is rejected`() {
        val issues = ContentValidator.validate(emptyList(), listOf(monster(id = "goblin"), monster(id = "goblin")))
        assertTrue(issues.any { it.message.contains("duplicate") })
    }

    @Test
    fun `dangling prerequisite and unlock are rejected`() {
        val issues = ContentValidator.validate(
            listOf(job(id = "squire", prerequisites = listOf("nonexistent"), unlocks = listOf("also_missing"))),
            emptyList(),
        )
        assertTrue(issues.any { it.message.contains("nonexistent") })
        assertTrue(issues.any { it.message.contains("also_missing") })
    }

    @Test
    fun `out-of-range armor class is rejected for jobs and monsters`() {
        val jobIssues = ContentValidator.validate(listOf(job(baseArmorClass = 0)), emptyList())
        assertTrue(jobIssues.any { it.message.contains("baseArmorClass") })

        val monsterIssues = ContentValidator.validate(emptyList(), listOf(monster(armorClass = 31)))
        assertTrue(monsterIssues.any { it.message.contains("armorClass") })
    }

    @Test
    fun `numeric sanity checks reject out-of-range values`() {
        assertTrue(
            ContentValidator.validate(listOf(job(speed = 0)), emptyList())
                .any { it.message.contains("speed") },
        )
        assertTrue(
            ContentValidator.validate(listOf(job(moveRangeCells = -1)), emptyList())
                .any { it.message.contains("moveRangeCells") },
        )
        assertTrue(
            ContentValidator.validate(listOf(job(attackRangeCells = 0)), emptyList())
                .any { it.message.contains("attackRangeCells") },
        )
        assertTrue(
            ContentValidator.validate(listOf(job(jobPointsToMaster = 0)), emptyList())
                .any { it.message.contains("jobPointsToMaster") },
        )
        assertTrue(
            ContentValidator.validate(emptyList(), listOf(monster(maxHp = 0)))
                .any { it.message.contains("maxHp") },
        )
        assertTrue(
            ContentValidator.validate(emptyList(), listOf(monster(xpReward = -1)))
                .any { it.message.contains("xpReward") },
        )
        assertTrue(
            ContentValidator.validate(emptyList(), listOf(monster(jobPointsReward = -1)))
                .any { it.message.contains("jobPointsReward") },
        )
    }

    @Test
    fun `unknown attackAbility and statGrowth keys are rejected`() {
        val abilityIssues = ContentValidator.validate(listOf(job(attackAbility = "luck")), emptyList())
        assertTrue(abilityIssues.any { it.message.contains("attackAbility") })

        val growthIssues = ContentValidator.validate(
            listOf(job(statGrowth = mapOf("luck" to 1))),
            emptyList(),
        )
        assertTrue(growthIssues.any { it.message.contains("statGrowth") })
    }

    @Test
    fun `a three-node cycle is detected and the message names the full path`() {
        val a = job(id = "a", unlocks = listOf("b"))
        val b = job(id = "b", prerequisites = listOf("a"), unlocks = listOf("c"))
        val c = job(id = "c", prerequisites = listOf("b"), unlocks = listOf("a"))

        val cycle = ContentValidator.findJobGraphCycle(listOf(a, b, c))
        assertTrue(cycle != null, "expected a cycle to be found")
        assertEquals(cycle!!.first(), cycle.last(), "the path must repeat the first id last")
        assertEquals(4, cycle.size, "a-b-c-a is a 3-edge cycle expressed as 4 ids")

        val issues = ContentValidator.validate(listOf(a, b, c), emptyList())
        val cycleIssue = issues.single { it.message.contains("cycle") }
        assertTrue(cycleIssue.message.contains("->"), "message must name the path, not just say a cycle was found")
        assertTrue(cycleIssue.message.contains(cycle.joinToString(" -> ")), "message must contain the exact path")
    }

    @Test
    fun `an acyclic graph reports no cycle`() {
        val squire = job(id = "squire", unlocks = listOf("knight"))
        val knight = job(id = "knight", prerequisites = listOf("squire"))
        assertNull(ContentValidator.findJobGraphCycle(listOf(squire, knight)))
    }

    @Test
    fun `multiple problems in one load produce multiple issues`() {
        val badJob = job(
            id = "",
            schemaVersion = 2,
            hitDie = "not-dice",
            baseArmorClass = -5,
        )
        val issues = ContentValidator.validate(listOf(badJob), emptyList())
        assertTrue(issues.size >= 4, "expected at least 4 distinct issues, got ${issues.size}: $issues")
    }
}
