package com.mininetworks.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Service
import com.mininetworks.game.render.FormFactor
import com.mininetworks.game.render.FormFactorScreenshotTest
import com.mininetworks.game.ui.menu.LegendPanel
import com.mininetworks.game.ui.menu.MenuAction
import com.mininetworks.game.ui.menu.Screen
import org.junit.After
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
 * The legend "What's what?": opened from the pause menu, it explains every symbol of the map (which device needs
 * which server, each server type with its service's bandwidth and ping, the data center as the top tier of any server,
 * the network's parts and the warning signs) and leads
 * back to the pause menu. Every tile's title and description are shown in full in all 12 languages, in every format,
 * at font scale 1 and 2.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "de-w800dp-h360dp-land-xxhdpi")
@OptIn(DebugApi::class)
class LegendTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val shots get() = File(System.getProperty("screenshots.dir") ?: "build/screenshots", "legend").apply { mkdirs() }

    @Before
    fun setUp() {
        SettingsStore(app).tutorialSeen = true
    }

    @After
    fun resetFont() {
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun save(bmp: Bitmap, name: String) = File(shots, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun tapAt(view: GameView, x: Float, y: Float) {
        view.injectTouch(MotionEvent.ACTION_DOWN, x, y)
        view.injectTouch(MotionEvent.ACTION_UP, x, y)
    }

    @Test
    fun pauseMenuOpensTheLegendAndBackReturnsToIt() {
        val view = GameView(app)
        val bmp = Bitmap.createBitmap(2400, 1080, Bitmap.Config.ARGB_8888)
        val game = FormFactorScreenshotTest.busyHud()
        view.drawSnapshot(Canvas(bmp), game, bmp.width, bmp.height, time = 1.3f, style = "Iso", screen = Screen.PAUSED)
        val entry = view.menuTarget(MenuAction.LEGEND)
        assertNotNull("the pause menu offers the legend", entry)
        tapAt(view, entry!!.centerX(), entry.centerY())
        assertEquals(Screen.LEGEND, view.currentScreen)
        bmp.eraseColor(0)
        view.drawSnapshot(Canvas(bmp), game, bmp.width, bmp.height, time = 1.3f, style = "Iso", screen = null)
        save(bmp, "legend-de.png")
        // Every server type, every device, every cable and the warning signs have a tile; which device needs which
        // server comes first.
        assertTrue("devices → servers leads", view.legendTile("device:PC")!!.top < view.legendTile("service:MAIL")!!.top)
        for (id in Service.entries.map { "service:${it.name}" } + Device.entries.map { "device:${it.name}" } +
            CableType.entries.map { "cable:${it.name}" } + listOf("data_center", "server", "router", "access_point", "cell_tower", "request", "response", "overload", "too_narrow", "ping")) {
            assertNotNull("tile $id", view.legendTile(id))
        }
        // Scrolling shows the rest; the back pill stays where it is.
        val back = view.legendTarget(LegendPanel.BACK)!!
        view.injectTouch(MotionEvent.ACTION_DOWN, 1200f, 1000f)
        view.injectTouch(MotionEvent.ACTION_MOVE, 1200f, 600f)
        view.injectTouch(MotionEvent.ACTION_MOVE, 1200f, 100f)
        view.injectTouch(MotionEvent.ACTION_UP, 1200f, 100f)
        assertEquals("a drag is not a tap", Screen.LEGEND, view.currentScreen)
        bmp.eraseColor(0)
        view.drawSnapshot(Canvas(bmp), game, bmp.width, bmp.height, time = 1.3f, style = "Iso", screen = null)
        save(bmp, "legend-de-scrolled.png")
        // Further down: the server types and the data center as the top tier of any of them.
        view.injectTouch(MotionEvent.ACTION_DOWN, 1200f, 1000f)
        view.injectTouch(MotionEvent.ACTION_MOVE, 1200f, 500f)
        view.injectTouch(MotionEvent.ACTION_MOVE, 1200f, 250f)
        view.injectTouch(MotionEvent.ACTION_UP, 1200f, 250f)
        bmp.eraseColor(0)
        view.drawSnapshot(Canvas(bmp), game, bmp.width, bmp.height, time = 1.3f, style = "Iso", screen = null)
        save(bmp, "legend-de-servers.png")
        tapAt(view, back.centerX(), back.centerY())
        assertEquals(Screen.PAUSED, view.currentScreen)
        // The system back gesture leads there too.
        view.drawSnapshot(Canvas(bmp), game, bmp.width, bmp.height, time = 1.3f, style = "Iso", screen = Screen.LEGEND)
        view.back()
        view.advance(0f)
        assertEquals(Screen.PAUSED, view.currentScreen)
    }

    @Test
    fun everyTileIsReadableInEveryLanguageAndFormat() {
        for (lang in LANGUAGES) for (f in listOf(FormFactor.PHONE_16_9, FormFactor.PHONE_20_9, FormFactor.TABLET_7_XHDPI, FormFactor.FOLDABLE)) {
            for (font in listOf(1f, 2f)) {
                RuntimeEnvironment.setQualifiers("$lang-${f.qualifiers}")
                RuntimeEnvironment.setFontScale(font)
                val view = GameView(app)
                view.accessibilityLayer.forceActive = true
                val bmp = Bitmap.createBitmap(f.widthPx, f.heightPx, Bitmap.Config.ARGB_8888)
                view.drawSnapshot(Canvas(bmp), FormFactorScreenshotTest.busyHud(), bmp.width, bmp.height, time = 1.3f, style = "Iso", screen = Screen.LEGEND)
                val nodes = view.accessibilityLayer.nodes
                val cut = nodes.filter { it.shortened }.map { it.text }
                assertTrue("$lang, ${f.id}, font $font: cut tiles $cut", cut.isEmpty())
                assertEquals("$lang, ${f.id}: 4 sections", 4, nodes.count { it.key.startsWith("legend:section:") })
                val back = nodes.single { it.key == "legend:back" }
                assertTrue("back pill is a touch target", back.bounds.height() >= 48f * app.resources.displayMetrics.density - 0.5f)
                if (font == 1f && f == FormFactor.PHONE_20_9 && lang in setOf("en", "ja")) save(bmp, "legend-$lang.png")
            }
        }
    }

    private companion object {
        val LANGUAGES = listOf("de", "en", "fr", "es", "it", "pt-rBR", "pl", "nl", "tr", "ja", "ko", "zh-rCN")
    }
}
