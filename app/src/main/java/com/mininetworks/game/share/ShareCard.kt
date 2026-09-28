package com.mininetworks.game.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import com.mininetworks.game.R
import com.mininetworks.game.game.World
import com.mininetworks.game.render.IsoRenderer
import com.mininetworks.game.render.ViewInsets
import com.mininetworks.game.ui.Texts
import com.mininetworks.game.ui.menu.LogoMark
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

    private val ink = text(0xFFFFFFFF.toInt(), bold = true)
    private val sub = text(0xFFD3E2EA.toInt(), bold = false)
    private val accent = text(ACCENT, bold = true)
    private val scrim = Paint()
    private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF; strokeWidth = 3f }
    private val logo = LogoMark(context)

    private fun text(color: Int, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setShadowLayer(6f, 0f, 2f, 0x66000000)
    }

    /**
     * The share card of [world] as it is now (typically right after its game over): the map full bleed, the network
     * framed right of the middle, and on the left a dusk-blue scrim with the app icon and name, the score, the scenery
     * and an invitation, so a shared card works as an advert for the game (judge panel).
     */
    fun render(world: World, time: Float = 0f): Bitmap {
        val card = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(card)
        drawMap(c, world, time)
        scrim.shader = LinearGradient(0f, 0f, SCRIM_END, 0f, intArrayOf(0xF2112634.toInt(), 0xD9112634.toInt(), 0x00112634), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, SCRIM_END, HEIGHT.toFloat(), scrim)
        scrim.shader = LinearGradient(0f, HEIGHT * 0.7f, 0f, HEIGHT.toFloat(), 0x00112634, 0x99112634.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, HEIGHT * 0.7f, WIDTH.toFloat(), HEIGHT.toFloat(), scrim)
        drawPanel(c, world)
        return card
    }

    /** The text that goes with the picture into the share sheet. */
    fun message(world: World): String {
        val count = NumberFormat.getIntegerInstance(locale).format(world.delivered.toLong())
        return context.resources.getQuantityString(R.plurals.share_text, world.delivered, texts.scenario(world.scenario), count, world.year)
    }

    private fun drawMap(c: Canvas, world: World, time: Float) {
        IsoRenderer().apply {
            density = MAP_DENSITY
            // The whole card is map; the network is framed in the part right of the text panel.
            layout(WIDTH, HEIGHT, world, ViewInsets(PANEL_W - 40f, MARGIN * 0.5f, MARGIN * 0.5f, MARGIN * 0.5f))
            camera.zoomBy(MAP_ZOOM, camera.centerX, camera.centerY)
            draw(c, world, drag = null, time = time)
        }
    }

    private fun drawPanel(c: Canvas, world: World) {
        val left = MARGIN
        val width = PANEL_W - MARGIN
        var y = MARGIN

        // The app icon and name.
        val icon = 92f
        logo.draw(c, left + icon / 2f, y + icon / 2f, icon)
        ink.textSize = 50f
        line(c, context.getString(R.string.app_name), left + icon + 24f, y + icon / 2f + ink.textSize * 0.36f, width - icon - 24f, ink, fromBaseline = true)
        y += icon + 56f

        // The score, big.
        val count = NumberFormat.getIntegerInstance(locale).format(world.delivered.toLong())
        ink.textSize = 190f
        y = line(c, count, left, y, width, ink, gap = 0.95f)
        sub.textSize = 42f
        y = line(c, context.resources.getQuantityString(R.plurals.share_card_packets, world.delivered), left, y, width, sub)

        y += 30f
        c.drawLine(left, y, left + width * 0.8f, y, rule)
        y += 30f

        ink.textSize = 48f
        y = line(c, texts.scenario(world.scenario), left, y, width, ink)
        sub.textSize = 40f
        y = line(c, context.getString(R.string.share_card_date, world.year, world.week), left, y, width, sub)
        world.daily?.let { y = line(c, context.getString(R.string.share_card_daily, texts.rule(it.rule)), left, y, width, sub) }

        // An invitation at the bottom of the panel.
        accent.textSize = 44f
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
        private const val MARGIN = 72f
        /** Width of the text panel on the left. */
        private const val PANEL_W = 620f
        /** Where the scrim behind the panel has faded out. */
        private const val SCRIM_END = 980f
        private const val MAP_DENSITY = 1.5f
        private const val MAP_ZOOM = 1.3f
        private const val ACCENT = 0xFF8FE3A8.toInt()
    }
}
