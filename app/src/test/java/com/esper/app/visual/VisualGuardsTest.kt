package com.esper.app.visual

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The source guards of docs/VISUALS.md §7: the palette in `EsperColors.kt` is the only place a
 * colour is written, nothing is drawn from a raster, and no font is bundled. Reads `src/main`
 * by path; unit tests run with `app/` as the working directory.
 */
class VisualGuardsTest {
    private val main = File("src/main")
    private val res = File(main, "res")

    private val namedColour = Regex("""\bColor\.([A-Za-z]+)""")
    private val allowedNames = setOf("Transparent", "Unspecified")
    private val hexLiteral = Regex("""\b0x([0-9A-Fa-f]{8}|[0-9A-Fa-f]{6})(?![0-9A-Fa-f_])""")
    private val xmlHex = Regex("""#([0-9A-Fa-f]{8}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{3,4})\b""")

    /** Each line of [files] that [bad] flags, as `path:line: text`. */
    private fun offenders(files: List<File>, bad: (String) -> Boolean): List<String> =
        files.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line -> if (bad(line)) "${file.path}:${i + 1}: ${line.trim()}" else null }
        }

    @Test
    fun `no colour is written outside the palette`() {
        val kotlin = main.walk().filter { it.isFile && it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.endsWith("ui/theme/EsperColors.kt") }.toList()
        assertTrue("found no Kotlin under ${main.absolutePath}", kotlin.size > 10)
        val found = offenders(kotlin) { line ->
            "Color(0x" in line ||
                namedColour.findAll(line).any { it.groupValues[1] !in allowedNames } ||
                ("olor" in line && hexLiteral.containsMatchIn(line))
        }
        assertTrue("colour literals outside EsperColors.kt:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun `no colour is written in resources but the launcher colours`() {
        val xml = res.walk().filter { it.isFile && it.extension == "xml" }
            .filterNot { it.invariantSeparatorsPath.endsWith("values/colors.xml") }.toList()
        assertTrue("found no resources under ${res.absolutePath}", xml.isNotEmpty())
        val found = offenders(xml) { xmlHex.containsMatchIn(it) }
        assertTrue("colour literals in resources:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun `no raster image in resources`() {
        val rasters = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
        val found = res.walk().filter { it.isFile && it.extension.lowercase() in rasters }.toList()
        assertTrue("raster images: $found", found.isEmpty())
    }

    @Test
    fun `no font is bundled`() {
        assertTrue("found nothing under ${main.absolutePath}", main.isDirectory)
        val found = main.walk().filter { it.isFile && it.extension.lowercase() in setOf("ttf", "otf") }.toList()
        assertTrue("font files: $found", found.isEmpty())
        assertTrue("res/font exists", !File(res, "font").exists())
    }
}
