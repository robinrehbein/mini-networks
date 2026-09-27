package com.mininetworks.game.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import com.mininetworks.game.R

/**
 * The game's type (docs/TOP100.md B4, judge panel: "everything is default Roboto"): Nunito, a rounded, friendly face
 * (SIL Open Font License, docs/licenses-nunito-OFL.txt), for the title, headings and big numbers; Roboto stays for body
 * text. Scripts Nunito lacks (Japanese, Korean, Chinese) fall back to the system fonts glyph by glyph.
 */
object Fonts {
    private var display: Typeface? = null
    private var heavy: Typeface? = null

    /** Nunito Black: the game's name, dialog titles, hero numbers and store captions. */
    fun display(context: Context): Typeface = display ?: load(context, R.font.nunito_black).also { display = it }

    /** Nunito ExtraBold: HUD counters, card titles and amounts. */
    fun heavy(context: Context): Typeface = heavy ?: load(context, R.font.nunito_extrabold).also { heavy = it }

    private fun load(context: Context, id: Int): Typeface =
        try {
            context.resources.getFont(id)
        } catch (_: Resources.NotFoundException) {
            Typeface.DEFAULT_BOLD
        }
}
