package com.mininetworks.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Scenarios
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World
import com.mininetworks.game.monetization.AdPlacement
import com.mininetworks.game.monetization.AdPolicy
import com.mininetworks.game.monetization.FakeMonetization
import com.mininetworks.game.monetization.MonetizationStore
import com.mininetworks.game.ui.menu.MenuAction
import com.mininetworks.game.ui.menu.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/**
 * The ad rules of docs/PLAN.md 5.1 in the running app (docs/TOP100.md E1): no ad while a game runs, no interstitial
 * after the first [AdPolicy.FREE_GAMES] games, then at most every [AdPolicy.EVERY_GAMES]th game, and a rewarded video
 * only right after a tap on its own button. A seeded monkey plays many games with random taps and checks every ad
 * that shows; scripted checks tap everywhere except the rewarded buttons.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(DebugApi::class)
class AdRulesTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val bmp = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)

    // Native graphics: the legacy mode keeps a text log of every draw call, which a long monkey run cannot afford.
    private fun canvas() = Canvas(bmp)

    @Before
    fun tutorialSeen() {
        SettingsStore(app).tutorialSeen = true
    }

    private fun draw(view: GameView) =
        view.drawSnapshot(canvas(), view.currentWorld, bmp.width, bmp.height, time = 0f, screen = null)

    private fun install(view: GameView, w: World) =
        view.drawSnapshot(canvas(), w, bmp.width, bmp.height, time = 0f)

    private fun touch(view: GameView, x: Float, y: Float) {
        view.injectTouch(MotionEvent.ACTION_DOWN, x, y)
        view.injectTouch(MotionEvent.ACTION_UP, x, y)
        view.advance(0f)
    }

    /** True while the simulation of a game runs: on the play screen, not lost, no week reward screen open. */
    private fun running(view: GameView) = view.currentScreen == Screen.PLAYING &&
        !view.currentWorld.gameOver && view.currentWorld.rewardOffer == null

    private fun RectF?.has(x: Float, y: Float) = this != null && contains(x, y)

    @Test
    fun monkeyNeverSeesAnAdWhileAGameRunsAndInterstitialsFollowTheSchedule() {
        val shop = FakeMonetization()
        val view = GameView(app).also { it.monetization = shop }
        val rnd = Random(20260927)
        val interstitialGames = ArrayList<Int>()
        var interstitialAllowed = false
        var rewardedAllowed = false
        var rewardedShown = 0
        shop.onShow = { placement ->
            assertTrue("an ad showed while a game ran ($placement)", !running(view))
            if (placement == AdPlacement.INTERSTITIAL) {
                assertTrue("interstitial without leaving a game-over card", interstitialAllowed)
                assertEquals("interstitials only between games", Screen.GAME_OVER, view.currentScreen)
            } else {
                assertTrue("rewarded video without a tap on its button", rewardedAllowed)
                rewardedShown++
            }
        }

        fun closeAds() {
            shop.interstitials.forEach { it() }
            shop.interstitials.clear()
            shop.rewardeds.forEach { it(rnd.nextBoolean()) }
            shop.rewardeds.clear()
            view.advance(0f)
        }

        fun gamesFinished() = MonetizationStore(app).loadPolicy().gamesFinished

        /** A tap at (x, y); ads may only show if it hit their buttons in the frame the player saw. */
        fun tap(x: Float, y: Float) {
            draw(view)
            val over = view.currentScreen == Screen.GAME_OVER
            val secondChance = if (over) view.menuTarget(MenuAction.SECOND_CHANCE) else null
            val leave = if (over) listOfNotNull(view.menuTarget(MenuAction.PLAY_AGAIN), view.menuTarget(MenuAction.MAIN_MENU)) else emptyList()
            val bonus = view.bonusTarget()
            if (over && view.menuTarget(MenuAction.SHARE).has(x, y)) return // opens the system share sheet
            val before = shop.interstitials.size
            interstitialAllowed = leave.any { it.contains(x, y) }
            rewardedAllowed = secondChance.has(x, y) || bonus.has(x, y)
            touch(view, x, y)
            if (shop.interstitials.size > before) interstitialGames += gamesFinished()
            interstitialAllowed = false
            rewardedAllowed = false
            closeAds()
        }

        fun tapAction(action: MenuAction) {
            draw(view)
            val r = view.menuTarget(action) ?: return
            tap(r.centerX(), r.centerY())
        }

        install(view, World(Scenarios.RIVER_TOWN, seed = 1L))
        var steps = 0
        var gameOverTaps = 0
        var weekScreenSteps = 0
        var weekScreens = 0
        while (gamesFinished() < GAMES && steps++ < MAX_STEPS) {
            when (view.currentScreen) {
                Screen.PLAYING -> {
                    val w = view.currentWorld
                    if (w.rewardOffer != null) {
                        if (weekScreenSteps++ == 0) weekScreens++
                        // Taps anywhere, now and then on the bonus pill; the week choice closes the screen.
                        if (rnd.nextInt(4) == 0) view.bonusTarget()?.let { tap(it.centerX(), it.centerY()) }
                        else tap(rnd.nextFloat() * bmp.width, rnd.nextFloat() * bmp.height)
                        if (weekScreenSteps > 6 && w.rewardOffer != null) w.chooseReward(0)
                    } else {
                        weekScreenSteps = 0
                        val shown = shop.interstitials.size + rewardedShown
                        repeat(15) { view.advance(1f / 60f) }
                        assertEquals("no ad from the passing of time", shown, shop.interstitials.size + rewardedShown)
                        if (rnd.nextInt(3) == 0) tap(rnd.nextFloat() * bmp.width, rnd.nextFloat() * bmp.height)
                    }
                }
                Screen.GAME_OVER -> {
                    gameOverTaps++
                    when {
                        gameOverTaps > 6 -> tapAction(MenuAction.PLAY_AGAIN)
                        rnd.nextInt(4) == 0 -> tapAction(listOf(MenuAction.SECOND_CHANCE, MenuAction.PLAY_AGAIN, MenuAction.MAIN_MENU).random(rnd))
                        rnd.nextInt(6) == 0 -> {
                            val before = shop.interstitials.size
                            interstitialAllowed = true
                            view.back()
                            view.advance(0f)
                            if (shop.interstitials.size > before) interstitialGames += gamesFinished()
                            interstitialAllowed = false
                            closeAds()
                        }
                        else -> tap(rnd.nextFloat() * bmp.width, rnd.nextFloat() * bmp.height)
                    }
                    if (view.currentScreen != Screen.GAME_OVER) gameOverTaps = 0
                }
                Screen.PAUSED -> {
                    view.back()
                    view.advance(0f)
                }
                // Left through a menu: a new game as if picked from the main menu.
                else -> install(view, World(Scenarios.RIVER_TOWN, seed = steps.toLong()))
            }
        }

        println("AdRules monkey: steps=$steps games=${gamesFinished()} weekScreens=$weekScreens rewarded=$rewardedShown interstitials=$interstitialGames")
        assertTrue("played $GAMES games in $steps steps", gamesFinished() >= GAMES)
        assertTrue("the monkey met the week screen ($weekScreens)", weekScreens > 0)
        assertTrue("the monkey watched rewarded videos ($rewardedShown)", rewardedShown > 0)
        // Every interstitial showed while its game was counted; ads are always loaded, so the schedule is exact.
        assertEquals((AdPolicy.FREE_GAMES + 1..gamesFinished() step AdPolicy.EVERY_GAMES).toList(), interstitialGames)
        assertTrue(interstitialGames.first() > AdPolicy.FREE_GAMES)
        assertTrue(interstitialGames.zipWithNext().all { (a, b) -> b - a >= AdPolicy.EVERY_GAMES })
    }

    @Test
    fun rewardedVideosShowOnlyOnATapOnTheirOwnButton() {
        val shop = FakeMonetization()
        val view = GameView(app).also { it.monetization = shop }
        draw(view)

        // Game-over card: taps everywhere except its buttons show nothing, the "continue" button shows one video.
        lost(view)
        val buttons = listOf(MenuAction.PLAY_AGAIN, MenuAction.SECOND_CHANCE, MenuAction.SHARE, MenuAction.MAIN_MENU)
            .mapNotNull { view.menuTarget(it) }
        assertNotNull("a video is ready, so continue is offered", view.menuTarget(MenuAction.SECOND_CHANCE))
        grid { x, y ->
            if (buttons.none { it.contains(x, y) }) touch(view, x, y)
        }
        assertEquals(Screen.GAME_OVER, view.currentScreen)
        assertTrue(shop.rewardeds.isEmpty())
        assertTrue(shop.interstitials.isEmpty())

        val secondChance = view.menuTarget(MenuAction.SECOND_CHANCE)!!
        touch(view, secondChance.centerX(), secondChance.centerY())
        assertEquals(1, shop.rewardeds.size)
        shop.rewardeds.single().invoke(false)
        view.advance(0f)

        // Week screen: taps everywhere but the bonus pill never show a video (a card choice closes the screen).
        grid { x, y ->
            if (view.currentScreen != Screen.PLAYING || view.currentWorld.rewardOffer == null) weekScreen(view)
            draw(view)
            if (!view.bonusTarget().has(x, y)) touch(view, x, y)
        }
        assertEquals(1, shop.rewardeds.size)
        weekScreen(view)
        draw(view)
        val pill = view.bonusTarget()!!
        touch(view, pill.centerX(), pill.centerY())
        assertEquals(2, shop.rewardeds.size)
    }

    private fun grid(each: (Float, Float) -> Unit) {
        for (gx in 1 until 24) for (gy in 1 until 14) each(gx * bmp.width / 24f, gy * bmp.height / 14f)
    }

    /** A game at its game-over card, with a video ready. */
    private fun lost(view: GameView) {
        val w = World(Scenarios.RIVER_TOWN, seed = 1L, spawnInitialNodes = false).apply {
            incidentsEnabled = false
            for (row in water) row.fill(false)
            val lonely = addClient(Device.PHONE, 10, 8)
            addServer(Service.CALL, 20, 8)
            repeat(World.Tuning.MAX_PENDING) { lonely.pending.addLast(Service.CALL) }
        }
        install(view, w)
        repeat(((World.Tuning.OVERLOAD_SECONDS * World.Tuning.EARLY_OVERLOAD_SLOWDOWN + 3f) * 60).toInt()) { view.advance(1f / 60f) }
        draw(view)
        assertEquals(Screen.GAME_OVER, view.currentScreen)
    }

    /** A game at its week reward screen. */
    private fun weekScreen(view: GameView): World {
        val w = World(Scenarios.RIVER_TOWN, seed = 1L, spawnInitialNodes = false).apply {
            incidentsEnabled = false
            addServer(Service.MAIL, 12, 8)
            advanceToNextWeek()
        }
        install(view, w)
        return w
    }

    private companion object {
        const val GAMES = 11
        const val MAX_STEPS = 20_000
    }
}
