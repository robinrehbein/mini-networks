package com.mininetworks.game.games

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.games.PlayGames
import com.google.android.gms.games.PlayGamesSdk
import com.google.android.gms.games.SnapshotsClient
import com.google.android.gms.games.snapshot.Snapshot
import com.google.android.gms.games.snapshot.SnapshotMetadataChange
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.mininetworks.game.R
import com.mininetworks.game.game.CloudProgress
import com.mininetworks.game.game.LeaderboardScore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [GameServices] with Play Games Services v2. Only used once games-ids.xml holds real ids ([GamesIds.configured]); the
 * SDK's own start-up provider is removed in the manifest, so without them no Play Games code runs at all.
 *
 * Sign-in: Play Games v2 signs the player in automatically at start; [signInSilently] only asks for the result, and the
 * leaderboards entry offers [signIn] when the automatic sign-in did not happen. Achievements and scores use the SDK's
 * fire-and-forget calls, which it queues while offline. The cloud save (Saved Games, one snapshot
 * [CloudProgress.SNAPSHOT_NAME]) opens with manual conflict resolution: both versions are merged with
 * [CloudProgress.merge] (most progress per value, the newer day for the streak), so no device loses progress; a save
 * written by a newer app version is kept as it is. Snapshot work blocks on its own background thread, never on the UI
 * thread and never on GameIo (the autosave must not wait for the network).
 */
class PlayGameServices(private val activity: Activity, private val ids: GamesIds) : GameServices {
    private val main = Handler(Looper.getMainLooper())
    private val cloud = Executors.newSingleThreadExecutor { r -> Thread(r, "PlayGamesCloud").apply { isDaemon = true } }
    private val signInClient by lazy { PlayGames.getGamesSignInClient(activity) }
    private val snapshots by lazy { PlayGames.getSnapshotsClient(activity) }

    override val available = true
    @Volatile override var signedIn = false
        private set
    @Volatile override var onSignedIn: (() -> Unit)? = null

    /** Initializes the SDK and looks for the automatic sign-in; call from `onCreate`. */
    fun start() {
        PlayGamesSdk.initialize(activity)
        signInSilently()
    }

    override fun signInSilently() {
        main.post { signInClient.isAuthenticated().addOnCompleteListener { signedIn(it.isSuccessful && it.result.isAuthenticated) } }
    }

    override fun signIn() {
        main.post { signInClient.signIn().addOnCompleteListener { signedIn(it.isSuccessful && it.result.isAuthenticated) } }
    }

    private fun signedIn(now: Boolean) {
        val was = signedIn
        signedIn = now
        if (now && !was) onSignedIn?.invoke()
    }

    override fun unlock(ids: List<String>): List<String> {
        if (!signedIn) return emptyList()
        val sent = ids.mapNotNull { id -> this.ids.achievement(id)?.let { id to it } }
        if (sent.isNotEmpty()) main.post {
            val client = PlayGames.getAchievementsClient(activity)
            sent.forEach { client.unlock(it.second) }
        }
        return sent.map { it.first }
    }

    override fun submit(score: LeaderboardScore): Boolean {
        val id = ids.leaderboard(score.board)
        if (!signedIn || id == null) return false
        main.post {
            val client = PlayGames.getLeaderboardsClient(activity)
            val tag = score.tag
            if (tag == null) client.submitScore(id, score.score) else client.submitScore(id, score.score, tag)
        }
        return true
    }

    override fun showLeaderboards() {
        if (!signedIn) {
            main.post {
                signInClient.signIn().addOnCompleteListener {
                    signedIn(it.isSuccessful && it.result.isAuthenticated)
                    if (signedIn) openLeaderboards()
                }
            }
        } else {
            main.post(::openLeaderboards)
        }
    }

    private fun openLeaderboards() {
        PlayGames.getLeaderboardsClient(activity).allLeaderboardsIntent
            .addOnSuccessListener { if (!activity.isFinishing) activity.startActivityForResult(it, REQUEST_LEADERBOARDS) }
            .addOnFailureListener { Log.w(TAG, "leaderboards not available", it) }
    }

    override fun loadProgress(onLoaded: (CloudProgress?) -> Unit) {
        if (!signedIn || cloud.isShutdown) return onLoaded(null)
        cloud.execute {
            val progress = try {
                openResolved()?.let { snapshot ->
                    read(snapshot).also { await(snapshots.discardAndClose(snapshot)) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "cloud save not loaded", e)
                null
            }
            onLoaded(progress)
        }
    }

    override fun saveProgress(progress: CloudProgress) {
        if (!signedIn || cloud.isShutdown) return
        cloud.execute {
            try {
                val snapshot = openResolved() ?: return@execute
                val remote = read(snapshot)
                val merged = remote?.let { CloudProgress.merge(it, progress) } ?: progress
                if (remote != null && (remote.newerFormat || merged == remote)) {
                    await(snapshots.discardAndClose(snapshot))
                } else {
                    snapshot.snapshotContents.writeBytes(merged.encode())
                    await(snapshots.commitAndClose(snapshot, change(merged)))
                }
            } catch (e: Exception) {
                Log.w(TAG, "cloud save not written", e)
            }
        }
    }

    /**
     * Opens the snapshot on the cloud thread, resolving conflicts until there is none: two saves of the current format
     * are merged into the resolution; if one was written by a newer app version, that one wins as it is.
     */
    private fun openResolved(): Snapshot? {
        var result = await(snapshots.open(CloudProgress.SNAPSHOT_NAME, true, SnapshotsClient.RESOLUTION_POLICY_MANUAL))
        repeat(MAX_CONFLICTS) {
            if (!result.isConflict) return result.data
            val conflict = result.conflict ?: return null
            val a = read(conflict.snapshot)
            val b = read(conflict.conflictingSnapshot)
            result = when {
                b != null && b.newerFormat -> await(snapshots.resolveConflict(conflict.conflictId, conflict.conflictingSnapshot))
                a != null && a.newerFormat -> await(snapshots.resolveConflict(conflict.conflictId, conflict.snapshot))
                else -> {
                    val merged = when {
                        a == null -> b
                        b == null -> a
                        else -> CloudProgress.merge(a, b)
                    } ?: CloudProgress()
                    val contents = conflict.resolutionSnapshotContents
                    contents.writeBytes(merged.encode())
                    await(snapshots.resolveConflict(conflict.conflictId, conflict.snapshot.metadata.snapshotId, change(merged), contents))
                }
            }
        }
        return if (result.isConflict) null else result.data
    }

    private fun read(snapshot: Snapshot?): CloudProgress? = snapshot?.let { CloudProgress.decode(it.snapshotContents.readFully()) }

    private fun change(p: CloudProgress): SnapshotMetadataChange = SnapshotMetadataChange.Builder()
        .setDescription(activity.resources.getQuantityString(R.plurals.cloud_save_description, p.stats.delivered.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), p.stats.delivered))
        .setProgressValue(p.progress)
        .build()

    private fun <T> await(task: Task<T>): T = Tasks.await(task, TIMEOUT_SECONDS, TimeUnit.SECONDS)

    /** Stops the cloud thread; call from `onDestroy`. */
    fun close() {
        cloud.shutdown()
    }

    private companion object {
        const val TAG = "PlayGames"
        const val REQUEST_LEADERBOARDS = 9101
        const val MAX_CONFLICTS = 3
        const val TIMEOUT_SECONDS = 30L
    }
}
