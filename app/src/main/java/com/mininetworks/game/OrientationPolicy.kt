package com.mininetworks.game

import android.content.pm.ActivityInfo

/**
 * Which orientations the game asks for (docs/TOP100.md A6). Phones play in landscape, as designed. On large screens
 * (smallest width 600 dp and up: tablets, unfolded foldables, desktop windows) a locked orientation would letterbox
 * the game whenever the device is held or docked upright, so there it follows the device and the user's rotation
 * lock; the game lays out for portrait too. A foldable switches between both as it folds and unfolds
 * ([MainActivity.onConfigurationChanged]).
 */
object OrientationPolicy {
    /** Android's large-screen threshold (smallest width in dp). */
    const val LARGE_SCREEN_DP = 600

    fun forSmallestWidth(smallestWidthDp: Int): Int =
        if (smallestWidthDp >= LARGE_SCREEN_DP) ActivityInfo.SCREEN_ORIENTATION_FULL_USER else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
}
