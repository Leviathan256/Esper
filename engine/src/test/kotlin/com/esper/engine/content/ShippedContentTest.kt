package com.esper.engine.content

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Validates the actual `/content` files shipped in this repo, straight off disk.
 * This is the check that fails a PR carrying malformed game data — the design
 * doc's job-pipeline rule 3 — so it must run against the real files, not fixtures.
 */
class ShippedContentTest {

    private val contentDir: File by lazy {
        val path = System.getProperty("esper.contentDir")
            ?: fail(
                "system property 'esper.contentDir' is not set — engine/build.gradle.kts " +
                    "must pass it to the test task (see engine-content package spec); " +
                    "cannot validate shipped content without it.",
            )
        val dir = File(path)
        assertTrue(dir.isDirectory, "esper.contentDir '$path' is not a directory")
        dir
    }

    private fun readTexts(subdir: String): Map<String, String> {
        val dir = File(contentDir, subdir)
        assertTrue(dir.isDirectory, "expected directory '$dir' to exist")
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: fail("could not list files in '$dir'")
        assertTrue(files.isNotEmpty(), "expected at least one *.json file in '$dir'")
        return files.associate { it.name to it.readText() }
    }

    private fun loadShippedCatalog(): ContentCatalog {
        val jobTexts = readTexts("jobs")
        val monsterTexts = readTexts("monsters")
        return ContentLoader.load(jobTexts, monsterTexts)
    }

    @Test
    fun `shipped content loads with zero issues`() {
        val jobTexts = readTexts("jobs")
        val monsterTexts = readTexts("monsters")

        val jobs = jobTexts.map { (source, text) -> ContentLoader.parseJob(source, text) }
        val monsters = monsterTexts.map { (source, text) -> ContentLoader.parseMonster(source, text) }

        val issues = ContentValidator.validate(jobs, monsters)
        assertEquals(emptyList<ContentIssue>(), issues, "shipped content must validate cleanly")

        // And the full load() path succeeds too (parses AND validates).
        val catalog = ContentLoader.load(jobTexts, monsterTexts)
        assertEquals(jobs.size, catalog.jobs.size)
        assertEquals(monsters.size, catalog.monsters.size)
    }

    @Test
    fun `shipped jobs contain the expected ids`() {
        val catalog = loadShippedCatalog()
        val ids = catalog.jobs.map { it.id }.toSet()
        assertEquals(setOf("squire", "knight", "apprentice"), ids)
    }

    @Test
    fun `shipped monsters contain the expected ids`() {
        val catalog = loadShippedCatalog()
        val ids = catalog.monsters.map { it.id }.toSet()
        assertEquals(setOf("slime", "goblin", "wolf"), ids)
    }

    @Test
    fun `squire and knight form a real unlock chain`() {
        val catalog = loadShippedCatalog()
        val squire = catalog.job("squire")
        val knight = catalog.job("knight")
        assertNotNull(squire, "squire must be shipped")
        assertNotNull(knight, "knight must be shipped")
        assertTrue(squire!!.unlocks.contains("knight"), "squire.unlocks must contain knight")
        assertTrue(knight!!.prerequisites.contains("squire"), "knight.prerequisites must contain squire")
    }

    @Test
    fun `shipped job graph is acyclic`() {
        val catalog = loadShippedCatalog()
        assertNull(ContentValidator.findJobGraphCycle(catalog.jobs), "shipped jobs must not form a cycle")
    }
}
