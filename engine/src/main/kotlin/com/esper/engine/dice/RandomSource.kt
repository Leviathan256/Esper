package com.esper.engine.dice

import java.util.Random as JavaRandom

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

/**
 * Deterministic. Every test uses this.
 *
 * Backed by `java.util.Random`, whose per-seed sequence is specified by the JDK
 * (a 48-bit linear congruential generator) and therefore stable across JVMs and
 * Kotlin versions — a test can pin a literal expected sequence and trust it stays
 * pinned.
 */
class SeededRandom(seed: Long) : RandomSource {
    private val random = JavaRandom(seed)

    override fun nextInt(bound: Int): Int = random.nextInt(bound)

    override fun nextDouble(): Double = random.nextDouble()
}

/** The one non-deterministic source. Used only by the running app. */
object SystemRandom : RandomSource {
    private val random = JavaRandom()

    override fun nextInt(bound: Int): Int = random.nextInt(bound)

    override fun nextDouble(): Double = random.nextDouble()
}
