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

/**
 * Style A from docs/style-explorations.html: Mini-Metro-like, flat vector.
 * Cables follow the same grid layout as in the isometric style, with softly rounded corners.
 *
 * The overview turns with the map ([Camera.angle]): the ground (grid, water, towers, veil, cables, radio circles) is
 * drawn in world-aligned coordinates ([gx], [gy]) on a canvas turned around the view centre, which gives exactly the
 * camera's world-to-screen mapping; icons, mountains, packets and labels stay upright at their turned positions.
 */
class FlatRenderer : Renderer {
    override val name = "Flat"

    /** Ground colors of the active color theme ([Cosmetic.theme], docs/TOP100.md C5), read at the start of a frame. */
    private var land = Cosmetic.palette.flatLand
    private var waterColor = Cosmetic.palette.flatWater
    private val ink = 0xFF262B33.toInt()
    private val alarm = 0xFFD7263D.toInt()

    private var backdrop = Cosmetic.palette.flatBackdrop
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

    /** Room for icons above the cells and request queues to the right of them (flat map units are turned cells). */
    override fun mapBounds(area: CellRect, angle: Float) = turnedBounds(area, angle, MapProjection.Identity, 0.3f, 0.6f, 0.9f, 0.3f)

    /** The camera focus turned back into world axes, so [gx] and [gy] draw world-aligned under [turnCanvas]. */
    private var groundFocusX = 0f
    private var groundFocusY = 0f

    /** Screen x (before the canvas turn of [turnCanvas]) of world x; exactly [Camera.toScreenX] when not turned. */
    private fun gx(x: Float) = camera.centerX + (x - groundFocusX) * camera.scale
    private fun gy(y: Float) = camera.centerY + (y - groundFocusY) * camera.scale

    /** Turns [canvas] by the camera's angle around the view centre; draw world-aligned with [gx] and [gy], then restore. */
    private fun turnCanvas(canvas: Canvas) {
        canvas.save()
        canvas.rotate(camera.angle, camera.centerX, camera.centerY)
    }

    override fun draw(canvas: Canvas, world: World, drag: DragPreview?, time: Float) {
        Cosmetic.paletteFor(world.scenario.id).let {
            land = it.flatLand
            waterColor = it.flatWater
            backdrop = it.flatBackdrop
        }
        if (cornerRadius != cell * 0.35f) {
            cornerRadius = cell * 0.35f
            cableP.pathEffect = CornerPathEffect(cornerRadius)
        }
        groundFocusX = camera.cosA * camera.focusX + camera.sinA * camera.focusY
        groundFocusY = -camera.sinA * camera.focusX + camera.cosA * camera.focusY
        canvas.drawColor(backdrop)
        turnCanvas(canvas)
        val grid = screenRect(world.bounds, gridRect)
        fillP.color = land; canvas.drawRect(grid, fillP)
        // One frame only: around the unlocked block while the map still grows (drawLockedArea), else around the grid.
        if (world.unlocked == world.bounds) { strokeP.color = edge; strokeP.strokeWidth = cell * 0.03f; canvas.drawRect(grid, strokeP) }
        canvas.save()
        canvas.clipRect(grid)
        drawWater(canvas, world)
        drawTowers(canvas, world)
        canvas.restore()
        canvas.restore()
        drawMountains(canvas, world)
        turnCanvas(canvas)
        drawLockedArea(canvas, world, grid)

        for (c in world.cables) {
            val grow = Juice.growth(world.time, c.builtAt, c.layout.length)
            if (grow < 1f) partialPolyline(cablePath(c), grow) else polyline(cablePath(c))
            val st = CableStyles.of(c.type)
            // Bold metro-map lines: the overview reads as a line map, not a wiring plan.
            val lw = st.width * FLAT_LINE
            cableP.color = land; cableP.strokeWidth = cell * (lw + 0.12f); canvas.drawPath(path, cableP)
            cableP.color = st.color; cableP.strokeWidth = cell * lw; canvas.drawPath(path, cableP)
            st.core?.let { cableP.color = it; cableP.strokeWidth = cell * st.coreWidth * FLAT_LINE; canvas.drawPath(path, cableP) }
            if (world.cableLoad(c) >= c.capacity) {
                cableP.color = alarm and 0x80FFFFFF.toInt(); cableP.strokeWidth = cell * 0.05f; canvas.drawPath(path, cableP)
            }
            if (world.isCut(c)) {
                cutP.pathEffect = cutDash.get(cell * 0.12f, 0.7f, 0f)
                cutP.strokeWidth = cell * st.width * 0.6f
                canvas.drawPath(path, cutP)
            }
            drawCableJuice(canvas, world, c, grow)
        }

        drawRadioCoverage(canvas, world, time)
        for (i in world.incidents) drawIncidentGround(canvas, i, time)

        drag?.let { d ->
            polyline(d.layout.waypoints)
            val st = CableStyles.of(d.type)
            val col = if (d.blocked) alarm else st.color
            val width = cell * maxOf(st.width, 0.12f)
            DragJuice.glow(canvas, path, col, width)
            cableP.color = land; cableP.strokeWidth = width + cell * 0.1f; canvas.drawPath(path, cableP)
            cableP.color = col; cableP.strokeWidth = width
            canvas.drawPath(path, cableP)
        }
        canvas.restore()

        drag?.let { d ->
            val end = d.layout.end
            val col = if (d.blocked) alarm else CableStyles.of(d.type).color
            val xs = FloatArray(d.trail.size + 1); val ys = FloatArray(d.trail.size + 1)
            d.trail.forEachIndexed { k, p -> val q = toScreen(p); xs[k] = q.x; ys[k] = q.y }
            toScreen(d.end).let { xs[d.trail.size] = it.x; ys[d.trail.size] = it.y }
            DragJuice.trail(canvas, xs, ys, col, density)
            d.label?.let {
                val s = toScreen(end)
                DragJuice.bubble(
                    canvas, it, d.detail, s.x, s.y, maxOf(cell * 0.9f, 40f * density), maxOf(cell * 0.38f, LABEL_MIN_DP * 1.2f * density), density,
                    if (d.blocked) alarm else ink, if (d.detailWarning) alarm else ink, col,
                )
            }
        }

        val packets = world.packets
        for (k in packets.indices) {
            val p = packets[k]
            world.packetPosition(p, pos)
            val sx = screenX(pos[0], pos[1]); val sy = screenY(pos[0], pos[1])
            val r = maxOf(cell * (0.11f + 0.035f * p.size), (2.6f + 0.8f * p.size) * density)
            if (p.isResponse) ServiceGlyphs.response(canvas, p.service, sx, sy, r * 0.9f)
            else ServiceGlyphs.token(canvas, p.service, sx, sy, r)
        }

        for (i in world.incidents) if (i.kind == IncidentKind.EXCAVATOR) {
            val spot = toScreen(i.spot)
            val size = cell * 0.3f
            val dig = if (i.struck) 0.75f + 0.25f * kotlin.math.sin(time * 3f) else 0f
            icons.excavator(canvas, spot.x - size * 1.25f, spot.y - size * 0.65f, size, dig)
        }

        for (n in world.nodes) {
            val nx = screenX(n.center.x, n.center.y); val ny = screenY(n.center.x, n.center.y)
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
                NodeKind.CELL_TOWER -> {
                    icons.cellTower(canvas, nx, ny, cell * 0.34f, time)
                    n.cellGeneration?.let {
                        val r = maxOf(cell * 0.12f, CHANNEL_BADGE_MIN_DP * 0.8f * density)
                        CellBadges.draw(canvas, it, nx + cell * 0.44f, ny - cell * 0.3f, r)
                    }
                }
                NodeKind.SERVER -> if (n.isDataCenter) {
                    icons.dataCenter(
                        canvas, n.service!!, world.serverBusy(n), screenX(n.footprintCenter.x, n.footprintCenter.y),
                        screenY(n.footprintCenter.x, n.footprintCenter.y), cell * 0.85f, time,
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
                        ServiceGlyphs.token(canvas, svc, px, py, r)
                        if (ProblemBadges.shows(world.routeProblem(n, svc))) {
                            // Stuck: the route is too slow for this real-time service, or no link is wide enough.
                            strokeP.color = alarm; strokeP.strokeWidth = r * 0.38f
                            canvas.drawCircle(px, py, r * 1.45f, strokeP)
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
            val nx = screenX(n.center.x, n.center.y); val ny = screenY(n.center.x, n.center.y)
            val bx = nx - cell * 0.34f; val by = ny - cell * 0.36f; val r = cell * 0.14f
            fillP.color = if (dark) IncidentStyles.EXCAVATOR_DARK else IncidentStyles.WARNING
            canvas.drawCircle(bx, by, r, fillP)
            fillP.color = if (dark) IncidentStyles.WARNING else 0xFFFFFFFF.toInt()
            canvas.drawPath(IncidentStyles.bolt(path, bx, by, r * 0.68f), fillP)
        }

        drawNodeJuice(canvas, world)

        world.failedNode?.let {
            val s = toScreen(it.center)
            strokeP.color = alarm; strokeP.strokeWidth = cell * 0.05f
            canvas.drawCircle(s.x, s.y, cell * (0.7f + 0.15f * kotlin.math.sin(time * 6f)), strokeP)
        }
    }

    /**
     * On the turned canvas: the growing tip of a cable being laid, the ring clicking in at both ends once it has grown,
     * and the glint running along it after an upgrade (docs/TOP100.md B3).
     */
    private fun drawCableJuice(canvas: Canvas, world: World, c: com.mininetworks.game.game.Cable, grow: Float) {
        val st = CableStyles.of(c.type)
        if (grow < 1f) {
            c.layout.pointAt(grow, pos)
            val x = gx(pos[0]); val y = gy(pos[1])
            fillP.color = 0x66FFFFFF; canvas.drawCircle(x, y, cell * 0.16f, fillP)
            fillP.color = st.color; canvas.drawCircle(x, y, cell * 0.08f, fillP)
            fillP.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(x, y, cell * 0.04f, fillP)
        }
        val land = Juice.landing(world.time, c.builtAt, c.layout.length)
        if (land in 0f..1f) {
            strokeP.color = st.color and 0xFFFFFF or (Juice.fade(land) shl 24)
            strokeP.strokeWidth = cell * 0.05f * (1f - 0.5f * land)
            canvas.drawCircle(gx(c.a.center.x), gy(c.a.center.y), cell * (0.3f + 0.3f * land), strokeP)
            canvas.drawCircle(gx(c.b.center.x), gy(c.b.center.y), cell * (0.3f + 0.3f * land), strokeP)
        }
        val g = Juice.upgrade(world.time, c.upgradedAt, Juice.CABLE_GLINT_SECONDS)
        if (g in 0f..1f) {
            polyline(cablePath(c))
            cableP.color = 0xFFFFFF or ((Juice.fade(g) * 0.55f).toInt() shl 24)
            cableP.strokeWidth = cell * (st.width + 0.1f)
            canvas.drawPath(path, cableP)
            c.layout.pointAt(g, pos)
            fillP.color = 0xFFFFFFFF.toInt()
            Juice.sparkle(canvas, path, gx(pos[0]), gy(pos[1]), cell * 0.16f, fillP)
        }
    }

    /** Upright: rings and sparkles at an upgraded node, and a pop with a little burst over each delivered response. */
    private fun drawNodeJuice(canvas: Canvas, world: World) {
        for (n in world.nodes) {
            val t = Juice.upgrade(world.time, n.upgradedAt)
            if (t !in 0f..1f) continue
            val x = screenX(n.footprintCenter.x, n.footprintCenter.y); val y = screenY(n.footprintCenter.x, n.footprintCenter.y)
            val base = cell * if (n.isDataCenter) 1.1f else 0.5f
            strokeP.color = (n.service?.let(ServiceColors::of) ?: RadioStyles.color(n)) and 0xFFFFFF or (Juice.fade(t) shl 24)
            strokeP.strokeWidth = cell * 0.05f * (1f - 0.5f * t)
            canvas.drawCircle(x, y, base * (1f + 0.8f * t), strokeP)
            Juice.sparkles(canvas, path, x, y - cell * 0.3f, t, base, cell * 0.5f, cell * 0.09f, 0xFFFFD34D.toInt(), fillP)
        }
        for (a in world.arrivals) {
            val t = (world.time - a.time) / DELIVERY_POP
            if (t !in 0f..1f) continue
            val x = screenX(a.node.center.x, a.node.center.y); val y = screenY(a.node.center.x, a.node.center.y)
            strokeP.color = ServiceColors.of(a.service) and 0xFFFFFF or ((1f - t) * 200f).toInt().shl(24)
            strokeP.strokeWidth = cell * 0.04f
            canvas.drawCircle(x, y, cell * (0.35f + 0.3f * t), strokeP)
            if (!a.isResponse) continue
            val py = y - cell * (0.55f + 0.35f * t)
            val alpha = Juice.fade(t) shl 24
            ServiceGlyphs.token(canvas, a.service, x, py, cell * 0.1f, alpha = alpha ushr 24)
            val b = t / 0.45f
            if (b <= 1f) Juice.sparkles(canvas, path, x, py, b, cell * 0.25f, 0f, cell * 0.05f, ServiceColors.of(a.service), fillP, n = 5)
        }
    }

    /** The first [f] of the polyline through [pts] in ground coordinates, into [path]. */
    private fun partialPolyline(pts: List<Vec2>, f: Float) {
        path.reset()
        var left = com.mininetworks.game.game.Geometry.polylineLength(pts) * f
        path.moveTo(gx(pts[0].x), gy(pts[0].y))
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            val seg = kotlin.math.hypot(b.x - a.x, b.y - a.y)
            if (seg >= left) {
                val u = if (seg > 0f) left / seg else 0f
                path.lineTo(gx(a.x + (b.x - a.x) * u), gy(a.y + (b.y - a.y) * u))
                return
            }
            path.lineTo(gx(b.x), gy(b.y))
            left -= seg
        }
    }

    /** Radio circles in the channel color, red where same-channel access points overlap, dashed lines for radio links. */
    private fun drawRadioCoverage(canvas: Canvas, world: World, time: Float) {
        val radios = radioScratch
        radios.clear()
        for (n in world.nodes) if (n.radius > 0f && !world.isDark(n)) radios += n
        if (radios.isEmpty()) return
        for (n in radios) {
            val c = ground(n.center); val col = RadioStyles.color(n)
            fillP.color = col and 0x00FFFFFF or 0x1F000000; canvas.drawCircle(c.x, c.y, n.radius * cell, fillP)
            strokeP.color = col and 0x00FFFFFF or 0x99000000.toInt(); strokeP.strokeWidth = cell * 0.025f
            canvas.drawCircle(c.x, c.y, n.radius * cell, strokeP)
        }
        for (a in radios) for (b in world.interferers(a)) {
            if (b.id < a.id) continue
            val ca = ground(a.center); val cb = ground(b.center)
            clip.reset(); clip.addCircle(ca.x, ca.y, a.radius * cell, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            fillP.color = alarm and 0x00FFFFFF or 0x59000000; canvas.drawCircle(cb.x, cb.y, b.radius * cell, fillP)
            canvas.restore()
        }
        for (n in radios) if (world.interferers(n).isNotEmpty()) {
            val c = ground(n.center)
            strokeP.color = alarm; strokeP.strokeWidth = cell * 0.03f; canvas.drawCircle(c.x, c.y, n.radius * cell, strokeP)
        }
        val dash = cell * 0.1f
        airP.pathEffect = airDash.get(dash, 0.8f, -time * dash * 4f)
        airP.strokeWidth = cell * 0.035f
        for (l in world.radioLinks) {
            airP.color = RadioStyles.color(l.radio)
            val a = ground(l.radio.center); val b = ground(l.device.center)
            canvas.drawLine(a.x, a.y, b.x, b.y, airP)
        }
    }

    /** Pulsing amber ring and countdown arc while announced, a red arc running down once struck, a hole at a cut. */
    private fun drawIncidentGround(canvas: Canvas, i: Incident, time: Float) {
        val c = ground(i.spot)
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
            canvas.drawRoundRect(gx(x - 0.04f), gy(y - 0.04f), gx(x + 1.04f), gy(y + 1.04f), r, r, fillP)
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

    /** Downtown towers as grey blocks, flat like the rest of the overview; drawn on the turned canvas. */
    private fun drawTowers(canvas: Canvas, world: World) {
        fillP.color = TOWER
        for (y in 0 until world.rows) for (x in 0 until world.cols) {
            if (world.terrainAt(x, y) != Terrain.HIGH_RISE) continue
            canvas.drawRoundRect(gx(x + 0.16f), gy(y + 0.16f), gx(x + 0.84f), gy(y + 0.84f), cell * 0.08f, cell * 0.08f, fillP)
        }
    }

    /** Mountains as two-tone peaks that stay upright however the map is turned, lit from the left. */
    private fun drawMountains(canvas: Canvas, world: World) {
        for (y in 0 until world.rows) for (x in 0 until world.cols) {
            if (world.terrainAt(x, y) != Terrain.MOUNTAIN) continue
            val cx = screenX(x + 0.5f, y + 0.5f); val cy = screenY(x + 0.5f, y + 0.5f)
            val lx = cx - cell * 0.42f; val ly = cy + cell * 0.4f
            val tx = cx; val ty = cy - cell * 0.38f
            val rx = cx + cell * 0.42f
            path.reset(); path.moveTo(lx, ly); path.lineTo(tx, ty); path.lineTo(rx, ly); path.close()
            fillP.color = MOUNTAIN; canvas.drawPath(path, fillP)
            path.reset(); path.moveTo(tx, ty); path.lineTo(rx, ly); path.lineTo(tx, ly); path.close()
            fillP.color = MOUNTAIN_SHADE; canvas.drawPath(path, fillP)
        }
    }

    /** Screen position of world point ([x], [y]) with the turn applied, for things drawn upright. */
    private fun screenX(x: Float, y: Float) = camera.toScreenX(camera.turnX(x, y))
    private fun screenY(x: Float, y: Float) = camera.toScreenY(camera.turnY(x, y))

    /** World point [p] in the ground coordinates of the turned canvas ([gx], [gy]). */
    private fun ground(p: Vec2) = Vec2(gx(p.x), gy(p.y))

    /** The [river] as one smooth band. */
    private fun drawRiver(canvas: Canvas) {
        val pts = river
        if (pts.isEmpty()) return
        path.reset()
        path.moveTo(gx(pts[0].x), gy(pts[0].y))
        for (i in 1 until pts.size - 1) {
            val mx = (pts[i].x + pts[i + 1].x) / 2; val my = (pts[i].y + pts[i + 1].y) / 2
            path.quadTo(gx(pts[i].x), gy(pts[i].y), gx(mx), gy(my))
        }
        path.lineTo(gx(pts.last().x), gy(pts.last().y))
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
        out.set(gx(r.left.toFloat()), gy(r.top.toFloat()), gx(r.right.toFloat()), gy(r.bottom.toFloat()))
        return out
    }

    private fun polyline(pts: List<Vec2>) {
        path.reset()
        for (i in pts.indices) {
            val x = gx(pts[i].x); val y = gy(pts[i].y)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
    }

    private companion object {
        /** Readable sizes on a phone: cell width of the automatic framing, minimum dp of a device icon's half size, a
         *  request's radius, an overload ring and the drag label. */
        const val READABLE_CELL_DP = 26f
        /** Cable width factor of the overview over [CableStyles] widths: bold metro-map lines. */
        const val FLAT_LINE = 1.6f
        const val ICON_MIN_DP = 7f
        const val REQUEST_MIN_DP = 3.2f
        const val RING_MIN_DP = 2.5f
        const val LABEL_MIN_DP = 13f
        /** Radius of an access point's channel badge never shrinks below this, so the number stays readable. */
        const val CHANNEL_BADGE_MIN_DP = 7f
        /** Seconds of a delivery pop and of the ring where a request reaches its server. */
        const val DELIVERY_POP = 0.8f
        const val MOUNTAIN = 0xFFB9B2A4.toInt()
        const val MOUNTAIN_SHADE = 0xFF9E9687.toInt()
        const val TOWER = 0xFFB4BAC2.toInt()
    }
}
