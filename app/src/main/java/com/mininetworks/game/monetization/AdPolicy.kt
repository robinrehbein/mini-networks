package com.mininetworks.game.monetization

/**
 * When an interstitial may show (docs/PLAN.md 5.1): only between games, never after the first [FREE_GAMES] finished
 * games, then at most every [EVERY_GAMES]th game, and never once ads are removed. Pure logic: the counters are handed
 * in and read back for storage ([MonetizationStore]). A game counts as finished when the player leaves its game-over
 * card, so a game that goes on after a rewarded video counts once.
 */
class AdPolicy(gamesFinished: Int = 0, lastInterstitialGame: Int = 0) {
    /** Games finished so far. */
    var gamesFinished = gamesFinished; private set

    /** The value of [gamesFinished] when the last interstitial showed, 0 before the first. */
    var lastInterstitialGame = lastInterstitialGame; private set

    /** Counts one finished game. */
    fun gameFinished() {
        gamesFinished++
    }

    /** True if an interstitial may show now, right after [gameFinished]. */
    fun interstitialDue(adsRemoved: Boolean) =
        !adsRemoved && gamesFinished > FREE_GAMES && gamesFinished - lastInterstitialGame >= EVERY_GAMES

    /** Records that an interstitial showed after the current game. */
    fun interstitialShown() {
        lastInterstitialGame = gamesFinished
    }

    companion object {
        /** Games at the start that never end with an interstitial. */
        const val FREE_GAMES = 3

        /** At most one interstitial per this many games. */
        const val EVERY_GAMES = 3
    }
}
