package com.mininetworks.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.World
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The HUD's budget and router line is as prominent as the date and the packets (bold 16 sp) and stays beside the date
 * on a phone, also in portrait and in long languages; only large text on a narrow screen makes it step down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(DebugApi::class)
class HudStockLayoutTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = RuntimeEnvironment.setFontScale(1f)

    /** Lays out the HUD of a mid-game world (72 coins, 3 routers) at [width] x [height] px, by day or by [night]. */
    private fun layout(width: Int, height: Int, night: Boolean = false): Pair<GameView, Triple<Float, Boolean, Boolean>> {
        SettingsStore(app).tutorialSeen = true
        val world = World(seed = 2L, spawnInitialNodes = false).also { w ->
            w.incidentsEnabled = false
            w.grant(72 - w.budget, 3 - w.routersAvailable)
            if (night) while (!w.isNight) w.update(0.1f)
        }
        val view = GameView(app)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.drawSnapshot(Canvas(bmp), world, width, height, time = 0f, style = "Iso")
        return view to view.hudStockLayout
    }

    private fun assertProminentBeside(tag: String, width: Int, height: Int, night: Boolean = false) {
        val (view, l) = layout(width, height, night)
        assertEquals("$tag: the budget line as large as the date", view.hudTextSize, l.first, 0.01f)
        assertFalse("$tag: on one line", l.second)
        assertFalse("$tag: beside the date", l.third)
    }

    @Test
    fun landscapePhone() = assertProminentBeside("de landscape", 2340, 1080)

    @Test
    fun germanPortraitAtNight() {
        assertProminentBeside("de 411dp night", 1233, 2600, night = true)
    }

    @Test
    @Config(qualifiers = "es-xxhdpi")
    fun spanishPortrait() {
        assertProminentBeside("es 411dp", 1233, 2600)
        val (_, l) = layout(1080, 2340)
        assertFalse("es 360dp: still beside the date", l.third)
    }

    @Test
    @Config(qualifiers = "fr-xxhdpi")
    fun frenchPortrait() {
        val (_, l) = layout(1080, 2340)
        assertFalse("fr 360dp: beside the date", l.third)
    }

    @Test
    @Config(qualifiers = "es-xxhdpi")
    fun largeTextOnANarrowPortraitStepsDown() {
        RuntimeEnvironment.setFontScale(2f)
        val (view, l) = layout(1080, 2340)
        assertTrue("es 360dp at 2x: a smaller size, two lines or below the date ($l)", l.first < view.hudTextSize || l.second || l.third)
    }
}
