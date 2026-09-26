package com.mininetworks.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import com.mininetworks.game.R
import com.mininetworks.game.data.HighscoreStore
import com.mininetworks.game.data.SaveStore
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.WeekNews
import com.mininetworks.game.game.World
import com.mininetworks.game.render.ServiceColors
import com.mininetworks.game.ui.menu.MenuAction
import com.mininetworks.game.ui.menu.Screen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Main menu, pause menu, settings, autosave and "continue", game over with the best score. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(DebugApi::class)
class MenuFlowTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)

    @After
    fun resetPalette() {
        ServiceColors.colorblind = false
    }

    private fun draw(view: GameView) =
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 0f, screen = null)

    /** Taps the menu entry for [action] as the player would, then draws the next frame. */
    private fun tap(view: GameView, action: MenuAction) {
        val r = view.menuTarget(action) ?: throw AssertionError("$action is not tappable on ${view.currentScreen}")
        view.injectTouch(MotionEvent.ACTION_DOWN, r.centerX(), r.centerY())
        view.injectTouch(MotionEvent.ACTION_UP, r.centerX(), r.centerY())
        draw(view)
    }

    private fun play(view: GameView, seconds: Float) {
        repeat((seconds * 60).toInt()) { view.advance(1f / 60f) }
    }

    private fun newView() = GameView(app).also(::draw)

    @Test
    fun opensOnMainMenuOverDemoTown() {
        val view = newView()
        assertEquals(Screen.MAIN_MENU, view.currentScreen)
        assertNotNull(view.menuTarget(MenuAction.PLAY))
        assertNull("nothing to continue yet", view.menuTarget(MenuAction.CONTINUE))
        val demo = view.currentWorld.snapshot()
        play(view, 2f)
        assertEquals("the demo town does not run", demo, view.currentWorld.snapshot())
    }

    @Test
    fun playPauseAndResume() {
        val view = newView()
        val demo = view.currentWorld
        tap(view, MenuAction.PLAY)
        assertEquals(Screen.PLAYING, view.currentScreen)
        assertNotSame(demo, view.currentWorld)
        play(view, 1f)
        assertTrue(view.currentWorld.time > 0.9f)

        view.back()
        view.advance(0f)
        assertEquals(Screen.PAUSED, view.currentScreen)
        assertTrue("opening the pause menu saves", SaveStore(app.filesDir).exists)
        val time = view.currentWorld.time
        play(view, 1f)
        assertEquals("paused", time, view.currentWorld.time)
        draw(view)
        tap(view, MenuAction.RESUME)
        assertEquals(Screen.PLAYING, view.currentScreen)
    }

    @Test
    fun activityPauseSavesAndContinueRestoresAfterRestart() {
        val view = newView()
        tap(view, MenuAction.PLAY)
        play(view, 5f)
        view.pause()
        assertEquals(Screen.PAUSED, view.currentScreen)
        val saved = view.currentWorld.snapshot()

        val restarted = newView()
        assertEquals(Screen.MAIN_MENU, restarted.currentScreen)
        tap(restarted, MenuAction.CONTINUE)
        assertEquals(Screen.PLAYING, restarted.currentScreen)
        assertEquals(saved, restarted.currentWorld.snapshot())
    }

    @Test
    fun mainMenuFromPauseKeepsTheGameToContinue() {
        val view = newView()
        tap(view, MenuAction.PLAY)
        val game = view.currentWorld
        play(view, 2f)
        view.back()
        view.advance(0f)
        draw(view)
        tap(view, MenuAction.MAIN_MENU)
        assertEquals(Screen.MAIN_MENU, view.currentScreen)
        tap(view, MenuAction.CONTINUE)
        assertEquals(Screen.PLAYING, view.currentScreen)
        assertTrue(game === view.currentWorld)
    }

    /** A game with some delivered packets that ends in game over within [World.Tuning.OVERLOAD_SECONDS]. */
    private fun doomedGame(extraPackets: Int): World {
        val w = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false)
        w.grant(50)
        val mail = w.addServer(Service.MAIL, 1, 1)
        val pc = w.addClient(Device.PC, 3, 1)
        w.connect(pc, mail, CableType.ISDN)
        while (w.delivered < extraPackets) {
            if (pc.pending.isEmpty()) pc.pending.addLast(Service.MAIL)
            w.update(1f / 60f)
        }
        w.removeCable(w.cables.single())
        val lonely = w.addClient(Device.PHONE, 3, 6)
        w.addServer(Service.CALL, 5, 6)
        repeat(World.Tuning.MAX_PENDING) { lonely.pending.addLast(Service.CALL) }
        return w
    }

    @Test
    fun gameOverRecordsBestScoreAndDropsTheSave() {
        val view = newView()
        val first = doomedGame(3)
        view.drawSnapshot(Canvas(bmp), first, bmp.width, bmp.height, time = 0f)
        view.pause()
        assertTrue(SaveStore(app.filesDir).exists)
        draw(view)
        tap(view, MenuAction.RESUME)
        play(view, World.Tuning.OVERLOAD_SECONDS + 3f)
        assertEquals(Screen.GAME_OVER, view.currentScreen)
        assertEquals(3, HighscoreStore(app).best())
        assertFalse("a lost game cannot be continued", SaveStore(app.filesDir).exists)

        view.drawSnapshot(Canvas(bmp), doomedGame(1), bmp.width, bmp.height, time = 0f)
        play(view, World.Tuning.OVERLOAD_SECONDS + 3f)
        assertEquals(Screen.GAME_OVER, view.currentScreen)
        assertEquals("a lower score keeps the best", 3, HighscoreStore(app).best())

        draw(view)
        tap(view, MenuAction.MAIN_MENU)
        assertEquals(Screen.MAIN_MENU, view.currentScreen)
        assertNull(view.menuTarget(MenuAction.CONTINUE))
        tap(view, MenuAction.PLAY)
        assertEquals(Screen.PLAYING, view.currentScreen)
        assertFalse(view.currentWorld.gameOver)
    }

    @Test
    fun gameOverFirstFocusesTheFailedDevice() {
        val view = newView()
        view.drawSnapshot(Canvas(bmp), doomedGame(2), bmp.width, bmp.height, time = 0f)
        val camera = view.activeRenderer.camera
        val fitted = camera.scale
        var s = 0
        while (!view.currentWorld.gameOver && s++ < 60 * 30) view.advance(1f / 60f)
        view.advance(1f / 60f)
        assertEquals("the map stays visible while the camera moves", Screen.PLAYING, view.currentScreen)
        assertTrue(camera.isAnimating)
        assertTrue("save is dropped right away", !SaveStore(app.filesDir).exists)
        play(view, 1f)
        assertEquals(Screen.PLAYING, view.currentScreen)
        assertTrue("zooms in", camera.scale > fitted * 1.3f)
        val failed = view.currentWorld.failedNode!!
        val onScreen = view.activeRenderer.toScreen(failed.center)
        assertEquals(bmp.width / 2f, onScreen.x, bmp.width * 0.1f)
        play(view, 1f)
        assertEquals(Screen.GAME_OVER, view.currentScreen)

        view.drawSnapshot(Canvas(bmp), doomedGame(2), bmp.width, bmp.height, time = 0f)
        s = 0
        while (!view.currentWorld.gameOver && s++ < 60 * 30) view.advance(1f / 60f)
        view.advance(1f / 60f)
        view.injectTouch(MotionEvent.ACTION_DOWN, 10f, 10f)
        view.injectTouch(MotionEvent.ACTION_UP, 10f, 10f)
        assertEquals("a tap skips to the result", Screen.GAME_OVER, view.currentScreen)
    }

    @Test
    fun liftingAFingerFromBeforeGameOverKeepsTheFocus() {
        val view = newView()
        view.drawSnapshot(Canvas(bmp), doomedGame(2), bmp.width, bmp.height, time = 0f)
        val phone = view.activeRenderer.toScreen(view.currentWorld.nodes.first { it.device == Device.PHONE }.center)
        view.injectTouch(MotionEvent.ACTION_DOWN, phone.x, phone.y)
        view.injectTouch(MotionEvent.ACTION_MOVE, phone.x + 40f, phone.y + 20f)
        var s = 0
        while (!view.currentWorld.gameOver && s++ < 60 * 30) view.advance(1f / 60f)
        view.advance(1f / 60f)
        view.injectTouch(MotionEvent.ACTION_UP, phone.x + 40f, phone.y + 20f)
        view.advance(1f / 60f)
        assertEquals("the cable drag ends without skipping the focus", Screen.PLAYING, view.currentScreen)
        assertTrue(view.activeRenderer.camera.isAnimating)
        view.injectTouch(MotionEvent.ACTION_DOWN, 10f, 10f)
        view.injectTouch(MotionEvent.ACTION_UP, 10f, 10f)
        assertEquals("a new tap still skips", Screen.GAME_OVER, view.currentScreen)
    }

    @Test
    fun settingsApplyAndPersist() {
        val view = newView()
        assertEquals("isometric is the main style", "Iso", view.activeRenderer.name)
        tap(view, MenuAction.SETTINGS)
        assertEquals(Screen.SETTINGS, view.currentScreen)
        tap(view, MenuAction.TOGGLE_OVERVIEW)
        assertEquals("Flat", view.activeRenderer.name)
        tap(view, MenuAction.TOGGLE_COLORBLIND)
        assertTrue(ServiceColors.colorblind)
        tap(view, MenuAction.TOGGLE_SOUND)
        tap(view, MenuAction.BACK)
        assertEquals(Screen.MAIN_MENU, view.currentScreen)

        ServiceColors.colorblind = false
        val restarted = newView()
        assertEquals("Flat", restarted.activeRenderer.name)
        assertTrue(ServiceColors.colorblind)
    }

    @Test
    fun settingsFromPauseReturnToPause() {
        val view = newView()
        tap(view, MenuAction.PLAY)
        view.back()
        view.advance(0f)
        draw(view)
        tap(view, MenuAction.SETTINGS)
        view.back()
        view.advance(0f)
        assertEquals(Screen.PAUSED, view.currentScreen)
    }

    @Test
    fun hapticsFollowTheSetting() {
        fun layCable(view: GameView): Int {
            val w = World(seed = 2L, spawnInitialNodes = false)
            for (row in w.water) row.fill(false)
            w.grant(100)
            val pc = w.addClient(Device.PC, 10, 7)
            val mail = w.addServer(Service.MAIL, 14, 9)
            view.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 0f, style = "Iso")
            val a = view.activeRenderer.toScreen(pc.center)
            val b = view.activeRenderer.toScreen(mail.center)
            val before = view.hapticPulses
            view.injectTouch(MotionEvent.ACTION_DOWN, a.x, a.y)
            view.injectTouch(MotionEvent.ACTION_MOVE, b.x, b.y)
            view.injectTouch(MotionEvent.ACTION_UP, b.x, b.y)
            assertNotNull(w.cableBetween(pc, mail))
            return view.hapticPulses - before
        }
        val view = newView()
        assertEquals("snap onto the target, then the cable locks in", 2, layCable(view))
        view.back()
        view.advance(0f)
        draw(view)
        tap(view, MenuAction.SETTINGS)
        tap(view, MenuAction.TOGGLE_HAPTICS)
        assertEquals(0, layCable(view))
    }

    @Test
    fun backOnMainMenuExits() {
        val view = newView()
        var exited = false
        view.onExit = { exited = true }
        view.back()
        view.advance(0f)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue(exited)
    }

    @Test
    fun pauseButtonWorksDuringTheRewardChoice() {
        val view = newView()
        tap(view, MenuAction.PLAY)
        val world = view.currentWorld
        world.jumpToWeek(1)
        world.advanceToNextWeek()
        val offer = world.rewardOffer
        assertNotNull(offer)
        draw(view)
        val pause = view.hudTarget("pause") ?: throw AssertionError("no pause button")
        view.injectTouch(MotionEvent.ACTION_DOWN, pause.centerX(), pause.centerY())
        view.injectTouch(MotionEvent.ACTION_UP, pause.centerX(), pause.centerY())
        view.advance(0f)
        assertEquals(Screen.PAUSED, view.currentScreen)
        draw(view)
        tap(view, MenuAction.RESUME)
        assertEquals(Screen.PLAYING, view.currentScreen)
        assertEquals("the choice is still open", offer, world.rewardOffer)
    }

    @Test
    @Config(qualifiers = "de")
    fun newsListsItemsWithTheLocalSeparator() {
        val news = WeekNews(2010, emptyList(), listOf(Device.WATCH), listOf(Service.CALL))
        assertEquals("Neu: Smartwatch, Telefonie-Server", Texts(app).news(news, withYear = false))
    }

    @Test
    fun clockCutsToTenMinutesAndWrapsPastMidnight() {
        val t = Texts(app)
        assertEquals("00:00", t.clock(0f))
        assertEquals("02:00", t.clock(2f))
        assertEquals("02:10", t.clock(2f + 1f / 6f))
        assertEquals("13:50", t.clock(13.99f))
        assertEquals("23:50", t.clock(23.99f))
        assertEquals("00:00", t.clock(24f))
        assertEquals("01:30", t.clock(25.5f))
    }

    @Test
    @Config(qualifiers = "de")
    fun hudShowsTheClockOnlyOnceANightlyServerExists() {
        val view = newView()
        val w = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false)
        w.addServer(Service.MAIL, 1, 1)
        w.addServer(Service.CAMERA_UPLOAD, 5, 1)
        view.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 0f)
        assertNull("no nightly service yet", view.clockLabel())
        w.addServer(Service.CLOUD_BACKUP, 9, 1)
        assertEquals("Tag · 06:00", view.clockLabel())
        repeat((World.Tuning.DAY_SECONDS * 0.8f * 60).toInt()) { w.update(1f / 60f) }
        assertEquals("Nacht · 01:10 · Backups um 02:00", view.clockLabel())
    }

    @Test
    @Config(qualifiers = "de")
    fun germanSystemLanguage() {
        assertEquals("Spielen", app.getString(R.string.menu_play))
        assertEquals("Glasfaser", Texts(app).cable(CableType.FIBER))
    }

    @Test
    @Config(qualifiers = "fr")
    fun otherLanguagesFallBackToGerman() {
        assertEquals("Spielen", app.getString(R.string.menu_play))
    }

    @Test
    @Config(qualifiers = "en")
    fun englishFollowsTheSystemLanguage() {
        assertEquals("Play", app.getString(R.string.menu_play))
        assertEquals("Fiber", Texts(app).cable(CableType.FIBER))
        assertNotEquals("Netz überlastet", app.getString(R.string.game_over_title))
    }
}
