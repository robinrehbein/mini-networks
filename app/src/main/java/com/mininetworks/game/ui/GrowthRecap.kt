package com.mininetworks.game.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.mininetworks.game.game.Cell
import com.mininetworks.game.game.CellRect
import com.mininetworks.game.game.GrowthRecorder
import com.mininetworks.game.game.NodeKind
import android.graphics.LinearGradient
import android.graphics.Shader
import com.mininetworks.game.render.CableStyles
import com.mininetworks.game.render.Decor
import com.mininetworks.game.render.DeviceIcons
import com.mininetworks.game.render.Scenery
import com.mininetworks.game.render.Cosmetic
import com.mininetworks.game.render.MapPalette
import com.mininetworks.game.render.shade
import com.mininetworks.game.render.ServiceColors
import com.mininetworks.game.render.ServiceGlyphs
import com.mininetworks.game.render.fill
import com.mininetworks.game.render.stroke
import kotlin.math.abs
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
        /** The map's seed: trees and cottages stand where they stood on the map. Null leaves the land bare. */
        seed: Long? = null,
        /** The cell of the device that lost the game: it pulses red on the last frame. */
        failed: Cell? = null,
    ) {
        if (frames.isEmpty()) return
        val k = frameAt(t, frames.size)
        val f = frames[k]
        val a = area(frames)
        // Backdrop: the palette's sky, a touch darker at the bottom.
        bg.shader = LinearGradient(0f, out.top, 0f, out.bottom, palette.background, palette.boardShade.blendTo(palette.background, 0.55f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(out, 10 * density, 10 * density, bg)
        // Framed on the network the player built (judge panel: the whole board made a small, pale thumbnail), with a
        // cell of land around it; the rest of the board runs out of the picture.
        val v = focusArea(frames.last(), a)
        val span = (v.width + v.height).toFloat()
        val thick = 0.45f
        u = minOf(out.width() * 0.98f / span, out.height() * 0.94f / (span / 2f + thick + 0.9f))
        ox = out.centerX() - ((v.right - v.top) + (v.left - v.bottom)) / 2f * u
        oy = out.centerY() + u * 0.6f - ((v.left + v.top) + (v.right + v.bottom)) / 4f * u
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(out, 10 * density, 10 * density, Path.Direction.CW)
        canvas.clipPath(clipPath)
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
        // Trees and cottages of the map on free land, so the recap is the player's town and not a wiring plan.
        if (seed != null) {
            val taken = HashSet<Long>()
            for (n in f.nodes) for (dy in 0 until n.size) for (dx in 0 until n.size) taken += key(n.x + dx, n.y + dy)
            for (c in f.cables) for (i in 1 until c.points.size) {
                val a = c.points[i - 1]; val b = c.points[i]
                val steps = (abs(b.x - a.x) + abs(b.y - a.y)).toInt() * 2 + 1
                for (k in 0..steps) {
                    val q = k / steps.toFloat()
                    taken += key(floor(a.x + (b.x - a.x) * q).toInt(), floor(a.y + (b.y - a.y) * q).toInt())
                }
            }
            for (cy in a.top until a.bottom) for (cx in a.left until a.right) {
                if (isWater(cx, cy) || key(cx, cy) in taken) continue
                val d = Scenery.planned(seed, cx, cy) ?: continue
                val open = f.unlocked.contains(cx, cy)
                val x = cx + 0.5f; val y = cy + 0.5f
                when (d) {
                    Decor.HOUSE -> box(canvas, x, y, 0.4f, u * 0.2f, if (open) 0xFFC9694F.toInt() else 0xFFD9C3B8.toInt(), 0xFFF4EDE0.toInt())
                    else -> {
                        val r0 = u * (if (d == Decor.BUSH) 0.13f else 0.2f)
                        val gx = px(x, y); val gy = py(x, y) - r0 * 0.9f
                        dot.color = 0x22000000; canvas.drawOval(gx - r0 * 0.6f, py(x, y) - r0 * 0.25f, gx + r0 * 1.1f, py(x, y) + r0 * 0.3f, dot)
                        dot.color = if (open) (if (d == Decor.PINE) palette.pineDark else palette.leafDark) else palette.lockedLandB.shade(-0.12f)
                        canvas.drawCircle(gx, gy, r0, dot)
                        dot.color = if (open) (if (d == Decor.PINE) palette.pine else palette.leaf) else palette.lockedLandB.shade(-0.05f)
                        canvas.drawCircle(gx - r0 * 0.18f, gy - r0 * 0.2f, r0 * 0.75f, dot)
                    }
                }
            }
        }
        for (c in f.cables) {
            path.reset()
            c.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(px(p.x, p.y), py(p.x, p.y)) else path.lineTo(px(p.x, p.y), py(p.x, p.y)) }
            val st = CableStyles.of(c.type)
            line.strokeWidth = maxOf(u * st.width * 2.2f, 2.5f * density)
            casing.strokeWidth = line.strokeWidth + 2f * density
            canvas.drawPath(path, casing)
            line.color = st.color
            canvas.drawPath(path, line)
            st.core?.let { line.color = it; line.strokeWidth = maxOf(u * st.coreWidth * 1.4f, 1f * density); canvas.drawPath(path, line) }
        }
        // Packets streaming along every cable in the colour and shape of the service it carries (the server at one
        // of its ends), so the look back shows the living network and not a grey wiring plan (judge panel).
        for (c in f.cables) {
            val svc = c.points.firstNotNullOfOrNull { p ->
                f.nodes.firstOrNull { n -> n.kind == NodeKind.SERVER && p.x >= n.x - 0.01f && p.x <= n.x + n.size + 0.01f && p.y >= n.y - 0.01f && p.y <= n.y + n.size + 0.01f }?.service
            } ?: continue
            var len = 0f
            for (i in 1 until c.points.size) len += abs(c.points[i].x - c.points[i - 1].x) + abs(c.points[i].y - c.points[i - 1].y)
            if (len < 0.5f) continue
            val gap = 1.3f
            val shift = (t * 1.1f) % gap
            var d = shift
            dot.color = ServiceColors.of(svc)
            while (d < len) {
                var rest = d
                for (i in 1 until c.points.size) {
                    val a0 = c.points[i - 1]; val b0 = c.points[i]
                    val seg = abs(b0.x - a0.x) + abs(b0.y - a0.y)
                    if (rest <= seg && seg > 0f) {
                        val q = rest / seg
                        val x = a0.x + (b0.x - a0.x) * q; val y = a0.y + (b0.y - a0.y) * q
                        val rr = maxOf(u * 0.17f, 3f * density)
                        ServiceGlyphs.token(canvas, svc, px(x, y), py(x, y) - rr * 0.4f, rr)
                        break
                    }
                    rest -= seg
                }
                d += gap
            }
        }
        // Buildings back to front.
        for (n in f.nodes.sortedBy { it.x + it.y + it.size }) {
            val cx = n.x + n.size / 2f; val cy = n.y + n.size / 2f
            when (n.kind) {
                NodeKind.SERVER -> {
                    val col = ServiceColors.of(n.service!!)
                    val h = u * (0.55f + 0.25f * n.level) * (if (n.size > 1) 1.4f else 1f)
                    box(canvas, cx, cy, 0.78f * n.size, h, col.shade(0.15f), 0xFFE9ECEF.toInt())
                    ServiceGlyphs.sign(canvas, n.service!!, px(cx, cy), py(cx, cy, h), u * 0.3f * n.size, col.shade(-0.2f))
                }
                NodeKind.CLIENT -> {
                    val h = u * 0.22f
                    val lost = k == frames.size - 1 && failed != null && failed.x == n.x && failed.y == n.y
                    if (lost) {
                        // The device that lost the game, in a red halo.
                        val beat = 0.5f + 0.5f * kotlin.math.sin(t * 6f)
                        dot.color = ((0x55 + (0x55 * beat).toInt()) shl 24) or 0xD7263D
                        val rr = u * (0.75f + 0.15f * beat)
                        canvas.drawOval(px(cx, cy) - rr, py(cx, cy) - rr / 2f, px(cx, cy) + rr, py(cx, cy) + rr / 2f, dot)
                    }
                    box(canvas, cx, cy, 0.5f, h, 0xFFFAFAF7.toInt(), 0xFFE3E6E1.toInt())
                    val d = n.device
                    val s = maxOf(u * 0.34f, 5f * density)
                    if (d != null) icons.device(canvas, d, px(cx, cy), py(cx, cy, h) - s * 1.05f, s)
                    else {
                        dot.color = 0xFF3A4350.toInt()
                        canvas.drawCircle(px(cx, cy), py(cx, cy, h) - u * 0.02f, maxOf(u * 0.13f, 1.2f * density), dot)
                    }
                    if (lost) {
                        casing.strokeWidth = 2.5f * density
                        casing.color = 0xFFD7263D.toInt()
                        canvas.drawCircle(px(cx, cy), py(cx, cy, h) - s * 1.05f, s * 1.7f, casing)
                        casing.color = 0xFFFFFFFF.toInt()
                    }
                }
                else -> box(canvas, cx, cy, 0.42f, u * 0.2f, 0xFFF5F7F9.toInt(), 0xFFD9DEE3.toInt())
            }
        }
        canvas.restore()
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
    private val clipPath = Path()

    /** The cells around everything built on [f] (one cell of margin, at least 7 × 5), inside [a]. */
    private fun focusArea(f: GrowthRecorder.Frame, a: CellRect): CellRect {
        if (f.nodes.isEmpty()) return a
        var l = f.nodes.minOf { it.x }; var t = f.nodes.minOf { it.y }
        var r = f.nodes.maxOf { it.x + it.size }; var b = f.nodes.maxOf { it.y + it.size }
        for (c in f.cables) for (p in c.points) {
            l = minOf(l, floor(p.x).toInt()); t = minOf(t, floor(p.y).toInt())
            r = maxOf(r, floor(p.x).toInt() + 1); b = maxOf(b, floor(p.y).toInt() + 1)
        }
        l -= 1; t -= 1; r += 1; b += 1
        while (r - l < 7) { l--; r++ }
        while (b - t < 5) { t--; b++ }
        return CellRect(maxOf(a.left, l), maxOf(a.top, t), minOf(a.right, r), minOf(a.bottom, b))
    }
    private val icons = DeviceIcons()

    private fun key(x: Int, y: Int) = (x.toLong() shl 32) xor (y.toLong() and 0xFFFFFFFFL)
    private val tile = Paint().apply { style = Paint.Style.FILL }

    private fun quad(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float) {
        path.reset()
        path.moveTo(ax, ay); path.lineTo(bx, by); path.lineTo(cx, cy); path.lineTo(dx, dy); path.close()
    }

    /** An iso box [s] cells wide and [h] px high centred on cell point ([cx], [cy]). */
    private fun box(canvas: Canvas, cx: Float, cy: Float, s: Float, h: Float, top: Int, side: Int) {
        val x0 = cx - s / 2; val y0 = cy - s / 2; val x1 = cx + s / 2; val y1 = cy + s / 2
        quad(px(x0, y1), py(x0, y1), px(x1, y1), py(x1, y1), px(x1, y1), py(x1, y1, h), px(x0, y1), py(x0, y1, h))
        dot.color = Cosmetic.blend(side.shade(-0.1f), 0xFFFFD08A.toInt(), 0.1f); canvas.drawPath(path, dot)
        quad(px(x1, y0), py(x1, y0), px(x1, y1), py(x1, y1), px(x1, y1), py(x1, y1, h), px(x1, y0), py(x1, y0, h))
        dot.color = Cosmetic.blend(side.shade(-0.26f), 0xFF34467A.toInt(), 0.14f); canvas.drawPath(path, dot)
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
