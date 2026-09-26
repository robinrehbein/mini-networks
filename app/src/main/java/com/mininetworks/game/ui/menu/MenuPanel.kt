package com.mininetworks.game.ui.menu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.mininetworks.game.render.fill
import com.mininetworks.game.render.shade

/** What a menu entry does when tapped. */
enum class MenuAction {
    PLAY, CONTINUE, SETTINGS, RESUME, RESTART, MAIN_MENU, BACK, PLAY_AGAIN,
    TOGGLE_SOUND, TOGGLE_HAPTICS, TOGGLE_OVERVIEW, TOGGLE_COLORBLIND,
}

/** One tappable entry of a [MenuPage]. */
sealed interface MenuItem {
    val action: MenuAction
    val label: String

    /** A pill button; [primary] is filled with the accent color, a disabled one is greyed out and ignores taps. */
    data class Button(override val action: MenuAction, override val label: String, val primary: Boolean = false, val enabled: Boolean = true) : MenuItem

    /** A row with a switch. */
    data class Toggle(override val action: MenuAction, override val label: String, val on: Boolean) : MenuItem
}

/**
 * Content of one menu card: a [title], an optional accent [highlight] line (e.g. a new best score), plain [lines],
 * the [items] and a small [footer]. A [hero] page is the main menu: big title, card on the left so the city shows.
 */
data class MenuPage(
    val title: String,
    val items: List<MenuItem>,
    val lines: List<String> = emptyList(),
    val highlight: String? = null,
    val footer: String? = null,
    val hero: Boolean = false,
)

/**
 * Draws a [MenuPage] on the game canvas in the look of the reward cards: a pale card on a slab over the dimmed map,
 * rounded pill buttons that sink when pressed. Everything scales down to fit short landscape screens.
 * [hit] maps a tap to an action; it is valid for the last drawn frame.
 */
class MenuPanel(context: Context) {
    private val density = context.resources.displayMetrics.density
    private val ink = 0xFF262B33.toInt()
    private val muted = 0xFF5B6674.toInt()
    private val accent = 0xFF3BA55C.toInt()
    private val dimCenter = fill(0xB3F3F1EC.toInt())
    private val dimHero = fill(0x40F3F1EC.toInt())
    private val fillP = fill(0)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    private val card = RectF()
    private val r = RectF()
    private val targets = ArrayList<Pair<RectF, MenuAction>>()

    /** The enabled item under ([x], [y]), or null. */
    fun hit(x: Float, y: Float): MenuAction? = targets.firstOrNull { it.first.contains(x, y) }?.second

    /** Where the enabled entry for [action] was drawn, or null. */
    fun targetOf(action: MenuAction): RectF? = targets.firstOrNull { it.second == action }?.first

    fun draw(canvas: Canvas, page: MenuPage, width: Int, height: Int, pressed: MenuAction? = null) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), if (page.hero) dimHero else dimCenter)
        targets.clear()

        // Natural size in dp; the whole card scales down if it does not fit the screen.
        val titleDp = if (page.hero) 40f else 28f
        var contentDp = titleDp + 10f
        if (page.highlight != null) contentDp += 26f
        contentDp += page.lines.size * 24f
        if (page.lines.isNotEmpty() || page.highlight != null) contentDp += 8f
        contentDp += page.items.size * (ITEM_DP + GAP_DP)
        if (page.footer != null) contentDp += 26f
        val naturalH = (contentDp + 2 * PAD_DP) * density
        val naturalW = CARD_W_DP * density
        val s = minOf(1f, height * 0.9f / naturalH, width * (if (page.hero) 0.5f else 0.8f) / naturalW)
        val u = density * s

        val cw = naturalW * s
        val ch = naturalH * s
        val left = if (page.hero) width * 0.07f else (width - cw) / 2f
        val top = (height - ch) / 2f - SLAB_DP * u / 2f
        card.set(left, top, left + cw, top + ch)
        slab(canvas, card, 18f * u, SLAB_DP * u, 0xFFFAFAF7.toInt(), 0xFFE3E6E1.toInt().shade(-0.2f), shadow = true)

        val cx = card.centerX()
        val inner = card.width() - 2 * PAD_DP * u
        var y = card.top + PAD_DP * u
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT_BOLD
        text.color = ink
        text.textSize = titleDp * u
        y += titleDp * u
        canvas.drawText(fit(page.title, inner), cx, y - 6f * u, text)
        y += 10f * u
        page.highlight?.let {
            text.color = accent.shade(-0.2f)
            text.textSize = 17f * u
            y += 26f * u
            canvas.drawText(fit(it, inner), cx, y - 6f * u, text)
        }
        text.typeface = Typeface.DEFAULT
        text.color = muted
        text.textSize = 15f * u
        for (line in page.lines) {
            y += 24f * u
            canvas.drawText(fit(line, inner), cx, y - 6f * u, text)
        }
        if (page.lines.isNotEmpty() || page.highlight != null) y += 8f * u

        for (item in page.items) {
            r.set(card.left + PAD_DP * u, y, card.right - PAD_DP * u, y + ITEM_DP * u)
            val down = item.action == pressed
            when (item) {
                is MenuItem.Button -> button(canvas, item, down, u)
                is MenuItem.Toggle -> toggle(canvas, item, down, u)
            }
            if (item !is MenuItem.Button || item.enabled) targets += RectF(r) to item.action
            y += (ITEM_DP + GAP_DP) * u
        }
        page.footer?.let {
            text.textAlign = Paint.Align.CENTER
            text.typeface = Typeface.DEFAULT
            text.color = muted
            text.textSize = 13f * u
            canvas.drawText(fit(it, inner), cx, y + 16f * u, text)
        }
    }

    private fun button(canvas: Canvas, b: MenuItem.Button, down: Boolean, u: Float) {
        val face = when {
            !b.enabled -> 0xFFF1F2EE.toInt()
            b.primary -> accent
            else -> 0xFFFFFFFF.toInt()
        }
        val depth = 4f * u
        val sink = if (down) depth * 0.8f else 0f
        slab(canvas, r, r.height() / 2f, depth, face, if (b.primary) accent.shade(-0.3f) else 0xFFD5DAD2.toInt(), sink = sink)
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = 17f * u
        text.color = when {
            !b.enabled -> 0xFFB0B6BD.toInt()
            b.primary -> 0xFFFFFFFF.toInt()
            else -> ink
        }
        canvas.drawText(fit(b.label, r.width() - 24f * u), r.centerX(), r.centerY() + sink + text.textSize * 0.35f, text)
    }

    private fun toggle(canvas: Canvas, t: MenuItem.Toggle, down: Boolean, u: Float) {
        fillP.color = if (down) 0xFFE6EAE3.toInt() else 0xFFF1F3EE.toInt()
        canvas.drawRoundRect(r, 14f * u, 14f * u, fillP)
        val tw = 46f * u
        val th = 26f * u
        val tx = r.right - 14f * u - tw
        val ty = r.centerY() - th / 2f
        fillP.color = if (t.on) accent else 0xFFD0D5CD.toInt()
        canvas.drawRoundRect(tx, ty, tx + tw, ty + th, th / 2f, th / 2f, fillP)
        val knobX = if (t.on) tx + tw - th / 2f else tx + th / 2f
        fillP.color = 0x33000000
        canvas.drawCircle(knobX, r.centerY() + 1.5f * u, th / 2f - 3f * u, fillP)
        fillP.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(knobX, r.centerY(), th / 2f - 3f * u, fillP)
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = 16f * u
        text.color = ink
        val room = tx - r.left - 28f * u
        text.textSize = maxOf(MIN_LABEL_SP, minOf(16f, 16f * room / text.measureText(t.label))) * u
        canvas.drawText(fit(t.label, room), r.left + 16f * u, r.centerY() + text.textSize * 0.35f, text)
    }

    /** A rounded face on a darker slab of thickness [depth]; a pressed face sinks by [sink] onto the slab. */
    private fun slab(canvas: Canvas, rect: RectF, radius: Float, depth: Float, face: Int, side: Int, shadow: Boolean = false, sink: Float = 0f) {
        if (shadow) {
            fillP.color = 0x26000000
            canvas.drawRoundRect(rect.left + depth, rect.top + depth * 2.2f, rect.right + depth, rect.bottom + depth * 2.2f, radius, radius, fillP)
        }
        fillP.color = side
        canvas.drawRoundRect(rect.left, rect.top + depth, rect.right, rect.bottom + depth, radius, radius, fillP)
        fillP.color = face
        canvas.drawRoundRect(rect.left, rect.top + sink, rect.right, rect.bottom + sink, radius, radius, fillP)
    }

    /** [s], shortened with an ellipsis if it is wider than [maxWidth] in the current text paint. */
    private fun fit(s: String, maxWidth: Float): String {
        if (text.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 1 && text.measureText(s, 0, end) + text.measureText(ELLIPSIS) > maxWidth) end--
        return s.substring(0, end).trimEnd() + ELLIPSIS
    }

    private companion object {
        const val CARD_W_DP = 360f
        const val PAD_DP = 22f
        const val ITEM_DP = 48f
        const val GAP_DP = 10f
        const val SLAB_DP = 8f
        const val MIN_LABEL_SP = 13f
        const val ELLIPSIS = "…"
    }
}
