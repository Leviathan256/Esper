package com.esper.engine.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class HexMetricsTest {

    private fun distance(a: LocalMetres, b: LocalMetres): Double =
        hypot(a.east - b.east, a.north - b.north)

    @Test
    fun `circumradius derives from cell width`() {
        assertEquals(
            HexMetrics.CELL_ACROSS_FLATS_METRES / kotlin.math.sqrt(3.0),
            HexMetrics.circumradiusMetres,
            1e-12,
        )
    }

    @Test
    fun `origin projects to the local origin`() {
        val local = HexMetrics.axialToLocal(HexCoord.ORIGIN)
        assertEquals(0.0, local.east, 1e-9)
        assertEquals(0.0, local.north, 1e-9)
    }

    @Test
    fun `localToNearestAxial round-trips axialToLocal for every cell within radius 15`() {
        val cells = HexGrid.cellsWithinRadius(HexCoord.ORIGIN, 15)
        assertEquals(3 * 15 * 16 + 1, cells.size)
        for (hex in cells) {
            val local = HexMetrics.axialToLocal(hex)
            val roundTripped = HexMetrics.localToNearestAxial(local)
            assertEquals(hex, roundTripped, "round trip failed for $hex -> $local -> $roundTripped")
        }
    }

    @Test
    fun `localToNearestAxial snaps a point offset from centre back to the same hex`() {
        val hex = HexCoord(3, -2)
        val center = HexMetrics.axialToLocal(hex)
        val nudged = LocalMetres(center.east + 0.05, center.north - 0.03)
        assertEquals(hex, HexMetrics.localToNearestAxial(nudged))
    }

    @Test
    fun `cornersLocal returns 6 distinct points each circumradius from centre`() {
        for (hex in listOf(HexCoord.ORIGIN, HexCoord(2, -3), HexCoord(-4, 1))) {
            val center = HexMetrics.axialToLocal(hex)
            val corners = HexMetrics.cornersLocal(hex)
            assertEquals(6, corners.size)
            assertEquals(6, corners.toSet().size, "corners of $hex should be distinct")
            for (corner in corners) {
                assertEquals(
                    HexMetrics.circumradiusMetres,
                    distance(center, corner),
                    1e-9,
                    "corner $corner should be circumradius from centre of $hex",
                )
            }
        }
    }

    @Test
    fun `cornersLocal are at angles 60 times i minus 30 degrees in order`() {
        val corners = HexMetrics.cornersLocal(HexCoord.ORIGIN)
        val r = HexMetrics.circumradiusMetres
        for (i in 0 until 6) {
            val angle = Math.toRadians(60.0 * i - 30.0)
            val expected = LocalMetres(r * kotlin.math.cos(angle), r * kotlin.math.sin(angle))
            assertEquals(expected.east, corners[i].east, 1e-9, "corner $i east")
            assertEquals(expected.north, corners[i].north, 1e-9, "corner $i north")
        }
    }
}
