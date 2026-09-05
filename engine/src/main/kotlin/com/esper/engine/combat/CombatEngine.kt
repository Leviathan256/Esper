package com.esper.engine.combat

import com.esper.engine.dice.RandomSource
import com.esper.engine.geometry.HexBoard
import com.esper.engine.geometry.HexCoord

/**
 * ATB turn order and action resolution.
 *
 * Discrete ticks, not a timer: [advanceToNextActor] adds each living unit's `speed`
 * to its gauge until one reaches [GAUGE_MAX]. Acting subtracts [GAUGE_MAX] rather
 * than resetting to zero, so overflow carries and fast units stay fair. Gauges do
 * not advance while an action resolves, and every action costs the same flat
 * amount — both are MVP defaults recorded in docs/GAME_DESIGN.md's open questions.
 *
 * This model is why the UI needs no background loop to leak.
 */
class CombatEngine(
    val board: HexBoard,
    initialUnits: List<CombatUnit>,
    private val rng: RandomSource,
) {
    /** Immutable snapshot. Re-read it after every call. */
    val units: List<CombatUnit> get() = TODO("implemented by engine-combat")

    fun unit(id: String): CombatUnit? = TODO("implemented by engine-combat")

    /** Null while the battle is still in progress. */
    fun result(): BattleResult? = TODO("implemented by engine-combat")

    fun isOver(): Boolean = TODO("implemented by engine-combat")

    /**
     * Ticks every living unit's gauge by its `speed` until one reaches [GAUGE_MAX],
     * and returns it. Ties break on lowest unit id. Returns null when the battle
     * is over.
     */
    fun advanceToNextActor(): CombatUnit? = TODO("implemented by engine-combat")

    fun currentActor(): CombatUnit? = TODO("implemented by engine-combat")

    /**
     * Applies [action] for [currentActor], spends [GAUGE_MAX] of gauge, and returns
     * the events. An illegal action yields [CombatEvent.ActionRejected] and spends
     * nothing.
     */
    fun submitAction(action: CombatAction): List<CombatEvent> = TODO("implemented by engine-combat")

    fun legalMoves(unitId: String): Set<HexCoord> = TODO("implemented by engine-combat")

    fun attackableTargets(unitId: String): List<CombatUnit> = TODO("implemented by engine-combat")

    companion object {
        const val GAUGE_MAX: Double = 100.0

        /** Guards against an all-zero-speed deadlock. */
        const val MAX_TICKS_PER_TURN: Int = 10_000
    }
}
