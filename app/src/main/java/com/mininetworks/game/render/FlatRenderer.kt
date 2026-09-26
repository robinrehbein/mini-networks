package com.mininetworks.game.render

import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.mininetworks.game.game.CellRect
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.Vec2
import com.mininetworks.game.game.World

/** Style A from docs/style-explorations.html: Mini-Metro-like, flat vector.
 * Cables follow the same grid layout as in the isometric style, with softly rounded corners. */
class FlatRenderer : Renderer {
    override val name = "Flat"

    private val land = 0xFFF3F1EC.toInt()
    private val waterColor = 0xFFC3DCE8.toInt()
    private val ink = 0xFF262B33.toInt()
    private val alarm = 0xFFD7263D.toInt()

    private val backdrop = 0xFFD3CFC5.toInt()
    /** Veil over cells that are not unlocked yet. */
    private val lockedVeil = 0x66C9C4B8
    private val edge = 0x55262B33

    override val camera = Camera()
    private val cell get() = camera.scale
    override val unitPx get() = cell
    private var cornerRadius = 0f

    private val fillP = fill(0)
    private val strokeP = stroke(0)
    /** Cable strokes: rounded corners, purely visual (packets follow the exact grid layout). */
    private val cableP = stroke(0)
    private val path = Path()
    private val icons = DeviceIcons()
    private val arcRect = RectF()
    private val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }

    /** Flat map space is world space: one map unit per cell. */
    override fun toMap(p: Vec2) = p
    override fun fromMap(mx: Float, my: Float) = Vec2(mx, my)

    /** Room for icons above the cells and request queues to the right of them. */
    override fun mapBounds(area: CellRect) =
        MapRect(area.left - 0.3f, area.top - 0.6f, area.right + 0.9f, area.bottom + 0.3f)

    override fun draw(canvas: Canvas, world: World, drag: DragPreview?, time: Float) {
        if (cornerRadius != cell * 0.35f) {
            cornerRadius = cell * 0.35f
            cableP.pathEffect = CornerPathEffect(cornerRadius)
        }
        canvas.drawColor(backdrop)
        val grid = screenRect(world.bounds)
        fillP.color = land; canvas.drawRect(grid, fillP)
        strokeP.color = edge; strokeP.strokeWidth = cell * 0.03f; canvas.drawRect(grid, strokeP)
        canvas.save()
        canvas.clipRect(grid)
        drawRiver(canvas, world)
        canvas.restore()
        drawLockedArea(canvas, world, grid)

        for (c in world.cables) {
            polyline(cablePath(c))
            val st = CableStyles.of(c.type)
            cableP.color = land; cableP.strokeWidth = cell * (st.width + 0.12f); canvas.drawPath(path, cableP)
            cableP.color = st.color; cableP.strokeWidth = cell * st.width; canvas.drawPath(path, cableP)
            st.core?.let { cableP.color = it; cableP.strokeWidth = cell * st.coreWidth; canvas.drawPath(path, cableP) }
            if (world.cableLoad(c) >= c.capacity) {
                cableP.color = alarm and 0x80FFFFFF.toInt(); cableP.strokeWidth = cell * 0.05f; canvas.drawPath(path, cableP)
            }
        }

        drag?.let { d ->
            val end = d.layout.end
            polyline(d.layout.waypoints)
            val st = CableStyles.of(d.type)
            cableP.color = if (d.blocked) alarm else st.color and 0x99FFFFFF.toInt()
            cableP.strokeWidth = cell * maxOf(st.width, 0.12f)
            canvas.drawPath(path, cableP)
            d.label?.let {
                val s = toScreen(end)
                labelP.textSize = cell * 0.36f
                labelP.color = if (d.blocked) alarm else ink
                canvas.drawText(it, s.x, s.y - cell * 0.7f, labelP)
            }
        }

        for (p in world.packets) {
            val s = toScreen(packetPosition(world, p))
            val r = cell * (0.09f + 0.03f * p.size)
            if (p.isResponse) {
                // Responses: smaller and outlined in the service color.
                fillP.color = land; Shapes.draw(canvas, p.service.shape, s.x, s.y, r * 0.8f, fillP)
                strokeP.color = ServiceColors.of(p.service); strokeP.strokeWidth = cell * 0.045f
                Shapes.draw(canvas, p.service.shape, s.x, s.y, r * 0.8f, strokeP)
            } else {
                fillP.color = ServiceColors.of(p.service)
                Shapes.draw(canvas, p.service.shape, s.x, s.y, r, fillP)
                strokeP.color = land; strokeP.strokeWidth = cell * 0.035f
                Shapes.draw(canvas, p.service.shape, s.x, s.y, r, strokeP)
            }
        }

        for (n in world.nodes) {
            val s = toScreen(n.center)
            when (n.kind) {
                NodeKind.ROUTER -> icons.router(canvas, s.x, s.y, cell * 0.3f, time)
                NodeKind.SERVER -> if (n.isDataCenter) {
                    val c = toScreen(n.footprintCenter)
                    icons.dataCenter(canvas, n.service!!, world.serverBusy(n), c.x, c.y, cell * 0.85f, time)
                } else {
                    icons.server(canvas, n.service!!, n.level, world.serverBusy(n), s.x, s.y, cell * 0.34f, time)
                }
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

    /** Veils everything outside the unlocked block and outlines the block. */
    private fun drawLockedArea(canvas: Canvas, world: World, grid: RectF) {
        if (world.unlocked == world.bounds) return
        val open = screenRect(world.unlocked)
        fillP.color = lockedVeil
        canvas.drawRect(grid.left, grid.top, grid.right, open.top, fillP)
        canvas.drawRect(grid.left, open.bottom, grid.right, grid.bottom, fillP)
        canvas.drawRect(grid.left, open.top, open.left, open.bottom, fillP)
        canvas.drawRect(open.right, open.top, grid.right, open.bottom, fillP)
        strokeP.color = edge; strokeP.strokeWidth = cell * 0.04f
        canvas.drawRect(open, strokeP)
    }

    private fun screenRect(r: CellRect): RectF {
        val a = toScreen(Vec2(r.left.toFloat(), r.top.toFloat()))
        val b = toScreen(Vec2(r.right.toFloat(), r.bottom.toFloat()))
        return RectF(a.x, a.y, b.x, b.y)
    }

    private fun polyline(pts: List<Vec2>) {
        path.reset()
        pts.forEachIndexed { i, p -> val s = toScreen(p); if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y) }
    }
}
