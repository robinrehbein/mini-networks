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
import com.mininetworks.game.render.IsoRenderer
import com.mininetworks.game.render.fill
import com.mininetworks.game.render.shade
import com.mininetworks.game.render.stroke

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
)

/**
 * The scenery select screen, drawn on the game canvas in the look of the menu cards: a title with a back pill and one
 * card per scenery with an isometric preview of its start map, its era and what makes it special. Locked cards are
 * greyed out with a padlock and show how to unlock them. [hit] maps a tap to a scenery id or [BACK]; it is valid for
 * the last drawn frame.
 */
class SceneryPicker(context: Context) {
    private val density = context.resources.displayMetrics.density
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
    private val previews = HashMap<String, Bitmap>()

    /** The scenery id or [BACK] under ([x], [y]), or null. */
    fun hit(x: Float, y: Float): String? = targets.firstOrNull { it.first.contains(x, y) }?.second

    /** Where the card of scenery [id] (or the [BACK] pill) was drawn, or null. */
    fun targetOf(id: String): RectF? = targets.firstOrNull { it.second == id }?.first

    fun draw(canvas: Canvas, title: String, back: String, cards: List<SceneryCard>, hint: String?, width: Int, height: Int, pressed: String?) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        targets.clear()
        val wDp = width / density
        val hDp = height / density
        val cardW = ((wDp - 2 * MARGIN_DP - (cards.size - 1) * GAP_DP) / cards.size).coerceAtMost(MAX_CARD_W_DP)
        val naturalH = MARGIN_DP + TITLE_DP + CARD_H_DP + HINT_DP
        val s = minOf(1f, hDp / naturalH)
        val u = density * s
        val rowW = (cards.size * cardW + (cards.size - 1) * GAP_DP) * u
        var x = (width - rowW) / 2f
        val top = (height - naturalH * u) / 2f + MARGIN_DP * u

        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT_BOLD
        text.color = ink
        text.textSize = 24f * u
        canvas.drawText(title, width / 2f, top + 28f * u, text)
        text.textSize = 15f * u
        val backW = text.measureText(back) + 40f * u
        r.set(x, top + 2f * u, x + backW, top + 38f * u)
        pill(canvas, back, pressed == BACK, u)
        targets += RectF(r) to BACK

        val cardTop = top + TITLE_DP * u
        for (card in cards) {
            r.set(x, cardTop, x + cardW * u, cardTop + (CARD_H_DP - SLAB_DP) * u)
            drawCard(canvas, card, pressed == card.scenario.id, u)
            targets += RectF(x, cardTop, x + cardW * u, cardTop + CARD_H_DP * u) to card.scenario.id
            x += (cardW + GAP_DP) * u
        }
        hint?.let {
            text.textAlign = Paint.Align.CENTER
            text.typeface = Typeface.DEFAULT_BOLD
            text.color = muted
            text.textSize = 14f * u
            canvas.drawText(fit(it, width - 2 * MARGIN_DP * u), width / 2f, cardTop + (CARD_H_DP + 20f) * u, text)
        }
    }

    private fun drawCard(canvas: Canvas, card: SceneryCard, down: Boolean, u: Float) {
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
        val preview = RectF(r.left + pad, r.top + pad, r.right - pad, r.top + pad + inner * PREVIEW_RATIO)
        val bmp = previewOf(card.scenario, preview.width().toInt().coerceAtLeast(1), preview.height().toInt().coerceAtLeast(1))
        canvas.save()
        clip.reset()
        clip.addRoundRect(preview, radius * 0.6f, radius * 0.6f, Path.Direction.CW)
        canvas.clipPath(clip)
        canvas.drawBitmap(bmp, null, preview, if (card.unlocked) bitmapP else greyed)
        canvas.restore()
        if (!card.unlocked) padlock(canvas, preview.centerX(), preview.centerY(), preview.height() * 0.22f)

        val cx = r.centerX()
        var y = preview.bottom + 20f * u
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT_BOLD
        text.color = if (card.unlocked) ink else muted
        text.textSize = 15f * u
        text.textSize = maxOf(MIN_NAME_SP, minOf(15f, 15f * inner / text.measureText(card.name))) * u
        canvas.drawText(fit(card.name, inner), cx, y, text)
        y += 17f * u
        text.textSize = 12f * u
        text.color = if (card.unlocked) accent.shade(-0.2f) else muted
        canvas.drawText(fit(card.era, inner), cx, y, text)
        text.typeface = Typeface.DEFAULT
        text.color = muted
        text.textSize = 11.5f * u
        for (line in wrap(card.description, inner, 2)) {
            y += 15f * u
            canvas.drawText(line, cx, y, text)
        }
        y = r.bottom - pad - 30f * u
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = 12f * u
        text.color = if (card.unlocked) ink else muted
        for (line in card.status.take(2)) {
            canvas.drawText(fit(line, inner), cx, y, text)
            y += 15f * u
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
        canvas.drawText(label, r.centerX(), r.centerY() + sink + text.textSize * 0.35f, text)
    }

    /**
     * The start map of [s] in the isometric main style, rendered once per size. The preview uses a fixed seed, so
     * rivers and scattered terrain look like, but not exactly like, the next game.
     */
    private fun previewOf(s: Scenario, w: Int, h: Int): Bitmap {
        val key = "${s.id}:$w:$h"
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
        private const val PREVIEW_SEED = 11L
        private const val MARGIN_DP = 16f
        private const val TITLE_DP = 52f
        private const val CARD_H_DP = 232f
        private const val HINT_DP = 30f
        private const val GAP_DP = 10f
        private const val MAX_CARD_W_DP = 180f
        private const val SLAB_DP = 6f
        private const val PREVIEW_RATIO = 0.62f
        /** The start block fills the preview a bit beyond its edges. */
        private const val PREVIEW_ZOOM = 1.5f
        private const val MIN_NAME_SP = 12f
        private const val ELLIPSIS = "…"
    }
}
