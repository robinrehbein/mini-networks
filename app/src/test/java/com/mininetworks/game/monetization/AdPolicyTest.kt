package com.mininetworks.game.monetization

import com.mininetworks.game.game.Scenarios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The interstitial frequency of docs/PLAN.md 5.1 and the product ids, as plain JVM tests. */
class AdPolicyTest {

    /** Plays [games] games and returns after which ones an interstitial showed (1-based). */
    private fun adsAfter(games: Int, policy: AdPolicy = AdPolicy(), loaded: (Int) -> Boolean = { true }): List<Int> {
        val shown = ArrayList<Int>()
        repeat(games) {
            policy.gameFinished()
            if (policy.interstitialDue(adsRemoved = false) && loaded(policy.gamesFinished)) {
                policy.interstitialShown()
                shown += policy.gamesFinished
            }
        }
        return shown
    }

    @Test
    fun noInterstitialInTheFirstThreeGames() {
        val p = AdPolicy()
        repeat(AdPolicy.FREE_GAMES) {
            p.gameFinished()
            assertFalse("game ${p.gamesFinished}", p.interstitialDue(adsRemoved = false))
        }
        p.gameFinished()
        assertTrue(p.interstitialDue(adsRemoved = false))
    }

    @Test
    fun atMostEveryThirdGame() {
        assertEquals(listOf(4, 7, 10, 13), adsAfter(13))
        val shown = adsAfter(100)
        assertTrue(shown.zipWithNext().all { (a, b) -> b - a >= AdPolicy.EVERY_GAMES })
    }

    @Test
    fun aMissedAdMovesTheNextOneOnly() {
        // No ad was loaded after game 4: it comes after game 5, and the next one three games later.
        assertEquals(listOf(5, 8, 11), adsAfter(11) { it != 4 })
    }

    @Test
    fun removedAdsMeanNoInterstitials() {
        val p = AdPolicy()
        repeat(20) {
            p.gameFinished()
            assertFalse(p.interstitialDue(adsRemoved = true))
        }
    }

    @Test
    fun countersRoundTrip() {
        val p = AdPolicy()
        adsAfter(5, p)
        val restored = AdPolicy(p.gamesFinished, p.lastInterstitialGame)
        assertEquals(5, restored.gamesFinished)
        assertEquals(4, restored.lastInterstitialGame)
        restored.gameFinished()
        assertFalse("only two games since the last ad", restored.interstitialDue(adsRemoved = false))
        restored.gameFinished()
        assertTrue(restored.interstitialDue(adsRemoved = false))
    }

    @Test
    fun productsCoverRemoveAdsAndEveryPurchasableScenery() {
        val products = Entitlements.PRODUCTS
        assertEquals(products.size, products.toSet().size)
        assertTrue(Entitlements.REMOVE_ADS in products)
        assertTrue(Entitlements.SCENERY_PACK in products)
        for (s in Scenarios.all) assertEquals(s.id, s.purchasable, Entitlements.sceneryProduct(s.id) in products)
        assertEquals("remove_ads", Entitlements.REMOVE_ADS)
    }

    @Test
    fun packOwnsEveryScenery() {
        val pack = setOf(Entitlements.SCENERY_PACK)
        val single = setOf(Entitlements.sceneryProduct(Scenarios.FUTURE.id))
        assertTrue(Scenarios.all.all { Entitlements.ownsScenery(pack, it.id) })
        assertTrue(Entitlements.ownsScenery(single, Scenarios.FUTURE.id))
        assertFalse(Entitlements.ownsScenery(single, Scenarios.MOUNTAIN_VILLAGE.id))
        assertFalse(Entitlements.ownsScenery(setOf(Entitlements.REMOVE_ADS), Scenarios.FUTURE.id))
    }
}
