package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import java.util.Locale

/**
 * Micro-benchmark guard for the simulation (docs/PLAN.md P4.2): 600 steps (10 s of play) of the [StressWorld], with
 * 60+ nodes and 200+ packets, must stay far below real time. The bound is generous so slow CI machines pass; the
 * numbers are printed for docs/PLAN.md.
 */
@OptIn(DebugApi::class)
class PerformanceTest {

    @Test
    fun stressWorldIsLargeAndBusy() {
        val w = StressWorld.build()
        assertTrue("nodes: ${w.nodes.size}", w.nodes.size >= 60)
        var peak = 0
        repeat(STEPS) {
            w.update(STEP)
            peak = maxOf(peak, w.packets.size)
        }
        assertTrue("peak packets: $peak", peak >= 200)
        assertFalse(w.gameOver)
        assertTrue("delivered: ${w.delivered}", w.delivered > 30)
        println("PerformanceTest: ${w.nodes.size} nodes, ${w.cables.size} cables, peak $peak packets, ${w.delivered} delivered")
    }

    @Test
    fun loadCountersMatchARecountAfterEveryStep() {
        val w = StressWorld.build(seed = 2L)
        val router = w.nodes.first { it.kind == NodeKind.ROUTER && w.ports(it) > 2 }
        repeat(STEPS) { step ->
            when (step) {
                120 -> w.announceExcavator(w.cables.first { it.a === router || it.b === router })
                240 -> w.announcePowerOutage(w.nodes.last { it.kind == NodeKind.ROUTER })
            }
            w.update(STEP)
            for (link in w.cables + w.radioLinks) {
                assertEquals("moving on $link", countOn(w, link, moving = true), w.linkLoad(link))
                assertEquals("waiting for $link", countOn(w, link, moving = false), w.linkWaiting(link))
            }
        }
        assertTrue("the excavator struck", w.incidents.any { it.struck })
    }

    /** The load of the medium of [link], counted packet by packet. */
    private fun countOn(w: World, link: Link, moving: Boolean) = w.packets.sumOf {
        val on = it.inTransit && (it.progress >= 0f) == moving && w.linkBetween(it.from, it.to)?.medium === link.medium
        if (on) it.size else 0
    }

    @Test
    fun sixHundredStepsOfALargeWorldStayFast() {
        // Warm-up so the JIT has compiled the hot paths; every measured run starts from a fresh, identical world.
        repeat(WARMUP_RUNS) { run() }
        val runs = List(MEASURED_RUNS) { run() }
        val best = runs.minOf { it.nanos }
        val kb = runs.minOf { it.bytes } / 1024.0 / STEPS
        println(
            "PerformanceTest: $STEPS steps of the stress world took %.1f ms (best of $MEASURED_RUNS), %.2f KiB allocated per step"
                .format(Locale.ROOT, best / 1e6, kb),
        )
        assertTrue("600 steps took ${best / 1_000_000} ms", best < LIMIT_NANOS)
    }

    /**
     * docs/TOP100.md section 4: 600 steps of the large stress world (at least 150 nodes) take at most 250 ms on the
     * JVM, best of [MEASURED_RUNS] after a warm-up.
     */
    @Test
    fun sixHundredStepsOfAHundredFiftyNodeWorldStayWithinTarget() {
        val nodes = StressWorld.buildLarge().nodes.size
        assertTrue("nodes: $nodes", nodes >= 150)
        repeat(WARMUP_RUNS) { run(StressWorld::buildLarge) }
        val runs = List(MEASURED_RUNS) { run(StressWorld::buildLarge) }
        val best = runs.minOf { it.nanos }
        println(
            "PerformanceTest: $STEPS steps of the large stress world ($nodes nodes) took %.1f ms (best of $MEASURED_RUNS)"
                .format(Locale.ROOT, best / 1e6),
        )
        assertTrue("600 steps with $nodes nodes took ${best / 1_000_000} ms", best <= TARGET_150_NANOS)
    }

    private class Run(val nanos: Long, val bytes: Long)

    /** Time and heap allocation of [STEPS] updates of a fresh stress world. */
    private fun run(build: (Long) -> World = StressWorld::build): Run {
        val w = build(1L)
        val bytes = allocatedBytes()
        val start = System.nanoTime()
        repeat(STEPS) { w.update(STEP) }
        val took = System.nanoTime() - start
        val allocated = allocatedBytes() - bytes
        assertTrue(w.packets.size >= 200)
        return Run(took, allocated)
    }

    /** Bytes this thread allocated so far, or 0 on a JVM without HotSpot's thread bean. */
    private fun allocatedBytes() =
        (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)?.currentThreadAllocatedBytes ?: 0L

    private companion object {
        const val STEP = 1f / 60f
        const val STEPS = 600
        const val WARMUP_RUNS = 3
        const val MEASURED_RUNS = 3
        /** 0.5 s for 10 s of play: about 40× the time measured on a laptop (docs/PLAN.md), so slow machines pass too. */
        const val LIMIT_NANOS = 500_000_000L
        /** docs/TOP100.md section 4: 150 nodes, 600 steps, at most 250 ms on the JVM. */
        const val TARGET_150_NANOS = 250_000_000L
    }
}
