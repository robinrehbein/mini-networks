package com.mininetworks.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.ui.GameView
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

    private data class Shot(val style: String, val w: Int, val h: Int, val name: String)
}
