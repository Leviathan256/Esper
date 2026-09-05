package com.esper.engine.geometry

import kotlin.math.abs

/**
 * Axial hex coordinate.
 *
 * POINTY-TOP orientation — the whole render layer depends on this, so it is not a
 * detail to flip later.
 */
data class HexCoord(val q: Int, val r: Int) {
    /** The implied third cube axis. Always `-q - r`. */
    val s: Int get() = -q - r

    /** Hex distance: `(|dq| + |dr| + |dq+dr|) / 2`. */
    fun distanceTo(other: HexCoord): Int {
        val dq = other.q - q
        val dr = other.r - r
        return (abs(dq) + abs(dr) + abs(dq + dr)) / 2
    }

    /** The 6 adjacent cells, in [DIRECTIONS] order. */
    fun neighbors(): List<HexCoord> = DIRECTIONS.map { this + it }

    operator fun plus(other: HexCoord): HexCoord = HexCoord(q + other.q, r + other.r)

    companion object {
        val ORIGIN: HexCoord = HexCoord(0, 0)

        /** Fixed order. Rendering, ring placement and tie-breaks all rely on it. */
        val DIRECTIONS: List<HexCoord> = listOf(
            HexCoord(1, 0),
            HexCoord(1, -1),
            HexCoord(0, -1),
            HexCoord(-1, 0),
            HexCoord(-1, 1),
            HexCoord(0, 1),
        )
    }
}
