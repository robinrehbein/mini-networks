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
    /** Settings: open the published privacy policy. */
    PRIVACY_POLICY,
    /** Settings: unlock the review-only test access code. */
    REVIEW_ACCESS,
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

    /**
     * A pill button; [primary] is filled with the accent color, a disabled one is greyed out and ignores taps. A [link]
     * is a secondary text link under the buttons (e.g. "Remove ads" on the main menu), still 48 dp high to tap.
     */
    data class Button(
        override val action: MenuAction, override val label: String, val primary: Boolean = false, val enabled: Boolean = true,
        val link: Boolean = false,
    ) : MenuItem

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
    /** Behind a centred card (pause, game over, settings): a dark dusk-blue scrim, so the HUD and map recede. */
    private val dimCenter = fill(0xB8132632.toInt())
    private val dimHero = fill(0x14F3F1EC)
    private val fillP = fill(0)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    private val card = RectF()
    private val r = RectF()
    private val targets = ArrayList<Pair<RectF, MenuAction>>()
    private val drawnNodes = ArrayList<UiNode>()
    private val logo = LogoMark(context)
    private val display = com.mininetworks.game.ui.Fonts.display(context)

    /** The enabled item under ([x], [y]), or null. */
    fun hit(x: Float, y: Float): MenuAction? = targets.firstOrNull { it.first.contains(x, y) }?.second

    /** Where the enabled entry for [action] was drawn, or null. */
    fun targetOf(action: MenuAction): RectF? = targets.firstOrNull { it.second == action }?.first

    /** Title, texts and entries of the last drawn card, for accessibility services and tests. */
    val nodes: List<UiNode> get() = drawnNodes

    /** Where the card was drawn last. */
    val cardBounds: RectF get() = RectF(card)

    /** Where a grid entry goes: [row], [col], and whether it [span]s the whole row. */
    private data class Slot(val row: Int, val col: Int, val span: Boolean)

    /** Sizes of one arrangement of a page: [s] scales text and spacing, [cols] columns of entries. */
    private inner class Layout(val page: MenuPage, val s: Float, val cols: Int, val width: Float, val split: Boolean = false) {
        val u = density * s
        /** A split card's text pane is tighter around its text, so the text itself can stay large on a low phone. */
        val pad = (if (split) SPLIT_PAD_DP else PAD_DP) * u
        val titleSize = scale.px(if (page.hero) 44f else 28f) * s
        val highlightSize = scale.px(21f) * s
        val lineSize = scale.px(if (page.hero) 17f else 15f) * s
        val labelSize = scale.px(labelSp(page)) * s
        /** The main menu's logo mark above the title. */
        val logoSize = (if (page.hero) HERO_LOGO_DP else LOGO_DP) * u
        val footerSize = scale.px(if (page.hero) 15f else 13f) * s
        val itemH = maxOf(TOUCH_DP * density, labelSize + 24f * u)
        val linkSize = scale.px(15f) * s
        val linkH = maxOf(TOUCH_DP * density, linkSize * 2.2f)
        val gap = (if (split) SPLIT_GAP_DP else GAP_DP) * u
        val inner = width - 2 * pad
        val lines: List<String> = page.lines.flatMap { line ->
            text.textSize = lineSize
            text.typeface = Typeface.DEFAULT
            balanced(line, inner, MAX_LINE_ROWS)
        }
        /** Pill buttons in the grid; [links] go below it as text links. */
        val grid = page.items.filter { it !is MenuItem.Button || !it.link }
        val links = page.items.filter { it is MenuItem.Button && it.link }
        /**
         * The main menu's primary entry (Play) spans the whole width as its own row, whatever the columns; so does the
         * primary entry of a split card's text pane (game over: "again" above "share" and "main menu").
         */
        val spanFirst = (page.hero || split) && cols > 1 && (grid.firstOrNull() as? MenuItem.Button)?.primary == true
        val colW = (inner - (cols - 1) * gap) / cols
        /**
         * Row and column of each grid entry, and whether it spans the row. In a split pane an entry whose label would
         * not fit half the pane at its size ("continue · watch a video") gets a row of its own, and so does one left
         * alone in its row; elsewhere the entries fill the columns in order.
         */
        val slots: List<Slot> = if (split && cols > 1) {
            val out = ArrayList<Slot>()
            var row = 0
            var col = 0
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = labelSize
            for ((k, item) in grid.withIndex()) {
                val wide = (k == 0 && spanFirst) || text.measureText(item.label) + 24f * u > colW
                if (wide) {
                    if (col != 0) { row++; col = 0 }
                    out += Slot(row, 0, true)
                    row++
                } else {
                    out += Slot(row, col, false)
                    if (++col == cols) { row++; col = 0 }
                }
            }
            out.map { sl -> if (out.count { it.row == sl.row } == 1) sl.copy(col = 0, span = true) else sl }
        } else {
            grid.indices.map { k ->
                // With a spanning first entry the others count from the second row.
                val i = if (spanFirst && k > 0) k - 1 + cols else k
                Slot(i / cols, if (spanFirst && k == 0) 0 else i % cols, spanFirst && k == 0)
            }
        }
        val rows = (slots.maxOfOrNull { it.row } ?: -1) + 1
        val titleH = titleSize * 1.25f + (if (page.hero) logoSize + 8f * u else 0f)
        val titleGap = (if (split) 4f else 10f) * u
        val scoreSize = scale.px(if (split) SPLIT_SCORE_SP else 44f) * s
        val scoreH = if (page.score != null) scoreSize * 1.3f else 0f
        val highlightH = if (page.highlight != null) highlightSize * 1.55f else 0f
        val lineH = lineSize * 1.6f
        val linesH = lines.size * lineH + if (lines.isNotEmpty() || page.highlight != null) 8f * u else 0f
        /** The main menu's primary entry (Play) is taller than the rest in a single column: the one thing to tap. */
        val primaryExtra = if (page.hero && grid.any { it is MenuItem.Button && it.primary }) itemH * 0.4f else 0f
        val itemsH = rows * itemH + rows * gap + primaryExtra + links.size * linkH
        /** On the main menu the footer (the best score) is a badge. */
        val footerH = if (page.footer != null) footerSize * (if (page.hero) 2.9f else 2f) else 0f
        val pictureW = page.picture?.let { minOf(inner, PICTURE_MAX_H_DP * u * it.aspect) } ?: 0f
        val pictureH = page.picture?.let { pictureW / it.aspect } ?: 0f
        val pictureBlock = if (page.picture != null) pictureH + 12f * u else 0f
        val height = 2 * pad + titleH + titleGap + scoreH + highlightH + linesH + pictureBlock + itemsH + footerH
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
        val rest = page.copy(picture = null)
        val maxH = areaH * 0.92f
        // The text is what a player must read (the reason for the loss, the entries); on a low screen the picture gives
        // way first: the entries pair up under the primary one and the text pane grows, before the text shrinks below
        // SPLIT_MIN_SCALE. Of these arrangements the one with the largest text wins (the first on a tie).
        fun fitted(share: Float, cols: Int, floor: Float): Layout? {
            var s = 1f
            while (true) {
                val l = Layout(rest, s, cols, cw * share, split = true)
                if (l.height <= maxH) return l
                if (s - 0.02f < floor - 1e-4f) return null
                s -= 0.02f
            }
        }
        val options = listOf(0.5f to 1, 0.5f to 2, 0.56f to 2, 0.62f to 2).mapNotNull { (share, cols) -> fitted(share, cols, SPLIT_MIN_SCALE) }
        val l = options.maxByOrNull { it.s } ?: run {
            // Very large system text: shrink further, down to the common minimum (entries stay 48 dp high).
            var s = SPLIT_MIN_SCALE
            var l = Layout(rest, s, 2, cw * 0.62f, split = true)
            while (l.height > maxH && s > MIN_SCALE) { s -= 0.02f; l = Layout(rest, s, 2, cw * 0.62f, split = true) }
            l
        }
        val paneW = l.width
        val u = l.u
        val ch = minOf(maxH, maxOf(l.height, (cw - paneW - 2 * l.pad) / pic.aspect + 2 * l.pad))
        val left = safe.left + (areaW - cw) / 2f
        val top = safe.top + (areaH - ch) / 2f - SLAB_DP * u / 2f
        card.set(left, top, left + cw, top + ch)
        slab(canvas, card, 18f * u, SLAB_DP * u, 0xFFFAFAF7.toInt(), 0xFFE3E6E1.toInt().shade(-0.2f), shadow = true)
        // The picture fills the whole left half on a rounded plate, from the title's height down to the last entry, so
        // no empty band opens above or below it; it draws itself as large as its aspect ratio allows inside.
        val boxW = cw - paneW - 1.5f * l.pad
        val pr = RectF(card.left + l.pad, card.top + l.pad, card.left + l.pad + boxW, card.bottom - l.pad)
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
        text.typeface = display
        text.color = if (page.hero) BRAND else ink
        text.textSize = l.titleSize
        // A title shrinks further than other texts before it is cut: the game's name on a narrow portrait window.
        val title = fitShrinking(page.title, inner, l.titleSize, l.titleSize * 0.5f)
        canvas.drawText(title, cx, y + l.titleSize, text)
        drawnNodes += UiNode("menu:title", textBounds(cx, y, inner, l.titleSize * 1.25f), page.title, UiNode.Kind.HEADING, shortened = title != page.title, textPx = text.textSize)
        y += l.titleSize * 1.25f + l.titleGap
        page.score?.let {
            text.color = BRAND
            text.typeface = display
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
        if (l.lines.isNotEmpty()) drawnNodes += UiNode("menu:lines", textBounds(cx, linesTop, inner, y - linesTop), page.lines.joinToString("\n"), UiNode.Kind.TEXT, textPx = l.lineSize)
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

        val colW = l.colW
        var extra = 0f
        val gridBottom = y + l.itemsH - l.links.size * l.linkH
        for ((k, item) in (l.grid + l.links).withIndex()) {
            val isLink = item is MenuItem.Button && item.link
            if (isLink) {
                r.set(paneLeft + l.pad, gridBottom + (k - l.grid.size) * l.linkH, paneLeft + l.pad + inner, gridBottom + (k - l.grid.size + 1) * l.linkH)
            } else {
                val slot = l.slots[k]
                val x = paneLeft + l.pad + slot.col * (colW + l.gap)
                val itemTop = y + slot.row * (l.itemH + l.gap) + extra
                val big = l.primaryExtra > 0f && item is MenuItem.Button && item.primary
                val w = if (slot.span) inner else colW
                r.set(x, itemTop, x + w, itemTop + l.itemH + if (big) l.primaryExtra else 0f)
                if (big) extra += l.primaryExtra
            }
            val big = !isLink && l.primaryExtra > 0f && item is MenuItem.Button && item.primary
            val down = item.action == pressed
            val shortened = when (item) {
                is MenuItem.Button -> if (item.link) link(canvas, item, down, l.linkSize, u) else button(canvas, item, down, u, if (big) l.labelSize * 1.3f else l.labelSize)
                is MenuItem.Toggle -> toggle(canvas, item, down, u, l.labelSize * 16f / 17f)
            }
            val drawnPx = text.textSize
            val enabled = item !is MenuItem.Button || item.enabled
            if (enabled) targets += RectF(r) to item.action
            drawnNodes += UiNode(
                "menu:${item.action.name}", RectF(r), item.label,
                if (item is MenuItem.Toggle) UiNode.Kind.TOGGLE else UiNode.Kind.BUTTON,
                checked = item is MenuItem.Toggle && item.on, enabled = enabled, shortened = shortened, textPx = drawnPx,
            )
        }
        y += l.itemsH
        if (page.hero) page.footer?.let { badge(canvas, it, cx, y, l, inner); return }
        page.footer?.let {
            text.textAlign = Paint.Align.CENTER
            text.typeface = Typeface.DEFAULT
            text.color = muted
            text.textSize = l.footerSize
            // With large text the small print may shrink down to its standard size (13 sp at 100 %) before it is cut;
            // a line that is still too long then breaks into two smaller lines in the footer's room.
            val footer = fitShrinking(it, inner, l.footerSize, minOf(l.footerSize * 0.8f, 13f * density))
            if (footer == it) {
                canvas.drawText(footer, cx, y + l.footerSize * 1.2f, text)
                drawnNodes += UiNode("menu:footer", textBounds(cx, y, inner, l.footerH), it, UiNode.Kind.TEXT)
            } else {
                text.textSize = l.footerSize * 0.8f
                val rows = balanced(it, inner, 2)
                rows.forEachIndexed { i, row -> canvas.drawText(row, cx, y + text.textSize * (1.0f + 1.15f * i), text) }
                drawnNodes += UiNode("menu:footer", textBounds(cx, y, inner, l.footerH), it, UiNode.Kind.TEXT, shortened = rows.any { r -> r.endsWith(ELLIPSIS) })
            }
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
            text.textSize = scale.px(labelSp(page)) * s
            // A toggle's switch and gaps take about 100 dp next to its label; links span the card and do not count.
            val label = page.items.filter { it !is MenuItem.Button || !it.link }
                .maxOfOrNull { text.measureText(it.label) + (if (it is MenuItem.Toggle) 100f else 48f) * u } ?: 0f
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
        if (page.hero) {
            // The main menu: Play spans the card, the other entries share one or two rows below it; the arrangement
            // that keeps the text largest wins (a low landscape phone gets the secondary entries side by side).
            val secondary = page.items.count { it !is MenuItem.Button || (!it.link && !it.primary) }
            val options = listOf(one) + (2..minOf(3, maxOf(2, secondary))).map { best(it) }
            val fitting = options.filter { it.height <= maxH }
            return if (fitting.isEmpty()) options.minBy { it.height } else fitting.maxBy { it.s + (if (it.cols == 1) 0.04f else 0f) }
        }
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
        // Play, the daily challenge and the achievements carry a small icon before the label (judge panel: the menu
        // read as generic pills); it gives way first when the label needs the room.
        val glyph = b.action in ICON_ACTIONS && b.enabled
        var iconSize = if (glyph) size * 0.85f else 0f
        // Next to an icon the pill's side padding shrinks a little, so a landscape phone's narrow pills keep theirs.
        var room = r.width() - (if (glyph) 16f else 24f) * u - (if (glyph) iconSize * 1.4f else 0f)
        text.textSize = size
        // The icon stays only while the label keeps at least 80 % of its size (and never gets cut) next to it.
        val keep = maxOf(0.8f, maxOf(size * 0.62f, MIN_LABEL_SP * u) / size)
        // A narrow pill (a landscape phone's row of three) tries a smaller icon before it gives the icon up.
        if (glyph && text.measureText(b.label) * keep * 1.03f > room) {
            iconSize = size * 0.62f
            room = r.width() - 12f * u - iconSize * 1.4f
        }
        if (glyph && text.measureText(b.label) * keep * 1.03f > room) { iconSize = 0f; room = r.width() - 24f * u }
        var label = fitShrinking(b.label, room, size, maxOf(size * 0.62f, MIN_LABEL_SP * u))
        if (iconSize > 0f && label != b.label) {
            // Hinting made it a hair too wide next to the icon: the label gets the whole button instead.
            iconSize = 0f
            label = fitShrinking(b.label, r.width() - 24f * u, size, maxOf(size * 0.62f, MIN_LABEL_SP * u))
        }
        val baseline = r.centerY() + sink + text.textSize * 0.35f
        if (iconSize > 0f) {
            val lw = text.measureText(label)
            val start = r.centerX() - (lw + iconSize * 1.4f) / 2f
            icon(canvas, b.action, start + iconSize / 2f, r.centerY() + sink, iconSize, text.color)
            canvas.drawText(label, start + iconSize * 1.4f + lw / 2f, baseline, text)
        } else {
            canvas.drawText(label, r.centerX(), baseline, text)
        }
        return label != b.label
    }

    /** The small icon of [action] centred at ([cx], [cy]), [size] px high; line icons in [ink]. */
    private fun icon(canvas: Canvas, action: MenuAction, cx: Float, cy: Float, size: Float, ink: Int) {
        val s = size / 2f
        when (action) {
            MenuAction.PLAY, MenuAction.CONTINUE -> {
                // A play triangle in a ring.
                lineStroke.color = ink; lineStroke.strokeWidth = s * 0.18f
                canvas.drawCircle(cx, cy, s * 0.95f, lineStroke)
                cup.reset()
                cup.moveTo(cx - s * 0.3f, cy - s * 0.48f); cup.lineTo(cx + s * 0.52f, cy); cup.lineTo(cx - s * 0.3f, cy + s * 0.48f); cup.close()
                fillP.color = ink; canvas.drawPath(cup, fillP)
            }
            MenuAction.DAILY -> {
                // The streak flame: orange with a yellow core.
                fun flame(k: Float, color: Int) {
                    cup.reset()
                    cup.moveTo(cx, cy - s * 1.0f * k)
                    cup.cubicTo(cx + s * 0.8f * k, cy - s * 0.3f * k, cx + s * 0.75f * k, cy + s * 0.55f * k, cx, cy + s * 0.9f * k)
                    cup.cubicTo(cx - s * 0.75f * k, cy + s * 0.55f * k, cx - s * 0.8f * k, cy - s * 0.3f * k, cx, cy - s * 1.0f * k)
                    cup.close()
                    fillP.color = color; canvas.drawPath(cup, fillP)
                }
                flame(1f, 0xFFE4572E.toInt())
                canvas.save(); canvas.translate(0f, s * 0.3f); flame(0.55f, 0xFFFFC21A.toInt()); canvas.restore()
            }
            MenuAction.ACHIEVEMENTS -> trophy(canvas, cx, cy, size)
            else -> Unit
        }
            }

    /** Draws the text link [b] centred in [r]: underlined, in the muted ink; true if its label had to be shortened. */
    private fun link(canvas: Canvas, b: MenuItem.Button, down: Boolean, size: Float, u: Float): Boolean {
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT_BOLD
        text.color = if (down) ink else muted
        val label = fitShrinking(b.label, r.width() - 16f * u, size, maxOf(size * 0.75f, MIN_LABEL_SP * u))
        val base = r.centerY() + text.textSize * 0.35f
        canvas.drawText(label, r.centerX(), base, text)
        val half = text.measureText(label) / 2f
        fillP.color = muted
        canvas.drawRect(r.centerX() - half, base + 3f * u, r.centerX() + half, base + 4.5f * u, fillP)
        return label != b.label
    }

    /** The main menu's best score as a gold badge with a trophy under the entries. */
    private fun badge(canvas: Canvas, label: String, cx: Float, top: Float, l: Layout, inner: Float) {
        val u = l.u
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT_BOLD
        val shown = fitShrinking(label, inner - 64f * u, l.footerSize, l.footerSize * 0.8f)
        val h = l.footerSize * 2.2f
        val icon = h * 0.52f
        val w = text.measureText(shown) + icon + h * 0.9f
        val bt = top + (l.footerH - h) / 2f
        val rect = RectF(cx - w / 2f, bt, cx + w / 2f, bt + h)
        fillP.color = 0xFFFFF1CC.toInt()
        canvas.drawRoundRect(rect, h / 2f, h / 2f, fillP)
        lineStroke.color = 0xFFE9B949.toInt()
        lineStroke.strokeWidth = 1.5f * u
        canvas.drawRoundRect(rect, h / 2f, h / 2f, lineStroke)
        trophy(canvas, rect.left + h * 0.35f + icon / 2f, rect.centerY(), icon)
        text.color = 0xFF6B4A00.toInt()
        canvas.drawText(shown, rect.left + h * 0.45f + icon + h * 0.12f, rect.centerY() + text.textSize * 0.35f, text)
        drawnNodes += UiNode("menu:footer", RectF(rect), label, UiNode.Kind.TEXT, shortened = shown != label)
    }

    private val lineStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cup = android.graphics.Path()

    /** A small gold cup [size] px high centred at ([cx], [cy]). */
    private fun trophy(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val s = size / 2f
        fillP.color = 0xFFE9A92B.toInt()
        cup.reset()
        cup.moveTo(cx - s * 0.75f, cy - s * 0.85f)
        cup.lineTo(cx + s * 0.75f, cy - s * 0.85f)
        cup.lineTo(cx + s * 0.6f, cy - s * 0.1f)
        cup.quadTo(cx, cy + s * 0.45f, cx - s * 0.6f, cy - s * 0.1f)
        cup.close()
        canvas.drawPath(cup, fillP)
        canvas.drawRect(cx - s * 0.12f, cy + s * 0.2f, cx + s * 0.12f, cy + s * 0.6f, fillP)
        canvas.drawRoundRect(cx - s * 0.5f, cy + s * 0.6f, cx + s * 0.5f, cy + s * 0.85f, s * 0.1f, s * 0.1f, fillP)
        lineStroke.color = 0xFFE9A92B.toInt()
        lineStroke.strokeWidth = s * 0.16f
        canvas.drawArc(cx - s * 1.05f, cy - s * 0.8f, cx - s * 0.45f, cy - s * 0.1f, 90f, 180f, false, lineStroke)
        canvas.drawArc(cx + s * 0.45f, cy - s * 0.8f, cx + s * 1.05f, cy - s * 0.1f, 270f, 180f, false, lineStroke)
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

    /** Label size of [page]'s entries in sp: larger on the main menu, whose few entries are its whole purpose. */
    private fun labelSp(page: MenuPage) = if (page.hero) 20f else 17f

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
        const val HERO_MAX_WIDTH = 0.56f
        /** Entries with an icon before their label. */
        val ICON_ACTIONS = setOf(MenuAction.PLAY, MenuAction.DAILY, MenuAction.ACHIEVEMENTS)
        const val ELLIPSIS = "…"
        /** Smallest text scale of a split card's text pane before the picture has given all the room it can. */
        const val SPLIT_MIN_SCALE = 0.86f
        const val SPLIT_PAD_DP = 14f
        const val SPLIT_GAP_DP = 6f
        /** The hero number of a split card (the packets on the game-over card); smaller than on a full card. */
        const val SPLIT_SCORE_SP = 34f
        /** A card with a picture splits into picture and text halves from this width-to-height ratio of the screen. */
        const val SPLIT_ASPECT = 1.25f
        const val SPLIT_W_DP = 760f
        const val LOGO_DP = 64f
        /** The logo mark over the game's name on the main menu. */
        const val HERO_LOGO_DP = 76f
        /** The brand's dusk blue (launcher icon) for the game's name and the hero numbers. */
        const val BRAND = 0xFF1B4A5E.toInt()
    }
}
