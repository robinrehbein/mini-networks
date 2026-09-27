package com.mininetworks.game.review

import android.app.Activity
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Asks for a Play Store rating inside the app (In-App Review API, docs/TOP100.md D1). When to ask is decided by
 * [ReviewPolicy][com.mininetworks.game.game.ReviewPolicy] in :core; this only shows the dialog. [request] may be called
 * from any thread. Play decides on its own whether the dialog really appears (quota), and it never says whether the
 * player rated, so nothing comes back.
 */
fun interface ReviewPrompt {
    fun request()
}

/** Never asks: debug builds and tests. */
object NoOpReviewPrompt : ReviewPrompt {
    override fun request() = Unit
}

/** The Play In-App Review flow over [activity]. */
class PlayReviewPrompt(private val activity: Activity) : ReviewPrompt {
    private val manager by lazy { ReviewManagerFactory.create(activity) }

    override fun request() {
        activity.runOnUiThread {
            manager.requestReviewFlow().addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w(TAG, "no review flow", task.exception)
                    return@addOnCompleteListener
                }
                if (!activity.isFinishing) manager.launchReviewFlow(activity, task.result)
            }
        }
    }

    private companion object {
        const val TAG = "Review"
    }
}
