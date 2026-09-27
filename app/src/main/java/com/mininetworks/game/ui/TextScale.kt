package com.mininetworks.game.ui

import android.util.DisplayMetrics
import android.util.TypedValue

/**
 * Text sizes for the canvas-drawn UI that follow the system font size (docs/TOP100.md A7). [px] converts sp like
 * platform text does: linear up to Android 13, on Android 14+ with the nonlinear curve that grows large text less than
 * small text. Layouts measure their rows from these sizes, so larger text makes rows taller instead of overlapping.
 * A change of the font size recreates the activity (fontScale is not in the manifest's configChanges), so the metrics
 * read once at creation stay valid.
 */
class TextScale(private val metrics: DisplayMetrics, smallestWidthDp: Int = 0) {
    /** Extra size of the whole UI on tablets ([uiScale]); 1 on phones. */
    val ui = uiScale(smallestWidthDp)

    /** Pixels per UI dp: the screen density times [ui]. */
    val density get() = metrics.density * ui

    /** Pixels for a text size of [sp]. */
    fun px(sp: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics) * ui

    /** Pixels for [dp]. */
    fun dp(dp: Float): Float = dp * density

    companion object {
        /**
         * The canvas UI grows on tablets like a smallest-width dimens set would (sw600dp, sw720dp): at phone size the
         * HUD, dialogs and menus would take a third of the relative room on a 10" screen and turn unreadable from a
         * distance or in a store thumbnail.
         */
        fun uiScale(smallestWidthDp: Int): Float = when {
            smallestWidthDp >= 720 -> 1.4f
            smallestWidthDp >= 600 -> 1.2f
            else -> 1f
        }

        /** The scale for [context]'s current configuration. */
        fun of(context: android.content.Context) =
            TextScale(context.resources.displayMetrics, context.resources.configuration.smallestScreenWidthDp)
    }

    /** How much the system font size enlarges text of [sp] (1 at the default size). */
    fun factor(sp: Float): Float = px(sp) / dp(sp)
}
