package com.esper.engine.dice

/** The outcome of one d20 attack roll, kept so the UI can show the actual dice. */
data class AttackRoll(
    val d20: Int,
    val total: Int,
    val hit: Boolean,
    val critical: Boolean,
    val fumble: Boolean,
)

/** D&D-style resolution. The one place an outcome is decided. */
object CombatMath {
    /**
     * `d20 + attackBonus` vs AC. Natural 20 is a critical and always hits; natural
     * 1 is a fumble and always misses.
     */
    fun rollAttack(attackBonus: Int, targetArmorClass: Int, rng: RandomSource): AttackRoll {
        val d20 = rng.nextInt(20) + 1
        val total = d20 + attackBonus
        val critical = d20 == 20
        val fumble = d20 == 1
        val hit = !fumble && (critical || total >= targetArmorClass)
        return AttackRoll(d20 = d20, total = total, hit = hit, critical = critical, fumble = fumble)
    }

    /** Never returns below 0. */
    fun rollDamage(damage: DiceExpr, critical: Boolean, rng: RandomSource): Int {
        val raw = if (critical) damage.rollCritical(rng) else damage.roll(rng)
        return maxOf(0, raw)
    }
}
