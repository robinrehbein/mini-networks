package com.mininetworks.game

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.mininetworks.game.monetization.PlayMonetization
import com.mininetworks.game.ui.GameView

class MainActivity : Activity() {

    private lateinit var gameView: GameView
    /** Ads and purchases; null in debug builds, which run with NoOpMonetization unless configured otherwise. */
    private var monetization: PlayMonetization? = null

    /** The registered [OnBackInvokedCallback] (Android 13+), null while back goes to the system; see [setBackHandling]. */
    private var backCallback: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (BuildConfig.DEBUG) DebugChecks.install()
        super.onCreate(savedInstanceState)
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

    override fun onDestroy() {
        monetization?.close()
        super.onDestroy()
    }
}
