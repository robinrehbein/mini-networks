package com.mininetworks.game.render

import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.mininetworks.game.game.CellRect
import com.mininetworks.game.game.Incident
import com.mininetworks.game.game.IncidentKind
import com.mininetworks.game.game.Incidents
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.Terrain
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
    private val airP = stroke(0)
    private val cutP = stroke(IncidentStyles.CUT)
    private val clip = Path()

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
        drawWater(canvas, world)
        drawRelief(canvas, world)
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
            if (world.isCut(c)) {
                val dash = cell * 0.12f
                cutP.pathEffect = DashPathEffect(floatArrayOf(dash, dash * 0.7f), 0f)
                cutP.strokeWidth = cell * st.width * 0.6f
                canvas.drawPath(path, cutP)
            }
        }

        drawRadioCoverage(canvas, world, time)
        for (i in world.incidents) drawIncidentGround(canvas, i, time)

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

        for (i in world.incidents) if (i.kind == IncidentKind.EXCAVATOR) {
            val spot = toScreen(i.spot)
            val size = cell * 0.3f
            val dig = if (i.struck) 0.75f + 0.25f * kotlin.math.sin(time * 3f) else 0f
            icons.excavator(canvas, spot.x - size * 1.25f, spot.y - size * 0.65f, size, dig)
        }

        for (n in world.nodes) {
            val s = toScreen(n.center)
            val dark = world.isDark(n)
            val warning = world.incidents.any { it.node === n && !it.struck }
            when (n.kind) {
                NodeKind.ROUTER -> icons.router(canvas, s.x, s.y, cell * 0.3f, time, warning, dark)
                NodeKind.ACCESS_POINT -> if (dark) {
                    icons.accessPoint(canvas, s.x, s.y, cell * 0.28f, RadioStyles.color(n), time, dark = true)
                } else {
                    icons.accessPoint(canvas, s.x, s.y, cell * 0.28f, RadioStyles.color(n), time)
                    val interfering = world.interferers(n).isNotEmpty()
                    val bx = s.x + cell * 0.36f; val by = s.y - cell * 0.3f; val r = cell * 0.15f
                    fillP.color = land; canvas.drawCircle(bx, by, r, fillP)
                    strokeP.color = if (interfering) alarm else RadioStyles.color(n); strokeP.strokeWidth = cell * 0.035f
                    canvas.drawCircle(bx, by, r, strokeP)
                    labelP.color = RadioStyles.color(n); labelP.textSize = r * (if (n.channel >= 10) 1.0f else 1.2f)
                    canvas.drawText(n.channel.toString(), bx, by + labelP.textSize * 0.36f, labelP)
                }
                NodeKind.CELL_TOWER -> icons.cellTower(canvas, s.x, s.y, cell * 0.34f, time)
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

        for (n in world.nodes) {
            val dark = world.isDark(n)
            if (!dark && world.incidents.none { it.node === n }) continue
            val s = toScreen(n.center)
            val bx = s.x - cell * 0.34f; val by = s.y - cell * 0.36f; val r = cell * 0.14f
            fillP.color = if (dark) IncidentStyles.EXCAVATOR_DARK else IncidentStyles.WARNING
            canvas.drawCircle(bx, by, r, fillP)
            fillP.color = if (dark) IncidentStyles.WARNING else 0xFFFFFFFF.toInt()
            canvas.drawPath(IncidentStyles.bolt(path, bx, by, r * 0.68f), fillP)
        }

        world.failedNode?.let {
            val s = toScreen(it.center)
            strokeP.color = alarm; strokeP.strokeWidth = cell * 0.05f
            canvas.drawCircle(s.x, s.y, cell * (0.7f + 0.15f * kotlin.math.sin(time * 6f)), strokeP)
        }
    }

    /** Radio circles in the channel color, red where same-channel access points overlap, dashed lines for radio links. */
    private fun drawRadioCoverage(canvas: Canvas, world: World, time: Float) {
        val radios = world.nodes.filter { it.radius > 0f && !world.isDark(it) }
        if (radios.isEmpty()) return
        for (n in radios) {
            val c = toScreen(n.center); val col = RadioStyles.color(n)
            fillP.color = col and 0x00FFFFFF or 0x1F000000; canvas.drawCircle(c.x, c.y, n.radius * cell, fillP)
            strokeP.color = col and 0x00FFFFFF or 0x99000000.toInt(); strokeP.strokeWidth = cell * 0.025f
            canvas.drawCircle(c.x, c.y, n.radius * cell, strokeP)
        }
        for (a in radios) for (b in world.interferers(a)) {
            if (b.id < a.id) continue
            val ca = toScreen(a.center); val cb = toScreen(b.center)
            clip.reset(); clip.addCircle(ca.x, ca.y, a.radius * cell, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            fillP.color = alarm and 0x00FFFFFF or 0x59000000; canvas.drawCircle(cb.x, cb.y, b.radius * cell, fillP)
            canvas.restore()
        }
        for (n in radios) if (world.interferers(n).isNotEmpty()) {
            val c = toScreen(n.center)
            strokeP.color = alarm; strokeP.strokeWidth = cell * 0.03f; canvas.drawCircle(c.x, c.y, n.radius * cell, strokeP)
        }
        val dash = cell * 0.1f
        airP.pathEffect = DashPathEffect(floatArrayOf(dash, dash * 0.8f), -time * dash * 4f)
        airP.strokeWidth = cell * 0.035f
        for (l in world.radioLinks) {
            airP.color = RadioStyles.color(l.radio)
            val a = toScreen(l.radio.center); val b = toScreen(l.device.center)
            canvas.drawLine(a.x, a.y, b.x, b.y, airP)
        }
    }

    /** Pulsing amber ring and countdown arc while announced, a red arc running down once struck, a hole at a cut. */
    private fun drawIncidentGround(canvas: Canvas, i: Incident, time: Float) {
        val c = toScreen(i.spot)
        val r = cell * (if (i.kind == IncidentKind.EXCAVATOR) 0.32f else 0.5f)
        if (i.kind == IncidentKind.EXCAVATOR && i.struck) {
            fillP.color = IncidentStyles.DIRT; canvas.drawCircle(c.x, c.y, cell * 0.2f, fillP)
            fillP.color = IncidentStyles.DIRT.shade(-0.4f); canvas.drawCircle(c.x, c.y, cell * 0.11f, fillP)
        }
        arcRect.set(c.x - r, c.y - r, c.x + r, c.y + r)
        strokeP.strokeWidth = cell * 0.05f
        if (!i.struck) {
            val phase = IncidentStyles.pulse(time * 1.5f)
            strokeP.color = IncidentStyles.WARNING and 0x00FFFFFF or ((1f - phase) * 220).toInt().shl(24)
            canvas.drawCircle(c.x, c.y, r * (0.7f + phase * 0.9f), strokeP)
            strokeP.color = IncidentStyles.WARNING
            canvas.drawArc(arcRect, -90f, 360f * (1f - i.warning / Incidents.WARNING_SECONDS), false, strokeP)
        } else {
            strokeP.color = IncidentStyles.CUT
            canvas.drawArc(arcRect, -90f, 360f * (1f - i.effectProgress), false, strokeP)
        }
    }

    /** One smooth band for a single top-to-bottom river (as in the river town), otherwise rounded water cells. */
    private fun drawWater(canvas: Canvas, world: World) {
        val singleRiver = (0 until world.rows).all { y -> (0 until world.cols).count { world.water[y][it] } <= 1 }
        if (singleRiver) {
            drawRiver(canvas, world)
            return
        }
        fillP.color = waterColor
        val r = cell * 0.3f
        for (y in 0 until world.rows) for (x in 0 until world.cols) {
            if (!world.water[y][x]) continue
            val a = toScreen(Vec2(x - 0.04f, y - 0.04f)); val b = toScreen(Vec2(x + 1.04f, y + 1.04f))
            canvas.drawRoundRect(a.x, a.y, b.x, b.y, r, r, fillP)
        }
    }

    /** Mountains as two-tone peaks, downtown towers as grey blocks, flat like the rest of the overview. */
    private fun drawRelief(canvas: Canvas, world: World) {
        for (y in 0 until world.rows) for (x in 0 until world.cols) {
            when (world.terrainAt(x, y)) {
                Terrain.MOUNTAIN -> {
                    val l = toScreen(Vec2(x + 0.08f, y + 0.9f)); val t = toScreen(Vec2(x + 0.5f, y + 0.12f)); val rr = toScreen(Vec2(x + 0.92f, y + 0.9f))
                    path.reset(); path.moveTo(l.x, l.y); path.lineTo(t.x, t.y); path.lineTo(rr.x, rr.y); path.close()
                    fillP.color = MOUNTAIN; canvas.drawPath(path, fillP)
                    path.reset(); path.moveTo(t.x, t.y); path.lineTo(rr.x, rr.y); path.lineTo(t.x, rr.y); path.close()
                    fillP.color = MOUNTAIN_SHADE; canvas.drawPath(path, fillP)
                }
                Terrain.HIGH_RISE -> {
                    val a = toScreen(Vec2(x + 0.16f, y + 0.16f)); val b = toScreen(Vec2(x + 0.84f, y + 0.84f))
                    fillP.color = TOWER; canvas.drawRoundRect(a.x, a.y, b.x, b.y, cell * 0.08f, cell * 0.08f, fillP)
                }
                else -> Unit
            }
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

    private companion object {
        const val MOUNTAIN = 0xFFB9B2A4.toInt()
        const val MOUNTAIN_SHADE = 0xFF9E9687.toInt()
        const val TOWER = 0xFFB4BAC2.toInt()
    }
}
