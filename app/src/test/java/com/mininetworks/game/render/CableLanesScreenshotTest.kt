package com.mininetworks.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.mininetworks.game.data.SettingsStore
import android.view.MotionEvent
import com.mininetworks.game.game.Cable
import com.mininetworks.game.game.CableLanes
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.ui.GameView
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A crowded town in both styles and on a portrait phone: cables over the same cells run side by side in lanes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "de-xxhdpi")
@OptIn(DebugApi::class)
class CableLanesScreenshotTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val shots get() = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    @Before
    fun tutorialSeen() {
        SettingsStore(app).tutorialSeen = true
    }

    private fun save(bmp: Bitmap, name: String) = File(shots, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

    @Test
    fun crowdedTownShowsEveryCable() {
        val world = Scenes.crowdedTown()
        val moved = world.cables.count { it.path !== it.layout.waypoints }
        assertTrue("cables over the same cells run in lanes ($moved of ${world.cables.size})", moved >= 4)
        for ((style, w, h, name) in listOf(
            Shot("Iso", 2400, 1080, "cables-lanes-iso.png"),
            Shot("Flat", 2400, 1080, "cables-lanes-flat.png"),
            Shot("Iso", 1080, 2400, "cables-lanes-portrait.png"),
        )) {
            val view = GameView(app)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.drawSnapshot(Canvas(bmp), world, w, h, time = 1.3f, style = style)
            save(bmp, name)
        }
    }

    @Test
    fun aSelectedCableStandsOutAndTheOthersFade() {
        val world = Scenes.crowdedTown()
        for ((style, w, h, name) in listOf(
            Shot("Iso", 2400, 1080, "cables-focus-iso.png"),
            Shot("Flat", 2400, 1080, "cables-focus-flat.png"),
        )) {
            val view = GameView(app)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.drawSnapshot(Canvas(bmp), world, w, h, time = 1.3f, style = style)
            val plain = Bitmap.createBitmap(bmp)
            view.hudTarget("pause")!!.let {
                view.injectTouch(MotionEvent.ACTION_DOWN, it.centerX(), it.centerY(), time = 0L)
                view.injectTouch(MotionEvent.ACTION_UP, it.centerX(), it.centerY(), time = 50L)
            }
            // A tap on a cable's middle may hit a device or another lane first: try along the longest cables until one is selected.
            var clock = 1_000L
            search@ for (target in world.cables.sortedByDescending { it.layout.length }.take(6)) {
                for (f in listOf(0.5f, 0.35f, 0.65f, 0.25f, 0.75f)) {
                    val at = view.activeRenderer.toScreen(target.pointAt(f))
                    view.injectTouch(MotionEvent.ACTION_DOWN, at.x, at.y, time = clock)
                    view.injectTouch(MotionEvent.ACTION_UP, at.x, at.y, time = clock + 50L)
                    clock += 1_000L
                    if (view.selected is Cable) break@search
                }
            }
            val picked = view.selected as? Cable
            assertNotNull("a tap on a cable selects a cable", picked)
            view.drawCurrent(Canvas(bmp))
            assertSame("the renderer follows the selection", picked, view.activeRenderer.focusCable)
            var changed = 0
            for (y in 0 until h step 3) for (x in 0 until w step 3) if (plain.getPixel(x, y) != bmp.getPixel(x, y)) changed++
            assertTrue("fading the other cables changes the picture ($changed)", changed > 2_000)
            save(bmp, name)
        }
    }

    @Test
    fun zoomedOutTheLanesKeepTheirDistance() {
        val world = Scenes.crowdedTown()
        val view = GameView(app)
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        view.drawSnapshot(Canvas(bmp), world, 1080, 2400, time = 1.3f, style = "Iso")
        val unit = view.activeRenderer.unitPx
        assertTrue("never beyond the cap", world.laneSpread in 1f..CableLanes.MAX_SPREAD)
        val pitch = unit * CableLanes.SPACING * world.laneSpread
        assertTrue("lanes at least 6 dp apart or at the cap ($pitch px)", pitch >= 6f * app.resources.displayMetrics.density - 0.5f || world.laneSpread == CableLanes.MAX_SPREAD)
    }

    private data class Shot(val style: String, val w: Int, val h: Int, val name: String)
}
