package com.mininetworks.game.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.mininetworks.game.game.Geometry
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.Vec2
import com.mininetworks.game.game.World

/** Style A from docs/style-explorations.html: Mini-Metro-like, flat vector, 45° cables. */
class FlatRenderer : Renderer {
    override val name = "Flat"

    private val land = 0xFFF3F1EC.toInt()
    private val waterColor = 0xFFC3DCE8.toInt()
    private val ink = 0xFF262B33.toInt()
    private val alarm = 0xFFD7263D.toInt()

    private var cell = 1f
    private var ox = 0f
    private var oy = 0f
    override val unitPx get() = cell

    private val fillP = fill(0)
    private val strokeP = stroke(0)
    private val path = Path()
    private val icons = DeviceIcons()
    private val arcRect = RectF()
    private val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }

    override fun layout(width: Int, height: Int, world: World) {
        cell = minOf(width / (world.cols + 0.6f), height / (world.rows + 2.2f))
        ox = (width - world.cols * cell) / 2f
        oy = (height - world.rows * cell) / 2f + cell * 0.2f
    }

    override fun toScreen(p: Vec2) = Vec2(ox + p.x * cell, oy + p.y * cell)
    override fun toWorld(sx: Float, sy: Float) = Vec2((sx - ox) / cell, (sy - oy) / cell)

    override fun draw(canvas: Canvas, world: World, drag: DragPreview?, time: Float) {
        canvas.drawColor(land)
        drawRiver(canvas, world)

        for (c in world.cables) {
            polyline(cablePath(c.a, c.b))
            val st = CableStyles.of(c.type)
            strokeP.color = land; strokeP.strokeWidth = cell * (st.width + 0.12f); canvas.drawPath(path, strokeP)
            strokeP.color = st.color; strokeP.strokeWidth = cell * st.width; canvas.drawPath(path, strokeP)
            st.core?.let { strokeP.color = it; strokeP.strokeWidth = cell * st.coreWidth; canvas.drawPath(path, strokeP) }
            if (world.cableLoad(c) >= c.capacity) {
                strokeP.color = alarm and 0x80FFFFFF.toInt(); strokeP.strokeWidth = cell * 0.05f; canvas.drawPath(path, strokeP)
            }
        }

        drag?.let { d ->
            val end = d.target?.center ?: d.end
            polyline(Geometry.octo(d.from.center, end))
            val st = CableStyles.of(d.type)
            strokeP.color = if (d.error == null) st.color and 0x99FFFFFF.toInt() else alarm
            strokeP.strokeWidth = cell * maxOf(st.width, 0.12f)
            canvas.drawPath(path, strokeP)
            val s = toScreen(end)
            labelP.textSize = cell * 0.36f
            labelP.color = if (d.error == null) ink else alarm
            canvas.drawText(d.error ?: "${d.type.label} · ${d.cost}", s.x, s.y - cell * 0.7f, labelP)
        }

        for (p in world.packets) {
            val s = toScreen(packetPosition(p))
            fillP.color = ServiceColors.of(p.service)
            Shapes.draw(canvas, p.service.shape, s.x, s.y, cell * (0.09f + 0.03f * p.size), fillP)
            strokeP.color = land; strokeP.strokeWidth = cell * 0.035f
            Shapes.draw(canvas, p.service.shape, s.x, s.y, cell * (0.09f + 0.03f * p.size), strokeP)
        }

        for (n in world.nodes) {
            val s = toScreen(n.center)
            when (n.kind) {
                NodeKind.ROUTER -> icons.router(canvas, s.x, s.y, cell * 0.3f, time)
                NodeKind.SERVER -> icons.server(canvas, n.service!!, n.level, world.serverBusy(n), s.x, s.y, cell * 0.34f, time)
                NodeKind.CLIENT -> {
                    icons.device(canvas, n.device!!, s.x, s.y, cell * 0.3f)
                    n.pending.take(8).forEachIndexed { i, svc ->
                        val px = s.x + cell * (0.52f + (i % 4) * 0.22f)
                        val py = s.y - cell * 0.12f + (i / 4) * cell * 0.24f
                        fillP.color = ServiceColors.of(svc)
                        Shapes.draw(canvas, svc.shape, px, py, cell * 0.08f, fillP)
                        if (world.routeFor(n, svc) == null && world.bestRoute(n, svc) != null) {
                            // Reachable, but the ping is too high for this real-time service.
                            strokeP.color = alarm; strokeP.strokeWidth = cell * 0.03f
                            Shapes.draw(canvas, svc.shape, px, py, cell * 0.11f, strokeP)
                        }
                    }
                    if (n.overload > 0f) {
                        arcRect.set(s.x - cell * 0.48f, s.y - cell * 0.48f, s.x + cell * 0.48f, s.y + cell * 0.48f)
                        strokeP.color = alarm; strokeP.strokeWidth = cell * 0.07f
                        canvas.drawArc(arcRect, -90f, 360f * n.overload, false, strokeP)
                    }
                }
            }
        }

        world.failedNode?.let {
            val s = toScreen(it.center)
            strokeP.color = alarm; strokeP.strokeWidth = cell * 0.05f
            canvas.drawCircle(s.x, s.y, cell * (0.7f + 0.15f * kotlin.math.sin(time * 6f)), strokeP)
        }
    }

    private fun drawRiver(canvas: Canvas, world: World) {
        val centers = (0 until world.rows).mapNotNull { y ->
            (0 until world.cols).firstOrNull { world.water[y][it] }?.let { Vec2(it + 0.5f, y + 0.5f) }
        }
        if (centers.isEmpty()) return
        val pts = listOf(Vec2(centers.first().x, -0.6f)) + centers + Vec2(centers.last().x, world.rows + 0.6f)
        path.reset()
        val first = toScreen(pts[0])
        path.moveTo(first.x, first.y)
        for (i in 1 until pts.size - 1) {
            val c = toScreen(pts[i])
            val m = toScreen(Vec2((pts[i].x + pts[i + 1].x) / 2, (pts[i].y + pts[i + 1].y) / 2))
            path.quadTo(c.x, c.y, m.x, m.y)
        }
        val last = toScreen(pts.last()); path.lineTo(last.x, last.y)
        strokeP.color = waterColor; strokeP.strokeWidth = cell * 1.0f
        canvas.drawPath(path, strokeP)
    }

    private fun polyline(pts: List<Vec2>) {
        path.reset()
        pts.forEachIndexed { i, p -> val s = toScreen(p); if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y) }
    }
}
