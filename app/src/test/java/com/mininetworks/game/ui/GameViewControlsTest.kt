package com.mininetworks.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.MotionEvent
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.Cell
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.RadioType
import com.mininetworks.game.game.Scenarios
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.Terrain
import com.mininetworks.game.game.World
import com.mininetworks.game.ui.menu.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Deliberate map controls (a second tap confirms removing, upgrading and picking up; placing happens on a tap),
 * feedback, the in-place pause and robustness against gestures cut short by pauses and game over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de")
@OptIn(DebugApi::class)
class GameViewControlsTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var view: GameView
    private lateinit var world: World
    private val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)

    @Before
    fun setUp() {
        SettingsStore(app).tutorialSeen = true
        world = World(seed = 2L, spawnInitialNodes = false)
        world.incidentsEnabled = false
        for (row in world.water) row.fill(false)
        world.grant(100)
        view = GameView(app)
        draw()
    }

    private fun draw() = view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 0f, style = "Iso")

    private var clock = 0L

    private fun tapAt(x: Float, y: Float) {
        clock += 400L
        view.injectTouch(MotionEvent.ACTION_DOWN, x, y, time = clock)
        view.injectTouch(MotionEvent.ACTION_UP, x, y, time = clock + 50L)
    }

    private fun tapCell(c: Cell) = view.activeRenderer.toScreen(c.center).let { tapAt(it.x, it.y) }

    private fun tapHud(id: String) {
        val r = view.hudTarget(id) ?: throw AssertionError("no HUD button $id")
        tapAt(r.centerX(), r.centerY())
    }

    private fun cell(dx: Int, dy: Int) = Cell(world.unlocked.left + dx, world.unlocked.top + dy)

    @Test
    fun routerIsPlacedOnATapNotAtTheStartOfAPanOrPinch() {
        draw()
        tapHud("router")
        val stock = world.routersAvailable
        val p = view.activeRenderer.toScreen(cell(4, 4).center)
        view.injectTouch(MotionEvent.ACTION_DOWN, p.x, p.y)
        view.injectTouch(MotionEvent.ACTION_MOVE, p.x + 120f, p.y)
        view.injectTouch(MotionEvent.ACTION_UP, p.x + 120f, p.y)
        assertEquals("a pan places nothing", stock, world.routersAvailable)
        assertFalse(view.activeRenderer.camera.followsArea)

        val q = view.activeRenderer.toScreen(cell(4, 4).center)
        view.injectTouch(MotionEvent.ACTION_DOWN, q.x, q.y)
        view.injectTouch(MotionEvent.ACTION_POINTER_DOWN, q.x, q.y, floatArrayOf(q.x, q.y, q.x + 300f, q.y))
        view.injectTouch(MotionEvent.ACTION_POINTER_UP, q.x, q.y, floatArrayOf(q.x, q.y))
        view.injectTouch(MotionEvent.ACTION_UP, q.x, q.y, floatArrayOf())
        assertEquals("a pinch places nothing", stock, world.routersAvailable)

        tapCell(cell(4, 4))
        assertEquals("the tap places it", stock - 1, world.routersAvailable)
        assertNotNull(world.nodeAt(cell(4, 4)))
    }

    @Test
    fun aCellThatDoesNotWorkSaysWhyAndKeepsPlacingArmed() {
        world.setTerrain(cell(3, 3).x, cell(3, 3).y, Terrain.WATER)
        draw()
        tapHud("router")
        tapCell(cell(3, 3))
        assertEquals("Nur auf freiem Land", view.shownHint)
        tapCell(cell(5, 5))
        assertNotNull("still armed: the next tap places", world.nodeAt(cell(5, 5)))
    }

    @Test
    fun anUncabledRouterGoesBackOnASecondTap() {
        val router = world.placeRouter(cell(4, 4).x, cell(4, 4).y)!!
        val stock = world.routersAvailable
        draw()
        tapCell(router.cell)
        assertTrue(router in world.nodes)
        assertTrue(view.shownHint!!.contains("nochmal antippen"))
        tapCell(router.cell)
        assertFalse(router in world.nodes)
        assertEquals(stock + 1, world.routersAvailable)
    }

    @Test
    fun serverUpgradeShowsThePriceFirstAndNeedsASecondTap() {
        val server = world.addServer(Service.MAIL, cell(6, 4).x, cell(6, 4).y)
        draw()
        val budget = world.budget
        val pulses = view.hapticPulses
        tapCell(server.cell)
        assertEquals("the first tap only previews", 1, server.level)
        assertEquals(budget, world.budget)
        assertTrue(view.shownHint!!.contains(World.Tuning.SERVER_UPGRADE_COST[0].toString()))
        tapCell(server.cell)
        assertEquals(2, server.level)
        assertEquals(budget - World.Tuning.SERVER_UPGRADE_COST[0], world.budget)
        assertTrue("feedback", view.hapticPulses > pulses)
        assertTrue(view.shownHint!!.contains("Stufe 2"))
    }

    @Test
    fun cableUpgradeSaysWhatItCostsOrWhyNot() {
        world.jumpToWeek(CableType.COAX.unlockWeek)
        val pc = world.addClient(Device.PC, cell(2, 2).x, cell(2, 2).y)
        val mail = world.addServer(Service.MAIL, cell(8, 2).x, cell(8, 2).y)
        assertTrue(world.connect(pc, mail, CableType.ISDN))
        val cable = world.cableBetween(pc, mail)!!
        draw()
        tapHud("cable:COAX")
        assertTrue("picking names the bandwidth", view.shownHint!!.contains("Bandbreite 6"))
        world.grant(-world.budget)
        val p = view.activeRenderer.toScreen(cable.layout.pointAt(0.5f))
        tapAt(p.x, p.y)
        assertEquals(CableType.ISDN, cable.type)
        assertTrue(view.shownHint!!.startsWith("Zu wenig Budget"))
        world.grant(50)
        val pulses = view.hapticPulses
        tapAt(p.x, p.y)
        assertEquals(CableType.COAX, cable.type)
        assertTrue(view.hapticPulses > pulses)
        assertTrue(view.shownHint!!.contains("aufgerüstet"))
    }

    @Test
    fun pauseButtonStopsTheClockButBuildingGoesOn() {
        val pc = world.addClient(Device.PC, cell(2, 2).x, cell(2, 2).y)
        val mail = world.addServer(Service.MAIL, cell(6, 2).x, cell(6, 2).y)
        draw()
        tapHud("pause")
        assertTrue(view.pausedInPlace)
        assertEquals(Screen.PLAYING, view.currentScreen)
        val time = world.time
        repeat(60) { view.advance(1f / 60f) }
        assertEquals("the clock stands still", time, world.time, 0f)
        val a = view.activeRenderer.toScreen(pc.center)
        val b = view.activeRenderer.toScreen(mail.center)
        view.injectTouch(MotionEvent.ACTION_DOWN, a.x, a.y)
        view.injectTouch(MotionEvent.ACTION_MOVE, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
        view.injectTouch(MotionEvent.ACTION_UP, b.x, b.y)
        assertNotNull("cables can still be laid", world.cableBetween(pc, mail))
        tapHud("pause")
        repeat(60) { view.advance(1f / 60f) }
        assertTrue(world.time > time)
    }

    @Test
    fun radioButtonsOnlyShowWithStock() {
        world.jumpToWeek(RadioType.CELL.unlockWeek)
        draw()
        assertNull("invented, but none won yet", view.hudTarget("radio:WLAN"))
        world.grant(0, extraAccessPoints = 1)
        draw()
        assertNotNull(view.hudTarget("radio:WLAN"))
        assertNull(view.hudTarget("radio:CELL"))
    }

    @Test
    fun tappingADeviceExplainsWhyItIsStuck() {
        world.jumpToWeek(CableType.FIBER.unlockWeek)
        val tv = world.addClient(Device.TV, cell(2, 2).x, cell(2, 2).y)
        val cdn = world.addServer(Service.STREAMING, cell(6, 2).x, cell(6, 2).y)
        world.connect(tv, cdn, CableType.ISDN)
        tv.pending.addLast(Service.STREAMING)
        draw()
        tapCell(tv.cell)
        assertEquals("Smart-TV: Streaming – kein Kabel breit genug (Bandbreite 3)", view.shownHint)
    }

    @Test
    fun gameOverCardSaysWhyTheGameWasLost() {
        world.jumpToWeek(CableType.FIBER.unlockWeek)
        val console = world.addClient(Device.CONSOLE, cell(1, 8).x, cell(1, 8).y)
        val game = world.addServer(Service.GAMING, cell(14, 1).x, cell(14, 1).y)
        world.connect(console, game, CableType.ISDN)
        repeat(World.Tuning.MAX_PENDING) { console.pending.addLast(Service.GAMING) }
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 0f, style = "Iso")
        var s = 0
        while (view.currentScreen != Screen.GAME_OVER && s++ < 60 * 60) view.advance(1f / 60f)
        assertEquals(Screen.GAME_OVER, view.currentScreen)
        val line = view.menuLines.first()
        assertTrue(line, line.startsWith("Gaming auf Konsole ruckelte: "))
        assertTrue(line, line.endsWith("höchstens 140 ms"))
    }

    @Test
    fun gameOverDropsTheHints() {
        world.setTerrain(cell(3, 3).x, cell(3, 3).y, Terrain.WATER)
        val phone = world.addClient(Device.PHONE, cell(10, 8).x, cell(10, 8).y)
        world.addServer(Service.CALL, cell(14, 8).x, cell(14, 8).y)
        repeat(World.Tuning.MAX_PENDING) { phone.pending.addLast(Service.CALL) }
        phone.overload = 0.99f
        draw()
        tapHud("router")
        tapCell(cell(3, 3))
        assertNotNull(view.shownHint)
        var s = 0
        while (view.currentScreen != Screen.GAME_OVER && s++ < 60 * 60) view.advance(1f / 60f)
        assertEquals(Screen.GAME_OVER, view.currentScreen)
        assertNull("no hint peeks out under the result card", view.shownHint)
    }

    @Test
    fun holdingAnAccessPointIntoGameOverDoesNothing() {
        world.jumpToWeek(RadioType.WLAN.unlockWeek)
        val ap = world.addRadio(RadioType.WLAN, cell(4, 4).x, cell(4, 4).y)
        val phone = world.addClient(Device.PHONE, cell(10, 8).x, cell(10, 8).y)
        world.addServer(Service.CALL, cell(14, 8).x, cell(14, 8).y)
        repeat(World.Tuning.MAX_PENDING) { phone.pending.addLast(Service.CALL) }
        phone.overload = 0.99f
        draw()
        val budget = world.budget
        val p = view.activeRenderer.toScreen(ap.center)
        view.injectTouch(MotionEvent.ACTION_DOWN, p.x, p.y)
        repeat(60) { view.advance(1f / 60f) }
        assertTrue(world.gameOver)
        assertFalse("no 5 GHz switch after game over", ap.fiveGhz)
        assertEquals(budget, world.budget)
    }

    @Test
    fun sameSizeSurfaceKeepsTheViewAndANewSizeRefits() {
        draw()
        val camera = view.activeRenderer.camera
        camera.zoomBy(1.7f, 800f, 450f)
        val zoomed = camera.scale
        view.surfaceChanged(view.holder, 0, bmp.width, bmp.height)
        view.advance(0f)
        assertEquals("coming back from the background keeps the zoom", zoomed, camera.scale, 0f)
        view.surfaceChanged(view.holder, 0, 1200, 900)
        view.advance(0f)
        assertTrue("a real resize fits again", camera.followsArea)
    }

    @Test
    fun recreatedActivityContinuesInThePauseMenu() {
        val game = World(Scenarios.RIVER_TOWN, seed = 4L)
        view.drawSnapshot(Canvas(bmp), game, bmp.width, bmp.height, time = 0f, style = "Iso")
        view.pause()
        val state = Bundle().also(view::saveState)
        val fresh = GameView(app)
        fresh.restoreState(state)
        // The save is read on the game thread before its first frame.
        fresh.advance(0f)
        assertEquals(Screen.PAUSED, fresh.currentScreen)
        assertEquals(game.seed, fresh.currentWorld.seed)
        assertEquals(game.nodes.size, fresh.currentWorld.nodes.size)
    }

    @Test
    fun aDownOffTheTutorialBubbleForgetsAnEarlierPress() {
        SettingsStore(app).tutorialSeen = false
        val t = GameView(app)
        val tb = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        t.drawSnapshot(Canvas(tb), t.currentWorld, tb.width, tb.height, time = 0f, screen = null)
        val bubble = t.tutorialTarget(TutorialOverlay.SKIP)!!
        t.injectTouch(MotionEvent.ACTION_DOWN, bubble.centerX(), bubble.centerY())
        t.pause()
        t.back()
        t.advance(0f)
        assertEquals(Screen.PLAYING, t.currentScreen)
        val tutorial = t.currentTutorial!!
        val a = t.activeRenderer.toScreen(tutorial.pc.center)
        val b = t.activeRenderer.toScreen(tutorial.mailServer.center)
        t.injectTouch(MotionEvent.ACTION_DOWN, a.x, a.y)
        t.injectTouch(MotionEvent.ACTION_MOVE, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
        t.injectTouch(MotionEvent.ACTION_UP, b.x, b.y)
        assertSame("the next drag lays its cable", tutorial.mailServer, t.currentWorld.cables.single().other(tutorial.pc))
    }
}
