package com.esper.engine.combat

import com.esper.engine.dice.AttackRoll
import com.esper.engine.geometry.HexCoord

/**
 * What happened, in order.
 *
 * The UI renders these rather than recomputing anything: the engine decides every
 * outcome, the screen only draws it.
 */
sealed interface CombatEvent {
    data class TurnReady(val unitId: String) : CombatEvent

    data class Moved(val unitId: String, val from: HexCoord, val to: HexCoord) : CombatEvent

    data class Attacked(
        val attackerId: String,
        val targetId: String,
        val roll: AttackRoll,
        val damage: Int,
        val targetHpAfter: Int,
    ) : CombatEvent

    data class Defeated(val unitId: String) : CombatEvent

    /** An illegal action. Nothing was mutated and no gauge was spent. */
    data class ActionRejected(val unitId: String, val reason: String) : CombatEvent

    /** Emitted exactly once per battle. */
    data class BattleEnded(val result: BattleResult) : CombatEvent
}

enum class BattleResult { VICTORY, DEFEAT }
