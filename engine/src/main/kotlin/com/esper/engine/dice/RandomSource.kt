package com.esper.engine.dice

/**
 * The only source of randomness in the engine.
 *
 * Everything that rolls takes one of these, so every test can pin a seed and every
 * bug report is reproducible from the seed alone.
 */
interface RandomSource {
    /** Uniform in `0 until bound`. */
    fun nextInt(bound: Int): Int

    /** Uniform in `[0.0, 1.0)`. */
    fun nextDouble(): Double
}

/** Deterministic. Every test uses this. */
class SeededRandom(seed: Long) : RandomSource {
    override fun nextInt(bound: Int): Int = TODO("implemented by engine-dice")

    override fun nextDouble(): Double = TODO("implemented by engine-dice")
}

/** The one non-deterministic source. Used only by the running app. */
object SystemRandom : RandomSource {
    override fun nextInt(bound: Int): Int = TODO("implemented by engine-dice")

    override fun nextDouble(): Double = TODO("implemented by engine-dice")
}
