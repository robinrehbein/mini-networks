package com.mininetworks.game.data

import android.content.Context

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

    private fun key(scenery: String) = "best_$scenery"

    companion object {
        /** The only scenery until P3.1 adds more ("Kleinstadt am Fluss", docs/PLAN.md 5.2). */
        const val DEFAULT_SCENERY = "river_town"
        private const val PREFS = "highscores"
    }
}
