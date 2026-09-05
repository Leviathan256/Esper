package com.esper.engine.combat

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
    fun chooseAction(engine: CombatEngine, actor: CombatUnit): CombatAction =
        TODO("implemented by engine-combat")
}
