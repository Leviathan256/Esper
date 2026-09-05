package com.esper.engine.encounter

import com.esper.engine.content.MonsterDefinition
import com.esper.engine.dice.RandomSource
import com.esper.engine.geometry.GeoPoint
import com.esper.engine.geometry.LocalMetres
import com.esper.engine.geometry.LocalTangentPlane
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Places one encounter near the player's real position, on-device. */
class EncounterSeeder(
    private val monsterPool: List<MonsterDefinition>,
    private val safety: SafeLocationChecker = AlwaysSafe,
    private val maxAttempts: Int = 16,
) {
    /**
     * Random bearing + distance in `[minRadiusMetres, maxRadiusMetres]`.
     *
     * Returns null on an empty pool or when every attempt is rejected by the safety
     * checker — bounded by [maxAttempts], so it never loops unbounded.
     */
    fun seedNear(
        center: GeoPoint,
        rng: RandomSource,
        minRadiusMetres: Double = 6.0,
        maxRadiusMetres: Double = 10.0,
    ): Encounter? {
        if (monsterPool.isEmpty()) return null
        val plane = LocalTangentPlane(center)
        repeat(maxAttempts) {
            val bearing = rng.nextDouble() * 2.0 * PI
            val distance = minRadiusMetres + rng.nextDouble() * (maxRadiusMetres - minRadiusMetres)
            val local = LocalMetres(east = distance * sin(bearing), north = distance * cos(bearing))
            val anchor = plane.toGeo(local)
            if (safety.isSafe(anchor)) {
                val monsterCount = 1 + rng.nextInt(2)
                val monsterIds = List(monsterCount) { monsterPool[rng.nextInt(monsterPool.size)].id }
                val id = "enc-" + rng.nextInt(Int.MAX_VALUE)
                return Encounter(id = id, anchor = anchor, monsterIds = monsterIds)
            }
        }
        return null
    }
}
