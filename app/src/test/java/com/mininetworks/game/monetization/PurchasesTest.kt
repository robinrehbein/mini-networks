package com.mininetworks.game.monetization

import com.mininetworks.game.game.Scenarios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Buying, restoring and acknowledging one-time products (docs/TOP100.md E2) with [Purchases] over a
 * [FakeBillingGateway] that plays Play's billing test mode: plain JVM tests, no Play Store needed.
 */
class PurchasesTest {

    private val removeAds = Entitlements.REMOVE_ADS
    private val pack = Entitlements.SCENERY_PACK
    private val future = Entitlements.sceneryProduct(Scenarios.FUTURE.id)

    private fun started(gateway: FakeBillingGateway = FakeBillingGateway(), store: OwnedProductsStore = MemoryOwnedStore()) =
        Purchases(gateway, store).also { it.start() }

    /** [Purchases] seen as the game sees it: through [PlayMonetization]'s getters. */
    private fun Purchases.adsRemoved() = removeAds in owned
    private fun Purchases.ownsScenery(id: String) = Entitlements.ownsScenery(owned, id)

    @Test
    fun removeAdsGrantsTheEntitlementAndIsAcknowledged() {
        val gateway = FakeBillingGateway()
        val store = MemoryOwnedStore()
        val purchases = started(gateway, store)
        assertEquals("1,99 €", purchases.price(removeAds))
        assertFalse(purchases.adsRemoved())

        assertTrue(purchases.purchase(removeAds))
        assertEquals(listOf(removeAds), gateway.launched)
        gateway.completeFlow(removeAds)

        assertTrue(purchases.adsRemoved())
        assertEquals("kept for offline starts", setOf(removeAds), store.owned)
        assertEquals("acknowledged, so Play does not refund it after three days", listOf(gateway.held.getValue(removeAds).token), gateway.acknowledged)
        assertTrue(gateway.held.getValue(removeAds).acknowledged)
        assertNull("no longer for sale", purchases.price(removeAds))
        assertFalse("cannot be bought twice", purchases.purchase(removeAds))
    }

    @Test
    fun theSceneryPackOwnsEveryPurchasableSceneryAndASingleOneOnlyItself() {
        val gateway = FakeBillingGateway()
        val purchases = started(gateway)
        assertTrue(purchases.purchase(future))
        gateway.completeFlow(future)
        assertTrue(purchases.ownsScenery(Scenarios.FUTURE.id))
        assertFalse(purchases.ownsScenery(Scenarios.MOUNTAIN_VILLAGE.id))
        assertFalse("a scenery does not remove ads", purchases.adsRemoved())

        assertTrue(purchases.purchase(pack))
        gateway.completeFlow(pack)
        for (s in Scenarios.all) assertTrue(s.id, purchases.ownsScenery(s.id))
        assertEquals(setOf(future, pack), purchases.owned)
        assertEquals(2, gateway.acknowledged.size)
    }

    @Test
    fun purchasesAreRestoredAfterAReinstall() {
        val gateway = FakeBillingGateway()
        val first = started(gateway)
        first.purchase(removeAds)
        gateway.completeFlow(removeAds)
        first.purchase(pack)
        gateway.completeFlow(pack)
        first.close()

        // Reinstall: the app data (and the stored set, which is excluded from backups) is gone, the Play account is not.
        val freshStore = MemoryOwnedStore()
        val acknowledgedBefore = gateway.acknowledged.size
        val second = Purchases(gateway, freshStore)
        second.start()
        assertTrue("queried on start", gateway.purchaseQueries >= 2)
        assertEquals(setOf(removeAds, pack), second.owned)
        assertEquals(setOf(removeAds, pack), freshStore.owned)
        assertTrue(second.adsRemoved())
        assertTrue(second.ownsScenery(Scenarios.FUTURE.id))
        assertEquals("already acknowledged ones are not sent again", acknowledgedBefore, gateway.acknowledged.size)
    }

    @Test
    fun aReinstallWhileOfflineRestoresAtTheNextRefresh() {
        val gateway = FakeBillingGateway()
        gateway.held[removeAds] = PurchaseInfo(listOf(removeAds), PurchaseInfo.State.PURCHASED, acknowledged = true, token = "old")
        gateway.setupResponse = BillingResponse.UNAVAILABLE
        val purchases = started(gateway)
        assertTrue(purchases.owned.isEmpty())
        assertFalse("nothing to sell while offline", purchases.purchase(removeAds))

        gateway.setupResponse = BillingResponse.OK
        purchases.refresh()
        assertEquals("refresh reconnects", 2, gateway.connects)
        assertTrue(purchases.adsRemoved())
    }

    @Test
    fun aFailedQueryKeepsTheStoredPurchases() {
        val gateway = FakeBillingGateway().apply { queryResponse = BillingResponse.UNAVAILABLE }
        val store = MemoryOwnedStore(setOf(removeAds))
        val purchases = started(gateway, store)
        assertTrue("offline start keeps the stored set", purchases.adsRemoved())
        assertEquals(setOf(removeAds), store.owned)
    }

    @Test
    fun theQueryReplacesTheStoredSetSoRefundsDisappear() {
        val gateway = FakeBillingGateway()
        val store = MemoryOwnedStore()
        val purchases = started(gateway, store)
        purchases.purchase(removeAds)
        gateway.completeFlow(removeAds)
        gateway.refund(removeAds)
        purchases.refresh()
        assertFalse(purchases.adsRemoved())
        assertEquals(emptySet<String>(), store.owned)
        assertNotNull("for sale again", purchases.price(removeAds))
    }

    @Test
    fun aPendingPurchaseCountsOnlyOncePaidAndIsThenAcknowledged() {
        val gateway = FakeBillingGateway()
        val purchases = started(gateway)
        purchases.purchase(pack)
        gateway.pendingFlow(pack)
        assertFalse("not owned while payment is pending", purchases.ownsScenery(Scenarios.FUTURE.id))
        assertEquals(setOf(pack), purchases.pending)
        assertTrue("never acknowledged while pending", gateway.acknowledged.isEmpty())
        assertNull("the store hides it, so it is not bought twice", purchases.price(pack))
        assertFalse(purchases.purchase(pack))

        gateway.payPending(pack, whileRunning = true)
        assertTrue(purchases.ownsScenery(Scenarios.FUTURE.id))
        assertEquals(emptySet<String>(), purchases.pending)
        assertEquals(listOf(gateway.held.getValue(pack).token), gateway.acknowledged)
    }

    @Test
    fun aPendingPurchasePaidWhileTheAppWasClosedCountsAtTheNextStart() {
        val gateway = FakeBillingGateway()
        val store = MemoryOwnedStore()
        val first = started(gateway, store)
        first.purchase(removeAds)
        gateway.pendingFlow(removeAds)
        first.close()
        assertEquals(emptySet<String>(), store.owned)

        gateway.payPending(removeAds, whileRunning = false)
        val second = started(gateway, store)
        assertTrue(second.adsRemoved())
        assertEquals(listOf(gateway.held.getValue(removeAds).token), gateway.acknowledged)
    }

    @Test
    fun aPendingPurchaseIsStillPendingAfterARestart() {
        val gateway = FakeBillingGateway()
        val first = started(gateway)
        first.purchase(removeAds)
        gateway.pendingFlow(removeAds)
        val second = started(gateway)
        assertFalse(second.adsRemoved())
        assertEquals(setOf(removeAds), second.pending)
        assertNull(second.price(removeAds))
    }

    @Test
    fun aFailedAcknowledgementIsRetriedAtTheNextQuery() {
        val gateway = FakeBillingGateway().apply { acknowledgeResponse = BillingResponse.UNAVAILABLE }
        val purchases = started(gateway)
        purchases.purchase(removeAds)
        gateway.completeFlow(removeAds)
        assertTrue("granted at once, acknowledged later", purchases.adsRemoved())
        assertTrue(gateway.acknowledged.isEmpty())

        gateway.acknowledgeResponse = BillingResponse.OK
        purchases.refresh()
        assertEquals(listOf(gateway.held.getValue(removeAds).token), gateway.acknowledged)
        purchases.refresh()
        assertEquals("once acknowledged, never again", 1, gateway.acknowledged.size)
    }

    @Test
    fun aCancelledFlowChangesNothing() {
        val gateway = FakeBillingGateway()
        val purchases = started(gateway)
        purchases.purchase(removeAds)
        gateway.cancelFlow()
        assertTrue(purchases.owned.isEmpty())
        assertTrue(purchases.pending.isEmpty())
        assertNotNull(purchases.price(removeAds))
    }

    @Test
    fun alreadyOwnedAsksPlayWhatIsOwned() {
        val gateway = FakeBillingGateway()
        val purchases = started(gateway)
        purchases.purchase(pack)
        gateway.alreadyOwnedFlow(pack)
        assertTrue(purchases.ownsScenery(Scenarios.MOUNTAIN_VILLAGE.id))
    }

    @Test
    fun nothingIsSoldBeforeTheStoreAnswersOrAfterClose() {
        val gateway = FakeBillingGateway().apply { autoSetup = false }
        val purchases = started(gateway)
        assertNull("prices unknown", purchases.price(removeAds))
        assertFalse(purchases.purchase(removeAds))
        gateway.finishSetup()
        assertTrue(purchases.purchase(removeAds))
        assertFalse("unknown products are never launched", purchases.purchase("scenery_unknown"))

        purchases.close()
        assertTrue(gateway.ended)
        assertFalse(purchases.purchase(pack))
        gateway.completeFlow(removeAds)
        assertTrue("answers after close are ignored", purchases.owned.isEmpty())
    }

    @Test
    fun ownedChangesAreReportedSoAdsStopOnceRemoved() {
        val gateway = FakeBillingGateway()
        val purchases = Purchases(gateway, MemoryOwnedStore())
        val reported = ArrayList<Set<String>>()
        purchases.onOwnedChanged = { reported += it }
        purchases.start()
        purchases.purchase(removeAds)
        gateway.completeFlow(removeAds)
        assertEquals(listOf(emptySet(), setOf(removeAds)), reported)
    }
}
