package com.esper.engine.encounter

import com.esper.engine.dice.SeededRandom
import com.esper.engine.content.MonsterDefinition
import com.esper.engine.geometry.GeoPoint
import com.esper.engine.geometry.LocalMetres
import com.esper.engine.geometry.LocalTangentPlane
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SafeLocationCheckerTest {

    private val origin = GeoPoint(lat = 51.5007, lon = -0.1246)
    private val plane = LocalTangentPlane(origin)

    /** A point [east]/[north] metres from [origin]. */
    private fun at(east: Double, north: Double) = plane.toGeo(LocalMetres(east, north))

    private fun json(point: GeoPoint) = """{"lat":${point.lat},"lon":${point.lon}}"""

    private fun way(tags: String, vararg points: GeoPoint) =
        """{"type":"way","tags":{$tags},"geometry":[${points.joinToString(",") { json(it) }}]}"""

    private fun response(vararg elements: String) = """{"elements":[${elements.joinToString(",")}]}"""

    private fun checker(vararg elements: String) = GeodataSafetyChecker(OverpassHazards.parse(response(*elements)))

    // A residential road running north–south through x = 0.
    private val road = way("\"highway\":\"residential\"", at(0.0, -100.0), at(0.0, 100.0))

    @Test
    fun `points on or beside a road are unsafe and points well clear of it are safe`() {
        val safety = checker(road)
        assertFalse(safety.isSafe(at(0.0, 0.0)))
        // 3.5 m half carriageway + 3 m clearance.
        assertFalse(safety.isSafe(at(6.0, 0.0)))
        assertTrue(safety.isSafe(at(7.0, 0.0)))
    }

    @Test
    fun `a width tag overrides the default carriageway width`() {
        val wide = way("\"highway\":\"residential\",\"width\":\"20\"", at(0.0, -100.0), at(0.0, 100.0))
        assertFalse(checker(wide).isSafe(at(12.0, 0.0)))
    }

    @Test
    fun `footpaths, tunnels and public access=no paths are not hazards`() {
        val footway = way("\"highway\":\"footway\"", at(0.0, -100.0), at(0.0, 100.0))
        val tunnel = way("\"highway\":\"primary\",\"tunnel\":\"yes\"", at(0.0, -100.0), at(0.0, 100.0))
        val publicPath = way("\"highway\":\"path\",\"access\":\"no\",\"foot\":\"yes\"", at(0.0, -100.0), at(0.0, 100.0))
        assertEquals(emptyList<Hazard>(), OverpassHazards.parse(response(footway, tunnel, publicPath)))
    }

    @Test
    fun `railways are unsafe`() {
        val rail = way("\"railway\":\"rail\"", at(-100.0, 0.0), at(100.0, 0.0))
        assertFalse(checker(rail).isSafe(at(10.0, 4.0)))
        assertTrue(checker(rail).isSafe(at(10.0, 6.0)))
    }

    @Test
    fun `inside a pond or a building is unsafe, outside it is safe`() {
        val square = arrayOf(at(-20.0, -20.0), at(20.0, -20.0), at(20.0, 20.0), at(-20.0, 20.0), at(-20.0, -20.0))
        for (tags in listOf("\"natural\":\"water\"", "\"building\":\"yes\"", "\"landuse\":\"grass\",\"access\":\"private\"")) {
            val safety = checker(way(tags, *square))
            assertFalse(safety.isSafe(at(0.0, 0.0)), tags)
            assertFalse(safety.isSafe(at(19.5, 0.0)), tags)
            assertTrue(safety.isSafe(at(30.0, 0.0)), tags)
        }
    }

    @Test
    fun `a multipolygon island is safe while the lake around it is not`() {
        val outer = arrayOf(at(-50.0, -50.0), at(50.0, -50.0), at(50.0, 50.0), at(-50.0, 50.0), at(-50.0, -50.0))
        val inner = arrayOf(at(-10.0, -10.0), at(10.0, -10.0), at(10.0, 10.0), at(-10.0, 10.0), at(-10.0, -10.0))
        fun member(role: String, points: Array<GeoPoint>) =
            """{"type":"way","role":"$role","geometry":[${points.joinToString(",") { json(it) }}]}"""
        val lake = """{"type":"relation","tags":{"natural":"water","type":"multipolygon"},""" +
            """"members":[${member("outer", outer)},${member("inner", inner)}]}"""
        val safety = checker(lake)
        assertTrue(safety.isSafe(at(0.0, 0.0)))
        assertFalse(safety.isSafe(at(30.0, 0.0)))
        assertTrue(safety.isSafe(at(80.0, 0.0)))
    }

    @Test
    fun `the sea side of a coastline is unsafe`() {
        // Running north with land on the left (west), sea on the right (east).
        val coast = way("\"natural\":\"coastline\"", at(0.0, -100.0), at(0.0, 100.0))
        val safety = checker(coast)
        assertTrue(safety.isSafe(at(-10.0, 0.0)))
        assertFalse(safety.isSafe(at(-1.0, 0.0)))
        assertFalse(safety.isSafe(at(10.0, 0.0)))
    }

    @Test
    fun `an Overpass error remark is a failure, not an empty safe map`() {
        val partial = """{"remark":"runtime error: Query timed out","elements":[]}"""
        assertThrows(IllegalStateException::class.java) { OverpassHazards.parse(partial) }
    }

    @Test
    fun `the query targets the centre with every hazard filter`() {
        val query = OverpassHazards.query(origin, 40.0)
        assertTrue(query.contains("is_in(${origin.lat},${origin.lon})"))
        assertTrue(query.contains("way(around:40,${origin.lat},${origin.lon})[highway];"))
        assertTrue(query.contains("rel(pivot.inside)[building];"))
        assertTrue(query.trimEnd().endsWith("out geom;"))
    }

    @Test
    fun `the seeder never places an encounter on a road next to the player`() {
        val monster = MonsterDefinition(
            schemaVersion = 1, id = "slime", displayName = "slime", maxHp = 7, armorClass = 12, attackBonus = 3,
            damage = "1d6", speed = 6, moveRangeCells = 3, xpReward = 10, jobPointsReward = 1,
        )
        val safety = checker(road)
        // Player on the pavement 7 m east of the road's centreline.
        val player = at(7.0, 0.0)
        repeat(50) { seed ->
            val encounter = EncounterSeeder(listOf(monster), safety, maxAttempts = 64)
                .seedNear(player, SeededRandom(seed.toLong()))
            assertNotNull(encounter)
            assertTrue(safety.isSafe(encounter!!.anchor))
            assertTrue(plane.toLocal(encounter.anchor).east > 6.5)
        }
    }
}
