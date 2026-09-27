package com.mininetworks.game.ui.menu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.mininetworks.game.render.ViewInsets
import com.mininetworks.game.render.fill
import com.mininetworks.game.render.shade
import com.mininetworks.game.render.stroke
import com.mininetworks.game.ui.TextScale
import com.mininetworks.game.ui.UiNode
import com.mininetworks.game.ui.TextWrap

/** One tile of the [AchievementsPanel]; all texts are ready to show. */
data class AchievementTile(
    val id: String,
    val title: String,
    val description: String,
    /** "37 / 100", or the date-free "Erreicht" once reached. */
    val progressText: String,
    /** 0..1 towards the target. */
    val progress: Float,
    val reached: Boolean,
    /** What reaching it unlocks ("Kabel-Skin Neon"), or null. */
    val reward: String?,
    /** Said by screen readers after the texts: reached or not. */
    val stateLabel: String,
)

/**
 * The achievements and missions screen (docs/TOP100.md C2), drawn on the game canvas in the look of the menu cards: a
 * back pill, the title and how many are reached, then a grid of tiles with title, what to do, a progress bar and the
 * cosmetic it unlocks. Reached tiles are bright with a check mark, the others muted. The grid scrolls vertically when
 * it does not fit. Only the back pill is tappable; [hit] is valid for the last drawn frame.
 */
class AchievementsPanel(context: Context) {
    private val scale = TextScale(context.resources.displayMetrics)
    private val density = scale.density
    private val ink = 0xFF262B33.toInt()
    private val muted = 0xFF5B6674.toInt()
    private val accent = 0xFF3BA55C.toInt()
    private val dim = fill(0xD9F3F1EC.toInt())
    private val fillP = fill(0)
    private val lineP = stroke(0)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    private val r = RectF()
    private val targets = ArrayList<Pair<RectF, String>>()
    private val drawnNodes = ArrayList<UiNode>()

    private var scroll = 0f
    private var maxScroll = 0f
    private var viewTop = 0f
    private var viewBottom = 0f
    private val tileBounds = HashMap<String, RectF>()

    /** [BACK] under ([x], [y]), or null. */
    fun hit(x: Float, y: Float): String? = targets.firstOrNull { it.first.contains(x, y) }?.second

    fun targetOf(id: String): RectF? = targets.firstOrNull { it.second == id }?.first

    /** Where the tile of achievement [id] was drawn last (maybe scrolled out of view), for tests. */
    fun tileOf(id: String): RectF? = tileBounds[id]?.let(::RectF)

    /** Back pill, title, count and every tile of the last drawn frame, for accessibility services and tests. */
    val nodes: List<UiNode> get() = drawnNodes

    /** True if the grid is taller than its area and scrolls. */
    val scrollable: Boolean get() = maxScroll > 0f

    /** Scrolls the grid by [dy] px (positive shows tiles further down). */
    fun scrollBy(dy: Float) {
        scroll = (scroll + dy).coerceIn(0f, maxScroll)
    }

    /** Back to the top, when the screen opens. */
    fun resetScroll() {
        scroll = 0f
    }

    /** Scrolls so that the tile of achievement [id] is fully visible (screen readers moving their focus onto it). */
    fun reveal(id: String) {
        val t = tileBounds[id] ?: return
        if (t.top < viewTop) scrollBy(t.top - viewTop) else if (t.bottom > viewBottom) scrollBy(t.bottom - viewBottom)
    }

    fun draw(
        canvas: Canvas, title: String, count: String, back: String, tiles: List<AchievementTile>, width: Int, height: Int,
        pressed: String?, safe: ViewInsets = ViewInsets.NONE,
    ) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        targets.clear()
        drawnNodes.clear()
        tileBounds.clear()
        val u = density
        val left = safe.left + MARGIN_DP * u
        val right = width - safe.right - MARGIN_DP * u
        val top = safe.top + MARGIN_DP * u
        val bottom = height - safe.bottom - MARGIN_DP * u
        val areaW = right - left

        // Header: back pill at the left, the title in the middle, the count at the right; they stack when too narrow.
        val pillH = maxOf(TOUCH_DP * u, scale.px(15f) + 20f * u)
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = scale.px(15f)
        val backW = maxOf(TOUCH_DP * u, text.measureText(back) + 40f * u)
        r.set(left, top, left + backW, top + pillH)
        pill(canvas, back, pressed == BACK, u)
        targets += RectF(r) to BACK
        drawnNodes += UiNode("achievement:$BACK", RectF(r), back, UiNode.Kind.BUTTON)
        text.textSize = scale.px(15f)
        val countW = text.measureText(count)
        text.textSize = scale.px(22f)
        val titleW = text.measureText(title)
        val oneRow = backW + titleW + countW + 48f * u <= areaW
        var y: Float
        if (oneRow) {
            text.textAlign = Paint.Align.CENTER
            text.color = ink
            val cx = (width / 2f).coerceIn(left + backW + 12f * u + titleW / 2f, right - countW - 12f * u - titleW / 2f)
            val base = top + pillH / 2f + text.textSize * 0.35f
            canvas.drawText(title, cx, base, text)
            drawnNodes += UiNode("achievement:title", RectF(cx - titleW / 2f, top, cx + titleW / 2f, top + pillH), title, UiNode.Kind.HEADING)
            text.textSize = scale.px(15f)
            text.textAlign = Paint.Align.RIGHT
            text.color = accent.shade(-0.25f)
            canvas.drawText(count, right, top + pillH / 2f + text.textSize * 0.35f, text)
            drawnNodes += UiNode("achievement:count", RectF(right - countW, top, right, top + pillH), count, UiNode.Kind.TEXT)
            y = top + pillH + 14f * u
        } else {
            y = top + pillH + 8f * u
            text.textAlign = Paint.Align.LEFT
            text.color = ink
            val shown = fit(title, areaW)
            canvas.drawText(shown, left, y + text.textSize, text)
            drawnNodes += UiNode("achievement:title", RectF(left, y, right, y + text.textSize * 1.3f), title, UiNode.Kind.HEADING)
            y += text.textSize * 1.3f
            text.textSize = scale.px(15f)
            text.color = accent.shade(-0.25f)
            canvas.drawText(count, left, y + text.textSize, text)
            drawnNodes += UiNode("achievement:count", RectF(left, y, left + countW, y + text.textSize * 1.4f), count, UiNode.Kind.TEXT)
            y += text.textSize * 1.4f + 8f * u
        }

        // The grid: as many columns of at least MIN_TILE_DP (grown with the text) as fit, at most three.
        val k = scale.factor(15f).coerceAtMost(2f)
        val minTile = MIN_TILE_DP * u * k
        val cols = ((areaW + GAP_DP * u) / (minTile + GAP_DP * u)).toInt().coerceIn(1, MAX_COLS)
        val tileW = (areaW - (cols - 1) * GAP_DP * u) / cols
        val titleSize = scale.px(15f)
        val descSize = scale.px(12.5f)
        val smallSize = scale.px(12f)
        val pad = 10f * u
        val iconR = maxOf(11f * u, titleSize * 0.62f)
        val textLeftPad = pad + 2 * iconR + 10f * u
        val inner = tileW - textLeftPad - pad
        text.typeface = Typeface.DEFAULT
        text.textSize = descSize
        // Each row is as tall as its tallest tile: one or two description lines, a reward line or none.
        fun heightOf(t: AchievementTile) = pad + titleSize * 1.3f + wrap(t.description, inner, 2).size * descSize * 1.3f + 6f * u +
            smallSize * 1.5f + (if (t.reward != null) smallSize * 1.4f else 0f) + pad
        val rows = (tiles.size + cols - 1) / cols
        val rowH = FloatArray(rows) { row -> tiles.subList(row * cols, minOf(tiles.size, row * cols + cols)).maxOf(::heightOf) }
        val rowTop = FloatArray(rows)
        for (i in 1 until rows) rowTop[i] = rowTop[i - 1] + rowH[i - 1] + ROW_GAP_DP * u
        val contentH = if (rows == 0) 0f else rowTop[rows - 1] + rowH[rows - 1]
        viewTop = y
        viewBottom = bottom
        maxScroll = (contentH - (viewBottom - viewTop)).coerceAtLeast(0f)
        scroll = scroll.coerceIn(0f, maxScroll)

        canvas.save()
        canvas.clipRect(safe.left, viewTop - 4f * u, width - safe.right, viewBottom + 4f * u)
        for ((i, t) in tiles.withIndex()) {
            val col = i % cols
            val row = i / cols
            val x = left + col * (tileW + GAP_DP * u)
            val ty = viewTop - scroll + rowTop[row]
            val tileH = rowH[row]
            r.set(x, ty, x + tileW, ty + tileH)
            tileBounds[t.id] = RectF(r)
            if (r.bottom >= viewTop - tileH && r.top <= viewBottom + tileH) {
                drawTile(canvas, t, u, pad, iconR, textLeftPad, inner, titleSize, descSize, smallSize)
            }
            drawnNodes += UiNode(
                "achievement:${t.id}", RectF(r),
                listOfNotNull(t.title, t.description, t.progressText, t.reward, t.stateLabel).joinToString(", "), UiNode.Kind.TEXT,
                checked = t.reached,
            )
        }
        canvas.restore()
        if (maxScroll > 0f) {
            // A thin scroll bar at the right edge says there is more.
            val trackH = viewBottom - viewTop
            val barH = maxOf(24f * u, trackH * trackH / (trackH + maxScroll))
            val barTop = viewTop + (trackH - barH) * (scroll / maxScroll)
            fillP.color = 0x55262B33
            canvas.drawRoundRect(right + 5f * u, barTop, right + 9f * u, barTop + barH, 2f * u, 2f * u, fillP)
        }
    }

    private fun drawTile(
        canvas: Canvas, t: AchievementTile, u: Float, pad: Float, iconR: Float, textLeftPad: Float, inner: Float,
        titleSize: Float, descSize: Float, smallSize: Float,
    ) {
        val radius = 14f * u
        fillP.color = 0x1F000000
        canvas.drawRoundRect(r.left + 2f * u, r.top + 4f * u, r.right + 2f * u, r.bottom + 4f * u, radius, radius, fillP)
        fillP.color = if (t.reached) 0xFFFAFAF7.toInt() else 0xFFEEF0EC.toInt()
        canvas.drawRoundRect(r, radius, radius, fillP)
        // Badge: a green disc with a check once reached, an outlined ring with the progress arc before.
        val cx = r.left + pad + iconR
        val cy = r.top + pad + iconR
        if (t.reached) {
            fillP.color = accent
            canvas.drawCircle(cx, cy, iconR, fillP)
            lineP.color = 0xFFFFFFFF.toInt()
            lineP.strokeWidth = iconR * 0.22f
            canvas.drawLine(cx - iconR * 0.42f, cy + iconR * 0.02f, cx - iconR * 0.1f, cy + iconR * 0.34f, lineP)
            canvas.drawLine(cx - iconR * 0.1f, cy + iconR * 0.34f, cx + iconR * 0.46f, cy - iconR * 0.3f, lineP)
        } else {
            lineP.color = 0x33262B33
            lineP.strokeWidth = iconR * 0.22f
            canvas.drawCircle(cx, cy, iconR * 0.85f, lineP)
            if (t.progress > 0f) {
                lineP.color = accent
                canvas.drawArc(cx - iconR * 0.85f, cy - iconR * 0.85f, cx + iconR * 0.85f, cy + iconR * 0.85f, -90f, 360f * t.progress, false, lineP)
            }
        }
        val x = r.left + textLeftPad
        var y = r.top + pad
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = titleSize
        text.color = if (t.reached) ink else muted.shade(-0.25f)
        canvas.drawText(fit(t.title, inner), x, y + titleSize, text)
        y += titleSize * 1.3f
        text.typeface = Typeface.DEFAULT
        text.textSize = descSize
        text.color = muted
        for (line in wrap(t.description, inner, 2)) {
            canvas.drawText(line, x, y + descSize, text)
            y += descSize * 1.3f
        }
        y += 6f * u
        // Progress bar with its numbers at the right.
        text.textSize = smallSize
        text.typeface = Typeface.DEFAULT_BOLD
        val label = t.progressText
        val lw = text.measureText(label)
        val barRight = r.right - pad - lw - 8f * u
        val barY = y + smallSize * 0.55f
        fillP.color = 0x22262B33
        canvas.drawRoundRect(x, barY - 2.5f * u, barRight, barY + 2.5f * u, 2.5f * u, 2.5f * u, fillP)
        if (t.progress > 0f) {
            fillP.color = accent
            canvas.drawRoundRect(x, barY - 2.5f * u, x + (barRight - x) * t.progress.coerceIn(0.03f, 1f), barY + 2.5f * u, 2.5f * u, 2.5f * u, fillP)
        }
        text.color = if (t.reached) accent.shade(-0.25f) else muted
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(label, r.right - pad, barY + smallSize * 0.35f, text)
        text.textAlign = Paint.Align.LEFT
        y += smallSize * 1.5f
        t.reward?.let {
            text.typeface = Typeface.DEFAULT
            text.textSize = smallSize
            text.color = if (t.reached) accent.shade(-0.3f) else muted
            canvas.drawText(fit(it, r.right - pad - x), x, y + smallSize, text)
        }
    }

    /** A pill button in [r]. */
    private fun pill(canvas: Canvas, label: String, down: Boolean, u: Float) {
        val depth = 4f * u
        val sink = if (down) depth * 0.8f else 0f
        val radius = r.height() / 2f
        fillP.color = 0xFFD5DAD2.toInt()
        canvas.drawRoundRect(r.left, r.top + depth, r.right, r.bottom + depth, radius, radius, fillP)
        fillP.color = 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(r.left, r.top + sink, r.right, r.bottom + sink, radius, radius, fillP)
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT_BOLD
        text.color = ink
        canvas.drawText(fit(label, r.width() - 24f * u), r.centerX(), r.centerY() + sink + text.textSize * 0.35f, text)
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

    companion object {
        /** Target id of the back pill. */
        const val BACK = "back"
        private const val MARGIN_DP = 16f
        private const val GAP_DP = 12f
        private const val ROW_GAP_DP = 10f
        private const val MIN_TILE_DP = 240f
        private const val MAX_COLS = 3
        private const val TOUCH_DP = 48f
        private const val ELLIPSIS = "…"
    }
}
