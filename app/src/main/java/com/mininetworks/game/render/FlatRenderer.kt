package com.mininetworks.game.render

import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.mininetworks.game.game.CellRect
import com.mininetworks.game.game.Incident
import com.mininetworks.game.game.IncidentKind
import com.mininetworks.game.game.Incidents
import com.mininetworks.game.game.Node
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
    override var density = 1f
    /** A cell at least [READABLE_CELL_DP] wide in the automatic framing. */
    override val readableScale get() = READABLE_CELL_DP * density
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
    private val cutDash = DashCache()
    private val airDash = DashCache()
    private val pos = FloatArray(2)
    private val gridRect = RectF()
    private val openRect = RectF()
    private val radioScratch = ArrayList<Node>()

    /** Cell centers of the single river of the map with water signature [riverMap], top to bottom; empty if none. */
    private var riverMap = 0L
    private var riverReady = false
    private val river = ArrayList<Vec2>()

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
        val grid = screenRect(world.bounds, gridRect)
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
                cutP.pathEffect = cutDash.get(cell * 0.12f, 0.7f, 0f)
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
                labelP.textSize = maxOf(cell * 0.36f, LABEL_MIN_DP * density)
                labelP.color = if (d.blocked) alarm else ink
                val ly = s.y - cell * 0.7f - if (d.detail != null) labelP.textSize * 1.15f else 0f
                canvas.drawText(it, s.x, ly, labelP)
                d.detail?.let { detail ->
                    labelP.color = if (d.detailWarning) alarm else ink
                    canvas.drawText(detail, s.x, ly + labelP.textSize * 1.15f, labelP)
                }
            }
        }

        val packets = world.packets
        for (k in packets.indices) {
            val p = packets[k]
            world.packetPosition(p, pos)
            val sx = camera.toScreenX(pos[0]); val sy = camera.toScreenY(pos[1])
            val r = cell * (0.09f + 0.03f * p.size)
            if (p.isResponse) {
                // Responses: smaller and outlined in the service color.
                fillP.color = land; Shapes.draw(canvas, p.service.shape, sx, sy, r * 0.8f, fillP)
                strokeP.color = ServiceColors.of(p.service); strokeP.strokeWidth = cell * 0.045f
                Shapes.draw(canvas, p.service.shape, sx, sy, r * 0.8f, strokeP)
            } else {
                fillP.color = ServiceColors.of(p.service)
                Shapes.draw(canvas, p.service.shape, sx, sy, r, fillP)
                strokeP.color = land; strokeP.strokeWidth = cell * 0.035f
                Shapes.draw(canvas, p.service.shape, sx, sy, r, strokeP)
            }
        }

        for (i in world.incidents) if (i.kind == IncidentKind.EXCAVATOR) {
            val spot = toScreen(i.spot)
            val size = cell * 0.3f
            val dig = if (i.struck) 0.75f + 0.25f * kotlin.math.sin(time * 3f) else 0f
            icons.excavator(canvas, spot.x - size * 1.25f, spot.y - size * 0.65f, size, dig)
        }

        for (n in world.nodes) {
            val nx = camera.toScreenX(n.center.x); val ny = camera.toScreenY(n.center.y)
            val dark = world.isDark(n)
            val warning = world.incidents.any { it.node === n && !it.struck }
            when (n.kind) {
                NodeKind.ROUTER -> icons.router(canvas, nx, ny, cell * 0.3f, time, warning, dark)
                NodeKind.ACCESS_POINT -> if (dark) {
                    icons.accessPoint(canvas, nx, ny, cell * 0.28f, RadioStyles.color(n), time, dark = true)
                } else {
                    icons.accessPoint(canvas, nx, ny, cell * 0.28f, RadioStyles.color(n), time)
                    val interfering = world.interferers(n).isNotEmpty()
                    val bx = nx + cell * 0.36f; val by = ny - cell * 0.3f; val r = maxOf(cell * 0.15f, CHANNEL_BADGE_MIN_DP * density)
                    fillP.color = land; canvas.drawCircle(bx, by, r, fillP)
                    strokeP.color = if (interfering) alarm else RadioStyles.color(n); strokeP.strokeWidth = cell * 0.035f
                    canvas.drawCircle(bx, by, r, strokeP)
                    labelP.color = RadioStyles.color(n); labelP.textSize = r * (if (n.channel >= 10) 1.0f else 1.2f)
                    canvas.drawText(n.channel.toString(), bx, by + labelP.textSize * 0.36f, labelP)
                }
                NodeKind.CELL_TOWER -> icons.cellTower(canvas, nx, ny, cell * 0.34f, time)
                NodeKind.SERVER -> if (n.isDataCenter) {
                    icons.dataCenter(
                        canvas, n.service!!, world.serverBusy(n), camera.toScreenX(n.footprintCenter.x),
                        camera.toScreenY(n.footprintCenter.y), cell * 0.85f, time,
                    )
                } else {
                    icons.server(canvas, n.service!!, n.level, world.serverBusy(n), nx, ny, cell * 0.34f, time)
                }
                NodeKind.CLIENT -> {
                    val icon = maxOf(cell * 0.3f, ICON_MIN_DP * density)
                    icons.device(canvas, n.device!!, nx, ny, icon)
                    val r = maxOf(cell * 0.08f, REQUEST_MIN_DP * density)
                    val qx = nx + maxOf(cell * 0.52f, icon * 1.6f)
                    val qy = ny - cell * 0.12f
                    for (i in 0 until minOf(n.pending.size, 8)) {
                        val svc = n.pending[i]
                        val px = qx + (i % 4) * r * 2.75f
                        val py = qy + (i / 4) * r * 3f
                        fillP.color = ServiceColors.of(svc)
                        Shapes.draw(canvas, svc.shape, px, py, r, fillP)
                        if (ProblemBadges.shows(world.routeProblem(n, svc))) {
                            // Stuck: the route is too slow for this real-time service, or no link is wide enough.
                            strokeP.color = alarm; strokeP.strokeWidth = r * 0.38f
                            Shapes.draw(canvas, svc.shape, px, py, r * 1.4f, strokeP)
                        }
                    }
                    ProblemBadges.of(world, n)?.let { ProblemBadges.draw(canvas, it, nx - icon * 1.25f, ny - icon * 1.1f, r * 1.9f) }
                    if (n.overload > 0f) {
                        arcRect.set(nx - cell * 0.48f, ny - cell * 0.48f, nx + cell * 0.48f, ny + cell * 0.48f)
                        strokeP.color = alarm; strokeP.strokeWidth = maxOf(cell * 0.07f, RING_MIN_DP * density)
                        canvas.drawArc(arcRect, -90f, 360f * n.overload, false, strokeP)
                    }
                }
            }
        }

        for (n in world.nodes) {
            val dark = world.isDark(n)
            if (!dark && world.incidents.none { it.node === n }) continue
            val nx = camera.toScreenX(n.center.x); val ny = camera.toScreenY(n.center.y)
            val bx = nx - cell * 0.34f; val by = ny - cell * 0.36f; val r = cell * 0.14f
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
        val radios = radioScratch
        radios.clear()
        for (n in world.nodes) if (n.radius > 0f && !world.isDark(n)) radios += n
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
        airP.pathEffect = airDash.get(dash, 0.8f, -time * dash * 4f)
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
        if (singleRiver(world)) {
            drawRiver(canvas)
            return
        }
        fillP.color = waterColor
        val r = cell * 0.3f
        for (y in 0 until world.rows) for (x in 0 until world.cols) {
            if (!world.water[y][x]) continue
            canvas.drawRoundRect(
                camera.toScreenX(x - 0.04f), camera.toScreenY(y - 0.04f), camera.toScreenX(x + 1.04f), camera.toScreenY(y + 1.04f),
                r, r, fillP,
            )
        }
    }

    /**
     * True if no row holds more than one water cell; then [river] holds the river's cell centers. Worked out again
     * only when the water changes (checked with a cheap signature every frame).
     */
    private fun singleRiver(world: World): Boolean {
        var h = System.identityHashCode(world).toLong()
        var single = true
        for (y in 0 until world.rows) {
            var count = 0
            for (x in 0 until world.cols) if (world.water[y][x]) {
                count++
                h = (h xor (y * 4096L + x)) * 0x100000001B3L
            }
            if (count > 1) single = false
        }
        if (!single) return false
        if (riverReady && h == riverMap) return true
        riverReady = true
        riverMap = h
        river.clear()
        for (y in 0 until world.rows) {
            val x = (0 until world.cols).firstOrNull { world.water[y][it] } ?: continue
            river += Vec2(x + 0.5f, y + 0.5f)
        }
        if (river.isNotEmpty()) {
            river.add(0, Vec2(river.first().x, -0.6f))
            river += Vec2(river.last().x, world.rows + 0.6f)
        }
        return true
    }

    /** Mountains as two-tone peaks, downtown towers as grey blocks, flat like the rest of the overview. */
    private fun drawRelief(canvas: Canvas, world: World) {
        for (y in 0 until world.rows) for (x in 0 until world.cols) {
            when (world.terrainAt(x, y)) {
                Terrain.MOUNTAIN -> {
                    val lx = camera.toScreenX(x + 0.08f); val ly = camera.toScreenY(y + 0.9f)
                    val tx = camera.toScreenX(x + 0.5f); val ty = camera.toScreenY(y + 0.12f)
                    val rx = camera.toScreenX(x + 0.92f); val ry = camera.toScreenY(y + 0.9f)
                    path.reset(); path.moveTo(lx, ly); path.lineTo(tx, ty); path.lineTo(rx, ry); path.close()
                    fillP.color = MOUNTAIN; canvas.drawPath(path, fillP)
                    path.reset(); path.moveTo(tx, ty); path.lineTo(rx, ry); path.lineTo(tx, ry); path.close()
                    fillP.color = MOUNTAIN_SHADE; canvas.drawPath(path, fillP)
                }
                Terrain.HIGH_RISE -> {
                    fillP.color = TOWER
                    canvas.drawRoundRect(
                        camera.toScreenX(x + 0.16f), camera.toScreenY(y + 0.16f), camera.toScreenX(x + 0.84f), camera.toScreenY(y + 0.84f),
                        cell * 0.08f, cell * 0.08f, fillP,
                    )
                }
                else -> Unit
            }
        }
    }

    /** The [river] as one smooth band. */
    private fun drawRiver(canvas: Canvas) {
        val pts = river
        if (pts.isEmpty()) return
        path.reset()
        path.moveTo(camera.toScreenX(pts[0].x), camera.toScreenY(pts[0].y))
        for (i in 1 until pts.size - 1) {
            val mx = (pts[i].x + pts[i + 1].x) / 2; val my = (pts[i].y + pts[i + 1].y) / 2
            path.quadTo(camera.toScreenX(pts[i].x), camera.toScreenY(pts[i].y), camera.toScreenX(mx), camera.toScreenY(my))
        }
        path.lineTo(camera.toScreenX(pts.last().x), camera.toScreenY(pts.last().y))
        strokeP.color = waterColor; strokeP.strokeWidth = cell * 1.0f
        canvas.drawPath(path, strokeP)
    }

    /** Veils everything outside the unlocked block and outlines the block. */
    private fun drawLockedArea(canvas: Canvas, world: World, grid: RectF) {
        if (world.unlocked == world.bounds) return
        val open = screenRect(world.unlocked, openRect)
        fillP.color = lockedVeil
        canvas.drawRect(grid.left, grid.top, grid.right, open.top, fillP)
        canvas.drawRect(grid.left, open.bottom, grid.right, grid.bottom, fillP)
        canvas.drawRect(grid.left, open.top, open.left, open.bottom, fillP)
        canvas.drawRect(open.right, open.top, grid.right, open.bottom, fillP)
        strokeP.color = edge; strokeP.strokeWidth = cell * 0.04f
        canvas.drawRect(open, strokeP)
    }

    /** [r] on screen, into [out]. */
    private fun screenRect(r: CellRect, out: RectF): RectF {
        out.set(
            camera.toScreenX(r.left.toFloat()), camera.toScreenY(r.top.toFloat()),
            camera.toScreenX(r.right.toFloat()), camera.toScreenY(r.bottom.toFloat()),
        )
        return out
    }

    private fun polyline(pts: List<Vec2>) {
        path.reset()
        for (i in pts.indices) {
            val x = camera.toScreenX(pts[i].x); val y = camera.toScreenY(pts[i].y)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
    }

    private companion object {
        /** Readable sizes on a phone: cell width of the automatic framing, minimum dp of a device icon's half size, a
         *  request's radius, an overload ring and the drag label. */
        const val READABLE_CELL_DP = 26f
        const val ICON_MIN_DP = 7f
        const val REQUEST_MIN_DP = 3.2f
        const val RING_MIN_DP = 2.5f
        const val LABEL_MIN_DP = 13f
        /** Radius of an access point's channel badge never shrinks below this, so the number stays readable. */
        const val CHANNEL_BADGE_MIN_DP = 7f
        const val MOUNTAIN = 0xFFB9B2A4.toInt()
        const val MOUNTAIN_SHADE = 0xFF9E9687.toInt()
        const val TOWER = 0xFFB4BAC2.toInt()
    }
}
