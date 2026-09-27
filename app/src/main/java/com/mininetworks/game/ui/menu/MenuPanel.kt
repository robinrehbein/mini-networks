package com.mininetworks.game.ui.menu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.mininetworks.game.render.ViewInsets
import com.mininetworks.game.render.fill
import com.mininetworks.game.render.shade
import com.mininetworks.game.ui.TextScale
import com.mininetworks.game.ui.UiNode
import com.mininetworks.game.ui.TextWrap

/** What a menu entry does when tapped. */
enum class MenuAction {
    PLAY, CONTINUE, SETTINGS, RESUME, RESTART, MAIN_MENU, BACK, PLAY_AGAIN, TUTORIAL,
    TOGGLE_SOUND, TOGGLE_HAPTICS, TOGGLE_OVERVIEW, TOGGLE_COLORBLIND,
    /** Settings: two-finger rotation stays at any angle instead of snapping to right angles. */
    TOGGLE_FREE_ROTATION,
    /** Game over: go on once (after a rewarded video unless ads are removed). */
    SECOND_CHANCE,
    /** Main menu: buy "remove ads". */
    REMOVE_ADS,
    /** Settings: the consent form's privacy options. */
    PRIVACY,
    /** Main menu: the daily challenge's card (docs/TOP100.md C1); on it: start today's challenge. */
    DAILY, DAILY_START,
    /** Main menu: the achievements and missions (docs/TOP100.md C2). */
    ACHIEVEMENTS,
    /** Settings: the next unlocked cable skin and color theme (docs/TOP100.md C5). */
    CABLE_SKIN, COLOR_THEME,
    /** Settings: the page with the view options and cosmetics, so neither page gets crowded at large text (A7). */
    APPEARANCE,
    /** Main menu: the Play Games leaderboards (docs/TOP100.md C3); only with Play Games. */
    LEADERBOARDS,
    /** Game over: share the network as a picture (docs/TOP100.md D2). */
    SHARE,
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
 * An animated picture in a menu card, e.g. the time-lapse of the player's network on the game-over card. [draw] paints
 * into the given rectangle (about [aspect] times as wide as high); [description] is what TalkBack reads.
 */
class MenuPicture(val description: String, val aspect: Float, val draw: (Canvas, RectF) -> Unit)

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
    /** Drawn between the text lines and the entries; on a wide screen it takes the left half of the card instead. */
    val picture: MenuPicture? = null,
    /** The page's most important number (the packets delivered on the game-over card), drawn large under the title. */
    val score: String? = null,
)

/**
 * Draws a [MenuPage] on the game canvas in the look of the reward cards: a pale card on a slab over the dimmed map,
 * rounded pill buttons that sink when pressed. Text follows the system font size ([TextScale]); rows grow with it.
 * When the card does not fit the screen, the entries move into two columns and then everything but the entries'
 * 48 dp touch height scales down, so no entry is ever smaller than a finger (docs/TOP100.md A7). The card stays
 * inside [draw]'s safe area (display cutout).
 * [hit] maps a tap to an action; it is valid for the last drawn frame, like [nodes].
 */
class MenuPanel(context: Context) {
    private val scale = TextScale.of(context)
    private val density = scale.density
    private val ink = 0xFF262B33.toInt()
    private val muted = 0xFF5B6674.toInt()
    private val accent = 0xFF3BA55C.toInt()
    private val dimCenter = fill(0xD1F3F1EC.toInt())
    private val dimHero = fill(0x14F3F1EC)
    private val fillP = fill(0)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    private val card = RectF()
    private val r = RectF()
    private val targets = ArrayList<Pair<RectF, MenuAction>>()
    private val drawnNodes = ArrayList<UiNode>()
    private val logo = LogoMark(context)

    /** The enabled item under ([x], [y]), or null. */
    fun hit(x: Float, y: Float): MenuAction? = targets.firstOrNull { it.first.contains(x, y) }?.second

    /** Where the enabled entry for [action] was drawn, or null. */
    fun targetOf(action: MenuAction): RectF? = targets.firstOrNull { it.second == action }?.first

    /** Title, texts and entries of the last drawn card, for accessibility services and tests. */
    val nodes: List<UiNode> get() = drawnNodes

    /** Where the card was drawn last. */
    val cardBounds: RectF get() = RectF(card)

    /** Sizes of one arrangement of a page: [s] scales text and spacing, [cols] columns of entries. */
    private inner class Layout(val page: MenuPage, val s: Float, val cols: Int, val width: Float) {
        val u = density * s
        val pad = PAD_DP * u
        val titleSize = scale.px(if (page.hero) 50f else 28f) * s
        val highlightSize = scale.px(21f) * s
        val lineSize = scale.px(15f) * s
        val labelSize = scale.px(17f) * s
        /** The main menu's logo mark above the title. */
        val logoSize = (if (page.hero) HERO_LOGO_DP else LOGO_DP) * u
        val footerSize = scale.px(13f) * s
        val itemH = maxOf(TOUCH_DP * density, labelSize + 24f * u)
        val gap = GAP_DP * u
        val inner = width - 2 * pad
        val lines: List<String> = page.lines.flatMap { line ->
            text.textSize = lineSize
            text.typeface = Typeface.DEFAULT
            balanced(line, inner, MAX_LINE_ROWS)
        }
        val rows = (page.items.size + cols - 1) / cols
        val titleH = titleSize * 1.25f + (if (page.hero) logoSize + 8f * u else 0f)
        val scoreSize = scale.px(44f) * s
        val scoreH = if (page.score != null) scoreSize * 1.3f else 0f
        val highlightH = if (page.highlight != null) highlightSize * 1.55f else 0f
        val lineH = lineSize * 1.6f
        val linesH = lines.size * lineH + if (lines.isNotEmpty() || page.highlight != null) 8f * u else 0f
        /** The main menu's primary entry (Play) is taller than the rest in a single column: the one thing to tap. */
        val primaryExtra = if (page.hero && cols == 1 && page.items.any { it is MenuItem.Button && it.primary }) itemH * 0.4f else 0f
        val itemsH = rows * itemH + rows * gap + primaryExtra
        val footerH = if (page.footer != null) footerSize * 2f else 0f
        val pictureW = page.picture?.let { minOf(inner, PICTURE_MAX_H_DP * u * it.aspect) } ?: 0f
        val pictureH = page.picture?.let { pictureW / it.aspect } ?: 0f
        val pictureBlock = if (page.picture != null) pictureH + 12f * u else 0f
        val height = 2 * pad + titleH + 10f * u + scoreH + highlightH + linesH + pictureBlock + itemsH + footerH
    }

    fun draw(canvas: Canvas, page: MenuPage, width: Int, height: Int, pressed: MenuAction? = null, safe: ViewInsets = ViewInsets.NONE) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), if (page.hero) dimHero else dimCenter)
        targets.clear()
        drawnNodes.clear()
        val areaW = width - safe.left - safe.right
        val areaH = height - safe.top - safe.bottom
        val pic = page.picture
        if (pic != null && areaW >= areaH * SPLIT_ASPECT) {
            drawSplit(canvas, page, pic, areaW, areaH, pressed, safe)
            return
        }
        val l = arrange(page, areaW, areaH)
        val u = l.u

        val cw = l.width
        val ch = l.height
        val left = if (page.hero && areaW > areaH) safe.left + maxOf(areaW * 0.07f, 16f * density) else safe.left + (areaW - cw) / 2f
        val top = safe.top + (areaH - ch) / 2f - SLAB_DP * u / 2f
        card.set(left, top, left + cw, top + ch)
        slab(canvas, card, 18f * u, SLAB_DP * u, 0xFFFAFAF7.toInt(), 0xFFE3E6E1.toInt().shade(-0.2f), shadow = true)
        drawContent(canvas, l, card.left, card.top, pressed)
    }

    /**
     * A card with a picture on a wide screen: the picture fills the left half (large and in colour, the hero of e.g.
     * the game-over card), the title, score, texts and entries the right half, so the entries keep their full size.
     */
    private fun drawSplit(canvas: Canvas, page: MenuPage, pic: MenuPicture, areaW: Float, areaH: Float, pressed: MenuAction?, safe: ViewInsets) {
        val cw = minOf(areaW * 0.9f, SPLIT_W_DP * density)
        val paneW = cw * 0.5f
        val rest = page.copy(picture = null)
        val maxH = areaH * 0.92f
        var s = 1f
        var l = Layout(rest, s, 1, paneW)
        while (l.height > maxH && s > MIN_SCALE) { s -= 0.02f; l = Layout(rest, s, 1, paneW) }
        val u = l.u
        val ch = minOf(maxH, maxOf(l.height, (paneW - 2 * l.pad) / pic.aspect + 2 * l.pad))
        val left = safe.left + (areaW - cw) / 2f
        val top = safe.top + (areaH - ch) / 2f - SLAB_DP * u / 2f
        card.set(left, top, left + cw, top + ch)
        slab(canvas, card, 18f * u, SLAB_DP * u, 0xFFFAFAF7.toInt(), 0xFFE3E6E1.toInt().shade(-0.2f), shadow = true)
        // The picture, as large as the left half allows at its aspect ratio, on a rounded plate.
        val boxW = paneW - 1.5f * l.pad
        val boxH = ch - 2 * l.pad
        val pw = minOf(boxW, boxH * pic.aspect)
        val ph = pw / pic.aspect
        val pcx = card.left + l.pad + boxW / 2f
        val pr = RectF(pcx - pw / 2f, card.centerY() - ph / 2f, pcx + pw / 2f, card.centerY() + ph / 2f)
        fillP.color = 0xFFEFF2EC.toInt()
        canvas.drawRoundRect(pr.left - 6f * u, pr.top - 6f * u, pr.right + 6f * u, pr.bottom + 6f * u, 14f * u, 14f * u, fillP)
        canvas.save()
        canvas.clipRect(pr)
        pic.draw(canvas, pr)
        canvas.restore()
        drawnNodes += UiNode("menu:picture", RectF(pr), pic.description, UiNode.Kind.TEXT)
        drawContent(canvas, l, card.right - paneW, card.top + (ch - l.height) / 2f, pressed)
    }

    /** Title, score, texts, picture (single-column card), entries and footer of [l], in a pane from [paneLeft], [paneTop]. */
    private fun drawContent(canvas: Canvas, l: Layout, paneLeft: Float, paneTop: Float, pressed: MenuAction?) {
        val page = l.page
        val u = l.u
        val cx = paneLeft + l.width / 2f
        val inner = l.inner
        var y = paneTop + l.pad
        if (page.hero) {
            logo.draw(canvas, cx, y + l.logoSize / 2f, l.logoSize)
            y += l.logoSize + 8f * u
        }
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT_BOLD
        text.color = if (page.hero) BRAND else ink
        text.textSize = l.titleSize
        // A title shrinks further than other texts before it is cut: the game's name on a narrow portrait window.
        val title = fitShrinking(page.title, inner, l.titleSize, l.titleSize * 0.5f)
        canvas.drawText(title, cx, y + l.titleSize, text)
        drawnNodes += UiNode("menu:title", textBounds(cx, y, inner, l.titleSize * 1.25f), page.title, UiNode.Kind.HEADING, shortened = title != page.title)
        y += l.titleSize * 1.25f + 10f * u
        page.score?.let {
            text.color = BRAND
            text.typeface = Typeface.DEFAULT_BOLD
            fitShrinking(it, inner, l.scoreSize, l.scoreSize * 0.6f)
            canvas.drawText(fit(it, inner), cx, y + l.scoreSize * 1.0f, text)
            drawnNodes += UiNode("menu:score", textBounds(cx, y, inner, l.scoreH), it, UiNode.Kind.TEXT)
            y += l.scoreH
        }
        page.highlight?.let {
            text.color = accent.shade(-0.2f)
            text.textSize = l.highlightSize
            canvas.drawText(fitShrinking(it, inner, l.highlightSize, l.highlightSize * 0.8f), cx, y + l.highlightSize * 1.1f, text)
            drawnNodes += UiNode("menu:highlight", textBounds(cx, y, inner, l.highlightH), it, UiNode.Kind.TEXT)
            y += l.highlightH
        }
        text.typeface = Typeface.DEFAULT
        text.color = muted
        text.textSize = l.lineSize
        val linesTop = y
        for (line in l.lines) {
            canvas.drawText(line, cx, y + l.lineSize * 1.15f, text)
            y += l.lineH
        }
        if (l.lines.isNotEmpty()) drawnNodes += UiNode("menu:lines", textBounds(cx, linesTop, inner, y - linesTop), page.lines.joinToString("\n"), UiNode.Kind.TEXT)
        if (l.lines.isNotEmpty() || page.highlight != null) y += 8f * u
        page.picture?.let { pic ->
            val pr = RectF(cx - l.pictureW / 2f, y, cx + l.pictureW / 2f, y + l.pictureH)
            canvas.save()
            canvas.clipRect(pr)
            pic.draw(canvas, pr)
            canvas.restore()
            drawnNodes += UiNode("menu:picture", RectF(pr), pic.description, UiNode.Kind.TEXT)
            y += l.pictureBlock
        }

        val colW = (inner - (l.cols - 1) * l.gap) / l.cols
        var extra = 0f
        for ((i, item) in page.items.withIndex()) {
            val col = i % l.cols
            val row = i / l.cols
            val x = paneLeft + l.pad + col * (colW + l.gap)
            val itemTop = y + row * (l.itemH + l.gap) + extra
            val big = l.primaryExtra > 0f && item is MenuItem.Button && item.primary
            r.set(x, itemTop, x + colW, itemTop + l.itemH + if (big) l.primaryExtra else 0f)
            if (big) extra += l.primaryExtra
            val down = item.action == pressed
            val shortened = when (item) {
                is MenuItem.Button -> button(canvas, item, down, u, if (big) l.labelSize * 1.25f else l.labelSize)
                is MenuItem.Toggle -> toggle(canvas, item, down, u, l.labelSize * 16f / 17f)
            }
            val enabled = item !is MenuItem.Button || item.enabled
            if (enabled) targets += RectF(r) to item.action
            drawnNodes += UiNode(
                "menu:${item.action.name}", RectF(r), item.label,
                if (item is MenuItem.Toggle) UiNode.Kind.TOGGLE else UiNode.Kind.BUTTON,
                checked = item is MenuItem.Toggle && item.on, enabled = enabled, shortened = shortened,
            )
        }
        y += l.itemsH
        page.footer?.let {
            text.textAlign = Paint.Align.CENTER
            text.typeface = Typeface.DEFAULT
            text.color = muted
            text.textSize = l.footerSize
            // With large text the small print may shrink down to its standard size (13 sp at 100 %) before it is cut.
            val footer = fitShrinking(it, inner, l.footerSize, minOf(l.footerSize * 0.8f, 13f * density))
            canvas.drawText(footer, cx, y + l.footerSize * 1.2f, text)
            drawnNodes += UiNode("menu:footer", textBounds(cx, y, inner, l.footerH), it, UiNode.Kind.TEXT, shortened = footer != it)
        }
    }

    /**
     * The arrangement for [page] in an area of [areaW] × [areaH] px: one column at full size if it fits, else the one
     * (one or two columns) that needs to shrink least; a long page that still does not fit takes three columns.
     * Entries never get lower than 48 dp.
     */
    private fun arrange(page: MenuPage, areaW: Float, areaH: Float): Layout {
        val maxH = areaH * 0.92f
        // The main menu card leaves room for the city beside it, except in a portrait window, where it takes the width.
        val maxW = areaW * (if (page.hero && areaW > areaH) HERO_MAX_WIDTH else 0.86f)
        fun widthFor(s: Float, cols: Int): Float {
            val u = density * s
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = scale.px(17f) * s
            // A toggle's switch and gaps take about 100 dp next to its label.
            val label = page.items.maxOfOrNull { text.measureText(it.label) + (if (it is MenuItem.Toggle) 100f else 48f) * u } ?: 0f
            val natural = maxOf(CARD_W_DP * u, cols * label + (cols - 1) * GAP_DP * u + 2 * PAD_DP * u)
            return minOf(natural, maxW)
        }
        fun best(cols: Int): Layout {
            var s = 1f
            while (true) {
                val l = Layout(page, s, cols, widthFor(s, cols))
                if (l.height <= maxH || s <= MIN_SCALE) return l
                s -= 0.02f
            }
        }
        val one = best(1)
        if (one.s >= 1f || page.items.size < 4) return one
        val two = best(2)
        val pick = if (two.s > one.s + 0.05f) two else one
        // A long page (the settings) on a low screen with large text gets a third column before it would leave the screen.
        if (pick.height <= maxH || page.items.size < 7) return pick
        val three = best(3)
        return if (three.height < pick.height) three else pick
    }

    private fun textBounds(cx: Float, top: Float, width: Float, height: Float) = RectF(cx - width / 2f, top, cx + width / 2f, top + height)

    /** Draws [b] into [r]; true if its label had to be shortened. */
    private fun button(canvas: Canvas, b: MenuItem.Button, down: Boolean, u: Float, size: Float): Boolean {
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
        text.color = when {
            !b.enabled -> 0xFF8A9199.toInt()
            b.primary -> 0xFFFFFFFF.toInt()
            else -> ink
        }
        val label = fitShrinking(b.label, r.width() - 24f * u, size, maxOf(size * 0.7f, MIN_LABEL_SP * u))
        canvas.drawText(label, r.centerX(), r.centerY() + sink + text.textSize * 0.35f, text)
        return label != b.label
    }

    /** Draws [t] into [r]; true if its label had to be shortened. */
    private fun toggle(canvas: Canvas, t: MenuItem.Toggle, down: Boolean, u: Float, size: Float): Boolean {
        fillP.color = if (down) 0xFFE6EAE3.toInt() else 0xFFF1F3EE.toInt()
        canvas.drawRoundRect(r, 14f * u, 14f * u, fillP)
        val tw = 46f * u
        val th = 26f * u
        val tx = r.right - 14f * u - tw
        val ty = r.centerY() - th / 2f
        fillP.color = if (t.on) accent else 0xFF7F887F.toInt()
        canvas.drawRoundRect(tx, ty, tx + tw, ty + th, th / 2f, th / 2f, fillP)
        val knobX = if (t.on) tx + tw - th / 2f else tx + th / 2f
        fillP.color = 0x33000000
        canvas.drawCircle(knobX, r.centerY() + 1.5f * u, th / 2f - 3f * u, fillP)
        fillP.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(knobX, r.centerY(), th / 2f - 3f * u, fillP)
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT_BOLD
        text.color = ink
        // The label starts 16 dp in and keeps 8 dp from the switch.
        val room = tx - r.left - 24f * u
        val label = fitShrinking(t.label, room, size, maxOf(size * 0.7f, MIN_LABEL_SP * u))
        canvas.drawText(label, r.left + 16f * u, r.centerY() + text.textSize * 0.35f, text)
        return label != t.label
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

    /**
     * Sets the text size to [size], or smaller down to [min] until [s] fits [maxWidth], and returns [s] (shortened
     * with an ellipsis if it does not fit even then).
     */
    private fun fitShrinking(s: String, maxWidth: Float, size: Float, min: Float): String {
        text.textSize = size
        val w = text.measureText(s)
        // 2 % headroom: text width does not scale exactly with the size (hinting), and a label shrunk to fit exactly
        // must not lose its last letter to the ellipsis.
        if (w > maxWidth) text.textSize = maxOf(min, size * maxWidth / w * 0.98f)
        return fit(s, maxWidth)
    }

    /** [s], shortened with an ellipsis if it is wider than [maxWidth] in the current text paint. */
    private fun fit(s: String, maxWidth: Float): String {
        if (text.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 1 && text.measureText(s, 0, end) + text.measureText(ELLIPSIS) > maxWidth) end--
        return s.substring(0, end).trimEnd() + ELLIPSIS
    }

    /** [s] broken into at most [maxLines] lines of [maxWidth] (at spaces, and between CJK characters); the last one is shortened if needed. */
    private fun wrap(s: String, maxWidth: Float, maxLines: Int): List<String> = TextWrap.wrap(s, maxWidth, maxLines) { text.measureText(it) }

    /** [TextWrap.balanced] in the current text paint. */
    private fun balanced(s: String, maxWidth: Float, maxLines: Int): List<String> = TextWrap.balanced(s, maxWidth, maxLines) { text.measureText(it) }

    private companion object {
        const val CARD_W_DP = 360f
        /** Highest a card's picture gets at full scale. */
        const val PICTURE_MAX_H_DP = 150f
        const val PAD_DP = 22f
        const val GAP_DP = 10f
        const val SLAB_DP = 8f
        /** Android's minimum touch target; entries never get lower. */
        const val TOUCH_DP = 48f
        const val MIN_LABEL_SP = 13f
        /** Smallest scale for text and spacing when a card does not fit. */
        const val MIN_SCALE = 0.5f
        /** Rows a text line of the card may wrap into. */
        const val MAX_LINE_ROWS = 3
        /** The main menu card leaves the rest of the screen to the demo town. */
        const val HERO_MAX_WIDTH = 0.6f
        const val ELLIPSIS = "…"
        /** A card with a picture splits into picture and text halves from this width-to-height ratio of the screen. */
        const val SPLIT_ASPECT = 1.25f
        const val SPLIT_W_DP = 760f
        const val LOGO_DP = 64f
        /** The logo mark over the game's name on the main menu. */
        const val HERO_LOGO_DP = 88f
        /** The brand's dusk blue (launcher icon) for the game's name and the hero numbers. */
        const val BRAND = 0xFF1B4A5E.toInt()
    }
}
