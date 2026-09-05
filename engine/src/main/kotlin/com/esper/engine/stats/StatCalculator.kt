package com.esper.engine.stats

import com.esper.engine.content.JobDefinition
import com.esper.engine.dice.DiceExpr

/**
 * Job + level -> stats. The only place a character's numbers are computed.
 *
 * `derive` is exactly:
 * ```
 * scores      = scoresAtLevel(base, job, level)   // base + statGrowth * (level - 1)
 * conMod      = scores.modifier("con")
 * sides       = DiceExpr.parse(job.hitDie).sides
 * maxHp       = max(1, (sides + conMod) + (level - 1) * ((sides / 2 + 1) + conMod))
 * armorClass  = job.baseArmorClass + scores.modifier("dex")
 * attackBonus = scores.modifier(job.attackAbility) + proficiencyBonus(level)
 * damage      = DiceExpr.parse(job.damage) with modifier += scores.modifier(job.attackAbility)
 * speed/moveRangeCells/attackRangeCells copied from the job
 * ```
 */
object StatCalculator {
    fun scoresAtLevel(base: AbilityScores, job: JobDefinition, level: Int): AbilityScores =
        base.plus(job.statGrowth, level - 1)

    fun derive(base: AbilityScores, job: JobDefinition, level: Int): DerivedStats {
        val scores = scoresAtLevel(base, job, level)
        val conMod = scores.modifier("con")
        val sides = DiceExpr.parse(job.hitDie).sides
        val maxHp = maxOf(
            1,
            (sides + conMod) + (level - 1) * ((sides / 2 + 1) + conMod),
        )
        val armorClass = job.baseArmorClass + scores.modifier("dex")
        val attackBonus = scores.modifier(job.attackAbility) + proficiencyBonus(level)
        val baseDamage = DiceExpr.parse(job.damage)
        val damage = baseDamage.copy(
            modifier = baseDamage.modifier + scores.modifier(job.attackAbility),
        )
        return DerivedStats(
            maxHp = maxHp,
            armorClass = armorClass,
            attackBonus = attackBonus,
            damage = damage,
            speed = job.speed,
            moveRangeCells = job.moveRangeCells,
            attackRangeCells = job.attackRangeCells,
        )
    }

    /** `2 + (level - 1) / 4`, integer division. */
    fun proficiencyBonus(level: Int): Int = 2 + (level - 1) / 4
}
