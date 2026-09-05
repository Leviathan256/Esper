package com.esper.engine.combat

import com.esper.engine.geometry.HexCoord
import com.esper.engine.stats.DerivedStats

/**
 * One combatant on the board.
 *
 * Immutable: the engine replaces units rather than mutating them, so a snapshot
 * handed to the UI can never change underneath it mid-frame.
 */
data class CombatUnit(
    /** e.g. "player", "goblin#0", "goblin#1". */
    val id: String,
    val displayName: String,
    val playerControlled: Boolean,
    /** Job id or monster id — drives rewards and the bestiary. */
    val sourceId: String,
    val stats: DerivedStats,
    val currentHp: Int,
    val position: HexCoord,
    val atbGauge: Double = 0.0,
) {
    val alive: Boolean get() = currentHp > 0
}
