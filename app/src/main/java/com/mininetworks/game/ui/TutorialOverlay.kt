package com.mininetworks.game.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.mininetworks.game.R
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.Tutorial
import com.mininetworks.game.game.TutorialFocus
import com.mininetworks.game.game.TutorialStep
import com.mininetworks.game.game.Vec2
import com.mininetworks.game.game.World
import com.mininetworks.game.render.Renderer
import com.mininetworks.game.render.fill
import com.mininetworks.game.render.shade
import kotlin.math.PI
import kotlin.math.sin

/**
 * Draws the [Tutorial] over the running game: a panel at the left (step, title, short text and "Skip", or the two
 * buttons at the end) in the look of the menu cards, beside the map, and a pulsing highlight on what the step is about: rings around
 * nodes, a dashed path with a moving finger for a cable to drag, a glow along cables, or a frame around a HUD button.
 * [hit] maps a tap in the bubble to [SKIP], [PLAY], [MENU] or [BUBBLE]; it is valid for the last drawn frame.
 */
class TutorialOverlay(private val context: Context) {
    private val scale = TextScale.of(context)
    private val density = scale.density
    private val ink = 0xFF262B33.toInt()
    private val muted = 0xFF5B6674.toInt()
    private val accent = 0xFF3BA55C.toInt()
    private val fillP = fill(0)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val bubble = RectF()
    private val r = RectF()
    private val targets = ArrayList<Pair<RectF, String>>()
    private val drawnNodes = ArrayList<UiNode>()

    /** Where the panel starts: below the HUD's top rows ([place]); its left edge follows the display cutout. */
    private var panelTop = TOP_DP * scale.density
    private var panelLeft = LEFT_DP * scale.density
    private var panelBottom = Float.MAX_VALUE

    /**
     * Keeps the panel below [top], right of [left] and above [bottom] (px): below the date, clear of a display cutout
     * and above the cable buttons. Text that would not fit shrinks, but never below its default size.
     */
    fun place(left: Float, top: Float, bottom: Float = Float.MAX_VALUE) {
        panelLeft = left
        panelTop = top
        panelBottom = bottom
    }

    /** Text and buttons of the bubble in the last drawn frame, for accessibility services and tests. */
    val nodes: List<UiNode> get() = drawnNodes

    /** Width of the panel on a screen [width] px wide; larger text makes it wider, up to half the screen. */
    private fun panelWidth(width: Int): Float {
        val grow = scale.factor(BODY_SP)
        return (width * 0.36f * grow).coerceIn(MIN_WIDTH_DP * density, maxOf(MIN_WIDTH_DP * density, minOf(MAX_WIDTH_DP * density * grow, width * 0.5f)))
    }

    /** Right edge of the panel on a screen [width] px wide, so the map can be framed beside it. */
    fun reservedRight(width: Int): Float = panelLeft + panelWidth(width)

    /** The bubble entry under ([x], [y]): a button id, [BUBBLE] elsewhere on the bubble, or null off it. */
    fun hit(x: Float, y: Float): String? {
        if (!bubble.contains(x, y)) return null
        return targets.firstOrNull { it.first.contains(x, y) }?.second ?: BUBBLE
    }

    /** Where button [id] was drawn in the last frame, for tests. */
    fun targetOf(id: String): RectF? = targets.firstOrNull { it.second == id }?.first

    /**
     * Draws the highlight for [focus] and the bubble for [tutorial]'s step. [hudButton] returns the screen rectangle of
     * a HUD button by id ("router" for the router tile of the toolbar, "cable:DSL" ...).
     */
    fun draw(
        canvas: Canvas,
        tutorial: Tutorial,
        focus: TutorialFocus,
        renderer: Renderer,
        world: World,
        hudButton: (String) -> RectF?,
        width: Int,
        time: Float,
        pressed: String?,
    ) {
        val pulse = 0.5f + 0.5f * sin(time * 2f * PI.toFloat() / PULSE_SECONDS)
        when (focus) {
            is TutorialFocus.Drag -> {
                val layout = world.planLayout(focus.from, focus.to)
                drawPath(canvas, layout.waypoints.map(renderer::toScreen), dashed = true)
                val f = (time % DRAG_SECONDS) / DRAG_SECONDS
                val finger = renderer.toScreen(layout.pointAt(minOf(1f, f * 1.25f)))
                // A device whose overload ring fills keeps it visible: no highlight ring on top.
                if (focus.from.overload <= 0f) nodeRing(canvas, renderer.toScreen(focus.from.center), renderer, pulse)
                nodeRing(canvas, renderer.toScreen(focus.to.center), renderer, pulse)
                fillP.color = 0x66FFFFFF
                canvas.drawCircle(finger.x, finger.y, 12 * density, fillP)
                fillP.color = HIGHLIGHT
                canvas.drawCircle(finger.x, finger.y, 7 * density, fillP)
            }
            is TutorialFocus.Nodes -> focus.nodes.forEach { nodeRing(canvas, renderer.toScreen(it.center), renderer, pulse) }
            is TutorialFocus.Cables -> focus.cables.forEach { c ->
                drawPath(canvas, c.layout.waypoints.map(renderer::toScreen), dashed = false, glow = pulse)
            }
            TutorialFocus.RouterButton -> hudButton("router")?.let { buttonFrame(canvas, it, pulse) }
            is TutorialFocus.CableButton -> hudButton("cable:${focus.type.name}")?.let { buttonFrame(canvas, it, pulse) }
            TutorialFocus.None -> Unit
        }
        drawBubble(canvas, tutorial, focus, width, pressed)
    }

    private fun nodeRing(canvas: Canvas, c: Vec2, renderer: Renderer, pulse: Float) {
        val radius = maxOf(0.6f * renderer.unitPx, 16 * density) * (1f + 0.12f * pulse)
        ring.pathEffect = null
        ring.strokeWidth = 6 * density
        ring.color = 0x99FFFFFF.toInt()
        canvas.drawCircle(c.x, c.y, radius, ring)
        ring.strokeWidth = 3 * density
        ring.color = HIGHLIGHT
        canvas.drawCircle(c.x, c.y, radius, ring)
    }

    private fun drawPath(canvas: Canvas, pts: List<Vec2>, dashed: Boolean, glow: Float = 0f) {
        path.reset()
        pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        ring.pathEffect = null
        ring.strokeWidth = (if (dashed) 8f else 12f + 6f * glow) * density
        ring.color = if (dashed) 0x88FFFFFF.toInt() else 0x66FFFFFF
        canvas.drawPath(path, ring)
        ring.strokeWidth = (if (dashed) 3.5f else 5f) * density
        ring.color = HIGHLIGHT
        if (dashed) ring.pathEffect = DashPathEffect(floatArrayOf(10 * density, 8 * density), 0f)
        canvas.drawPath(path, ring)
        ring.pathEffect = null
    }

    private fun buttonFrame(canvas: Canvas, b: RectF, pulse: Float) {
        val grow = (4f + 4f * pulse) * density
        r.set(b.left - grow, b.top - grow, b.right + grow, b.bottom + grow)
        ring.pathEffect = null
        ring.strokeWidth = 4 * density
        ring.color = HIGHLIGHT
        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, ring)
    }

    private fun drawBubble(canvas: Canvas, t: Tutorial, focus: TutorialFocus, width: Int, pressed: String?) {
        targets.clear()
        drawnNodes.clear()
        val pad = 14 * density
        val left = panelLeft
        val top = panelTop
        bubble.set(left, top, left + panelWidth(width), top)
        val inner = bubble.width() - 2 * pad
        val done = t.finished
        val body = body(t, focus)
        // Large text first; if the panel would run into the buttons below, all its text shrinks step by step.
        var q = 1f
        var bodySize = 0f
        var lines: List<String> = emptyList()
        var lineH = 0f
        var kickerSize = 0f
        var titleSize = 0f
        var kickerH = 0f
        var titleH = 0f
        var footerH = 0f
        val floor = BODY_SP * density * 0.95f
        while (true) {
            bodySize = scale.px(BODY_SP) * q
            text.typeface = Typeface.DEFAULT
            text.textSize = bodySize
            lines = wrap(body, inner)
            lineH = bodySize * 1.32f
            kickerSize = scale.px(12f) * q
            titleSize = scale.px(18f) * q
            kickerH = kickerSize * 1.35f
            titleH = titleSize * 1.45f
            footerH = maxOf(TOUCH_DP * density, scale.px(15f) * q + 20 * density)
            bubble.bottom = top + pad + kickerH + titleH + lines.size * lineH + 12 * density + footerH + pad
            if (bubble.bottom <= panelBottom || bodySize * 0.95f < floor) break
            q *= 0.95f
        }

        // A card on a slab, like the menu cards.
        val depth = 5 * density
        val corner = 16 * density
        fillP.color = 0x26000000
        canvas.drawRoundRect(bubble.left + depth, bubble.top + depth * 2f, bubble.right + depth, bubble.bottom + depth * 2f, corner, corner, fillP)
        fillP.color = 0xFFE3E6E1.toInt().shade(-0.2f)
        canvas.drawRoundRect(bubble.left, bubble.top + depth, bubble.right, bubble.bottom + depth, corner, corner, fillP)
        fillP.color = 0xFFFAFAF7.toInt()
        canvas.drawRoundRect(bubble, corner, corner, fillP)

        var y = top + pad
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = kickerSize
        text.color = HIGHLIGHT
        val kicker = context.getString(R.string.tutorial_kicker, t.number, Tutorial.STEPS)
        canvas.drawText(kicker, bubble.left + pad, y + kickerH * 0.75f, text)
        // Progress: one dot per step, right of the kicker.
        for (i in 0 until Tutorial.STEPS) {
            fillP.color = if (i < t.number || done) HIGHLIGHT else 0xFFD5DAD2.toInt()
            canvas.drawCircle(bubble.right - pad - 3 * density - (Tutorial.STEPS - 1 - i) * 11 * density, y + kickerH * 0.5f, 3 * density, fillP)
        }
        y += kickerH
        text.textSize = titleSize
        text.color = ink
        val title = if (done) context.getString(R.string.tutorial_done_title) else title(t.step)
        canvas.drawText(fit(title, inner), bubble.left + pad, y + titleH * 0.72f, text)
        drawnNodes += UiNode("tutorial:title", RectF(bubble.left, top, bubble.right, y + titleH), "$kicker. $title", UiNode.Kind.HEADING)
        y += titleH
        text.typeface = Typeface.DEFAULT
        text.textSize = bodySize
        text.color = muted
        val bodyTop = y
        for (line in lines) {
            y += lineH
            canvas.drawText(line, bubble.left + pad, y - lineH * 0.28f, text)
        }
        drawnNodes += UiNode("tutorial:text", RectF(bubble.left, bodyTop, bubble.right, y), body, UiNode.Kind.TEXT)
        y += 12 * density
        if (done) {
            val gap = 10 * density
            val w = (inner - gap) / 2f
            button(canvas, RectF(bubble.left + pad, y, bubble.left + pad + w, y + footerH), context.getString(R.string.tutorial_play), PLAY, primary = true, pressed, q)
            button(canvas, RectF(bubble.right - pad - w, y, bubble.right - pad, y + footerH), context.getString(R.string.menu_main), MENU, primary = false, pressed, q)
        } else {
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = scale.px(13f) * q
            val label = context.getString(R.string.tutorial_skip)
            val w = minOf(maxOf(TOUCH_DP * density, text.measureText(label) + 28 * density), inner)
            r.set(bubble.right - pad - w, y, bubble.right - pad, y + footerH)
            fillP.color = if (pressed == SKIP) 0xFFE6EAE3.toInt() else 0xFFF1F3EE.toInt()
            canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, fillP)
            text.color = muted
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(fit(label, w - 8 * density), r.centerX(), r.centerY() + text.textSize * 0.35f, text)
            text.textAlign = Paint.Align.LEFT
            targets += RectF(r) to SKIP
            drawnNodes += UiNode("tutorial:$SKIP", RectF(r), label, UiNode.Kind.BUTTON)
        }
    }

    private fun button(canvas: Canvas, rect: RectF, label: String, id: String, primary: Boolean, pressed: String?, q: Float = 1f) {
        val sink = if (pressed == id) 3 * density else 0f
        fillP.color = if (primary) accent.shade(-0.3f) else 0xFFD5DAD2.toInt()
        canvas.drawRoundRect(rect.left, rect.top + 4 * density, rect.right, rect.bottom + 4 * density, rect.height() / 2, rect.height() / 2, fillP)
        fillP.color = if (primary) accent else 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(rect.left, rect.top + sink, rect.right, rect.bottom + sink, rect.height() / 2, rect.height() / 2, fillP)
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = scale.px(15f) * q
        val room = rect.width() - 16 * density
        if (text.measureText(label) > room) text.textSize = maxOf(text.textSize * room / text.measureText(label), text.textSize * 0.7f)
        text.textAlign = Paint.Align.CENTER
        text.color = if (primary) 0xFFFFFFFF.toInt() else ink
        canvas.drawText(fit(label, room), rect.centerX(), rect.centerY() + sink + text.textSize * 0.35f, text)
        text.textAlign = Paint.Align.LEFT
        targets += RectF(rect) to id
        drawnNodes += UiNode("tutorial:$id", RectF(rect), label, UiNode.Kind.BUTTON)
    }

    private fun title(step: TutorialStep): String = context.getString(
        when (step) {
            TutorialStep.LAY_CABLE -> R.string.tutorial_cable_title
            TutorialStep.PLACE_ROUTER -> R.string.tutorial_router_title
            TutorialStep.BANDWIDTH -> R.string.tutorial_bandwidth_title
            TutorialStep.PING -> R.string.tutorial_ping_title
            TutorialStep.OVERLOAD -> R.string.tutorial_overload_title
            TutorialStep.PORTS -> R.string.tutorial_ports_title
            TutorialStep.DONE -> R.string.tutorial_done_title
        },
    )

    /** Title and text of the bubble as a reader sees them, for tests (the tutorial bot's reading time). */
    internal fun text(t: Tutorial, focus: TutorialFocus): String = title(t.step) + " " + body(t, focus)

    /** The step's text; some steps say something else once the player did the first half. */
    internal fun body(t: Tutorial, focus: TutorialFocus): String {
        val gamingLimit = Service.GAMING.maxPingMs!!
        return when (t.step) {
            TutorialStep.LAY_CABLE -> context.getString(R.string.tutorial_cable_text)
            TutorialStep.PLACE_ROUTER -> context.getString(
                if (focus == TutorialFocus.RouterButton) R.string.tutorial_router_text else R.string.tutorial_router_connect_text,
            )
            TutorialStep.BANDWIDTH -> when (focus) {
                is TutorialFocus.Drag -> context.getString(R.string.tutorial_bandwidth_text)
                is TutorialFocus.CableButton -> context.getString(
                    R.string.tutorial_bandwidth_narrow_text, Service.STREAMING.bandwidth, CableType.ISDN.capacity, CableType.DSL.capacity,
                )
                else -> context.getString(R.string.tutorial_bandwidth_upgrade_text)
            }
            TutorialStep.PING -> t.tooSlowPingMs()?.let { context.getString(R.string.tutorial_ping_slow_text, it, gamingLimit) }
                ?: context.getString(R.string.tutorial_ping_text, gamingLimit)
            TutorialStep.OVERLOAD -> World.Tuning.MAX_PENDING.let { context.resources.getQuantityString(R.plurals.tutorial_overload_text, it, it) }
            TutorialStep.PORTS -> context.getString(
                if (focus == TutorialFocus.RouterButton) R.string.tutorial_ports_text else R.string.tutorial_ports_connect_text,
            )
            TutorialStep.DONE -> context.getString(R.string.tutorial_done_text)
        }
    }

    /** Splits [s] into lines no wider than [maxWidth] in the current text paint (at spaces, and between CJK characters). */
    private fun wrap(s: String, maxWidth: Float): List<String> = TextWrap.wrap(s, maxWidth) { text.measureText(it) }

    /** [s], shortened with an ellipsis if it is wider than [maxWidth] in the current text paint. */
    private fun fit(s: String, maxWidth: Float): String {
        if (text.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 1 && text.measureText(s, 0, end) + text.measureText(ELLIPSIS) > maxWidth) end--
        return s.substring(0, end).trimEnd() + ELLIPSIS
    }

    companion object {
        const val SKIP = "skip"
        const val PLAY = "play"
        const val MENU = "menu"
        /** Anywhere else on the bubble: the tap is swallowed so it does not reach the map. */
        const val BUBBLE = "bubble"

        private val HIGHLIGHT = 0xFF2F7BF6.toInt()
        private const val PULSE_SECONDS = 1.2f
        /** One sweep of the finger along a cable to drag. */
        private const val DRAG_SECONDS = 1.8f
        /** The panel sits at the left edge, below the date and above the cable buttons. */
        private const val LEFT_DP = 16f
        private const val TOP_DP = 60f
        private const val MIN_WIDTH_DP = 240f
        private const val MAX_WIDTH_DP = 320f
        private const val BODY_SP = 13.5f
        /** Android's minimum touch target. */
        private const val TOUCH_DP = 48f
        private const val ELLIPSIS = "…"
    }
}
