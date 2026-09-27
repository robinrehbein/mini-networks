package com.mininetworks.game.ui

import android.graphics.RectF

/**
 * One element of the canvas-drawn UI as accessibility services (TalkBack) see it (docs/TOP100.md A7): where it was
 * drawn in the last frame, what it says and what it is. [key] names it across frames ("menu:PLAY", "hud:router" ...);
 * activating a [Kind.BUTTON] or [Kind.TOGGLE] does what a tap on it does.
 */
data class UiNode(
    val key: String,
    val bounds: RectF,
    val text: String,
    val kind: Kind,
    /** A toggle's state. */
    val checked: Boolean = false,
    /** The picked cable technology, the armed placing button. */
    val selected: Boolean = false,
    val enabled: Boolean = true,
    /** True if the drawn label had to be shortened with "…" to fit (TalkBack still reads [text] in full). */
    val shortened: Boolean = false,
) {
    enum class Kind { TEXT, HEADING, BUTTON, TOGGLE }

    val actionable get() = enabled && (kind == Kind.BUTTON || kind == Kind.TOGGLE)
}
