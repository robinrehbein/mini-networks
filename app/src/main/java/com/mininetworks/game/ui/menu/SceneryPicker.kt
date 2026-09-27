package com.mininetworks.game.ui.menu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.mininetworks.game.game.Scenario
import com.mininetworks.game.game.World
import com.mininetworks.game.render.Cosmetic
import com.mininetworks.game.render.IsoRenderer
import com.mininetworks.game.render.ViewInsets
import com.mininetworks.game.render.fill
import com.mininetworks.game.render.shade
import com.mininetworks.game.render.stroke
import com.mininetworks.game.ui.TextScale
import com.mininetworks.game.ui.UiNode

/** One card of the [SceneryPicker]; all texts are ready to show. */
data class SceneryCard(
    val scenario: Scenario,
    val name: String,
    val era: String,
    val description: String,
    val unlocked: Boolean,
    /** Up to two lines at the bottom: the best score, the packet goal or the shop. */
    val status: List<String>,
    /** 0..1 towards the packet goal of a locked scenery, null without one. */
    val progress: Float?,
    /** Said by screen readers for a locked card ("locked"), null for an unlocked one. */
    val lockedLabel: String? = null,
)

/**
 * The scenery select screen, drawn on the game canvas in the look of the menu cards: a title with a back pill and one
 * card per scenery with an isometric preview of its start map, its era and what makes it special. Locked cards are
 * greyed out with a padlock and show how to unlock them. While the store sells the pack, a pill at the top right buys
 * it. [hit] maps a tap to a scenery id, [BACK] or [PACK]; it is valid for the last drawn frame.
 */
class SceneryPicker(context: Context) {
    private val scale = TextScale(context.resources.displayMetrics)
    private val density = scale.density
    private val ink = 0xFF262B33.toInt()
    private val muted = 0xFF5B6674.toInt()
    private val accent = 0xFF3BA55C.toInt()
    private val dim = fill(0xB3F3F1EC.toInt())
    private val fillP = fill(0)
    private val lineP = stroke(0)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    private val greyed = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.1f) })
        alpha = 150
    }
    private val bitmapP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()
    private val r = RectF()
    private val targets = ArrayList<Pair<RectF, String>>()
    private val drawnNodes = ArrayList<UiNode>()
    private val previews = HashMap<String, Bitmap>()

    /** The scenery id, [BACK] or [PACK] under ([x], [y]), or null. */
    fun hit(x: Float, y: Float): String? = targets.firstOrNull { it.first.contains(x, y) }?.second

    /** Where the card of scenery [id] (or the [BACK] or [PACK] pill) was drawn, or null. */
    fun targetOf(id: String): RectF? = targets.firstOrNull { it.second == id }?.first

    /** Title, pills, cards and hint of the last drawn frame, for accessibility services and tests. */
    val nodes: List<UiNode> get() = drawnNodes

    /** Horizontal scroll of the card row in px, when the cards do not all fit ([scrollable]). */
    private var scroll = 0f
    private var maxScroll = 0f
    /** Left edge of the visible row area and its width in px, for [reveal]. */
    private var viewLeft = 0f
    private var viewWidth = 0f

    /** True if the last drawn row of cards is wider than the screen and scrolls sideways. */
    val scrollable: Boolean get() = maxScroll > 0f

    /** Scrolls the card row by [dx] px (positive shows cards further right). */
    fun scrollBy(dx: Float) {
        scroll = (scroll + dx).coerceIn(0f, maxScroll)
    }

    /** Scrolls so that the card of scenery [id] is fully visible (screen readers moving their focus onto it). */
    fun reveal(id: String) {
        val card = targetOf(id) ?: return
        if (card.left < viewLeft) scrollBy(card.left - viewLeft)
        else if (card.right > viewLeft + viewWidth) scrollBy(card.right - viewLeft - viewWidth)
    }

    /** Text sizes (px at scale 1) and the card height in dp for cards [cardWDp] wide, with a preview [ratio] as high. */
    private inner class CardText(cardWDp: Float, val ratio: Float) {
        val name = scale.px(15f)
        val era = scale.px(12f)
        val desc = scale.px(11.5f)
        val status = scale.px(12f)
        /** Height of the card face in dp: preview, name, era, two description rows, a gap, two status rows, the bar. */
        val heightDp = (8f + (cardWDp - 16f) * ratio + 8f) +
            (name * 1.3f + era * 1.35f + 2 * desc * 1.3f + status * 0.8f + 2 * status * 1.3f) / density + 8f + 8f
    }

    /**
     * Draws the picker; [pack] labels the pill that buys every scenery, null hides it; [mode] labels the pill next to the
     * back pill that switches the game mode ([MODE], docs/TOP100.md C4), null hides it. It stays inside [safe].
     */
    fun draw(
        canvas: Canvas, title: String, back: String, cards: List<SceneryCard>, hint: String?, width: Int, height: Int, pressed: String?,
        pack: String? = null, safe: ViewInsets = ViewInsets.NONE, mode: String? = null,
    ) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        targets.clear()
        drawnNodes.clear()
        val areaW = width - safe.left - safe.right
        val areaH = height - safe.top - safe.bottom
        val wDp = areaW / density
        val hDp = areaH / density
        // Large text needs wider cards and leaves less room for the preview.
        val k = scale.factor(15f)
        val ratio = if (k > 1.15f) PREVIEW_RATIO_LARGE_TEXT else PREVIEW_RATIO
        val minCard = MIN_CARD_W_DP * k.coerceAtMost(1.8f)
        val maxCard = MAX_CARD_W_DP * k.coerceAtMost(1.6f)
        val pillH = maxOf(TOUCH_DP, scale.px(15f) / density + 20f)
        val titleSize = scale.px(24f)
        val titleDp = maxOf(pillH, titleSize / density * 1.3f) + 12f
        val hintDp = if (hint != null) scale.px(14f) / density * 2f else 12f
        // The pack pill moves below the back pill when both do not fit side by side (large text, narrow window).
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = scale.px(15f)
        val backDp = maxOf(TOUCH_DP, text.measureText(back) / density + 40f)
        val packDp = pack?.let { maxOf(TOUCH_DP, text.measureText(it) / density + 40f) } ?: 0f
        val modeDp = mode?.let { maxOf(TOUCH_DP, text.measureText(it) / density + 40f) } ?: 0f
        // The mode pill sits right of the back pill, or on its own row below it when both do not fit.
        val modeRow = mode != null && backDp + 12f + modeDp > wDp - 2 * MARGIN_DP
        val leftGroupDp = if (mode != null && !modeRow) backDp + 12f + modeDp else backDp
        val packRow = pack != null && leftGroupDp + packDp + 12f > wDp - 2 * MARGIN_DP
        val pillRows = 1 + (if (modeRow) 1 else 0) + (if (packRow) 1 else 0)
        val pillsDp = pillRows * pillH + (pillRows - 1) * 8f
        fun widthFor(cols: Int) = (wDp - 2 * MARGIN_DP - (cols - 1) * GAP_DP) / cols
        // In a grid the title gets its own line under the pills, so it never runs into them.
        fun heightFor(cols: Int, cardW: Float): Float {
            val rows = (cards.size + cols - 1) / cols
            val titleH = if (rows > 1) pillsDp + 8f + titleDp else maxOf(titleDp, pillsDp + 12f)
            return MARGIN_DP + titleH + rows * (CardText(cardW, ratio).heightDp + SLAB_DP) + (rows - 1) * ROW_GAP_DP + hintDp
        }
        // One row while cards stay wide enough (landscape); otherwise a balanced grid (3 + 2, or two columns in a
        // portrait window) if it needs little shrinking; otherwise one row that scrolls sideways (large text on a phone).
        var cols: Int
        var cardW: Float
        var scrolling = false
        if (widthFor(cards.size) >= minCard) {
            cols = cards.size
            cardW = widthFor(cols).coerceAtMost(maxCard)
        } else {
            val fitCols = (cards.size downTo 1).first { n -> n == 1 || widthFor(n) >= minCard }
            val gridRows = (cards.size + fitCols - 1) / fitCols
            cols = (cards.size + gridRows - 1) / gridRows
            cardW = widthFor(cols).coerceAtMost(maxCard)
            if (hDp / heightFor(cols, cardW) < MIN_GRID_SCALE) {
                cols = cards.size
                cardW = minCard.coerceAtMost(wDp - 2 * MARGIN_DP)
                scrolling = true
            }
        }
        val rows = (cards.size + cols - 1) / cols
        val wrapped = rows > 1
        val metrics = CardText(cardW, ratio)
        val cardH = metrics.heightDp + SLAB_DP
        val naturalH = heightFor(cols, cardW)
        val s = minOf(1f, hDp / naturalH)
        val u = density * s
        val rowW = (cols * cardW + (cols - 1) * GAP_DP) * u
        viewLeft = safe.left + MARGIN_DP * u
        viewWidth = areaW - 2 * MARGIN_DP * u
        maxScroll = if (scrolling) (rowW - viewWidth).coerceAtLeast(0f) else 0f
        scroll = scroll.coerceIn(0f, maxScroll)
        val left = if (scrolling) viewLeft - scroll else safe.left + (areaW - rowW) / 2f
        // Pills and title span the row, or the visible area while the row scrolls.
        val barLeft = if (scrolling) viewLeft else left
        val barW = if (scrolling) viewWidth else rowW
        val top = safe.top + (areaH - naturalH * u) / 2f + MARGIN_DP * u

        // The pills keep their 48 dp touch height at any scale; the rest of the screen shrinks around them.
        val pillPx = maxOf(pillH * u, TOUCH_DP * density)
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = scale.px(15f) * s
        val backW = maxOf(TOUCH_DP * density, text.measureText(back) + 40f * u)
        r.set(barLeft, top, barLeft + backW, top + pillPx)
        pill(canvas, back, pressed == BACK, u)
        targets += RectF(r) to BACK
        drawnNodes += UiNode("scenery:$BACK", RectF(r), back, UiNode.Kind.BUTTON)
        var leftGroupRight = r.right
        if (mode != null) {
            text.textSize = scale.px(15f) * s
            val modeW = maxOf(TOUCH_DP * density, text.measureText(mode) + 40f * u).coerceAtMost(barW)
            if (modeRow) r.set(barLeft, top + pillPx + 8f * u, barLeft + modeW, top + 2 * pillPx + 8f * u)
            else r.set(leftGroupRight + 12f * u, top, leftGroupRight + 12f * u + modeW, top + pillPx)
            if (!modeRow) leftGroupRight = r.right
            pill(canvas, mode, pressed == MODE, u)
            targets += RectF(r) to MODE
            drawnNodes += UiNode("scenery:$MODE", RectF(r), mode, UiNode.Kind.BUTTON)
        }
        var packLeft = barLeft + barW
        if (pack != null) {
            text.textSize = scale.px(15f) * s
            val packW = maxOf(TOUCH_DP * density, text.measureText(pack) + 40f * u).coerceAtMost(barW)
            val packTop = if (packRow) top + (pillRows - 1) * (pillPx + 8f * u) else top
            r.set(barLeft + barW - packW, packTop, barLeft + barW, packTop + pillPx)
            if (!packRow) packLeft = r.left
            pill(canvas, pack, pressed == PACK, u, primary = true)
            targets += RectF(r) to PACK
            drawnNodes += UiNode("scenery:$PACK", RectF(r), pack, UiNode.Kind.BUTTON)
        }

        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT_BOLD
        text.color = ink
        val pillsBottom = top + pillRows * pillPx + (pillRows - 1) * 8f * u
        val titleTop = if (wrapped) pillsBottom + 8f * u else top
        // Centered between the pills, so it never runs into them.
        val titleLeft = if (wrapped) barLeft else leftGroupRight + 12f * u
        val titleRight = if (wrapped) barLeft + barW else packLeft - 12f * u
        val titleRoom = titleRight - titleLeft
        text.textSize = titleSize * s
        if (text.measureText(title) > titleRoom) text.textSize = maxOf(text.textSize * titleRoom / text.measureText(title), text.textSize * 0.7f)
        val shown = fit(title, titleRoom)
        val half = text.measureText(shown) / 2f
        val cx = if (titleLeft + half <= titleRight - half) (width / 2f).coerceIn(titleLeft + half, titleRight - half) else (titleLeft + titleRight) / 2f
        val titleBaseline = titleTop + (if (wrapped) titleDp * u else pillPx) / 2f + text.textSize * 0.35f
        canvas.drawText(shown, cx, titleBaseline, text)
        drawnNodes += UiNode("scenery:title", RectF(cx - half, titleTop, cx + half, titleBaseline + text.textSize * 0.3f), title, UiNode.Kind.HEADING)

        val gridTop = if (wrapped) titleTop + titleDp * u else maxOf(top + titleDp * u, pillsBottom + 12f * u)
        for ((i, card) in cards.withIndex()) {
            val row = i / cols
            val inRow = minOf(cols, cards.size - row * cols)
            // A shorter last row is centered.
            val rowLeft = left + (cols - inRow) * (cardW + GAP_DP) * u / 2f
            val x = rowLeft + (i % cols) * (cardW + GAP_DP) * u
            val cardTop = gridTop + row * (cardH + ROW_GAP_DP) * u
            r.set(x, cardTop, x + cardW * u, cardTop + (cardH - SLAB_DP) * u)
            drawCard(canvas, card, pressed == card.scenario.id, u, s, metrics)
            val bounds = RectF(x, cardTop, x + cardW * u, cardTop + cardH * u)
            targets += bounds to card.scenario.id
            drawnNodes += UiNode("scenery:${card.scenario.id}", RectF(bounds), cardText(card), UiNode.Kind.BUTTON)
        }
        val lastBottom = gridTop + (rows * cardH + (rows - 1) * ROW_GAP_DP) * u
        hint?.let {
            text.textAlign = Paint.Align.CENTER
            text.typeface = Typeface.DEFAULT_BOLD
            text.color = muted
            text.textSize = scale.px(14f) * s
            val baseline = lastBottom + text.textSize * 1.5f
            canvas.drawText(fit(it, areaW - 2 * MARGIN_DP * u), width / 2f, baseline, text)
            drawnNodes += UiNode("scenery:hint", RectF(safe.left, baseline - text.textSize, width - safe.right, baseline + text.textSize * 0.3f), it, UiNode.Kind.TEXT)
        }
    }

    /** What a screen reader says for a card: name, era, description, status, and that it is locked. */
    private fun cardText(card: SceneryCard): String =
        (listOf(card.name, card.era, card.description) + card.status + listOfNotNull(card.lockedLabel)).joinToString(", ")

    private fun drawCard(canvas: Canvas, card: SceneryCard, down: Boolean, u: Float, s: Float, m: CardText) {
        val sink = if (down) SLAB_DP * 0.8f * u else 0f
        val radius = 16f * u
        fillP.color = 0x26000000
        canvas.drawRoundRect(r.left + 4f * u, r.top + 12f * u, r.right + 4f * u, r.bottom + 12f * u, radius, radius, fillP)
        fillP.color = if (card.unlocked) 0xFFD5DAD2.toInt().shade(-0.1f) else 0xFFD9DBD8.toInt()
        canvas.drawRoundRect(r.left, r.top + SLAB_DP * u, r.right, r.bottom + SLAB_DP * u, radius, radius, fillP)
        r.offset(0f, sink)
        fillP.color = if (card.unlocked) 0xFFFAFAF7.toInt() else 0xFFF1F2EE.toInt()
        canvas.drawRoundRect(r, radius, radius, fillP)

        val pad = 8f * u
        val inner = r.width() - 2 * pad
        val preview = RectF(r.left + pad, r.top + pad, r.right - pad, r.top + pad + inner * m.ratio)
        val bmp = previewOf(card.scenario, preview.width().toInt().coerceAtLeast(1), preview.height().toInt().coerceAtLeast(1))
        canvas.save()
        clip.reset()
        clip.addRoundRect(preview, radius * 0.6f, radius * 0.6f, Path.Direction.CW)
        canvas.clipPath(clip)
        canvas.drawBitmap(bmp, null, preview, if (card.unlocked) bitmapP else greyed)
        canvas.restore()
        if (!card.unlocked) padlock(canvas, preview.centerX(), preview.centerY(), preview.height() * 0.22f)

        val cx = r.centerX()
        var y = preview.bottom + pad
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT_BOLD
        text.color = if (card.unlocked) ink else muted
        text.textSize = m.name * s
        text.textSize = maxOf(MIN_NAME_SP * u, minOf(m.name * s, m.name * s * inner / text.measureText(card.name)))
        y += m.name * s
        canvas.drawText(fit(card.name, inner), cx, y, text)
        y += m.name * s * 0.3f
        text.textSize = m.era * s
        text.color = if (card.unlocked) accent.shade(-0.2f) else muted
        y += m.era * s * 1.1f
        canvas.drawText(fit(card.era, inner), cx, y, text)
        y += m.era * s * 0.25f
        text.typeface = Typeface.DEFAULT
        text.color = muted
        text.textSize = m.desc * s
        for (line in wrap(card.description, inner, 2)) {
            y += m.desc * s * 1.3f
            canvas.drawText(line, cx, y - m.desc * s * 0.25f, text)
        }
        // Status rows sit at the bottom, above the progress bar, clear of the description.
        y = r.bottom - pad - 8f * u - 2 * m.status * s * 1.3f
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = m.status * s
        text.color = if (card.unlocked) ink else muted
        for (line in card.status.take(2)) {
            y += m.status * s * 1.3f
            canvas.drawText(fit(line, inner), cx, y - m.status * s * 0.3f, text)
            text.typeface = Typeface.DEFAULT
        }
        card.progress?.let { p ->
            val bar = RectF(r.left + 2 * pad, r.bottom - pad - 4f * u, r.right - 2 * pad, r.bottom - pad + 1f * u)
            fillP.color = 0x22262B33
            canvas.drawRoundRect(bar, bar.height() / 2, bar.height() / 2, fillP)
            if (p > 0f) {
                fillP.color = accent
                canvas.drawRoundRect(bar.left, bar.top, bar.left + bar.width() * p.coerceIn(0.04f, 1f), bar.bottom, bar.height() / 2, bar.height() / 2, fillP)
            }
        }
    }

    /** A white padlock on a dark disc. */
    private fun padlock(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        fillP.color = 0xCC262B33.toInt()
        canvas.drawCircle(cx, cy, size * 1.35f, fillP)
        fillP.color = 0xFFFFFFFF.toInt()
        val w = size * 0.95f
        canvas.drawRoundRect(cx - w / 2, cy - size * 0.1f, cx + w / 2, cy + size * 0.65f, size * 0.12f, size * 0.12f, fillP)
        lineP.color = 0xFFFFFFFF.toInt()
        lineP.strokeWidth = size * 0.16f
        val sr = size * 0.3f
        canvas.drawArc(cx - sr, cy - size * 0.1f - sr * 1.5f, cx + sr, cy - size * 0.1f + sr * 0.5f, 180f, 180f, false, lineP)
        canvas.drawLine(cx - sr, cy - size * 0.1f - sr * 0.5f, cx - sr, cy - size * 0.05f, lineP)
        canvas.drawLine(cx + sr, cy - size * 0.1f - sr * 0.5f, cx + sr, cy - size * 0.05f, lineP)
        fillP.color = 0xFF262B33.toInt()
        canvas.drawCircle(cx, cy + size * 0.25f, size * 0.1f, fillP)
    }

    /** A pill button in [r]; a [primary] one is filled with the accent color like the main menu's first button. */
    private fun pill(canvas: Canvas, label: String, down: Boolean, u: Float, primary: Boolean = false) {
        val depth = 4f * u
        val sink = if (down) depth * 0.8f else 0f
        val radius = r.height() / 2f
        fillP.color = if (primary) accent.shade(-0.3f) else 0xFFD5DAD2.toInt()
        canvas.drawRoundRect(r.left, r.top + depth, r.right, r.bottom + depth, radius, radius, fillP)
        fillP.color = if (primary) accent else 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(r.left, r.top + sink, r.right, r.bottom + sink, radius, radius, fillP)
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT_BOLD
        text.color = if (primary) 0xFFFFFFFF.toInt() else ink
        canvas.drawText(fit(label, r.width() - 24f * u), r.centerX(), r.centerY() + sink + text.textSize * 0.35f, text)
    }

    /**
     * The start map of [s] in the isometric main style, rendered once per size. The preview uses a fixed seed, so
     * rivers and scattered terrain look like, but not exactly like, the next game.
     */
    private fun previewOf(s: Scenario, w: Int, h: Int): Bitmap {
        // The previews follow the color theme (docs/TOP100.md C5).
        val key = "${s.id}:$w:$h:${Cosmetic.theme}"
        return previews.getOrPut(key) {
            val world = World(s, seed = PREVIEW_SEED)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val iso = IsoRenderer()
            iso.layout(w, h, world)
            iso.camera.zoomBy(PREVIEW_ZOOM, w / 2f, h / 2f)
            iso.draw(Canvas(bmp), world, drag = null, time = 0f)
            bmp
        }
    }

    /** [s], shortened with an ellipsis if it is wider than [maxWidth] in the current text paint. */
    private fun fit(s: String, maxWidth: Float): String {
        if (text.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 1 && text.measureText(s, 0, end) + text.measureText(ELLIPSIS) > maxWidth) end--
        return s.substring(0, end).trimEnd() + ELLIPSIS
    }

    /** [s] broken at spaces into at most [maxLines] lines of [maxWidth]; the last one is shortened if needed. */
    private fun wrap(s: String, maxWidth: Float, maxLines: Int): List<String> {
        val lines = ArrayList<String>()
        var line = ""
        val words = s.split(' ')
        for ((i, word) in words.withIndex()) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (text.measureText(candidate) <= maxWidth || line.isEmpty()) {
                line = candidate
                continue
            }
            if (lines.size == maxLines - 1) {
                lines += fit((listOf(line) + words.subList(i, words.size)).joinToString(" "), maxWidth)
                return lines
            }
            lines += line
            line = word
        }
        if (line.isNotEmpty()) lines += fit(line, maxWidth)
        return lines
    }

    companion object {
        /** Target id of the back pill. */
        const val BACK = "back"
        /** Target id of the pill that buys the scenery pack. */
        const val PACK = "pack"
        /** Target id of the pill that switches the game mode. */
        const val MODE = "mode"
        private const val PREVIEW_SEED = 11L
        private const val MARGIN_DP = 16f
        private const val GAP_DP = 10f
        private const val MAX_CARD_W_DP = 180f
        /** Narrowest card in one row; below it the cards wrap into a grid. */
        private const val MIN_CARD_W_DP = 110f
        /** Android's minimum touch target. */
        private const val TOUCH_DP = 48f
        private const val ROW_GAP_DP = 16f
        private const val SLAB_DP = 6f
        private const val PREVIEW_RATIO = 0.62f
        /** Flatter previews with large text, so the texts keep their size. */
        private const val PREVIEW_RATIO_LARGE_TEXT = 0.42f
        /** A grid that would need to shrink more than this scrolls in one row instead. */
        private const val MIN_GRID_SCALE = 0.8f
        /** The start block fills the preview a bit beyond its edges. */
        private const val PREVIEW_ZOOM = 1.5f
        private const val MIN_NAME_SP = 12f
        private const val ELLIPSIS = "…"
    }
}
