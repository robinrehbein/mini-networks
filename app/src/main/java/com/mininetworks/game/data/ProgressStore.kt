package com.mininetworks.game.data

import android.content.Context
import com.mininetworks.game.game.DailyStreak
import com.mininetworks.game.game.PlayerStats

/**
 * Achievement stats ([PlayerStats], docs/TOP100.md C2) and the daily challenge's streak and best scores (C1), in
 * SharedPreferences. Like the other stores it is created and read on the game thread; writes go through `apply()`,
 * which hands the disk work to a background thread.
 */
class ProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadStats(): PlayerStats = PlayerStats.decode(prefs.getString(KEY_STATS, null))

    fun saveStats(stats: PlayerStats) = prefs.edit().putString(KEY_STATS, stats.encode()).apply()

    var streak: DailyStreak
        get() = DailyStreak(
            lastDay = if (prefs.contains(KEY_STREAK_DAY)) prefs.getLong(KEY_STREAK_DAY, 0L) else null,
            current = prefs.getInt(KEY_STREAK_CURRENT, 0).coerceAtLeast(0),
            best = prefs.getInt(KEY_STREAK_BEST, 0).coerceAtLeast(0),
        )
        set(value) {
            val e = prefs.edit()
            value.lastDay?.let { e.putLong(KEY_STREAK_DAY, it) } ?: e.remove(KEY_STREAK_DAY)
            e.putInt(KEY_STREAK_CURRENT, value.current).putInt(KEY_STREAK_BEST, value.best).apply()
        }

    /** Best delivered packets in the daily challenge of [day], 0 if not played. Only the latest day is kept. */
    fun dailyBest(day: Long): Int = if (prefs.getLong(KEY_DAILY_DAY, Long.MIN_VALUE) == day) prefs.getInt(KEY_DAILY_BEST, 0) else 0

    /** The UTC day [dailyBest] is kept for, null before the first daily challenge. */
    val dailyDay: Long? get() = if (prefs.contains(KEY_DAILY_DAY)) prefs.getLong(KEY_DAILY_DAY, 0L) else null

    /** Takes a daily best from the cloud save (docs/TOP100.md C6): a later day replaces, the same day keeps the higher. */
    fun restoreDaily(day: Long?, best: Int) {
        if (day == null || best <= 0) return
        val stored = dailyDay
        if (stored != null && day < stored) return
        submitDaily(day, best)
    }

    /** Records [score] for the challenge of [day]; returns true if it beats that day's best. */
    fun submitDaily(day: Long, score: Int): Boolean {
        if (score <= dailyBest(day)) return false
        prefs.edit().putLong(KEY_DAILY_DAY, day).putInt(KEY_DAILY_BEST, score).apply()
        return true
    }

    private companion object {
        const val PREFS = "progress"
        const val KEY_STATS = "stats"
        const val KEY_STREAK_DAY = "streak_day"
        const val KEY_STREAK_CURRENT = "streak_current"
        const val KEY_STREAK_BEST = "streak_best"
        const val KEY_DAILY_DAY = "daily_day"
        const val KEY_DAILY_BEST = "daily_best"
    }
}
