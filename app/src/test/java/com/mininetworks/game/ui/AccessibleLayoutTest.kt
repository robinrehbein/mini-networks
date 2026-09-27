package com.mininetworks.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.World
import com.mininetworks.game.monetization.Entitlements
import com.mininetworks.game.monetization.FakeMonetization
import com.mininetworks.game.render.FormFactor
import com.mininetworks.game.render.FormFactorScreenshotTest
import com.mininetworks.game.ui.menu.Screen
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Touch targets and overlap (docs/TOP100.md A6, A7) over the layout rectangles of every screen, in every [FormFactor]
 * (plus a portrait phone window) at font scale 1.0 and 2.0: every tappable element of the HUD, the menus, the scenery
 * picker, the reward choice and the tutorial is at least 48 × 48 dp, lies inside the screen (a scrolling card row
 * excepted), and no two elements (buttons or texts) overlap. The rectangles are the ones the frame was drawn with and
 * that taps and TalkBack use ([UiNode]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "de")
@OptIn(DebugApi::class)
class AccessibleLayoutTest {

    private val app get() = RuntimeEnvironment.getApplication()

    /** One screen size to check: [qualifiers] for Robolectric and the surface in px. */
    private data class Size(val id: String, val qualifiers: String, val width: Int, val height: Int)

    private val sizes = FormFactor.entries.map { Size(it.id, it.qualifiers, it.widthPx, it.heightPx) } +
        Size("phone-portrait", "w360dp-h800dp-port-xxhdpi", 1080, 2400)

    @Before
    fun setUp() {
        SettingsStore(app).tutorialSeen = true
    }

    @After
    fun resetFont() {
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun hudTargetsAreLargeAndApartInEveryFormat() = everywhere { size, view, bmp ->
        view.drawSnapshot(Canvas(bmp), FormFactorScreenshotTest.busyHud(), bmp.width, bmp.height, time = 1.3f, style = "Iso")
        val nodes = view.accessibilityLayer.nodes.filter { it.key != "hud:map" }
        // Menu, pause, router, two radios, four cable types.
        check(size, "hud", nodes, expectedActions = 9)
    }

    @Test
    fun hudWithPausedBannerAndLongHintStaysApart() = everywhere { size, view, bmp ->
        val world = FormFactorScreenshotTest.busyHud()
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        // Pause in place (banner at the top) and pick fibre (a long hint above the buttons).
        view.accessibilityLayer.performAction(view.accessibilityLayer.idOf("hud:pause"), android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null)
        view.accessibilityLayer.performAction(view.accessibilityLayer.idOf("hud:cable:FIBER"), android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null)
        view.advance(0f)
        view.drawCurrent(Canvas(bmp))
        val nodes = view.accessibilityLayer.nodes.filter { it.key != "hud:map" }
        assertTrue("${size}: paused banner shown", nodes.any { it.key == "hud:paused" })
        assertTrue("${size}: hint shown", nodes.any { it.key == "hud:hint" })
        check(size, "hud-paused", nodes, expectedActions = 9)
    }

    @Test
    fun menusKeepLargeEntriesInEveryFormat() = everywhere { size, view, bmp ->
        view.monetization = FakeMonetization(prices = mapOf(Entitlements.REMOVE_ADS to "2,99 €"), privacyOptionsRequired = true)
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = null)
        check(size, "main menu", view.accessibilityLayer.nodes, expectedActions = 3)
        val game = FormFactorScreenshotTest.busyHud()
        for ((screen, actions) in listOf(Screen.PAUSED to 4, Screen.SETTINGS to 7, Screen.GAME_OVER to 2)) {
            bmp.eraseColor(0)
            view.drawSnapshot(Canvas(bmp), game, bmp.width, bmp.height, time = 1.3f, style = "Iso", screen = screen)
            check(size, screen.name, view.accessibilityLayer.nodes, expectedActions = actions)
        }
    }

    @Test
    fun sceneryPickerKeepsLargeTargetsInEveryFormat() = everywhere { size, view, bmp ->
        view.monetization = FakeMonetization(prices = mapOf(Entitlements.SCENERY_PACK to "4,99 €"))
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = Screen.SCENERIES)
        // Back, pack and five sceneries; a row of cards wider than the screen scrolls, so cards may reach past it.
        check(size, "sceneries", view.accessibilityLayer.nodes, expectedActions = 7, offScreenOk = { it.startsWith("scenery:") && it != "scenery:back" && it != "scenery:pack" })
    }

    @Test
    fun rewardChoiceKeepsLargeTargetsInEveryFormat() = everywhere { size, view, bmp ->
        view.monetization = FakeMonetization(owned = mutableSetOf(Entitlements.REMOVE_ADS))
        view.drawSnapshot(Canvas(bmp), FormFactorScreenshotTest.rewardWorld(), bmp.width, bmp.height, time = 1.3f, style = "Iso")
        // Two cards, the extra router, the menu button.
        check(size, "reward", view.accessibilityLayer.nodes, expectedActions = 4)
    }

    @Test
    fun tutorialPanelKeepsClearOfTheHudInEveryFormat() = everywhere { size, _, bmp ->
        SettingsStore(app).tutorialSeen = false
        val view = GameView(app)
        view.accessibilityLayer.forceActive = true
        val w = view.currentTutorial!!.world
        view.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 0.3f, screen = null)
        view.advance(0.5f)
        view.drawCurrent(Canvas(bmp))
        val nodes = view.accessibilityLayer.nodes.filter { it.key != "hud:map" }
        // Skip, and the HUD: menu, pause, router, ISDN.
        check(size, "tutorial", nodes, expectedActions = 5)
        SettingsStore(app).tutorialSeen = true
    }

    /** Runs [body] with a fresh view for every size at font scale 1.0 and 2.0. */
    private fun everywhere(body: (String, GameView, Bitmap) -> Unit) {
        for (size in sizes) for (font in listOf(1f, 2f)) {
            RuntimeEnvironment.setQualifiers("+${size.qualifiers}")
            RuntimeEnvironment.setFontScale(font)
            val view = GameView(app)
            view.accessibilityLayer.forceActive = true
            screen = RectF(0f, 0f, size.width.toFloat(), size.height.toFloat())
            body("${size.id} at font ${font}x", view, Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888))
        }
        RuntimeEnvironment.setFontScale(1f)
    }

    /** The surface of the size being checked. */
    private var screen = RectF()

    private fun check(size: String, what: String, nodes: List<UiNode>, expectedActions: Int, offScreenOk: (String) -> Boolean = { false }) {
        val density = app.resources.displayMetrics.density
        val actions = nodes.filter { it.actionable }
        assertTrue("$size, $what: ${actions.size} tappable elements, expected $expectedActions: ${actions.map { it.key }}", actions.size == expectedActions)
        val min = 48f * density - 0.5f
        for (n in actions) {
            assertTrue("$size, $what: ${n.key} is ${n.bounds.width() / density} × ${n.bounds.height() / density} dp", n.bounds.width() >= min && n.bounds.height() >= min)
        }
        for (n in nodes) {
            if (offScreenOk(n.key)) continue
            if (!screen.contains(n.bounds)) fail("$size, $what: ${n.key} ${n.bounds} leaves the screen $screen")
        }
        for (i in nodes.indices) for (j in i + 1 until nodes.size) {
            val a = nodes[i].bounds
            val b = nodes[j].bounds
            val overlap = RectF()
            if (overlap.setIntersect(a, b) && overlap.width() > 1f && overlap.height() > 1f) {
                fail("$size, $what: ${nodes[i].key} $a overlaps ${nodes[j].key} $b")
            }
        }
    }
}
