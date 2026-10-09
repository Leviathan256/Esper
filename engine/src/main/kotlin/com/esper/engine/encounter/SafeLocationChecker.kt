package com.esper.engine.encounter

import com.esper.engine.geometry.GeoPoint
import com.esper.engine.geometry.LocalMetres
import com.esper.engine.geometry.LocalTangentPlane
import kotlin.math.hypot

/**
 * The seam for "never seed a player onto a road, railway, water, or private
 * property" (docs/GAME_DESIGN.md, Combat safety rules).
 */
fun interface SafeLocationChecker {
    fun isSafe(point: GeoPoint): Boolean
}

/**
 * Something a player must never be sent onto, already buffered: each hazard owns
 * the distance it must be kept at, so policy (how wide a road is, how much margin
 * a building gets) lives in whoever builds the list, not in the geometry.
 */
sealed interface Hazard {
    /** A line feature (road, railway, river): unsafe within [bufferMetres] of the centreline. */
    data class Corridor(val path: List<GeoPoint>, val bufferMetres: Double) : Hazard

    /**
     * A polygon (water body, building, private land): unsafe inside, or within
     * [bufferMetres] of its boundary. Inside is decided even-odd across every
     * path, so a multipolygon's outer and inner ways need no ring assembly.
     */
    data class Area(val boundary: List<List<GeoPoint>>, val bufferMetres: Double) : Hazard

    /**
     * OSM coastline: land is on the left of the way's direction. Unsafe within
     * [bufferMetres], or on the sea side of the nearest segment — the sea itself
     * is never a polygon in OSM, so this is the only way to see it.
     */
    data class Coastline(val path: List<GeoPoint>, val bufferMetres: Double) : Hazard
}

/** Rejects any point inside or too close to a known [Hazard]. */
class GeodataSafetyChecker(private val hazards: List<Hazard>) : SafeLocationChecker {

    override fun isSafe(point: GeoPoint): Boolean {
        // Anchored at the point under test, so the point is the local origin.
        val plane = LocalTangentPlane(point)
        return hazards.none { it.covers(plane) }
    }

    private fun Hazard.covers(plane: LocalTangentPlane): Boolean = when (this) {
        is Hazard.Corridor -> segments(plane, path).any { distanceToOrigin(it) <= bufferMetres }
        is Hazard.Area -> {
            val segments = boundary.flatMap { segments(plane, it) }
            crossings(segments) % 2 == 1 || segments.any { distanceToOrigin(it) <= bufferMetres }
        }
        is Hazard.Coastline -> {
            val nearest = segments(plane, path).minByOrNull { distanceToOrigin(it) }
            nearest != null && (distanceToOrigin(nearest) <= bufferMetres || isRightOf(nearest))
        }
    }

    private fun segments(plane: LocalTangentPlane, path: List<GeoPoint>): List<Pair<LocalMetres, LocalMetres>> =
        path.map(plane::toLocal).zipWithNext()

    private fun distanceToOrigin(segment: Pair<LocalMetres, LocalMetres>): Double {
        val (a, b) = segment
        val dx = b.east - a.east
        val dy = b.north - a.north
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0.0) 0.0 else ((-a.east * dx - a.north * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(a.east + t * dx, a.north + t * dy)
    }

    /** Ray cast from the origin due east; half-open on north so a shared vertex counts once. */
    private fun crossings(segments: List<Pair<LocalMetres, LocalMetres>>): Int = segments.count { (a, b) ->
        (a.north > 0.0) != (b.north > 0.0) &&
            a.east + (0.0 - a.north) * (b.east - a.east) / (b.north - a.north) > 0.0
    }

    private fun isRightOf(segment: Pair<LocalMetres, LocalMetres>): Boolean {
        val (a, b) = segment
        val cross = (b.east - a.east) * (0.0 - a.north) - (b.north - a.north) * (0.0 - a.east)
        return cross < 0.0
    }
}
