package com.esper.engine.geometry

/**
 * A real-world position in degrees.
 *
 * Deliberately not Android's or osmdroid's `GeoPoint`: the engine has zero Android
 * imports so it can be unit-tested on a plain JVM. The Android layer converts at
 * the boundary, aliasing the osmdroid type at its import sites.
 */
data class GeoPoint(val lat: Double, val lon: Double)

/** Metres east and north of a [LocalTangentPlane]'s anchor. */
data class LocalMetres(val east: Double, val north: Double)
