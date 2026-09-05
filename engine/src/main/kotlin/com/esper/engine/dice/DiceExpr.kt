package com.esper.engine.dice

/**
 * Parsed dice notation, e.g. `1d8+2`.
 *
 * Content authors write one string per damage field; count, sides and modifier are
 * kept apart in here so a critical hit can double the dice without doubling the
 * modifier.
 */
data class DiceExpr(val count: Int, val sides: Int, val modifier: Int) {
    fun roll(rng: RandomSource): Int = TODO("implemented by engine-dice")

    /** D&D crit: dice count doubled, modifier applied once. */
    fun rollCritical(rng: RandomSource): Int = TODO("implemented by engine-dice")

    val average: Double get() = TODO("implemented by engine-dice")

    /** Round-trips through [parse]: `"1d8+2"`, `"0"`. */
    override fun toString(): String = TODO("implemented by engine-dice")

    companion object {
        val ZERO: DiceExpr = DiceExpr(count = 0, sides = 0, modifier = 0)

        val PATTERN: Regex = Regex("""^\s*(\d+)d(\d+)\s*([+-]\s*\d+)?\s*$""")

        /** Also accepts a bare integer. Throws [IllegalArgumentException] otherwise. */
        fun parse(text: String): DiceExpr = TODO("implemented by engine-dice")

        fun parseOrNull(text: String): DiceExpr? = TODO("implemented by engine-dice")
    }
}
