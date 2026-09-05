package com.esper.engine.geometry

/** Set operations over the hex lattice. Pure maths — no board, no units. */
object HexGrid {
    /** Every cell within [radiusCells] of [center]. `size == 3*n*(n+1) + 1`. */
    fun cellsWithinRadius(center: HexCoord, radiusCells: Int): Set<HexCoord> =
        TODO("implemented by engine-geometry")

    /** Only the cells at exactly [radiusCells] from [center]. */
    fun ring(center: HexCoord, radiusCells: Int): Set<HexCoord> =
        TODO("implemented by engine-geometry")

    /**
     * BFS. Excludes [blocked]; includes [from]; every result is within [allowed].
     *
     * BFS rather than a distance filter on purpose: a unit must not be able to hop
     * a wall of bodies just because the far side is within its move range.
     */
    fun reachableCells(
        from: HexCoord,
        moveRangeCells: Int,
        allowed: Set<HexCoord>,
        blocked: Set<HexCoord>,
    ): Set<HexCoord> = TODO("implemented by engine-geometry")
}
