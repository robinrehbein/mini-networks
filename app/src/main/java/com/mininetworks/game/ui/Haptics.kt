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

    /**
     * One pulse for a [HapticFeedbackConstants] [kind]: a heavy click, a click or a light tick; [ERROR] is a double
     * buzz for an action the game refused, [ALARM] a long falling buzz for a lost game.
     */
    fun pulse(kind: Int) {
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        val effect = if (kind == ALARM) {
            VibrationEffect.createWaveform(longArrayOf(0, 120, 70, 90, 70, 260), intArrayOf(0, 255, 0, 200, 0, 140), -1)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(
                when (kind) {
                    ERROR -> VibrationEffect.EFFECT_DOUBLE_CLICK
                    HapticFeedbackConstants.LONG_PRESS -> VibrationEffect.EFFECT_HEAVY_CLICK
                    HapticFeedbackConstants.CLOCK_TICK -> VibrationEffect.EFFECT_TICK
                    else -> VibrationEffect.EFFECT_CLICK
                },
            )
        } else {
            when (kind) {
                ERROR -> VibrationEffect.createWaveform(longArrayOf(0, 25, 60, 25), intArrayOf(0, 220, 0, 220), -1)
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

    companion object {
        /**
         * A refused action (no budget, not invented yet, already connected …). Int.MIN_VALUE, so it cannot collide with
         * a [HapticFeedbackConstants] value (NO_HAPTICS is -1).
         */
        const val ERROR = Int.MIN_VALUE

        /** The game was lost: a long buzz in three falling pulses. */
        const val ALARM = Int.MIN_VALUE + 1
    }
}
