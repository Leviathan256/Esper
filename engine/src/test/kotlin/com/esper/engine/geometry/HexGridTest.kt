package com.esper.engine.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HexGridTest {

    @Test
    fun `cellsWithinRadius size matches the closed-form for n 0 through 15`() {
        for (n in 0..15) {
            val expected = 3 * n * (n + 1) + 1
            val actual = HexGrid.cellsWithinRadius(HexCoord.ORIGIN, n).size
            assertEquals(expected, actual, "radius $n")
        }
    }

    @Test
    fun `cellsWithinRadius 12 has 469 cells`() {
        assertEquals(469, HexGrid.cellsWithinRadius(HexCoord.ORIGIN, 12).size)
    }

    @Test
    fun `cellsWithinRadius 0 is only the center`() {
        assertEquals(setOf(HexCoord.ORIGIN), HexGrid.cellsWithinRadius(HexCoord.ORIGIN, 0))
    }

    @Test
    fun `cellsWithinRadius contains only cells within distance`() {
        val n = 5
        val cells = HexGrid.cellsWithinRadius(HexCoord.ORIGIN, n)
        for (cell in cells) {
            assertTrue(HexCoord.ORIGIN.distanceTo(cell) <= n)
        }
    }

    @Test
    fun `ring returns only cells at exactly the given radius`() {
        for (n in 0..8) {
            val ring = HexGrid.ring(HexCoord.ORIGIN, n)
            assertTrue(ring.all { HexCoord.ORIGIN.distanceTo(it) == n }, "ring $n contains a wrong-distance cell")
        }
    }

    @Test
    fun `ring 0 is just the center`() {
        assertEquals(setOf(HexCoord.ORIGIN), HexGrid.ring(HexCoord.ORIGIN, 0))
    }

    @Test
    fun `ring union across radii equals cellsWithinRadius`() {
        val n = 6
        val union = (0..n).flatMap { HexGrid.ring(HexCoord.ORIGIN, it) }.toSet()
        assertEquals(HexGrid.cellsWithinRadius(HexCoord.ORIGIN, n), union)
    }

    @Test
    fun `reachableCells includes the origin and respects range`() {
        val allowed = HexGrid.cellsWithinRadius(HexCoord.ORIGIN, 10)
        val reachable = HexGrid.reachableCells(HexCoord.ORIGIN, 3, allowed, emptySet())
        assertTrue(HexCoord.ORIGIN in reachable)
        assertEquals(HexGrid.cellsWithinRadius(HexCoord.ORIGIN, 3), reachable)
        assertTrue(reachable.all { HexCoord.ORIGIN.distanceTo(it) <= 3 })
    }

    @Test
    fun `reachableCells stays inside allowed`() {
        val allowed = HexGrid.cellsWithinRadius(HexCoord.ORIGIN, 2)
        val reachable = HexGrid.reachableCells(HexCoord.ORIGIN, 10, allowed, emptySet())
        assertEquals(allowed, reachable)
        assertTrue(reachable.all { it in allowed })
    }

    @Test
    fun `reachableCells excludes blocked cells`() {
        val allowed = HexGrid.cellsWithinRadius(HexCoord.ORIGIN, 3)
        val blocked = setOf(HexCoord(1, 0))
        val reachable = HexGrid.reachableCells(HexCoord.ORIGIN, 3, allowed, blocked)
        assertFalse(HexCoord(1, 0) in reachable)
        assertTrue(HexCoord.ORIGIN in reachable)
    }

    @Test
    fun `reachableCells cannot path through a solid ring of blockers`() {
        val allowed = HexGrid.cellsWithinRadius(HexCoord.ORIGIN, 5)
        val wall = HexGrid.ring(HexCoord.ORIGIN, 1)
        val reachable = HexGrid.reachableCells(HexCoord.ORIGIN, 5, allowed, wall)
        assertEquals(setOf(HexCoord.ORIGIN), reachable, "should be trapped inside the ring of blockers")
    }

    @Test
    fun `reachableCells with a partial ring can escape through the gap`() {
        val allowed = HexGrid.cellsWithinRadius(HexCoord.ORIGIN, 5)
        val fullWall = HexGrid.ring(HexCoord.ORIGIN, 1)
        val gapCell = fullWall.first()
        val wallWithGap = fullWall - gapCell
        val reachable = HexGrid.reachableCells(HexCoord.ORIGIN, 5, allowed, wallWithGap)
        assertTrue(reachable.size > 1, "should escape through the gap at $gapCell")
        assertTrue(gapCell in reachable)
    }

    @Test
    fun `reachableCells excludes cells beyond move range even when allowed and unblocked`() {
        val allowed = HexGrid.cellsWithinRadius(HexCoord.ORIGIN, 10)
        val reachable = HexGrid.reachableCells(HexCoord.ORIGIN, 2, allowed, emptySet())
        assertFalse(HexCoord(3, 0) in reachable)
        assertTrue(HexCoord(2, 0) in reachable)
    }
}
