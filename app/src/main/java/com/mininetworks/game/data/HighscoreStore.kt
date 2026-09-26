package com.mininetworks.game.data

import android.content.Context
import com.mininetworks.game.game.Scenarios

/** Best score (delivered packets) per scenery, in SharedPreferences. */
class HighscoreStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun best(scenery: String = DEFAULT_SCENERY) = prefs.getInt(key(scenery), 0)

    /** Records [score]; returns true if it beats the previous best. */
    fun submit(score: Int, scenery: String = DEFAULT_SCENERY): Boolean {
        if (score <= best(scenery)) return false
        prefs.edit().putInt(key(scenery), score).apply()
        return true
    }

    /** The scenery the player started last; the main menu shows its best score. */
    var lastScenery: String
        get() = prefs.getString(KEY_LAST, null) ?: DEFAULT_SCENERY
        set(value) = prefs.edit().putString(KEY_LAST, value).apply()

    private fun key(scenery: String) = "best_$scenery"

    companion object {
        /** "Kleinstadt am Fluss", the free first scenery (docs/PLAN.md 5.2). */
        val DEFAULT_SCENERY = Scenarios.RIVER_TOWN.id
        private const val PREFS = "highscores"
        private const val KEY_LAST = "last_scenery"
    }
}
