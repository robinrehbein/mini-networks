package com.mininetworks.game.game

import kotlin.math.abs
import kotlin.math.floor

/**
 * Parallel lanes for cables that run over the same cells, like the lines of Mini Metro: laid on top of each other they
 * hide one another, and from the fourth week on most cables share cells with others (docs/BALANCING.md has the
 * measurements). [assign] gives every cable a [Cable.path]: its [CableLayout] with each straight run moved sideways to
 * the lane it got, so cables that share a cell edge always run side by side. The layout itself, the routing and the
 * costs do not change; this is only how the cable is drawn and how a packet travels on it.
 *
 * A run is the stretch between two waypoints. It takes the lowest lane no other run on any of its cell edges has taken
 * (cables in list order, so a cable already laid keeps its lane when a new one arrives), and is centred in the lanes
 * of the busiest edge it uses: a cable alone stays on the middle of its cells. More than [MAX_LANES] on one edge share
 * the last lane rather than spreading out of their cells. The offset is along the map's own axes whichever way the
 * cable runs, so two cables in opposite directions still agree on which lane is whose.
 */
object CableLanes {
    const val MAX_LANES = 4

    /** Distance between two lanes in cells; four lanes then take up 0.9 of a cell, so they stay inside their cells; wide enough that two fibers side by side do not touch. */
    const val SPACING = 0.30f

    /** How far zooming out may widen the lanes ([spreadFor]); four lanes then take up 1.6 cells, which still keeps them apart from the next row's. */
    const val MAX_SPREAD = 1.8f

    /**
     * The factor for [SPACING] that keeps two lanes at least [minPitchPx] apart on a screen where one world unit is
     * [unitPx] pixels: 1 while zoomed in, more as the map shrinks, in steps of a tenth so a pinch does not re-lay the
     * cables every frame.
     */
    fun spreadFor(unitPx: Float, minPitchPx: Float): Float {
        if (unitPx <= 0f) return 1f
        val wanted = minPitchPx / (unitPx * SPACING)
        return (Math.round(wanted.coerceIn(1f, MAX_SPREAD) * 10f) / 10f)
    }

    private class Run(val edges: LongArray, val horizontal: Boolean) {
        var lane = 0
    }

    /** Sets [Cable.path] of every cable in [cables], their lanes [spread] times as far apart as [SPACING]. */
    fun assign(cables: List<Cable>, spread: Float = 1f) {
        val used = HashMap<Long, Int>()
        val runs = ArrayList<Array<Run?>>(cables.size)
        for (c in cables) {
            val pts = c.layout.waypoints
            val perRun = arrayOfNulls<Run>(pts.size - 1)
            for (i in 0 until pts.size - 1) {
                val run = runOf(pts[i], pts[i + 1]) ?: continue
                var lane = 0
                while (lane < MAX_LANES - 1 && run.edges.any { (used[it] ?: 0) and (1 shl lane) != 0 }) lane++
                run.lane = lane
                for (e in run.edges) used[e] = (used[e] ?: 0) or (1 shl lane)
                perRun[i] = run
            }
            runs += perRun
        }
        for ((k, c) in cables.withIndex()) c.path = pathOf(c.layout.waypoints, runs[k], used, spread)
    }

    /** The straight run from [a] to [b] as the cell edges it covers; null for a zero-length or diagonal one. */
    private fun runOf(a: Vec2, b: Vec2): Run? {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return when {
            abs(dy) < EPS && abs(dx) > EPS -> {
                val row = floor(a.y).toInt()
                val from = minOf(floor(a.x).toInt(), floor(b.x).toInt())
                val to = maxOf(floor(a.x).toInt(), floor(b.x).toInt())
                Run(LongArray(to - from) { edge(0, row, from + it) }, horizontal = true)
            }
            abs(dx) < EPS && abs(dy) > EPS -> {
                val col = floor(a.x).toInt()
                val from = minOf(floor(a.y).toInt(), floor(b.y).toInt())
                val to = maxOf(floor(a.y).toInt(), floor(b.y).toInt())
                Run(LongArray(to - from) { edge(1, col, from + it) }, horizontal = false)
            }
            else -> null
        }
    }

    private fun edge(orientation: Int, line: Int, pos: Int) = (orientation * OFFSET + line + OFFSET / 2) * OFFSET + pos + OFFSET / 2

    /** The offset of [run] from the middle of its cells, in cells. */
    private fun offsetOf(run: Run, used: Map<Long, Int>, spread: Float): Float {
        var top = 0
        for (e in run.edges) top = maxOf(top, 31 - Integer.numberOfLeadingZeros(used[e] ?: 1))
        return (run.lane - top / 2f) * SPACING * spread
    }

    private fun pathOf(pts: List<Vec2>, runs: Array<Run?>, used: Map<Long, Int>, spread: Float): List<Vec2> {
        val offsets = FloatArray(runs.size) { i -> runs[i]?.let { offsetOf(it, used, spread) } ?: 0f }
        if (offsets.all { it == 0f }) return pts
        return pts.mapIndexed { i, p ->
            // A corner takes its y from the horizontal run and its x from the vertical run that meet there; the end of
            // a cable is only moved across its one run.
            var sx = 0f; var sy = 0f; var nx = 0; var ny = 0
            for (r in intArrayOf(i - 1, i)) {
                val run = runs.getOrNull(r) ?: continue
                if (run.horizontal) { sy += offsets[r]; ny++ } else { sx += offsets[r]; nx++ }
            }
            Vec2(p.x + if (nx > 0) sx / nx else 0f, p.y + if (ny > 0) sy / ny else 0f)
        }
    }

    private const val EPS = 1e-4f
    private const val OFFSET = 1L shl 20
}
