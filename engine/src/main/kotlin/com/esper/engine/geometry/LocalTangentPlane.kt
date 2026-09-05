package com.esper.engine.geometry

/**
 * Equirectangular local projection anchored at one point.
 *
 * Accurate to well under 1 cm over a combat board, which is two orders of
 * magnitude finer than the 1 m cell, so the flat-earth approximation costs
 * nothing at this scale.
 */
class LocalTangentPlane(val anchor: GeoPoint) {
    fun toLocal(point: GeoPoint): LocalMetres = TODO("implemented by engine-geometry")

    fun toGeo(local: LocalMetres): GeoPoint = TODO("implemented by engine-geometry")

    fun distanceMetres(a: GeoPoint, b: GeoPoint): Double = TODO("implemented by engine-geometry")

    companion object {
        const val EARTH_RADIUS_METRES: Double = 6_371_008.8

        /** cos(latitude) is clamped to this to stay finite at the poles. */
        const val MIN_COS_LATITUDE: Double = 1e-6
    }
}
