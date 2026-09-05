package com.esper.engine.encounter

import com.esper.engine.content.MonsterDefinition
import com.esper.engine.dice.RandomSource
import com.esper.engine.geometry.GeoPoint

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
    ): Encounter? = TODO("implemented by engine-encounter")
}
