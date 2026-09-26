package com.mininetworks.game.monetization

import com.mininetworks.game.monetization.PurchaseInfo.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The consent gating and the owned-set reconciliation of [PlayMonetization], via [PlayRules]. */
class PlayRulesTest {

    private fun purchase(product: String, state: State = State.PURCHASED, acknowledged: Boolean = false) =
        PurchaseInfo(listOf(product), state, acknowledged, "token-$product")

    @Test
    fun noAdsSdkBeforeConsentOrAfterRemoveAds() {
        assertFalse("no consent yet", PlayRules.canInitializeAds(consentAllows = false, adsRemoved = false, started = false, closed = false))
        assertTrue(PlayRules.canInitializeAds(consentAllows = true, adsRemoved = false, started = false, closed = false))
        assertFalse("paying players send nothing to the ads SDK", PlayRules.canInitializeAds(consentAllows = true, adsRemoved = true, started = false, closed = false))
        assertFalse("only once", PlayRules.canInitializeAds(consentAllows = true, adsRemoved = false, started = true, closed = false))
        assertFalse(PlayRules.canInitializeAds(consentAllows = true, adsRemoved = false, started = false, closed = true))
    }

    @Test
    fun adsLoadOnlyWithConsentAndWithoutRemoveAds() {
        assertTrue(PlayRules.shouldLoadAds(sdkReady = true, consentAllows = true, adsRemoved = false, closed = false))
        assertFalse("SDK not ready", PlayRules.shouldLoadAds(sdkReady = false, consentAllows = true, adsRemoved = false, closed = false))
        assertFalse("consent withdrawn", PlayRules.shouldLoadAds(sdkReady = true, consentAllows = false, adsRemoved = false, closed = false))
        assertFalse("ads removed", PlayRules.shouldLoadAds(sdkReady = true, consentAllows = true, adsRemoved = true, closed = false))
        assertFalse(PlayRules.shouldLoadAds(sdkReady = true, consentAllows = true, adsRemoved = false, closed = true))
    }

    @Test
    fun queryAnswerReplacesTheStoredSet() {
        val outcome = PlayRules.reconcile(listOf(purchase(Entitlements.SCENERY_PACK, acknowledged = true)))
        assertEquals("a refunded remove_ads is gone", setOf(Entitlements.SCENERY_PACK), outcome.owned)
        assertEquals("already acknowledged", emptyList<String>(), outcome.acknowledge)
        assertEquals(emptySet<String>(), PlayRules.reconcile(emptyList()).owned)
    }

    @Test
    fun onlyCompletedPurchasesCountAndAreAcknowledged() {
        val outcome = PlayRules.reconcile(
            listOf(
                purchase(Entitlements.REMOVE_ADS),
                purchase(Entitlements.SCENERY_PACK, State.PENDING),
                purchase("scenery_future_2030", State.OTHER),
            ),
        )
        assertEquals(setOf(Entitlements.REMOVE_ADS), outcome.owned)
        assertEquals(listOf("token-${Entitlements.REMOVE_ADS}"), outcome.acknowledge)
    }

    @Test
    fun finishedFlowAddsToWhatIsOwned() {
        val outcome = PlayRules.addBought(setOf(Entitlements.REMOVE_ADS), listOf(purchase(Entitlements.SCENERY_PACK), purchase("x", State.PENDING)))
        assertEquals(setOf(Entitlements.REMOVE_ADS, Entitlements.SCENERY_PACK), outcome.owned)
        assertEquals(listOf("token-${Entitlements.SCENERY_PACK}"), outcome.acknowledge)
    }
}
