package com.mininetworks.game.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.mininetworks.game.R
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
    private val density = context.resources.displayMetrics.density
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

    /** Width of the panel on a screen [width] px wide. */
    private fun panelWidth(width: Int) = (width * 0.36f).coerceIn(MIN_WIDTH_DP * density, MAX_WIDTH_DP * density)

    /** Right edge of the panel on a screen [width] px wide, so the map can be framed beside it. */
    fun reservedRight(width: Int): Float = LEFT_DP * density + panelWidth(width)

    /** The bubble entry under ([x], [y]): a button id, [BUBBLE] elsewhere on the bubble, or null off it. */
    fun hit(x: Float, y: Float): String? {
        if (!bubble.contains(x, y)) return null
        return targets.firstOrNull { it.first.contains(x, y) }?.second ?: BUBBLE
    }

    /** Where button [id] was drawn in the last frame, for tests. */
    fun targetOf(id: String): RectF? = targets.firstOrNull { it.second == id }?.first

    /**
     * Draws the highlight for [focus] and the bubble for [tutorial]'s step. [hudButton] returns the screen rectangle of
     * a HUD button by id ("router", "cable:DSL" ...).
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
        val pad = 14 * density
        val left = LEFT_DP * density
        val top = TOP_DP * density
        bubble.set(left, top, left + panelWidth(width), top)
        val inner = bubble.width() - 2 * pad
        val done = t.finished
        text.typeface = Typeface.DEFAULT
        text.textSize = BODY_SP * density
        val lines = wrap(body(t, focus), inner)
        val lineH = BODY_SP * 1.32f * density
        val kickerH = 16 * density
        val titleH = 26 * density
        val footerH = if (done) 40 * density else 32 * density
        bubble.bottom = top + pad + kickerH + titleH + lines.size * lineH + 12 * density + footerH + pad

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
        text.textSize = 12 * density
        text.color = HIGHLIGHT
        canvas.drawText(context.getString(R.string.tutorial_kicker, t.number, Tutorial.STEPS), bubble.left + pad, y + kickerH * 0.75f, text)
        // Progress: one dot per step, right of the kicker.
        for (i in 0 until Tutorial.STEPS) {
            fillP.color = if (i < t.number || done) HIGHLIGHT else 0xFFD5DAD2.toInt()
            canvas.drawCircle(bubble.right - pad - 3 * density - (Tutorial.STEPS - 1 - i) * 11 * density, y + kickerH * 0.5f, 3 * density, fillP)
        }
        y += kickerH
        text.textSize = 18 * density
        text.color = ink
        canvas.drawText(fit(if (done) context.getString(R.string.tutorial_done_title) else title(t.step), inner), bubble.left + pad, y + titleH * 0.72f, text)
        y += titleH
        text.typeface = Typeface.DEFAULT
        text.textSize = BODY_SP * density
        text.color = muted
        for (line in lines) {
            y += lineH
            canvas.drawText(line, bubble.left + pad, y - lineH * 0.28f, text)
        }
        y += 12 * density
        if (done) {
            val gap = 10 * density
            val w = (inner - gap) / 2f
            button(canvas, RectF(bubble.left + pad, y, bubble.left + pad + w, y + footerH), context.getString(R.string.tutorial_play), PLAY, primary = true, pressed)
            button(canvas, RectF(bubble.right - pad - w, y, bubble.right - pad, y + footerH), context.getString(R.string.menu_main), MENU, primary = false, pressed)
        } else {
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = 13 * density
            val label = context.getString(R.string.tutorial_skip)
            val w = text.measureText(label) + 28 * density
            r.set(bubble.right - pad - w, y, bubble.right - pad, y + footerH)
            fillP.color = if (pressed == SKIP) 0xFFE6EAE3.toInt() else 0xFFF1F3EE.toInt()
            canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, fillP)
            text.color = muted
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(label, r.centerX(), r.centerY() + text.textSize * 0.35f, text)
            text.textAlign = Paint.Align.LEFT
            targets += RectF(r) to SKIP
        }
    }

    private fun button(canvas: Canvas, rect: RectF, label: String, id: String, primary: Boolean, pressed: String?) {
        val sink = if (pressed == id) 3 * density else 0f
        fillP.color = if (primary) accent.shade(-0.3f) else 0xFFD5DAD2.toInt()
        canvas.drawRoundRect(rect.left, rect.top + 4 * density, rect.right, rect.bottom + 4 * density, rect.height() / 2, rect.height() / 2, fillP)
        fillP.color = if (primary) accent else 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(rect.left, rect.top + sink, rect.right, rect.bottom + sink, rect.height() / 2, rect.height() / 2, fillP)
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = 15 * density
        text.textAlign = Paint.Align.CENTER
        text.color = if (primary) 0xFFFFFFFF.toInt() else ink
        canvas.drawText(fit(label, rect.width() - 16 * density), rect.centerX(), rect.centerY() + sink + text.textSize * 0.35f, text)
        text.textAlign = Paint.Align.LEFT
        targets += RectF(rect) to id
    }

    private fun title(step: TutorialStep): String = context.getString(
        when (step) {
            TutorialStep.LAY_CABLE -> R.string.tutorial_cable_title
            TutorialStep.PLACE_ROUTER -> R.string.tutorial_router_title
            TutorialStep.CABLE_TYPE -> R.string.tutorial_type_title
            TutorialStep.PING -> R.string.tutorial_ping_title
            TutorialStep.OVERLOAD -> R.string.tutorial_overload_title
            TutorialStep.DONE -> R.string.tutorial_done_title
        },
    )

    /** The step's text; some steps say something else once the player did the first half. */
    internal fun body(t: Tutorial, focus: TutorialFocus): String {
        val gamingLimit = Service.GAMING.maxPingMs!!
        return when (t.step) {
            TutorialStep.LAY_CABLE -> context.getString(R.string.tutorial_cable_text)
            TutorialStep.PLACE_ROUTER -> context.getString(
                if (focus == TutorialFocus.RouterButton) R.string.tutorial_router_text else R.string.tutorial_router_connect_text,
            )
            TutorialStep.CABLE_TYPE -> context.getString(
                if (focus is TutorialFocus.CableButton) R.string.tutorial_type_text else R.string.tutorial_type_upgrade_text,
            )
            TutorialStep.PING -> t.tooSlowPingMs()?.let { context.getString(R.string.tutorial_ping_slow_text, it, gamingLimit) }
                ?: context.getString(R.string.tutorial_ping_text, gamingLimit)
            TutorialStep.OVERLOAD -> World.Tuning.MAX_PENDING.let { context.resources.getQuantityString(R.plurals.tutorial_overload_text, it, it) }
            TutorialStep.DONE -> context.getString(R.string.tutorial_done_text)
        }
    }

    /** Splits [s] into lines no wider than [maxWidth] in the current text paint, breaking at spaces. */
    private fun wrap(s: String, maxWidth: Float): List<String> {
        val lines = ArrayList<String>()
        var line = ""
        for (word in s.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && text.measureText(candidate) > maxWidth) {
                lines += line
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

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
        private const val ELLIPSIS = "…"
    }
}
