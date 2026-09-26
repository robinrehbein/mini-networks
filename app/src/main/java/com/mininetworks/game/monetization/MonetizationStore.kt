package com.mininetworks.game.monetization

import android.content.Context

/** The [AdPolicy] counters and the last known owned products, in SharedPreferences. */
class MonetizationStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadPolicy() = AdPolicy(prefs.getInt(KEY_GAMES, 0), prefs.getInt(KEY_LAST_AD, 0))

    fun savePolicy(p: AdPolicy) {
        prefs.edit().putInt(KEY_GAMES, p.gamesFinished).putInt(KEY_LAST_AD, p.lastInterstitialGame).apply()
    }

    /**
     * Product ids owned at the last check with the store. Used until the store answers again at the next start, so
     * purchases work offline; the answer of the store replaces them.
     */
    var owned: Set<String>
        get() = prefs.getStringSet(KEY_OWNED, null)?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_OWNED, value).apply()

    private companion object {
        const val PREFS = "monetization"
        const val KEY_GAMES = "games_finished"
        const val KEY_LAST_AD = "last_interstitial_game"
        const val KEY_OWNED = "owned_products"
    }
}
