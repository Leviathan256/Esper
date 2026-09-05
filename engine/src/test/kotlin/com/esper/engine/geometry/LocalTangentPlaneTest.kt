package com.esper.engine.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cos

class LocalTangentPlaneTest {

    private val anchors = listOf(
        GeoPoint(0.0, 0.0),
        GeoPoint(37.7749, -122.4194),
        GeoPoint(-33.8688, 151.2093),
        GeoPoint(64.1466, -21.9426),
    )

    @Test
    fun `toLocal of the anchor itself is the local origin`() {
        for (anchor in anchors) {
            val plane = LocalTangentPlane(anchor)
            val local = plane.toLocal(anchor)
            assertEquals(0.0, local.east, 1e-9)
            assertEquals(0.0, local.north, 1e-9)
        }
    }

    @Test
    fun `toGeo of the local origin is the anchor`() {
        for (anchor in anchors) {
            val plane = LocalTangentPlane(anchor)
            val geo = plane.toGeo(LocalMetres(0.0, 0.0))
            assertEquals(anchor.lat, geo.lat, 1e-12)
            assertEquals(anchor.lon, geo.lon, 1e-12)
        }
    }

    @Test
    fun `toGeo toLocal round trips within 1e-9 degrees for points up to 100m from anchor`() {
        for (anchor in anchors) {
            val plane = LocalTangentPlane(anchor)
            // Points offset up to 100m north/east/etc from the anchor.
            val deltaLatDeg = Math.toDegrees(100.0 / LocalTangentPlane.EARTH_RADIUS_METRES)
            val cosLat = cos(Math.toRadians(anchor.lat)).coerceAtLeast(LocalTangentPlane.MIN_COS_LATITUDE)
            val deltaLonDeg = Math.toDegrees(100.0 / (LocalTangentPlane.EARTH_RADIUS_METRES * cosLat))

            val candidates = listOf(
                anchor,
                GeoPoint(anchor.lat + deltaLatDeg, anchor.lon),
                GeoPoint(anchor.lat - deltaLatDeg, anchor.lon),
                GeoPoint(anchor.lat, anchor.lon + deltaLonDeg),
                GeoPoint(anchor.lat, anchor.lon - deltaLonDeg),
                GeoPoint(anchor.lat + deltaLatDeg / 2, anchor.lon + deltaLonDeg / 2),
            )
            for (point in candidates) {
                val roundTripped = plane.toGeo(plane.toLocal(point))
                assertEquals(point.lat, roundTripped.lat, 1e-9, "lat round trip for anchor $anchor, point $point")
                assertEquals(point.lon, roundTripped.lon, 1e-9, "lon round trip for anchor $anchor, point $point")
            }
        }
    }

    @Test
    fun `distanceMetres between points 10m apart is 10 within 0,01`() {
        for (anchor in anchors) {
            val plane = LocalTangentPlane(anchor)
            val other = plane.toGeo(LocalMetres(east = 10.0, north = 0.0))
            assertEquals(10.0, plane.distanceMetres(anchor, other), 0.01)

            val diagonal = plane.toGeo(LocalMetres(east = 6.0, north = 8.0)) // 3-4-5 triangle * 2 = 10
            assertEquals(10.0, plane.distanceMetres(anchor, diagonal), 0.01)
        }
    }

    @Test
    fun `distanceMetres from a point to itself is zero`() {
        val plane = LocalTangentPlane(anchors[0])
        for (anchor in anchors) {
            assertEquals(0.0, plane.distanceMetres(anchor, anchor), 1e-9)
        }
    }

    @Test
    fun `distanceMetres is symmetric`() {
        val plane = LocalTangentPlane(anchors[0])
        val a = anchors[1]
        val b = anchors[2]
        assertEquals(plane.distanceMetres(a, b), plane.distanceMetres(b, a), 1e-6)
    }

    @Test
    fun `cos latitude clamp keeps toLocal and toGeo finite near the poles`() {
        val anchor = GeoPoint(89.9999, 0.0)
        val plane = LocalTangentPlane(anchor)
        val local = plane.toLocal(GeoPoint(90.0, 45.0))
        assertTrue(local.east.isFinite())
        assertTrue(local.north.isFinite())
        val geo = plane.toGeo(LocalMetres(50.0, 50.0))
        assertTrue(geo.lat.isFinite())
        assertTrue(geo.lon.isFinite())
    }
}
