package com.esper.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * The palette of docs/VISUALS.md §4.1: the only colour literals in the app (`VisualGuardsTest`).
 * The launcher's two colours are repeated in `res/values/colors.xml`, the one exception.
 */
object EsperColors {
    /** Text, every outline. */
    val ink = Color(0xFF14171C)

    /** Secondary text. */
    val inkSoft = Color(0xFF454C57)

    /** Screen ground, cards, glyphs on tokens. */
    val paper = Color(0xFFFBF8F1)

    /** Wells: fields, stat tiles, the track of a bar. */
    val paperDim = Color(0xFFEFE9DB)

    /** Dividers only, never text. */
    val line = Color(0xFFCDC4B0)

    /** Your side, where you can go. */
    val ally = Color(0xFF1946A6)

    /** Hostile, what you can hit, errors. */
    val foe = Color(0xFFA02812)

    /** Units in nobody's fight. */
    val neutral = Color(0xFF48515D)

    /** Acting now, and the one primary action of a screen. Nothing else; always edged in [ink]. */
    val turn = Color(0xFFF5B800)

    /** HP fill, always on an [ink] track. */
    val vital = Color(0xFF35C46F)

    /** Green text ("Mastered"). */
    val vitalInk = Color(0xFF0E5A31)

    /** Things that grow: XP, job points, Ask Claude. */
    val esper = Color(0xFF722A8C)

    // Plate hues: only on plates, never for a side.
    val moss = Color(0xFF7C9A3C)
    val jade = Color(0xFF3DAE93)
    val slate = Color(0xFF73808F)
    val steel = Color(0xFF93A1B3)
    val violet = Color(0xFF7B57BF)
    val crimson = Color(0xFFB5333C)
    val ember = Color(0xFFD9782D)
    val bone = Color(0xFFD8CCB2)

    /** [hue] mixed 70% toward [paper]. */
    fun tint(hue: Color): Color = mix(hue, 30, paper)

    /** [hue] mixed 35% toward [ink]. */
    fun shade(hue: Color): Color = mix(hue, 65, ink)

    /** The plate's accent: [ember], or [bone] where ember would vanish. */
    fun accent(hue: Color): Color = if (hue == ember || hue == crimson) bone else ember

    /**
     * Per channel on 0..255 sRGB, in integers, a half rounding up, so the Rust sheet and the app
     * agree (`content/fixtures/look/mix.tsv`). Not Compose's `lerp`: that mixes in Oklab.
     */
    private fun mix(hue: Color, huePercent: Int, toward: Color): Color {
        val a = hue.toArgb()
        val b = toward.toArgb()
        fun channel(shift: Int) =
            (((a shr shift) and 0xFF) * huePercent + ((b shr shift) and 0xFF) * (100 - huePercent) + 50) / 100
        return Color(red = channel(16), green = channel(8), blue = channel(0))
    }
}
