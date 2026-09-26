package com.mininetworks.game.render

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.mininetworks.game.game.Cell
import com.mininetworks.game.game.CellRect
import com.mininetworks.game.game.Incident
import com.mininetworks.game.game.IncidentKind
import com.mininetworks.game.game.Incidents
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.Vec2
import com.mininetworks.game.game.World
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/** Style B from docs/style-explorations.html: isometric tiles, extruded buildings, grid-aligned cables. */
class IsoRenderer : Renderer {
    override val name = "Iso"

    private val landA = 0xFFDDE9D6.toInt()
    private val landB = 0xFFD5E3CD.toInt()
    private val waterA = 0xFF8FC3DA.toInt()
    private val waterB = 0xFFA4D0E3.toInt()
    private val alarm = 0xFFD7263D.toInt()

    /** Tiles that are not unlocked yet: washed-out versions of the land and water colors. */
    private val lockedLandA = 0xFFE3E6E0.toInt()
    private val lockedLandB = 0xFFDDE1DA.toInt()
    private val lockedWaterA = 0xFFC4D8E1.toInt()
    private val lockedWaterB = 0xFFCDDEE6.toInt()
    private val edge = 0x8C2F3A34.toInt()

    override val camera = Camera()
    /** Tile width and height in pixels at the current zoom. */
    private val tw get() = camera.scale
    private val th get() = camera.scale / 2f
    override val unitPx get() = tw * 0.7f

    private val fillP = fill(0)
    private val strokeP = stroke(0)
    private val path = Path()
    private val icons = DeviceIcons()
    private val oval = RectF()
    private val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val airP = stroke(0)
    private val cutP = stroke(IncidentStyles.CUT)
    private val clip = Path()

    /** Isometric map space: one unit per tile width; a tile is half as high as it is wide. */
    override fun toMap(p: Vec2) = Vec2((p.x - p.y) / 2f, (p.x + p.y) / 4f)

    override fun fromMap(mx: Float, my: Float): Vec2 {
        val a = 2f * mx // x - y
        val b = 4f * my // x + y
        return Vec2((a + b) / 2f, (b - a) / 2f)
    }

    /** The diamond of [area] plus room for tall buildings above and the board edge below. */
    override fun mapBounds(area: CellRect) = MapRect(
        (area.left - area.bottom) / 2f - 0.3f,
        (area.left + area.top) / 4f - 1.1f,
        (area.right - area.top) / 2f + 0.4f,
        (area.right + area.bottom) / 4f + 0.4f,
    )

    private fun sx(x: Float, y: Float) = camera.toScreenX((x - y) / 2f)
    private fun sy(x: Float, y: Float, z: Float = 0f) = camera.toScreenY((x + y) / 4f - z / 2f)

    override fun draw(canvas: Canvas, world: World, drag: DragPreview?, time: Float) {
        canvas.drawColor(0xFFEEF3EA.toInt())
        val open = world.unlocked
        for (y in 0 until world.rows) for (x in 0 until world.cols) {
            quad(x.toFloat(), y.toFloat(), 1f, 1f, 0f)
            val even = (x + y) % 2 == 0
            fillP.color = when {
                !open.contains(x, y) && world.water[y][x] -> if (even) lockedWaterA else lockedWaterB
                !open.contains(x, y) -> if (even) lockedLandA else lockedLandB
                world.water[y][x] -> if (even) waterA else waterB
                else -> if (even) landA else landB
            }
            canvas.drawPath(path, fillP)
        }
        // Board edges give the "toy on a table" look.
        val w = world.cols.toFloat(); val h = world.rows.toFloat()
        side(0f, h, w, h); fillP.color = 0xFFB9C9AF.toInt(); canvas.drawPath(path, fillP)
        side(w, 0f, w, h); fillP.color = 0xFFA7BA9C.toInt(); canvas.drawPath(path, fillP)
        if (open != world.bounds) {
            quad(open.left.toFloat(), open.top.toFloat(), open.width.toFloat(), open.height.toFloat(), 0f)
            strokeP.color = edge; strokeP.strokeWidth = tw * 0.03f; canvas.drawPath(path, strokeP)
        }

        for (c in world.cables) {
            polyline(cablePath(c))
            val st = CableStyles.of(c.type)
            strokeP.color = 0xB3FFFFFF.toInt(); strokeP.strokeWidth = tw * (st.width * 0.75f + 0.08f); canvas.drawPath(path, strokeP)
            strokeP.color = st.color; strokeP.strokeWidth = tw * st.width * 0.75f; canvas.drawPath(path, strokeP)
            st.core?.let { strokeP.color = it; strokeP.strokeWidth = tw * st.coreWidth * 0.75f; canvas.drawPath(path, strokeP) }
            if (world.isCut(c)) {
                val dash = tw * 0.16f
                cutP.pathEffect = DashPathEffect(floatArrayOf(dash, dash * 0.8f), 0f)
                cutP.strokeWidth = tw * st.width * 0.6f
                canvas.drawPath(path, cutP)
            }
        }
        drawRadioCoverage(canvas, world, time)
        for (i in world.incidents) drawIncidentGround(canvas, i, time)

        drag?.let { d ->
            val end = d.layout.end
            polyline(d.layout.waypoints)
            val st = CableStyles.of(d.type)
            strokeP.color = if (d.blocked) alarm else st.color and 0x99FFFFFF.toInt()
            strokeP.strokeWidth = tw * maxOf(st.width, 0.12f) * 0.75f; canvas.drawPath(path, strokeP)
            d.label?.let {
                labelP.textSize = tw * 0.28f; labelP.color = if (d.blocked) alarm else 0xFF2F3A34.toInt()
                canvas.drawText(it, sx(end.x, end.y), sy(end.x, end.y) - th * 1.6f, labelP)
            }
        }

        // Painter's algorithm: everything with height is drawn back-to-front by x + y.
        val items = ArrayList<Pair<Float, () -> Unit>>()
        for (n in world.nodes) {
            val c = n.footprintCenter
            items += (c.x + c.y) to { drawNode(canvas, world, n, time) }
        }
        for (i in world.incidents) if (i.kind == IncidentKind.EXCAVATOR) {
            val stand = excavatorStand(world, i)
            items += (stand.first.x + stand.first.y) to { drawExcavator(canvas, i, stand.first, stand.second, time) }
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
                    // Requests: filled, with a light rim so they stay visible on dark cables.
                    fillP.color = ServiceColors.of(p.service)
                    Shapes.draw(canvas, p.service.shape, px, py, r, fillP)
                    strokeP.color = landA; strokeP.strokeWidth = tw * 0.02f
                    Shapes.draw(canvas, p.service.shape, px, py, r, strokeP)
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

    /**
     * Radio coverage on the ground: a tinted ellipse per radio (the iso view of its circle) in its channel color, red
     * where two access points on the same channel overlap, and dashed, drifting lines for the links it carries.
     */
    private fun drawRadioCoverage(canvas: Canvas, world: World, time: Float) {
        val radios = world.nodes.filter { it.radius > 0f && !world.isDark(it) }
        if (radios.isEmpty()) return
        for (n in radios) {
            val col = RadioStyles.color(n)
            groundEllipse(n.center, n.radius)
            fillP.color = col and 0x00FFFFFF or 0x24000000; canvas.drawOval(oval, fillP)
            strokeP.color = col and 0x00FFFFFF or 0xA0000000.toInt(); strokeP.strokeWidth = tw * 0.018f; canvas.drawOval(oval, strokeP)
        }
        for (a in radios) for (b in world.interferers(a)) {
            if (b.id < a.id) continue
            groundEllipse(a.center, a.radius)
            clip.reset(); clip.addOval(oval, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            groundEllipse(b.center, b.radius)
            fillP.color = RadioStyles.INTERFERENCE and 0x00FFFFFF or 0x66000000; canvas.drawOval(oval, fillP)
            canvas.restore()
        }
        for (n in radios) if (world.interferers(n).isNotEmpty()) {
            groundEllipse(n.center, n.radius)
            strokeP.color = RadioStyles.INTERFERENCE; strokeP.strokeWidth = tw * 0.022f; canvas.drawOval(oval, strokeP)
        }
        val dash = tw * 0.07f
        airP.pathEffect = DashPathEffect(floatArrayOf(dash, dash * 0.8f), -time * dash * 4f)
        airP.strokeWidth = tw * 0.025f
        for (l in world.radioLinks) {
            airP.color = RadioStyles.color(l.radio)
            val a = l.radio.center; val b = l.device.center
            canvas.drawLine(sx(a.x, a.y), sy(a.x, a.y), sx(b.x, b.y), sy(b.x, b.y), airP)
        }
    }

    /** The screen ellipse of a ground circle around [c] with radius [r] (in cells), into [oval]. */
    private fun groundEllipse(c: Vec2, r: Float) {
        val x = sx(c.x, c.y); val y = sy(c.x, c.y)
        val hw = r * tw * HALF_SQRT2; val hh = r * th * HALF_SQRT2
        oval.set(x - hw, y - hh, x + hw, y + hh)
    }

    private fun drawNode(canvas: Canvas, world: World, n: Node, time: Float) {
        val busy = world.serverBusy(n)
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
                val dark = world.isDark(n)
                val warning = world.incidents.any { it.node === n && !it.struck }
                box(canvas, x, y, 0.4f, 0.15f, if (dark) DARK_TOP else 0xFFF5F7F9.toInt(), if (dark) DARK_SIDE else 0xFFD9DEE3.toInt())
                icons.router(canvas, sx(x, y), sy(x, y, 0.15f) - tw * 0.08f, tw * 0.17f, time, warning, dark)
                if (dark || warning) powerBadge(canvas, sx(x, y), sy(x, y, 0.15f) - tw * 0.42f, dark, time)
            }
            NodeKind.ACCESS_POINT -> {
                val dark = world.isDark(n)
                val warning = world.incidents.any { it.node === n && !it.struck }
                box(canvas, x, y, 0.36f, 0.3f, if (dark) DARK_TOP else 0xFFF5F7F9.toInt(), if (dark) DARK_SIDE else 0xFFD9DEE3.toInt())
                icons.accessPoint(canvas, sx(x, y), sy(x, y, 0.3f) - tw * 0.06f, tw * 0.15f, RadioStyles.color(n), time, dark)
                if (!dark) channelBadge(canvas, n, sx(x, y) + tw * 0.22f, sy(x, y, 0.3f) - tw * 0.2f, world.interferers(n).isNotEmpty())
                if (dark || warning) powerBadge(canvas, sx(x, y) - tw * 0.2f, sy(x, y, 0.3f) - tw * 0.3f, dark, time)
            }
            NodeKind.CELL_TOWER -> drawCellTower(canvas, x, y, time)
        }
    }

    /**
     * Marks on the ground at an incident: while announced, a pulsing amber ring and an arc that fills until it strikes;
     * once struck, the arc runs down in red until the effect ends. A cut cable also gets a dug-up hole.
     */
    private fun drawIncidentGround(canvas: Canvas, i: Incident, time: Float) {
        val p = i.spot
        val r = if (i.kind == IncidentKind.EXCAVATOR) 0.42f else 0.62f
        if (i.kind == IncidentKind.EXCAVATOR && i.struck) {
            groundEllipse(p, 0.3f); fillP.color = IncidentStyles.DIRT; canvas.drawOval(oval, fillP)
            groundEllipse(p, 0.18f); fillP.color = IncidentStyles.DIRT.shade(-0.4f); canvas.drawOval(oval, fillP)
        }
        strokeP.strokeWidth = tw * 0.035f
        if (!i.struck) {
            val phase = IncidentStyles.pulse(time * 1.5f)
            groundEllipse(p, r * (0.7f + phase * 0.9f))
            strokeP.color = IncidentStyles.WARNING and 0x00FFFFFF or ((1f - phase) * 220).toInt().shl(24)
            canvas.drawOval(oval, strokeP)
            groundEllipse(p, r)
            strokeP.color = IncidentStyles.WARNING
            canvas.drawArc(oval, -90f, 360f * (1f - i.warning / Incidents.WARNING_SECONDS), false, strokeP)
        } else {
            groundEllipse(p, r)
            strokeP.color = IncidentStyles.CUT
            canvas.drawArc(oval, -90f, 360f * (1f - i.effectProgress), false, strokeP)
        }
    }

    /**
     * Where the excavator at [i] stands, and the unit direction from there to the cut: beside the cable, preferably on
     * the side away from the viewer so the cut stays visible in front of it, unless a node or cable is in that cell.
     */
    private fun excavatorStand(world: World, i: Incident): Pair<Vec2, Vec2> {
        val layout = i.cable!!.layout
        val a = layout.pointAt((i.cutAt - 0.02f).coerceAtLeast(0f))
        val b = layout.pointAt((i.cutAt + 0.02f).coerceAtMost(1f))
        val back = if (abs(b.x - a.x) >= abs(b.y - a.y)) Vec2(0f, 1f) else Vec2(1f, 0f)
        val spot = i.spot
        fun standFor(d: Vec2) = Vec2(spot.x - d.x * 0.62f, spot.y - d.y * 0.62f)
        fun clear(d: Vec2): Boolean {
            val cell = Cell(floor(spot.x - d.x).toInt(), floor(spot.y - d.y).toInt())
            return world.nodeAt(cell) == null && world.cables.none { cell in it.layout.cells }
        }
        val front = Vec2(-back.x, -back.y)
        val d = if (clear(back) || !clear(front)) back else front
        return standFor(d) to d
    }

    /**
     * A small excavator drawn with paths: tracks along the cable, a yellow cab with a window towards it, and a boom
     * whose bucket hangs raised over the cable while announced and digs in the hole once the cable is cut.
     * A beacon on the roof flashes during the announcement.
     */
    private fun drawExcavator(canvas: Canvas, i: Incident, at: Vec2, d: Vec2, time: Float) {
        val alongX = d.y != 0f
        // Boom and window share the cab wall facing the viewer, side by side along the cable, so the boom never covers
        // the window: the boom takes the half it swings across.
        val u = if (d.x + d.y > 0f) -1f else 1f
        val side = if (alongX) Vec2(0.09f * u, 0f) else Vec2(0f, 0.09f * u)
        val lx = if (alongX) 0.28f else 0.2f
        val ly = if (alongX) 0.2f else 0.28f
        oval.set(sx(at.x, at.y) - tw * 0.3f, sy(at.x, at.y) - th * 0.3f, sx(at.x, at.y) + tw * 0.3f, sy(at.x, at.y) + th * 0.3f)
        fillP.color = 0x2E000000; canvas.drawOval(oval, fillP)
        boxRect(canvas, at.x - lx, at.y - ly, at.x + lx, at.y + ly, 0f, 0.12f, IncidentStyles.EXCAVATOR_DARK.shade(0.25f), IncidentStyles.EXCAVATOR_DARK)
        val cx = at.x - d.x * 0.04f; val cy = at.y - d.y * 0.04f
        val cab = 0.16f
        val cabTop = 0.44f
        boxRect(canvas, cx - cab, cy - cab, cx + cab, cy + cab, 0.12f, cabTop - 0.12f, IncidentStyles.EXCAVATOR.shade(0.2f), IncidentStyles.EXCAVATOR)
        fillP.color = 0xFFBFD6E6.toInt()
        val w0 = if (u < 0f) 0.02f else -cab + 0.03f
        val w1 = if (u < 0f) cab - 0.03f else -0.02f
        if (alongX) faceY(cy + cab, cx + w0, cx + w1, 0.2f, 0.4f) else faceX(cx + cab, cy + w0, cy + w1, 0.2f, 0.4f)
        canvas.drawPath(path, fillP)
        if (!i.struck) {
            fillP.color = if (sin(time * 12f) > 0f) IncidentStyles.WARNING else IncidentStyles.WARNING.shade(-0.45f)
            canvas.drawCircle(sx(cx, cy), sy(cx, cy, cabTop + 0.04f), tw * 0.035f, fillP)
        }
        val baseX = cx + d.x * 0.12f + side.x; val baseY = cy + d.y * 0.12f + side.y
        val elbowX = at.x + d.x * 0.4f + side.x; val elbowY = at.y + d.y * 0.4f + side.y
        val spot = i.spot
        val bucketZ = if (i.struck) 0.06f + 0.1f * (sin(time * 3f) + 1f) else 0.42f + 0.04f * sin(time * 2f)
        val b0x = sx(baseX, baseY); val b0y = sy(baseX, baseY, 0.34f)
        val e0x = sx(elbowX, elbowY); val e0y = sy(elbowX, elbowY, 0.82f)
        val kx = sx(spot.x, spot.y); val ky = sy(spot.x, spot.y, bucketZ + 0.12f)
        strokeP.color = IncidentStyles.EXCAVATOR_DARK; strokeP.strokeWidth = tw * 0.065f
        canvas.drawLine(b0x, b0y, e0x, e0y, strokeP); canvas.drawLine(e0x, e0y, kx, ky, strokeP)
        strokeP.color = IncidentStyles.EXCAVATOR; strokeP.strokeWidth = tw * 0.04f
        canvas.drawLine(b0x, b0y, e0x, e0y, strokeP); canvas.drawLine(e0x, e0y, kx, ky, strokeP)
        val bx = sx(spot.x, spot.y); val by = sy(spot.x, spot.y, bucketZ)
        val k = tw * 0.07f
        path.reset()
        path.moveTo(bx - k, by - k * 0.9f); path.lineTo(bx + k, by - k * 0.9f)
        path.lineTo(bx + k * 0.55f, by + k * 0.6f); path.lineTo(bx - k * 0.8f, by + k * 0.35f); path.close()
        fillP.color = IncidentStyles.EXCAVATOR_DARK; canvas.drawPath(path, fillP)
    }

    /** A disc with a lightning bolt above a router or access point: flashing amber while announced, dark once out. */
    private fun powerBadge(canvas: Canvas, bx: Float, by: Float, dark: Boolean, time: Float) {
        val r = tw * 0.1f
        val on = dark || sin(time * 10f) > -0.2f
        fillP.color = if (dark) IncidentStyles.EXCAVATOR_DARK else if (on) IncidentStyles.WARNING else 0xFFFFFFFF.toInt()
        canvas.drawCircle(bx, by, r, fillP)
        strokeP.color = 0xFFFFFFFF.toInt(); strokeP.strokeWidth = tw * 0.015f
        canvas.drawCircle(bx, by, r, strokeP)
        fillP.color = if (dark) IncidentStyles.WARNING else if (on) 0xFFFFFFFF.toInt() else IncidentStyles.WARNING
        canvas.drawPath(IncidentStyles.bolt(path, bx, by, r * 0.68f), fillP)
    }

    /** Channel number in a disc: channel color, red rim while the access point suffers interference. */
    private fun channelBadge(canvas: Canvas, n: Node, bx: Float, by: Float, interfering: Boolean) {
        val r = tw * 0.1f
        fillP.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(bx, by, r, fillP)
        strokeP.color = if (interfering) RadioStyles.INTERFERENCE else RadioStyles.color(n)
        strokeP.strokeWidth = tw * (if (interfering) 0.03f else 0.02f)
        canvas.drawCircle(bx, by, r, strokeP)
        labelP.color = RadioStyles.color(n)
        labelP.textSize = r * (if (n.channel >= 10) 1.0f else 1.2f)
        canvas.drawText(n.channel.toString(), bx, by + labelP.textSize * 0.36f, labelP)
    }

    /** A lattice mast on a concrete foot: four legs meet at the top, braces between them, antenna panels and a light. */
    private fun drawCellTower(canvas: Canvas, x: Float, y: Float, time: Float) {
        box(canvas, x, y, 0.56f, 0.08f, 0xFFCBD2D9.toInt(), 0xFFB9C2CC.toInt())
        val base = 0.08f
        val height = 2.1f
        val half = 0.2f
        fun leg(dx: Float, dy: Float, z: Float) = (1f - (z - base) / height * 0.8f).let { k -> Vec2(x + dx * half * k, y + dy * half * k) }
        val corners = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)
        strokeP.color = 0xFF5B6674.toInt(); strokeP.strokeWidth = tw * 0.02f
        val top = base + height
        for ((dx, dy) in corners) {
            val a = leg(dx, dy, base); val b = leg(dx, dy, top)
            canvas.drawLine(sx(a.x, a.y), sy(a.x, a.y, base), sx(b.x, b.y), sy(b.x, b.y, top), strokeP)
        }
        strokeP.strokeWidth = tw * 0.012f
        for (level in 1..4) {
            val z = base + height * level / 5f
            val zPrev = base + height * (level - 1) / 5f
            for (i in corners.indices) {
                val (dx0, dy0) = corners[i]; val (dx1, dy1) = corners[(i + 1) % corners.size]
                val a = leg(dx0, dy0, z); val b = leg(dx1, dy1, z); val c = leg(dx0, dy0, zPrev)
                canvas.drawLine(sx(a.x, a.y), sy(a.x, a.y, z), sx(b.x, b.y), sy(b.x, b.y, z), strokeP)
                canvas.drawLine(sx(c.x, c.y), sy(c.x, c.y, zPrev), sx(b.x, b.y), sy(b.x, b.y, z), strokeP)
            }
        }
        val panelZ = top - 0.55f
        box(canvas, x - 0.12f, y + 0.02f, 0.1f, 0.4f, 0xFFF5F7F9.toInt(), 0xFFE3E6E1.toInt(), z0 = panelZ)
        box(canvas, x + 0.02f, y - 0.12f, 0.1f, 0.4f, 0xFFF5F7F9.toInt(), 0xFFE3E6E1.toInt(), z0 = panelZ)
        box(canvas, x + 0.08f, y + 0.08f, 0.1f, 0.4f, 0xFFF5F7F9.toInt(), 0xFFE3E6E1.toInt(), z0 = panelZ)
        fillP.color = if (sin(time * 2.5f) > 0f) 0xFFE4572E.toInt() else 0xFF8A3A2A.toInt()
        canvas.drawCircle(sx(x, y), sy(x, y, top + 0.05f), tw * 0.04f, fillP)
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

    private fun box(canvas: Canvas, cx: Float, cy: Float, s: Float, h: Float, top: Int, side: Int, z0: Float = 0f) =
        boxRect(canvas, cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2, z0, h, top, side)

    /** A block over the ground rectangle ([x0], [y0]) – ([x1], [y1]) from height [z0], [h] high; the two front walls are shaded. */
    private fun boxRect(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, z0: Float, h: Float, top: Int, side: Int) {
        val z1 = z0 + h
        path.reset()
        path.moveTo(sx(x0, y1), sy(x0, y1, z0)); path.lineTo(sx(x1, y1), sy(x1, y1, z0))
        path.lineTo(sx(x1, y1), sy(x1, y1, z1)); path.lineTo(sx(x0, y1), sy(x0, y1, z1)); path.close()
        fillP.color = side.shade(-0.12f); canvas.drawPath(path, fillP)
        path.reset()
        path.moveTo(sx(x1, y0), sy(x1, y0, z0)); path.lineTo(sx(x1, y1), sy(x1, y1, z0))
        path.lineTo(sx(x1, y1), sy(x1, y1, z1)); path.lineTo(sx(x1, y0), sy(x1, y0, z1)); path.close()
        fillP.color = side.shade(-0.25f); canvas.drawPath(path, fillP)
        quad(x0, y0, x1 - x0, y1 - y0, z1)
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

    private companion object {
        /** Half of √2: a ground circle of radius r spans r·√2/2 tile widths to each side in iso. */
        const val HALF_SQRT2 = 0.70710677f
        /** Router and access point bases without power. */
        const val DARK_TOP = 0xFF6E7781.toInt()
        const val DARK_SIDE = 0xFF59616B.toInt()
    }

    private fun polyline(pts: List<Vec2>) {
        path.reset()
        pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(sx(p.x, p.y), sy(p.x, p.y)) else path.lineTo(sx(p.x, p.y), sy(p.x, p.y)) }
    }
}
