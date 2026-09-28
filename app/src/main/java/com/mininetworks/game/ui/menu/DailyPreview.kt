package com.mininetworks.game.ui.menu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.mininetworks.game.game.DailyChallenge
import com.mininetworks.game.game.World
import com.mininetworks.game.render.Cosmetic
import com.mininetworks.game.render.IsoRenderer
import com.mininetworks.game.render.fill

/**
 * The daily challenge's card picture: today's map in colour (the real start map of the day's seed) with a flame badge
 * that counts the streak, so the card shows what the player will play instead of explaining it in prose.
 */
class DailyPreview(private val density: Float) {
    private var key: String? = null
    private var bitmap: Bitmap? = null
    private val bitmapP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val plate = fill(0xF2FFFFFF.toInt())
    private val shadow = fill(0x33000000)
    private val flameOuter = fill(0xFFF28C28.toInt())
    private val flameInner = fill(0xFFFFD166.toInt())
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; color = 0xFF262B33.toInt() }
    private val flame = Path()
    private val rect = RectF()

    /** Width to height of the picture. */
    val aspect = 1.6f

    /** Draws challenge [c]'s map into [r] with the flame badge showing [streak] days. */
    fun draw(canvas: Canvas, r: RectF, c: DailyChallenge, streak: Int) {
        val w = r.width().toInt().coerceAtLeast(1)
        val h = r.height().toInt().coerceAtLeast(1)
        val k = "${c.day}:$w:$h:${Cosmetic.theme}"
        if (k != key) {
            val world = World(c.scenario, seed = c.seed, daily = c)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val iso = IsoRenderer()
            iso.density = density
            iso.layout(w, h, world)
            iso.camera.zoomBy(1.05f, w / 2f, h / 2f)
            iso.draw(Canvas(bmp), world, drag = null, time = 0f)
            bitmap = bmp
            key = k
        }
        canvas.drawBitmap(bitmap!!, null, r, bitmapP)
        // The streak badge: a white pill with a flame and the day count, bottom left.
        val size = maxOf(r.height() * 0.13f, 14f * density)
        text.textSize = size * 0.95f
        val label = streak.toString()
        val pw = size * 1.9f + text.measureText(label)
        val ph = size * 1.6f
        rect.set(r.left + size * 0.5f, r.bottom - size * 0.5f - ph, r.left + size * 0.5f + pw, r.bottom - size * 0.5f)
        rect.offset(0f, 2f * density); canvas.drawRoundRect(rect, ph / 2f, ph / 2f, shadow)
        rect.offset(0f, -2f * density); canvas.drawRoundRect(rect, ph / 2f, ph / 2f, plate)
        val fx = rect.left + size * 0.85f
        val fy = rect.centerY() + size * 0.1f
        flamePath(fx, fy, size * 0.62f); canvas.drawPath(flame, flameOuter)
        flamePath(fx, fy + size * 0.14f, size * 0.36f); canvas.drawPath(flame, flameInner)
        canvas.drawText(label, rect.left + size * 1.5f, rect.centerY() + text.textSize * 0.36f, text)
    }

    /** A flame of half-height [s] around ([x], [y]): round at the bottom, pointed at the top. */
    private fun flamePath(x: Float, y: Float, s: Float) {
        flame.reset()
        flame.moveTo(x, y - s * 1.25f)
        flame.cubicTo(x + s * 0.35f, y - s * 0.6f, x + s * 0.85f, y - s * 0.2f, x + s * 0.8f, y + s * 0.35f)
        flame.cubicTo(x + s * 0.75f, y + s * 0.85f, x + s * 0.4f, y + s * 1.0f, x, y + s * 1.0f)
        flame.cubicTo(x - s * 0.4f, y + s * 1.0f, x - s * 0.75f, y + s * 0.85f, x - s * 0.8f, y + s * 0.35f)
        flame.cubicTo(x - s * 0.85f, y - s * 0.2f, x - s * 0.35f, y - s * 0.6f, x, y - s * 1.25f)
        flame.close()
    }
}
