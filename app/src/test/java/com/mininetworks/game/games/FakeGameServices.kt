package com.mininetworks.game.games

import com.mininetworks.game.game.CloudProgress
import com.mininetworks.game.game.LeaderboardScore

/**
 * A [GameServices] for tests: records every call; [cloud] is the save "in the cloud", and [saveProgress] merges into it
 * like the Play implementation does. Callbacks run right away on the calling thread.
 */
class FakeGameServices(
    override val available: Boolean = true,
    var cloud: CloudProgress? = null,
) : GameServices {
    override var signedIn = false
        private set
    override var onSignedIn: (() -> Unit)? = null
    val unlocked = ArrayList<String>()
    val scores = ArrayList<LeaderboardScore>()
    val saved = ArrayList<CloudProgress>()
    var leaderboardsShown = 0
    var signInRequests = 0
    var loads = 0

    /** Signs in as Play Games would after its automatic sign-in. */
    fun signInNow() {
        signedIn = true
        onSignedIn?.invoke()
    }

    override fun signInSilently() = Unit

    override fun signIn() {
        signInRequests++
        signInNow()
    }

    override fun unlock(ids: List<String>): List<String> {
        if (!signedIn) return emptyList()
        unlocked += ids
        return ids
    }

    override fun submit(score: LeaderboardScore): Boolean {
        if (!signedIn) return false
        scores += score
        return true
    }

    override fun showLeaderboards() {
        if (!signedIn) signIn()
        leaderboardsShown++
    }

    override fun loadProgress(onLoaded: (CloudProgress?) -> Unit) {
        loads++
        onLoaded(if (signedIn) cloud else null)
    }

    override fun saveProgress(progress: CloudProgress) {
        if (!signedIn) return
        saved += progress
        cloud = cloud?.let { CloudProgress.merge(it, progress) } ?: progress
    }
}
