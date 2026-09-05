package com.esper.engine.geometry

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Equirectangular local projection anchored at one point.
 *
 * Accurate to well under 1 cm over a combat board, which is two orders of
 * magnitude finer than the 1 m cell, so the flat-earth approximation costs
 * nothing at this scale.
 */
class LocalTangentPlane(val anchor: GeoPoint) {
    private val anchorLatRad = Math.toRadians(anchor.lat)
    private val cosAnchorLat = max(cos(anchorLatRad), MIN_COS_LATITUDE)

    fun toLocal(point: GeoPoint): LocalMetres {
        val dLat = Math.toRadians(point.lat - anchor.lat)
        val dLon = Math.toRadians(point.lon - anchor.lon)
        val north = dLat * EARTH_RADIUS_METRES
        val east = dLon * EARTH_RADIUS_METRES * cosAnchorLat
        return LocalMetres(east, north)
    }

    fun toGeo(local: LocalMetres): GeoPoint {
        val dLat = local.north / EARTH_RADIUS_METRES
        val dLon = local.east / (EARTH_RADIUS_METRES * cosAnchorLat)
        return GeoPoint(
            lat = anchor.lat + Math.toDegrees(dLat),
            lon = anchor.lon + Math.toDegrees(dLon),
        )
    }

    /** Great-circle distance (haversine) — exact for a sphere, independent of this plane's anchor. */
    fun distanceMetres(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        val c = 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        return EARTH_RADIUS_METRES * c
    }

    companion object {
        const val EARTH_RADIUS_METRES: Double = 6_371_008.8

        /** cos(latitude) is clamped to this to stay finite at the poles. */
        const val MIN_COS_LATITUDE: Double = 1e-6
    }
}
