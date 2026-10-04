package com.mininetworks.game.data

import android.content.Context
import com.mininetworks.game.game.CableSkin
import com.mininetworks.game.game.ColorTheme

/** Player settings. The language is not stored: it follows the system. */
data class GameSettings(
    val sound: Boolean = true,
    val haptics: Boolean = true,
    /** Flat renderer instead of the isometric main style. */
    val overviewMode: Boolean = false,
    /** Alternative service colors that stay apart with red-green color blindness. */
    val colorblind: Boolean = false,
    /** Two-finger rotation stays at any angle; off, the map snaps to the nearest multiple of 45° on release. */
    val freeRotation: Boolean = false,
    /** Cosmetic cable colors (docs/TOP100.md C5); only a skin unlocked by an achievement is used. */
    val cableSkin: CableSkin = CableSkin.CLASSIC,
    /** Cosmetic map colors (docs/TOP100.md C5). */
    val colorTheme: ColorTheme = ColorTheme.MEADOW,
)

/** [GameSettings] and whether the tutorial was seen, in SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load() = GameSettings(
        sound = prefs.getBoolean(KEY_SOUND, true),
        haptics = prefs.getBoolean(KEY_HAPTICS, true),
        overviewMode = prefs.getBoolean(KEY_OVERVIEW, false),
        colorblind = prefs.getBoolean(KEY_COLORBLIND, false),
        freeRotation = prefs.getBoolean(KEY_FREE_ROTATION, false),
        cableSkin = CableSkin.entries.firstOrNull { it.name == prefs.getString(KEY_CABLE_SKIN, null) } ?: CableSkin.CLASSIC,
        colorTheme = ColorTheme.entries.firstOrNull { it.name == prefs.getString(KEY_COLOR_THEME, null) } ?: ColorTheme.MEADOW,
    )

    /** True once the tutorial was finished, skipped or left; until then the app opens in it. */
    var tutorialSeen: Boolean
        get() = prefs.getBoolean(KEY_TUTORIAL_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_TUTORIAL_SEEN, value).apply()

    /** Keys of the one-time coaching tips already shown ([com.mininetworks.game.ui.Coaching.key]): each shows once per install. */
    val coachingSeen: Set<String>
        get() = prefs.getStringSet(KEY_COACHING_SEEN, null)?.toSet() ?: emptySet()

    /** Records that the coaching tip [key] was shown. */
    fun markCoachingSeen(key: String) {
        prefs.edit().putStringSet(KEY_COACHING_SEEN, coachingSeen + key).apply()
    }

    fun save(s: GameSettings) {
        prefs.edit()
            .putBoolean(KEY_SOUND, s.sound)
            .putBoolean(KEY_HAPTICS, s.haptics)
            .putBoolean(KEY_OVERVIEW, s.overviewMode)
            .putBoolean(KEY_COLORBLIND, s.colorblind)
            .putBoolean(KEY_FREE_ROTATION, s.freeRotation)
            .putString(KEY_CABLE_SKIN, s.cableSkin.name)
            .putString(KEY_COLOR_THEME, s.colorTheme.name)
            .apply()
    }

    private companion object {
        const val PREFS = "settings"
        const val KEY_SOUND = "sound"
        const val KEY_HAPTICS = "haptics"
        const val KEY_OVERVIEW = "overview_mode"
        const val KEY_COLORBLIND = "colorblind"
        const val KEY_FREE_ROTATION = "free_rotation"
        const val KEY_TUTORIAL_SEEN = "tutorial_seen"
        const val KEY_CABLE_SKIN = "cable_skin"
        const val KEY_COLOR_THEME = "color_theme"
        const val KEY_COACHING_SEEN = "coaching_seen"
    }
}
