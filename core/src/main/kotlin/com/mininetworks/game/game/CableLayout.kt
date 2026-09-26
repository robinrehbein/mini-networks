package com.mininetworks.game.game

import kotlin.math.abs
import kotlin.math.floor

/** Which leg of an L-shaped cable comes first, seen from the cable's start. */
enum class Bend { HORIZONTAL_FIRST, VERTICAL_FIRST }

/** A grid cell. */
data class Cell(val x: Int, val y: Int) {
    val center get() = Vec2(x + 0.5f, y + 0.5f)
}

/**
 * Geometry of one cable: grid-aligned waypoints through cell centers, like a road. Consecutive waypoints share a row
 * or a column, so there are no diagonals. Length, cost, water crossings and packet movement all derive from it.
 */
class CableLayout(val waypoints: List<Vec2>) {
    init {
        require(waypoints.isNotEmpty())
        for (p in waypoints) require(isCenter(p.x) && isCenter(p.y)) { "waypoint $p is not a cell center" }
        for (i in 0 until waypoints.size - 1) {
            require(waypoints[i].x == waypoints[i + 1].x || waypoints[i].y == waypoints[i + 1].y) { "diagonal segment" }
        }
    }

    /** Every cell the cable walks through, start and end included, in order. */
    val cells: List<Cell> = buildList {
        add(cellOf(waypoints.first()))
        for (i in 0 until waypoints.size - 1) {
            val from = cellOf(waypoints[i])
            val to = cellOf(waypoints[i + 1])
            val sx = (to.x - from.x).coerceIn(-1, 1)
            val sy = (to.y - from.y).coerceIn(-1, 1)
            var c = from
            while (c != to) {
                c = Cell(c.x + sx, c.y + sy)
                add(c)
            }
        }
    }

    /** Number of cell-to-cell steps; also the cable length in world units. */
    val steps get() = cells.size - 1

    val length get() = steps.toFloat()

    val start get() = waypoints.first()
    val end get() = waypoints.last()

    /** Point at fraction [f] (0 = start, 1 = end) of the way along the cable. */
    fun pointAt(f: Float): Vec2 = Geometry.pointAlong(waypoints, f)

    companion object {
        private fun isCenter(v: Float) = abs(v - floor(v) - 0.5f) < 1e-4f
        private fun cellOf(p: Vec2) = Cell(floor(p.x).toInt(), floor(p.y).toInt())

        /** L-shaped layout from cell [a] to cell [b]; a straight line when they share a row or column. */
        fun between(a: Cell, b: Cell, bend: Bend): CableLayout {
            val corner = if (bend == Bend.HORIZONTAL_FIRST) Cell(b.x, a.y) else Cell(a.x, b.y)
            return CableLayout(listOf(a, corner, b).distinct().map { it.center })
        }

        /**
         * The bend the pointer [trail] (world points of the drag) follows clearly more closely, or null when the drag
         * does not prefer one, e.g. for straight cables or a drag straight across the diagonal.
         */
        fun suggestBend(a: Cell, b: Cell, trail: List<Vec2>): Bend? {
            if (a.x == b.x || a.y == b.y || trail.isEmpty()) return null
            val h = between(a, b, Bend.HORIZONTAL_FIRST).waypoints
            val v = between(a, b, Bend.VERTICAL_FIRST).waypoints
            val dh = trail.sumOf { Geometry.distToPolyline(it, h).toDouble() }
            val dv = trail.sumOf { Geometry.distToPolyline(it, v).toDouble() }
            return when {
                dh < dv * CLEAR_PREFERENCE -> Bend.HORIZONTAL_FIRST
                dv < dh * CLEAR_PREFERENCE -> Bend.VERTICAL_FIRST
                else -> null
            }
        }

        /** A bend counts as suggested when the trail is this much closer to it than to the other one. */
        private const val CLEAR_PREFERENCE = 0.75
    }
}
