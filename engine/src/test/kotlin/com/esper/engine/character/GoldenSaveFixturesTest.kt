package com.esper.engine.character

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Golden save fixtures in `content/fixtures/save/`: `v<N>.json` is a save at schema
 * version N, and `v<N>.expected.json` is what [SaveCodec] re-encodes it to after
 * decode + migrate. The Rust `save_codec` reads the same files (ADR-0002), so the
 * two codecs cannot drift silently. v0 predates `schemaVersion` and is hand-written;
 * every later version is written by [SaveCodec.encode].
 */
class GoldenSaveFixturesTest {

    private val fixtureDir: File by lazy {
        val content = System.getProperty("esper.contentDir")
            ?: fail("system property 'esper.contentDir' is not set by engine/build.gradle.kts")
        File(content, "fixtures/save").also { assertTrue(it.isDirectory, "missing '$it'") }
    }

    @Test
    fun `there is one fixture per save schema version`() {
        val names = fixtureDir.list()!!.toSet()
        for (v in 0..CharacterState.CURRENT_SCHEMA_VERSION) {
            assertTrue("v$v.json" in names && "v$v.expected.json" in names, "missing golden fixture pair for schema v$v")
        }
    }

    @TestFactory
    fun `every fixture decodes, migrates and re-encodes to its expected JSON`(): List<DynamicTest> =
        fixtureDir.listFiles { f -> f.name.matches(Regex("""v\d+\.json""")) }!!.sorted().map { input ->
            DynamicTest.dynamicTest(input.name) {
                val expected = File(fixtureDir, input.name.replace(".json", ".expected.json")).readText().trimEnd()
                assertEquals(expected, SaveCodec.encode(SaveCodec.decode(input.readText())))
            }
        }
}
