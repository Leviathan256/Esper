package com.esper.engine.content

import com.esper.engine.dice.DiceExpr
import com.esper.engine.stats.AbilityScores

/** One problem with one content file. [source] is the filename it came from. */
data class ContentIssue(val source: String, val message: String)

/** Carries EVERY issue found, not just the first — one CI run should list them all. */
class ContentValidationException(val issues: List<ContentIssue>) : RuntimeException(
    issues.joinToString(prefix = "content invalid:\n  - ", separator = "\n  - ") {
        "${it.source}: ${it.message}"
    },
)

/**
 * The check that fails a PR carrying malformed game data, per docs/GAME_DESIGN.md's
 * job-pipeline rule 3.
 */
object ContentValidator {

    private val ID_PATTERN = Regex("^[a-z0-9_]+$")

    /** `DiceExpr.parse` also accepts a bare integer; mirrored here without depending on
     *  `DiceExpr.parse` itself, which is owned and implemented by a sibling package. */
    private val BARE_INTEGER = Regex("""^\s*-?\d+\s*$""")

    private val ABILITY_KEYS = AbilityScores.KEYS.toHashSet()

    /** Returns every issue found, never just the first. Empty means valid. */
    fun validate(
        jobs: List<JobDefinition>,
        monsters: List<MonsterDefinition>,
    ): List<ContentIssue> {
        val issues = mutableListOf<ContentIssue>()
        val jobIds = jobs.map { it.id }.toHashSet()

        val seenJobIds = hashSetOf<String>()
        for (job in jobs) {
            val src = jobSource(job)
            validateCommon(job.schemaVersion, job.id, job.displayName, src, issues)
            if (job.id.isNotBlank() && !seenJobIds.add(job.id)) {
                issues += ContentIssue(src, "duplicate job id '${job.id}'")
            }
            validateDice(job.hitDie, src, "hitDie", requireSingleDie = true, issues)
            validateDice(job.damage, src, "damage", requireSingleDie = false, issues)
            checkRange(job.baseArmorClass, 1..30, src, "baseArmorClass", issues)
            checkRange(job.speed, 1..50, src, "speed", issues)
            checkRange(job.moveRangeCells, 0..24, src, "moveRangeCells", issues)
            checkRange(job.attackRangeCells, 1..24, src, "attackRangeCells", issues)
            if (job.jobPointsToMaster < 1) {
                issues += ContentIssue(src, "jobPointsToMaster ${job.jobPointsToMaster} must be >= 1")
            }
            checkAbilityKey(job.attackAbility, src, "attackAbility", issues)
            for (key in job.statGrowth.keys) {
                checkAbilityKey(key, src, "statGrowth key", issues)
            }
            for (prereq in job.prerequisites) {
                if (prereq !in jobIds) {
                    issues += ContentIssue(src, "prerequisite '$prereq' does not resolve to a known job")
                }
            }
            for (unlock in job.unlocks) {
                if (unlock !in jobIds) {
                    issues += ContentIssue(src, "unlock '$unlock' does not resolve to a known job")
                }
            }
        }

        val seenMonsterIds = hashSetOf<String>()
        for (monster in monsters) {
            val src = monsterSource(monster)
            validateCommon(monster.schemaVersion, monster.id, monster.displayName, src, issues)
            if (monster.id.isNotBlank() && !seenMonsterIds.add(monster.id)) {
                issues += ContentIssue(src, "duplicate monster id '${monster.id}'")
            }
            validateDice(monster.damage, src, "damage", requireSingleDie = false, issues)
            if (monster.maxHp < 1) {
                issues += ContentIssue(src, "maxHp ${monster.maxHp} must be >= 1")
            }
            checkRange(monster.armorClass, 1..30, src, "armorClass", issues)
            checkRange(monster.speed, 1..50, src, "speed", issues)
            checkRange(monster.moveRangeCells, 0..24, src, "moveRangeCells", issues)
            checkRange(monster.attackRangeCells, 1..24, src, "attackRangeCells", issues)
            if (monster.xpReward < 0) {
                issues += ContentIssue(src, "xpReward ${monster.xpReward} must be >= 0")
            }
            if (monster.jobPointsReward < 0) {
                issues += ContentIssue(src, "jobPointsReward ${monster.jobPointsReward} must be >= 0")
            }
        }

        val cycle = findJobGraphCycle(jobs)
        if (cycle != null) {
            issues += ContentIssue("job graph", "job unlock cycle: ${cycle.joinToString(" -> ")}")
        }

        return issues
    }

    /**
     * DFS with a recursion stack over edges `prerequisite -> job` and `job -> unlock`.
     *
     * Returns null when acyclic; otherwise the cycle as a job-id path with the first
     * id repeated last, so the message names the path rather than saying "cycle found".
     */
    fun findJobGraphCycle(jobs: List<JobDefinition>): List<String>? {
        val ids = jobs.map { it.id }
        val idSet = ids.toHashSet()
        val edges = linkedMapOf<String, MutableList<String>>()
        for (id in ids) edges[id] = mutableListOf()
        for (job in jobs) {
            for (prereq in job.prerequisites) {
                if (prereq in idSet) edges.getValue(prereq).add(job.id)
            }
            for (unlock in job.unlocks) {
                if (unlock in idSet) edges.getValue(job.id).add(unlock)
            }
        }

        val visited = hashSetOf<String>()
        val onStack = hashSetOf<String>()
        val stack = mutableListOf<String>()

        fun dfs(node: String): List<String>? {
            visited += node
            onStack += node
            stack += node
            for (next in edges.getValue(node)) {
                if (next in onStack) {
                    val cycleStart = stack.indexOf(next)
                    return stack.subList(cycleStart, stack.size).toList() + next
                }
                if (next !in visited) {
                    val found = dfs(next)
                    if (found != null) return found
                }
            }
            onStack -= node
            stack.removeAt(stack.size - 1)
            return null
        }

        for (id in ids) {
            if (id !in visited) {
                val found = dfs(id)
                if (found != null) return found
            }
        }
        return null
    }

    private fun jobSource(job: JobDefinition) = "job:${job.id.ifBlank { "<blank>" }}"

    private fun monsterSource(monster: MonsterDefinition) = "monster:${monster.id.ifBlank { "<blank>" }}"

    private fun validateCommon(
        schemaVersion: Int,
        id: String,
        displayName: String,
        source: String,
        issues: MutableList<ContentIssue>,
    ) {
        if (schemaVersion != 1) {
            issues += ContentIssue(source, "schemaVersion $schemaVersion must be 1")
        }
        if (id.isBlank()) {
            issues += ContentIssue(source, "id must not be blank")
        } else if (!ID_PATTERN.matches(id)) {
            issues += ContentIssue(source, "id '$id' must be lowercase and match [a-z0-9_]+")
        }
        if (displayName.isBlank()) {
            issues += ContentIssue(source, "displayName must not be blank")
        }
    }

    private fun checkRange(value: Int, range: IntRange, source: String, field: String, issues: MutableList<ContentIssue>) {
        if (value !in range) {
            issues += ContentIssue(source, "$field $value out of range ${range.first}..${range.last}")
        }
    }

    private fun checkAbilityKey(key: String, source: String, field: String, issues: MutableList<ContentIssue>) {
        if (key !in ABILITY_KEYS) {
            issues += ContentIssue(source, "$field '$key' is not one of ${AbilityScores.KEYS}")
        }
    }

    private fun validateDice(
        text: String,
        source: String,
        field: String,
        requireSingleDie: Boolean,
        issues: MutableList<ContentIssue>,
    ) {
        val parsed = parseDiceLoosely(text)
        if (parsed == null) {
            issues += ContentIssue(source, "$field '$text' is not valid dice notation")
            return
        }
        if (requireSingleDie && parsed.first != 1) {
            issues += ContentIssue(source, "$field '$text' must have exactly 1 die (count == 1)")
        }
    }

    /** Recognises the same grammar `DiceExpr.parse` documents, without calling it — this
     *  package must validate content even before `engine-dice`'s bodies are implemented. */
    private fun parseDiceLoosely(text: String): Pair<Int, Int>? {
        val match = DiceExpr.PATTERN.matchEntire(text)
        if (match != null) {
            // toIntOrNull, not toInt: the regex accepts any run of digits, so a
            // count or sides past Int.MAX_VALUE must be reported as an issue, not
            // thrown — validate() promises every issue, never an exception.
            val count = match.groupValues[1].toIntOrNull() ?: return null
            val sides = match.groupValues[2].toIntOrNull() ?: return null
            return count to sides
        }
        if (BARE_INTEGER.matches(text)) {
            return 0 to 0
        }
        return null
    }
}
