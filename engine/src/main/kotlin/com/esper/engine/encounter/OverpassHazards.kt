package com.esper.engine.encounter

import com.esper.engine.geometry.GeoPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns OpenStreetMap data from an Overpass API query into [Hazard]s.
 *
 * Pure: the engine builds the query and reads the response; the Android layer
 * only moves bytes. Which tags count as dangerous or private, and how wide each
 * is, is decided here in one place.
 */
object OverpassHazards {

    /** Margin kept beyond a road's or railway's edge. */
    const val TRAFFIC_CLEARANCE_METRES: Double = 3.0

    /** Margin kept from water and coastline. */
    const val WATER_CLEARANCE_METRES: Double = 2.0

    /** Margin kept outside buildings and private land. */
    const val PROPERTY_CLEARANCE_METRES: Double = 1.0

    // Tag filters for anything [classify] can turn into a hazard. Kept as broad
    // as classify's inputs; classify discards what turns out to be harmless.
    private val FILTERS = listOf(
        "[highway]",
        "[railway]",
        "[waterway]",
        "[natural~\"^(water|wetland|coastline)$\"]",
        "[landuse~\"^(reservoir|basin|military|railway)$\"]",
        "[building]",
        "[access~\"^(private|no)$\"]",
    )

    /**
     * Everything within [radiusMetres] of [center], plus any area the centre is
     * inside — a point deep in a large lake or private estate has no boundary
     * within the radius, so `around` alone would miss it.
     */
    fun query(center: GeoPoint, radiusMetres: Double): String {
        val around = "(around:${radiusMetres.toInt()},${center.lat},${center.lon})"
        val clauses = FILTERS.flatMap { filter ->
            listOf("way$around$filter;", "rel$around$filter;", "way(pivot.inside)$filter;", "rel(pivot.inside)$filter;")
        }
        return buildString {
            append("[out:json][timeout:15];\n")
            append("is_in(${center.lat},${center.lon})->.inside;\n")
            append("(\n")
            clauses.forEach { append("  ").append(it).append('\n') }
            append(");\nout geom;\n")
        }
    }

    /**
     * Parses an `out geom` JSON response. Throws on a response Overpass flagged
     * as failed (it still returns 200 with partial data on a timeout), because a
     * partial hazard list would wrongly mark dangerous ground safe.
     */
    fun parse(json: String): List<Hazard> {
        val root = Json.parseToJsonElement(json).jsonObject
        val remark = root["remark"]?.jsonPrimitive?.contentOrNull
        check(remark == null || !remark.contains("error", ignoreCase = true)) { "Overpass error: $remark" }
        val elements = root["elements"]?.jsonArray ?: error("Overpass response has no elements")
        return elements.mapNotNull { element ->
            val obj = element.jsonObject
            val tags = obj["tags"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: return@mapNotNull null
            when (obj["type"]?.jsonPrimitive?.content) {
                "way" -> {
                    val path = geometry(obj["geometry"])
                    if (path.size < 2) null else classify(tags, listOf(path), isArea = path.first() == path.last())
                }
                "relation" -> {
                    val paths = obj["members"]?.jsonArray.orEmpty()
                        .map { geometry(it.jsonObject["geometry"]) }
                        .filter { it.size >= 2 }
                    if (paths.isEmpty()) null else classify(tags, paths, isArea = true)
                }
                else -> null
            }
        }
    }

    private fun geometry(element: kotlinx.serialization.json.JsonElement?): List<GeoPoint> =
        (element as? JsonArray).orEmpty().mapNotNull { node ->
            val point = node as? JsonObject ?: return@mapNotNull null
            val lat = point["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val lon = point["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            GeoPoint(lat, lon)
        }

    private fun classify(tags: Map<String, String>, paths: List<List<GeoPoint>>, isArea: Boolean): Hazard? {
        // Underground features don't occupy the surface the player stands on.
        if (tags["tunnel"] in setOf("yes", "culvert") || (tags["layer"]?.toIntOrNull() ?: 0) < 0) return null

        val highway = tags["highway"]
        val railway = tags["railway"]
        val waterway = tags["waterway"]
        val explicitHalfWidth = tags["width"]?.removeSuffix(" m")?.toDoubleOrNull()?.div(2.0)

        fun corridor(halfWidth: Double, clearance: Double) =
            Hazard.Corridor(paths.first(), (explicitHalfWidth ?: halfWidth) + clearance)

        fun area(clearance: Double) = Hazard.Area(paths, clearance)

        // `access=no` + `foot=yes` is the usual tagging for a public footpath.
        val privateAccess = tags["access"] in setOf("private", "no") &&
            tags["foot"] !in setOf("yes", "designated", "permissive")
        return when {
            highway != null && highway !in FOOT_HIGHWAYS && highway !in NOT_A_ROAD ->
                corridor(ROAD_HALF_WIDTHS[highway] ?: DEFAULT_ROAD_HALF_WIDTH, TRAFFIC_CLEARANCE_METRES)
            railway in ACTIVE_RAILWAYS -> corridor(RAIL_HALF_WIDTH, TRAFFIC_CLEARANCE_METRES)
            tags["natural"] == "coastline" -> Hazard.Coastline(paths.first(), WATER_CLEARANCE_METRES)
            waterway == "riverbank" || tags["natural"] in setOf("water", "wetland") ||
                tags["landuse"] in setOf("reservoir", "basin") -> area(WATER_CLEARANCE_METRES)
            waterway != null && waterway in WATERWAY_HALF_WIDTHS ->
                corridor(WATERWAY_HALF_WIDTHS.getValue(waterway), WATER_CLEARANCE_METRES)
            tags["building"].let { it != null && it != "no" } -> area(PROPERTY_CLEARANCE_METRES)
            tags["landuse"] in setOf("military", "railway") -> area(PROPERTY_CLEARANCE_METRES)
            // A private footpath is still somewhere the player may not go.
            privateAccess && highway != null -> corridor(1.0, PROPERTY_CLEARANCE_METRES)
            privateAccess && isArea -> area(PROPERTY_CLEARANCE_METRES)
            else -> null
        }
    }

    private val FOOT_HIGHWAYS = setOf(
        "footway", "path", "pedestrian", "cycleway", "steps", "bridleway", "corridor", "elevator", "platform",
    )

    /** Tagged as highway but not a carriageway anyone drives on. */
    private val NOT_A_ROAD = setOf(
        "proposed", "construction", "abandoned", "disused", "razed", "street_lamp", "bus_stop", "crossing",
        "traffic_signals", "stop", "give_way", "turning_circle", "milestone",
    )

    private const val DEFAULT_ROAD_HALF_WIDTH = 3.0

    /** Typical half carriageway width in metres, used when the way has no `width` tag. */
    private val ROAD_HALF_WIDTHS = mapOf(
        "motorway" to 8.0, "trunk" to 7.0, "primary" to 6.0, "secondary" to 5.0, "tertiary" to 4.0,
        "motorway_link" to 4.0, "trunk_link" to 4.0, "primary_link" to 4.0, "secondary_link" to 3.5,
        "tertiary_link" to 3.5, "residential" to 3.5, "unclassified" to 3.5, "living_street" to 3.0,
        "service" to 2.5, "track" to 2.0,
    )

    private val ACTIVE_RAILWAYS = setOf(
        "rail", "light_rail", "tram", "subway", "narrow_gauge", "monorail", "funicular", "preserved",
    )

    private const val RAIL_HALF_WIDTH = 2.5

    private val WATERWAY_HALF_WIDTHS = mapOf(
        "river" to 10.0, "canal" to 6.0, "stream" to 1.5, "drain" to 1.0, "ditch" to 1.0,
    )
}
