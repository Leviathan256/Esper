package com.esper.engine.encounter

import com.esper.engine.content.MonsterDefinition
import com.esper.engine.dice.RandomSource
import com.esper.engine.geometry.GeoPoint
import com.esper.engine.geometry.LocalMetres
import com.esper.engine.geometry.LocalTangentPlane
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Places one encounter near the player's real position, on-device. */
class EncounterSeeder(
    private val monsterPool: List<MonsterDefinition>,
    private val safety: SafeLocationChecker,
    private val maxAttempts: Int = 16,
) {
    /** The seeded encounter, or null and what made every outdoor spot unsafe. */
    data class Outcome(val encounter: Encounter?, val blockers: Set<HazardKind>)

    /** [seed] without widening or the reason; null on failure. */
    fun seedNear(
        center: GeoPoint,
        rng: RandomSource,
        minRadiusMetres: Double = 6.0,
        maxRadiusMetres: Double = 10.0,
    ): Encounter? = seed(center, rng, minRadiusMetres, maxRadiusMetres).encounter

    /**
     * Tries, in order (issue #61): [maxAttempts] random spots [minRadiusMetres] to
     * [maxRadiusMetres] away; the same again in outdoor rings [RING_STEP_METRES]
     * wider each, out to [leashRadiusMetres]; then, along [maxAttempts] random
     * bearings out to [indoorRadiusMetres], the first spot that is safe just inside
     * a building. Outdoor spots always win. Bounded: never loops unbounded.
     */
    fun seed(
        center: GeoPoint,
        rng: RandomSource,
        minRadiusMetres: Double = 6.0,
        maxRadiusMetres: Double = 10.0,
        leashRadiusMetres: Double = maxRadiusMetres,
        indoorRadiusMetres: Double = INDOOR_SEARCH_METRES,
    ): Outcome {
        if (monsterPool.isEmpty()) return Outcome(null, emptySet())
        val plane = LocalTangentPlane(center)
        fun at(bearing: Double, distance: Double) =
            plane.toGeo(LocalMetres(east = distance * sin(bearing), north = distance * cos(bearing)))

        val blockers = mutableSetOf<HazardKind>()
        var inner = minRadiusMetres
        var outer = maxRadiusMetres
        while (true) {
            repeat(maxAttempts) {
                val bearing = rng.nextDouble() * 2.0 * PI
                val anchor = at(bearing, inner + rng.nextDouble() * (outer - inner))
                if (safety.isSafe(anchor)) return Outcome(encounterAt(anchor, rng), emptySet())
                blockers += safety.blockers(anchor)
            }
            if (outer >= leashRadiusMetres) break
            inner = outer
            outer = min(outer + RING_STEP_METRES, leashRadiusMetres)
        }
        repeat(maxAttempts) {
            val bearing = rng.nextDouble() * 2.0 * PI
            var distance = minRadiusMetres
            while (distance <= indoorRadiusMetres) {
                val anchor = at(bearing, distance)
                if (safety.isSafeIndoors(anchor)) return Outcome(encounterAt(anchor, rng), emptySet())
                distance += INDOOR_STEP_METRES
            }
        }
        return Outcome(null, blockers)
    }

    private fun encounterAt(anchor: GeoPoint, rng: RandomSource): Encounter {
        val monsterCount = 1 + rng.nextInt(2)
        val monsterIds = List(monsterCount) { monsterPool[rng.nextInt(monsterPool.size)].id }
        val id = "enc-" + rng.nextInt(Int.MAX_VALUE)
        return Encounter(id = id, anchor = anchor, monsterIds = monsterIds)
    }

    companion object {
        /** How much wider each fallback outdoor ring is. */
        const val RING_STEP_METRES: Double = 2.0

        /**
         * How far out indoor spots are looked for: beyond the leash, since the map
         * card enters from anywhere, but close enough that the 40 m hazard query
         * still covers the widest default hazard (a river, 12 m) past the exit point.
         */
        const val INDOOR_SEARCH_METRES: Double = 24.0

        /** Smaller than the 3 m indoor band, so a bearing that crosses a wall cannot step over it. */
        const val INDOOR_STEP_METRES: Double = 1.0
    }
}
