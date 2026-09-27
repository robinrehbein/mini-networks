package com.mininetworks.game

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.content.ActivityNotFoundException
import android.util.Log
import com.mininetworks.game.games.GamesIds
import com.mininetworks.game.games.PlayGameServices
import com.mininetworks.game.monetization.PlayMonetization
import com.mininetworks.game.review.PlayReviewPrompt
import com.mininetworks.game.share.ShareSheet
import com.mininetworks.game.ui.GameView
import java.io.File

class MainActivity : Activity() {

    private lateinit var gameView: GameView
    /** Ads and purchases; null in debug builds, which run with NoOpMonetization unless configured otherwise. */
    private var monetization: PlayMonetization? = null
    /** Play Games; null in debug builds and while games-ids.xml holds placeholders (NoOpGameServices then). */
    private var games: PlayGameServices? = null

    /** The registered [OnBackInvokedCallback] (Android 13+), null while back goes to the system; see [setBackHandling]. */
    private var backCallback: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (BuildConfig.DEBUG) DebugChecks.install()
        super.onCreate(savedInstanceState)
        applyOrientation(resources.configuration)
        goEdgeToEdge()
        gameView = GameView(this)
        gameView.onExit = ::finish
        gameView.onBackHandlingChanged = ::setBackHandling
        if (BuildConfig.PLAY_MONETIZATION) {
            // Returns at once: consent runs on the main thread (the UMP SDK wants that), billing setup on GameIo.
            monetization = PlayMonetization(this).also {
                gameView.monetization = it
                it.start()
            }
        }
        if (BuildConfig.PLAY_SERVICES) {
            // Play Games only with real ids (docs/RELEASE.md 11); the SDK is initialized here, not by its provider.
            val ids = GamesIds(resources)
            if (ids.configured) {
                games = PlayGameServices(this, ids).also {
                    gameView.gameServices = it
                    it.start()
                }
            }
            gameView.reviewPrompt = PlayReviewPrompt(this)
        }
        gameView.onShare = ::share
        setContentView(gameView)
        // Recreated after all (process death, or a change not listed in the manifest's configChanges): go on from the
        // autosave in the pause menu instead of starting over on the main menu.
        savedInstanceState?.let(gameView::restoreState)
        hideSystemBars()
    }

    /**
     * Edge to edge on every version (target SDK 35+ enforces it from Android 15 on): the game draws behind the system
     * bars and into the display cutout; [GameView] keeps its HUD clear of the cutout.
     */
    private fun goEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Bars that peek in show over the game instead of on a solid strip.
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.TRANSPARENT
        }
    }

    /**
     * Predictive back (Android 13+, `android:enableOnBackInvokedCallback`): while the game uses back (in a game, in a
     * menu) a callback walks the menus (game -> pause menu -> game, settings -> previous screen); on the main menu it
     * is unregistered, so the system handles back itself and shows its back-to-home preview.
     */
    private fun setBackHandling(handled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val registered = backCallback as OnBackInvokedCallback?
        if (handled && registered == null) {
            val callback = OnBackInvokedCallback { gameView.back() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        } else if (!handled && registered != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(registered)
            backCallback = null
        }
    }

    /** True while the back callback is registered; for tests. */
    internal val backCallbackRegistered get() = backCallback != null

    @Deprecated("Replaced by OnBackInvokedDispatcher on Android 13+, still used below that.")
    override fun onBackPressed() {
        gameView.back()
    }

    /**
     * Full screen: the HUD layout assumes the whole surface. Called again whenever the window gets focus back, since an
     * ad, the billing sheet or the consent form can bring the system bars back (and some devices do on their own).
     */
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            gameView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    /** Landscape on phones, free rotation on large screens ([OrientationPolicy]). */
    private fun applyOrientation(config: Configuration) {
        val wanted = OrientationPolicy.forSmallestWidth(config.smallestScreenWidthDp)
        if (requestedOrientation != wanted) requestedOrientation = wanted
    }

    /** Folding, unfolding or resizing the window: the activity stays (manifest configChanges), the orientation adapts. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        monetization?.refresh()
        gameView.resume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        gameView.saveState(outState)
    }

    override fun onPause() {
        gameView.pause()
        super.onPause()
    }

    /** Opens the Android share sheet for the share card [file] (docs/TOP100.md D2). */
    private fun share(file: File, text: String) {
        if (isFinishing) return
        try {
            startActivity(ShareSheet.chooser(this, file, text))
        } catch (e: ActivityNotFoundException) {
            Log.w("Share", "no app to share with", e)
        } catch (e: IllegalArgumentException) {
            Log.w("Share", "share card not shareable", e)
        }
    }

    override fun onDestroy() {
        games?.close()
        monetization?.close()
        super.onDestroy()
    }
}
