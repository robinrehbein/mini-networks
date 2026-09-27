package com.mininetworks.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World
import com.mininetworks.game.ui.GameView
import com.mininetworks.game.ui.GrowthRecap
import com.mininetworks.game.ui.menu.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * docs/TOP100.md B3, rendered into docs/screenshots/juice/: a cable growing and clicking in, a glint on an upgraded
 * cable, sparkles over an upgraded server, a delivery pop (both styles); the confetti at a week change; and the
 * game-over card with the time-lapse of the player's network at three moments.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "de-xxhdpi")
@OptIn(DebugApi::class)
class JuiceScreenshotTest {

    @Before
    fun tutorialSeen() {
        SettingsStore(RuntimeEnvironment.getApplication()).tutorialSeen = true
    }

    private val shots get() = File(System.getProperty("screenshots.dir") ?: "build/screenshots", "juice").apply { mkdirs() }

    private fun save(bmp: Bitmap, name: String) = File(shots, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun step(w: World, seconds: Float) = repeat((seconds * 60).toInt()) { w.update(1f / 60f) }

    @Test
    fun layingUpgradingAndDelivering() {
        val w = World(cols = 12, rows = 7, seed = 3L, spawnInitialNodes = false)
        for (row in w.water) row.fill(false)
        w.incidentsEnabled = false
        w.jumpToWeek(CableType.FIBER.unlockWeek)
        w.grant(400)
        val mail = w.addServer(Service.MAIL, 2, 1)
        val game = w.addServer(Service.GAMING, 9, 1)
        val pc = w.addClient(Device.PC, 3, 4)
        val laptop = w.addClient(Device.LAPTOP, 6, 5)
        val console = w.addClient(Device.CONSOLE, 9, 5)
        check(w.connect(pc, mail, CableType.DSL))
        repeat(4) { pc.pending.addLast(Service.MAIL) }
        // Run until a response has just been delivered: the pop and its burst show.
        var guard = 0
        while (w.arrivals.none { it.isResponse && w.time - it.time in 0.1f..0.25f } && guard++ < 60 * 30) w.update(1f / 60f)
        check(w.arrivals.any { it.isResponse }) { "a delivery pops" }
        check(w.connect(laptop, mail, CableType.DSL))
        step(w, 0.5f) // grown and clicking in
        check(w.upgrade(w.cableBetween(pc, mail)!!, CableType.FIBER))
        check(w.upgradeServer(game))
        step(w, 0.3f) // glint half way, sparkles rising
        check(w.connect(console, game, CableType.FIBER))
        step(w, 0.1f) // still growing
        assertTrue(Juice.growth(w.time, w.cableBetween(console, game)!!.builtAt, w.cableBetween(console, game)!!.layout.length) < 1f)
        for (r in listOf(IsoRenderer(), FlatRenderer())) {
            val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
            r.density = 2f
            r.layout(bmp.width, bmp.height, w)
            r.draw(Canvas(bmp), w, drag = null, time = 1.3f)
            save(bmp, "effects-${r.name.lowercase()}.png")
        }
    }

    @Test
    fun weekChangeConfettiAndGameOverTimeLapse() {
        val app = RuntimeEnvironment.getApplication()
        val w = World(seed = 11L, spawnInitialNodes = false)
        w.incidentsEnabled = false
        w.grant(300)
        val view = GameView(app)
        val bmp = Bitmap.createBitmap(2400, 1080, Bitmap.Config.ARGB_8888)
        view.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 0f, style = "Iso")
        fun play(seconds: Float) = repeat((seconds * 60).toInt()) { view.advance(1f / 60f) }
        // A small network grows over the first week, one piece every few seconds.
        val u = w.unlocked
        val mail = w.addServer(Service.MAIL, u.left + 3, u.top + 2)
        val call = w.addServer(Service.CALL, u.right - 4, u.bottom - 3)
        val router = w.addRouter(u.left + 7, u.top + 5)
        play(3f)
        check(w.connect(router, mail, CableType.ISDN))
        check(w.connect(router, call, CableType.ISDN))
        play(3f)
        for ((i, spot) in listOf(2 to 7, 5 to 1, 10 to 3, 12 to 8, 4 to 8).withIndex()) {
            val device = if (i % 2 == 0) Device.PC else Device.PHONE
            val c = w.addClient(device, u.left + spot.first, u.top + spot.second)
            play(2.5f)
            w.connect(c, router, CableType.ISDN)
            play(2.5f)
        }
        while (w.rewardOffer == null) play(0.5f)
        play(0.6f)
        assertTrue("confetti at the week change", view.celebrating)
        view.drawCurrent(Canvas(bmp))
        save(bmp, "week-confetti.png")
        play(3f)
        assertFalse("the celebration is short", view.celebrating)
        w.chooseReward(0)
        // A lonely PC without a cable overflows and ends the game.
        val lonely = w.addClient(Device.PC, u.right - 2, u.top + 1)
        repeat(6) { lonely.pending.addLast(Service.MAIL) }
        var guard = 0
        while (view.currentScreen != Screen.GAME_OVER && guard++ < 200) {
            play(0.5f)
            w.rewardOffer?.let { w.chooseReward(0) }
        }
        assertEquals(Screen.GAME_OVER, view.currentScreen)
        val frames = view.growthRecorder.frames
        assertTrue("recorded the growth: ${frames.size} frames", frames.size >= 5)
        assertTrue("from the first servers to the full network", frames.first().cables.size < frames.last().cables.size)
        view.accessibilityLayer.forceActive = true
        for ((name, t) in listOf("start" to 0.2f, "middle" to 2.0f, "end" to 4.2f)) {
            val recap = GrowthRecap(2f) { "" }
            val shown = recap.frameAt(t, frames.size)
            view.drawCurrent(Canvas(bmp))
            save(bmp, "game-over-recap-$name.png")
            if (name == "start") assertEquals(0, shown)
            if (name == "end") assertEquals(frames.size - 1, shown)
            play(if (name == "start") 1.8f else 2.2f)
        }
        assertNotNull(
            "TalkBack reads the time-lapse",
            view.accessibilityLayer.nodes.firstOrNull { it.key == "menu:picture" },
        )
    }
}
