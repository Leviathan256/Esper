package com.esper.engine.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HexCoordTest {

    @Test
    fun `distance to self is zero`() {
        val h = HexCoord(3, -2)
        assertEquals(0, h.distanceTo(h))
    }

    @Test
    fun `distance to each neighbor is one`() {
        val h = HexCoord(2, -1)
        for (n in h.neighbors()) {
            assertEquals(1, h.distanceTo(n), "neighbor $n should be distance 1 from $h")
        }
    }

    @Test
    fun `distance is symmetric`() {
        val pairs = listOf(
            HexCoord(0, 0) to HexCoord(4, -2),
            HexCoord(3, -1) to HexCoord(-2, 2),
            HexCoord(2, 3) to HexCoord(-1, -4),
            HexCoord(-5, 5) to HexCoord(5, -5),
        )
        for ((a, b) in pairs) {
            assertEquals(a.distanceTo(b), b.distanceTo(a), "distance should be symmetric for $a, $b")
        }
    }

    @Test
    fun `distance matches hand-computed values for far pairs`() {
        assertEquals(4, HexCoord(0, 0).distanceTo(HexCoord(4, -2)))
        assertEquals(5, HexCoord(3, -1).distanceTo(HexCoord(-2, 2)))
        assertEquals(10, HexCoord(2, 3).distanceTo(HexCoord(-1, -4)))
        assertEquals(10, HexCoord(-5, 5).distanceTo(HexCoord(5, -5)))
        assertEquals(0, HexCoord(7, -3).distanceTo(HexCoord(7, -3)))
    }

    @Test
    fun `neighbors always returns 6 distinct coords`() {
        val origins = listOf(HexCoord(0, 0), HexCoord(5, -3), HexCoord(-10, 4))
        for (origin in origins) {
            val neighbors = origin.neighbors()
            assertEquals(6, neighbors.size)
            assertEquals(6, neighbors.toSet().size, "neighbors of $origin should be distinct")
            assertTrue(neighbors.none { it == origin })
        }
    }

    @Test
    fun `plus is componentwise addition`() {
        assertEquals(HexCoord(5, -1), HexCoord(2, 3) + HexCoord(3, -4))
    }

    @Test
    fun `s is the implied third cube axis`() {
        assertEquals(-5, HexCoord(2, 3).s)
        assertEquals(0, HexCoord.ORIGIN.s)
    }

    @Test
    fun `directions are the fixed order and each maps to a neighbor`() {
        val expected = listOf(
            HexCoord(1, 0), HexCoord(1, -1), HexCoord(0, -1),
            HexCoord(-1, 0), HexCoord(-1, 1), HexCoord(0, 1),
        )
        assertEquals(expected, HexCoord.DIRECTIONS)
        assertEquals(expected, HexCoord.ORIGIN.neighbors())
    }
}
