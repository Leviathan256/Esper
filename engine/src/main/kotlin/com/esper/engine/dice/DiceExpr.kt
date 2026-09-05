package com.esper.engine.dice

/**
 * Parsed dice notation, e.g. `1d8+2`.
 *
 * Content authors write one string per damage field; count, sides and modifier are
 * kept apart in here so a critical hit can double the dice without doubling the
 * modifier.
 */
data class DiceExpr(val count: Int, val sides: Int, val modifier: Int) {
    /** Sums [count] dice of [sides] sides plus [modifier]. Zero dice contribute 0. */
    fun roll(rng: RandomSource): Int {
        var total = 0
        repeat(count) { total += rng.nextInt(sides) + 1 }
        return total + modifier
    }

    /** D&D crit: dice count doubled, modifier applied once. */
    fun rollCritical(rng: RandomSource): Int {
        var total = 0
        repeat(count * 2) { total += rng.nextInt(sides) + 1 }
        return total + modifier
    }

    val average: Double get() = count * (sides + 1) / 2.0 + modifier

    /** Round-trips through [parse]: `"1d8+2"`, `"0"`. */
    override fun toString(): String {
        if (count == 0 && sides == 0) return modifier.toString()
        val base = "${count}d${sides}"
        return when {
            modifier > 0 -> "$base+$modifier"
            modifier < 0 -> "$base$modifier"
            else -> base
        }
    }

    companion object {
        val ZERO: DiceExpr = DiceExpr(count = 0, sides = 0, modifier = 0)

        val PATTERN: Regex = Regex("""^\s*(\d+)d(\d+)\s*([+-]\s*\d+)?\s*$""")

        private val BARE_INT_PATTERN = Regex("""^\s*([+-]?\d+)\s*$""")

        /** Also accepts a bare integer. Throws [IllegalArgumentException] otherwise. */
        fun parse(text: String): DiceExpr {
            return parseOrNull(text)
                ?: throw IllegalArgumentException("not a valid dice expression: \"$text\"")
        }

        fun parseOrNull(text: String): DiceExpr? {
            val diceMatch = PATTERN.matchEntire(text)
            if (diceMatch != null) {
                val (countText, sidesText, modifierText) = diceMatch.destructured
                val count = countText.toIntOrNull() ?: return null
                val sides = sidesText.toIntOrNull() ?: return null
                val modifier = modifierText
                    .replace(" ", "")
                    .takeIf { it.isNotEmpty() }
                    ?.toIntOrNull() ?: 0
                return DiceExpr(count = count, sides = sides, modifier = modifier)
            }
            val bareMatch = BARE_INT_PATTERN.matchEntire(text) ?: return null
            val value = bareMatch.groupValues[1].toIntOrNull() ?: return null
            return DiceExpr(count = 0, sides = 0, modifier = value)
        }
    }
}
