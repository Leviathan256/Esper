package com.esper.engine.geometry

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
    fun distanceTo(other: HexCoord): Int = TODO("implemented by engine-geometry")

    /** The 6 adjacent cells, in [DIRECTIONS] order. */
    fun neighbors(): List<HexCoord> = TODO("implemented by engine-geometry")

    operator fun plus(other: HexCoord): HexCoord = TODO("implemented by engine-geometry")

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
