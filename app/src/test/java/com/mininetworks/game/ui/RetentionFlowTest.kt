package com.mininetworks.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import com.mininetworks.game.R
import com.mininetworks.game.data.ProgressStore
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.Achievements
import com.mininetworks.game.game.CableSkin
import com.mininetworks.game.game.ColorTheme
import com.mininetworks.game.game.DailyChallenge
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.GameMode
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.PlayerStats
import com.mininetworks.game.game.Save
import com.mininetworks.game.game.Scenarios
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World
import com.mininetworks.game.render.Cosmetic
import com.mininetworks.game.ui.menu.AchievementsPanel
import com.mininetworks.game.ui.menu.MenuAction
import com.mininetworks.game.ui.menu.SceneryPicker
import com.mininetworks.game.ui.menu.Screen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Retention in the app (docs/TOP100.md C1, C2, C4, C5): the daily challenge from the main menu with its streak, the
 * achievements screen and unlock toasts, the three modes from the scenery picker, and cosmetics in the settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(DebugApi::class)
class RetentionFlowTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)

    @Before
    fun tutorialSeen() {
        SettingsStore(app).tutorialSeen = true
    }

    @After
    fun resetCosmetics() {
        Cosmetic.reset()
    }

    private fun draw(view: GameView) =
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 0f, screen = null)

    private fun tap(view: GameView, action: MenuAction) {
        val r = view.menuTarget(action) ?: throw AssertionError("$action is not tappable on ${view.currentScreen}")
        view.injectTouch(MotionEvent.ACTION_DOWN, r.centerX(), r.centerY())
        view.injectTouch(MotionEvent.ACTION_UP, r.centerX(), r.centerY())
        draw(view)
    }

    private fun tapScenery(view: GameView, id: String) {
        val r = view.sceneryTarget(id) ?: throw AssertionError("$id is not on ${view.currentScreen}")
        view.injectTouch(MotionEvent.ACTION_DOWN, r.centerX(), r.centerY())
        view.injectTouch(MotionEvent.ACTION_UP, r.centerX(), r.centerY())
        draw(view)
    }

    /** 2026-09-27 12:00 UTC. */
    private val noon = 1_790_467_200_000L + 12 * 3_600_000L

    private fun newView(clock: Long = noon) = GameView(app).also { it.wallClock = { clock }; draw(it) }

    /** Cables every client to the nearest server of a service it wants, with the best invented cable; money is granted. */
    private fun wire(w: World) {
        w.grant(500)
        val cable = w.unlockedCables.last()
        for (client in w.nodes.filter { it.kind == NodeKind.CLIENT && w.ports(it) == 0 }) {
            val server = w.nodes
                .filter { it.kind == NodeKind.SERVER && it.service in client.device!!.services && w.ports(it) < it.maxPorts }
                .minByOrNull { abs(it.cellX - client.cellX) + abs(it.cellY - client.cellY) } ?: continue
            w.connect(client, server, cable)
        }
    }

    /** Plays the running game for [seconds], wiring new clients every few seconds and taking the first reward. */
    private fun playWired(view: GameView, seconds: Int, until: () -> Boolean = { false }) {
        repeat(seconds * 60) { i ->
            val w = view.currentWorld
            if (i % 120 == 0) wire(w)
            w.rewardOffer?.let { w.chooseReward(0) }
            view.advance(1f / 60f)
            if (until()) return
        }
    }

    @Test
    fun dailyChallengeStartsTheSameGameForEveryoneAndCountsTheStreakOnce() {
        val view = newView()
        tap(view, MenuAction.DAILY)
        assertEquals(Screen.DAILY, view.currentScreen)
        val c = DailyChallenge.at(noon)
        val rule = Texts(app).rule(c.rule)
        assertTrue("the card names the rule of the day: ${view.menuLines}", view.menuLines.any { rule in it })
        tap(view, MenuAction.DAILY_START)
        assertEquals(Screen.PLAYING, view.currentScreen)
        val w = view.currentWorld
        assertEquals(c, w.daily)
        assertEquals(c.seed, w.seed)
        // A second player (another view, another device) gets the same map and start that day.
        val other = newView(noon + 5 * 3_600_000L)
        tap(other, MenuAction.DAILY)
        tap(other, MenuAction.DAILY_START)
        assertEquals(Save.encode(World(c.scenario, seed = c.seed, daily = c)), Save.encode(other.currentWorld))
        assertEquals(Save.encode(World(c.scenario, seed = c.seed, daily = c)), Save.encode(w))

        assertEquals(0, view.dailyStreak.current)
        playWired(view, 240) { view.currentWorld.delivered >= DailyChallenge.STREAK_PACKETS }
        assertTrue("delivered ${view.currentWorld.delivered}", view.currentWorld.delivered >= DailyChallenge.STREAK_PACKETS)
        view.advance(1f / 60f)
        assertEquals(1, view.dailyStreak.current)
        assertEquals(c.day, view.dailyStreak.lastDay)
        assertEquals("stored", view.dailyStreak, ProgressStore(app).streak)
        assertEquals(1, view.achievementStats.dailyDone)
        // Playing on does not count the day twice.
        playWired(view, 20)
        assertEquals(1, view.dailyStreak.current)
        assertEquals(1, view.achievementStats.dailyDone)
    }

    @Test
    fun dailyCardShowsTheStreakAndTheNextDayContinuesIt() {
        val store = ProgressStore(app)
        val day = DailyChallenge.dayOf(noon)
        store.streak = com.mininetworks.game.game.DailyStreak(day - 1, 4, 6)
        val view = newView()
        tap(view, MenuAction.DAILY)
        val streak = app.resources.getQuantityString(R.plurals.daily_streak, 4, 4, 6)
        assertEquals(streak, view.menuHighlight)
        // Two days later the streak is broken.
        val later = newView(noon + 2 * 86_400_000L)
        tap(later, MenuAction.DAILY)
        assertEquals(app.resources.getQuantityString(R.plurals.daily_streak, 0, 0, 6), later.menuHighlight)
        tap(later, MenuAction.BACK)
        assertEquals(Screen.MAIN_MENU, later.currentScreen)
    }

    @Test
    fun achievementsScreenListsEveryAchievementScrollsAndGoesBack() {
        val view = newView()
        view.setAchievementStats(PlayerStats(delivered = 150, cablesLaid = 40))
        tap(view, MenuAction.ACHIEVEMENTS)
        assertEquals(Screen.ACHIEVEMENTS, view.currentScreen)
        view.accessibilityLayer.forceActive = true
        draw(view)
        val tiles = view.accessibilityLayer.nodes.filter { it.key.startsWith("achievement:") && it.kind == UiNode.Kind.TEXT && it.key != "achievement:count" }
        assertEquals(Achievements.all.size, tiles.size)
        val reached = app.getString(R.string.ach_reached)
        assertTrue(tiles.single { it.key == "achievement:delivered_100" }.text.contains(reached))
        assertTrue(tiles.single { it.key == "achievement:cables_100" }.text.contains("40"))
        val count = view.accessibilityLayer.nodes.single { it.key == "achievement:count" }.text
        assertEquals(app.getString(R.string.achievements_count, 3, Achievements.all.size), count)

        // A vertical drag scrolls the grid.
        val last = Achievements.all.last().id
        val before = view.achievementTile(last)!!.top
        view.injectTouch(MotionEvent.ACTION_DOWN, 800f, 800f)
        view.injectTouch(MotionEvent.ACTION_MOVE, 800f, 500f)
        view.injectTouch(MotionEvent.ACTION_MOVE, 800f, 200f)
        view.injectTouch(MotionEvent.ACTION_UP, 800f, 200f)
        draw(view)
        assertTrue("scrolled: $before -> ${view.achievementTile(last)!!.top}", view.achievementTile(last)!!.top < before - 100f)
        assertEquals("a drag is not a tap", Screen.ACHIEVEMENTS, view.currentScreen)

        val back = view.achievementTarget(AchievementsPanel.BACK)!!
        view.injectTouch(MotionEvent.ACTION_DOWN, back.centerX(), back.centerY())
        view.injectTouch(MotionEvent.ACTION_UP, back.centerX(), back.centerY())
        assertEquals(Screen.MAIN_MENU, view.currentScreen)
    }

    @Test
    fun unlockingAnAchievementShowsAToastAndStoresTheStats() {
        val view = newView()
        tap(view, MenuAction.PLAY)
        tapScenery(view, Scenarios.RIVER_TOWN.id)
        assertEquals(setOf(Scenarios.RIVER_TOWN.id), view.achievementStats.sceneries)
        playWired(view, 60) { view.currentWorld.delivered >= 1 }
        view.advance(1f / 60f)
        val toast = view.shownToast
        assertNotNull(toast)
        assertEquals(app.getString(R.string.toast_achievement, app.getString(R.string.ach_delivered_1)), toast!!.title)
        assertTrue(view.achievementStats.delivered >= 1)
        assertEquals("stored right away", view.achievementStats, ProgressStore(app).loadStats())
        // It leaves again after a few seconds.
        repeat((AchievementToast.SECONDS * 60).toInt() + 30) { view.advance(1f / 60f); view.currentWorld.rewardOffer?.let { view.currentWorld.chooseReward(0) } }
        assertNotEquals(toast, view.shownToast)
    }

    @Test
    fun theModePillStartsEndlessAndCreativeGames() {
        val view = newView()
        tap(view, MenuAction.PLAY)
        assertEquals(GameMode.NORMAL, view.sceneryMode)
        tapScenery(view, SceneryPicker.MODE)
        assertEquals(GameMode.ENDLESS, view.sceneryMode)
        tapScenery(view, Scenarios.RIVER_TOWN.id)
        assertEquals(GameMode.ENDLESS, view.currentWorld.mode)

        // Nothing is cabled: in endless mode the rings fill, but the game goes on.
        repeat(60 * 150) {
            view.advance(1f / 60f)
            view.currentWorld.rewardOffer?.let { view.currentWorld.chooseReward(0) }
        }
        assertEquals(Screen.PLAYING, view.currentScreen)
        assertFalse(view.currentWorld.gameOver)
        assertTrue(view.currentWorld.nodes.any { it.overload >= 1f })

        view.back()
        view.advance(0f)
        draw(view)
        tap(view, MenuAction.MAIN_MENU)
        tap(view, MenuAction.PLAY)
        tapScenery(view, SceneryPicker.MODE)
        assertEquals(GameMode.CREATIVE, view.sceneryMode)
        tapScenery(view, Scenarios.RIVER_TOWN.id)
        val w = view.currentWorld
        assertEquals(GameMode.CREATIVE, w.mode)
        view.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 0f, screen = null)
        assertNotNull("radios can be placed without stock", view.hudTarget("radio:WLAN"))
        assertEquals(1, view.achievementStats.creativeGames)
    }

    @Test
    fun cosmeticsCycleOnlyThroughUnlockedOnesAndAreStored() {
        val view = newView()
        tap(view, MenuAction.SETTINGS)
        tap(view, MenuAction.CABLE_SKIN)
        assertEquals("nothing unlocked yet", CableSkin.CLASSIC, Cosmetic.skin)
        view.setAchievementStats(PlayerStats(cablesLaid = 100, fiberLaid = 25, bestWeek = 10))
        draw(view)
        tap(view, MenuAction.CABLE_SKIN)
        assertEquals(CableSkin.COPPER, Cosmetic.skin)
        tap(view, MenuAction.CABLE_SKIN)
        assertEquals(CableSkin.NEON, Cosmetic.skin)
        tap(view, MenuAction.CABLE_SKIN)
        assertEquals("wraps around", CableSkin.CLASSIC, Cosmetic.skin)
        tap(view, MenuAction.CABLE_SKIN)
        tap(view, MenuAction.COLOR_THEME)
        assertEquals(ColorTheme.AUTUMN, Cosmetic.theme)
        val stored = SettingsStore(app).load()
        assertEquals(CableSkin.COPPER, stored.cableSkin)
        assertEquals(ColorTheme.AUTUMN, stored.colorTheme)
        // A fresh start picks them up again.
        Cosmetic.reset()
        newView()
        assertEquals(CableSkin.COPPER, Cosmetic.skin)
        assertEquals(ColorTheme.AUTUMN, Cosmetic.theme)
    }

    @Test
    fun aStoredCosmeticThatIsNotUnlockedFallsBack() {
        SettingsStore(app).save(com.mininetworks.game.data.GameSettings(cableSkin = CableSkin.GOLD, colorTheme = ColorTheme.WINTER))
        newView()
        assertEquals(CableSkin.CLASSIC, Cosmetic.skin)
        assertEquals(ColorTheme.MEADOW, Cosmetic.theme)
    }

    @Test
    fun endlessBestIsKeptApartFromTheNormalBest() {
        val view = newView()
        tap(view, MenuAction.PLAY)
        tapScenery(view, SceneryPicker.MODE)
        tapScenery(view, Scenarios.RIVER_TOWN.id)
        playWired(view, 60) { view.currentWorld.delivered >= 5 }
        val delivered = view.currentWorld.delivered
        view.pause()
        val scores = com.mininetworks.game.data.HighscoreStore(app)
        assertEquals(delivered, scores.best("endless_${Scenarios.RIVER_TOWN.id}"))
        assertEquals("endless games never unlock sceneries", 0, scores.best(Scenarios.RIVER_TOWN.id))
        assertNull(view.shownSceneryHint)
    }
}
