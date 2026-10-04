package com.mininetworks.game.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.Scenarios
import com.mininetworks.game.game.World
import com.mininetworks.game.ui.Coaching
import com.mininetworks.game.ui.GameView
import com.mininetworks.game.ui.menu.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The HUD's hints and helpers into docs/screenshots/, for the visual review: a refused action on its red plate with
 * the "!" mark, a one-time coaching tip, the low budget (bar and tint), the legend opened with the "?" button, the
 * stacked incident pins and the "?" on a narrow portrait phone with radios in stock (clear of the price badges).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "de-xxhdpi")
@OptIn(DebugApi::class)
class HudPolishScreenshotTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val shots get() = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
    private var clock = 0L

    @Before
    fun tutorialSeen() {
        SettingsStore(app).tutorialSeen = true
    }

    private fun save(bmp: Bitmap, name: String) = File(shots, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun phone() = Bitmap.createBitmap(2400, 1080, Bitmap.Config.ARGB_8888)

    private fun tapAt(view: GameView, x: Float, y: Float) {
        clock += 400L
        view.injectTouch(MotionEvent.ACTION_DOWN, x, y, time = clock)
        view.injectTouch(MotionEvent.ACTION_UP, x, y, time = clock + 50L)
    }

    private fun tapHud(view: GameView, id: String) {
        val r = view.hudTarget(id) ?: throw AssertionError("no HUD button $id")
        tapAt(view, r.centerX(), r.centerY())
    }

    /** A view on [world], paused in place (the hints' timers run, the network stands still). */
    private fun paused(world: World, bmp: Bitmap): GameView {
        val view = GameView(app)
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        tapHud(view, "pause")
        return view
    }

    private fun seenAllBut(tip: Coaching?) =
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putStringSet("coaching_seen", Coaching.entries.filter { it != tip }.map { it.key }.toSet()).commit()

    @Test
    fun renderErrorHint() {
        seenAllBut(null)
        val world = Scenes.hud()
        world.grant(-world.budget)
        val bmp = phone()
        val view = paused(world, bmp)
        val server = world.nodes.first { it.kind == NodeKind.SERVER && !it.isDataCenter }
        view.activeRenderer.toScreen(server.center).let { tapAt(view, it.x, it.y) }
        assertTrue("a refused upgrade shows as an error", view.shownHintIsError)
        view.drawCurrent(Canvas(bmp))
        save(bmp, "hint-error.png")
    }

    @Test
    fun renderCoachTip() {
        seenAllBut(Coaching.RADIO_STOCK)
        val world = Scenes.hud().also { it.grant(0, extraAccessPoints = 1) }
        val bmp = phone()
        val view = paused(world, bmp)
        repeat(30) { view.advance(1f / 60f) }
        assertNotNull(view.shownHint)
        assertTrue(view.shownHint!!, view.shownHint!!.startsWith("Tipp"))
        // Twice, as on a device: the server plates keep clear of the tip drawn in the frame before.
        view.drawCurrent(Canvas(bmp))
        view.drawCurrent(Canvas(bmp))
        save(bmp, "coach-tip.png")
    }

    @Test
    fun renderBudgetLow() {
        seenAllBut(null)
        val world = Scenes.hud()
        world.grant(-(world.budget - 4))
        val bmp = phone()
        val view = paused(world, bmp)
        assertTrue(view.budgetLow)
        view.drawCurrent(Canvas(bmp))
        save(bmp, "hud-budget-low.png")
    }

    @Test
    fun renderLegendFromHelp() {
        seenAllBut(null)
        val world = Scenes.hud()
        val bmp = phone()
        val view = GameView(app)
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        tapHud(view, "help")
        assertEquals(Screen.LEGEND, view.currentScreen)
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, screen = null)
        save(bmp, "legend-from-help.png")
    }

    @Test
    fun renderStackedIncidentPins() {
        seenAllBut(null)
        val world = Scenes.incidents()
        val bmp = phone()
        val view = GameView(app)
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        save(bmp, "incidents-pins-stacked.png")
    }

    @Test
    @Config(qualifiers = "de-w360dp-h800dp-port-xxhdpi")
    fun renderPortraitHelpButton() {
        seenAllBut(null)
        val world = World(scenario = Scenarios.FUTURE, seed = 2L, spawnInitialNodes = false).also { it.incidentsEnabled = false }
        world.grant(0, extraRouters = 2, extraAccessPoints = 2, extraCellTowers = 2)
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        val view = GameView(app)
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        save(bmp, "portrait-help-button.png")
    }
}
