package com.mininetworks.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.mininetworks.game.game.Cable
import com.mininetworks.game.game.Cell
import com.mininetworks.game.game.CellRect
import com.mininetworks.game.game.Geometry
import com.mininetworks.game.game.Incident
import com.mininetworks.game.game.IncidentKind
import com.mininetworks.game.game.Incidents
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.Packet
import com.mininetworks.game.game.RouteProblem
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.Terrain
import com.mininetworks.game.game.Vec2
import com.mininetworks.game.game.World
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/** The iso squash: one map unit per tile width, a tile half as high as it is wide. */
object IsoProjection : MapProjection {
    override fun projectX(x: Float, y: Float) = (x - y) / 2f
    override fun projectY(x: Float, y: Float) = (x + y) / 4f
    override fun unprojectX(mx: Float, my: Float) = mx + 2f * my
    override fun unprojectY(mx: Float, my: Float) = 2f * my - mx
}

/**
 * Style B from docs/style-explorations.html: isometric tiles, extruded buildings, grid-aligned cables.
 *
 * The map can be turned to any angle ([Camera.angle]): every world point is first turned around the world origin and
 * then squashed ([IsoProjection]), so tiles, cables, packets, radio circles and shadows all follow. What stands up from
 * the ground is built from its faces: a wall is drawn only while it faces the viewer, and its shade comes from the
 * direction it faces on screen, so the light stays at the upper left however the map is turned. Things with height
 * are painted back to front by their depth in the turned map ([depthOf]).
 */
class IsoRenderer : Renderer {
    override val name = "Iso"

    /** Ground colors of the active color theme ([Cosmetic.theme], docs/TOP100.md C5); read once per frame. */
    private var pal = Cosmetic.palette
    private val landA get() = pal.landA
    private val landB get() = pal.landB
    private val waterA get() = pal.waterA
    private val waterB get() = pal.waterB
    private val alarm = 0xFFD7263D.toInt()

    /** Tiles that are not unlocked yet: washed-out versions of the land and water colors. */
    private val lockedLandA get() = pal.lockedLandA
    private val lockedLandB get() = pal.lockedLandB
    private val lockedWaterA get() = pal.lockedWaterA
    private val lockedWaterB get() = pal.lockedWaterB
    private val edge = 0x8C2F3A34.toInt()

    override val camera = Camera().apply { projection = IsoProjection }
    override var density = 1f
    /** A tile at least [READABLE_TILE_DP] wide: device icons about 14 dp, requests about 6 dp. */
    override val readableScale get() = READABLE_TILE_DP * density
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
    private val shimmerP = stroke(0)

    /** Ground layer cache: tiles, board, shadows and decorations of [groundMap] as seen from [groundView]. */
    private var groundBitmap: Bitmap? = null
    private var groundCanvas: Canvas? = null
    private var groundMap = 0L
    private val groundView = ViewKey()
    /** Camera of the previous frame: the cache is only rebuilt once the view holds still. */
    private val lastView = ViewKey()

    /** Camera and canvas size a ground image was drawn for; mutable so comparing it every frame allocates nothing. */
    private class ViewKey {
        var scale = Float.NaN
        var focusX = 0f
        var focusY = 0f
        var angle = 0f
        var width = 0
        var height = 0

        fun matches(c: Camera, w: Int, h: Int) =
            scale == c.scale && focusX == c.focusX && focusY == c.focusY && angle == c.angle && width == w && height == h

        fun set(c: Camera, w: Int, h: Int) {
            scale = c.scale; focusX = c.focusX; focusY = c.focusY; angle = c.angle; width = w; height = h
        }
    }

    /** Layout of the ground for the map signature [planMap]: relief and decorations back to front, grass cells. */
    private var planMap = 0L
    private var planAngle = 0f
    private var planReady = false
    private val plan = DepthQueue()
    private val grassCells = ArrayList<Cell>()
    /** Mountain and tower cells in row order, the order their shadows are drawn in. */
    private val reliefCells = ArrayList<Cell>()

    /** Where the excavators of [standFor] stand ([excavatorStand]), for the network with signature [standMap]. */
    private var standMap = 0L
    private val standFor = ArrayList<Incident>()
    private val standAt = ArrayList<Vec2>()
    private val standDir = ArrayList<Vec2>()
    private val standCells = ArrayList<Cell>()

    /** Painter's order of everything with height, rebuilt every frame without allocating. */
    private val depth = DepthQueue(256)
    private val radioScratch = ArrayList<Node>()
    private val pos = FloatArray(2)
    private val cutDash = DashCache()
    private val airDash = DashCache()

    /** The (turned) diamond of [area] plus room for tall buildings above and the board edge below. */
    override fun mapBounds(area: CellRect, angle: Float) = turnedBounds(area, angle, IsoProjection, 0.3f, 1.1f, 0.4f, 0.4f)

    /** Screen x of world point ([x], [y]) on the ground: turned by the camera's angle, then squashed. */
    private fun sx(x: Float, y: Float): Float {
        val c = camera.cosA; val s = camera.sinA
        return camera.toScreenX(((c - s) * x - (s + c) * y) / 2f)
    }

    /** Screen y of world point ([x], [y]) at height [z] (in tile widths). */
    private fun sy(x: Float, y: Float, z: Float = 0f): Float {
        val c = camera.cosA; val s = camera.sinA
        return camera.toScreenY(((c + s) * x + (c - s) * y) / 4f - z / 2f)
    }

    /** Depth of world point ([x], [y]) in the turned map: larger is nearer the viewer (x + y when not turned). */
    private fun depthOf(x: Float, y: Float): Float {
        val c = camera.cosA; val s = camera.sinA
        return (c + s) * x + (c - s) * y
    }

    /** True if a wall whose outward normal in the world is ([nx], [ny]) faces the viewer at the current angle. */
    private fun facing(nx: Float, ny: Float) = depthOf(nx, ny) > FACING_EPS

    /**
     * Brightness change of a wall with world normal ([nx], [ny]): the wall facing screen lower left is lit (-0.12),
     * the one facing lower right is in shade (-0.25), in between it blends; so the light stays put on screen.
     */
    private fun wallShade(nx: Float, ny: Float): Float {
        val c = camera.cosA; val s = camera.sinA
        val tx = c * nx - s * ny; val ty = s * nx + c * ny
        return -0.185f + 0.065f * (ty - tx)
    }

    /** World x and y offsets of a screen-fixed offset ([dx], [dy]) given in the unturned map (e.g. a shadow's cast). */
    private fun unturnX(dx: Float, dy: Float) = camera.cosA * dx + camera.sinA * dy
    private fun unturnY(dx: Float, dy: Float) = -camera.sinA * dx + camera.cosA * dy

    override fun draw(canvas: Canvas, world: World, drag: DragPreview?, time: Float) {
        pal = Cosmetic.palette
        drawGroundLayer(canvas, world)
        drawWaterShimmer(canvas, world, time)

        for (c in world.cables) {
            val grow = growth(world, c)
            if (grow < 1f) partialPolyline(cablePath(c), grow) else polyline(cablePath(c))
            val st = CableStyles.of(c.type)
            strokeP.color = 0xB3FFFFFF.toInt(); strokeP.strokeWidth = tw * (st.width * 0.75f + 0.08f); canvas.drawPath(path, strokeP)
            strokeP.color = st.color; strokeP.strokeWidth = tw * st.width * 0.75f; canvas.drawPath(path, strokeP)
            st.core?.let { strokeP.color = it; strokeP.strokeWidth = tw * st.coreWidth * 0.75f; canvas.drawPath(path, strokeP) }
            if (world.isCut(c)) {
                cutP.pathEffect = cutDash.get(tw * 0.16f, 0.8f, 0f)
                cutP.strokeWidth = tw * st.width * 0.6f
                canvas.drawPath(path, cutP)
            }
            if (grow < 1f) drawCableTip(canvas, c, grow)
            drawCableJuice(canvas, world, c)
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
                labelP.textSize = maxOf(tw * 0.28f, LABEL_MIN_DP * density); labelP.color = if (d.blocked) alarm else 0xFF2F3A34.toInt()
                val ly = sy(end.x, end.y) - th * 1.6f - if (d.detail != null) labelP.textSize * 1.15f else 0f
                canvas.drawText(it, sx(end.x, end.y), ly, labelP)
                d.detail?.let { detail ->
                    labelP.color = if (d.detailWarning) alarm else 0xFF2F3A34.toInt()
                    canvas.drawText(detail, sx(end.x, end.y), ly + labelP.textSize * 1.15f, labelP)
                }
            }
        }

        drawArrivalRings(canvas, world)
        world.failedNode?.let { drawFailedPulse(canvas, it, time) }

        // Painter's algorithm: everything with height is drawn back-to-front by its depth in the turned map.
        depth.clear()
        val nodes = world.nodes
        for (k in nodes.indices) {
            val c = nodes[k].footprintCenter
            depth.add(depthOf(c.x, c.y), NODE, nodes[k])
        }
        for (k in standFor.indices) depth.add(depthOf(standAt[k].x, standAt[k].y), EXCAVATOR, k)
        val packets = world.packets
        for (k in packets.indices) {
            world.packetPosition(packets[k], pos)
            depth.add(depthOf(pos[0], pos[1]) + 0.01f, PACKET, packets[k], pos[0], pos[1])
        }
        depth.sort()
        for (k in 0 until depth.size) {
            when (depth.kind(k)) {
                NODE -> drawNode(canvas, world, depth.ref(k) as Node, time)
                EXCAVATOR -> (depth.ref(k) as Int).let { drawExcavator(canvas, standFor[it], standAt[it], standDir[it], time) }
                else -> drawPacket(canvas, depth.ref(k) as Packet, depth.x(k), depth.y(k))
            }
        }
        depth.clear()

        for (n in nodes) {
            if (n.kind != NodeKind.CLIENT || n.overload <= 0f) continue
            val cx = sx(n.center.x, n.center.y); val cy = sy(n.center.x, n.center.y)
            oval.set(cx - tw * 0.55f, cy - th * 0.55f, cx + tw * 0.55f, cy + th * 0.55f)
            strokeP.color = alarm; strokeP.strokeWidth = maxOf(tw * 0.05f, RING_MIN_DP * density)
            canvas.drawArc(oval, -90f, 360f * n.overload, false, strokeP)
        }
        for (n in nodes) if (n.upgradedAt > Float.NEGATIVE_INFINITY) drawUpgradeJuice(canvas, world, n)
        drawDeliveryPops(canvas, world)
    }

    /** A packet floating over its link at world point ([x], [y]), with a shadow on the ground. */
    private fun drawPacket(canvas: Canvas, p: Packet, x: Float, y: Float) {
        val r = maxOf(tw * (0.055f + 0.02f * p.size), PACKET_MIN_DP * density * (0.8f + 0.2f * p.size))
        // The shadow falls to the lower right of the packet, like every other shadow on the board.
        val sx0 = x + unturnX(0.1f, 0.04f); val sy0 = y + unturnY(0.1f, 0.04f)
        val gx = sx(sx0, sy0); val gy = sy(sx0, sy0)
        oval.set(gx - r * 0.95f, gy - r * 0.5f, gx + r * 0.95f, gy + r * 0.5f)
        fillP.color = 0x30000000; canvas.drawOval(oval, fillP)
        val px = sx(x, y)
        val py = sy(x, y, 0.35f)
        val col = ServiceColors.of(p.service)
        if (p.isResponse) {
            // Responses: smaller, white with a thick outline in the service color.
            val rr = r * 0.8f
            fillP.color = 0xFFFFFFFF.toInt(); Shapes.draw(canvas, p.service.shape, px, py, rr, fillP)
            strokeP.color = col; strokeP.strokeWidth = rr * 0.42f
            Shapes.draw(canvas, p.service.shape, px, py, rr, strokeP)
        } else {
            // Requests: a filled token with a white sticker rim, so the shape stays readable on dark cables and grass.
            strokeP.color = 0xFFFFFFFF.toInt(); strokeP.strokeWidth = r * 0.55f
            Shapes.draw(canvas, p.service.shape, px, py, r, strokeP)
            fillP.color = col
            Shapes.draw(canvas, p.service.shape, px, py, r, fillP)
        }
    }

    // ---------------------------------------------------------------- ground layer

    /**
     * Draws the static ground (tiles, board, shadows, decorations). It comes from a cached bitmap while the map and
     * the view are unchanged; after a change it is drawn directly until the camera holds still for a frame, then
     * cached again, so a pan or pinch never pays for rebuilding and uploading a bitmap every frame.
     */
    private fun drawGroundLayer(canvas: Canvas, world: World) {
        val map = mapSignature(world)
        val still = lastView.matches(camera, canvas.width, canvas.height)
        lastView.set(camera, canvas.width, canvas.height)
        val cached = groundBitmap
        if (cached != null && groundView.matches(camera, canvas.width, canvas.height) && map == groundMap) {
            canvas.drawBitmap(cached, 0f, 0f, null)
            return
        }
        if (!still || canvas.width <= 0 || canvas.height <= 0) {
            drawGround(canvas, world, map)
            return
        }
        val bmp = cached?.takeIf { it.width == canvas.width && it.height == canvas.height }
            ?: Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888).also {
                cached?.recycle()
                groundBitmap = it
                groundCanvas = Canvas(it)
            }
        drawGround(groundCanvas!!, world, map)
        groundView.set(camera, canvas.width, canvas.height)
        groundMap = map
        canvas.drawBitmap(bmp, 0f, 0f, null)
    }

    /** True if the next frame of [world] with the current camera would come from the ground cache; for tests. */
    internal fun groundCached(world: World, width: Int, height: Int) =
        groundBitmap != null && groundView.matches(camera, width, height) && groundMap == mapSignature(world)

    /**
     * Changes whenever anything drawn into the ground layer may change: water, unlocked area, nodes, cables and the
     * cells excavators stand on. Runs every frame, so it only mixes numbers and allocates nothing.
     */
    private fun mapSignature(world: World): Long {
        var h = mix(networkSignature(world), Cosmetic.theme.ordinal)
        val u = world.unlocked
        h = mix(mix(mix(mix(h, u.left), u.top), u.right), u.bottom)
        for (y in 0 until world.rows) for (x in 0 until world.cols) {
            val t = world.terrainAt(x, y)
            if (t != Terrain.LAND) h = mix(h, (y * 4096 + x) * 4 + t.ordinal)
        }
        updateStands(world, networkSignature(world))
        for (k in standCells.indices) h = mix(mix(h, standCells[k].x), standCells[k].y)
        return h
    }

    /** Changes whenever a node or cable appears, moves, grows or goes away. */
    private fun networkSignature(world: World): Long {
        var h = System.identityHashCode(world).toLong()
        val nodes = world.nodes
        h = mix(h, nodes.size)
        for (k in nodes.indices) {
            val n = nodes[k]
            h = mix(mix(mix(mix(mix(h, n.id), n.kind.ordinal), n.cellX), n.cellY), n.level)
        }
        val cables = world.cables
        h = mix(h, cables.size)
        for (k in cables.indices) {
            val c = cables[k]
            h = mix(mix(h, c.a.id), c.b.id)
            val pts = c.layout.waypoints
            for (i in pts.indices) h = mix(mix(h, pts[i].x.toRawBits()), pts[i].y.toRawBits())
        }
        return h
    }

    /**
     * Brings the excavator stands up to date: they only change when an excavator comes or goes or the network with
     * signature [network] changes, so they are worked out once instead of every frame.
     */
    private fun updateStands(world: World, network: Long) {
        var same = network == standMap
        var count = 0
        for (i in world.incidents) if (i.kind == IncidentKind.EXCAVATOR) {
            if (count >= standFor.size || standFor[count] !== i) same = false
            count++
        }
        if (same && count == standFor.size) return
        standMap = network
        standFor.clear(); standAt.clear(); standDir.clear(); standCells.clear()
        for (i in world.incidents) if (i.kind == IncidentKind.EXCAVATOR) {
            val (at, d) = excavatorStand(world, i)
            standFor += i; standAt += at; standDir += d; standCells += standCell(i.spot, d)
        }
    }

    private fun drawGround(c: Canvas, world: World, map: Long) {
        drawBackdrop(c)
        val open = world.unlocked
        val seed = world.seed
        drawBoardShadow(c, world)
        for (y in 0 until world.rows) for (x in 0 until world.cols) {
            quad(x.toFloat(), y.toFloat(), 1f, 1f, 0f)
            // The checkerboard marks the grid where cables go; on water and on locked land it is kept faint so the
            // river reads as one body of water and the area still to unlock stays calm.
            val even = (x + y) % 2 == 0
            val soft = if (even) SOFT_CHECKER else 1f - SOFT_CHECKER
            val locked = !open.contains(x, y)
            val base = when (world.terrainAt(x, y)) {
                Terrain.WATER -> if (locked) blend(lockedWaterA, lockedWaterB, 0.5f) else blend(waterA, waterB, soft)
                Terrain.MOUNTAIN -> (if (even) ROCK_A else ROCK_B).let { if (locked) wash(it) else it }
                Terrain.HIGH_RISE -> (if (even) PAVEMENT_A else PAVEMENT_B).let { if (locked) wash(it) else it }
                Terrain.LAND -> if (locked) blend(lockedLandA, lockedLandB, soft) else if (even) landA else landB
            }
            fillP.color = base.shade(Scenery.tileVariation(seed, x, y) * TILE_VARIATION)
            c.drawPath(path, fillP)
        }
        drawShores(c, world)
        // Board edges give the "toy on a table" look: the ones facing the viewer at the current angle.
        val w = world.cols.toFloat(); val h = world.rows.toFloat()
        for (e in BOARD_EDGES.indices step 6) {
            val nx = BOARD_EDGES[e + 4]; val ny = BOARD_EDGES[e + 5]
            if (!facing(nx, ny)) continue
            side(BOARD_EDGES[e] * w, BOARD_EDGES[e + 1] * h, BOARD_EDGES[e + 2] * w, BOARD_EDGES[e + 3] * h)
            val tx = camera.cosA * nx - camera.sinA * ny; val ty = camera.sinA * nx + camera.cosA * ny
            fillP.color = blend(pal.boardLit, pal.boardShade, ((tx - ty + 1f) / 2f).coerceIn(0f, 1f)); c.drawPath(path, fillP)
        }
        if (open != world.bounds) {
            quad(open.left.toFloat(), open.top.toFloat(), open.width.toFloat(), open.height.toFloat(), 0f)
            strokeP.color = edge; strokeP.strokeWidth = tw * 0.03f; c.drawPath(path, strokeP)
        }
        updatePlan(world, map)
        for (cell in grassCells) drawGrass(c, seed, cell.x, cell.y, open.contains(cell.x, cell.y))
        for (n in world.nodes) drawShadow(c, n)
        for (cell in reliefCells) {
            if (world.terrainAt(cell.x, cell.y) == Terrain.MOUNTAIN) {
                groundShadow(c, cell.x + 0.5f, cell.y + 0.5f, 0.86f, mountainHeight(seed, cell) * 0.7f)
            } else {
                groundShadow(c, cell.x + 0.5f, cell.y + 0.5f, TOWER_SIZE, towerHeight(seed, cell))
            }
        }
        // Mountains, towers and decorations back to front, so nearer ones overlap farther ones.
        for (k in 0 until plan.size) {
            val cell = plan.ref(k) as Cell
            val lit = open.contains(cell.x, cell.y)
            when (val kind = plan.kind(k)) {
                MOUNTAIN -> drawMountain(c, seed, cell, lit)
                TOWER -> drawTower(c, seed, cell, lit)
                else -> drawDecor(c, seed, cell, Decor.entries[kind - DECOR], lit)
            }
        }
    }

    /**
     * The table the board stands on: a calm vertical gradient of the theme's background, lighter towards the top, so
     * the space around the board (tall on a portrait phone) reads as soft daylight rather than an empty fill.
     */
    private fun drawBackdrop(c: Canvas) {
        val h = c.height.toFloat()
        if (backdropHeight != h || backdropColor != pal.background) {
            backdropHeight = h
            backdropColor = pal.background
            backdropP.shader = LinearGradient(
                0f, 0f, 0f, maxOf(h, 1f),
                pal.background.shade(0.5f), pal.background.shade(-0.035f), Shader.TileMode.CLAMP,
            )
        }
        c.drawPaint(backdropP)
    }

    private val backdropP = Paint()
    private var backdropHeight = Float.NaN
    private var backdropColor = 0

    /** The board's soft shadow on the table, cast to the lower right like every other shadow. */
    private fun drawBoardShadow(c: Canvas, world: World) {
        val w = world.cols.toFloat(); val h = world.rows.toFloat()
        val dx = unturnX(0.9f, 0.35f); val dy = unturnY(0.9f, 0.35f)
        for (pass in BOARD_SHADOW_SPREAD.indices) {
            val e = BOARD_SHADOW_SPREAD[pass]
            quad(dx - e, dy - e, w + 2 * e, h + 2 * e, -0.5f)
            fillP.color = BOARD_SHADOW_ALPHA shl 24
            c.drawPath(path, fillP)
        }
    }

    /**
     * Banks where water meets land: the water lies a little lower, so the bank on its far side shows as a short earth
     * wall with a bright waterline, and the near side gets a thin light rim. Which banks show follows the turned map.
     */
    private fun drawShores(c: Canvas, world: World) {
        val open = world.unlocked
        strokeP.strokeWidth = tw * 0.022f
        for (y in 0 until world.rows) for (x in 0 until world.cols) {
            if (world.terrainAt(x, y) != Terrain.WATER) continue
            val lit = open.contains(x, y)
            for (k in 0 until 4) {
                val nx = NEIGHBOURS[2 * k]; val ny = NEIGHBOURS[2 * k + 1]
                val ox = x + nx; val oy = y + ny
                if (ox < 0 || oy < 0 || ox >= world.cols || oy >= world.rows) continue
                if (world.terrainAt(ox, oy) == Terrain.WATER) continue
                // The edge between this cell and its neighbour.
                val ax = x + if (nx > 0) 1f else 0f; val ay = y + if (ny > 0) 1f else 0f
                val bx = if (nx != 0) ax else ax + 1f; val by = if (ny != 0) ay else ay + 1f
                val bank = if (lit) landB.shade(BANK_SHADE) else wash(landB.shade(BANK_SHADE))
                if (facing(-nx.toFloat(), -ny.toFloat())) {
                    poly(ax, ay, 0f, bx, by, 0f, bx, by, -BANK_DEPTH, ax, ay, -BANK_DEPTH)
                    fillP.color = bank; c.drawPath(path, fillP)
                    strokeP.color = if (lit) FOAM else FOAM_LOCKED
                    c.drawLine(sx(ax, ay), sy(ax, ay, -BANK_DEPTH), sx(bx, by), sy(bx, by, -BANK_DEPTH), strokeP)
                } else {
                    strokeP.color = if (lit) FOAM else FOAM_LOCKED
                    c.drawLine(sx(ax, ay), sy(ax, ay), sx(bx, by), sy(bx, by), strokeP)
                }
            }
        }
    }

    /**
     * Works out, once per map signature, which cells get grass and which relief and decorations stand where, in draw
     * order; while the camera moves the ground is drawn every frame and only replays this plan.
     */
    private fun updatePlan(world: World, map: Long) {
        if (planReady && map == planMap && planAngle == camera.angle) return
        planReady = true
        planMap = map
        planAngle = camera.angle
        plan.clear()
        grassCells.clear()
        reliefCells.clear()
        val seed = world.seed
        val taken = Scenery.occupied(world, standCells)
        for (y in 0 until world.rows) for (x in 0 until world.cols) {
            if (world.terrainAt(x, y) != Terrain.LAND || Cell(x, y) in taken || Scenery.planned(seed, x, y) != null) continue
            grassCells += Cell(x, y)
        }
        // Relief first, then decorations; equal depths keep that order, as before.
        for (y in 0 until world.rows) for (x in 0 until world.cols) {
            when (world.terrainAt(x, y)) {
                Terrain.MOUNTAIN -> plan.add(depthOf(x + 0.5f, y + 0.5f), MOUNTAIN, Cell(x, y))
                Terrain.HIGH_RISE -> plan.add(depthOf(x + 0.5f, y + 0.5f), TOWER, Cell(x, y))
                else -> continue
            }
            reliefCells += Cell(x, y)
        }
        for ((cell, d) in Scenery.decorations(world, taken)) plan.add(depthOf(cell.x + 0.5f, cell.y + 0.5f), DECOR + d.ordinal, cell)
        plan.sort()
    }

    private fun mountainHeight(seed: Long, cell: Cell) = 0.5f + 0.45f * Scenery.unit(seed, cell.x, cell.y, 30)

    private fun towerHeight(seed: Long, cell: Cell) = 0.6f + 0.9f * Scenery.unit(seed, cell.x, cell.y, 31)

    /** A rocky peak filling its cell, lit from the upper left, with a snow cap on the higher ones. */
    private fun drawMountain(c: Canvas, seed: Long, cell: Cell, open: Boolean) {
        fun col(v: Int) = if (open) v else wash(v)
        val h = mountainHeight(seed, cell)
        val x0 = cell.x + 0.02f; val y0 = cell.y + 0.02f; val x1 = cell.x + 0.98f; val y1 = cell.y + 0.98f
        val ax = cell.x + 0.5f + (Scenery.unit(seed, cell.x, cell.y, 32) - 0.5f) * 0.24f
        val ay = cell.y + 0.5f + (Scenery.unit(seed, cell.x, cell.y, 33) - 0.5f) * 0.24f
        // Four faces from the base edges up to the peak, back to front; each is shaded by where it faces on screen.
        val e = mountainEdges
        e[0] = x0; e[1] = y0; e[2] = x1; e[3] = y0
        e[4] = x0; e[5] = y1; e[6] = x0; e[7] = y0
        e[8] = x1; e[9] = y0; e[10] = x1; e[11] = y1
        e[12] = x1; e[13] = y1; e[14] = x0; e[15] = y1
        sortFacesBackToFront()
        for (i in 0 until 4) {
            val f = faceOrder[i]
            poly(e[4 * f], e[4 * f + 1], 0f, e[4 * f + 2], e[4 * f + 3], 0f, ax, ay, h)
            fillP.color = col(rockColor(MOUNTAIN_NORMALS[2 * f], MOUNTAIN_NORMALS[2 * f + 1])); c.drawPath(path, fillP)
        }
        if (h < SNOW_FROM) return
        val k = 0.3f
        for (i in 0 until 4) {
            val f = faceOrder[i]
            val ax0 = ax + (e[4 * f] - ax) * k; val ay0 = ay + (e[4 * f + 1] - ay) * k
            val ax1 = ax + (e[4 * f + 2] - ax) * k; val ay1 = ay + (e[4 * f + 3] - ay) * k
            poly(ax0, ay0, h * (1f - k), ax1, ay1, h * (1f - k), ax, ay, h)
            fillP.color = col(snowColor(MOUNTAIN_NORMALS[2 * f], MOUNTAIN_NORMALS[2 * f + 1]))
            c.drawPath(path, fillP)
        }
    }

    /** Base edges of the mountain being drawn, as x0, y0, x1, y1 per face (north, west, east, south). */
    private val mountainEdges = FloatArray(16)
    /** Faces 0..3 ordered back to front at the current angle, by [sortFacesBackToFront]. */
    private val faceOrder = IntArray(4)

    /** Orders the four faces with normals [MOUNTAIN_NORMALS] back to front into [faceOrder] (insertion sort, no allocation). */
    private fun sortFacesBackToFront() {
        for (i in 0 until 4) {
            var j = i
            val d = depthOf(MOUNTAIN_NORMALS[2 * i], MOUNTAIN_NORMALS[2 * i + 1])
            while (j > 0 && depthOf(MOUNTAIN_NORMALS[2 * faceOrder[j - 1]], MOUNTAIN_NORMALS[2 * faceOrder[j - 1] + 1]) > d) {
                faceOrder[j] = faceOrder[j - 1]
                j--
            }
            faceOrder[j] = i
        }
    }

    /**
     * Rock color of a face with world normal ([nx], [ny]), by the direction it faces on screen: lit towards the upper
     * left, dark towards the right, blended in between (unturned: north lit, west a bit less, east dark, south mid).
     */
    private fun rockColor(nx: Float, ny: Float) = screenBlend(nx, ny, ROCK_LIT, ROCK_LIT.shade(-0.06f), ROCK_DARK, ROCK_MID)

    private fun snowColor(nx: Float, ny: Float) = screenBlend(nx, ny, SNOW, SNOW, SNOW_SHADE, SNOW_SHADE.shade(0.08f))

    /**
     * Blends [up], [left], [right] and [down] (the colors of a face whose turned normal points to the unturned north,
     * west, east and south) by the direction the face with world normal ([nx], [ny]) points to after turning.
     */
    private fun screenBlend(nx: Float, ny: Float, up: Int, left: Int, right: Int, down: Int): Int {
        val c = camera.cosA; val s = camera.sinA
        val tx = c * nx - s * ny; val ty = s * nx + c * ny
        val horizontal = if (tx >= 0f) right else left
        val vertical = if (ty >= 0f) down else up
        val wx = abs(tx); val wy = abs(ty)
        return blend(vertical, horizontal, if (wx + wy > 0f) wx / (wx + wy) else 0f)
    }

    /** A downtown tower on a paved cell: glass walls with rows of windows, a few of them lit, and a roof box. */
    private fun drawTower(c: Canvas, seed: Long, cell: Cell, open: Boolean) {
        fun col(v: Int) = if (open) v else wash(v)
        val h = towerHeight(seed, cell)
        val cx = cell.x + 0.5f; val cy = cell.y + 0.5f
        val half = TOWER_SIZE / 2f
        val tint = if (Scenery.unit(seed, cell.x, cell.y, 34) < 0.5f) TOWER_A else TOWER_B
        boxRect(c, cx - half, cy - half, cx + half, cy + half, 0f, h, col(tint.shade(0.25f)), col(tint))
        val rows = (h / 0.16f).toInt()
        for (r in 0 until rows) {
            val z = 0.08f + r * 0.16f
            if (z + 0.07f > h - 0.04f) break
            for (i in 0 until 3) {
                val u = -half + 0.05f + i * 0.18f
                val lit = Scenery.unit(seed, cell.x * 7 + i, cell.y * 7 + r, 35) < 0.18f
                fillP.color = col(if (lit) WINDOW_LIT else WINDOW)
                for (f in 0 until 4) if (wallPatch(cx, cy, half, half, f, u, u + 0.12f, z, z + 0.07f)) c.drawPath(path, fillP)
            }
        }
        boxRect(c, cx - 0.12f, cy - 0.14f, cx + 0.08f, cy + 0.04f, h, 0.07f, col(0xFFCBD2D9.toInt()), col(0xFF9AA3AD.toInt()))
    }

    /** Tufts of grass and a few flowers on some bare land cells. */
    private fun drawGrass(c: Canvas, seed: Long, x: Int, y: Int, open: Boolean) {
        val r = Scenery.unit(seed, x, y, 4)
        if (r > 0.34f) {
            // Bare cells get two faint specks of soil, a quiet texture that keeps big meadows from looking flat.
            if (r > 0.7f) return
            fillP.color = (if (open) landB else lockedLandB).shade(-0.07f)
            for (i in 0 until 2) {
                val px = x + 0.15f + 0.7f * Scenery.unit(seed, x, y, 40 + i)
                val py = y + 0.15f + 0.7f * Scenery.unit(seed, x, y, 42 + i)
                oval.set(sx(px, py) - tw * 0.03f, sy(px, py) - th * 0.03f, sx(px, py) + tw * 0.03f, sy(px, py) + th * 0.03f)
                c.drawOval(oval, fillP)
            }
            return
        }
        val px = x + 0.2f + 0.6f * Scenery.unit(seed, x, y, 5)
        val py = y + 0.2f + 0.6f * Scenery.unit(seed, x, y, 6)
        val gx = sx(px, py); val gy = sy(px, py)
        val k = tw * 0.035f
        strokeP.color = (if (open) pal.grass else wash(pal.grass)); strokeP.strokeWidth = tw * 0.012f
        for (i in 0 until 2) {
            val ox = gx + i * k * 1.6f
            c.drawLine(ox - k * 0.6f, gy - k, ox, gy, strokeP)
            c.drawLine(ox, gy - k * 1.3f, ox, gy, strokeP)
            c.drawLine(ox + k * 0.6f, gy - k, ox, gy, strokeP)
        }
        if (r < 0.07f) {
            fillP.color = if (open) (if (r < 0.035f) FLOWER_A else FLOWER_B) else wash(FLOWER_A)
            for (i in 0 until 3) c.drawCircle(gx - k * 1.8f + i * k * 1.3f, gy + k * (0.7f + (i % 2) * 0.5f), tw * 0.011f, fillP)
        }
    }

    /** Soft drop shadow of a node on the ground, cast away from the light (upper left) by its height. */
    private fun drawShadow(c: Canvas, n: Node) {
        val f = n.footprintCenter
        when (n.kind) {
            NodeKind.SERVER -> if (n.isDataCenter) groundShadow(c, f.x, f.y, 1.86f, 1.4f) else groundShadow(c, f.x, f.y, 0.78f, n.level * 0.72f)
            NodeKind.CLIENT -> groundShadow(c, f.x, f.y, 0.5f, 0.45f)
            NodeKind.ROUTER -> groundShadow(c, f.x, f.y, 0.4f, 0.35f)
            NodeKind.ACCESS_POINT -> groundShadow(c, f.x, f.y, 0.36f, 0.45f)
            NodeKind.CELL_TOWER -> {
                groundShadow(c, f.x, f.y, 0.56f, 0.1f)
                groundShadow(c, f.x, f.y, 0.22f, 2.1f)
            }
        }
    }

    /**
     * The ground shadow of an [s]-wide block of height [h] at ([cx], [cy]): its footprint swept away from the light,
     * drawn in a few widening, faint passes so the edge is soft (works on hardware canvases without blur filters).
     */
    private fun groundShadow(c: Canvas, cx: Float, cy: Float, s: Float, h: Float) {
        // The cast is fixed on screen (light from the upper left), so in the world it turns against the map.
        val sdx = minOf(h * 0.6f, 1.4f); val sdy = minOf(h * 0.22f, 0.5f)
        val dx = unturnX(sdx, sdy); val dy = unturnY(sdx, sdy)
        for (pass in SHADOW_SPREAD.indices) {
            val e = SHADOW_SPREAD[pass] * (0.6f + 0.4f * minOf(h, 1.5f))
            val x0 = cx - s / 2 - e; val x1 = cx + s / 2 + e
            val y0 = cy - s / 2 - e; val y1 = cy + s / 2 + e
            // The footprint and the footprint moved by the cast, and the hull around both.
            val q = hullIn
            q[0] = x0; q[1] = y0; q[2] = x1; q[3] = y0; q[4] = x1; q[5] = y1; q[6] = x0; q[7] = y1
            for (k in 0 until 4) { q[8 + 2 * k] = q[2 * k] + dx; q[9 + 2 * k] = q[2 * k + 1] + dy }
            for (k in 0 until 8) { val wx = q[2 * k]; val wy = q[2 * k + 1]; q[2 * k] = sx(wx, wy); q[2 * k + 1] = sy(wx, wy) }
            val n = hull.convex(q, 8, hullOut)
            path.reset()
            for (k in 0 until n) if (k == 0) path.moveTo(hullOut[0], hullOut[1]) else path.lineTo(hullOut[2 * k], hullOut[2 * k + 1])
            path.close()
            fillP.color = SHADOW_ALPHA[pass] shl 24
            c.drawPath(path, fillP)
        }
    }

    private val hullIn = FloatArray(16)
    private val hullOut = FloatArray(18)
    private val hull = Hull()

    /** A small tree, pine, bush or house, jittered inside its cell; washed out outside the unlocked area. */
    private fun drawDecor(c: Canvas, seed: Long, cell: Cell, d: Decor, open: Boolean) {
        val x = cell.x + 0.5f + (Scenery.unit(seed, cell.x, cell.y, 11) - 0.5f) * 0.3f
        val y = cell.y + 0.5f + (Scenery.unit(seed, cell.x, cell.y, 12) - 0.5f) * 0.3f
        val k = 0.85f + 0.3f * Scenery.unit(seed, cell.x, cell.y, 13)
        fun col(v: Int) = if (open) v else wash(v)
        if (d != Decor.HOUSE) {
            groundEllipse(Vec2(x + unturnX(0.13f, 0.05f) * k, y + unturnY(0.13f, 0.05f) * k), 0.17f * k)
            fillP.color = 0x22000000; c.drawOval(oval, fillP)
        }
        val gx = sx(x, y)
        when (d) {
            Decor.TREE -> {
                strokeP.color = col(TRUNK); strokeP.strokeWidth = tw * 0.035f
                c.drawLine(gx, sy(x, y), gx, sy(x, y, 0.16f * k), strokeP)
                val cy = sy(x, y, 0.3f * k); val r = tw * 0.12f * k
                fillP.color = col(pal.leafDark); c.drawCircle(gx, cy, r, fillP)
                fillP.color = col(pal.leaf); c.drawCircle(gx - r * 0.18f, cy - r * 0.18f, r * 0.78f, fillP)
            }
            Decor.PINE -> {
                strokeP.color = col(TRUNK); strokeP.strokeWidth = tw * 0.03f
                c.drawLine(gx, sy(x, y), gx, sy(x, y, 0.1f * k), strokeP)
                for ((z0, z1, half) in listOf(Triple(0.08f, 0.42f, 0.12f), Triple(0.24f, 0.56f, 0.09f))) {
                    val b = sy(x, y, z0 * k); val t = sy(x, y, z1 * k); val hw = tw * half * k
                    path.reset(); path.moveTo(gx, t); path.lineTo(gx + hw, b); path.lineTo(gx - hw, b); path.close()
                    fillP.color = col(pal.pineDark); c.drawPath(path, fillP)
                    path.reset(); path.moveTo(gx, t); path.lineTo(gx, b); path.lineTo(gx - hw, b); path.close()
                    fillP.color = col(pal.pine); c.drawPath(path, fillP)
                }
            }
            Decor.BUSH -> {
                val cy = sy(x, y, 0.06f); val r = tw * 0.065f * k
                fillP.color = col(pal.leafDark)
                c.drawCircle(gx - r * 0.7f, cy, r, fillP); c.drawCircle(gx + r * 0.7f, cy, r, fillP)
                fillP.color = col(pal.leaf); c.drawCircle(gx, cy - r * 0.45f, r * 1.05f, fillP)
            }
            Decor.HOUSE -> drawHouse(c, x, y, k, ::col)
        }
    }

    /** A cottage: light walls with a door and a window, gable roof with the ridge along x. */
    private fun drawHouse(c: Canvas, x: Float, y: Float, k: Float, col: (Int) -> Int) {
        val hx = 0.2f * k; val hy = 0.16f * k
        val x0 = x - hx; val x1 = x + hx; val y0 = y - hy; val y1 = y + hy
        groundShadowRect(c, x0, y0, x1, y1, 0.3f * k)
        val z1 = 0.17f * k; val zr = z1 + 0.14f * k; val ym = y
        val wall = col(HOUSE_WALL)
        boxRect(c, x0, y0, x1, y1, 0f, z1, wall, wall)
        // A door on the south wall, windows on the others; only the walls facing the viewer show them.
        fillP.color = col(HOUSE_DOOR)
        if (wallPatch(x, y, hx, hy, FACE_SOUTH, -0.04f * k, 0.03f * k, 0f, 0.11f * k)) c.drawPath(path, fillP)
        fillP.color = col(HOUSE_WINDOW)
        for (f in 1 until 4) if (wallPatch(x, y, hx, hy, f, -0.06f * k, 0.04f * k, 0.06f * k, 0.12f * k)) c.drawPath(path, fillP)
        val o = 0.03f * k
        val roof = col(HOUSE_ROOF)
        // Gable roof with the ridge along x: the slope facing away first, then the gable ends that face the viewer,
        // then the near slope.
        val northBack = !facing(0f, -1f)
        if (northBack) roofNorth(x0, x1, y0, ym, z1, zr, o) else roofSouth(x0, x1, y1, ym, z1, zr, o)
        fillP.color = roof.shade(0.12f); c.drawPath(path, fillP)
        if (facing(1f, 0f)) {
            poly(x1, y0, z1, x1, y1, z1, x1, ym, zr)
            fillP.color = wall.shade(wallShade(1f, 0f)); c.drawPath(path, fillP)
        }
        if (facing(-1f, 0f)) {
            poly(x0, y0, z1, x0, y1, z1, x0, ym, zr)
            fillP.color = wall.shade(wallShade(-1f, 0f)); c.drawPath(path, fillP)
        }
        if (northBack) roofSouth(x0, x1, y1, ym, z1, zr, o) else roofNorth(x0, x1, y0, ym, z1, zr, o)
        fillP.color = roof; c.drawPath(path, fillP)
    }

    private fun roofNorth(x0: Float, x1: Float, y0: Float, ym: Float, z1: Float, zr: Float, o: Float) =
        poly(x0 - o, y0 - o, z1, x1 + o, y0 - o, z1, x1 + o, ym, zr, x0 - o, ym, zr)

    private fun roofSouth(x0: Float, x1: Float, y1: Float, ym: Float, z1: Float, zr: Float, o: Float) =
        poly(x0 - o, y1 + o, z1, x1 + o, y1 + o, z1, x1 + o, ym, zr, x0 - o, ym, zr)

    private fun groundShadowRect(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, h: Float) =
        groundShadow(c, (x0 + x1) / 2, (y0 + y1) / 2, maxOf(x1 - x0, y1 - y0), h)

    /** Closed polygon through world points given as x, y, z triples, into [path]. */
    private fun poly(vararg p: Float) {
        path.reset()
        for (i in p.indices step 3) {
            val px = sx(p[i], p[i + 1]); val py = sy(p[i], p[i + 1], p[i + 2])
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
    }

    /** [color] faded towards the locked ground, for decorations outside the unlocked area. */
    private fun wash(color: Int) = blend(color, lockedLandA, 0.6f)

    // ---------------------------------------------------------------- animations

    /** Light streaks drifting down the river; two per water cell, each fading in and out on its own phase. */
    private fun drawWaterShimmer(canvas: Canvas, world: World, time: Float) {
        shimmerP.strokeWidth = tw * 0.024f
        val open = world.unlocked
        for (y in 0 until world.rows) for (x in 0 until world.cols) {
            if (!world.water[y][x]) continue
            for (k in 0 until 2) {
                val t = time * SHIMMER_SPEED + Scenery.unit(world.seed, x, y, 20 + k)
                val phase = t - floor(t)
                val a = sin(phase * PI.toFloat())
                val px = x + 0.22f + 0.56f * Scenery.unit(world.seed, x, y, 22 + k)
                val py = y + 0.12f + phase * 0.62f
                val alpha = (a * (if (open.contains(x, y)) 170f else 80f)).toInt()
                shimmerP.color = (alpha shl 24) or 0xFFFFFF
                canvas.drawLine(sx(px, py), sy(px, py), sx(px, py + 0.2f), sy(px, py + 0.2f), shimmerP)
            }
        }
    }

    /** 0..1: how much of [c] is laid, easing out after it was built ([Juice.growth]). */
    private fun growth(world: World, c: Cable): Float = Juice.growth(world.time, c.builtAt, c.layout.length)

    /**
     * After a cable has grown, a ring clicks in on the ground at both of its ends; after an upgrade, a glint runs
     * along it in the new technology's colour and a spark rides its front.
     */
    private fun drawCableJuice(canvas: Canvas, world: World, c: Cable) {
        val land = Juice.landing(world.time, c.builtAt, c.layout.length)
        if (land in 0f..1f) {
            strokeP.color = CableStyles.of(c.type).color and 0xFFFFFF or (Juice.fade(land) shl 24)
            strokeP.strokeWidth = tw * 0.03f * (1f - 0.5f * land)
            for (end in END_POINTS) {
                groundEllipse(if (end == 0) c.a.center else c.b.center, 0.2f + 0.35f * land)
                canvas.drawOval(oval, strokeP)
            }
        }
        val g = Juice.upgrade(world.time, c.upgradedAt, Juice.CABLE_GLINT_SECONDS)
        if (g in 0f..1f) {
            polyline(cablePath(c))
            strokeP.color = 0xFFFFFF or ((Juice.fade(g) * 0.55f).toInt() shl 24)
            strokeP.strokeWidth = tw * (CableStyles.of(c.type).width * 0.75f + 0.1f)
            canvas.drawPath(path, strokeP)
            val p = c.layout.pointAt(g)
            val x = sx(p.x, p.y); val y = sy(p.x, p.y)
            fillP.color = 0xFFFFFFFF.toInt()
            Juice.sparkle(canvas, path, x, y, tw * 0.12f, fillP)
            fillP.color = CableStyles.of(c.type).color
            canvas.drawCircle(x, y, tw * 0.035f, fillP)
        }
    }

    /** Rings on the ground and sparkles rising above a server or access point that was just upgraded. */
    private fun drawUpgradeJuice(canvas: Canvas, world: World, n: Node) {
        val t = Juice.upgrade(world.time, n.upgradedAt)
        if (t !in 0f..1f) return
        val c = n.footprintCenter
        val base = if (n.isDataCenter) 1.1f else 0.45f
        strokeP.strokeWidth = tw * 0.035f * (1f - 0.5f * t)
        val col = n.service?.let(ServiceColors::of) ?: RadioStyles.color(n)
        for (k in 0 until 2) {
            val u = (t * 1.3f - k * 0.3f).coerceIn(0f, 1f)
            if (u <= 0f) continue
            groundEllipse(c, base * (1f + 0.9f * u))
            strokeP.color = col and 0xFFFFFF or (Juice.fade(u) shl 24)
            canvas.drawOval(oval, strokeP)
        }
        val top = if (n.kind == NodeKind.SERVER) (if (n.isDataCenter) 1.2f else n.level * 0.72f) else 0.4f
        Juice.sparkles(canvas, path, sx(c.x, c.y), sy(c.x, c.y, top), t, tw * base * 0.8f, tw * 0.5f, tw * 0.07f, 0xFFFFD34D.toInt(), fillP)
    }

    /** The first [f] of the polyline through [pts], into [path]. */
    private fun partialPolyline(pts: List<Vec2>, f: Float) {
        path.reset()
        var left = Geometry.polylineLength(pts) * f
        path.moveTo(sx(pts[0].x, pts[0].y), sy(pts[0].x, pts[0].y))
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            val seg = hypot(b.x - a.x, b.y - a.y)
            if (seg >= left) {
                val u = if (seg > 0f) left / seg else 0f
                val px = a.x + (b.x - a.x) * u; val py = a.y + (b.y - a.y) * u
                path.lineTo(sx(px, py), sy(px, py))
                return
            }
            path.lineTo(sx(b.x, b.y), sy(b.x, b.y))
            left -= seg
        }
    }

    /** A bright spark at the growing end of a cable being laid. */
    private fun drawCableTip(canvas: Canvas, c: Cable, f: Float) {
        val p = c.layout.pointAt(f)
        val x = sx(p.x, p.y); val y = sy(p.x, p.y)
        fillP.color = 0x66FFFFFF; canvas.drawCircle(x, y, tw * 0.11f, fillP)
        fillP.color = CableStyles.of(c.type).color; canvas.drawCircle(x, y, tw * 0.055f, fillP)
        fillP.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(x, y, tw * 0.03f, fillP)
    }

    /** Rings on the ground where a request reached its server (in the service color) or a response came home. */
    private fun drawArrivalRings(canvas: Canvas, world: World) {
        for (a in world.arrivals) {
            val t = (world.time - a.time) / if (a.isResponse) DELIVERY_POP else SERVER_POP
            if (t !in 0f..1f) continue
            val base = when {
                a.isResponse -> 0.32f
                a.node.isDataCenter -> 1.25f
                else -> 0.55f
            }
            groundEllipse(a.node.footprintCenter, base * (1f + 0.7f * t))
            strokeP.color = ServiceColors.of(a.service) and 0xFFFFFF or ((1f - t) * 220f).toInt().shl(24)
            strokeP.strokeWidth = tw * 0.035f * (1f - 0.5f * t)
            canvas.drawOval(oval, strokeP)
        }
    }

    /** A delivered response rises as a small shape above its device and fades out. */
    private fun drawDeliveryPops(canvas: Canvas, world: World) {
        for (a in world.arrivals) {
            if (!a.isResponse) continue
            val t = (world.time - a.time) / DELIVERY_POP
            if (t !in 0f..1f) continue
            val c = a.node.center
            val x = sx(c.x, c.y); val y = sy(c.x, c.y, 0.2f) - tw * (0.5f + 0.3f * t)
            val alpha = ((1f - t * t) * 255f).toInt().shl(24)
            val r = tw * 0.055f * (0.7f + 0.6f * sin(minOf(t * 3f, 1f) * PI.toFloat() / 2f))
            fillP.color = ServiceColors.of(a.service) and 0xFFFFFF or alpha
            Shapes.draw(canvas, a.service.shape, x, y, r, fillP)
            strokeP.color = 0xFFFFFF or alpha; strokeP.strokeWidth = tw * 0.015f
            Shapes.draw(canvas, a.service.shape, x, y, r, strokeP)
            // A little burst the moment it lands.
            val b = t / 0.45f
            if (b <= 1f) Juice.sparkles(canvas, path, x, y, b, tw * 0.2f, 0f, tw * 0.035f, ServiceColors.of(a.service), fillP, n = 5)
        }
    }

    /** Scale of a server's badge: a short bounce each time a request arrives. */
    private fun badgePop(world: World, n: Node): Float {
        val last = world.arrivals.lastOrNull { !it.isResponse && it.node === n } ?: return 1f
        val t = (world.time - last.time) / BADGE_POP
        return if (t in 0f..1f) 1f + 0.45f * sin(t * PI.toFloat()) else 1f
    }

    /** Game over: red ripples around the device whose queue overflowed. */
    private fun drawFailedPulse(canvas: Canvas, n: Node, time: Float) {
        groundEllipse(n.center, 0.55f)
        fillP.color = alarm and 0xFFFFFF or 0x40000000; canvas.drawOval(oval, fillP)
        strokeP.strokeWidth = tw * 0.04f
        for (k in 0 until 3) {
            val u = time * 0.7f + k / 3f
            val t = u - floor(u)
            groundEllipse(n.center, 0.55f + 1.4f * t)
            strokeP.color = alarm and 0xFFFFFF or ((1f - t) * 210f).toInt().shl(24)
            canvas.drawOval(oval, strokeP)
        }
    }

    /**
     * Radio coverage on the ground: a tinted ellipse per radio (the iso view of its circle) in its channel color, red
     * where two access points on the same channel overlap, and dashed, drifting lines for the links it carries.
     */
    private fun drawRadioCoverage(canvas: Canvas, world: World, time: Float) {
        val radios = radioScratch
        radios.clear()
        for (n in world.nodes) if (n.radius > 0f && !world.isDark(n)) radios += n
        if (radios.isEmpty()) return
        // A tinted disc per radio with a soft glow inside its rim and a crisp border with a light halo outside, so
        // the reach stays clear on grass, water and under other discs.
        val rim = maxOf(tw * 0.028f, RADIO_RIM_MIN_DP * density)
        for (n in radios) {
            val col = RadioStyles.color(n)
            groundEllipse(n.center, n.radius)
            fillP.color = col and 0x00FFFFFF or 0x2A000000; canvas.drawOval(oval, fillP)
            val glow = tw * 0.16f
            oval.inset(glow / 2f, glow / 4f)
            strokeP.color = col and 0x00FFFFFF or 0x26000000; strokeP.strokeWidth = glow; canvas.drawOval(oval, strokeP)
            groundEllipse(n.center, n.radius)
            strokeP.color = 0x8CFFFFFF.toInt(); strokeP.strokeWidth = rim * 2.2f; canvas.drawOval(oval, strokeP)
            strokeP.color = col and 0x00FFFFFF or 0xE6000000.toInt(); strokeP.strokeWidth = rim; canvas.drawOval(oval, strokeP)
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
            strokeP.color = RadioStyles.INTERFERENCE; strokeP.strokeWidth = rim * 1.2f; canvas.drawOval(oval, strokeP)
        }
        val dash = tw * 0.07f
        airP.pathEffect = airDash.get(dash, 0.8f, -time * dash * 4f)
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
            NodeKind.SERVER -> if (n.isDataCenter) drawDataCenter(canvas, n, time, busy, badgePop(world, n)) else {
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
                        val f = rightFace()
                        val px = x + FACES[4 * f] * 0.39f - FACES[4 * f + 2] * 0.2f
                        val py = y + FACES[4 * f + 1] * 0.39f - FACES[4 * f + 3] * 0.2f
                        val lx = sx(px, py); val ly = sy(px, py, lv * unit + 0.2f + i * 0.25f)
                        canvas.drawRect(lx - tw * 0.04f, ly - th * 0.08f, lx + tw * 0.06f, ly + th * 0.04f, fillP)
                    }
                }
                val top = n.level * unit - 0.06f
                val badge = tw * 0.13f * badgePop(world, n)
                strokeP.color = col.shade(-0.25f); strokeP.strokeWidth = badge * 0.3f
                Shapes.draw(canvas, service.shape, sx(x, y), sy(x, y, top), badge, strokeP)
                fillP.color = 0xFFFFFFFF.toInt()
                Shapes.draw(canvas, service.shape, sx(x, y), sy(x, y, top), badge, fillP)
            }
            NodeKind.CLIENT -> {
                val d = n.device!!
                box(canvas, x, y, 0.5f, 0.2f, 0xFFFAFAF7.toInt(), 0xFFE3E6E1.toInt())
                val icon = maxOf(tw * 0.2f, ICON_MIN_DP * density)
                icons.device(canvas, d, sx(x, y), sy(x, y, 0.2f) - icon, icon)
                // Waiting requests in a queue beside the device; a red outline marks one that is stuck (ping, bandwidth).
                val r = maxOf(tw * 0.065f, REQUEST_MIN_DP * density)
                val qx = sx(x, y) + maxOf(tw * 0.35f, icon * 1.5f)
                val qy = sy(x, y, 0.2f) - icon * 2.2f
                var badge: RouteProblem? = null
                for (i in 0 until minOf(n.pending.size, 8)) {
                    val svc = n.pending[i]
                    val px = qx + (i % 4) * r * 2.6f
                    val py = qy + (i / 4) * r * 2.6f
                    // The same white sticker rim as the packets on the cables, so waiting and moving requests match.
                    strokeP.color = 0xFFFFFFFF.toInt(); strokeP.strokeWidth = r * 0.5f
                    Shapes.draw(canvas, svc.shape, px, py, r, strokeP)
                    fillP.color = ServiceColors.of(svc)
                    Shapes.draw(canvas, svc.shape, px, py, r, fillP)
                    val problem = world.routeProblem(n, svc)
                    if (ProblemBadges.shows(problem)) {
                        strokeP.color = alarm; strokeP.strokeWidth = r * 0.35f
                        Shapes.draw(canvas, svc.shape, px, py, r * 1.45f, strokeP)
                        if (badge == null) badge = problem
                    }
                }
                badge?.let { ProblemBadges.draw(canvas, it, sx(x, y) - icon * 1.3f, qy, r * 1.9f) }
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
     * the side away from the viewer so the cut stays visible in front of it, unless a node, cable or decoration is in
     * that cell. A decoration left in the chosen cell is hidden while the excavator stands there ([excavatorCells]).
     */
    private fun excavatorStand(world: World, i: Incident): Pair<Vec2, Vec2> {
        val layout = i.cable!!.layout
        val a = layout.pointAt((i.cutAt - 0.02f).coerceAtLeast(0f))
        val b = layout.pointAt((i.cutAt + 0.02f).coerceAtMost(1f))
        val back = if (abs(b.x - a.x) >= abs(b.y - a.y)) Vec2(0f, 1f) else Vec2(1f, 0f)
        val spot = i.spot
        fun standFor(d: Vec2) = Vec2(spot.x - d.x * 0.62f, spot.y - d.y * 0.62f)
        fun clear(d: Vec2): Boolean {
            val cell = standCell(spot, d)
            return world.nodeAt(cell) == null && world.cables.none { cell in it.layout.cells } &&
                Scenery.planned(world.seed, cell.x, cell.y) == null
        }
        val front = Vec2(-back.x, -back.y)
        val d = if (clear(back) || !clear(front)) back else front
        return standFor(d) to d
    }

    private fun standCell(spot: Vec2, d: Vec2) = Cell(floor(spot.x - d.x).toInt(), floor(spot.y - d.y).toInt())

    /** Cells that excavators currently stand on; the ground layer leaves their decorations out. */
    internal fun excavatorCells(world: World): List<Cell> {
        updateStands(world, networkSignature(world))
        return standCells.toList()
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
        val u = if (depthOf(d.x, d.y) > 0f) -1f else 1f
        val side = if (alongX) Vec2(0.09f * u, 0f) else Vec2(0f, 0.09f * u)
        val lx = if (alongX) 0.28f else 0.2f
        val ly = if (alongX) 0.2f else 0.28f
        groundShadow(canvas, at.x, at.y, 0.5f, 0.44f)
        boxRect(canvas, at.x - lx, at.y - ly, at.x + lx, at.y + ly, 0f, 0.12f, IncidentStyles.EXCAVATOR_DARK.shade(0.25f), IncidentStyles.EXCAVATOR_DARK)
        val cx = at.x - d.x * 0.04f; val cy = at.y - d.y * 0.04f
        val cab = 0.16f
        val cabTop = 0.44f
        boxRect(canvas, cx - cab, cy - cab, cx + cab, cy + cab, 0.12f, cabTop - 0.12f, IncidentStyles.EXCAVATOR.shade(0.2f), IncidentStyles.EXCAVATOR)
        fillP.color = 0xFFBFD6E6.toInt()
        val w0 = if (u < 0f) 0.02f else -cab + 0.03f
        val w1 = if (u < 0f) cab - 0.03f else -0.02f
        // The window sits on whichever long side of the cab faces the viewer.
        val face = if (alongX) (if (facing(0f, 1f)) FACE_SOUTH else FACE_NORTH) else (if (facing(1f, 0f)) FACE_EAST else FACE_WEST)
        if (wallPatch(cx, cy, cab, cab, face, w0, w1, 0.2f, 0.4f)) canvas.drawPath(path, fillP)
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
        val r = maxOf(tw * 0.1f, CHANNEL_BADGE_MIN_DP * density)
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
        /** Inward pull of the legs at height z: they meet towards the top. */
        fun k(z: Float) = 1f - (z - base) / height * 0.8f
        fun legX(corner: Int, z: Float) = x + TOWER_CORNERS[2 * corner] * half * k(z)
        fun legY(corner: Int, z: Float) = y + TOWER_CORNERS[2 * corner + 1] * half * k(z)
        strokeP.color = 0xFF5B6674.toInt(); strokeP.strokeWidth = tw * 0.02f
        val top = base + height
        for (i in 0 until 4) {
            val ax = legX(i, base); val ay = legY(i, base); val bx = legX(i, top); val by = legY(i, top)
            canvas.drawLine(sx(ax, ay), sy(ax, ay, base), sx(bx, by), sy(bx, by, top), strokeP)
        }
        strokeP.strokeWidth = tw * 0.012f
        for (level in 1..4) {
            val z = base + height * level / 5f
            val zPrev = base + height * (level - 1) / 5f
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                val ax = legX(i, z); val ay = legY(i, z); val bx = legX(j, z); val by = legY(j, z)
                val cx = legX(i, zPrev); val cy = legY(i, zPrev)
                canvas.drawLine(sx(ax, ay), sy(ax, ay, z), sx(bx, by), sy(bx, by, z), strokeP)
                canvas.drawLine(sx(cx, cy), sy(cx, cy, zPrev), sx(bx, by), sy(bx, by, z), strokeP)
            }
        }
        val panelZ = top - 0.55f
        // Three antenna panels around the mast, back to front.
        val order = sortedByDepth(TOWER_PANELS, x, y)
        for (k in 0 until 3) {
            val p = order[k]
            box(canvas, x + TOWER_PANELS[2 * p], y + TOWER_PANELS[2 * p + 1], 0.1f, 0.4f, 0xFFF5F7F9.toInt(), 0xFFE3E6E1.toInt(), z0 = panelZ)
        }
        fillP.color = if (sin(time * 2.5f) > 0f) 0xFFE4572E.toInt() else 0xFF8A3A2A.toInt()
        canvas.drawCircle(sx(x, y), sy(x, y, top + 0.05f), tw * 0.04f, fillP)
    }

    /** Tier 4: a wide, low hall over the 2×2 footprint with rack LEDs on both visible walls and cooling on the roof. */
    private fun drawDataCenter(canvas: Canvas, n: Node, time: Float, busy: Boolean, pop: Float) {
        val service = n.service!!
        val col = ServiceColors.of(service)
        val c = n.footprintCenter
        val base = 0.12f
        box(canvas, c.x, c.y, 1.86f, base, 0xFFCBD2D9.toInt(), 0xFFCBD2D9.toInt())
        val s = 1.62f
        val h = 1.05f
        box(canvas, c.x, c.y, s, h, col.shade(0.15f), 0xFFE9ECEF.toInt(), z0 = base)
        val first = -s / 2 + 0.16f
        for (i in 0 until 5) for (row in 0 until 3) {
            val u = first + i * 0.29f
            val z = base + 0.22f + row * 0.26f
            fillP.color = when {
                busy -> alarm
                sin(time * 3f + i * 1.3f + row * 2.1f + n.id) > 0f -> col
                else -> 0xFF9AA3AD.toInt()
            }
            // Rack LEDs on every wall; only those facing the viewer show.
            for (f in 0 until 4) if (wallPatch(c.x, c.y, s / 2, s / 2, f, u, u + 0.17f, z, z + 0.1f)) canvas.drawPath(path, fillP)
        }
        val roof = base + h
        val order = sortedByDepth(ROOF_UNITS, c.x, c.y)
        for (k in 0 until 2) {
            val p = order[k]
            box(canvas, c.x + ROOF_UNITS[2 * p], c.y + ROOF_UNITS[2 * p + 1], 0.42f, 0.16f, 0xFF5B6674.toInt(), 0xFFB9C2CC.toInt(), z0 = roof)
        }
        val bx = sx(c.x + 0.2f, c.y + 0.25f); val by = sy(c.x + 0.2f, c.y + 0.25f, roof)
        oval.set(bx - tw * 0.2f * pop, by - th * 0.2f * pop, bx + tw * 0.2f * pop, by + th * 0.2f * pop)
        fillP.color = 0xFFFFFFFF.toInt(); canvas.drawOval(oval, fillP)
        fillP.color = col
        Shapes.draw(canvas, service.shape, bx, by - th * 0.04f, tw * 0.08f * pop, fillP)
    }

    /**
     * A patch on wall [face] ([FACES]) of a box around ([cx], [cy]) with half sizes [hx] and [hy]: from [ua] to [ub]
     * along the wall and from height [za] to [zb], into [path]. Returns false (and leaves [path] alone) if that wall
     * faces away from the viewer at the current angle.
     */
    private fun wallPatch(cx: Float, cy: Float, hx: Float, hy: Float, face: Int, ua: Float, ub: Float, za: Float, zb: Float): Boolean {
        val nx = FACES[4 * face]; val ny = FACES[4 * face + 1]; val tx = FACES[4 * face + 2]; val ty = FACES[4 * face + 3]
        if (!facing(nx, ny)) return false
        val bx = cx + nx * hx; val by = cy + ny * hy
        val ax = bx + tx * ua; val ay = by + ty * ua
        val ex = bx + tx * ub; val ey = by + ty * ub
        path.reset()
        path.moveTo(sx(ax, ay), sy(ax, ay, za)); path.lineTo(sx(ex, ey), sy(ex, ey, za))
        path.lineTo(sx(ex, ey), sy(ex, ey, zb)); path.lineTo(sx(ax, ay), sy(ax, ay, zb)); path.close()
        return true
    }

    /** The wall of [FACES] that faces most to the right on screen (the east wall when not turned). */
    private fun rightFace(): Int {
        var best = 0
        var bestV = -Float.MAX_VALUE
        for (f in 0 until 4) {
            val nx = FACES[4 * f]; val ny = FACES[4 * f + 1]
            val v = (camera.cosA * nx - camera.sinA * ny) - (camera.sinA * nx + camera.cosA * ny)
            if (v > bestV) { bestV = v; best = f }
        }
        return best
    }

    private val depthOrder = IntArray(4)

    /** Indices of the ([offsets] x, y pairs around ([x], [y])) back to front, in a reused array. */
    private fun sortedByDepth(offsets: FloatArray, x: Float, y: Float): IntArray {
        val n = offsets.size / 2
        for (i in 0 until n) {
            var j = i
            val d = depthOf(x + offsets[2 * i], y + offsets[2 * i + 1])
            while (j > 0 && depthOf(x + offsets[2 * depthOrder[j - 1]], y + offsets[2 * depthOrder[j - 1] + 1]) > d) {
                depthOrder[j] = depthOrder[j - 1]
                j--
            }
            depthOrder[j] = i
        }
        return depthOrder
    }

    private fun box(canvas: Canvas, cx: Float, cy: Float, s: Float, h: Float, top: Int, side: Int, z0: Float = 0f) =
        boxRect(canvas, cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2, z0, h, top, side)

    /**
     * A block over the ground rectangle ([x0], [y0]) – ([x1], [y1]) from height [z0], [h] high: the walls that face the
     * viewer at the current angle (two, or one when looking straight at a wall), shaded by where they face, then the top.
     */
    private fun boxRect(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, z0: Float, h: Float, top: Int, side: Int) {
        val z1 = z0 + h
        for (f in 0 until 4) {
            val nx = FACES[4 * f]; val ny = FACES[4 * f + 1]
            if (!facing(nx, ny)) continue
            // The wall's two base corners, from the corner the tangent starts at.
            val ax: Float; val ay: Float; val bx: Float; val by: Float
            when (f) {
                FACE_SOUTH -> { ax = x0; ay = y1; bx = x1; by = y1 }
                FACE_EAST -> { ax = x1; ay = y0; bx = x1; by = y1 }
                FACE_NORTH -> { ax = x1; ay = y0; bx = x0; by = y0 }
                else -> { ax = x0; ay = y1; bx = x0; by = y0 }
            }
            path.reset()
            path.moveTo(sx(ax, ay), sy(ax, ay, z0)); path.lineTo(sx(bx, by), sy(bx, by, z0))
            path.lineTo(sx(bx, by), sy(bx, by, z1)); path.lineTo(sx(ax, ay), sy(ax, ay, z1)); path.close()
            fillP.color = side.shade(wallShade(nx, ny)); canvas.drawPath(path, fillP)
        }
        quad(x0, y0, x1 - x0, y1 - y0, z1)
        fillP.color = top; canvas.drawPath(path, fillP)
        // A thin bright rim around the top catches the light and keeps every block crisp against its walls.
        edgeP.strokeWidth = tw * 0.012f
        canvas.drawPath(path, edgeP)
    }

    private val edgeP = stroke(0x73FFFFFF)

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
        /** Readable sizes on a phone (docs/PLAN.md P4.3 review): tile width of the automatic framing, and minimum dp of
         *  a device icon's half size, a request's radius, an overload ring and the drag label. */
        const val READABLE_TILE_DP = 36f
        const val ICON_MIN_DP = 7f
        const val REQUEST_MIN_DP = 3.2f
        const val RING_MIN_DP = 2.5f
        const val LABEL_MIN_DP = 13f
        /** Radius of an access point's channel badge never shrinks below this, so the number stays readable. */
        const val CHANNEL_BADGE_MIN_DP = 7f

        /** Kinds of [depth] and [plan] items; decorations are [DECOR] plus their [Decor.ordinal]. */
        const val NODE = 0
        const val EXCAVATOR = 1
        const val PACKET = 2
        const val MOUNTAIN = 3
        const val TOWER = 4
        const val DECOR = 5

        /** The four legs of a cell tower as x, y pairs, clockwise from the back corner. */
        val TOWER_CORNERS = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f)
        /** The two ends of a cable, for loops over them. */
        val END_POINTS = intArrayOf(0, 1)
        /** Offsets of a cell tower's antenna panels and a data center's roof units from their centre. */
        val TOWER_PANELS = floatArrayOf(-0.12f, 0.02f, 0.02f, -0.12f, 0.08f, 0.08f)
        val ROOF_UNITS = floatArrayOf(-0.38f, -0.38f, 0.12f, -0.38f)

        /** Walls of a box as outward normal x, y and the tangent x, y along which patches are measured. */
        val FACES = floatArrayOf(0f, 1f, 1f, 0f, 1f, 0f, 0f, 1f, 0f, -1f, -1f, 0f, -1f, 0f, 0f, -1f)
        const val FACE_SOUTH = 0
        const val FACE_EAST = 1
        const val FACE_NORTH = 2
        const val FACE_WEST = 3
        /** A wall this close to edge-on is left out. */
        const val FACING_EPS = 1e-4f
        /** Outward normals of a mountain's faces in [drawMountain] order: north, west, east, south. */
        val MOUNTAIN_NORMALS = floatArrayOf(0f, -1f, -1f, 0f, 1f, 0f, 0f, 1f)
        /** The board's four edges as x0, y0, x1, y1 in units of the grid size, then the outward normal. */
        val BOARD_EDGES = floatArrayOf(
            0f, 1f, 1f, 1f, 0f, 1f,
            1f, 0f, 1f, 1f, 1f, 0f,
            1f, 0f, 0f, 0f, 0f, -1f,
            0f, 1f, 0f, 0f, -1f, 0f,
        )

        /** Neighbour directions as x, y pairs: east, south, west, north. */
        val NEIGHBOURS = intArrayOf(1, 0, 0, 1, -1, 0, 0, -1)
        /** Share of the other checker color mixed into water and locked tiles: 0.5 would be flat, 0 full contrast. */
        const val SOFT_CHECKER = 0.32f
        /** How far water lies below the land, and the bank's shade and waterline. */
        const val BANK_DEPTH = 0.07f
        const val BANK_SHADE = -0.3f
        const val FOAM = 0xCCFFFFFF.toInt()
        const val FOAM_LOCKED = 0x80FFFFFF.toInt()
        /** Spread (cells) of the passes of the board's shadow on the table, and the alpha of each. */
        val BOARD_SHADOW_SPREAD = floatArrayOf(0.9f, 0.7f, 0.5f, 0.32f, 0.16f, 0.04f)
        const val BOARD_SHADOW_ALPHA = 0x06
        /** Smallest radius of a packet and width of a radio's rim on a phone, in dp. */
        const val PACKET_MIN_DP = 3.6f
        const val RADIO_RIM_MIN_DP = 1.3f

        /** FNV-style step of the map signatures. */
        fun mix(h: Long, v: Int) = (h xor v.toLong()) * 0x100000001B3L

        /** Half of √2: a ground circle of radius r spans r·√2/2 tile widths to each side in iso. */
        const val HALF_SQRT2 = 0.70710677f
        /** Router and access point bases without power. */
        const val DARK_TOP = 0xFF6E7781.toInt()
        const val DARK_SIDE = 0xFF59616B.toInt()
        /** Largest brightness change of a ground tile (see [Scenery.tileVariation]). */
        const val TILE_VARIATION = 0.035f
        /** Outward spread (cells) and alpha of the soft shadow passes, outermost first. */
        val SHADOW_SPREAD = floatArrayOf(0.1f, 0.05f, 0f)
        val SHADOW_ALPHA = intArrayOf(0x10, 0x12, 0x1A)
        const val FLOWER_A = 0xFFFFFFFF.toInt()
        const val FLOWER_B = 0xFFF2D06B.toInt()
        const val TRUNK = 0xFF8A6A4A.toInt()
        const val HOUSE_WALL = 0xFFF4EDE0.toInt()
        const val HOUSE_ROOF = 0xFFC9694F.toInt()
        const val HOUSE_DOOR = 0xFF8A6A4A.toInt()
        const val HOUSE_WINDOW = 0xFFBFD6E6.toInt()
        const val ROCK_A = 0xFFCBC6B8.toInt()
        const val ROCK_B = 0xFFC4BFB0.toInt()
        const val ROCK_LIT = 0xFFB7AE9C.toInt()
        const val ROCK_MID = 0xFF9C9382.toInt()
        const val ROCK_DARK = 0xFF837B6C.toInt()
        const val SNOW = 0xFFFBFCFD.toInt()
        const val SNOW_SHADE = 0xFFD9E0E8.toInt()
        /** Peaks at least this high (in tile widths) get a snow cap. */
        const val SNOW_FROM = 0.72f
        const val PAVEMENT_A = 0xFFD4D7D6.toInt()
        const val PAVEMENT_B = 0xFFCDD1D0.toInt()
        const val TOWER_A = 0xFFA9BACB.toInt()
        const val TOWER_B = 0xFFB9B3C4.toInt()
        const val WINDOW = 0xFFE3ECF4.toInt()
        const val WINDOW_LIT = 0xFFF6DE8E.toInt()
        /** Width of a downtown tower's footprint, in cells. */
        const val TOWER_SIZE = 0.66f
        /** Seconds of the ring where a request reaches a server, of a delivery pop, and of the badge bounce. */
        const val SERVER_POP = 0.7f
        const val DELIVERY_POP = 0.8f
        const val BADGE_POP = 0.3f
        /** Streak cycles per second on the water. */
        const val SHIMMER_SPEED = 0.35f

        /** Linear mix of two opaque colors, [f] = 0 gives [a]. */
        fun blend(a: Int, b: Int, f: Float): Int {
            fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1f - f) + ((b shr shift) and 0xFF) * f).toInt() shl shift
            return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
        }
    }

    private fun polyline(pts: List<Vec2>) {
        path.reset()
        pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(sx(p.x, p.y), sy(p.x, p.y)) else path.lineTo(sx(p.x, p.y), sy(p.x, p.y)) }
    }
}
