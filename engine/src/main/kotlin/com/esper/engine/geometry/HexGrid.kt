package com.esper.engine.geometry

/** Set operations over the hex lattice. Pure maths — no board, no units. */
object HexGrid {
    /** Every cell within [radiusCells] of [center]. `size == 3*n*(n+1) + 1`. */
    fun cellsWithinRadius(center: HexCoord, radiusCells: Int): Set<HexCoord> {
        if (radiusCells < 0) return emptySet()
        val result = mutableSetOf<HexCoord>()
        for (dq in -radiusCells..radiusCells) {
            val rMin = maxOf(-radiusCells, -dq - radiusCells)
            val rMax = minOf(radiusCells, -dq + radiusCells)
            for (dr in rMin..rMax) {
                result.add(center + HexCoord(dq, dr))
            }
        }
        return result
    }

    /** Only the cells at exactly [radiusCells] from [center]. */
    fun ring(center: HexCoord, radiusCells: Int): Set<HexCoord> {
        if (radiusCells < 0) return emptySet()
        if (radiusCells == 0) return setOf(center)
        return cellsWithinRadius(center, radiusCells) - cellsWithinRadius(center, radiusCells - 1)
    }

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
    ): Set<HexCoord> {
        val visitedDistance = mutableMapOf(from to 0)
        val frontier = ArrayDeque<HexCoord>()
        frontier.add(from)
        while (frontier.isNotEmpty()) {
            val current = frontier.removeFirst()
            val distance = visitedDistance.getValue(current)
            if (distance >= moveRangeCells) continue
            for (neighbor in current.neighbors()) {
                if (neighbor in visitedDistance) continue
                if (neighbor !in allowed) continue
                if (neighbor in blocked) continue
                visitedDistance[neighbor] = distance + 1
                frontier.add(neighbor)
            }
        }
        return visitedDistance.keys
    }
}
