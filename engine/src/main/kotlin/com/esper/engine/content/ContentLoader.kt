package com.esper.engine.content

/**
 * Turns raw JSON text into validated runtime objects.
 *
 * Takes text, never files: the engine does no I/O, so the same code path serves the
 * JVM tests (reading `/content` from disk) and the app (reading APK assets).
 */
object ContentLoader {
    /** [source] is a filename used only in error messages. */
    fun parseJob(source: String, text: String): JobDefinition = TODO("implemented by engine-content")

    fun parseMonster(source: String, text: String): MonsterDefinition =
        TODO("implemented by engine-content")

    /**
     * Maps are filename -> file text. Parses, then validates.
     *
     * @throws ContentValidationException carrying every issue found.
     */
    fun load(
        jobTexts: Map<String, String>,
        monsterTexts: Map<String, String>,
    ): ContentCatalog = TODO("implemented by engine-content")
}
