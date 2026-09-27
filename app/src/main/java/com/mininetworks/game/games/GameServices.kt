package com.mininetworks.game.games

import com.mininetworks.game.game.CloudProgress
import com.mininetworks.game.game.LeaderboardScore

/**
 * Google Play Games Services (docs/TOP100.md C2, C3, C6): sign-in, achievements, leaderboards and a cloud save of the
 * player's progress. Everything is optional: the game plays fully offline and without an account (A8); the local
 * stores stay the truth and Play Games is a copy of them.
 *
 * Calls come from the game thread and return at once; the work runs on the main thread or a background thread of the
 * implementation. Callbacks ([onSignedIn], the one of [loadProgress]) may come on any thread: the game hands them to
 * its game thread. [PlayGameServices] is the Google Play implementation, [NoOpGameServices] the default for debug
 * builds, tests and builds whose games-ids.xml still holds placeholders.
 */
interface GameServices {
    /** False without Play Games: the menus then show no entry for it. */
    val available: Boolean

    /** True while a Play Games account is signed in. */
    val signedIn: Boolean

    /** Called once each time the player becomes signed in (silently at start or after [signIn]). */
    var onSignedIn: (() -> Unit)?

    /** Asks Play Games whether the player is signed in, without any UI (Play Games signs in automatically). */
    fun signInSilently()

    /** Signs in with the Play Games UI, e.g. from the leaderboards entry. */
    fun signIn()

    /** Unlocks the achievements [ids] (Achievements ids); returns those actually handed to Play Games. */
    fun unlock(ids: List<String>): List<String>

    /** Submits [score] to its leaderboard; false if that is not possible right now. */
    fun submit(score: LeaderboardScore): Boolean

    /** Shows all leaderboards (signing in first if needed). */
    fun showLeaderboards()

    /** Loads the cloud save; [onLoaded] gets it (conflicts already merged), or null if there is none or it failed. */
    fun loadProgress(onLoaded: (CloudProgress?) -> Unit)

    /** Merges [progress] into the cloud save and writes it; does nothing while signed out. */
    fun saveProgress(progress: CloudProgress)
}

/** No Play Games: never signed in, nothing is sent. The default of debug builds and tests. */
object NoOpGameServices : GameServices {
    override val available = false
    override val signedIn = false
    override var onSignedIn: (() -> Unit)?
        get() = null
        set(_) = Unit
    override fun signInSilently() = Unit
    override fun signIn() = Unit
    override fun unlock(ids: List<String>) = emptyList<String>()
    override fun submit(score: LeaderboardScore) = false
    override fun showLeaderboards() = Unit
    override fun loadProgress(onLoaded: (CloudProgress?) -> Unit) = onLoaded(null)
    override fun saveProgress(progress: CloudProgress) = Unit
}
