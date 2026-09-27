package com.mininetworks.game.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.mininetworks.game.render.ViewInsets
import com.mininetworks.game.render.fill
import com.mininetworks.game.render.stroke

/**
 * The unlock toast (docs/TOP100.md C2): a dark pill that drops in at the top centre with a trophy, "Erfolg: …" and,
 * if the achievement unlocked a cosmetic, a second line naming it. Several unlocks queue up and show one after the
 * other for [SECONDS] each. Drawn on top of everything, in a game and in the menus; it takes no touches.
 */
class AchievementToast(private val scale: TextScale) {
    /** One toast: the headline and an optional second line. */
    data class Message(val title: String, val detail: String?)

    private val density = scale.density
    private val queue = ArrayDeque<Message>()
    private var current: Message? = null
    private var shownAt = 0f
    private val bg = fill(0xF0262B33.toInt())
    private val gold = fill(0xFFF5C542.toInt())
    private val goldLine = stroke(0xFFF5C542.toInt())
    private val titleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = Typeface.DEFAULT_BOLD }
    private val detailP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5DE9A.toInt() }
    private val r = RectF()

    /** The toast on screen now, for accessibility services and tests. */
    val showing: Message? get() = current

    /** Where the toast was drawn last, or null. */
    var bounds: RectF? = null
        private set

    /** True while the toast is fully in view (not sliding), when accessibility services get it. */
    var settled = false
        private set

    fun add(m: Message) {
        queue.addLast(m)
    }

    /** Moves on at animation time [time]: the next toast once the current one had its [SECONDS]. */
    fun update(time: Float) {
        val c = current
        if (c != null && time - shownAt < SECONDS) return
        current = queue.removeFirstOrNull()
        shownAt = time
    }

    fun draw(canvas: Canvas, width: Int, time: Float, safe: ViewInsets) {
        val m = current
        if (m == null) {
            bounds = null
            settled = false
            return
        }
        val t = time - shownAt
        // Slides down in 0.25 s, stays, slides up in the last 0.25 s.
        val slide = minOf(1f, t / SLIDE, (SECONDS - t) / SLIDE).coerceIn(0f, 1f)
        val u = density
        titleP.textSize = scale.px(15f)
        detailP.textSize = scale.px(13f)
        val maxW = width - safe.left - safe.right - 32f * u
        val iconR = maxOf(12f * u, titleP.textSize * 0.7f)
        val textW = maxOf(titleP.measureText(m.title), m.detail?.let { detailP.measureText(it) } ?: 0f)
        val w = minOf(maxW, textW + 2 * iconR + 44f * u)
        val h = maxOf(2 * iconR + 16f * u, titleP.textSize * 1.4f + (if (m.detail != null) detailP.textSize * 1.4f else 0f) + 16f * u)
        val cx = safe.left + (width - safe.left - safe.right) / 2f
        val top = safe.top + 10f * u - (h + 20f * u) * (1f - slide)
        r.set(cx - w / 2f, top, cx + w / 2f, top + h)
        canvas.drawRoundRect(r, h / 2f, h / 2f, bg)
        // A small gold trophy: cup, handles and foot.
        val ix = r.left + 14f * u + iconR
        val iy = r.centerY()
        canvas.drawCircle(ix, iy - iconR * 0.2f, iconR * 0.55f, gold)
        canvas.drawRect(ix - iconR * 0.55f, iy - iconR * 0.75f, ix + iconR * 0.55f, iy - iconR * 0.2f, gold)
        goldLine.strokeWidth = iconR * 0.14f
        canvas.drawCircle(ix - iconR * 0.62f, iy - iconR * 0.4f, iconR * 0.25f, goldLine)
        canvas.drawCircle(ix + iconR * 0.62f, iy - iconR * 0.4f, iconR * 0.25f, goldLine)
        canvas.drawRect(ix - iconR * 0.1f, iy + iconR * 0.3f, ix + iconR * 0.1f, iy + iconR * 0.6f, gold)
        canvas.drawRect(ix - iconR * 0.4f, iy + iconR * 0.58f, ix + iconR * 0.4f, iy + iconR * 0.75f, gold)
        val tx = ix + iconR + 12f * u
        val room = r.right - 18f * u - tx
        val lines = if (m.detail != null) 2 else 1
        val block = titleP.textSize * 1.2f + (if (lines == 2) detailP.textSize * 1.3f else 0f)
        var y = r.centerY() - block / 2f + titleP.textSize
        canvas.drawText(fit(m.title, room, titleP), tx, y, titleP)
        m.detail?.let {
            y += detailP.textSize * 1.35f
            canvas.drawText(fit(it, room, detailP), tx, y, detailP)
        }
        bounds = RectF(r)
        settled = slide >= 1f
    }

    private fun fit(s: String, maxWidth: Float, p: Paint): String {
        if (p.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 1 && p.measureText(s, 0, end) + p.measureText("…") > maxWidth) end--
        return s.substring(0, end).trimEnd() + "…"
    }

    companion object {
        const val SECONDS = 3.2f
        private const val SLIDE = 0.25f
    }
}
