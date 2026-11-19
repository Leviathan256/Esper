package com.esper.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.esper.app.ui.theme.EsperColors.ally
import com.esper.app.ui.theme.EsperColors.esper
import com.esper.app.ui.theme.EsperColors.foe
import com.esper.app.ui.theme.EsperColors.ink
import com.esper.app.ui.theme.EsperColors.inkSoft
import com.esper.app.ui.theme.EsperColors.line
import com.esper.app.ui.theme.EsperColors.neutral
import com.esper.app.ui.theme.EsperColors.paper
import com.esper.app.ui.theme.EsperColors.paperDim
import com.esper.app.ui.theme.EsperColors.turn
import com.esper.app.ui.theme.EsperColors.vital
import com.esper.app.ui.theme.EsperColors.vitalInk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

/**
 * The contrast floors of docs/VISUALS.md §4.1, plain and under the sun veil, and the plate
 * mixing against the shared fixture the Rust sheet also reads.
 */
class ThemeContrastTest {
    /** sRGB channels 0..1. */
    private class Rgb(val r: Double, val g: Double, val b: Double)

    private fun rgb(c: Color) = c.toArgb().let {
        Rgb(((it shr 16) and 0xFF) / 255.0, ((it shr 8) and 0xFF) / 255.0, (it and 0xFF) / 255.0)
    }

    private fun hex(s: String) = s.removePrefix("#").toInt(16).let {
        Rgb(((it shr 16) and 0xFF) / 255.0, ((it shr 8) and 0xFF) / 255.0, (it and 0xFF) / 255.0)
    }

    /** [top] at [alpha] over [under]. */
    private fun over(top: Rgb, alpha: Double, under: Rgb) = Rgb(
        top.r * alpha + under.r * (1 - alpha),
        top.g * alpha + under.g * (1 - alpha),
        top.b * alpha + under.b * (1 - alpha),
    )

    private val white = Rgb(1.0, 1.0, 1.0)
    private fun veiled(c: Rgb) = over(white, Spec.SUN_VEIL_ALPHA.toDouble(), c)

    private fun luminance(c: Rgb): Double {
        fun lin(x: Double) = if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        return 0.2126 * lin(c.r) + 0.7152 * lin(c.g) + 0.0722 * lin(c.b)
    }

    /** The WCAG ratio. */
    private fun contrast(a: Rgb, b: Rgb): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    /** The Fixture Town grounds the board covers (§5): grass, pond, path, tree. */
    private val grounds = listOf("#D6F4D9", "#B7D0D7", "#FFFFFF", "#BFD6B8").map(::hex)
    private val mats = grounds.map { over(rgb(paper), Spec.MAT_WASH_ALPHA.toDouble(), it) }
    private val moveWashes = mats.map { over(rgb(ally), Spec.MOVE_FILL_ALPHA.toDouble(), it) }

    /** Fails naming the row unless every pair holds [floor] plain and [veilFloor] under the veil. */
    private fun floor(row: String, floor: Double, veilFloor: Double, pairs: List<Pair<Rgb, Rgb>>) {
        val plain = pairs.minOf { (a, b) -> contrast(a, b) }
        val sun = pairs.minOf { (a, b) -> contrast(veiled(a), veiled(b)) }
        assertTrue("$row: $plain under the floor $floor", plain >= floor)
        assertTrue("$row under the veil: $sun under the floor $veilFloor", sun >= veilFloor)
    }

    private fun on(fg: List<Color>, bg: List<Color>) = fg.flatMap { f -> bg.map { rgb(f) to rgb(it) } }

    @Test
    fun `every row of the floors table holds, plain and under the sun veil`() {
        floor("ink on paper / paperDim", 14.0, 3.5, on(listOf(ink), listOf(paper, paperDim)))
        floor("inkSoft on paper / paperDim", 7.0, 2.4, on(listOf(inkSoft), listOf(paper, paperDim)))
        floor("coloured text on paper", 7.0, 2.6, on(listOf(ally, foe, vitalInk, esper), listOf(paper)))
        floor("paper glyph on a side", 7.0, 2.5, on(listOf(paper), listOf(ally, foe, neutral)))
        floor("esper bar on its track", 7.0, 2.5, on(listOf(esper), listOf(paperDim)))
        floor("ink on turn", 9.0, 2.8, on(listOf(ink), listOf(turn)))
        floor("vital on ink", 7.0, 2.5, on(listOf(vital), listOf(ink)))
        floor("token fill on the mat", 5.5, 2.4, listOf(ally, foe).flatMap { c -> mats.map { rgb(c) to it } })
        floor("token fill on the move wash", 4.4, 2.1, listOf(ally, foe).flatMap { c -> moveWashes.map { rgb(c) to it } })
    }

    @Test
    fun `the contrast arithmetic gives the documented ratios`() {
        // §4.1's own numbers, so a broken formula cannot pass every floor by accident.
        assertEquals(16.9, contrast(rgb(ink), rgb(paper)), 0.05)
        assertEquals(1.6, contrast(rgb(line), rgb(paper)), 0.05)
        assertEquals(1.14, contrast(rgb(ally), rgb(foe)), 0.005)
    }

    @Test
    fun `tint and shade match the sixteen shared mix values`() {
        val hues = mapOf(
            "moss" to EsperColors.moss, "jade" to EsperColors.jade, "slate" to EsperColors.slate,
            "steel" to EsperColors.steel, "violet" to EsperColors.violet, "crimson" to EsperColors.crimson,
            "ember" to EsperColors.ember, "bone" to EsperColors.bone,
        )
        val rows = File("../content/fixtures/look/mix.tsv").readLines().drop(1).filter { it.isNotBlank() }
        assertEquals(16, rows.size)
        for (row in rows) {
            val (fn, name, expected) = row.split('\t')
            val hue = hues.getValue(name)
            val got = when (fn) {
                "tint" -> EsperColors.tint(hue)
                "shade" -> EsperColors.shade(hue)
                else -> error("unknown fn $fn")
            }
            assertEquals("$fn($name)", expected.uppercase(), "#%06X".format(got.toArgb() and 0xFFFFFF))
        }
    }

    @Test
    fun `accent is ember, or bone on ember and crimson`() {
        assertEquals(EsperColors.ember, EsperColors.accent(EsperColors.moss))
        assertEquals(EsperColors.bone, EsperColors.accent(EsperColors.ember))
        assertEquals(EsperColors.bone, EsperColors.accent(EsperColors.crimson))
    }
}
