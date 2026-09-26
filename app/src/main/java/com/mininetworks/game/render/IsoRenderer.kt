package com.mininetworks.game.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.mininetworks.game.game.Geometry
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.Vec2
import com.mininetworks.game.game.World
import kotlin.math.sin

/** Style B from design/style-explorations.html: isometric tiles, extruded buildings, grid-aligned cables. */
class IsoRenderer : Renderer {
    override val name = "Iso"

    private val landA = 0xFFDDE9D6.toInt()
    private val landB = 0xFFD5E3CD.toInt()
    private val waterA = 0xFF8FC3DA.toInt()
    private val waterB = 0xFFA4D0E3.toInt()
    private val road = 0xFF5B6570.toInt()
    private val alarm = 0xFFD7263D.toInt()

    private var tw = 1f
    private var th = 1f
    private var ox = 0f
    private var oy = 0f
    override val unitPx get() = tw * 0.7f

    private val fillP = fill(0)
    private val strokeP = stroke(0)
    private val path = Path()
    private val oval = RectF()
    private val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }

    override fun layout(width: Int, height: Int, world: World) {
        val span = (world.cols + world.rows).toFloat()
        tw = minOf(width / (span / 2f + 0.6f), height / (span / 4f + 2.4f))
        th = tw / 2f
        ox = width / 2f - (world.cols - world.rows) * tw / 4f
        oy = (height - span * th / 2f) / 2f + th * 1.4f
    }

    private fun sx(x: Float, y: Float) = ox + (x - y) * tw / 2f
    private fun sy(x: Float, y: Float, z: Float = 0f) = oy + (x + y) * th / 2f - z * th

    override fun toScreen(p: Vec2) = Vec2(sx(p.x, p.y), sy(p.x, p.y))

    override fun toWorld(sx: Float, sy: Float): Vec2 {
        val a = (sx - ox) / (tw / 2f) // x - y
        val b = (sy - oy) / (th / 2f) // x + y
        return Vec2((a + b) / 2f, (b - a) / 2f)
    }

    override fun cablePath(a: Node, b: Node) = Geometry.lPath(a.center, b.center)

    override fun draw(canvas: Canvas, world: World, drag: DragPreview?, time: Float) {
        canvas.drawColor(0xFFEEF3EA.toInt())
        for (y in 0 until world.rows) for (x in 0 until world.cols) {
            quad(x.toFloat(), y.toFloat(), 1f, 1f, 0f)
            fillP.color = if (world.water[y][x]) (if ((x + y) % 2 == 0) waterA else waterB) else (if ((x + y) % 2 == 0) landA else landB)
            canvas.drawPath(path, fillP)
        }
        // Board edges give the "toy on a table" look.
        val w = world.cols.toFloat(); val h = world.rows.toFloat()
        side(0f, h, w, h); fillP.color = 0xFFB9C9AF.toInt(); canvas.drawPath(path, fillP)
        side(w, 0f, w, h); fillP.color = 0xFFA7BA9C.toInt(); canvas.drawPath(path, fillP)

        for (c in world.cables) {
            polyline(cablePath(c.a, c.b))
            strokeP.color = 0xB3FFFFFF.toInt(); strokeP.strokeWidth = tw * 0.2f; canvas.drawPath(path, strokeP)
            strokeP.color = if (world.cableLoad(c) >= c.capacity) road.shade(0.3f) else road
            strokeP.strokeWidth = tw * 0.11f; canvas.drawPath(path, strokeP)
        }
        drag?.let { d ->
            val end = d.target?.center ?: d.end
            polyline(Geometry.lPath(d.from.center, end))
            strokeP.color = if (d.error == null) road.shade(0.4f) else alarm
            strokeP.strokeWidth = tw * 0.11f; canvas.drawPath(path, strokeP)
            labelP.textSize = tw * 0.28f; labelP.color = strokeP.color
            canvas.drawText(d.error ?: "${d.cost} Kabel", sx(end.x, end.y), sy(end.x, end.y) - th * 1.4f, labelP)
        }

        // Painter's algorithm: everything with height is drawn back-to-front by x + y.
        val items = ArrayList<Pair<Float, () -> Unit>>()
        for (n in world.nodes) items += (n.center.x + n.center.y) to { drawNode(canvas, n, time) }
        for (p in world.packets) {
            val pos = packetPosition(p)
            items += (pos.x + pos.y + 0.01f) to {
                oval.set(sx(pos.x, pos.y) - tw * 0.07f, sy(pos.x, pos.y) - th * 0.07f, sx(pos.x, pos.y) + tw * 0.07f, sy(pos.x, pos.y) + th * 0.07f)
                fillP.color = 0x2E000000; canvas.drawOval(oval, fillP)
                fillP.color = TypeColors.iso(p.type)
                Shapes.draw(canvas, p.type.shape, sx(pos.x, pos.y), sy(pos.x, pos.y, 0.35f), tw * 0.075f, fillP)
            }
        }
        items.sortBy { it.first }
        items.forEach { it.second() }

        for (n in world.nodes) {
            if (n.kind != NodeKind.CLIENT || n.overload <= 0f) continue
            val cx = sx(n.center.x, n.center.y); val cy = sy(n.center.x, n.center.y)
            oval.set(cx - tw * 0.55f, cy - th * 0.55f, cx + tw * 0.55f, cy + th * 0.55f)
            strokeP.color = alarm; strokeP.strokeWidth = tw * 0.05f
            canvas.drawArc(oval, -90f, 360f * n.overload, false, strokeP)
        }
    }

    private fun drawNode(canvas: Canvas, n: Node, time: Float) {
        val x = n.center.x; val y = n.center.y
        when (n.kind) {
            NodeKind.SERVER -> {
                val col = TypeColors.iso(n.type!!)
                box(canvas, x, y, 0.78f, 2.0f, col.shade(0.15f), 0xFFE9ECEF.toInt())
                for (i in 0 until 4) {
                    val on = sin(time * 3f + i * 1.7f + x) > 0f
                    fillP.color = if (on) col else 0xFF9AA3AD.toInt()
                    val lx = sx(x + 0.39f, y - 0.25f); val ly = sy(x + 0.39f, y - 0.25f, 0.45f + i * 0.38f)
                    canvas.drawRect(lx - tw * 0.04f, ly - th * 0.08f, lx + tw * 0.06f, ly + th * 0.04f, fillP)
                }
            }
            NodeKind.CLIENT -> {
                val t = n.type!!
                box(canvas, x, y, 0.56f, 0.55f, TypeColors.iso(t), 0xFFFAFAF7.toInt())
                fillP.color = 0xFFFFFFFF.toInt()
                Shapes.draw(canvas, t.shape, sx(x, y), sy(x, y, 0.55f), tw * 0.07f, fillP)
                fillP.color = TypeColors.iso(t)
                for (i in 0 until minOf(n.pending, 8)) {
                    Shapes.draw(canvas, t.shape, sx(x, y) + tw * (0.35f + (i % 4) * 0.13f), sy(x, y, 1.1f) + (i / 4) * th * 0.3f, tw * 0.05f, fillP)
                }
            }
            NodeKind.ROUTER -> {
                box(canvas, x, y, 0.32f, 0.35f, 0xFFF5F7F9.toInt(), 0xFFD9DEE3.toInt())
                strokeP.color = 0xFF6B7580.toInt(); strokeP.strokeWidth = tw * 0.025f
                canvas.drawLine(sx(x, y), sy(x, y, 0.35f), sx(x, y), sy(x, y, 1.0f), strokeP)
                fillP.color = if (sin(time * 5f) > 0f) 0xFF43D17A.toInt() else 0xFF2E6B45.toInt()
                canvas.drawCircle(sx(x, y), sy(x, y, 1.0f), tw * 0.04f, fillP)
            }
        }
    }

    private fun box(canvas: Canvas, cx: Float, cy: Float, s: Float, h: Float, top: Int, side: Int) {
        val x0 = cx - s / 2; val y0 = cy - s / 2; val x1 = cx + s / 2; val y1 = cy + s / 2
        path.reset()
        path.moveTo(sx(x0, y1), sy(x0, y1)); path.lineTo(sx(x1, y1), sy(x1, y1))
        path.lineTo(sx(x1, y1), sy(x1, y1, h)); path.lineTo(sx(x0, y1), sy(x0, y1, h)); path.close()
        fillP.color = side.shade(-0.12f); canvas.drawPath(path, fillP)
        path.reset()
        path.moveTo(sx(x1, y0), sy(x1, y0)); path.lineTo(sx(x1, y1), sy(x1, y1))
        path.lineTo(sx(x1, y1), sy(x1, y1, h)); path.lineTo(sx(x1, y0), sy(x1, y0, h)); path.close()
        fillP.color = side.shade(-0.25f); canvas.drawPath(path, fillP)
        quad(x0, y0, s, s, h)
        fillP.color = top; canvas.drawPath(path, fillP)
    }

    private fun quad(x: Float, y: Float, w: Float, d: Float, z: Float) {
        path.reset()
        path.moveTo(sx(x, y), sy(x, y, z)); path.lineTo(sx(x + w, y), sy(x + w, y, z))
        path.lineTo(sx(x + w, y + d), sy(x + w, y + d, z)); path.lineTo(sx(x, y + d), sy(x, y + d, z)); path.close()
    }

    /** Vertical board edge from (x0,y0) to (x1,y1), 0.5 units deep. */
    private fun side(x0: Float, y0: Float, x1: Float, y1: Float) {
        path.reset()
        path.moveTo(sx(x0, y0), sy(x0, y0)); path.lineTo(sx(x1, y1), sy(x1, y1))
        path.lineTo(sx(x1, y1), sy(x1, y1, -0.5f)); path.lineTo(sx(x0, y0), sy(x0, y0, -0.5f)); path.close()
    }

    private fun polyline(pts: List<Vec2>) {
        path.reset()
        pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(sx(p.x, p.y), sy(p.x, p.y)) else path.lineTo(sx(p.x, p.y), sy(p.x, p.y)) }
    }
}
