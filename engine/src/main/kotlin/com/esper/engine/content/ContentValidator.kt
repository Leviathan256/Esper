package com.esper.engine.content

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
    /** Returns every issue found, never just the first. Empty means valid. */
    fun validate(
        jobs: List<JobDefinition>,
        monsters: List<MonsterDefinition>,
    ): List<ContentIssue> = TODO("implemented by engine-content")

    /**
     * DFS with a recursion stack over edges `prerequisite -> job` and `job -> unlock`.
     *
     * Returns null when acyclic; otherwise the cycle as a job-id path with the first
     * id repeated last, so the message names the path rather than saying "cycle found".
     */
    fun findJobGraphCycle(jobs: List<JobDefinition>): List<String>? =
        TODO("implemented by engine-content")
}
