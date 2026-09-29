package com.esper.engine.content

import kotlinx.serialization.Serializable

/**
 * One monster, authored as `content/monsters/<id>.json`.
 *
 * A flat stat block, not D&D ability scores: nothing in the MVP derives a
 * monster's AC or to-hit from scores, so carrying them would be dead data. One
 * attack per monster; a `List<AttackDefinition>` is the natural extension.
 */
@Serializable
data class MonsterDefinition(
    val schemaVersion: Int,
    val id: String,
    val displayName: String,
    val maxHp: Int,
    val armorClass: Int,
    val attackBonus: Int,
    val damage: String,
    val speed: Int,
    val moveRangeCells: Int,
    val attackRangeCells: Int = 1,
    val xpReward: Int,
    val jobPointsReward: Int,
    val bestiaryText: String = "",
)
