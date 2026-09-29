package com.esper.engine.combat

import com.esper.engine.geometry.HexCoord

/**
 * What a unit can do on its turn.
 *
 * The complete MVP verb set: abilities are recorded on the character but not yet
 * castable. Every action costs the same flat gauge.
 */
sealed interface CombatAction {
    data class Move(val to: HexCoord) : CombatAction

    data class Attack(val targetId: String) : CombatAction

    data object Wait : CombatAction
}
