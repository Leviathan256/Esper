package com.esper.engine.combat

import com.esper.engine.geometry.HexCoord

/**
 * The one scripted enemy.
 *
 * If a living enemy is in range, attack the lowest-HP one (tie: lowest id).
 * Otherwise move to the reachable cell minimising hex distance to the nearest
 * living enemy (tie: lowest `(q, r)`); if that is the current cell, wait. No living
 * enemies means wait.
 */
object SimpleAi {
    /** Always returns an action [CombatEngine] will accept. */
    fun chooseAction(engine: CombatEngine, actor: CombatUnit): CombatAction {
        val targets = engine.attackableTargets(actor.id)
        if (targets.isNotEmpty()) {
            val target = targets.minWithOrNull(compareBy({ it.currentHp }, { it.id }))!!
            return CombatAction.Attack(target.id)
        }

        val livingEnemies = engine.units.filter { it.alive && it.playerControlled != actor.playerControlled }
        if (livingEnemies.isEmpty()) return CombatAction.Wait

        val candidates = engine.legalMoves(actor.id)
        val best = candidates.minWithOrNull(
            compareBy<HexCoord> { cell ->
                livingEnemies.minOf { cell.distanceTo(it.position) }
            }.thenBy { it.q }.thenBy { it.r },
        )!!

        return if (best == actor.position) CombatAction.Wait else CombatAction.Move(best)
    }
}
