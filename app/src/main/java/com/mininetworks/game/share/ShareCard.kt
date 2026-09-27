package com.mininetworks.game.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.mininetworks.game.R
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World
import com.mininetworks.game.render.IsoRenderer
import com.mininetworks.game.render.ServiceColors
import com.mininetworks.game.render.Shapes
import com.mininetworks.game.ui.Texts
import java.text.NumberFormat

/**
 * The picture a player shares after a game (docs/TOP100.md D2): the network as the game drew it (iso style, the
 * player's cable skin and color theme) next to the score, the scenery, the year and week it reached, and the app's name.
 * [WIDTH] × [HEIGHT] (16:9, what messengers and social networks preview without cropping). Drawn on the game thread
 * with its own renderer, so the running camera is not touched.
 */
class ShareCard(private val context: Context) {
    private val texts = Texts(context)
    private val locale get() = context.resources.configuration.locales[0]

    private val paper = Paint().apply { shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), 0xFFF7F5F0.toInt(), 0xFFE9E5DC.toInt(), Shader.TileMode.CLAMP) }
    private val ink = text(0xFF262B33.toInt(), bold = true)
    private val sub = text(0xFF5B6674.toInt(), bold = false)
    private val accent = text(ACCENT, bold = true)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = 0x33262B33 }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22262B33 }
    private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33262B33; strokeWidth = 3f }

    private fun text(color: Int, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    /** The share card of [world] as it is now (typically right after its game over). */
    fun render(world: World, time: Float = 0f): Bitmap {
        val card = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(card)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paper)
        drawMap(c, world, time)
        drawPanel(c, world)
        return card
    }

    /** The text that goes with the picture into the share sheet. */
    fun message(world: World): String {
        val count = NumberFormat.getIntegerInstance(locale).format(world.delivered.toLong())
        return context.resources.getQuantityString(R.plurals.share_text, world.delivered, texts.scenario(world.scenario), count, world.year)
    }

    private fun drawMap(c: Canvas, world: World, time: Float) {
        val w = (MAP.width()).toInt()
        val h = (MAP.height()).toInt()
        val map = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        IsoRenderer().apply {
            density = MAP_DENSITY
            layout(w, h, world)
            draw(Canvas(map), world, drag = null, time = time)
        }
        val r = RectF(MAP)
        c.drawRoundRect(RectF(r).apply { offset(0f, 10f) }, CORNER, CORNER, shadow)
        c.save()
        c.clipPath(Path().apply { addRoundRect(r, CORNER, CORNER, Path.Direction.CW) })
        c.drawBitmap(map, r.left, r.top, null)
        c.restore()
        c.drawRoundRect(r, CORNER, CORNER, frame)
        map.recycle()
    }

    private fun drawPanel(c: Canvas, world: World) {
        val left = MARGIN
        val width = MAP.left - MARGIN - GAP
        var y = MARGIN

        // The app's mark: one dot per service in its color and shape, then the name.
        val services = listOf(Service.MAIL, Service.CALL, Service.GAMING, Service.STREAMING)
        services.forEachIndexed { i, s ->
            val cx = left + DOT + i * DOT * 2.6f
            dot.color = ServiceColors.of(s)
            c.drawCircle(cx, y + DOT, DOT, dot)
            dot.color = 0xFFFFFFFF.toInt()
            Shapes.draw(c, s.shape, cx, y + DOT, DOT * 0.5f, dot)
        }
        y += DOT * 2 + 34f
        ink.textSize = 48f
        y = line(c, context.getString(R.string.app_name), left, y, width, ink)

        // The score, big.
        y += 70f
        val count = NumberFormat.getIntegerInstance(locale).format(world.delivered.toLong())
        ink.textSize = 176f
        y = line(c, count, left, y, width, ink, gap = 0.95f)
        sub.textSize = 40f
        y = line(c, context.resources.getQuantityString(R.plurals.share_card_packets, world.delivered), left, y, width, sub)

        y += 34f
        c.drawLine(left, y, left + width, y, rule)
        y += 34f

        ink.textSize = 46f
        y = line(c, texts.scenario(world.scenario), left, y, width, ink)
        sub.textSize = 40f
        y = line(c, context.getString(R.string.share_card_date, world.year, world.week), left, y, width, sub)
        world.daily?.let { y = line(c, context.getString(R.string.share_card_daily, texts.rule(it.rule)), left, y, width, sub) }

        // An invitation at the bottom of the panel.
        accent.textSize = 40f
        line(c, context.getString(R.string.share_card_tagline), left, HEIGHT - MARGIN - accent.textSize * 0.25f, width, accent, fromBaseline = true)
    }

    /**
     * Draws [s] with its top at [top] (or its baseline at [top] if [fromBaseline]), shrunk to fit [width]; returns the
     * top of the next line.
     */
    private fun line(c: Canvas, s: String, x: Float, top: Float, width: Float, p: Paint, gap: Float = 1.3f, fromBaseline: Boolean = false): Float {
        val size = p.textSize
        while (p.measureText(s) > width && p.textSize > size * 0.5f) p.textSize *= 0.95f
        val baseline = if (fromBaseline) top else top - p.ascent()
        c.drawText(s, x, baseline, p)
        val next = top + p.textSize * gap
        p.textSize = size
        return next
    }

    companion object {
        const val WIDTH = 1600
        const val HEIGHT = 900
        private const val MARGIN = 64f
        private const val GAP = 48f
        private const val CORNER = 36f
        private const val DOT = 16f
        private const val MAP_DENSITY = 1.3f
        private const val ACCENT = 0xFF2E8B57.toInt()
        private val MAP = RectF(600f, MARGIN, WIDTH - MARGIN, HEIGHT - MARGIN)
    }
}
