package com.mininetworks.game.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.Vec2
import com.mininetworks.game.game.World
import kotlin.math.sin

/** Style B from docs/style-explorations.html: isometric tiles, extruded buildings, grid-aligned cables. */
class IsoRenderer : Renderer {
    override val name = "Iso"

    private val landA = 0xFFDDE9D6.toInt()
    private val landB = 0xFFD5E3CD.toInt()
    private val waterA = 0xFF8FC3DA.toInt()
    private val waterB = 0xFFA4D0E3.toInt()
    private val alarm = 0xFFD7263D.toInt()

    private var tw = 1f
    private var th = 1f
    private var ox = 0f
    private var oy = 0f
    override val unitPx get() = tw * 0.7f

    private val fillP = fill(0)
    private val strokeP = stroke(0)
    private val path = Path()
    private val icons = DeviceIcons()
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
            polyline(cablePath(c))
            val st = CableStyles.of(c.type)
            strokeP.color = 0xB3FFFFFF.toInt(); strokeP.strokeWidth = tw * (st.width * 0.75f + 0.08f); canvas.drawPath(path, strokeP)
            strokeP.color = st.color; strokeP.strokeWidth = tw * st.width * 0.75f; canvas.drawPath(path, strokeP)
            st.core?.let { strokeP.color = it; strokeP.strokeWidth = tw * st.coreWidth * 0.75f; canvas.drawPath(path, strokeP) }
        }
        drag?.let { d ->
            val end = d.layout.end
            polyline(d.layout.waypoints)
            val st = CableStyles.of(d.type)
            strokeP.color = if (d.error == null) st.color and 0x99FFFFFF.toInt() else alarm
            strokeP.strokeWidth = tw * maxOf(st.width, 0.12f) * 0.75f; canvas.drawPath(path, strokeP)
            labelP.textSize = tw * 0.28f; labelP.color = if (d.error == null) 0xFF2F3A34.toInt() else alarm
            canvas.drawText(d.error ?: "${d.type.label} · ${d.cost}", sx(end.x, end.y), sy(end.x, end.y) - th * 1.6f, labelP)
        }

        // Painter's algorithm: everything with height is drawn back-to-front by x + y.
        val items = ArrayList<Pair<Float, () -> Unit>>()
        for (n in world.nodes) {
            val c = n.footprintCenter
            items += (c.x + c.y) to { drawNode(canvas, n, time, world.serverBusy(n)) }
        }
        for (p in world.packets) {
            val pos = packetPosition(world, p)
            items += (pos.x + pos.y + 0.01f) to {
                oval.set(sx(pos.x, pos.y) - tw * 0.07f, sy(pos.x, pos.y) - th * 0.07f, sx(pos.x, pos.y) + tw * 0.07f, sy(pos.x, pos.y) + th * 0.07f)
                fillP.color = 0x2E000000; canvas.drawOval(oval, fillP)
                val r = tw * (0.05f + 0.02f * p.size)
                val px = sx(pos.x, pos.y); val py = sy(pos.x, pos.y, 0.35f)
                if (p.isResponse) {
                    // Responses: smaller, white with an outline in the service color.
                    fillP.color = 0xFFFFFFFF.toInt(); Shapes.draw(canvas, p.service.shape, px, py, r * 0.8f, fillP)
                    strokeP.color = ServiceColors.of(p.service); strokeP.strokeWidth = tw * 0.025f
                    Shapes.draw(canvas, p.service.shape, px, py, r * 0.8f, strokeP)
                } else {
                    fillP.color = ServiceColors.of(p.service)
                    Shapes.draw(canvas, p.service.shape, px, py, r, fillP)
                }
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

    private fun drawNode(canvas: Canvas, n: Node, time: Float, busy: Boolean) {
        val x = n.center.x; val y = n.center.y
        when (n.kind) {
            NodeKind.SERVER -> if (n.isDataCenter) drawDataCenter(canvas, n, time, busy) else {
                // One stacked hardware unit per server level: bigger servers literally tower over the town.
                val service = n.service!!
                val col = ServiceColors.of(service)
                val unit = 0.72f
                for (lv in 0 until n.level) {
                    box(canvas, x, y, 0.78f, unit - 0.06f, col.shade(0.15f), 0xFFE9ECEF.toInt(), z0 = lv * unit)
                    for (i in 0 until 2) {
                        val on = sin(time * 3f + i * 1.7f + lv + x) > 0f
                        fillP.color = when {
                            busy -> 0xFFD7263D.toInt()
                            on -> col
                            else -> 0xFF9AA3AD.toInt()
                        }
                        val lx = sx(x + 0.39f, y - 0.2f); val ly = sy(x + 0.39f, y - 0.2f, lv * unit + 0.2f + i * 0.25f)
                        canvas.drawRect(lx - tw * 0.04f, ly - th * 0.08f, lx + tw * 0.06f, ly + th * 0.04f, fillP)
                    }
                }
                val top = n.level * unit - 0.06f
                fillP.color = 0xFFFFFFFF.toInt()
                Shapes.draw(canvas, service.shape, sx(x, y), sy(x, y, top), tw * 0.1f, fillP)
            }
            NodeKind.CLIENT -> {
                val d = n.device!!
                box(canvas, x, y, 0.5f, 0.2f, 0xFFFAFAF7.toInt(), 0xFFE3E6E1.toInt())
                icons.device(canvas, d, sx(x, y), sy(x, y, 0.2f) - tw * 0.2f, tw * 0.2f)
                n.pending.take(8).forEachIndexed { i, svc ->
                    fillP.color = ServiceColors.of(svc)
                    Shapes.draw(canvas, svc.shape, sx(x, y) + tw * (0.35f + (i % 4) * 0.13f), sy(x, y, 1.1f) + (i / 4) * th * 0.3f, tw * 0.05f, fillP)
                }
            }
            NodeKind.ROUTER -> {
                box(canvas, x, y, 0.4f, 0.15f, 0xFFF5F7F9.toInt(), 0xFFD9DEE3.toInt())
                icons.router(canvas, sx(x, y), sy(x, y, 0.15f) - tw * 0.08f, tw * 0.17f, time)
            }
        }
    }

    /** Tier 4: a wide, low hall over the 2×2 footprint with rack LEDs on both visible walls and cooling on the roof. */
    private fun drawDataCenter(canvas: Canvas, n: Node, time: Float, busy: Boolean) {
        val service = n.service!!
        val col = ServiceColors.of(service)
        val c = n.footprintCenter
        val base = 0.12f
        box(canvas, c.x, c.y, 1.86f, base, 0xFFCBD2D9.toInt(), 0xFFCBD2D9.toInt())
        val s = 1.62f
        val h = 1.05f
        box(canvas, c.x, c.y, s, h, col.shade(0.15f), 0xFFE9ECEF.toInt(), z0 = base)
        val front = c.y + s / 2; val right = c.x + s / 2
        val first = -s / 2 + 0.16f
        for (i in 0 until 5) for (row in 0 until 3) {
            val u = first + i * 0.29f
            val z = base + 0.22f + row * 0.26f
            fillP.color = when {
                busy -> alarm
                sin(time * 3f + i * 1.3f + row * 2.1f + n.id) > 0f -> col
                else -> 0xFF9AA3AD.toInt()
            }
            faceY(front, c.x + u, c.x + u + 0.17f, z, z + 0.1f); canvas.drawPath(path, fillP)
            faceX(right, c.y + u, c.y + u + 0.17f, z, z + 0.1f); canvas.drawPath(path, fillP)
        }
        val roof = base + h
        box(canvas, c.x - 0.38f, c.y - 0.38f, 0.42f, 0.16f, 0xFF5B6674.toInt(), 0xFFB9C2CC.toInt(), z0 = roof)
        box(canvas, c.x + 0.12f, c.y - 0.38f, 0.42f, 0.16f, 0xFF5B6674.toInt(), 0xFFB9C2CC.toInt(), z0 = roof)
        val bx = sx(c.x + 0.2f, c.y + 0.25f); val by = sy(c.x + 0.2f, c.y + 0.25f, roof)
        oval.set(bx - tw * 0.2f, by - th * 0.2f, bx + tw * 0.2f, by + th * 0.2f)
        fillP.color = 0xFFFFFFFF.toInt(); canvas.drawOval(oval, fillP)
        fillP.color = col
        Shapes.draw(canvas, service.shape, bx, by - th * 0.04f, tw * 0.08f, fillP)
    }

    /** Wall patch on the plane y = [y], from x [xa] to [xb] and height [za] to [zb]. */
    private fun faceY(y: Float, xa: Float, xb: Float, za: Float, zb: Float) {
        path.reset()
        path.moveTo(sx(xa, y), sy(xa, y, za)); path.lineTo(sx(xb, y), sy(xb, y, za))
        path.lineTo(sx(xb, y), sy(xb, y, zb)); path.lineTo(sx(xa, y), sy(xa, y, zb)); path.close()
    }

    /** Wall patch on the plane x = [x], from y [ya] to [yb] and height [za] to [zb]. */
    private fun faceX(x: Float, ya: Float, yb: Float, za: Float, zb: Float) {
        path.reset()
        path.moveTo(sx(x, ya), sy(x, ya, za)); path.lineTo(sx(x, yb), sy(x, yb, za))
        path.lineTo(sx(x, yb), sy(x, yb, zb)); path.lineTo(sx(x, ya), sy(x, ya, zb)); path.close()
    }

    private fun box(canvas: Canvas, cx: Float, cy: Float, s: Float, h: Float, top: Int, side: Int, z0: Float = 0f) {
        val x0 = cx - s / 2; val y0 = cy - s / 2; val x1 = cx + s / 2; val y1 = cy + s / 2
        val z1 = z0 + h
        path.reset()
        path.moveTo(sx(x0, y1), sy(x0, y1, z0)); path.lineTo(sx(x1, y1), sy(x1, y1, z0))
        path.lineTo(sx(x1, y1), sy(x1, y1, z1)); path.lineTo(sx(x0, y1), sy(x0, y1, z1)); path.close()
        fillP.color = side.shade(-0.12f); canvas.drawPath(path, fillP)
        path.reset()
        path.moveTo(sx(x1, y0), sy(x1, y0, z0)); path.lineTo(sx(x1, y1), sy(x1, y1, z0))
        path.lineTo(sx(x1, y1), sy(x1, y1, z1)); path.lineTo(sx(x1, y0), sy(x1, y0, z1)); path.close()
        fillP.color = side.shade(-0.25f); canvas.drawPath(path, fillP)
        quad(x0, y0, s, s, z1)
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
