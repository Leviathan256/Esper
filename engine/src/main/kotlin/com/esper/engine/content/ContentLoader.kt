package com.esper.engine.content

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Turns raw JSON text into validated runtime objects.
 *
 * Takes text, never files: the engine does no I/O, so the same code path serves the
 * JVM tests (reading `/content` from disk) and the app (reading APK assets).
 */
object ContentLoader {

    /** Shared everywhere this package parses content JSON, per the design doc's
     *  forward-compatibility rule: unknown fields never crash an older APK, and every
     *  optional field's default is written back out so a round-trip is lossless. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** [source] is a filename used only in error messages. */
    fun parseJob(source: String, text: String): JobDefinition =
        try {
            json.decodeFromString(JobDefinition.serializer(), text)
        } catch (e: SerializationException) {
            throw ContentValidationException(listOf(ContentIssue(source, "failed to parse: ${e.message}")))
        } catch (e: IllegalArgumentException) {
            throw ContentValidationException(listOf(ContentIssue(source, "failed to parse: ${e.message}")))
        }

    fun parseMonster(source: String, text: String): MonsterDefinition =
        try {
            json.decodeFromString(MonsterDefinition.serializer(), text)
        } catch (e: SerializationException) {
            throw ContentValidationException(listOf(ContentIssue(source, "failed to parse: ${e.message}")))
        } catch (e: IllegalArgumentException) {
            throw ContentValidationException(listOf(ContentIssue(source, "failed to parse: ${e.message}")))
        }

    /**
     * Maps are filename -> file text. Parses, then validates.
     *
     * @throws ContentValidationException carrying every issue found.
     */
    fun load(
        jobTexts: Map<String, String>,
        monsterTexts: Map<String, String>,
    ): ContentCatalog {
        val issues = mutableListOf<ContentIssue>()

        val jobs = mutableListOf<JobDefinition>()
        for ((source, text) in jobTexts) {
            try {
                jobs += parseJob(source, text)
            } catch (e: ContentValidationException) {
                issues += e.issues
            }
        }

        val monsters = mutableListOf<MonsterDefinition>()
        for ((source, text) in monsterTexts) {
            try {
                monsters += parseMonster(source, text)
            } catch (e: ContentValidationException) {
                issues += e.issues
            }
        }

        issues += ContentValidator.validate(jobs, monsters)

        if (issues.isNotEmpty()) {
            throw ContentValidationException(issues)
        }
        return ContentCatalog(jobs, monsters)
    }
}
