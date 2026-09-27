package com.mininetworks.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import com.mininetworks.game.data.HighscoreStore
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.World
import com.mininetworks.game.ui.GameView
import com.mininetworks.game.ui.menu.MenuAction
import com.mininetworks.game.ui.menu.Screen
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Tablets, foldables and wide screens (docs/TOP100.md A6) and large text (A7): the game HUD and the main menu in every
 * [FormFactor], and at font scale 2.0 the HUD and every menu on the narrowest phone, the tablet and the foldable.
 * Written to docs/screenshots/formfactors/:
 *   ./gradlew testDebugUnitTest --tests '*FormFactorScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "de")
@OptIn(DebugApi::class)
class FormFactorScreenshotTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val shots get() = File(System.getProperty("screenshots.dir") ?: "build/screenshots", "formfactors").apply { mkdirs() }

    @Before
    fun setUp() {
        SettingsStore(app).tutorialSeen = true
        HighscoreStore(app).submit(1187)
    }

    @Test
    fun hudAndMainMenuInEveryFormat() {
        for (ff in FormFactor.entries.filter { it.screenshot }) {
            RuntimeEnvironment.setQualifiers("+${ff.qualifiers}")
            shoot(ff, "hud") { view, bmp -> view.drawSnapshot(Canvas(bmp), busyHud(), bmp.width, bmp.height, time = 1.3f, style = "Iso") }
            shoot(ff, "menu") { view, bmp -> view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = null) }
            shoot(ff, "sceneries") { view, bmp ->
                view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = Screen.SCENERIES)
            }
            shoot(ff, "reward") { view, bmp -> view.drawSnapshot(Canvas(bmp), rewardWorld(), bmp.width, bmp.height, time = 1.3f, style = "Iso") }
        }
    }

    @Test
    fun largeTextOnPhoneTabletAndFoldable() {
        for (ff in listOf(FormFactor.PHONE_16_9, FormFactor.PHONE_20_9, FormFactor.TABLET_10, FormFactor.FOLDABLE)) {
            RuntimeEnvironment.setQualifiers("+${ff.qualifiers}")
            RuntimeEnvironment.setFontScale(2f)
            val suffix = "font200"
            shoot(ff, "hud-$suffix") { view, bmp -> view.drawSnapshot(Canvas(bmp), busyHud(), bmp.width, bmp.height, time = 1.3f, style = "Iso") }
            shoot(ff, "menu-$suffix") { view, bmp -> view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = null) }
            if (ff == FormFactor.PHONE_16_9) {
                shoot(ff, "settings-$suffix") { view, bmp ->
                    view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = null)
                    tap(view, view.menuTarget(MenuAction.SETTINGS)!!.centerX(), view.menuTarget(MenuAction.SETTINGS)!!.centerY())
                    bmp.eraseColor(0)
                    view.drawCurrent(Canvas(bmp))
                }
                // The second settings page with the view options and the cosmetics (docs/TOP100.md A7, C5).
                shoot(ff, "appearance-$suffix") { view, bmp ->
                    view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = Screen.SETTINGS)
                    tap(view, view.menuTarget(MenuAction.APPEARANCE)!!.centerX(), view.menuTarget(MenuAction.APPEARANCE)!!.centerY())
                    bmp.eraseColor(0)
                    view.drawCurrent(Canvas(bmp))
                }
                RuntimeEnvironment.setQualifiers("+en")
                shoot(ff, "appearance-$suffix-en") { view, bmp ->
                    view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = Screen.APPEARANCE)
                }
                RuntimeEnvironment.setQualifiers("+de")
                shoot(ff, "pause-$suffix") { view, bmp ->
                    view.drawSnapshot(Canvas(bmp), busyHud(), bmp.width, bmp.height, time = 1.3f, style = "Iso", screen = Screen.PAUSED)
                }
                shoot(ff, "sceneries-$suffix") { view, bmp ->
                    view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = Screen.SCENERIES)
                }
                shoot(ff, "tutorial-$suffix") { _, bmp ->
                    SettingsStore(app).tutorialSeen = false
                    val view = GameView(app)
                    val w = view.currentTutorial!!.world
                    view.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 0.3f, screen = null)
                    view.advance(1f)
                    bmp.eraseColor(0)
                    view.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 1.3f, screen = null)
                    SettingsStore(app).tutorialSeen = true
                }
                shoot(ff, "reward-$suffix") { view, bmp -> view.drawSnapshot(Canvas(bmp), rewardWorld(), bmp.width, bmp.height, time = 1.3f, style = "Iso") }
            }
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun tap(view: GameView, x: Float, y: Float) {
        view.injectTouch(MotionEvent.ACTION_DOWN, x, y)
        view.injectTouch(MotionEvent.ACTION_UP, x, y)
    }

    private fun shoot(ff: FormFactor, name: String, draw: (GameView, Bitmap) -> Unit) {
        val bmp = Bitmap.createBitmap(ff.widthPx, ff.heightPx, Bitmap.Config.ARGB_8888)
        draw(GameView(app), bmp)
        File(shots, "${ff.id}-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        /** The HUD with the most on it: every cable type, WLAN and mast buttons, routers in stock. */
        @OptIn(DebugApi::class)
        fun busyHud(): World = Scenes.hud().also { it.grant(0, extraRouters = 2, extraAccessPoints = 2, extraCellTowers = 1) }

        /** The week reward choice. */
        @OptIn(DebugApi::class)
        fun rewardWorld(): World = Scenes.hud().also {
            it.jumpToWeek(5)
            it.advanceToNextWeek()
        }
    }
}
