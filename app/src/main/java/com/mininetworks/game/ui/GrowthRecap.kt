package com.mininetworks.game.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.mininetworks.game.game.CellRect
import com.mininetworks.game.game.GrowthRecorder
import com.mininetworks.game.game.NodeKind
import android.graphics.LinearGradient
import android.graphics.Shader
import com.mininetworks.game.render.CableStyles
import com.mininetworks.game.render.Cosmetic
import com.mininetworks.game.render.MapPalette
import com.mininetworks.game.render.shade
import com.mininetworks.game.render.ServiceColors
import com.mininetworks.game.render.Shapes
import com.mininetworks.game.render.fill
import com.mininetworks.game.render.stroke
import kotlin.math.floor

/**
 * The game-over look back (docs/TOP100.md B3): the player's network growing from the first cable to the last, as a
 * time-lapse of the [GrowthRecorder] frames in a small isometric map. It plays in [PLAY_SECONDS], holds the end for
 * [HOLD_SECONDS] and starts over. [dateOf] labels a frame (e.g. "2004 · Woche 5").
 */
class GrowthRecap(private val density: Float, private val dateOf: (GrowthRecorder.Frame) -> String) {
    private val casing = stroke(0xFFFFFFFF.toInt()).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val line = stroke(0).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dot = fill(0)
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

    /** Width over height of the recap: the isometric board (twice as wide as high) with room for its buildings. */
    @Suppress("UNUSED_PARAMETER")
    fun aspect(frames: List<GrowthRecorder.Frame>): Float = ASPECT

    private fun area(frames: List<GrowthRecorder.Frame>): CellRect = frames.last().unlocked

    // Isometric projection of the current frame: origin and half tile width in px.
    private var ox = 0f
    private var oy = 0f
    private var u = 1f
    private fun px(x: Float, y: Float) = ox + (x - y) * u
    private fun py(x: Float, y: Float, z: Float = 0f) = oy + (x + y) * u / 2f - z

    /**
     * Draws frame [frameAt] ([t] seconds in) of [frames] into [out] as a small isometric diorama in the colours of
     * [palette], like the map and the share card (judge panel: a flat wiring plan clashed with the rest of the game);
     * [isWater] tells the terrain.
     */
    fun draw(
        canvas: Canvas, out: RectF, frames: List<GrowthRecorder.Frame>, t: Float, isWater: (Int, Int) -> Boolean,
        palette: MapPalette = Cosmetic.palette,
    ) {
        if (frames.isEmpty()) return
        val k = frameAt(t, frames.size)
        val f = frames[k]
        val a = area(frames)
        // Backdrop: the palette's sky, a touch darker at the bottom.
        bg.shader = LinearGradient(0f, out.top, 0f, out.bottom, palette.background, palette.boardShade.blendTo(palette.background, 0.55f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(out, 10 * density, 10 * density, bg)
        val span = (a.width + a.height).toFloat()
        val thick = 0.45f
        u = minOf(out.width() * 0.96f / span, out.height() * 0.9f / (span / 2f + thick + 1.6f))
        ox = out.centerX() - ((a.right - a.top) + (a.left - a.bottom)) / 2f * u
        oy = out.centerY() + u * 0.6f - ((a.left + a.top) + (a.right + a.bottom)) / 4f * u
        val depth = thick * u
        // The board's two visible sides, then every tile.
        quad(px(a.left.toFloat(), a.bottom.toFloat()), py(a.left.toFloat(), a.bottom.toFloat()), px(a.right.toFloat(), a.bottom.toFloat()), py(a.right.toFloat(), a.bottom.toFloat()),
            px(a.right.toFloat(), a.bottom.toFloat()), py(a.right.toFloat(), a.bottom.toFloat()) + depth, px(a.left.toFloat(), a.bottom.toFloat()), py(a.left.toFloat(), a.bottom.toFloat()) + depth)
        tile.color = palette.boardLit; canvas.drawPath(path, tile)
        quad(px(a.right.toFloat(), a.top.toFloat()), py(a.right.toFloat(), a.top.toFloat()), px(a.right.toFloat(), a.bottom.toFloat()), py(a.right.toFloat(), a.bottom.toFloat()),
            px(a.right.toFloat(), a.bottom.toFloat()), py(a.right.toFloat(), a.bottom.toFloat()) + depth, px(a.right.toFloat(), a.top.toFloat()), py(a.right.toFloat(), a.top.toFloat()) + depth)
        tile.color = palette.boardShade; canvas.drawPath(path, tile)
        for (cy in a.top until a.bottom) for (cx in a.left until a.right) {
            val open = f.unlocked.contains(cx, cy)
            val even = (cx + cy) % 2 == 0
            tile.color = when {
                isWater(cx, cy) -> if (open) (if (even) palette.waterA else palette.waterB) else (if (even) palette.lockedWaterA else palette.lockedWaterB)
                open -> if (even) palette.landA else palette.landB
                else -> if (even) palette.lockedLandA else palette.lockedLandB
            }
            val x = cx.toFloat(); val y = cy.toFloat()
            quad(px(x, y), py(x, y), px(x + 1, y), py(x + 1, y), px(x + 1, y + 1), py(x + 1, y + 1), px(x, y + 1), py(x, y + 1))
            canvas.drawPath(path, tile)
        }
        for (c in f.cables) {
            path.reset()
            c.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(px(p.x, p.y), py(p.x, p.y)) else path.lineTo(px(p.x, p.y), py(p.x, p.y)) }
            val st = CableStyles.of(c.type)
            line.strokeWidth = maxOf(u * st.width * 1.6f, 2f * density)
            casing.strokeWidth = line.strokeWidth + 2f * density
            canvas.drawPath(path, casing)
            line.color = st.color
            canvas.drawPath(path, line)
            st.core?.let { line.color = it; line.strokeWidth = maxOf(u * st.coreWidth * 1.4f, 1f * density); canvas.drawPath(path, line) }
        }
        // Buildings back to front.
        for (n in f.nodes.sortedBy { it.x + it.y + it.size }) {
            val cx = n.x + n.size / 2f; val cy = n.y + n.size / 2f
            when (n.kind) {
                NodeKind.SERVER -> {
                    val col = ServiceColors.of(n.service!!)
                    val h = u * (0.55f + 0.25f * n.level) * (if (n.size > 1) 1.4f else 1f)
                    box(canvas, cx, cy, 0.78f * n.size, h, col.shade(0.15f), 0xFFE9ECEF.toInt())
                    dot.color = 0xFFFFFFFF.toInt()
                    Shapes.draw(canvas, n.service!!.shape, px(cx, cy), py(cx, cy, h), u * 0.28f * n.size, dot)
                }
                NodeKind.CLIENT -> {
                    val h = u * 0.3f
                    box(canvas, cx, cy, 0.5f, h, 0xFFFAFAF7.toInt(), 0xFFE3E6E1.toInt())
                    dot.color = 0xFF3A4350.toInt()
                    canvas.drawCircle(px(cx, cy), py(cx, cy, h) - u * 0.02f, maxOf(u * 0.13f, 1.2f * density), dot)
                }
                else -> box(canvas, cx, cy, 0.42f, u * 0.2f, 0xFFF5F7F9.toInt(), 0xFFD9DEE3.toInt())
            }
        }
        // The date of the frame in a pill, and how far the time-lapse has run.
        label.textSize = 13f * density
        val text = dateOf(f)
        val w = label.measureText(text) + 14 * density
        r.set(out.left + 6 * density, out.top + 6 * density, out.left + 6 * density + w, out.top + 26 * density)
        canvas.drawRoundRect(r, 8 * density, 8 * density, pill)
        canvas.drawText(text, r.left + 6 * density, r.centerY() + label.textSize * 0.36f, label)
        val done = if (frames.size <= 1) 1f else k / (frames.size - 1f)
        canvas.drawRect(out.left, out.bottom - 4 * density, out.left + out.width() * done, out.bottom, progress)
    }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tile = Paint().apply { style = Paint.Style.FILL }

    private fun quad(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float) {
        path.reset()
        path.moveTo(ax, ay); path.lineTo(bx, by); path.lineTo(cx, cy); path.lineTo(dx, dy); path.close()
    }

    /** An iso box [s] cells wide and [h] px high centred on cell point ([cx], [cy]). */
    private fun box(canvas: Canvas, cx: Float, cy: Float, s: Float, h: Float, top: Int, side: Int) {
        val x0 = cx - s / 2; val y0 = cy - s / 2; val x1 = cx + s / 2; val y1 = cy + s / 2
        quad(px(x0, y1), py(x0, y1), px(x1, y1), py(x1, y1), px(x1, y1), py(x1, y1, h), px(x0, y1), py(x0, y1, h))
        dot.color = side.shade(-0.12f); canvas.drawPath(path, dot)
        quad(px(x1, y0), py(x1, y0), px(x1, y1), py(x1, y1), px(x1, y1), py(x1, y1, h), px(x1, y0), py(x1, y0, h))
        dot.color = side.shade(-0.25f); canvas.drawPath(path, dot)
        quad(px(x0, y0), py(x0, y0, h), px(x1, y0), py(x1, y0, h), px(x1, y1), py(x1, y1, h), px(x0, y1), py(x0, y1, h))
        dot.color = top; canvas.drawPath(path, dot)
    }

    private fun Int.blendTo(other: Int, f: Float) = Cosmetic.blend(this, other, f)

    companion object {
        /** Width over height of the recap picture. */
        const val ASPECT = 1.75f
        const val PLAY_SECONDS = 4f
        const val HOLD_SECONDS = 1.5f
    }
}
