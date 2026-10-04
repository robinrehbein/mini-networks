package com.mininetworks.game.ui

import android.graphics.Canvas
import android.graphics.Paint
import com.mininetworks.game.game.Service
import com.mininetworks.game.render.ServiceGlyphs

/**
 * Service pictograms inside a canvas-drawn text line, so a hint names a server with the same token its name plate
 * ([com.mininetworks.game.render.ServerLabels]) and the device's waiting requests show ("PC will ✉ Mail → Mail-Server").
 *
 * A text carries one private-use character per pictogram ([of]), followed by a no-break space so the pictogram never
 * ends up on another line than the name after it. For measuring and wrapping, [layout] turns every marker into an em
 * space (about as wide as the token); [drawTokens] then paints the tokens into those gaps, in order. Screen readers
 * and tests read the text [plain], without markers.
 *
 * The HUD's counters use two more markers: the budget's coin ([coin]) and the router's network sign ([router]), each
 * in front of its number ("Budget ● 42  ·  Router ● 3"), painted by [drawLine].
 */
object InlineGlyphs {
    private const val BASE = ''
    private const val GAP = ' '
    private const val NBSP = ' '
    private const val COIN = '\uE0F0'
    private const val ROUTER = '\uE0F1'

    /** The body colour of a router on the map ([com.mininetworks.game.render.DeviceIcons.router]). */
    private const val ROUTER_COLOR = 0xFF5C7FA8.toInt()
    /** The coin's rim: a darker ring keeps the gold disc visible on the white HUD plate. */
    private const val COIN_RIM = 0x59000000

    /** The marker for [s]'s pictogram, with the no-break space after it. */
    fun of(s: Service): String = "${BASE + s.ordinal}$NBSP"

    /** The marker for the budget's coin, with the no-break space after it. */
    fun coin(): String = "$COIN$NBSP"

    /** The marker for the router's network sign, with the no-break space after it. */
    fun router(): String = "$ROUTER$NBSP"

    private fun serviceOf(c: Char): Service? = (c - BASE).let { if (it in Service.entries.indices) Service.entries[it] else null }

    private fun isMarker(c: Char) = c == COIN || c == ROUTER || serviceOf(c) != null

    private fun hasMarkers(text: String) = text.any(::isMarker)

    /** The services of the markers in [text], in order. */
    fun services(text: String): List<Service> = if (hasMarkers(text)) text.mapNotNull(::serviceOf) else emptyList()

    /** [text] without its markers and the no-break spaces after them. */
    fun plain(text: String): String {
        if (!hasMarkers(text)) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (isMarker(text[i])) {
                i++
                if (i < text.length && text[i] == NBSP) i++
                continue
            }
            out.append(text[i++])
        }
        return out.toString()
    }

    /** [text] with every marker as an em space: same length, measurable and wrappable with any paint. */
    fun layout(text: String): String =
        if (!hasMarkers(text)) text else String(CharArray(text.length) { i -> if (isMarker(text[i])) GAP else text[i] })

    /**
     * Paints the tokens into the em spaces of [line] (a line of [layout] text drawn left-aligned at [x], baseline [y]
     * with [paint]), taking the services from [services] starting at [next]; returns the index of the next unused one.
     */
    fun drawTokens(canvas: Canvas, line: String, x: Float, y: Float, paint: Paint, services: List<Service>, next: Int): Int {
        var k = next
        var i = line.indexOf(GAP)
        while (i >= 0 && k < services.size) {
            val left = x + paint.measureText(line, 0, i)
            val w = paint.measureText(line, i, i + 1)
            ServiceGlyphs.token(canvas, services[k++], left + w / 2f, y - paint.textSize * 0.35f, paint.textSize * 0.5f, rim = false)
            i = line.indexOf(GAP, i + 1)
        }
        return k
    }

    private val coinFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val coinRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = COIN_RIM }

    /**
     * Paints the tokens of [text] from [start] to [end] into the gaps of its [layout] (that part drawn on one line,
     * left-aligned at [x], baseline [y] with [paint]): a service as its token, the coin as a disc in [coinColor] and
     * the router as its network sign. Allocates nothing, so the HUD can call it every frame.
     */
    fun drawLine(canvas: Canvas, text: String, layout: String, start: Int, end: Int, x: Float, y: Float, paint: Paint, coinColor: Int) {
        val cy = y - paint.textSize * 0.35f
        val r = paint.textSize * 0.42f
        for (i in start until end) {
            val c = text[i]
            if (!isMarker(c)) continue
            val cx = x + paint.measureText(layout, start, i) + paint.measureText(layout, i, i + 1) / 2f
            when (c) {
                COIN -> {
                    coinFill.color = coinColor
                    canvas.drawCircle(cx, cy, r, coinFill)
                    coinRim.strokeWidth = r * 0.16f
                    canvas.drawCircle(cx, cy, r * 0.92f, coinRim)
                }
                ROUTER -> ServiceGlyphs.networkSign(canvas, cx, cy, r, ROUTER_COLOR, ROUTER_COLOR)
                else -> ServiceGlyphs.token(canvas, serviceOf(c)!!, cx, cy, paint.textSize * 0.5f, rim = false)
            }
        }
    }
}
