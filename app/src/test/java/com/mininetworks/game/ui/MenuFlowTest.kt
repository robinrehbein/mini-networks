package com.mininetworks.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import com.mininetworks.game.R
import com.mininetworks.game.audio.Alarm
import com.mininetworks.game.audio.ServicePitch
import com.mininetworks.game.audio.Sound
import com.mininetworks.game.data.HighscoreStore
import com.mininetworks.game.data.GameIo
import com.mininetworks.game.data.SaveStore
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Scenarios
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.WeekNews
import com.mininetworks.game.game.World
import com.mininetworks.game.monetization.Entitlements
import com.mininetworks.game.monetization.FakeMonetization
import com.mininetworks.game.render.ServiceColors
import com.mininetworks.game.ui.menu.MenuAction
import com.mininetworks.game.ui.menu.SceneryPicker
import com.mininetworks.game.ui.menu.Screen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
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

    /** These tests start after the tutorial; [TutorialFlowTest] covers the first launch. */
    @Before
    fun tutorialSeen() {
        SettingsStore(app).tutorialSeen = true
    }

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

    /** "Spielen", then the card of scenery [id] on the picker. */
    private fun pickScenery(view: GameView, id: String = Scenarios.RIVER_TOWN.id) {
        tap(view, MenuAction.PLAY)
        assertEquals(Screen.SCENERIES, view.currentScreen)
        tapScenery(view, id)
    }

    private fun tapScenery(view: GameView, id: String) {
        val r = view.sceneryTarget(id) ?: throw AssertionError("$id is not on ${view.currentScreen}")
        view.injectTouch(MotionEvent.ACTION_DOWN, r.centerX(), r.centerY())
        view.injectTouch(MotionEvent.ACTION_UP, r.centerX(), r.centerY())
        draw(view)
    }

    private fun play(view: GameView, seconds: Float) {
        repeat((seconds * 60).toInt()) { view.advance(1f / 60f) }
    }

    private fun newView() = GameView(app).also(::draw)

    /** True if the autosave file exists once the I/O thread has written everything queued so far. */
    private fun saveExists(): Boolean {
        GameIo.awaitIdle()
        return SaveStore(app.filesDir).exists
    }

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
        pickScenery(view)
        assertEquals(Screen.PLAYING, view.currentScreen)
        assertNotSame(demo, view.currentWorld)
        play(view, 1f)
        assertTrue(view.currentWorld.time > 0.9f)

        view.back()
        view.advance(0f)
        assertEquals(Screen.PAUSED, view.currentScreen)
        assertTrue("opening the pause menu saves", saveExists())
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
        pickScenery(view)
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
        pickScenery(view)
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

    /** A game with some delivered packets that ends in game over within [LOSE_SECONDS]. */
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
        assertTrue(saveExists())
        draw(view)
        tap(view, MenuAction.RESUME)
        play(view, LOSE_SECONDS)
        assertEquals(Screen.GAME_OVER, view.currentScreen)
        assertEquals(3, HighscoreStore(app).best())
        assertFalse("a lost game cannot be continued", saveExists())

        view.drawSnapshot(Canvas(bmp), doomedGame(1), bmp.width, bmp.height, time = 0f)
        play(view, LOSE_SECONDS)
        assertEquals(Screen.GAME_OVER, view.currentScreen)
        assertEquals("a lower score keeps the best", 3, HighscoreStore(app).best())

        draw(view)
        tap(view, MenuAction.MAIN_MENU)
        assertEquals(Screen.MAIN_MENU, view.currentScreen)
        assertNull(view.menuTarget(MenuAction.CONTINUE))
        pickScenery(view)
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
        while (!view.currentWorld.gameOver && s++ < 60 * 60) view.advance(1f / 60f)
        view.advance(1f / 60f)
        assertEquals("the map stays visible while the camera moves", Screen.PLAYING, view.currentScreen)
        assertTrue(camera.isAnimating)
        assertTrue("save is dropped right away", !saveExists())
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
        while (!view.currentWorld.gameOver && s++ < 60 * 60) view.advance(1f / 60f)
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
        while (!view.currentWorld.gameOver && s++ < 60 * 60) view.advance(1f / 60f)
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
        tap(view, MenuAction.TOGGLE_SOUND)
        tap(view, MenuAction.APPEARANCE)
        assertEquals(Screen.APPEARANCE, view.currentScreen)
        tap(view, MenuAction.TOGGLE_OVERVIEW)
        assertEquals("Flat", view.activeRenderer.name)
        tap(view, MenuAction.TOGGLE_COLORBLIND)
        assertTrue(ServiceColors.colorblind)
        tap(view, MenuAction.BACK)
        assertEquals(Screen.SETTINGS, view.currentScreen)
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
        pickScenery(view)
        view.back()
        view.advance(0f)
        draw(view)
        tap(view, MenuAction.SETTINGS)
        tap(view, MenuAction.APPEARANCE)
        view.back()
        view.advance(0f)
        assertEquals("system back leads from the second settings page to the first", Screen.SETTINGS, view.currentScreen)
        draw(view)
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
    fun soundsFollowTheGameAndTheSetting() {
        fun session(view: GameView): List<Sound> {
            val w = World(seed = 2L, spawnInitialNodes = false)
            for (row in w.water) row.fill(false)
            w.grant(100)
            w.incidentsEnabled = false
            val pc = w.addClient(Device.PC, 10, 7)
            val mail = w.addServer(Service.MAIL, 14, 9)
            val phone = w.addClient(Device.PHONE, 3, 3)
            view.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 0f, style = "Iso")
            view.advance(1f / 60f)
            val before = view.playedSounds.size
            val a = view.activeRenderer.toScreen(pc.center)
            val b = view.activeRenderer.toScreen(mail.center)
            view.injectTouch(MotionEvent.ACTION_DOWN, a.x, a.y)
            view.injectTouch(MotionEvent.ACTION_MOVE, b.x, b.y)
            view.injectTouch(MotionEvent.ACTION_UP, b.x, b.y)
            assertNotNull(w.cableBetween(pc, mail))
            pc.pending.addLast(Service.MAIL)
            var frames = 0
            while (w.delivered == 0 && frames++ < 60 * 20) view.advance(1f / 60f)
            assertTrue(w.delivered > 0)
            repeat(World.Tuning.MAX_PENDING) { phone.pending.addLast(Service.CALL) }
            repeat(10) { view.advance(1f / 60f) }
            phone.pending.clear()
            w.advanceToNextWeek()
            view.advance(1f / 60f)
            return view.playedSounds.drop(before).map { it.first }
        }
        val view = newView()
        val heard = session(view)
        assertEquals("a cable click first", Sound.CABLE, heard.first())
        assertTrue("a pluck per delivery", Sound.PLUCK in heard)
        assertEquals("one warning for the overloaded phone", 1, heard.count { it == Sound.WARNING })
        assertEquals("the week ends with a chime", Sound.CHIME, heard.last())
        assertEquals(ServicePitch.rate(Service.MAIL), view.playedSounds.first { it.first == Sound.PLUCK }.second, 0f)
        view.back()
        view.advance(0f)
        draw(view)
        tap(view, MenuAction.SETTINGS)
        tap(view, MenuAction.TOGGLE_SOUND)
        assertEquals(emptyList<Sound>(), session(view))
    }

    @Test
    fun alarmRisesWithTheRingAndBuzzesOnlyWithHaptics() {
        /** Fills an unserved phone's ring in steps; the sounds and alarm pulses that came with it. */
        fun lose(view: GameView): Triple<List<Pair<Sound, Float>>, List<Float>, Int> {
            val w = World(seed = 2L, spawnInitialNodes = false)
            for (row in w.water) row.fill(false)
            w.incidentsEnabled = false
            val phone = w.addClient(Device.PHONE, 3, 3)
            view.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 0f, style = "Iso")
            view.advance(1f / 60f)
            val sounds = view.playedSounds.size
            val pulses = view.alarmPulses
            for (ring in floatArrayOf(0f, 0.55f, 0.85f, 0.99999f)) {
                while (phone.pending.size < World.Tuning.MAX_PENDING) phone.pending.addLast(Service.CALL)
                phone.overload = ring
                view.advance(1f / 60f)
            }
            assertTrue("the phone's ring closed", w.gameOver)
            repeat(5) { view.advance(1f / 60f) }
            return Triple(view.playedSounds.drop(sounds), view.playedVolumes.drop(sounds), view.alarmPulses - pulses)
        }
        val view = newView()
        val (heard, volumes, pulses) = lose(view)
        assertEquals(
            listOf(Sound.WARNING to 1f, Sound.WARNING to Alarm.HALF_RATE, Sound.WARNING to Alarm.CRITICAL_RATE, Sound.GAME_OVER to 1f),
            heard.filter { it.first != Sound.CHIME },
        )
        val warnings = heard.indices.filter { heard[it].first == Sound.WARNING }.map { volumes[it] }
        assertTrue("each alarm stage is louder: $warnings", warnings.zipWithNext().all { (a, b) -> b > a })
        assertEquals("a heavy click at 80 %, a long buzz on game over", 2, pulses)
        val quiet = newView()
        quiet.drawSnapshot(Canvas(bmp), World(seed = 2L, spawnInitialNodes = false), bmp.width, bmp.height, time = 0f, style = "Iso")
        quiet.back()
        quiet.advance(0f)
        draw(quiet)
        tap(quiet, MenuAction.SETTINGS)
        tap(quiet, MenuAction.TOGGLE_HAPTICS)
        assertEquals("haptics off: no buzz", 0, lose(quiet).third)
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
    fun menuButtonWorksDuringTheRewardChoice() {
        val view = newView()
        pickScenery(view)
        val world = view.currentWorld
        world.jumpToWeek(1)
        world.advanceToNextWeek()
        val offer = world.rewardOffer
        assertNotNull(offer)
        draw(view)
        val menu = view.hudTarget("menu") ?: throw AssertionError("no menu button")
        view.injectTouch(MotionEvent.ACTION_DOWN, menu.centerX(), menu.centerY())
        view.injectTouch(MotionEvent.ACTION_UP, menu.centerX(), menu.centerY())
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
        assertEquals("Neu: Smartwatch, Telefonzentrale", Texts(app).news(news, withYear = false))
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

    /** A language the game is not translated into (docs/TOP100.md F1 has 12) gets the default, German. */
    @Test
    @Config(qualifiers = "ru")
    fun otherLanguagesFallBackToGerman() {
        assertEquals("Spielen", app.getString(R.string.menu_play))
    }

    @Test
    @Config(qualifiers = "fr")
    fun frenchFollowsTheSystemLanguage() {
        assertEquals("Jouer", app.getString(R.string.menu_play))
        assertEquals("Fibre", Texts(app).cable(CableType.FIBER))
    }

    @Test
    @Config(qualifiers = "en")
    fun englishFollowsTheSystemLanguage() {
        assertEquals("Play", app.getString(R.string.menu_play))
        assertEquals("Fiber", Texts(app).cable(CableType.FIBER))
        assertNotEquals("Netz überlastet", app.getString(R.string.game_over_title))
    }

    // ---------------------------------------------------------------- sceneries

    @Test
    fun playOpensTheSceneryPickerWithOnlyTheRiverTownOpen() {
        val view = newView()
        tap(view, MenuAction.PLAY)
        assertEquals(Screen.SCENERIES, view.currentScreen)
        for (s in Scenarios.all) assertNotNull(view.sceneryTarget(s.id))
        val demo = view.currentWorld
        tapScenery(view, Scenarios.METROPOLIS.id)
        assertEquals("a locked scenery does not start", Screen.SCENERIES, view.currentScreen)
        assertSame(demo, view.currentWorld)
        tapScenery(view, Scenarios.FUTURE.id)
        assertEquals(Screen.SCENERIES, view.currentScreen)
        tapScenery(view, SceneryPicker.BACK)
        assertEquals(Screen.MAIN_MENU, view.currentScreen)
        tap(view, MenuAction.PLAY)
        view.back()
        view.advance(0f)
        assertEquals("back leaves the picker", Screen.MAIN_MENU, view.currentScreen)
        pickScenery(view)
        assertEquals(Screen.PLAYING, view.currentScreen)
        assertEquals(Scenarios.RIVER_TOWN, view.currentWorld.scenario)
    }

    @Test
    fun reachingTheGoalUnlocksTheNextScenery() {
        HighscoreStore(app).submit(Scenarios.METROPOLIS_TARGET, Scenarios.RIVER_TOWN.id)
        val view = newView()
        pickScenery(view, Scenarios.METROPOLIS.id)
        assertEquals(Screen.PLAYING, view.currentScreen)
        val w = view.currentWorld
        assertEquals(Scenarios.METROPOLIS, w.scenario)
        assertEquals(1998, w.year)
        assertEquals(Scenarios.METROPOLIS.cols, w.cols)

        view.back()
        view.advance(0f)
        draw(view)
        tap(view, MenuAction.RESTART)
        assertEquals("restart keeps the scenery", Scenarios.METROPOLIS, view.currentWorld.scenario)
        assertNotSame(w, view.currentWorld)
        view.pause()
        val restarted = newView()
        tap(restarted, MenuAction.CONTINUE)
        assertEquals(Scenarios.METROPOLIS, restarted.currentWorld.scenario)
    }

    @Test
    fun boughtSceneriesArePlayable() {
        val view = newView()
        val shop = FakeMonetization(
            owned = mutableSetOf(Entitlements.sceneryProduct(Scenarios.MOUNTAIN_VILLAGE.id)),
            prices = mapOf(Entitlements.sceneryProduct(Scenarios.FUTURE.id) to "1,99 €"),
        )
        view.monetization = shop
        tap(view, MenuAction.PLAY)
        tapScenery(view, Scenarios.FUTURE.id)
        assertEquals("a locked scenery asks the store", listOf(Entitlements.sceneryProduct(Scenarios.FUTURE.id)), shop.purchases)
        assertEquals(Screen.SCENERIES, view.currentScreen)
        tapScenery(view, Scenarios.MOUNTAIN_VILLAGE.id)
        assertEquals(Screen.PLAYING, view.currentScreen)
        assertEquals(Scenarios.MOUNTAIN_VILLAGE, view.currentWorld.scenario)
    }

    @Test
    fun bestScoresArePerScenery() {
        val view = newView()
        val metro = World(Scenarios.METROPOLIS, seed = 1L, spawnInitialNodes = false).apply {
            for (row in water) row.fill(false)
            for (row in highRises) row.fill(false)
            val lonely = addClient(Device.PHONE, 20, 10)
            addServer(Service.CALL, 22, 10)
            repeat(World.Tuning.MAX_PENDING) { lonely.pending.addLast(Service.CALL) }
        }
        HighscoreStore(app).submit(50, Scenarios.RIVER_TOWN.id)
        view.drawSnapshot(Canvas(bmp), metro, bmp.width, bmp.height, time = 0f)
        play(view, LOSE_SECONDS)
        assertEquals(Screen.GAME_OVER, view.currentScreen)
        assertEquals(50, HighscoreStore(app).best(Scenarios.RIVER_TOWN.id))
        assertEquals(0, HighscoreStore(app).best(Scenarios.METROPOLIS.id))
        draw(view)
        tap(view, MenuAction.PLAY_AGAIN)
        assertEquals("play again keeps the scenery", Scenarios.METROPOLIS, view.currentWorld.scenario)
    }

    private companion object {
        /** Long enough for an overload ring to close, even in the slower first weeks ([World.Tuning.EARLY_WEEKS]). */
        const val LOSE_SECONDS = World.Tuning.OVERLOAD_SECONDS * World.Tuning.EARLY_OVERLOAD_SLOWDOWN + 3f
    }
}
