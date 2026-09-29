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
 */
object InlineGlyphs {
    private const val BASE = ''
    private const val GAP = ' '
    private const val NBSP = ' '

    /** The marker for [s]'s pictogram, with the no-break space after it. */
    fun of(s: Service): String = "${BASE + s.ordinal}$NBSP"

    private fun serviceOf(c: Char): Service? = (c - BASE).let { if (it in Service.entries.indices) Service.entries[it] else null }

    private fun hasMarkers(text: String) = text.any { serviceOf(it) != null }

    /** The services of the markers in [text], in order. */
    fun services(text: String): List<Service> = if (hasMarkers(text)) text.mapNotNull(::serviceOf) else emptyList()

    /** [text] without its markers and the no-break spaces after them. */
    fun plain(text: String): String {
        if (!hasMarkers(text)) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (serviceOf(text[i]) != null) {
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
        if (!hasMarkers(text)) text else String(CharArray(text.length) { i -> if (serviceOf(text[i]) != null) GAP else text[i] })

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
}
