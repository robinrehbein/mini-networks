package com.mininetworks.game.game

import kotlin.random.Random

/**
 * [Random] with the same sequence as `Random(seed)` that can be saved and restored: it counts its draws, and
 * [restore] replays that many draws on a fresh generator. Every derived value ([nextInt], [nextFloat], shuffling ...)
 * goes through [nextBits], and each draw of the underlying generator advances it by exactly one step.
 */
class ReplayableRandom(val seed: Long) : Random() {
    private val inner = Random(seed)

    /** Number of draws taken so far. */
    var draws = 0L
        private set

    override fun nextBits(bitCount: Int): Int {
        draws++
        return inner.nextBits(bitCount)
    }

    companion object {
        /** A generator for [seed] that continues right after [draws] draws. */
        fun restore(seed: Long, draws: Long): ReplayableRandom {
            require(draws >= 0) { "negative draw count" }
            return ReplayableRandom(seed).apply { repeat(draws) { nextBits(32) } }
        }

        private inline fun repeat(times: Long, action: () -> Unit) {
            var i = 0L
            while (i < times) { action(); i++ }
        }
    }
}
