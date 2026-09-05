package com.esper.engine.encounter

import com.esper.engine.content.MonsterDefinition
import com.esper.engine.dice.SeededRandom
import com.esper.engine.geometry.GeoPoint
import com.esper.engine.geometry.LocalTangentPlane
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EncounterSeederTest {

    private fun monster(id: String) = MonsterDefinition(
        schemaVersion = 1,
        id = id,
        displayName = id,
        maxHp = 7,
        armorClass = 12,
        attackBonus = 3,
        damage = "1d6",
        speed = 6,
        moveRangeCells = 3,
        xpReward = 10,
        jobPointsReward = 1,
    )

    private val pool = listOf(monster("goblin"), monster("slime"), monster("wolf"))
    private val center = GeoPoint(lat = 47.6062, lon = -122.3321)

    @Test
    fun `anchor distance always falls within the requested radius`() {
        val seeder = EncounterSeeder(pool)
        val plane = LocalTangentPlane(center)
        // One shared rng advanced across all 1000 draws: reseeding java.util.Random with
        // small sequential longs correlates its very first output, so statistical
        // coverage checks must walk one seeded stream rather than reseed per sample.
        val rng = SeededRandom(1234L)
        repeat(1000) { i ->
            val encounter = seeder.seedNear(center, rng, minRadiusMetres = 6.0, maxRadiusMetres = 10.0)
            assertNotNull(encounter, "draw $i unexpectedly produced no encounter")
            val distance = plane.distanceMetres(center, encounter!!.anchor)
            assertTrue(distance in 5.99..10.01, "draw $i: distance $distance out of [6,10]")
        }
    }

    @Test
    fun `bearings cover all four quadrants across many seeds`() {
        val seeder = EncounterSeeder(pool)
        val plane = LocalTangentPlane(center)
        val rng = SeededRandom(5678L)
        var ne = false
        var se = false
        var sw = false
        var nw = false
        repeat(1000) {
            val encounter = seeder.seedNear(center, rng)!!
            val local = plane.toLocal(encounter.anchor)
            when {
                local.east >= 0 && local.north >= 0 -> ne = true
                local.east >= 0 && local.north < 0 -> se = true
                local.east < 0 && local.north < 0 -> sw = true
                else -> nw = true
            }
        }
        assertTrue(ne && se && sw && nw, "expected all four quadrants to appear: NE=$ne SE=$se SW=$sw NW=$nw")
    }

    @Test
    fun `monster ids only ever come from the supplied pool and count is 1 or 2`() {
        val seeder = EncounterSeeder(pool)
        val poolIds = pool.map { it.id }.toSet()
        val rng = SeededRandom(4242L)
        repeat(500) { i ->
            val encounter = seeder.seedNear(center, rng)!!
            assertTrue(encounter.monsterIds.size in 1..2, "draw $i: unexpected count ${encounter.monsterIds.size}")
            assertTrue(poolIds.containsAll(encounter.monsterIds), "draw $i: ids ${encounter.monsterIds} not subset of $poolIds")
        }
    }

    @Test
    fun `the same seed always produces an identical encounter`() {
        val seeder = EncounterSeeder(pool)
        val first = seeder.seedNear(center, SeededRandom(99L))
        val second = seeder.seedNear(center, SeededRandom(99L))
        assertEquals(first, second)
    }

    @Test
    fun `an empty pool returns null`() {
        val seeder = EncounterSeeder(emptyList())
        assertNull(seeder.seedNear(center, SeededRandom(1L)))
    }

    @Test
    fun `a safety checker that always rejects returns null after at most maxAttempts and never loops forever`() {
        val seeder = EncounterSeeder(pool, safety = SafeLocationChecker { false }, maxAttempts = 5)
        assertNull(seeder.seedNear(center, SeededRandom(1L)))
    }

    @Test
    fun `a safety checker rejecting only some points still eventually succeeds within maxAttempts`() {
        var calls = 0
        val safety = SafeLocationChecker {
            calls += 1
            calls >= 3
        }
        val seeder = EncounterSeeder(pool, safety = safety, maxAttempts = 16)
        val encounter = seeder.seedNear(center, SeededRandom(1L))
        assertNotNull(encounter)
    }
}
