package com.esper.engine.stats

import com.esper.engine.dice.DiceExpr

/**
 * Everything combat needs about a unit, already computed.
 *
 * `combat` consumes this as a plain data holder and never calls [StatCalculator],
 * which is what lets a monster's flat stat block and a character's job-derived
 * stats meet on the same board.
 */
data class DerivedStats(
    val maxHp: Int,
    val armorClass: Int,
    val attackBonus: Int,
    val damage: DiceExpr,
    val speed: Int,
    val moveRangeCells: Int,
    val attackRangeCells: Int,
)
