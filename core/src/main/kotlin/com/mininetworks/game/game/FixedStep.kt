package com.mininetworks.game.game

/**
 * Fixed-timestep accumulator: turns variable frame times into a whole number of [step]-sized simulation ticks,
 * so the simulation is deterministic regardless of frame rate.
 * At most [maxSteps] ticks run per frame; time beyond that is dropped to avoid a spiral of death after a hitch.
 */
class FixedStep(val step: Float = FIXED_DT, val maxSteps: Int = MAX_STEPS_PER_FRAME) {

    /** Unsimulated time carried over to the next frame, always in 0 until [step] after [advance]. */
    var accumulator = 0f
        private set

    /** Adds [frameSeconds] of real time and calls [tick] once per whole step. Returns the number of ticks run. */
    inline fun advance(frameSeconds: Float, tick: (Float) -> Unit): Int {
        var acc = accumulator + frameSeconds.coerceAtLeast(0f)
        var steps = 0
        while (acc >= step && steps < maxSteps) {
            tick(step)
            acc -= step
            steps++
        }
        if (acc >= step) acc %= step
        setAccumulator(acc)
        return steps
    }

    /** Drops pending time, e.g. after resuming from a pause. */
    fun reset() { accumulator = 0f }

    @PublishedApi internal fun setAccumulator(value: Float) { accumulator = value }

    companion object {
        /** Simulation step for [World.update]: 1/60 s. */
        const val FIXED_DT = 1f / 60f
        const val MAX_STEPS_PER_FRAME = 5
    }
}
