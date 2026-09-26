package com.mininetworks.game.game

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/** A block of grid cells: columns [left] until [right], rows [top] until [bottom] (right and bottom exclusive). */
@Serializable
data class CellRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val center get() = Vec2((left + right) / 2f, (top + bottom) / 2f)

    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom

    operator fun contains(c: Cell) = contains(c.x, c.y)

    /** This block grown by [rings] cells on every side, but never beyond [limit]. */
    fun expand(rings: Int, limit: CellRect) = CellRect(
        max(limit.left, left - rings),
        max(limit.top, top - rings),
        min(limit.right, right + rings),
        min(limit.bottom, bottom + rings),
    )

    companion object {
        /** A [width] × [height] block centred in [outer] (clamped to its size). */
        fun centered(outer: CellRect, width: Int, height: Int): CellRect {
            val w = min(width, outer.width)
            val h = min(height, outer.height)
            val l = outer.left + (outer.width - w) / 2
            val t = outer.top + (outer.height - h) / 2
            return CellRect(l, t, l + w, t + h)
        }
    }
}
