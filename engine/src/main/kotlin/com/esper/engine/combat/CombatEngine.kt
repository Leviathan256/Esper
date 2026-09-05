package com.esper.engine.combat

import com.esper.engine.dice.CombatMath
import com.esper.engine.dice.RandomSource
import com.esper.engine.geometry.HexBoard
import com.esper.engine.geometry.HexCoord
import com.esper.engine.geometry.HexGrid

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
    /** Insertion-order preserving so [units] is stable across calls. */
    private val unitsById = LinkedHashMap<String, CombatUnit>().apply {
        for (u in initialUnits) put(u.id, u)
    }

    private var currentActorId: String? = null
    private var battleResult: BattleResult? = null

    /** Immutable snapshot. Re-read it after every call. */
    val units: List<CombatUnit> get() = unitsById.values.toList()

    fun unit(id: String): CombatUnit? = unitsById[id]

    /** Null while the battle is still in progress. */
    fun result(): BattleResult? = battleResult

    fun isOver(): Boolean = battleResult != null

    /**
     * Ticks every living unit's gauge by its `speed` until one reaches [GAUGE_MAX],
     * and returns it. Ties break on lowest unit id. Returns null when the battle
     * is over.
     */
    fun advanceToNextActor(): CombatUnit? {
        if (isOver()) return null
        currentActorId = null

        // A tie in a previous call can leave a unit that did not act already at or
        // past GAUGE_MAX (its gauge was never spent). Check before ticking so that
        // leftover readiness is not skipped past.
        readyActor()?.let {
            currentActorId = it.id
            return it
        }

        var ticks = 0
        while (ticks < MAX_TICKS_PER_TURN) {
            ticks++
            for (id in unitsById.keys.toList()) {
                val u = unitsById.getValue(id)
                if (u.alive) {
                    unitsById[id] = u.copy(atbGauge = u.atbGauge + u.stats.speed)
                }
            }
            readyActor()?.let {
                currentActorId = it.id
                return it
            }
        }
        return null
    }

    /** The lowest-id living unit whose gauge has reached [GAUGE_MAX], if any. */
    private fun readyActor(): CombatUnit? =
        unitsById.values.filter { it.alive && it.atbGauge >= GAUGE_MAX }.minByOrNull { it.id }

    fun currentActor(): CombatUnit? = currentActorId?.let { unitsById[it] }

    /**
     * Applies [action] for [currentActor], spends [GAUGE_MAX] of gauge, and returns
     * the events. An illegal action yields [CombatEvent.ActionRejected] and spends
     * nothing.
     */
    fun submitAction(action: CombatAction): List<CombatEvent> {
        if (isOver()) {
            return listOf(CombatEvent.ActionRejected("none", "battle already over"))
        }
        val actor = currentActor()
            ?: return listOf(CombatEvent.ActionRejected("none", "no current actor"))
        return when (action) {
            is CombatAction.Wait -> {
                commit(actor)
                emptyList()
            }
            is CombatAction.Move -> handleMove(actor, action.to)
            is CombatAction.Attack -> handleAttack(actor, action.targetId)
        }
    }

    fun legalMoves(unitId: String): Set<HexCoord> {
        val actor = unitsById[unitId] ?: return emptySet()
        val blocked = unitsById.values
            .filter { it.alive && it.id != unitId }
            .map { it.position }
            .toSet()
        return HexGrid.reachableCells(actor.position, actor.stats.moveRangeCells, board.cells, blocked)
    }

    fun attackableTargets(unitId: String): List<CombatUnit> {
        val actor = unitsById[unitId] ?: return emptyList()
        return unitsById.values.filter { candidate ->
            candidate.id != unitId &&
                candidate.alive &&
                candidate.playerControlled != actor.playerControlled &&
                actor.position.distanceTo(candidate.position) <= actor.stats.attackRangeCells
        }
    }

    private fun handleMove(actor: CombatUnit, to: HexCoord): List<CombatEvent> {
        val legal = legalMoves(actor.id)
        if (to !in legal) {
            return listOf(CombatEvent.ActionRejected(actor.id, "illegal move to $to"))
        }
        val from = actor.position
        commit(actor.copy(position = to))
        return listOf(CombatEvent.Moved(actor.id, from, to))
    }

    private fun handleAttack(actor: CombatUnit, targetId: String): List<CombatEvent> {
        val target = unitsById[targetId]
        if (target == null || !target.alive || target.playerControlled == actor.playerControlled) {
            return listOf(CombatEvent.ActionRejected(actor.id, "no valid target $targetId"))
        }
        if (actor.position.distanceTo(target.position) > actor.stats.attackRangeCells) {
            return listOf(CombatEvent.ActionRejected(actor.id, "target $targetId out of range"))
        }

        val roll = CombatMath.rollAttack(actor.stats.attackBonus, target.stats.armorClass, rng)
        val damage = if (roll.hit) CombatMath.rollDamage(actor.stats.damage, roll.critical, rng) else 0
        val newHp = maxOf(0, target.currentHp - damage)
        unitsById[target.id] = target.copy(currentHp = newHp)
        commit(actor)

        val events = mutableListOf<CombatEvent>(
            CombatEvent.Attacked(
                attackerId = actor.id,
                targetId = target.id,
                roll = roll,
                damage = damage,
                targetHpAfter = newHp,
            ),
        )
        if (newHp <= 0) {
            events.add(CombatEvent.Defeated(target.id))
        }
        checkBattleEnd()?.let { events.add(CombatEvent.BattleEnded(it)) }
        return events
    }

    /** Spends [GAUGE_MAX] of [unit]'s gauge (overflow carries) and clears the current actor. */
    private fun commit(unit: CombatUnit) {
        unitsById[unit.id] = unit.copy(atbGauge = unit.atbGauge - GAUGE_MAX)
        currentActorId = null
    }

    /** Sets and returns the outcome the first time it becomes true; null every time after. */
    private fun checkBattleEnd(): BattleResult? {
        if (battleResult != null) return null
        val livingPlayers = unitsById.values.count { it.playerControlled && it.alive }
        val livingEnemies = unitsById.values.count { !it.playerControlled && it.alive }
        val outcome = when {
            livingPlayers == 0 -> BattleResult.DEFEAT
            livingEnemies == 0 -> BattleResult.VICTORY
            else -> null
        }
        if (outcome != null) battleResult = outcome
        return outcome
    }

    companion object {
        const val GAUGE_MAX: Double = 100.0

        /** Guards against an all-zero-speed deadlock. */
        const val MAX_TICKS_PER_TURN: Int = 10_000
    }
}
