package com.mininetworks.game.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.Service
import kotlin.math.sin

/**
 * The servers the player is looking for: those of [services], e.g. what a device being dragged from asks for.
 * [strength] 0..1 fades the highlight in and out. Mutable, so the view renews one instance per frame.
 */
class ServerFocus(var services: Set<Service>, var strength: Float)

/**
 * "Which device needs which server" on the map (playtest: players took the data center for the server of PCs and the
 * telephone exchange for the one of phones). Every service has its own server type; a data center is only the top
 * tier of any of them. Two things say so, shared by all styles:
 *
 *  - A name plate per server: the service's request token (the same disc, pictogram and colour a device's waiting
 *    request shows) and the server's name ("Mail-Server", "Telefonzentrale"); a data center adds its stage
 *    ("Mail-Server · Rechenzentrum"), so it reads as an upgrade of that server, not as a type of its own.
 *  - A [focus]: matching servers get a pulsing ring on the ground in the service colour and a stronger plate, the
 *    others are dimmed ([dimAlpha]).
 *
 * Plates must never clutter the map. A renderer collects per frame ([begin]) the screen boxes of everything that
 * carries information ([obstacle]: buildings, device icons, request bubbles, badges, the drag label) and one [add]
 * per server with its building's box; the view [reserve]s the HUD. [draw] then places each plate in the first free
 * spot (see [plate]: around the building, slid to a side, a data center's plate also stacked in two lines or on its
 * own front), drawn above everything on the map with a pointer to its building. A plate that finds no free spot is
 * left out (the roof sign still names the service), unless its server is in focus: then it takes the spot that
 * covers least. Plates fade out below [FADE_FROM_DP] of cell width and are gone below [FADE_TO_DP] ([visibility]),
 * except in focus. Text size follows the system font size ([textPx]); the colorblind palette recolours the token.
 */
class ServerLabels {
    /** Plate texts; null (renderer-only tests) draws no plates. */
    var names: Names? = null

    /** The servers to highlight this frame, or null. */
    var focus: ServerFocus? = null

    /** Text size of a plate in pixels; 0 means [DEFAULT_SP] at the renderer's density. */
    var textPx = 0f

    /** [server] names each service's server; [dataCenter] is the stage a server reaches at the top tier. */
    class Names(val server: Map<Service, String>, val dataCenter: String)

    /** True if [n] is a server of a service in [focus]. */
    fun matches(n: Node) = n.kind == NodeKind.SERVER && focus?.services?.contains(n.service) == true

    /** Alpha (0..255) to draw server [n] with: dimmed while a focus is on that does not include it. */
    fun dimAlpha(n: Node): Int {
        val f = focus ?: return 255
        if (n.kind != NodeKind.SERVER || n.service in f.services) return 255
        return (255 - DIM * f.strength).toInt()
    }

    /** 0..1 phase of the focus pulse: one ring every [PULSE_SECONDS]. */
    fun pulse(time: Float) = (time / PULSE_SECONDS).let { it - kotlin.math.floor(it) }

    /**
     * How much of the plates shows at a cell width of [cellPx] pixels: 1 from [FADE_FROM_DP], 0 below [FADE_TO_DP]
     * (a zoomed-out map would be all plates).
     */
    fun visibility(cellPx: Float, density: Float) = ((cellPx / density - FADE_TO_DP) / (FADE_FROM_DP - FADE_TO_DP)).coerceIn(0f, 1f)

    // ---------------------------------------------------------------- per-frame layout

    private var obstacles = FloatArray(4 * 64)
    /** Per obstacle: true for what a plate must never touch (bubbles, badges, labels), false for a building's body. */
    private var hard = BooleanArray(64)
    private var obstacleCount = 0
    private val servers = ArrayList<Node>()
    private var boxes = FloatArray(4 * 16)
    /** Index in [obstacles] of each server's own building, which its plate may touch. */
    private var ownObstacle = IntArray(16)
    private val placed = ArrayList<RectF>()
    private var placedCount = 0

    private val reserved = ArrayList<RectF>()
    private var reservedCount = 0

    /**
     * Screen boxes the view draws over the map (HUD pills, buttons, the hint), kept from frame to frame: plates never
     * go there. [clearReserved], then [reserve] each box, e.g. after drawing the HUD.
     */
    fun clearReserved() {
        reservedCount = 0
    }

    fun reserve(box: RectF) {
        if (reservedCount == reserved.size) reserved += RectF()
        reserved[reservedCount++].set(box)
    }

    /** Starts a frame: forgets the obstacles and servers of the last one. */
    fun begin() {
        obstacleCount = 0
        servers.clear()
    }

    /**
     * A screen box ([l], [t], [r], [b]) that plates keep clear of: completely if [hardEdge] (a bubble, badge or label
     * would lose what it says, a device would hide), else, if no spot is clear of everything, up to [SOFT_OVERLAP]
     * of a plate may lie over it (the edge of a building, whose box is looser than its drawing).
     */
    fun obstacle(l: Float, t: Float, r: Float, b: Float, hardEdge: Boolean = false) {
        if (names == null) return
        if (4 * obstacleCount + 4 > obstacles.size) obstacles = obstacles.copyOf(obstacles.size * 2)
        if (obstacleCount == hard.size) hard = hard.copyOf(hard.size * 2)
        obstacles[4 * obstacleCount] = l; obstacles[4 * obstacleCount + 1] = t
        obstacles[4 * obstacleCount + 2] = r; obstacles[4 * obstacleCount + 3] = b
        hard[obstacleCount] = hardEdge
        obstacleCount++
    }

    /** Server [n] whose building (sign included) covers the screen box ([l], [t], [r], [b]); also an obstacle. */
    fun add(n: Node, l: Float, t: Float, r: Float, b: Float) {
        if (names == null) return
        val k = servers.size
        if (4 * k + 4 > boxes.size) boxes = boxes.copyOf(boxes.size * 2)
        boxes[4 * k] = l; boxes[4 * k + 1] = t; boxes[4 * k + 2] = r; boxes[4 * k + 3] = b
        if (k == ownObstacle.size) ownObstacle = ownObstacle.copyOf(k * 2)
        ownObstacle[k] = obstacleCount
        servers += n
        obstacle(l, t, r, b)
    }

    private val shadowP = fill(0x2B000000)
    private val plateP = fill(0)
    private val rimP = stroke(0)
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val ringP = stroke(0)
    private val tail = Path()
    private val rect = RectF()
    private val candidate = RectF()
    /** Where plates may go: the view inside the HUD's insets. */
    private val area = RectF()

    /**
     * Places and draws the plates of the servers [add]ed this frame, at [visibility] (see [visibility]), inside
     * [camera]'s view minus its insets (never under the HUD); servers in [focus] come first and always show.
     */
    fun draw(canvas: Canvas, camera: Camera, visibility: Float, density: Float, time: Float) {
        val m = EDGE_DP * density
        area.set(camera.insets.left + m, camera.insets.top + m, camera.viewWidth - camera.insets.right - m, camera.viewHeight - camera.insets.bottom - m)
        val names = names ?: return
        val f = focus
        if (visibility <= 0f && (f == null || f.strength <= 0f)) return
        val size = if (textPx > 0f) textPx else DEFAULT_SP * density
        placedCount = 0
        // Focused servers claim their spots first; the rest keep the order of the world.
        for (pass in 0..1) for (k in servers.indices) {
            val n = servers[k]
            val focused = matches(n)
            if (focused != (pass == 0)) continue
            val alpha = when {
                focused -> maxOf(visibility, f!!.strength)
                f != null -> visibility * (1f - DIM_PLATES * f.strength)
                else -> visibility
            }
            if (alpha <= 0.02f) continue
            plate(canvas, n, names, k, size, density, alpha, if (focused) f!!.strength else 0f, time)
        }
    }

    /**
     * Places and draws the plate of server [n] (the [k]-th [add]ed) at [alpha]; [focus] > 0 marks a server in focus:
     * a rim in its colour and a spot even where none is free.
     */
    private fun plate(canvas: Canvas, n: Node, names: Names, k: Int, size: Float, density: Float, alpha: Float, focus: Float, time: Float) {
        val service = n.service ?: return
        val name = names.server[service] ?: return
        val stage = if (n.isDataCenter) names.dataCenter else null
        textP.textSize = size
        textP.typeface = Typeface.DEFAULT_BOLD
        val nameW = textP.measureText(name)
        textP.typeface = Typeface.DEFAULT
        val stageW = stage?.let { textP.measureText(SEPARATOR + it) } ?: 0f
        val stackedW = stage?.let { textP.measureText(it) } ?: 0f
        val gap = size * 0.64f
        val l = boxes[4 * k]; val t = boxes[4 * k + 1]; val r = boxes[4 * k + 2]; val b = boxes[4 * k + 3]
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        // Token centred in a square at the left end (same margin around it as above and below), then the text: in one
        // line, or for a data center where that does not fit, the stage under the name (narrower, taller).
        fun width(stacked: Boolean) = size * 2f * 0.81f + GAP * size + (if (stacked) maxOf(nameW, stackedW) else nameW + stageW) + size * 0.84f
        fun height(stacked: Boolean) = if (stacked) size * 3.3f else size * 2f
        // Under the building, above its sign, right of it, left of it, then under and above again with the plate slid
        // to one side (its pointer still at the building), last on a data center's own front (hiding only itself): the
        // first spot clear of everything, in one line, else stacked; else the first that only touches the edge of a
        // building ([SOFT_OVERLAP]); a server in focus takes the least covering spot.
        var best = -1
        var bestStacked = false
        var bestCost = Float.MAX_VALUE
        var firstSoft = -1
        var softStacked = false
        search@ for (stacked in if (stage != null) STACKINGS else FLAT_ONLY) {
            val w = width(stacked); val h = height(stacked)
            // Only a data center's wide hall has room for its plate on its own front; a rack server would vanish.
            for (s in 0 until if (n.isDataCenter) CANDIDATES else ON) {
                spot(s, cx, cy, l, t, r, b, w, h, gap, w / 2f - size * 1.6f)
                val cost = cost(candidate, k, density)
                if (cost == 0f) { best = s; bestStacked = stacked; bestCost = 0f; break@search }
                if (firstSoft < 0 && cost <= candidate.width() * candidate.height() * SOFT_OVERLAP) { firstSoft = s; softStacked = stacked }
                if (cost < bestCost) { best = s; bestStacked = stacked; bestCost = cost }
            }
        }
        val s: Int
        val stacked: Boolean
        when {
            bestCost == 0f -> { s = best; stacked = bestStacked }
            firstSoft >= 0 -> { s = firstSoft; stacked = softStacked }
            focus > 0f && bestCost < Float.MAX_VALUE -> { s = best; stacked = bestStacked }
            else -> return
        }
        val w = width(stacked); val h = height(stacked)
        spot(s, cx, cy, l, t, r, b, w, h, gap, w / 2f - size * 1.6f)
        val side = when (s) { 4, 5 -> BELOW; 6, 7 -> ABOVE; 9, 10 -> ON; else -> s }
        claim(candidate)
        val a = (alpha * 255f).toInt().coerceIn(0, 255)
        val col = ServiceColors.of(service)
        rect.set(candidate)
        val radius = size
        // A pointer from the plate to its building, so a plate beside a crowd of buildings still says whose it is.
        tail.reset()
        val tip = size * 0.52f
        when (side) {
            BELOW -> { tail.moveTo(cx - tip, rect.top + 1f); tail.lineTo(cx, rect.top - tip); tail.lineTo(cx + tip, rect.top + 1f) }
            ABOVE -> { tail.moveTo(cx - tip, rect.bottom - 1f); tail.lineTo(cx, rect.bottom + tip); tail.lineTo(cx + tip, rect.bottom - 1f) }
            RIGHT -> { tail.moveTo(rect.left + 1f, cy - tip); tail.lineTo(rect.left - tip, cy); tail.lineTo(rect.left + 1f, cy + tip) }
            ON -> Unit
            else -> { tail.moveTo(rect.right - 1f, cy - tip); tail.lineTo(rect.right + tip, cy); tail.lineTo(rect.right - 1f, cy + tip) }
        }
        tail.close()
        shadowP.alpha = (0x2B * alpha).toInt()
        canvas.save()
        canvas.translate(0f, 1.5f * density)
        canvas.drawRoundRect(rect, radius, radius, shadowP)
        canvas.restore()
        plateP.color = withAlpha(PLATE, a)
        canvas.drawPath(tail, plateP)
        canvas.drawRoundRect(rect, radius, radius, plateP)
        // Neutral rim; in focus a rim in the service colour that breathes with the ground ring.
        if (focus > 0f) {
            val beat = 0.5f + 0.5f * sin(pulse(time) * 2f * Math.PI.toFloat())
            rimP.color = withAlpha(col, a)
            rimP.strokeWidth = (1.6f + 0.8f * beat * focus) * density
        } else {
            rimP.color = withAlpha(RIM, a)
            rimP.strokeWidth = 1f * density
        }
        canvas.drawRoundRect(rect, radius, radius, rimP)
        // The token: the very request a device shows for this service.
        val tokenR = size * 0.62f
        val tx = rect.left + size
        ServiceGlyphs.token(canvas, service, tx, rect.centerY(), tokenR, alpha = a, rim = false)
        val base = if (stacked) rect.centerY() - size * 0.2f else rect.centerY() + size * 0.36f
        var x = tx + tokenR + GAP * size
        textP.typeface = Typeface.DEFAULT_BOLD
        textP.color = withAlpha(INK, a)
        canvas.drawText(name, x, base, textP)
        stage?.let {
            textP.typeface = Typeface.DEFAULT
            textP.color = withAlpha(MUTED, a)
            if (stacked) canvas.drawText(it, x, base + size * 1.2f, textP) else canvas.drawText(SEPARATOR + it, x + nameW, base, textP)
        }
    }

    /** Candidate spot [s] of [plate] into [candidate], around the building box ([l], [t], [r], [b]). */
    private fun spot(s: Int, cx: Float, cy: Float, l: Float, t: Float, r: Float, b: Float, w: Float, h: Float, gap: Float, slide: Float) {
        val dx = when (s) { 4, 6, 9 -> slide; 5, 7, 10 -> -slide; else -> 0f }
        when (s) {
            0, 4, 5 -> candidate.set(cx - w / 2f + dx, b + gap, cx + w / 2f + dx, b + gap + h)
            1, 6, 7 -> candidate.set(cx - w / 2f + dx, t - gap - h, cx + w / 2f + dx, t - gap)
            RIGHT -> candidate.set(r + gap, cy - h / 2f, r + gap + w, cy + h / 2f)
            ON, 9, 10 -> candidate.set(cx - w / 2f + dx, b - h - gap * 2f, cx + w / 2f + dx, b - gap * 2f)
            else -> candidate.set(l - gap - w, cy - h / 2f, l - gap, cy + h / 2f)
        }
    }

    /**
     * How much plate [c] would cover: 0 when it is clear of every plate so far and every obstacle but server [self]'s
     * own building; the area it lies over soft obstacles ([obstacle]); hard obstacles and other plates count
     * [HARD_WEIGHT] times their area and put it past any soft tolerance. Outside the [area]: [Float.MAX_VALUE].
     */
    private fun cost(c: RectF, self: Int, density: Float): Float {
        if (c.left < area.left || c.top < area.top || c.right > area.right || c.bottom > area.bottom) return Float.MAX_VALUE
        val penalty = c.width() * c.height()
        var cost = 0f
        for (i in 0 until obstacleCount) {
            if (i == ownObstacle[self]) continue
            val l = obstacles[4 * i]; val t = obstacles[4 * i + 1]; val r = obstacles[4 * i + 2]; val b = obstacles[4 * i + 3]
            if (c.left >= r || c.right <= l || c.top >= b || c.bottom <= t) continue
            val a = (minOf(c.right, r) - maxOf(c.left, l)) * (minOf(c.bottom, b) - maxOf(c.top, t))
            cost += if (hard[i]) penalty + a * HARD_WEIGHT else a
        }
        val pad = PLATE_GAP_DP * density
        // HUD boxes are those of its texts and buttons; the pills behind the texts reach further.
        val hud = HUD_PAD_DP * density
        for (i in 0 until reservedCount) {
            val p = reserved[i]
            if (c.left < p.right + hud && c.right > p.left - hud && c.top < p.bottom + hud && c.bottom > p.top - hud) return Float.MAX_VALUE
        }
        for (i in 0 until placedCount) {
            val p = placed[i]
            if (c.left >= p.right + pad || c.right <= p.left - pad || c.top >= p.bottom + pad || c.bottom <= p.top - pad) continue
            cost += penalty + (minOf(c.right, p.right) - maxOf(c.left, p.left)).coerceAtLeast(0f) * (minOf(c.bottom, p.bottom) - maxOf(c.top, p.top)).coerceAtLeast(0f) * HARD_WEIGHT
        }
        return cost
    }

    private fun claim(c: RectF) {
        if (placedCount == placed.size) placed += RectF()
        placed[placedCount++].set(c)
    }

    /**
     * The focus ring of a matching server on the ground: a soft disc in the service colour, a crisp rim and a ring
     * running outwards. [oval] is the ground ellipse of the ring's rest size (a circle in the flat style); [ring] is
     * the rim's stroke width in pixels. Leaves [oval] as it was.
     */
    fun drawRing(canvas: Canvas, n: Node, oval: RectF, ring: Float, time: Float) {
        val f = focus ?: return
        val col = ServiceColors.of(n.service ?: return)
        val s = f.strength
        ringP.style = Paint.Style.FILL
        ringP.color = withAlpha(col, (0x3A * s).toInt())
        canvas.drawOval(oval, ringP)
        ringP.style = Paint.Style.STROKE
        ringP.color = withAlpha(0xFFFFFFFF.toInt(), (0xD0 * s).toInt())
        ringP.strokeWidth = ring * 2.2f
        canvas.drawOval(oval, ringP)
        ringP.color = withAlpha(col, (0xFF * s).toInt())
        ringP.strokeWidth = ring
        canvas.drawOval(oval, ringP)
        val t = pulse(time)
        val cx = oval.centerX(); val cy = oval.centerY()
        val hw = oval.width() / 2f * (1f + 0.55f * t); val hh = oval.height() / 2f * (1f + 0.55f * t)
        rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
        ringP.color = withAlpha(col, ((1f - t) * 0xE0 * s).toInt())
        ringP.strokeWidth = ring * (1f - 0.4f * t)
        canvas.drawOval(rect, ringP)
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0xFFFFFF) or ((((color ushr 24) * alpha.coerceIn(0, 255)) / 255) shl 24)

    companion object {
        /** Plate text size in sp before the system font size and the tablet scale. */
        const val DEFAULT_SP = 11f
        /** Cell widths (dp) at which plates show fully and at which they are gone. */
        const val FADE_FROM_DP = 46f
        const val FADE_TO_DP = 34f
        /** How much a server outside the focus is dimmed (alpha units) and how much of its plate fades. */
        const val DIM = 120f
        const val DIM_PLATES = 0.6f
        const val PULSE_SECONDS = 1.1f
        /** Room kept between two plates and to the canvas edge. */
        const val PLATE_GAP_DP = 3f
        const val EDGE_DP = 4f
        /** Room kept around a [reserve]d HUD box. */
        const val HUD_PAD_DP = 16f
        /** Gap between the token and the name, in text sizes. */
        const val GAP = 0.4f
        const val SEPARATOR = " · "
        const val PLATE = 0xF7FFFFFF.toInt()
        const val RIM = 0x33262B33
        const val INK = 0xFF262B33.toInt()
        const val MUTED = 0xFF5B6674.toInt()
        /** Share of a plate that may lie over soft obstacles (building and device bodies). */
        const val SOFT_OVERLAP = 0.18f
        /** How much more an area over a hard obstacle or another plate counts than one over a building's edge. */
        const val HARD_WEIGHT = 4f
        /** Spots tried per plate, see [plate]. */
        private const val CANDIDATES = 11
        /** A data center's plate tries one line first, then the stage under the name; others only one line. */
        private val STACKINGS = booleanArrayOf(false, true)
        private val FLAT_ONLY = booleanArrayOf(false)
        private const val BELOW = 0
        private const val ABOVE = 1
        private const val RIGHT = 2
        private const val ON = 8
    }
}
