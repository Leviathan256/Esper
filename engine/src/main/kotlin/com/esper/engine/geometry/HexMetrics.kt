package com.esper.engine.geometry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.round

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
    fun axialToLocal(hex: HexCoord): LocalMetres {
        val r = circumradiusMetres
        val east = r * (sqrt(3.0) * hex.q + sqrt(3.0) / 2.0 * hex.r)
        val north = r * (1.5 * hex.r)
        return LocalMetres(east, north)
    }

    /**
     * ```
     * q = (sqrt(3)/3 * east - 1/3 * north) / R
     * r = (2/3 * north) / R
     * ```
     * then cube-round.
     */
    fun localToNearestAxial(local: LocalMetres): HexCoord {
        val r = circumradiusMetres
        val qFrac = (sqrt(3.0) / 3.0 * local.east - 1.0 / 3.0 * local.north) / r
        val rFrac = (2.0 / 3.0 * local.north) / r
        return cubeRound(qFrac, rFrac)
    }

    /** 6 corners at angles 60°*i - 30°, i = 0..5, counter-clockwise. */
    fun cornersLocal(hex: HexCoord): List<LocalMetres> {
        val center = axialToLocal(hex)
        val r = circumradiusMetres
        return (0 until 6).map { i ->
            val angle = Math.toRadians(60.0 * i - 30.0)
            LocalMetres(center.east + r * cos(angle), center.north + r * sin(angle))
        }
    }

    /** Rounds fractional axial coordinates to the nearest hex via the cube-coordinate method. */
    private fun cubeRound(qFrac: Double, rFrac: Double): HexCoord {
        val xFrac = qFrac
        val zFrac = rFrac
        val yFrac = -xFrac - zFrac

        var rx = round(xFrac)
        var ry = round(yFrac)
        var rz = round(zFrac)

        val xDiff = abs(rx - xFrac)
        val yDiff = abs(ry - yFrac)
        val zDiff = abs(rz - zFrac)

        if (xDiff > yDiff && xDiff > zDiff) {
            rx = -ry - rz
        } else if (yDiff > zDiff) {
            ry = -rx - rz
        } else {
            rz = -rx - ry
        }

        return HexCoord(rx.toInt(), rz.toInt())
    }
}
