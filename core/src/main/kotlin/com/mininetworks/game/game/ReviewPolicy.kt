package com.mininetworks.game.game

/** What [ReviewPolicy] remembers between games: finished games and when the review was last asked for. */
data class ReviewState(val finishedGames: Int = 0, val lastAskedAt: Long? = null)

/**
 * A finished game as [ReviewPolicy] sees it: [score] delivered packets against the [previousBest] of the same board
 * (scenery or day), [weeks] played, and whether the game already [continued] once after a game over.
 */
data class FinishedGame(val score: Int, val previousBest: Int, val weeks: Int, val continued: Boolean = false)

/** The state to store after a game, and whether to ask for a review now. */
data class ReviewDecision(val state: ReviewState, val ask: Boolean)

/**
 * When to ask for a Play Store rating with the In-App Review API (docs/TOP100.md D1): only after a positive moment,
 * at most once every [MIN_DAYS_BETWEEN] days, never right after a frustrating game over.
 *
 * Positive: a new record (beating an earlier best, so a first game is no "record"), or, until the first request, any
 * finished game from the [FIRST_ASK_AFTER_GAMES]rd on (so a 3rd game that was frustrating moves the request to the
 * next good one instead of losing it). Frustrating: a game lost within [MIN_WEEKS] weeks, one that ended below half
 * of the best, or a game lost again after its second chance; these win over a record. The Play API decides itself
 * whether the dialog really shows (it has its own quota); the policy counts a request as asked either way.
 *
 * Only normal games and daily challenges count (the app calls [onGameOver] for them); endless and creative games have
 * no game over, the tutorial is no game.
 */
object ReviewPolicy {
    const val FIRST_ASK_AFTER_GAMES = 3
    const val MIN_DAYS_BETWEEN = 30
    const val MIN_WEEKS = 3
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    fun newRecord(g: FinishedGame) = g.previousBest > 0 && g.score > g.previousBest

    fun frustrating(g: FinishedGame) =
        g.weeks < MIN_WEEKS || g.continued || (g.previousBest > 0 && g.score * 2 < g.previousBest)

    /** True if at [now] the last request is [MIN_DAYS_BETWEEN] days ago (a clock set back never counts as later). */
    fun cooledDown(state: ReviewState, now: Long): Boolean {
        val last = state.lastAskedAt ?: return true
        return now >= last && now - last >= MIN_DAYS_BETWEEN * DAY_MILLIS
    }

    /**
     * Counts the finished game [g] at [now] and says whether to ask for a review right after it. A game lost again after
     * its second chance was already counted at its first game over.
     */
    fun onGameOver(state: ReviewState, g: FinishedGame, now: Long): ReviewDecision {
        val counted = if (g.continued) state else state.copy(finishedGames = state.finishedGames + 1)
        val positive = newRecord(g) || (counted.finishedGames >= FIRST_ASK_AFTER_GAMES && counted.lastAskedAt == null)
        val ask = positive && !frustrating(g) && cooledDown(counted, now)
        return ReviewDecision(if (ask) counted.copy(lastAskedAt = now) else counted, ask)
    }
}
