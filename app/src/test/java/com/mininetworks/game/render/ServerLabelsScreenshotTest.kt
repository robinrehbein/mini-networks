package com.mininetworks.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.World
import com.mininetworks.game.ui.GameView
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * "Which device needs which server" on a landscape phone (2400 × 1080, xxhdpi), full game frames with the HUD:
 * every server's name plate, the servers a device being dragged from needs lighting up (the rest dimmed), the hint
 * after tapping a device, both styles, a turned map, the busy grown city, a long-word language and the colorblind
 * palette. Writes docs/screenshots/servers-*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "de-xxhdpi")
@OptIn(DebugApi::class)
class ServerLabelsScreenshotTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val shots get() = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    @Before
    fun tutorialSeen() {
        SettingsStore(app).tutorialSeen = true
    }

    @After
    fun palette() {
        ServiceColors.colorblind = false
    }

    private fun save(bmp: Bitmap, name: String) = File(shots, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun frame(world: World, style: String): Pair<GameView, Bitmap> {
        val view = GameView(app)
        val bmp = Bitmap.createBitmap(2400, 1080, Bitmap.Config.ARGB_8888)
        // Twice: the plates keep clear of the HUD of the frame before.
        repeat(2) { view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = style) }
        return view to bmp
    }

    /** Puts a finger on [from] and drags it a little way towards the screen centre, then draws at a later time. */
    private fun dragFrom(view: GameView, bmp: Bitmap, world: World, from: Node) {
        val p = view.activeRenderer.toScreen(from.center)
        view.injectTouch(MotionEvent.ACTION_DOWN, p.x, p.y, time = 1000L)
        val tx = p.x + (bmp.width / 2f - p.x) * 0.25f; val ty = p.y + (bmp.height / 2f - p.y) * 0.25f
        view.injectTouch(MotionEvent.ACTION_MOVE, (p.x + tx) / 2f, (p.y + ty) / 2f, time = 1050L)
        view.injectTouch(MotionEvent.ACTION_MOVE, tx, ty, time = 1100L)
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 2.0f)
    }

    @Test
    fun renderPlatesAndHighlight() {
        for (style in listOf("Iso", "Flat")) {
            val world = Scenes.hud()
            val (view, bmp) = frame(world, style)
            save(bmp, "servers-labels-${style.lowercase()}.png")
            dragFrom(view, bmp, world, world.nodes.first { it.device == Device.PC })
            assertTrue("the drag lights up the PC's servers", view.activeRenderer.serverLabels.focus!!.strength > 0.99f)
            save(bmp, "servers-highlight-drag-${style.lowercase()}.png")
            view.injectTouch(MotionEvent.ACTION_CANCEL, 0f, 0f)
        }
    }

    /** A tap on the phone: its telephone exchange lights up and the hint names it with the service's token. */
    @Test
    fun renderTapHint() {
        val world = Scenes.hud()
        val (view, bmp) = frame(world, "Iso")
        val phone = world.nodes.first { it.device == Device.PHONE }
        phone.pending.clear()
        val p = view.activeRenderer.toScreen(phone.center)
        view.injectTouch(MotionEvent.ACTION_DOWN, p.x, p.y, time = 1000L)
        view.injectTouch(MotionEvent.ACTION_UP, p.x, p.y, time = 1050L)
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 2.0f)
        save(bmp, "servers-tap-hint-iso.png")
        val laptop = world.nodes.first { it.device == Device.LAPTOP }
        laptop.pending.clear()
        val q = view.activeRenderer.toScreen(laptop.center)
        view.injectTouch(MotionEvent.ACTION_DOWN, q.x, q.y, time = 3000L)
        view.injectTouch(MotionEvent.ACTION_UP, q.x, q.y, time = 3050L)
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 2.7f)
        save(bmp, "servers-tap-hint-laptop-iso.png")
    }

    /** The map turned by 45°: plates stay upright and clear of the buildings, rings stay on the ground. */
    @Test
    fun renderTurned() {
        val world = Scenes.hud()
        val (view, bmp) = frame(world, "Iso")
        val r = view.activeRenderer
        r.rotateBy(45f, bmp.width / 2f, bmp.height / 2f, world)
        dragFrom(view, bmp, world, world.nodes.first { it.device == Device.LAPTOP })
        save(bmp, "servers-highlight-turned-iso.png")
        view.injectTouch(MotionEvent.ACTION_CANCEL, 0f, 0f)
    }

    /** The busy grown city: plates only where they fit, never over a device or a request bubble. */
    @Test
    fun renderGrownCity() {
        for (style in listOf("Iso", "Flat")) {
            val world = Scenes.grownCity()
            val (view, bmp) = frame(world, style)
            save(bmp, "servers-labels-grown-${style.lowercase()}.png")
            dragFrom(view, bmp, world, world.nodes.first { it.device == Device.SMARTPHONE || it.device == Device.LAPTOP })
            save(bmp, "servers-highlight-grown-${style.lowercase()}.png")
            view.injectTouch(MotionEvent.ACTION_CANCEL, 0f, 0f)
        }
    }

    /** Long server names (French) and the colorblind palette. */
    @Test
    @Config(qualifiers = "fr-xxhdpi")
    fun renderLongWordsColorblind() {
        val world = Scenes.hud()
        val (view, bmp) = frame(world, "Iso")
        ServiceColors.colorblind = true
        dragFrom(view, bmp, world, world.nodes.first { it.device == Device.TABLET })
        save(bmp, "servers-highlight-fr-colorblind-iso.png")
        view.injectTouch(MotionEvent.ACTION_CANCEL, 0f, 0f)
    }
}
