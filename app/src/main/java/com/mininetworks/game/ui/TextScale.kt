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
class TextScale(private val metrics: DisplayMetrics) {
    val density get() = metrics.density

    /** Pixels for a text size of [sp]. */
    fun px(sp: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)

    /** Pixels for [dp]. */
    fun dp(dp: Float): Float = dp * metrics.density

    /** How much the system font size enlarges text of [sp] (1 at the default size). */
    fun factor(sp: Float): Float = px(sp) / dp(sp)
}
