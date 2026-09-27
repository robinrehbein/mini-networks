package com.mininetworks.game.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.mininetworks.game.game.CellRect
import com.mininetworks.game.game.GrowthRecorder
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.render.CableStyles
import com.mininetworks.game.render.ServiceColors
import com.mininetworks.game.render.Shapes
import com.mininetworks.game.render.fill
import com.mininetworks.game.render.stroke
import kotlin.math.floor

/**
 * The game-over look back (docs/TOP100.md B3): the player's network growing from the first cable to the last, as a
 * time-lapse of the [GrowthRecorder] frames in a small flat map. It plays in [PLAY_SECONDS], holds the end for
 * [HOLD_SECONDS] and starts over. [dateOf] labels a frame (e.g. "2004 · Woche 5").
 */
class GrowthRecap(private val density: Float, private val dateOf: (GrowthRecorder.Frame) -> String) {
    private val land = fill(0xFFF3F1EC.toInt())
    private val veil = fill(0xFFE2DED5.toInt())
    private val water = fill(0xFFC3DCE8.toInt())
    private val line = stroke(0).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dot = fill(0)
    private val ink = stroke(0xFF262B33.toInt())
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; color = 0xFF262B33.toInt() }
    private val pill = fill(0xE6FFFFFF.toInt())
    private val progress = fill(0xFF3BA55C.toInt())
    private val path = Path()
    private val r = RectF()

    /** Which frame shows [t] seconds into the recap, for [count] frames. */
    fun frameAt(t: Float, count: Int): Int {
        if (count <= 1) return 0
        val cycle = PLAY_SECONDS + HOLD_SECONDS
        val u = (t - floor(t / cycle) * cycle) / PLAY_SECONDS
        return (u.coerceIn(0f, 1f) * (count - 1)).toInt()
    }

    /** Width over height of the map of [frames] (the last frame's unlocked area), kept between 1.2 and 2.6. */
    fun aspect(frames: List<GrowthRecorder.Frame>): Float = area(frames).let { it.width.toFloat() / it.height }.coerceIn(1.2f, 2.6f)

    private fun area(frames: List<GrowthRecorder.Frame>): CellRect = frames.last().unlocked

    /** Draws frame [frameAt] ([t] seconds in) of [frames] into [out]; [isWater] tells the terrain. */
    fun draw(canvas: Canvas, out: RectF, frames: List<GrowthRecorder.Frame>, t: Float, isWater: (Int, Int) -> Boolean) {
        if (frames.isEmpty()) return
        val k = frameAt(t, frames.size)
        val f = frames[k]
        val a = area(frames)
        val cell = minOf(out.width() / a.width, out.height() / a.height)
        val ox = out.centerX() - a.width * cell / 2f - a.left * cell
        val oy = out.centerY() - a.height * cell / 2f - a.top * cell
        fun x(v: Float) = ox + v * cell
        fun y(v: Float) = oy + v * cell
        canvas.drawRoundRect(out, 10 * density, 10 * density, veil)
        r.set(x(f.unlocked.left.toFloat()), y(f.unlocked.top.toFloat()), x(f.unlocked.right.toFloat()), y(f.unlocked.bottom.toFloat()))
        canvas.drawRect(r, land)
        for (cy in a.top until a.bottom) for (cx in a.left until a.right) {
            if (isWater(cx, cy)) canvas.drawRect(x(cx.toFloat()), y(cy.toFloat()), x(cx + 1f), y(cy + 1f), water)
        }
        for (c in f.cables) {
            path.reset()
            c.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.x), y(p.y)) else path.lineTo(x(p.x), y(p.y)) }
            line.color = CableStyles.of(c.type).color
            line.strokeWidth = maxOf(cell * CableStyles.of(c.type).width * 1.4f, 1.5f * density)
            canvas.drawPath(path, line)
        }
        for (n in f.nodes) {
            val cx = x(n.x + n.size / 2f); val cy = y(n.y + n.size / 2f)
            when (n.kind) {
                NodeKind.SERVER -> {
                    val h = cell * (0.32f + 0.08f * n.level) * n.size
                    val service = n.service!!
                    dot.color = ServiceColors.of(service)
                    canvas.drawRoundRect(cx - h, cy - h, cx + h, cy + h, h * 0.3f, h * 0.3f, dot)
                    dot.color = 0xFFFFFFFF.toInt()
                    Shapes.draw(canvas, service.shape, cx, cy, h * 0.45f, dot)
                }
                NodeKind.CLIENT -> {
                    dot.color = 0xFF262B33.toInt()
                    canvas.drawCircle(cx, cy, maxOf(cell * 0.2f, 1.5f * density), dot)
                }
                else -> {
                    dot.color = 0xFFFFFFFF.toInt()
                    val h = maxOf(cell * 0.24f, 2f * density)
                    canvas.drawRect(cx - h, cy - h, cx + h, cy + h, dot)
                    ink.strokeWidth = maxOf(cell * 0.06f, 1f)
                    canvas.drawRect(cx - h, cy - h, cx + h, cy + h, ink)
                }
            }
        }
        // The date of the frame in a pill, and how far the time-lapse has run.
        label.textSize = 10f * density
        val text = dateOf(f)
        val w = label.measureText(text) + 12 * density
        r.set(out.left + 4 * density, out.top + 4 * density, out.left + 4 * density + w, out.top + 20 * density)
        canvas.drawRoundRect(r, 8 * density, 8 * density, pill)
        canvas.drawText(text, r.left + 6 * density, r.centerY() + label.textSize * 0.36f, label)
        val done = if (frames.size <= 1) 1f else k / (frames.size - 1f)
        canvas.drawRect(out.left, out.bottom - 3 * density, out.left + out.width() * done, out.bottom, progress)
    }

    companion object {
        const val PLAY_SECONDS = 4f
        const val HOLD_SECONDS = 1.5f
    }
}
