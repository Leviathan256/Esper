package com.esper.engine.stats

import com.esper.engine.content.JobDefinition

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
        TODO("implemented by engine-character")

    fun derive(base: AbilityScores, job: JobDefinition, level: Int): DerivedStats =
        TODO("implemented by engine-character")

    /** `2 + (level - 1) / 4`, integer division. */
    fun proficiencyBonus(level: Int): Int = TODO("implemented by engine-character")
}
