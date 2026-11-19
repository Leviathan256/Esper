package com.esper.engine.encounter

import com.esper.engine.content.MonsterDefinition
import com.esper.engine.dice.SeededRandom
import com.esper.engine.geometry.GeoPoint
import com.esper.engine.geometry.LocalMetres
import com.esper.engine.geometry.LocalTangentPlane
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Issue #61: a player inside a large building still gets an Encounter. */
class IndoorSeedingTest {

    // A fictional local plane at 0,0, never a real place.
    private val origin = GeoPoint(0.0, 0.0)
    private val plane = LocalTangentPlane(origin)

    private fun at(east: Double, north: Double) = plane.toGeo(LocalMetres(east, north))

    private fun json(point: GeoPoint) = """{"lat":${point.lat},"lon":${point.lon}}"""

    private fun way(tags: String, vararg points: GeoPoint) =
        """{"type":"way","tags":{$tags},"geometry":[${points.joinToString(",") { json(it) }}]}"""

    private fun checker(vararg elements: String) =
        GeodataSafetyChecker(OverpassHazards.parse("""{"elements":[${elements.joinToString(",")}]}"""))

    /** A hall 30 m wide east–west and 200 m long, centred on the leash centre: every outdoor ring lands inside it. */
    private fun hall(tags: String = "\"building\":\"yes\"") =
        way(tags, at(-15.0, -100.0), at(15.0, -100.0), at(15.0, 100.0), at(-15.0, 100.0), at(-15.0, -100.0))

    /** A residential road running north–south through [east]: unsafe within 6.5 m (3.5 m half width + 3 m). */
    private fun road(east: Double) = way("\"highway\":\"residential\"", at(east, -100.0), at(east, 100.0))

    // 6.1 m from the point 0.5 m outside the east wall, 8.6 m from an anchor 2 m inside it.
    private val roadFacingEastWall = road(21.6)
    private val roadFacingWestWall = road(-21.6)

    private val pool = listOf(
        MonsterDefinition(
            schemaVersion = 1, id = "slime", displayName = "slime", maxHp = 7, armorClass = 12, attackBonus = 3,
            damage = "1d6", speed = 6, moveRangeCells = 3, xpReward = 10, jobPointsReward = 1,
        ),
    )

    private fun seed(safety: SafeLocationChecker, seed: Long, maxAttempts: Int = 16) =
        EncounterSeeder(pool, safety, maxAttempts).seed(origin, SeededRandom(seed), leashRadiusMetres = 12.0)

    @Test
    fun `a spot up to 3 m inside a public building is safe indoors when the ground outside it is clear`() {
        val safety = checker(hall())
        assertFalse(safety.isSafe(at(13.0, 0.0)))
        assertTrue(safety.isSafeIndoors(at(13.0, 0.0)))
        assertTrue(safety.isSafeIndoors(at(-12.5, 0.0)))
        assertFalse(safety.isSafeIndoors(at(11.5, 0.0)), "3.5 m inside is too deep")
        assertFalse(safety.isSafeIndoors(at(0.0, 0.0)), "the middle of the hall is too deep")
        assertFalse(safety.isSafeIndoors(at(15.5, 0.0)), "outside the building is not indoors")
    }

    @Test
    fun `a wall facing a road within clearance is refused even though the anchor itself is clear of the road`() {
        assertTrue(checker(roadFacingEastWall).isSafe(at(13.0, 0.0)), "the anchor alone is clear of the road")
        val safety = checker(hall(), roadFacingEastWall)
        assertFalse(safety.isSafeIndoors(at(13.0, 0.0)))
        assertTrue(safety.isSafeIndoors(at(-13.0, 0.0)), "the west wall still opens onto clear ground")
    }

    @Test
    fun `a wall whose outside is another building is refused`() {
        val annex = way("\"building\":\"yes\"", at(15.0, -100.0), at(30.0, -100.0), at(30.0, 100.0), at(15.0, 100.0), at(15.0, -100.0))
        assertFalse(checker(hall(), annex).isSafeIndoors(at(13.0, 0.0)))
    }

    @Test
    fun `homes and access=private buildings never get an indoor anchor`() {
        val homes = listOf(
            "house", "detached", "semidetached_house", "terrace", "apartments", "residential", "bungalow", "cabin",
            "dormitory", "farm", "houseboat", "static_caravan", "hut",
        ).map { "\"building\":\"$it\"" } + "\"building\":\"yes\",\"access\":\"private\""
        for (tags in homes) {
            val safety = checker(hall(tags))
            assertFalse(safety.isSafeIndoors(at(13.0, 0.0)), tags)
            repeat(10) { assertNull(seed(safety, it.toLong()).encounter, tags) }
        }
    }

    @Test
    fun `a leash centre deep inside a large public building gets an encounter just inside a wall`() {
        val safety = checker(hall())
        repeat(20) { s ->
            val encounter = seed(safety, s.toLong()).encounter
            assertNotNull(encounter, "seed $s")
            val east = plane.toLocal(encounter!!.anchor).east
            assertTrue(kotlin.math.abs(east) in 12.0..15.0, "seed $s: $east m east is not within 3 m of a wall")
        }
    }

    @Test
    fun `only the wall that opens onto clear ground is used`() {
        val safety = checker(hall(), roadFacingEastWall)
        repeat(20) { s ->
            val encounter = seed(safety, s.toLong(), maxAttempts = 64).encounter
            assertNotNull(encounter, "seed $s")
            val east = plane.toLocal(encounter!!.anchor).east
            assertTrue(east in -15.0..-12.0, "seed $s: $east m east is not just inside the west wall")
        }
    }

    @Test
    fun `with roads facing every reachable wall nothing is seeded and buildings are named as the blocker`() {
        val outcome = seed(checker(hall(), roadFacingEastWall, roadFacingWestWall), 1L)
        assertNull(outcome.encounter)
        assertEquals(setOf(HazardKind.BUILDING), outcome.blockers)
    }

    @Test
    fun `a player boxed in by roads is told roads blocked seeding`() {
        val outcome = seed(checker(road(-10.0), road(0.0), road(10.0)), 1L)
        assertNull(outcome.encounter)
        assertEquals(setOf(HazardKind.ROAD), outcome.blockers)
    }

    @Test
    fun `no geodata never opens an indoor spot`() {
        assertFalse(GeodataSafetyChecker(emptyList()).isSafeIndoors(at(13.0, 0.0)))
    }

    /** Records every check as (indoors?, distance from the leash centre). */
    private inner class Spy(
        private val outdoor: (Double) -> Boolean,
        private val indoor: (Double) -> Boolean,
    ) : SafeLocationChecker {
        val calls = mutableListOf<Pair<Boolean, Double>>()

        override fun isSafe(point: GeoPoint): Boolean =
            plane.distanceMetres(origin, point).let { calls += false to it; outdoor(it) }

        override fun isSafeIndoors(point: GeoPoint): Boolean =
            plane.distanceMetres(origin, point).let { calls += true to it; indoor(it) }
    }

    @Test
    fun `outdoor spots 6 to 10 m away win over everything`() {
        repeat(50) { s ->
            val spy = Spy(outdoor = { it <= 10.0 }, indoor = { true })
            val anchor = seed(spy, s.toLong()).encounter!!.anchor
            assertTrue(plane.distanceMetres(origin, anchor) in 5.99..10.01, "seed $s")
            assertTrue(spy.calls.none { it.first }, "seed $s: an indoor spot was tried")
        }
    }

    @Test
    fun `the widened outdoor ring out to the leash radius is tried before any indoor spot`() {
        repeat(50) { s ->
            val spy = Spy(outdoor = { it > 10.0 }, indoor = { true })
            val anchor = seed(spy, s.toLong()).encounter!!.anchor
            assertTrue(plane.distanceMetres(origin, anchor) in 10.0..12.01, "seed $s")
            assertTrue(spy.calls.none { it.first }, "seed $s: an indoor spot was tried")
        }
        val spy = Spy(outdoor = { false }, indoor = { true })
        assertNotNull(seed(spy, 7L).encounter)
        val firstIndoor = spy.calls.indexOfFirst { it.first }
        assertTrue(spy.calls.take(firstIndoor).any { it.second > 10.0 }, "the ring never widened")
        assertTrue(spy.calls.drop(firstIndoor).all { it.first }, "an outdoor spot came after an indoor one")
    }

    @Test
    fun `seeding is bounded, never widens past the leash and fails closed`() {
        val spy = Spy(outdoor = { false }, indoor = { false })
        assertNull(seed(spy, 3L, maxAttempts = 5).encounter)
        val outdoor = spy.calls.filterNot { it.first }
        assertEquals(10, outdoor.size, "5 tries in each of the 6–10 m and 10–12 m rings")
        assertTrue(outdoor.all { it.second <= 12.01 })
        assertTrue(spy.calls.count { it.first } <= 5 * 25, "indoor search must be bounded")
    }
}
