package com.mininetworks.game.ui.menu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.Drawable
import com.mininetworks.game.R
import com.mininetworks.game.render.fill

/**
 * The game's logo mark: the launcher icon (server tower, glowing cables and packet on dusk blue) as a rounded tile
 * with a soft shadow, for the main menu's logotype above the game's name.
 */
class LogoMark(context: Context) {
    private val background: Drawable? = context.getDrawable(R.drawable.ic_launcher_background)
    private val foreground: Drawable? = context.getDrawable(R.drawable.ic_launcher_foreground)
    private val shadow = fill(0x33000000)
    private val clip = Path()

    /** Draws the mark centered on ([cx], [cy]), [size] px wide and high. */
    fun draw(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val half = size / 2f
        val radius = size * 0.24f
        canvas.drawRoundRect(cx - half, cy - half + size * 0.06f, cx + half, cy + half + size * 0.06f, radius, radius, shadow)
        // The adaptive layers are 108 units wide with the visible 72 in the middle: draw them 1.5 × larger, clipped.
        val full = size * 108f / 72f
        val l = (cx - full / 2f).toInt()
        val t = (cy - full / 2f).toInt()
        canvas.save()
        clip.reset()
        clip.addRoundRect(cx - half, cy - half, cx + half, cy + half, radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)
        for (d in listOfNotNull(background, foreground)) {
            d.setBounds(l, t, l + full.toInt(), t + full.toInt())
            d.draw(canvas)
        }
        canvas.restore()
    }
}
