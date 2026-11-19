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

    /**
     * True for a spot just inside a building a player may walk into, whose
     * outside is itself safe (issue #61). Only ever tried after every outdoor spot.
     */
    fun isSafeIndoors(point: GeoPoint): Boolean = false

    /** What makes [point] unsafe outdoors, for the player-facing notice; empty when safe or unknown. */
    fun blockers(point: GeoPoint): Set<HazardKind> = emptySet()
}

/** What a [Hazard] is, so the game can say what kept an Encounter from spawning. */
enum class HazardKind { BUILDING, ROAD, RAILWAY, WATER, PRIVATE }

/**
 * Something a player must never be sent onto, already buffered: each hazard owns
 * the distance it must be kept at, so policy (how wide a road is, how much margin
 * a building gets) lives in whoever builds the list, not in the geometry.
 */
sealed interface Hazard {
    val kind: HazardKind

    /** A line feature (road, railway, river): unsafe within [bufferMetres] of the centreline. */
    data class Corridor(val path: List<GeoPoint>, val bufferMetres: Double, override val kind: HazardKind) : Hazard

    /**
     * A polygon (water body, building, private land): unsafe inside, or within
     * [bufferMetres] of its boundary. Inside is decided even-odd across every
     * path, so a multipolygon's outer and inner ways need no ring assembly.
     * An [enterable] building may hold an Encounter just inside its walls.
     */
    data class Area(
        val boundary: List<List<GeoPoint>>,
        val bufferMetres: Double,
        override val kind: HazardKind,
        val enterable: Boolean = false,
    ) : Hazard

    /**
     * OSM coastline: land is on the left of the way's direction. Unsafe within
     * [bufferMetres], or on the sea side of the nearest segment — the sea itself
     * is never a polygon in OSM, so this is the only way to see it.
     */
    data class Coastline(val path: List<GeoPoint>, val bufferMetres: Double) : Hazard {
        override val kind: HazardKind get() = HazardKind.WATER
    }
}

/** Rejects any point inside or too close to a known [Hazard]. */
class GeodataSafetyChecker(private val hazards: List<Hazard>) : SafeLocationChecker {

    override fun isSafe(point: GeoPoint): Boolean {
        // Anchored at the point under test, so the point is the local origin.
        val plane = LocalTangentPlane(point)
        return hazards.none { it.covers(plane) }
    }

    override fun blockers(point: GeoPoint): Set<HazardKind> {
        val plane = LocalTangentPlane(point)
        return hazards.filter { it.covers(plane) }.mapTo(mutableSetOf()) { it.kind }
    }

    /**
     * Inside an enterable building, at most [INDOOR_DEPTH_METRES] from its
     * outline, clear of every other hazard, and the point just outside the
     * nearest wall is open ground. No geodata means no building, so never true.
     */
    override fun isSafeIndoors(point: GeoPoint): Boolean {
        val plane = LocalTangentPlane(point)
        val (rooms, others) = hazards.partition { it is Hazard.Area && it.enterable }
        if (others.any { it.covers(plane) }) return false
        return rooms.any { room -> (room as Hazard.Area).exit(plane)?.let(::isOpenGround) == true }
    }

    /** The point [EXIT_STEP_METRES] beyond the nearest wall, if the origin is inside this area and near enough to it. */
    private fun Hazard.Area.exit(plane: LocalTangentPlane): GeoPoint? {
        val segments = segments(plane)
        if (crossings(segments) % 2 == 0) return null
        val wall = segments.map(::nearestToOrigin).minByOrNull { hypot(it.east, it.north) } ?: return null
        val depth = hypot(wall.east, wall.north)
        if (depth == 0.0 || depth > INDOOR_DEPTH_METRES) return null
        val scale = (depth + EXIT_STEP_METRES) / depth
        return plane.toGeo(LocalMetres(wall.east * scale, wall.north * scale))
    }

    /** Outside every building and clear of every road, railway, water and private land. */
    private fun isOpenGround(point: GeoPoint): Boolean {
        val plane = LocalTangentPlane(point)
        return hazards.none {
            if (it is Hazard.Area && it.kind == HazardKind.BUILDING) crossings(it.segments(plane)) % 2 == 1 else it.covers(plane)
        }
    }

    private fun Hazard.Area.segments(plane: LocalTangentPlane) = boundary.flatMap { segments(plane, it) }

    private fun Hazard.covers(plane: LocalTangentPlane): Boolean = when (this) {
        is Hazard.Corridor -> segments(plane, path).any { distanceToOrigin(it) <= bufferMetres }
        is Hazard.Area -> {
            val segments = segments(plane)
            crossings(segments) % 2 == 1 || segments.any { distanceToOrigin(it) <= bufferMetres }
        }
        is Hazard.Coastline -> {
            val nearest = segments(plane, path).minByOrNull { distanceToOrigin(it) }
            nearest != null && (distanceToOrigin(nearest) <= bufferMetres || isRightOf(nearest))
        }
    }

    private fun segments(plane: LocalTangentPlane, path: List<GeoPoint>): List<Pair<LocalMetres, LocalMetres>> =
        path.map(plane::toLocal).zipWithNext()

    private fun distanceToOrigin(segment: Pair<LocalMetres, LocalMetres>): Double =
        nearestToOrigin(segment).let { hypot(it.east, it.north) }

    private fun nearestToOrigin(segment: Pair<LocalMetres, LocalMetres>): LocalMetres {
        val (a, b) = segment
        val dx = b.east - a.east
        val dy = b.north - a.north
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0.0) 0.0 else ((-a.east * dx - a.north * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return LocalMetres(a.east + t * dx, a.north + t * dy)
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

    companion object {
        /** How far inside a building's outline an Encounter may sit (issue #61). */
        const val INDOOR_DEPTH_METRES: Double = 3.0

        /** How far past the wall the "just outside" point is checked. */
        const val EXIT_STEP_METRES: Double = 0.5
    }
}
