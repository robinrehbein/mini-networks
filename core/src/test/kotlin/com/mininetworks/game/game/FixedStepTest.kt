package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedStepTest {

    @Test
    fun runsOneTickPerWholeStepAndCarriesRemainder() {
        val clock = FixedStep()
        val ticks = mutableListOf<Float>()
        assertEquals(0, clock.advance(0.01f) { ticks += it })
        assertEquals(1, clock.advance(0.01f) { ticks += it })
        assertEquals(listOf(FixedStep.FIXED_DT), ticks)
        assertEquals(0.02f - FixedStep.FIXED_DT, clock.accumulator, 1e-6f)
    }

    @Test
    fun capsTicksPerFrameAndDropsBacklog() {
        val clock = FixedStep()
        var n = 0
        assertEquals(FixedStep.MAX_STEPS_PER_FRAME, clock.advance(1f) { n++ })
        assertEquals(FixedStep.MAX_STEPS_PER_FRAME, n)
        assertTrue("backlog is dropped", clock.accumulator < FixedStep.FIXED_DT)
    }

    @Test
    fun simulationIsIndependentOfFrameRate() {
        fun run(frame: Float): World {
            val w = World(seed = 7L)
            val clock = FixedStep()
            var ticks = 0
            while (ticks < 1800) clock.advance(frame) { if (ticks < 1800) { w.update(it); ticks++ } }
            return w
        }
        val a = run(1f / 60f)
        val b = run(1f / 23f)
        assertEquals(a.time, b.time, 0f)
        assertEquals(a.delivered, b.delivered)
        assertEquals(a.nodes.map { it.toString() }, b.nodes.map { it.toString() })
    }

    @Test
    fun ignoresNegativeFrameTime() {
        val clock = FixedStep()
        assertEquals(0, clock.advance(-1f) {})
        assertEquals(0f, clock.accumulator, 0f)
    }
}
