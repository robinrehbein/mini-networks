package com.mininetworks.game.data

import android.content.Context

/** Player settings. The language is not stored: it follows the system. */
data class GameSettings(
    val sound: Boolean = true,
    val haptics: Boolean = true,
    /** Flat renderer instead of the isometric main style. */
    val overviewMode: Boolean = false,
    /** Alternative service colors that stay apart with red-green color blindness. */
    val colorblind: Boolean = false,
)

/** [GameSettings] in SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load() = GameSettings(
        sound = prefs.getBoolean(KEY_SOUND, true),
        haptics = prefs.getBoolean(KEY_HAPTICS, true),
        overviewMode = prefs.getBoolean(KEY_OVERVIEW, false),
        colorblind = prefs.getBoolean(KEY_COLORBLIND, false),
    )

    fun save(s: GameSettings) {
        prefs.edit()
            .putBoolean(KEY_SOUND, s.sound)
            .putBoolean(KEY_HAPTICS, s.haptics)
            .putBoolean(KEY_OVERVIEW, s.overviewMode)
            .putBoolean(KEY_COLORBLIND, s.colorblind)
            .apply()
    }

    private companion object {
        const val PREFS = "settings"
        const val KEY_SOUND = "sound"
        const val KEY_HAPTICS = "haptics"
        const val KEY_OVERVIEW = "overview_mode"
        const val KEY_COLORBLIND = "colorblind"
    }
}
