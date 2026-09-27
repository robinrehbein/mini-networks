package com.mininetworks.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Rewards
import com.mininetworks.game.game.Scenarios
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World
import com.mininetworks.game.monetization.AdPlacement
import com.mininetworks.game.monetization.AdPolicy
import com.mininetworks.game.monetization.Entitlements
import com.mininetworks.game.monetization.FakeMonetization
import com.mininetworks.game.monetization.MonetizationStore
import com.mininetworks.game.ui.menu.MenuAction
import com.mininetworks.game.ui.menu.SceneryPicker
import com.mininetworks.game.ui.menu.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** Ads and purchases in the app (docs/PLAN.md 5.1), with a [FakeMonetization] that the test closes by hand. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(DebugApi::class)
class MonetizationFlowTest {

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

    private fun newView(shop: FakeMonetization) = GameView(app).also {
        it.monetization = shop
        draw(it)
    }

    /** A river town game with one phone that cannot reach its server, played until its game-over card shows. */
    private fun lostGame(view: GameView): World {
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
        return w
    }

    /** A game at its week reward screen. */
    private fun rewardScreen(view: GameView): World {
        val w = World(Scenarios.RIVER_TOWN, seed = 1L, spawnInitialNodes = false).apply {
            incidentsEnabled = false
            addServer(Service.MAIL, 12, 8)
            advanceToNextWeek()
        }
        assertNotNull(w.rewardOffer)
        view.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 0f)
        return w
    }

    @Test
    fun interstitialOnlyBetweenGamesFromTheFourthGameOnAndAtMostEveryThird() {
        val shop = FakeMonetization()
        val view = newView(shop)
        val shownAfter = ArrayList<Int>()
        for (game in 1..7) {
            lostGame(view)
            assertEquals("no ad during play or on the game-over card", shownAfter.size, shop.interstitials.size)
            val lost = view.currentWorld
            tap(view, MenuAction.PLAY_AGAIN)
            if (shop.interstitials.size > shownAfter.size) {
                shownAfter += game
                assertEquals("the next game waits for the ad", Screen.GAME_OVER, view.currentScreen)
                assertEquals(AdPlacement.INTERSTITIAL, view.awaitedAd)
                assertSame(lost, view.currentWorld)
                tap(view, MenuAction.MAIN_MENU)
                assertEquals("taps wait while the ad is open", Screen.GAME_OVER, view.currentScreen)
                val close = shop.interstitials[shop.interstitials.size - 1]
                close()
                view.advance(0f)
                assertNull(view.awaitedAd)
            }
            assertEquals(Screen.PLAYING, view.currentScreen)
            assertNotSame(lost, view.currentWorld)
        }
        assertEquals(listOf(4, 7), shownAfter)
        val stored = MonetizationStore(app).loadPolicy()
        assertEquals(7, stored.gamesFinished)
        assertEquals(7, stored.lastInterstitialGame)
    }

    @Test
    fun theMainMenuWaitsForTheInterstitialToo() {
        MonetizationStore(app).savePolicy(AdPolicy(gamesFinished = AdPolicy.FREE_GAMES))
        val shop = FakeMonetization()
        val view = newView(shop)
        lostGame(view)
        view.back()
        view.advance(0f)
        assertEquals(1, shop.interstitials.size)
        assertEquals(Screen.GAME_OVER, view.currentScreen)
        val close = shop.interstitials[0]
        close()
        view.advance(0f)
        assertEquals(Screen.MAIN_MENU, view.currentScreen)
    }

    @Test
    fun noInterstitialWithoutALoadedAdOrWithAdsRemoved() {
        MonetizationStore(app).savePolicy(AdPolicy(gamesFinished = 10))
        val empty = FakeMonetization(interstitialLoaded = false)
        val view = newView(empty)
        lostGame(view)
        tap(view, MenuAction.PLAY_AGAIN)
        assertEquals(Screen.PLAYING, view.currentScreen)

        val paid = FakeMonetization(owned = mutableSetOf(Entitlements.REMOVE_ADS))
        view.monetization = paid
        repeat(4) {
            lostGame(view)
            tap(view, MenuAction.PLAY_AGAIN)
            assertEquals(Screen.PLAYING, view.currentScreen)
        }
        assertTrue(paid.interstitials.isEmpty())
    }

    @Test
    fun aMissedAdCallbackDoesNotLockTheGame() {
        MonetizationStore(app).savePolicy(AdPolicy(gamesFinished = AdPolicy.FREE_GAMES, lastInterstitialGame = 0))
        val shop = FakeMonetization()
        val view = newView(shop)
        lostGame(view)
        tap(view, MenuAction.SECOND_CHANCE)
        assertEquals(AdPlacement.CONTINUE, view.awaitedAd)
        view.pause()
        view.resume()
        view.pause()
        play(view, 3f)
        assertEquals("the result may still come", AdPlacement.CONTINUE, view.awaitedAd)
        play(view, 4f)
        assertNull("no callback: given up without the reward", view.awaitedAd)
        assertEquals(Screen.GAME_OVER, view.currentScreen)
        assertTrue(view.currentWorld.gameOver)

        tap(view, MenuAction.PLAY_AGAIN)
        assertEquals(AdPlacement.INTERSTITIAL, view.awaitedAd)
        play(view, 7f)
        assertNull(view.awaitedAd)
        assertEquals("the new game starts as if the ad had closed", Screen.PLAYING, view.currentScreen)
    }

    @Test
    fun rewardedVideoContinuesOnceAfterGameOver() {
        val shop = FakeMonetization()
        val view = newView(shop)
        val w = lostGame(view)
        tap(view, MenuAction.SECOND_CHANCE)
        assertEquals(1, shop.rewardeds.size)
        assertEquals(AdPlacement.CONTINUE, view.awaitedAd)
        assertEquals(Screen.GAME_OVER, view.currentScreen)

        view.adClosed(AdPlacement.CONTINUE, earned = true)
        view.advance(0f)
        assertEquals(Screen.PLAYING, view.currentScreen)
        assertSame("the same game goes on", w, view.currentWorld)
        assertFalse(w.gameOver)
        assertTrue(w.continued)
        assertTrue(w.nodes.all { it.overload < 0.01f })

        var s = 0
        while (view.currentScreen != Screen.GAME_OVER && s++ < 60 * 120) {
            if (w.rewardOffer != null) w.chooseReward(0)
            view.advance(1f / 60f)
        }
        assertEquals(Screen.GAME_OVER, view.currentScreen)
        draw(view)
        assertNull("only once per game", view.menuTarget(MenuAction.SECOND_CHANCE))
        tap(view, MenuAction.PLAY_AGAIN)
        assertEquals("the continued game counts once", 1, MonetizationStore(app).loadPolicy().gamesFinished)
    }

    @Test
    fun anUnfinishedVideoGivesNothing() {
        val shop = FakeMonetization()
        val view = newView(shop)
        val w = lostGame(view)
        tap(view, MenuAction.SECOND_CHANCE)
        shop.rewardeds.single().invoke(false)
        view.advance(0f)
        draw(view)
        assertEquals(Screen.GAME_OVER, view.currentScreen)
        assertTrue(w.gameOver)
        assertTrue(w.canContinue)
        assertNotNull("can try again", view.menuTarget(MenuAction.SECOND_CHANCE))
    }

    @Test
    fun continueNeedsALoadedVideoOrNoAds() {
        val none = FakeMonetization(rewardedLoaded = false)
        val view = newView(none)
        lostGame(view)
        assertNull(view.menuTarget(MenuAction.SECOND_CHANCE))

        val paid = FakeMonetization(owned = mutableSetOf(Entitlements.REMOVE_ADS), rewardedLoaded = false)
        view.monetization = paid
        val w = lostGame(view)
        tap(view, MenuAction.SECOND_CHANCE)
        assertTrue("no video once ads are removed", paid.rewardeds.isEmpty())
        assertEquals(Screen.PLAYING, view.currentScreen)
        assertTrue(w.continued)
    }

    @Test
    fun bonusRouterOnTheWeekScreen() {
        val shop = FakeMonetization()
        val view = newView(shop)
        val w = rewardScreen(view)
        val routers = w.routersAvailable
        val pill = view.bonusTarget() ?: throw AssertionError("no bonus pill")
        tapAt(view, pill)
        assertEquals(AdPlacement.BONUS_ROUTER, view.awaitedAd)
        assertEquals(1, shop.rewardeds.size)

        view.pause()
        assertEquals("an open ad does not open the pause menu", Screen.PLAYING, view.currentScreen)
        shop.rewardeds.single().invoke(true)
        view.advance(0f)
        draw(view)
        assertEquals(routers + Rewards.BONUS_ROUTERS, w.routersAvailable)
        assertNotNull("the week choice stays open", w.rewardOffer)
        assertNull("once per week", view.bonusTarget())
    }

    @Test
    fun bonusRouterWithoutVideoOnceAdsAreRemovedAndHiddenWithoutAVideo() {
        val none = FakeMonetization(rewardedLoaded = false)
        val view = newView(none)
        rewardScreen(view)
        assertNull(view.bonusTarget())

        val paid = FakeMonetization(owned = mutableSetOf(Entitlements.REMOVE_ADS))
        view.monetization = paid
        val w = rewardScreen(view)
        val routers = w.routersAvailable
        tapAt(view, view.bonusTarget()!!)
        assertTrue(paid.rewardeds.isEmpty())
        assertEquals(routers + Rewards.BONUS_ROUTERS, w.routersAvailable)
    }

    @Test
    fun storeEntriesAppearOnlyWhileSold() {
        val noShop = newView(FakeMonetization())
        assertNull(noShop.menuTarget(MenuAction.REMOVE_ADS))
        tap(noShop, MenuAction.SETTINGS)
        assertNull(noShop.menuTarget(MenuAction.PRIVACY))

        val shop = FakeMonetization(
            prices = mapOf(Entitlements.REMOVE_ADS to "2,99 €", Entitlements.SCENERY_PACK to "4,99 €"),
            privacyOptionsRequired = true,
        )
        val view = newView(shop)
        tap(view, MenuAction.REMOVE_ADS)
        assertEquals(listOf(Entitlements.REMOVE_ADS), shop.purchases)
        tap(view, MenuAction.SETTINGS)
        tap(view, MenuAction.PRIVACY)
        assertEquals(1, shop.privacyShown)
        tap(view, MenuAction.BACK)
        tap(view, MenuAction.PLAY)
        tapAt(view, view.sceneryTarget(SceneryPicker.PACK)!!)
        assertEquals(listOf(Entitlements.REMOVE_ADS, Entitlements.SCENERY_PACK), shop.purchases)

        // Bought: the entries disappear and every scenery is playable.
        shop.owned += listOf(Entitlements.REMOVE_ADS, Entitlements.SCENERY_PACK)
        draw(view)
        assertNull(view.sceneryTarget(SceneryPicker.PACK))
        tapAt(view, view.sceneryTarget(Scenarios.FUTURE.id)!!)
        assertEquals(Screen.PLAYING, view.currentScreen)
        assertEquals(Scenarios.FUTURE, view.currentWorld.scenario)
        view.back()
        view.advance(0f)
        draw(view)
        tap(view, MenuAction.MAIN_MENU)
        assertNull(view.menuTarget(MenuAction.REMOVE_ADS))
    }

    private companion object {
        /** Long enough for an overload ring to close, even in the slower first weeks ([World.Tuning.EARLY_WEEKS]). */
        const val LOSE_SECONDS = World.Tuning.OVERLOAD_SECONDS * World.Tuning.EARLY_OVERLOAD_SLOWDOWN + 3f
    }
}
