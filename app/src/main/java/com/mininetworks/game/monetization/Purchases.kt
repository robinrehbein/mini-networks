package com.mininetworks.game.monetization

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The one-time products the player owns, kept in step with Play (docs/PLAN.md 5.1, docs/TOP100.md E2). Plain Kotlin
 * on top of a [BillingGateway], so all of it runs as JVM tests with a fake gateway.
 *
 * - [start] reads what was owned at the last check from [store] (purchases work offline) and connects; once the
 *   connection is up, every purchase is queried and the answer replaces the stored set ([PlayRules.reconcile]), which
 *   restores purchases after a reinstall and drops refunds. [refresh] (from `onResume`) asks again and reconnects
 *   after a failed setup.
 * - Only completed purchases count. Pending ones (slow payment methods) are remembered in [pending], so the store
 *   hides their price and cannot sell them twice; they count once Play reports them as purchased, by an update while
 *   the app runs or by the next query.
 * - Completed purchases are acknowledged. A failed acknowledgement is tried again at the next query; one in flight is
 *   never sent twice.
 *
 * [owned] and [pending] are read on the game thread and answer from memory. [onOwnedChanged] runs on the thread that
 * delivered the answer, after every answer of Play that was applied.
 */
class Purchases(private val gateway: BillingGateway, private val store: OwnedProductsStore) : BillingGateway.Listener {
    /** Product ids owned now. */
    @Volatile var owned: Set<String> = emptySet(); private set

    /** Product ids with a purchase that waits for payment. */
    @Volatile var pending: Set<String> = emptySet(); private set

    /** Called with [owned] after every answer of Play that was applied (a query or a purchase update). */
    var onOwnedChanged: (Set<String>) -> Unit = {}

    private val prices = ConcurrentHashMap<String, String>()
    private val acknowledging: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    @Volatile private var connecting = false
    @Volatile private var closed = false

    /** Loads the stored products and connects; call once, off the main thread (the store reads from disk). */
    fun start() {
        owned = store.owned
        connect()
    }

    /** Asks Play again what is owned (and for prices while they are missing), or reconnects after a failed setup. */
    fun refresh() {
        if (closed) return
        if (!gateway.isReady) {
            connect()
            return
        }
        queryPurchases()
        if (prices.isEmpty()) queryPrices()
    }

    /** Ends the connection; later answers are ignored. */
    fun close() {
        closed = true
        gateway.end()
    }

    /** The store price of [productId] while it can be bought: known, not owned and not waiting for payment. */
    fun price(productId: String): String? =
        if (productId in owned || productId in pending) null else prices[productId]

    /** Starts buying [productId]; false if it cannot be bought right now. */
    fun purchase(productId: String): Boolean {
        if (closed || price(productId) == null || !gateway.isReady) return false
        return gateway.launchPurchase(productId)
    }

    private fun connect() {
        if (closed || connecting) return
        connecting = true
        gateway.connect(this)
    }

    override fun onSetupFinished(response: BillingResponse) {
        connecting = false
        if (closed || response != BillingResponse.OK) return
        queryPurchases()
        queryPrices()
    }

    // Calls reconnect on their own; a failed setup is retried by refresh().
    override fun onDisconnected() {
        connecting = false
    }

    override fun onPurchasesUpdated(response: BillingResponse, purchases: List<PurchaseInfo>) {
        if (closed) return
        when (response) {
            BillingResponse.OK -> apply(PlayRules.addBought(owned, pending, purchases))
            // Owned on another device or from before a reinstall: ask Play what is owned instead of staying locked.
            BillingResponse.ITEM_ALREADY_OWNED -> queryPurchases()
            else -> Unit
        }
    }

    private fun queryPurchases() {
        gateway.queryPurchases { response, purchases ->
            // Offline or failed: keep what is known, the next refresh asks again.
            if (!closed && response == BillingResponse.OK) apply(PlayRules.reconcile(purchases))
        }
    }

    private fun queryPrices() {
        gateway.queryPrices(Entitlements.PRODUCTS) { response, found ->
            if (response == BillingResponse.OK) prices.putAll(found)
        }
    }

    private fun apply(outcome: PurchaseOutcome) {
        pending = outcome.pending
        if (outcome.owned != owned) {
            owned = outcome.owned
            store.owned = outcome.owned
        }
        onOwnedChanged(owned)
        outcome.acknowledge.forEach(::acknowledge)
    }

    private fun acknowledge(token: String) {
        if (!acknowledging.add(token)) return
        // Success or not, the token is free again: a failed one comes back unacknowledged from the next query.
        gateway.acknowledge(token) { acknowledging.remove(token) }
    }
}
