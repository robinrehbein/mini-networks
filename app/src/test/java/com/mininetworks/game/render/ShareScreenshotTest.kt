package com.mininetworks.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.DailyChallenge
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.Scenario
import com.mininetworks.game.game.Scenarios
import com.mininetworks.game.game.World
import com.mininetworks.game.games.FakeGameServices
import com.mininetworks.game.share.ShareCard
import com.mininetworks.game.ui.GameView
import com.mininetworks.game.ui.menu.MenuAction
import com.mininetworks.game.ui.menu.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * docs/TOP100.md C3, D2, rendered into docs/screenshots/share/: the share card of a played network (German, English,
 * a daily challenge, a big map), the game-over card with its "share" entry and the main menu with the leaderboards.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "de-xxhdpi")
@OptIn(DebugApi::class)
class ShareScreenshotTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val shots get() = File(System.getProperty("screenshots.dir") ?: "build/screenshots", "share").apply { mkdirs() }

    @Before
    fun tutorialSeen() {
        SettingsStore(app).tutorialSeen = true
    }

    private fun save(bmp: Bitmap, name: String) = File(shots, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

    /** A game of [s] played for [weeks] weeks: every client cabled to the nearest fitting server, week rewards taken. */
    private fun played(s: Scenario, weeks: Int, daily: DailyChallenge? = null): World {
        val w = World(s, seed = daily?.seed ?: 4L, daily = daily)
        w.incidentsEnabled = false
        w.grant(600, extraRouters = 4)
        val end = w.week + weeks
        while (w.week < end && !w.gameOver) {
            repeat(60 * 4) { w.rewardOffer?.let { w.chooseReward(0) }; w.update(1f / 60f) }
            val cable = w.unlockedCables.last()
            for (client in w.nodes.filter { it.kind == NodeKind.CLIENT && w.cables.none { c -> c.a === it || c.b === it } }) {
                val server = w.nodes
                    .filter { it.kind == NodeKind.SERVER && it.service in client.device!!.services && w.ports(it) < it.maxPorts }
                    .minByOrNull { abs(it.cellX - client.cellX) + abs(it.cellY - client.cellY) } ?: continue
                w.connect(client, server, cable)
            }
            if (w.budget < 200) w.grant(400)
            // Keep the network alive for the picture: a device about to overload gets its queue emptied.
            for (n in w.nodes) if (n.pending.size > World.Tuning.MAX_PENDING / 2) n.pending.clear()
        }
        check(!w.gameOver) { "the scripted game ended in week ${w.week}" }
        return w
    }

    @Test
    fun shareCards() {
        val river = played(Scenarios.RIVER_TOWN, weeks = 5)
        val card = ShareCard(app).render(river, time = 1.3f)
        assertEquals(ShareCard.WIDTH, card.width)
        assertEquals(ShareCard.HEIGHT, card.height)
        save(card, "share-card.png")

        val metropolis = played(Scenarios.METROPOLIS, weeks = 7)
        save(ShareCard(app).render(metropolis, time = 1.3f), "share-card-metropolis.png")

        val daily = DailyChallenge.at(20_358L * 24 * 60 * 60 * 1000)
        save(ShareCard(app).render(played(daily.scenario, weeks = 3, daily), time = 1.3f), "share-card-daily.png")
    }

    @Test
    @Config(qualifiers = "en-xxhdpi")
    fun shareCardInEnglish() {
        save(ShareCard(app).render(played(Scenarios.ISLAND, weeks = 5), time = 1.3f), "share-card-en.png")
    }

    @Test
    fun gameOverCardWithShareAndMenuWithLeaderboards() {
        val view = GameView(app)
        view.gameServices = FakeGameServices()
        val bmp = Bitmap.createBitmap(2400, 1080, Bitmap.Config.ARGB_8888)
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = null)
        assertNotNull(view.menuTarget(MenuAction.LEADERBOARDS))
        save(bmp, "menu-leaderboards.png")
        view.drawSnapshot(Canvas(bmp), played(Scenarios.RIVER_TOWN, weeks = 4), bmp.width, bmp.height, time = 1.3f, style = "Iso", screen = Screen.GAME_OVER)
        assertNotNull(view.menuTarget(MenuAction.SHARE))
        save(bmp, "game-over-share.png")
    }
}
