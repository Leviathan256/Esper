package com.esper.engine.content

/** Everything the game knows, after parsing and validation. */
data class ContentCatalog(
    val jobs: List<JobDefinition>,
    val monsters: List<MonsterDefinition>,
) {
    val jobsById: Map<String, JobDefinition> get() = jobs.associateBy { it.id }

    val monstersById: Map<String, MonsterDefinition> get() = monsters.associateBy { it.id }

    fun job(id: String): JobDefinition? = jobsById[id]

    fun monster(id: String): MonsterDefinition? = monstersById[id]

    companion object {
        /** What the app falls back to when content fails to load, so it never crashes. */
        val EMPTY: ContentCatalog = ContentCatalog(emptyList(), emptyList())
    }
}
