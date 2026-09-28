package com.mininetworks.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import com.mininetworks.game.data.GameIo
import com.mininetworks.game.data.SaveStore
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.Cell
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.Scenarios
import com.mininetworks.game.game.Tutorial
import com.mininetworks.game.game.TutorialStep
import com.mininetworks.game.game.World
import com.mininetworks.game.ui.menu.MenuAction
import com.mininetworks.game.ui.menu.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The tutorial in the app: first launch, skipping, replaying from the settings, touches, never saved, the finish. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TutorialFlowTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)

    private fun draw(view: GameView) =
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 0f, screen = null)

    private fun newView() = GameView(app).also(::draw)

    /** True if the autosave file exists once the I/O thread has written everything queued so far. */
    private fun saveExists(): Boolean {
        GameIo.awaitIdle()
        return SaveStore(app.filesDir).exists
    }

    private fun tapAt(view: GameView, x: Float, y: Float) {
        view.injectTouch(MotionEvent.ACTION_DOWN, x, y)
        view.injectTouch(MotionEvent.ACTION_UP, x, y)
        draw(view)
    }

    private fun tap(view: GameView, action: MenuAction) {
        val r = view.menuTarget(action) ?: throw AssertionError("$action is not tappable on ${view.currentScreen}")
        tapAt(view, r.centerX(), r.centerY())
    }

    private fun tapBubble(view: GameView, id: String) {
        val r = view.tutorialTarget(id) ?: throw AssertionError("$id is not on the tutorial bubble")
        tapAt(view, r.centerX(), r.centerY())
    }

    private fun tapHud(view: GameView, id: String) {
        val r = view.hudTarget(id) ?: throw AssertionError("no HUD button $id")
        tapAt(view, r.centerX(), r.centerY())
    }

    private fun play(view: GameView, seconds: Float) {
        repeat((seconds * 60).toInt()) { view.advance(1f / 60f) }
        draw(view)
    }

    @Test
    fun firstLaunchOpensTheTutorial() {
        val view = newView()
        assertEquals(Screen.PLAYING, view.currentScreen)
        val t = view.currentTutorial!!
        assertTrue(view.currentWorld.guided)
        assertEquals(TutorialStep.LAY_CABLE, t.step)
        assertEquals(Scenarios.RIVER_TOWN.id, view.currentWorld.scenario.id)
        assertNotNull(view.tutorialTarget(TutorialOverlay.SKIP))
    }

    @Test
    fun skipLeadsToTheMainMenuAndIsRemembered() {
        val view = newView()
        tapBubble(view, TutorialOverlay.SKIP)
        assertEquals(Screen.MAIN_MENU, view.currentScreen)
        assertNull(view.currentTutorial)
        assertTrue(SettingsStore(app).tutorialSeen)
        assertEquals("the next launch opens on the main menu", Screen.MAIN_MENU, newView().currentScreen)
    }

    @Test
    fun aSavedGameMeansItIsNotTheFirstLaunch() {
        SaveStore(app.filesDir).save(World(seed = 1L))
        assertEquals(Screen.MAIN_MENU, newView().currentScreen)
    }

    @Test
    fun tutorialIsNeverSaved() {
        val view = newView()
        play(view, 2f)
        view.pause()
        assertEquals(Screen.PAUSED, view.currentScreen)
        assertFalse(saveExists())
        draw(view)
        tap(view, MenuAction.MAIN_MENU)
        assertEquals(Screen.MAIN_MENU, view.currentScreen)
        assertNull(view.currentTutorial)
        assertTrue("leaving counts as seen", SettingsStore(app).tutorialSeen)
        assertNull("nothing to continue", view.menuTarget(MenuAction.CONTINUE))
    }

    @Test
    fun replayFromSettingsKeepsTheRunningGame() {
        SettingsStore(app).tutorialSeen = true
        val view = newView()
        tap(view, MenuAction.PLAY)
        val r = view.sceneryTarget(Scenarios.RIVER_TOWN.id)!!
        tapAt(view, r.centerX(), r.centerY())
        val game = view.currentWorld
        play(view, 2f)
        view.back()
        view.advance(0f)
        draw(view)
        tap(view, MenuAction.SETTINGS)
        tap(view, MenuAction.TUTORIAL)
        assertEquals(Screen.PLAYING, view.currentScreen)
        assertEquals(TutorialStep.LAY_CABLE, view.currentTutorial!!.step)
        assertTrue("the game was saved before", saveExists())

        view.back()
        view.advance(0f)
        draw(view)
        tap(view, MenuAction.RESTART)
        assertEquals("restart restarts the tutorial", TutorialStep.LAY_CABLE, view.currentTutorial!!.step)
        view.back()
        view.advance(0f)
        draw(view)
        tap(view, MenuAction.MAIN_MENU)
        tap(view, MenuAction.CONTINUE)
        assertEquals(Screen.PLAYING, view.currentScreen)
        assertNull(view.currentTutorial)
        assertEquals(game.snapshot(), view.currentWorld.snapshot())
    }

    @Test
    fun playerFollowsTheStepsWithTouches() {
        val view = newView()
        val t = view.currentTutorial!!
        val w = view.currentWorld
        val renderer = view.activeRenderer
        // Step 1: drag from the PC to the mail server.
        val a = renderer.toScreen(t.pc.center)
        val b = renderer.toScreen(t.mailServer.center)
        view.injectTouch(MotionEvent.ACTION_DOWN, a.x, a.y)
        view.injectTouch(MotionEvent.ACTION_MOVE, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
        view.injectTouch(MotionEvent.ACTION_UP, b.x, b.y)
        play(view, 0.1f)
        assertEquals(TutorialStep.PLACE_ROUTER, t.step)

        // Step 2: the router button, then a free cell near the phones.
        tapHud(view, "router")
        val cell = w.nearestFree(Cell(t.phones[0].cellX - 2, t.phones[0].cellY + 1))!!
        val p = renderer.toScreen(cell.center)
        tapAt(view, p.x, p.y)
        val router = w.nodes.single { it.kind == NodeKind.ROUTER }
        for (n in t.phones) assertTrue(w.connect(n, router, CableType.ISDN))
        play(view, 0.1f)
        assertEquals(TutorialStep.BANDWIDTH, t.step)

        // Step 3: the TV first gets ISDN like everything so far: too narrow for streaming. Then DSL, and tap its cable.
        val tv = renderer.toScreen(t.tv!!.center)
        val cdn = renderer.toScreen(t.streamServer!!.center)
        view.injectTouch(MotionEvent.ACTION_DOWN, tv.x, tv.y)
        view.injectTouch(MotionEvent.ACTION_MOVE, (tv.x + cdn.x) / 2f, (tv.y + cdn.y) / 2f)
        view.injectTouch(MotionEvent.ACTION_UP, cdn.x, cdn.y)
        play(view, 2f)
        assertEquals(TutorialStep.BANDWIDTH, t.step)
        assertTrue(t.tooNarrow())
        assertTrue("the bubble explains the bandwidth", view.tutorialText()!!.contains(app.getString(com.mininetworks.game.R.string.tutorial_bandwidth_narrow_text, 3, 2, 4)))
        tapHud(view, "cable:DSL")
        assertEquals(CableType.DSL, view.pickedCable)
        val cable = w.cableBetween(t.tv!!, t.streamServer!!)!!
        val mid = renderer.toScreen(cable.layout.pointAt(0.5f))
        tapAt(view, mid.x, mid.y)
        assertEquals(CableType.DSL, cable.type)
        play(view, 0.1f)
        assertEquals(TutorialStep.PING, t.step)
        assertTrue("fiber is now in the picker", view.hudTarget("cable:FIBER") != null)
    }

    @Test
    fun bubbleTouchesNeverReachTheMap() {
        val view = newView()
        val t = view.currentTutorial!!
        val skip = view.tutorialTarget(TutorialOverlay.SKIP)!!
        // Down on the bubble (left of "skip"), dragged away over the map: no cable, no skip.
        view.injectTouch(MotionEvent.ACTION_DOWN, skip.left - 40f, skip.centerY())
        val b = view.activeRenderer.toScreen(t.mailServer.center)
        view.injectTouch(MotionEvent.ACTION_MOVE, b.x, b.y)
        view.injectTouch(MotionEvent.ACTION_UP, b.x, b.y)
        draw(view)
        assertTrue(view.currentWorld.cables.isEmpty())
        assertEquals(Screen.PLAYING, view.currentScreen)
        assertEquals(TutorialStep.LAY_CABLE, t.step)
    }

    @Test
    fun finishingOffersANewGameInTheRiverTown() {
        val view = newView()
        val t = view.currentTutorial!!
        val w = view.currentWorld
        assertTrue(w.connect(t.pc, t.mailServer, CableType.ISDN))
        play(view, 0.1f)
        val router = w.placeRouter(t.phones[0].cellX - 1, t.phones[0].cellY)!!
        for (n in t.phones) assertTrue(w.connect(n, router, CableType.ISDN))
        play(view, 0.1f)
        assertTrue(w.connect(t.tv!!, t.streamServer!!, CableType.DSL))
        play(view, 0.1f)
        assertTrue(w.connect(t.pc, t.gameServer!!, CableType.FIBER))
        play(view, 0.1f)
        assertTrue(w.connect(t.newPc!!, t.mailServer, CableType.DSL))
        play(view, 1f)
        assertEquals(TutorialStep.PORTS, t.step)
        // Step 6 with touches: the router tile dragged next to the new PCs, then both PCs and the server cabled to it.
        val pcs = t.officePcs
        val tile = view.hudTarget("router")!!
        val spot = w.nearestFree(Cell(pcs[1].cellX + 1, pcs[1].cellY))!!
        val drop = view.activeRenderer.toScreen(spot.center)
        view.injectTouch(MotionEvent.ACTION_DOWN, tile.centerX(), tile.centerY())
        view.injectTouch(MotionEvent.ACTION_MOVE, drop.x, drop.y)
        draw(view)
        view.injectTouch(MotionEvent.ACTION_UP, drop.x, drop.y)
        val shared = w.nodes.single { it.kind == NodeKind.ROUTER && it.cell == spot }
        for (n in pcs + t.mailServer) assertTrue(w.connect(n, shared, CableType.DSL))
        play(view, 0.1f)
        assertTrue(t.finished)
        assertNull("no skip at the end", view.tutorialTarget(TutorialOverlay.SKIP))
        tapBubble(view, TutorialOverlay.PLAY)
        assertEquals(Screen.PLAYING, view.currentScreen)
        assertNull(view.currentTutorial)
        assertNotSame(w, view.currentWorld)
        assertFalse(view.currentWorld.guided)
        assertEquals(Scenarios.RIVER_TOWN.id, view.currentWorld.scenario.id)
        assertEquals(Scenarios.RIVER_TOWN.startBudget, view.currentWorld.budget)
        assertTrue(SettingsStore(app).tutorialSeen)
        assertEquals(Tutorial.STEPS, t.number)
    }

    /**
     * docs/TOP100.md B1, the tutorial bot in the app: from the very first launch it reads the first bubble as it is
     * shown (at 150 words per minute, plus 3 s to find the PC), drags the cable with a finger over 1.5 s and waits for
     * the answer. The first packet must be delivered within 30 s of game time.
     */
    @Test
    fun tutorialBotDeliversTheFirstPacketWithinThirtySeconds() {
        val view = newView()
        val t = view.currentTutorial!!
        val w = view.currentWorld
        assertEquals(0f, w.time, 0f)
        val bubble = view.tutorialText()!!
        val words = bubble.split(' ').count { it.isNotBlank() }
        val reading = words / 150f * 60f + 3f
        play(view, reading)
        val renderer = view.activeRenderer
        val a = renderer.toScreen(t.pc.center)
        val b = renderer.toScreen(t.mailServer.center)
        view.injectTouch(MotionEvent.ACTION_DOWN, a.x, a.y)
        val frames = 90
        for (k in 1..frames) {
            view.injectTouch(MotionEvent.ACTION_MOVE, a.x + (b.x - a.x) * k / frames, a.y + (b.y - a.y) * k / frames)
            view.advance(1f / 60f)
        }
        view.injectTouch(MotionEvent.ACTION_UP, b.x, b.y)
        assertNotNull("the bot's drag laid the cable", w.cableBetween(t.pc, t.mailServer))
        while (w.delivered == 0 && w.time < 60f) view.advance(1f / 60f)
        println("TutorialFlowTest: bot read $words words in %.1f s, first packet delivered at %.1f s of game time".format(reading, w.time))
        assertTrue("first delivery at ${w.time} s", w.delivered >= 1 && w.time <= 30f)
    }
}
