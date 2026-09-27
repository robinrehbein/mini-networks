package com.mininetworks.game.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.MotionEvent
import com.mininetworks.game.R
import com.mininetworks.game.data.GameIo
import com.mininetworks.game.data.HighscoreStore
import com.mininetworks.game.data.ProgressStore
import com.mininetworks.game.data.ReviewStore
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.Achievements
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.CloudProgress
import com.mininetworks.game.game.DailyChallenge
import com.mininetworks.game.game.DailyStreak
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.GameMode
import com.mininetworks.game.game.LeaderboardScore
import com.mininetworks.game.game.Leaderboards
import com.mininetworks.game.game.PlayerStats
import com.mininetworks.game.game.ReviewState
import com.mininetworks.game.game.Scenario
import com.mininetworks.game.game.Scenarios
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World
import com.mininetworks.game.games.FakeGameServices
import com.mininetworks.game.games.GamesIds
import com.mininetworks.game.review.ReviewPrompt
import com.mininetworks.game.share.ShareCard
import com.mininetworks.game.share.ShareSheet
import com.mininetworks.game.ui.menu.MenuAction
import com.mininetworks.game.ui.menu.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Play Games, rating and sharing in the app (docs/TOP100.md C2, C3, C6, D1, D2), with [FakeGameServices] and a counting
 * [ReviewPrompt] in place of the Google SDKs: the placeholder ids, achievement sync, leaderboard scores, the cloud save
 * merge, when the rating is asked for, and the share card through the FileProvider.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
@OptIn(DebugApi::class)
class PlayServicesFlowTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)

    @Before
    fun tutorialSeen() {
        SettingsStore(app).tutorialSeen = true
    }

    private fun draw(view: GameView) =
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 0f, screen = null)

    private fun tap(view: GameView, action: MenuAction) {
        val r = view.menuTarget(action) ?: throw AssertionError("$action is not tappable on ${view.currentScreen}")
        view.injectTouch(MotionEvent.ACTION_DOWN, r.centerX(), r.centerY())
        view.injectTouch(MotionEvent.ACTION_UP, r.centerX(), r.centerY())
        draw(view)
    }

    private fun newView(games: FakeGameServices? = null) = GameView(app).also { v ->
        v.wallClock = { NOON }
        games?.let { v.gameServices = it }
        draw(v)
    }

    /**
     * A game of [scenario] (or the [daily] challenge) that delivered [packets] in week [week] and then overloads: a
     * phone far from its server with a full queue.
     */
    private fun doomedGame(packets: Int, week: Int = 4, scenario: Scenario = Scenarios.RIVER_TOWN, daily: DailyChallenge? = null): World {
        val w = World(scenario, cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false, daily = daily)
        w.incidentsEnabled = false
        w.grant(50)
        w.jumpToWeek(week)
        val mail = w.addServer(Service.MAIL, 1, 1)
        val pc = w.addClient(Device.PC, 3, 1)
        w.connect(pc, mail, CableType.ISDN)
        while (w.delivered < packets) {
            if (pc.pending.isEmpty()) pc.pending.addLast(Service.MAIL)
            w.update(1f / 60f)
        }
        w.removeCable(w.cables.single())
        val lonely = w.addClient(Device.PHONE, 3, 6)
        w.addServer(Service.CALL, 5, 6)
        repeat(World.Tuning.MAX_PENDING) { lonely.pending.addLast(Service.CALL) }
        return w
    }

    /** Plays [w] until its game-over card shows (the camera's glide to the failed device included). */
    private fun loseGame(view: GameView, w: World) {
        view.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 0f)
        var guard = 0
        while (view.currentScreen != Screen.GAME_OVER) {
            step(view)
            check(guard++ < 60 * 120) { "no game-over card within two minutes" }
        }
        draw(view)
    }

    /** One frame; a week reward on the way is taken, so the game runs on. */
    private fun step(view: GameView) {
        view.currentWorld.rewardOffer?.let { view.currentWorld.chooseReward(0) }
        view.advance(1f / 60f)
    }

    // ---------------------------------------------------------------- ids and menu (C2, C3)

    @Test
    fun gamesIdsCoverEveryAchievementAndLeaderboardAndAreOnlyPlaceholders() {
        assertEquals(Achievements.all.map { it.id }.toSet(), GamesIds.ACHIEVEMENTS.keys)
        assertEquals(Leaderboards.all.toSet(), GamesIds.LEADERBOARDS.keys)
        val ids = GamesIds(app.resources)
        // The repo holds no real ids: Play Games stays off, every single id is skipped.
        assertFalse(ids.configured)
        assertTrue(ids.appId.startsWith(GamesIds.PLACEHOLDER_PREFIX))
        for (id in GamesIds.ACHIEVEMENTS.keys) assertNull(id, ids.achievement(id))
        for (key in GamesIds.LEADERBOARDS.keys) assertNull(key, ids.leaderboard(key))
        val values = (GamesIds.ACHIEVEMENTS.values + GamesIds.LEADERBOARDS.values).map(app::getString)
        assertEquals("every id is a distinct placeholder", values.size, values.toSet().size)
        assertTrue(values.all { it.startsWith(GamesIds.PLACEHOLDER_PREFIX) })
    }

    @Test
    fun leaderboardsAreOnlyInTheMenuWithPlayGamesAndSignInFirst() {
        val plain = newView()
        assertNull("no Play Games (debug build, placeholders): no entry", plain.menuTarget(MenuAction.LEADERBOARDS))
        val games = FakeGameServices()
        val view = newView(games)
        tap(view, MenuAction.LEADERBOARDS)
        assertEquals("signed out: the sign-in comes first", 1, games.signInRequests)
        assertEquals(1, games.leaderboardsShown)
        tap(view, MenuAction.LEADERBOARDS)
        assertEquals(1, games.signInRequests)
        assertEquals(2, games.leaderboardsShown)
    }

    // ---------------------------------------------------------------- achievements (C2)

    @Test
    fun signingInSendsEveryReachedAchievementAndThenOnlyNewOnes() {
        val games = FakeGameServices()
        val view = newView(games)
        view.setAchievementStats(PlayerStats(delivered = 150, cablesLaid = 12))
        view.advance(0f)
        assertTrue("nothing is sent while signed out", games.unlocked.isEmpty())
        games.signInNow()
        view.advance(0f)
        assertEquals(listOf("delivered_1", "delivered_100", "cables_10"), games.unlocked)

        loseGame(view, doomedGame(3))
        assertEquals("the first finished game: only the new achievement follows", listOf("delivered_1", "delivered_100", "cables_10", "games_1"), games.unlocked)

        // A new sign-in (e.g. another account) gets everything reached once more; Play ignores repeats.
        games.onSignedIn?.invoke()
        view.advance(0f)
        assertEquals(8, games.unlocked.size)
    }

    // ---------------------------------------------------------------- leaderboards (C3)

    @Test
    fun gameOverSubmitsToTheSceneryBoardAndTheDailyBoardOnItsDay() {
        val games = FakeGameServices().apply { signInNow() }
        val view = newView(games)
        loseGame(view, doomedGame(4, scenario = Scenarios.RIVER_TOWN))
        assertEquals(LeaderboardScore(Leaderboards.scenery(Scenarios.RIVER_TOWN.id), 4), games.scores.last())

        val c = DailyChallenge.at(NOON)
        loseGame(view, doomedGame(5, scenario = c.scenario, daily = c))
        assertEquals(LeaderboardScore(Leaderboards.DAILY, 5, Leaderboards.dayTag(c.day)), games.scores.last())

        // Past its UTC day the daily run submits nothing (C1 rule).
        view.wallClock = { NOON + DAY }
        loseGame(view, doomedGame(6, scenario = c.scenario, daily = c))
        assertEquals(2, games.scores.size)

        // Signed out: no score, and the game goes on as before.
        val offline = FakeGameServices()
        val other = newView(offline)
        loseGame(other, doomedGame(4))
        assertTrue(offline.scores.isEmpty())
        assertEquals(Screen.GAME_OVER, other.currentScreen)
    }

    // ---------------------------------------------------------------- cloud save (C6)

    @Test
    fun theCloudSaveIsMergedIntoTheLocalStoresAndTheMergeGoesBack() {
        HighscoreStore(app).submit(50, Scenarios.RIVER_TOWN.id)
        val progress = ProgressStore(app)
        progress.streak = DailyStreak(20_300, 2, 2)
        val remote = CloudProgress(
            savedAt = 5,
            stats = PlayerStats(delivered = 900, bestGame = 320, sceneries = setOf(Scenarios.METROPOLIS.id)),
            best = mapOf(Scenarios.RIVER_TOWN.id to 320, Scenarios.METROPOLIS.id to 140),
            streakDay = 20_350, streakCurrent = 3, streakBest = 5,
            dailyDay = 20_350, dailyBest = 44,
        )
        val games = FakeGameServices(cloud = remote)
        val view = newView(games)
        view.setAchievementStats(PlayerStats(delivered = 60, cablesLaid = 12))
        games.signInNow()
        view.advance(0f)

        // The local stores now hold the most progress of both devices.
        val scores = HighscoreStore(app)
        assertEquals(320, scores.best(Scenarios.RIVER_TOWN.id))
        assertEquals(140, scores.best(Scenarios.METROPOLIS.id))
        val stats = ProgressStore(app).loadStats()
        assertEquals(900L, stats.delivered)
        assertEquals(12, stats.cablesLaid)
        assertEquals(setOf(Scenarios.METROPOLIS.id), stats.sceneries)
        assertEquals(DailyStreak(20_350, 3, 5), ProgressStore(app).streak)
        assertEquals(44, ProgressStore(app).dailyBest(20_350))
        // Achievements reached on the other device are on Play Games too, and this device's cables went up.
        assertTrue(games.unlocked.containsAll(listOf("delivered_100", "game_300", "cables_10")))
        val up = games.saved.single()
        assertEquals(12, up.stats.cablesLaid)
        assertEquals(900L, up.stats.delivered)

        // A second load of the same cloud save changes nothing and writes nothing.
        games.onSignedIn?.invoke()
        view.advance(0f)
        assertEquals(1, games.saved.size)
    }

    @Test
    fun withoutACloudSaveTheLocalProgressGoesUpAndEveryGameOverUpdatesIt() {
        HighscoreStore(app).submit(77, Scenarios.RIVER_TOWN.id)
        val games = FakeGameServices()
        val view = newView(games)
        games.signInNow()
        view.advance(0f)
        assertEquals(mapOf(Scenarios.RIVER_TOWN.id to 77), games.cloud!!.best)
        loseGame(view, doomedGame(3, scenario = Scenarios.METROPOLIS))
        assertEquals(2, games.saved.size)
        assertEquals(3, games.cloud!!.best[Scenarios.METROPOLIS.id])
        assertEquals(1, games.cloud!!.stats.gamesFinished)
    }

    @Test
    fun aNewerCloudFormatIsMergedInButNeverOverwritten() {
        val games = FakeGameServices(cloud = CloudProgress(version = CloudProgress.VERSION + 1, best = mapOf("island_harbor" to 9)))
        val view = newView(games)
        games.signInNow()
        view.advance(0f)
        assertEquals(9, HighscoreStore(app).best("island_harbor"))
        assertTrue(games.saved.isEmpty())
    }

    // ---------------------------------------------------------------- rating (D1)

    private class CountingPrompt : ReviewPrompt {
        var requests = 0
        override fun request() {
            requests++
        }
    }

    @Test
    fun theRatingIsAskedWithTheThirdGameOverCardAndThenPausesForThirtyDays() {
        val prompt = CountingPrompt()
        val view = newView().also { it.reviewPrompt = prompt }
        loseGame(view, doomedGame(3))
        loseGame(view, doomedGame(3))
        assertEquals(0, prompt.requests)
        view.drawSnapshot(Canvas(bmp), doomedGame(3), bmp.width, bmp.height, time = 0f)
        // Not while the camera glides to the failed device: only with the result card.
        while (!view.currentWorld.gameOver) step(view)
        assertEquals(0, prompt.requests)
        while (view.currentScreen != Screen.GAME_OVER) step(view)
        assertEquals(1, prompt.requests)
        assertEquals(ReviewState(3, NOON), ReviewStore(app).state)
        // A new record 10 days later: still within the 30 days.
        view.wallClock = { NOON + 10 * DAY }
        loseGame(view, doomedGame(5))
        assertEquals(1, prompt.requests)
        // 31 days later a record asks again.
        view.wallClock = { NOON + 31 * DAY }
        loseGame(view, doomedGame(7))
        assertEquals(2, prompt.requests)
    }

    @Test
    fun neverAfterAQuickLossNorInEndlessOrCreative() {
        val prompt = CountingPrompt()
        val view = newView().also { it.reviewPrompt = prompt }
        ReviewStore(app).state = ReviewState(finishedGames = 2)
        loseGame(view, doomedGame(3, week = 1))
        assertEquals("lost in the first week: frustrating", 0, prompt.requests)
        assertEquals(3, ReviewStore(app).state.finishedGames)
        loseGame(view, doomedGame(3))
        assertEquals("the next good game asks", 1, prompt.requests)
        // Endless and creative never end, so they never ask; the tutorial is no game.
        val endless = World(Scenarios.RIVER_TOWN, cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false, mode = GameMode.ENDLESS)
        view.drawSnapshot(Canvas(bmp), endless, bmp.width, bmp.height, time = 0f)
        repeat(60 * 30) { view.advance(1f / 60f) }
        assertEquals(1, prompt.requests)
    }

    // ---------------------------------------------------------------- sharing (D2)

    @Test
    fun shareOpensTheShareSheetWithTheCardAndItsText() {
        val view = newView()
        var shared: Pair<File, String>? = null
        view.onShare = { file, text -> shared = file to text }
        loseGame(view, doomedGame(4))
        tap(view, MenuAction.SHARE)
        GameIo.awaitIdle()
        shadowOf(Looper.getMainLooper()).idle()
        val (file, text) = shared ?: throw AssertionError("the share sheet was not asked for")
        assertEquals(File(File(app.cacheDir, ShareSheet.DIR), ShareSheet.FILE), file)
        val card = BitmapFactory.decodeFile(file.path)
        assertEquals(ShareCard.WIDTH, card.width)
        assertEquals(ShareCard.HEIGHT, card.height)
        assertEquals(app.resources.getQuantityString(R.plurals.share_text, 4, Texts(app).scenario(Scenarios.RIVER_TOWN), "4", view.currentWorld.year), text)
        assertEquals("the game-over card stays", Screen.GAME_OVER, view.currentScreen)
    }

    @Test
    fun theShareIntentHandsOutAContentUriWithAReadGrant() {
        val file = ShareSheet.write(app.cacheDir, Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
        val intent = ShareSheet.intent(app, file, "hallo")
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/png", intent.type)
        assertEquals("hallo", intent.getStringExtra(Intent.EXTRA_TEXT))
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals("${app.packageName}.fileprovider", uri.authority)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        // The provider serves exactly that file.
        app.contentResolver.openInputStream(uri)!!.use { assertEquals(file.length(), it.readBytes().size.toLong()) }
        val chooser = ShareSheet.chooser(app, file, "hallo")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        assertEquals(Intent.ACTION_SEND, chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!.action)
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
        /** 2026-09-27 12:00 UTC. */
        const val NOON = 20_358L * DAY + DAY / 2
    }
}
