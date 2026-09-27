package com.mininetworks.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.mininetworks.game.R
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Scenarios
import com.mininetworks.game.game.World
import com.mininetworks.game.render.FormFactor
import com.mininetworks.game.render.FormFactorScreenshotTest
import com.mininetworks.game.render.ServiceColors
import com.mininetworks.game.ui.menu.Screen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * TalkBack on the canvas-drawn UI (docs/TOP100.md A7): menus, HUD buttons, scenery and reward cards are virtual views
 * with text, role and state; activating one does what a tap does; explore-by-touch finds the element under the finger.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "de-xxhdpi")
@OptIn(DebugApi::class)
class CanvasAccessibilityTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val bmp = Bitmap.createBitmap(2400, 1080, Bitmap.Config.ARGB_8888)
    private lateinit var view: GameView
    private val a11y get() = view.accessibilityLayer

    @Before
    fun setUp() {
        SettingsStore(app).tutorialSeen = true
        view = GameView(app)
        a11y.forceActive = true
    }

    @After
    fun tearDown() {
        ServiceColors.colorblind = false
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun info(key: String): AccessibilityNodeInfo {
        val id = a11y.idOf(key)
        assertNotEquals("no element $key in ${a11y.nodes.map { it.key }}", CanvasAccessibility.INVALID, id)
        return a11y.createAccessibilityNodeInfo(id)!!
    }

    private fun click(key: String): Boolean = a11y.performAction(a11y.idOf(key), AccessibilityNodeInfo.ACTION_CLICK, null)

    /** Lets the game thread take the input and draws the next frame. */
    private fun frame() {
        view.advance(0f)
        view.drawCurrent(Canvas(bmp))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun mainMenuEntriesAreButtonsThatWork() {
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 0f, screen = null)
        val root = a11y.createAccessibilityNodeInfo(View.NO_ID)!!
        assertEquals("every element is a child of the view", a11y.nodes.size, root.childCount)
        val title = info("menu:title")
        assertEquals(app.getString(R.string.app_name), title.text.toString())
        assertTrue("the card title is a heading", title.isHeading)
        val play = info("menu:PLAY")
        assertEquals(app.getString(R.string.menu_play), play.text.toString())
        assertEquals("android.widget.Button", play.className)
        assertTrue(play.isClickable && play.isEnabled)
        assertTrue(play.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK))
        val bounds = android.graphics.Rect()
        play.getBoundsInScreen(bounds)
        assertTrue("bounds as drawn: $bounds", bounds.width() > 0 && bounds.height() >= 48 * 3)
        // "Continue" without a save is shown but disabled: no click.
        val cont = info("menu:CONTINUE")
        assertFalse(cont.isEnabled)
        assertFalse(click("menu:CONTINUE"))

        assertTrue(click("menu:PLAY"))
        frame()
        assertEquals(Screen.SCENERIES, view.currentScreen)
        assertTrue("the picker's cards are offered now", a11y.nodes.any { it.key == "scenery:${Scenarios.RIVER_TOWN.id}" })
        assertFalse("the main menu's entries are gone", a11y.nodes.any { it.key.startsWith("menu:") })
        val locked = info("scenery:${Scenarios.ISLAND.id}").text.toString()
        assertTrue("a locked card says so: $locked", locked.contains(app.getString(R.string.a11y_locked)))
        assertTrue(click("scenery:${Scenarios.RIVER_TOWN.id}"))
        frame()
        assertEquals(Screen.PLAYING, view.currentScreen)
    }

    @Test
    fun settingsTogglesAreSwitchesWithTheirState() {
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 0f, screen = Screen.APPEARANCE)
        val toggle = info("menu:TOGGLE_COLORBLIND")
        assertEquals("android.widget.Switch", toggle.className)
        assertTrue(toggle.isCheckable)
        @Suppress("DEPRECATION")
        assertFalse(toggle.isChecked)
        assertTrue(click("menu:TOGGLE_COLORBLIND"))
        frame()
        assertTrue("the colorblind palette is on", ServiceColors.colorblind)
        @Suppress("DEPRECATION")
        assertTrue(info("menu:TOGGLE_COLORBLIND").isChecked)
    }

    @Test
    fun hudButtonsSayWhatTheyDoAndWork() {
        val world = FormFactorScreenshotTest.busyHud()
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 0f, style = "Iso")
        assertEquals(app.getString(R.string.a11y_menu), info("hud:menu").text.toString())
        val isdn = info("hud:cable:ISDN")
        assertTrue("the picked cable is selected", isdn.isSelected)
        assertEquals(app.getString(R.string.a11y_cable, "ISDN", CableType.ISDN.costPerCell), isdn.text.toString())
        assertFalse(info("hud:cable:DSL").isSelected)
        assertTrue(info("hud:status").text.toString().contains(world.budget.toString()))
        assertTrue("the map says what is on it", info("hud:map").text.toString().contains(world.nodes.size.toString()))

        assertTrue(click("hud:cable:DSL"))
        frame()
        assertEquals(CableType.DSL, view.pickedCable)
        assertTrue(info("hud:cable:DSL").isSelected)
        assertTrue("picking a cable explains it in the hint, a live region", a11y.nodes.any { it.key == CanvasAccessibility.LIVE })
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, info(CanvasAccessibility.LIVE).liveRegion)

        assertTrue(click("hud:pause"))
        frame()
        assertTrue(view.pausedInPlace)
        @Suppress("DEPRECATION")
        assertTrue(info("hud:pause").isChecked)

        assertTrue(click("hud:router"))
        frame()
        assertTrue("router placing armed", info("hud:router").isSelected)

        assertTrue(click("hud:menu"))
        frame()
        assertEquals(Screen.PAUSED, view.currentScreen)
        assertTrue(a11y.nodes.any { it.key == "menu:RESUME" })
    }

    @Test
    fun rewardCardsCanBeChosen() {
        val world = FormFactorScreenshotTest.rewardWorld()
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 0f, style = "Iso")
        assertNotNull(world.rewardOffer)
        assertEquals(setOf("reward:title", "reward:prompt", "reward:0", "reward:1", "hud:menu"), a11y.nodes.map { it.key }.toSet())
        assertTrue(click("reward:1"))
        frame()
        assertNull(world.rewardOffer)
    }

    @Test
    fun tutorialCanBeSkipped() {
        SettingsStore(app).tutorialSeen = false
        view = GameView(app)
        a11y.forceActive = true
        view.drawSnapshot(Canvas(bmp), view.currentTutorial!!.world, bmp.width, bmp.height, time = 0f, screen = null)
        assertTrue(info("tutorial:title").isHeading)
        assertTrue(info("tutorial:text").text.isNotEmpty())
        assertTrue(click("tutorial:skip"))
        frame()
        assertEquals(Screen.MAIN_MENU, view.currentScreen)
        assertNull(view.currentTutorial)
    }

    @Test
    fun exploreByTouchFindsTheElementUnderTheFinger() {
        view.drawSnapshot(Canvas(bmp), World(Scenarios.RIVER_TOWN, seed = 2L), bmp.width, bmp.height, time = 0f)
        val menu = view.hudTarget("menu")!!
        assertEquals(a11y.idOf("hud:menu"), a11y.idAt(menu.centerX(), menu.centerY()))
        assertEquals("elsewhere the map", a11y.idOf("hud:map"), a11y.idAt(bmp.width / 2f, bmp.height / 2f))
        val hover = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_HOVER_ENTER, menu.centerX(), menu.centerY(), 0)
        assertTrue(a11y.onHover(hover))
        hover.recycle()
        // Accessibility focus moves with TalkBack's swipes; the focused element reports it.
        val id = a11y.idOf("hud:menu")
        assertTrue(a11y.performAction(id, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
        assertTrue(a11y.createAccessibilityNodeInfo(id)!!.isAccessibilityFocused)
        assertTrue(a11y.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)!!.isAccessibilityFocused)
    }

    @Test
    fun idsStayStableAcrossFrames() {
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 0f, screen = null)
        val play = a11y.idOf("menu:PLAY")
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1f, screen = Screen.SETTINGS)
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 2f, screen = Screen.MAIN_MENU)
        assertEquals(play, a11y.idOf("menu:PLAY"))
    }

    @Test
    fun nothingIsCollectedWithoutAnAccessibilityService() {
        a11y.forceActive = false
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 0f, screen = null)
        assertTrue(a11y.nodes.isEmpty())
    }

    @Test
    fun focusingAScrolledAwaySceneryCardScrollsItIn() {
        RuntimeEnvironment.setQualifiers("+${FormFactor.PHONE_16_9.qualifiers}")
        RuntimeEnvironment.setFontScale(2f)
        view = GameView(app)
        a11y.forceActive = true
        val phone = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        view.drawSnapshot(Canvas(phone), view.currentWorld, phone.width, phone.height, time = 0f, screen = Screen.SCENERIES)
        val last = "scenery:${Scenarios.all.last().id}"
        val before = a11y.nodes.first { it.key == last }.bounds
        assertTrue("large text on a 16:9 phone: the row scrolls, the last card starts off screen ($before)", before.right > phone.width)
        a11y.performAction(a11y.idOf(last), AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        view.advance(0f)
        view.drawCurrent(Canvas(phone))
        val after = a11y.nodes.first { it.key == last }.bounds
        assertTrue("scrolled in: $after", after.left >= 0f && after.right <= phone.width)

        // A sideways drag scrolls too, and does not start a scenery.
        val first = a11y.nodes.first { it.key == "scenery:${Scenarios.RIVER_TOWN.id}" }.bounds
        view.injectTouch(MotionEvent.ACTION_DOWN, phone.width / 2f, first.centerY())
        view.injectTouch(MotionEvent.ACTION_MOVE, phone.width / 2f + 300f, first.centerY())
        view.injectTouch(MotionEvent.ACTION_MOVE, phone.width / 2f + 900f, first.centerY())
        view.injectTouch(MotionEvent.ACTION_UP, phone.width / 2f + 900f, first.centerY())
        view.drawCurrent(Canvas(phone))
        assertEquals(Screen.SCENERIES, view.currentScreen)
        assertTrue("dragged back", a11y.nodes.first { it.key == last }.bounds.right > after.right)
    }
}
