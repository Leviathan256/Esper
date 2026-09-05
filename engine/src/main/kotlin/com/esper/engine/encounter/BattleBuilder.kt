package com.esper.engine.encounter

import com.esper.engine.character.CharacterState
import com.esper.engine.combat.CombatUnit
import com.esper.engine.content.ContentCatalog
import com.esper.engine.geometry.HexBoard

/** A board plus the units standing on it, ready to hand to a `CombatEngine`. */
data class Battle(val board: HexBoard, val units: List<CombatUnit>)

/**
 * The single engine-side entry point from (encounter + character + catalog) to a
 * ready battle, so the Android layer assembles nothing itself.
 */
object BattleBuilder {
    const val PLAYER_UNIT_ID: String = "player"

    const val MONSTER_START_RING_CELLS: Int = 4

    /** Player at ORIGIN; monsters spread deterministically around the ring at radius 4. */
    fun build(
        encounter: Encounter,
        character: CharacterState,
        catalog: ContentCatalog,
    ): Battle = TODO("implemented by engine-encounter")
}
