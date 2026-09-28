package com.mininetworks.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.Cell
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.GameMode
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.Scenarios
import com.mininetworks.game.game.World
import com.mininetworks.game.ui.GameView
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The toolbar's two groups ("Kabel", "Netzwerk") and the ports: the network tiles on a landscape phone, in portrait,
 * in long-word languages and in creative mode; a router tile dragged onto the map (a free cell and a taken one); the
 * port dots on the map and a cable dragged onto a PC whose 2 ports are in use, in both styles, and the hint after it.
 * Written to docs/screenshots/:
 *   ./gradlew testDebugUnitTest --tests '*PortsScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "de-w800dp-h360dp-land-xxhdpi")
@OptIn(DebugApi::class)
class PortsScreenshotTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val shots get() = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    @Before
    fun setUp() {
        SettingsStore(app).tutorialSeen = true
    }

    private fun save(bmp: Bitmap, name: String) = File(shots, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun phone() = Bitmap.createBitmap(2400, 1080, Bitmap.Config.ARGB_8888)

    /** The HUD with the router, WLAN and mast tiles, on a landscape phone and in portrait. */
    @Test
    fun toolbarNetwork() {
        val bmp = phone()
        GameView(app).drawSnapshot(Canvas(bmp), FormFactorScreenshotTest.busyHud(), bmp.width, bmp.height, time = 1.3f, style = "Iso")
        save(bmp, "toolbar-network.png")
        val one = phone()
        GameView(app).drawSnapshot(Canvas(one), Scenes.hud(), one.width, one.height, time = 1.3f, style = "Iso")
        save(one, "toolbar-network-router.png")
    }

    @Test
    @Config(qualifiers = "de-w360dp-h800dp-port-xxhdpi")
    fun toolbarNetworkPortrait() {
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        GameView(app).drawSnapshot(Canvas(bmp), FormFactorScreenshotTest.busyHud(), bmp.width, bmp.height, time = 1.3f, style = "Iso")
        save(bmp, "toolbar-network-portrait.png")
    }

    /** Long words: French and Polish on the narrowest landscape phone (640 × 360 dp). */
    @Test
    fun toolbarNetworkLongWords() {
        for (lang in listOf("fr", "pl")) {
            RuntimeEnvironment.setQualifiers("$lang-w640dp-h360dp-land-xxhdpi")
            val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            GameView(app).drawSnapshot(Canvas(bmp), FormFactorScreenshotTest.busyHud(), bmp.width, bmp.height, time = 1.3f, style = "Iso")
            save(bmp, "toolbar-network-$lang.png")
        }
    }

    /** Creative mode: no prices, unlimited stock (∞) on every tile. */
    @Test
    fun toolbarCreative() {
        val w = World(Scenarios.RIVER_TOWN, seed = 4L, mode = GameMode.CREATIVE)
        val bmp = phone()
        GameView(app).drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        save(bmp, "toolbar-creative.png")
    }

    /** The router tile dragged over a free cell (white) and over a taken one (red, the reason in the hint line). */
    @Test
    fun toolbarDrag() {
        val w = Scenes.hud()
        val view = GameView(app)
        val bmp = phone()
        view.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        val tile = view.hudTarget("router")!!
        for ((name, cell) in listOf("toolbar-drag-iso.png" to w.nearestFree(Cell(8, 6))!!, "toolbar-drag-invalid.png" to w.nodes.first { it.kind == NodeKind.ROUTER }.cell)) {
            val to = view.activeRenderer.toScreen(cell.center)
            view.injectTouch(MotionEvent.ACTION_DOWN, tile.centerX(), tile.centerY())
            view.injectTouch(MotionEvent.ACTION_MOVE, (tile.centerX() + to.x) / 2f, (tile.centerY() + to.y) / 2f)
            view.injectTouch(MotionEvent.ACTION_MOVE, to.x, to.y)
            bmp.eraseColor(0)
            view.drawCurrent(Canvas(bmp))
            save(bmp, name)
            view.injectTouch(MotionEvent.ACTION_CANCEL, to.x, to.y)
        }
    }

    /**
     * A cable dragged from the watch onto the PC, whose 2 ports (mail server and router) are in use: every full node
     * is ringed red, the dragged node shows its free ports; then, let go, the hint and the pulsing router tile.
     */
    @Test
    fun portsFull() {
        for (style in listOf("Iso", "Flat")) {
            val w = Scenes.hud()
            val view = GameView(app)
            val bmp = phone()
            view.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 1.3f, style = style)
            val watch = w.nodes.first { it.device == Device.WATCH }
            val pc = w.nodes.first { it.device == Device.PC }
            check(w.ports(pc) == pc.maxPorts) { "the PC should be full" }
            val a = view.activeRenderer.toScreen(watch.center)
            val b = view.activeRenderer.toScreen(pc.center)
            view.injectTouch(MotionEvent.ACTION_DOWN, a.x, a.y)
            view.injectTouch(MotionEvent.ACTION_MOVE, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
            view.injectTouch(MotionEvent.ACTION_MOVE, b.x, b.y)
            bmp.eraseColor(0)
            view.drawCurrent(Canvas(bmp))
            save(bmp, "ports-full-${style.lowercase()}.png")
            if (style == "Iso") {
                view.injectTouch(MotionEvent.ACTION_UP, b.x, b.y)
                view.advance(0.3f)
                bmp.eraseColor(0)
                view.drawCurrent(Canvas(bmp))
                save(bmp, "ports-full-hint.png")
            }
        }
    }

    /** Close in: the calm port dots under every node, filled where a cable is plugged in. */
    @Test
    fun portsZoomedIn() {
        val w = Scenes.hud()
        val view = GameView(app)
        val bmp = phone()
        view.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        val r = view.activeRenderer
        val router = w.nodes.first { it.kind == NodeKind.ROUTER }
        r.focusOn(router, zoom = 2.2f)
        repeat(240) { r.camera.step(1f / 60f) }
        bmp.eraseColor(0)
        view.drawCurrent(Canvas(bmp))
        save(bmp, "ports-zoom-iso.png")
    }
}
