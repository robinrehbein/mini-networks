package com.mininetworks.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import com.mininetworks.game.R
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Failure
import com.mininetworks.game.game.LossTip
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World
import com.mininetworks.game.render.Scenes
import com.mininetworks.game.ui.menu.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The game-over card's tip against the cause of the loss ([World.lossTip], [GameView.lossTipText]) and the callout
 * that names the failed device while the camera glides to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(DebugApi::class)
class GameOverTipTest {

    private val app get() = RuntimeEnvironment.getApplication()

    private fun world() = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false).also { w ->
        for (row in w.water) row.fill(false)
        w.incidentsEnabled = false
        w.jumpToWeek(CableType.FIBER.unlockWeek)
        w.grant(500)
    }

    private fun viewOf(w: World): GameView {
        SettingsStore(app).tutorialSeen = true
        val view = GameView(app)
        val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        view.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 0f, style = "Iso")
        bmp.recycle()
        return view
    }

    @Test
    fun cardShowsTheTipUnderTheReason() {
        val view = lostGameView(app) { it.accessibilityLayer.forceActive = true }
        val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = null)
        val f = view.currentWorld.failure!!
        assertEquals("the phone was never cabled", LossTip.CONNECT, view.currentWorld.lossTip(f))
        val tip = app.getString(R.string.loss_tip_connect, app.getString(R.string.device_phone), app.getString(R.string.server_call))
        val lines = view.accessibilityLayer.nodes.single { it.key == "menu:lines" }.text.split("\n")
        assertEquals("the tip right under the reason", tip, lines[1])
        val server = app.getString(R.string.server_call)
        assertTrue("one name for the server in the reason and the tip: ${lines[0]}", server in lines[0])
    }

    @Test
    fun tooNarrowNamesTheWideEnoughCables() {
        val w = world()
        val tv = w.addClient(Device.TV, 1, 1)
        w.connect(tv, w.addServer(Service.STREAMING, 4, 1), CableType.ISDN)
        val view = viewOf(w)
        val text = view.lossTipText(Failure(tv, Service.STREAMING, w.routeProblem(tv, Service.STREAMING), null))
        assertEquals(app.getString(R.string.loss_tip_wider_cable, "Streaming", "DSL / Koax / Glasfaser"), text)
    }

    @Test
    fun everyCauseHasItsOwnTipInEveryLanguage() {
        for (lang in LANGUAGES) {
            RuntimeEnvironment.setQualifiers(lang)
            val w = world()
            val phone = w.addClient(Device.PHONE, 1, 1)
            w.addServer(Service.CALL, 5, 1)
            val pc = w.addClient(Device.PC, 1, 4)
            w.connect(pc, w.addServer(Service.MAIL, 4, 4), CableType.FIBER)
            w.addServer(Service.GAMING, 8, 8)
            val tv = w.addClient(Device.TV, 1, 7)
            w.connect(tv, w.addServer(Service.STREAMING, 4, 7), CableType.ISDN)
            val console = w.addClient(Device.CONSOLE, 15, 0)
            w.connect(console, w.nodes.first { it.service == Service.GAMING }, CableType.ISDN)
            val view = viewOf(w)
            val texts = mapOf(
                LossTip.CONNECT to Failure(phone, Service.CALL, w.routeProblem(phone, Service.CALL), null),
                LossTip.NEEDS_SERVER to Failure(pc, Service.GAMING, w.routeProblem(pc, Service.GAMING), null),
                LossTip.WIDER_CABLE to Failure(tv, Service.STREAMING, w.routeProblem(tv, Service.STREAMING), null),
                LossTip.FASTER_CABLE to Failure(console, Service.GAMING, w.routeProblem(console, Service.GAMING), null),
                LossTip.SECOND_CABLE to Failure(pc, Service.MAIL, null, null),
            ).map { (expected, f) ->
                assertEquals("$lang: $expected", expected, w.lossTip(f))
                view.lossTipText(f).also { assertTrue("$lang: $expected is empty", it.isNotBlank()) }
            }
            assertEquals("$lang: one tip per cause", texts.size, texts.toSet().size)
            // Every tip, also those whose causes take incidents or a long run to set up (classified in LossTipTest).
            val all = LossTip.entries.map { tip ->
                view.lossTipText(Failure(pc, Service.MAIL, null, null), tip).also { assertTrue("$lang: $tip is empty", it.isNotBlank()) }
            }
            assertEquals("$lang: one text per tip", LossTip.entries.size, all.toSet().size)
        }
        RuntimeEnvironment.setQualifiers("de")
    }

    @Test
    fun calloutNamesTheFailedDeviceDuringTheGlide() {
        val world = Scenes.hud()
        val phone = world.addClient(Device.PHONE, 8, 9)
        repeat(World.Tuning.MAX_PENDING) { phone.pending.addLast(Service.CALL) }
        val view = viewOf(world)
        val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        var s = 0
        while (!world.gameOver && s++ < 60 * 30) view.advance(1f / 60f)
        repeat(60) { view.advance(1f / 60f) }
        assertEquals(Screen.PLAYING, view.currentScreen)
        view.drawCurrent(Canvas(bmp))
        assertNotNull("a callout during the glide", view.failCalloutBox)
        val box = view.failCalloutBox!!
        assertEquals(app.getString(R.string.game_over_callout, app.getString(R.string.device_phone)), view.failCalloutText)
        val at = view.activeRenderer.toScreen(phone.footprintCenter)
        assertTrue("the callout points at the device: $box, device at $at", at.x in box.left..box.right)
        assertTrue("the callout keeps the device clear: $box, device at $at", box.top > at.y || box.bottom < at.y)
        assertTrue("on screen: $box", box.left >= 0f && box.right <= bmp.width && box.top >= 0f && box.bottom <= bmp.height)
        // The card replaces the glide, and the callout with it.
        while (view.currentScreen != Screen.GAME_OVER) view.advance(1f / 60f)
        view.drawCurrent(Canvas(bmp))
        assertNull(view.failCalloutBox)
    }

    @Test
    fun calloutFitsAPortraitPhoneWithLargeTextAndLongNames() {
        for (lang in listOf("it", "pt-rBR", "fr")) {
            RuntimeEnvironment.setQualifiers("$lang-w360dp-h640dp-port")
            RuntimeEnvironment.setFontScale(1.3f)
            val world = Scenes.hud()
            val camera = world.addClient(Device.CAMERA, 8, 9)
            repeat(World.Tuning.MAX_PENDING) { camera.pending.addLast(Service.CAMERA_UPLOAD) }
            val view = viewOf(world)
            val d = app.resources.displayMetrics
            val bmp = Bitmap.createBitmap((360 * d.density).toInt(), (640 * d.density).toInt(), Bitmap.Config.ARGB_8888)
            view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 0f, style = "Iso")
            var s = 0
            while (!world.gameOver && s++ < 60 * 30) view.advance(1f / 60f)
            assertTrue("$lang: the camera overflowed", world.gameOver)
            // Every frame of the glide, from its first one: on screen, on one side of the device.
            var side: Boolean? = null
            repeat(60) {
                view.drawCurrent(Canvas(bmp))
                val box = view.failCalloutBox ?: throw AssertionError("$lang: a callout during the glide")
                assertTrue("$lang: on screen: $box", box.left >= 0f && box.right <= bmp.width && box.top >= 0f && box.bottom <= bmp.height)
                val at = view.activeRenderer.toScreen(camera.footprintCenter)
                val below = box.top > at.y
                if (side == null) side = below
                assertEquals("$lang: the callout keeps its side", side, below)
                view.advance(1f / 60f)
            }
            bmp.recycle()
        }
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("de")
    }

    private companion object {
        val LANGUAGES = listOf("de", "en", "es", "fr", "it", "ja", "ko", "nl", "pl", "pt-rBR", "tr", "zh-rCN")
    }
}
