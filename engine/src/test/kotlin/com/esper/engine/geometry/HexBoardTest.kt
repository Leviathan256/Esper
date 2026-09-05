package com.esper.engine.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HexBoardTest {

    private val anchor = GeoPoint(51.5074, -0.1278)

    @Test
    fun `cells is cellsWithinRadius around the origin`() {
        val board = HexBoard(anchor, radiusCells = 4)
        assertEquals(HexGrid.cellsWithinRadius(HexCoord.ORIGIN, 4), board.cells)
        assertEquals(3 * 4 * 5 + 1, board.cells.size)
    }

    @Test
    fun `contains matches membership in cells`() {
        val board = HexBoard(anchor, radiusCells = 3)
        assertTrue(board.contains(HexCoord.ORIGIN))
        assertTrue(board.contains(HexCoord(3, 0)))
        assertFalse(board.contains(HexCoord(4, 0)))
    }

    @Test
    fun `centerGeo of the origin is the anchor`() {
        val board = HexBoard(anchor, radiusCells = 2)
        val center = board.centerGeo(HexCoord.ORIGIN)
        assertEquals(anchor.lat, center.lat, 1e-9)
        assertEquals(anchor.lon, center.lon, 1e-9)
    }

    @Test
    fun `cornersGeo returns 6 points forming a polygon around the cell centre`() {
        val board = HexBoard(anchor, radiusCells = 2)
        val hex = HexCoord(1, -1)
        val corners = board.cornersGeo(hex)
        assertEquals(6, corners.size)
        assertEquals(6, corners.toSet().size)

        val center = board.centerGeo(hex)
        for (corner in corners) {
            val distance = board.plane.distanceMetres(center, corner)
            assertEquals(HexMetrics.circumradiusMetres, distance, 1e-6)
        }
    }

    @Test
    fun `nearestCell of the anchor is the origin`() {
        val board = HexBoard(anchor, radiusCells = 3)
        assertEquals(HexCoord.ORIGIN, board.nearestCell(anchor))
    }

    @Test
    fun `nearestCell of a cell centre round trips through geo`() {
        val board = HexBoard(anchor, radiusCells = 6)
        for (hex in listOf(HexCoord(2, -1), HexCoord(-3, 2), HexCoord(0, 4))) {
            val geo = board.centerGeo(hex)
            assertEquals(hex, board.nearestCell(geo))
        }
    }

    @Test
    fun `nearestCell may fall outside cells and contains catches it`() {
        val board = HexBoard(anchor, radiusCells = 1)
        val farHex = HexCoord(10, -10)
        val geo = board.centerGeo(farHex)
        val nearest = board.nearestCell(geo)
        assertEquals(farHex, nearest)
        assertFalse(board.contains(nearest))
    }
}
