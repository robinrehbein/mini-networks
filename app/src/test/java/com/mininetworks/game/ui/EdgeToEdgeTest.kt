package com.mininetworks.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Insets
import android.os.Looper
import android.os.StrictMode
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import com.mininetworks.game.MainActivity
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.Scenarios
import com.mininetworks.game.game.World
import com.mininetworks.game.ui.menu.MenuAction
import com.mininetworks.game.ui.menu.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Edge to edge and predictive back (docs/TOP100.md A5): the HUD keeps clear of a display cutout on any side, the
 * activity lays out into the cutout, and the back callback is registered exactly while the game has a use for back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EdgeToEdgeTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)

    @Before
    fun tutorialSeen() {
        SettingsStore(app).tutorialSeen = true
    }

    private fun cutout(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0): WindowInsets =
        WindowInsets.Builder().setInsets(WindowInsets.Type.displayCutout(), Insets.of(left, top, right, bottom)).build()

    private fun gameIn(view: GameView) {
        view.drawSnapshot(Canvas(bmp), World(Scenarios.RIVER_TOWN, seed = 2L), bmp.width, bmp.height, time = 0f)
    }

    @Test
    fun onlyTheCutoutCounts() {
        assertEquals(120f, GameView.safeInsetsOf(cutout(left = 120)).left)
        val bars = WindowInsets.Builder().setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 60, 0, 90)).build()
        assertEquals("hidden or peeking bars overlay the game, they take no room", 0f, GameView.safeInsetsOf(bars).top)
    }

    @Test
    fun hudStaysClearOfACutoutOnTheLeftOrRight() {
        val view = GameView(app)
        gameIn(view)
        val bare = view.hudTarget("cable:ISDN")!!.left
        // As the window delivers them (dispatch, not a direct call, which would fall back to the legacy path).
        view.dispatchApplyWindowInsets(cutout(left = 120))
        view.advance(0f)
        view.drawCurrent(Canvas(bmp))
        assertEquals("cable picker moves right by the cutout", bare + 120f, view.hudTarget("cable:ISDN")!!.left, 0.5f)
        val menuRight = view.hudTarget("menu")!!.right

        view.dispatchApplyWindowInsets(cutout(right = 140))
        view.advance(0f)
        view.drawCurrent(Canvas(bmp))
        assertEquals(bare, view.hudTarget("cable:ISDN")!!.left, 0.5f)
        assertEquals("menu button moves left by the cutout", menuRight - 140f, view.hudTarget("menu")!!.right, 0.5f)
        assertTrue(view.hudTarget("menu")!!.right <= bmp.width - 140f)
    }

    /** Menu cards, the scenery picker and the tutorial panel keep clear of a cutout too (docs/TOP100.md A6). */
    @Test
    fun menusAndPickerStayClearOfACutout() {
        val view = GameView(app)
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 0f, screen = null)
        view.dispatchApplyWindowInsets(cutout(left = 300))
        view.advance(0f)
        view.drawCurrent(Canvas(bmp))
        assertTrue("main menu card right of the cutout", view.menuTarget(MenuAction.PLAY)!!.left >= 300f)
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 0f, screen = Screen.SETTINGS)
        assertTrue("settings card right of the cutout", view.menuTarget(MenuAction.BACK)!!.left >= 300f)
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 0f, screen = Screen.SCENERIES)
        assertTrue("picker's back pill right of the cutout", view.sceneryTarget(com.mininetworks.game.ui.menu.SceneryPicker.BACK)!!.left >= 300f)

        SettingsStore(app).tutorialSeen = false
        val tutorial = GameView(app)
        tutorial.dispatchApplyWindowInsets(cutout(left = 300))
        tutorial.drawSnapshot(Canvas(bmp), tutorial.currentTutorial!!.world, bmp.width, bmp.height, time = 0f, screen = null)
        tutorial.advance(0f)
        tutorial.drawCurrent(Canvas(bmp))
        assertTrue("tutorial panel right of the cutout", tutorial.tutorialTarget(TutorialOverlay.SKIP)!!.left >= 300f)
    }

    @Test
    fun backIsHandledEverywhereButTheMainMenu() {
        val view = GameView(app)
        val seen = ArrayList<Boolean>()
        view.onBackHandlingChanged = { seen += it }
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 0f, screen = null)
        view.advance(0f)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("main menu: back leaves the app, the system animates it", listOf(false), seen)
        val play = view.menuTarget(MenuAction.PLAY)!!
        view.injectTouch(android.view.MotionEvent.ACTION_DOWN, play.centerX(), play.centerY())
        view.injectTouch(android.view.MotionEvent.ACTION_UP, play.centerX(), play.centerY())
        view.advance(0f)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Screen.SCENERIES, view.currentScreen)
        assertEquals(listOf(false, true), seen)
        view.back()
        view.advance(0f)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Screen.MAIN_MENU, view.currentScreen)
        assertEquals(listOf(false, true, false), seen)
    }

    @Test
    fun activityGoesEdgeToEdgeAndRegistersBackOnlyWhenNeeded() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
            activity.window.attributes.layoutInDisplayCutoutMode,
        )
        assertNotEquals("debug build: StrictMode is on", StrictMode.ThreadPolicy.LAX.toString(), StrictMode.getThreadPolicy().toString())
        val view = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as GameView
        assertFalse(activity.backCallbackRegistered)
        view.onBackHandlingChanged!!(true)
        assertTrue(activity.backCallbackRegistered)
        view.onBackHandlingChanged!!(false)
        assertFalse("main menu: the system handles back (predictive back-to-home)", activity.backCallbackRegistered)
        controller.pause().stop().destroy()
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
    }
}
