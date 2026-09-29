package com.mininetworks.game.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants

/**
 * Short vibrations through the [Vibrator] itself. View.performHapticFeedback follows the system's "touch feedback"
 * setting, which many phones ship switched off, so the game's own haptics switch did nothing there.
 */
internal class Haptics(context: Context) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** One pulse for a [HapticFeedbackConstants] [kind]: a heavy click, a click or a light tick. */
    fun pulse(kind: Int) {
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(
                when (kind) {
                    HapticFeedbackConstants.LONG_PRESS -> VibrationEffect.EFFECT_HEAVY_CLICK
                    HapticFeedbackConstants.CLOCK_TICK -> VibrationEffect.EFFECT_TICK
                    else -> VibrationEffect.EFFECT_CLICK
                },
            )
        } else {
            when (kind) {
                HapticFeedbackConstants.LONG_PRESS -> VibrationEffect.createOneShot(30, 255)
                HapticFeedbackConstants.CLOCK_TICK -> VibrationEffect.createOneShot(8, 90)
                else -> VibrationEffect.createOneShot(15, 180)
            }
        }
        try {
            v.vibrate(effect)
        } catch (_: SecurityException) {
            // No vibrate permission (should not happen): stay silent rather than crash.
        }
    }
}
