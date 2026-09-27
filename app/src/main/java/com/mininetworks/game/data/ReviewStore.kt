package com.mininetworks.game.data

import android.content.Context
import com.mininetworks.game.game.ReviewState

/**
 * The [ReviewState] of the rating request (docs/TOP100.md D1), in SharedPreferences. Created and read on the game
 * thread like the other stores; writes go through `apply()`. Backed up with the other progress (backup_rules.xml), so a
 * restored install keeps the 30-day pause instead of asking again right away.
 */
class ReviewStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var state: ReviewState
        get() = ReviewState(
            finishedGames = prefs.getInt(KEY_FINISHED, 0).coerceAtLeast(0),
            lastAskedAt = if (prefs.contains(KEY_ASKED)) prefs.getLong(KEY_ASKED, 0L) else null,
        )
        set(value) {
            val e = prefs.edit().putInt(KEY_FINISHED, value.finishedGames)
            value.lastAskedAt?.let { e.putLong(KEY_ASKED, it) } ?: e.remove(KEY_ASKED)
            e.apply()
        }

    private companion object {
        const val PREFS = "review"
        const val KEY_FINISHED = "finished_games"
        const val KEY_ASKED = "last_asked"
    }
}
