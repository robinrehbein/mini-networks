package com.mininetworks.game.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.mininetworks.game.game.CellGeneration

/**
 * The generation sign on a cell tower: a white pill with "3G", "4G" or "5G" in the generation's color, so an old tower
 * worth upgrading stands out on the map. The text is the same in every language.
 */
object CellBadges {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    fun color(g: CellGeneration) = when (g) {
        CellGeneration.G3 -> 0xFF8A7A5C.toInt()
        CellGeneration.G4 -> 0xFF1FA39A.toInt()
        CellGeneration.G5 -> 0xFF7A4FD6.toInt()
    }

    /** Draws the sign of [g] centered on [x], [y] with half height [r]. */
    fun draw(c: Canvas, g: CellGeneration, x: Float, y: Float, r: Float) {
        val col = color(g)
        val w = r * 1.55f
        rect.set(x - w, y - r, x + w, y + r)
        fill.color = 0x30000000
        c.drawRoundRect(rect.left + r * 0.12f, rect.top + r * 0.18f, rect.right + r * 0.12f, rect.bottom + r * 0.18f, r, r, fill)
        fill.color = if (g == CellGeneration.entries.last()) col else 0xFFFFFFFF.toInt()
        c.drawRoundRect(rect, r, r, fill)
        rim.color = col
        rim.strokeWidth = r * 0.2f
        c.drawRoundRect(rect, r, r, rim)
        text.color = if (g == CellGeneration.entries.last()) 0xFFFFFFFF.toInt() else col
        text.textSize = r * 1.25f
        c.drawText(g.label, x, y + text.textSize * 0.36f, text)
    }
}
