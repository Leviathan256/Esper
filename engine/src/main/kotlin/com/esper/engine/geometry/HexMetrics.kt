package com.esper.engine.geometry

import kotlin.math.sqrt

/**
 * Pointy-top hex geometry in metres.
 *
 * The MVP deviation from H3 lives here: a pure-Kotlin grid with no JNI, sized to
 * match H3 res 15 closely enough that combat plays the same. See
 * docs/GAME_DESIGN.md, Combat > Grid.
 */
object HexMetrics {
    /** Flat-to-flat width of one cell. 1 m, per GAME_DESIGN (H3 res 15 is 1.03 m). */
    const val CELL_ACROSS_FLATS_METRES: Double = 1.0

    /** Centre-to-corner. [CELL_ACROSS_FLATS_METRES] / sqrt(3). */
    val circumradiusMetres: Double = CELL_ACROSS_FLATS_METRES / sqrt(3.0)

    /**
     * Pointy-top, with east = x and north = y:
     * ```
     * east  = R * (sqrt(3)*q + sqrt(3)/2 * r)
     * north = R * (3/2 * r)
     * ```
     */
    fun axialToLocal(hex: HexCoord): LocalMetres = TODO("implemented by engine-geometry")

    /**
     * ```
     * q = (sqrt(3)/3 * east - 1/3 * north) / R
     * r = (2/3 * north) / R
     * ```
     * then cube-round.
     */
    fun localToNearestAxial(local: LocalMetres): HexCoord = TODO("implemented by engine-geometry")

    /** 6 corners at angles 60°*i - 30°, i = 0..5, counter-clockwise. */
    fun cornersLocal(hex: HexCoord): List<LocalMetres> = TODO("implemented by engine-geometry")
}
