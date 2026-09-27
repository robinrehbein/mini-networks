package com.mininetworks.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import com.mininetworks.game.R
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.Scenarios
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.Unlock
import com.mininetworks.game.game.World
import com.mininetworks.game.monetization.AdPolicy
import com.mininetworks.game.monetization.MonetizationStore
import com.mininetworks.game.monetization.NoOpMonetization
import com.mininetworks.game.ui.menu.MenuAction
import com.mininetworks.game.ui.menu.Screen
import org.junit.Assert.assertEquals
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
 * Fully offline (docs/TOP100.md A8): with [NoOpMonetization] — what a device without network gets, since no ad
 * loads, the store answers nothing and nothing is owned — the whole loop still works: play, lose, play again, from
 * the fourth game on too (when an interstitial would be due), the main menu, sceneries unlocked by score. No screen
 * ever waits for an ad or a purchase, and the paid extras simply are not offered. The simulation itself (`:core`) has
 * no network code at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de")
@OptIn(DebugApi::class)
class OfflinePlayTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)

    @Before
    fun tutorialSeen() {
        SettingsStore(app).tutorialSeen = true
    }

    private fun draw(view: GameView) =
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 0f, screen = null)

    private fun tapAt(view: GameView, r: RectF) {
        view.injectTouch(MotionEvent.ACTION_DOWN, r.centerX(), r.centerY())
        view.injectTouch(MotionEvent.ACTION_UP, r.centerX(), r.centerY())
        draw(view)
    }

    private fun tap(view: GameView, action: MenuAction) =
        tapAt(view, view.menuTarget(action) ?: throw AssertionError("$action is not tappable on ${view.currentScreen}"))

    private fun play(view: GameView, seconds: Float) {
        repeat((seconds * 60).toInt()) { view.advance(1f / 60f) }
    }

    /** A game with one phone that cannot reach its server, played until its game-over card shows. */
    private fun lostGame(view: GameView) {
        val w = World(Scenarios.RIVER_TOWN, seed = 1L, spawnInitialNodes = false).apply {
            incidentsEnabled = false
            for (row in water) row.fill(false)
            val lonely = addClient(Device.PHONE, 10, 8)
            addServer(Service.CALL, 20, 8)
            repeat(World.Tuning.MAX_PENDING) { lonely.pending.addLast(Service.CALL) }
        }
        view.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 0f)
        play(view, LOSE_SECONDS)
        assertEquals(Screen.GAME_OVER, view.currentScreen)
        draw(view)
    }

    @Test
    fun theDefaultIsTheOfflineNoOp() {
        assertSame("debug builds and tests start without any store or ad SDK", NoOpMonetization, GameView(app).monetization)
    }

    @Test
    fun aRealGameDeliversPacketsWithoutNetwork() {
        val view = GameView(app)
        draw(view)
        tap(view, MenuAction.PLAY)
        tapAt(view, view.sceneryTarget(Scenarios.RIVER_TOWN.id)!!)
        assertEquals(Screen.PLAYING, view.currentScreen)
        val w = view.currentWorld
        w.grant(500)
        // Wire every client straight to a server it needs, as a player would with cables.
        for (c in w.nodes.filter { it.kind == NodeKind.CLIENT }) {
            val s = w.nodes.firstOrNull { it.kind == NodeKind.SERVER && it.service in c.device!!.services } ?: continue
            w.connect(c, s, CableType.ISDN)
        }
        play(view, 30f)
        assertTrue("packets delivered: ${w.delivered}", w.delivered > 0)
        assertNull(view.awaitedAd)
    }

    @Test
    fun manyGamesInARowNeverWaitForAnAdOrTheStore() {
        MonetizationStore(app).savePolicy(AdPolicy(gamesFinished = AdPolicy.FREE_GAMES))
        val view = GameView(app)
        draw(view)
        for (game in 1..6) {
            lostGame(view)
            assertTrue("no ad-backed second chance offline", view.menuTarget(MenuAction.SECOND_CHANCE) == null)
            if (game % 2 == 0) {
                tap(view, MenuAction.MAIN_MENU)
                assertEquals("interstitial due but none loaded: straight on", Screen.MAIN_MENU, view.currentScreen)
                assertNull("no remove-ads offer without a store price", view.menuTarget(MenuAction.REMOVE_ADS))
            } else {
                tap(view, MenuAction.PLAY_AGAIN)
                assertEquals(Screen.PLAYING, view.currentScreen)
            }
            assertNull(view.awaitedAd)
        }
        assertEquals(AdPolicy.FREE_GAMES + 6, MonetizationStore(app).loadPolicy().gamesFinished)
    }

    @Test
    fun aLockedPaidSceneryExplainsInsteadOfHanging() {
        val view = GameView(app)
        draw(view)
        tap(view, MenuAction.PLAY)
        val paid = Scenarios.all.first { it.unlock == Unlock.Purchase }
        tapAt(view, view.sceneryTarget(paid.id)!!)
        assertEquals(Screen.SCENERIES, view.currentScreen)
        assertEquals("says the shop cannot be reached", app.getString(R.string.scenery_hint_no_shop), view.shownSceneryHint)
        assertNull(view.awaitedAd)
        tapAt(view, view.sceneryTarget(Scenarios.RIVER_TOWN.id)!!)
        assertEquals("the free scenery plays offline", Screen.PLAYING, view.currentScreen)
    }

    private companion object {
        const val LOSE_SECONDS = World.Tuning.OVERLOAD_SECONDS * World.Tuning.EARLY_OVERLOAD_SLOWDOWN + 3f
    }
}
