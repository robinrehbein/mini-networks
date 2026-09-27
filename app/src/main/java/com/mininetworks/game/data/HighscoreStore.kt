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

    /** Every best score by key (scenery id, "endless_<id>"), for the cloud save (docs/TOP100.md C6). */
    fun all(): Map<String, Int> = prefs.all.entries
        .filter { it.key.startsWith(PREFIX) && it.value is Int }
        .associate { it.key.removePrefix(PREFIX) to it.value as Int }
        .filterValues { it > 0 }

    /** Takes the scores of [scores] that beat the stored ones (a cloud save never lowers a best). */
    fun restore(scores: Map<String, Int>) {
        val e = prefs.edit()
        for ((key, score) in scores) if (score > best(key)) e.putInt(key(key), score)
        e.apply()
    }

    /** The scenery the player started last; the main menu shows its best score. */
    var lastScenery: String
        get() = prefs.getString(KEY_LAST, null) ?: DEFAULT_SCENERY
        set(value) = prefs.edit().putString(KEY_LAST, value).apply()

    private fun key(scenery: String) = "$PREFIX$scenery"

    companion object {
        /** "Kleinstadt am Fluss", the free first scenery (docs/PLAN.md 5.2). */
        val DEFAULT_SCENERY = Scenarios.RIVER_TOWN.id
        private const val PREFS = "highscores"
        private const val KEY_LAST = "last_scenery"
        private const val PREFIX = "best_"
    }
}
