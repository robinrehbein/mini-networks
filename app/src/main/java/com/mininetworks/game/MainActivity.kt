package com.mininetworks.game

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.window.OnBackInvokedDispatcher
import com.mininetworks.game.monetization.PlayMonetization
import com.mininetworks.game.ui.GameView

class MainActivity : Activity() {

    private lateinit var gameView: GameView
    /** Ads and purchases; null in debug builds, which run with NoOpMonetization unless configured otherwise. */
    private var monetization: PlayMonetization? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gameView = GameView(this)
        gameView.onExit = ::finish
        if (BuildConfig.PLAY_MONETIZATION) {
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
        // Back walks the menus (game -> pause menu -> game, settings -> previous screen); on the main menu it exits.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { gameView.back() }
        }
    }

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
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
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
