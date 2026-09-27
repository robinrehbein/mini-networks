package com.mininetworks.game.monetization

/** The answer of a billing call, reduced to what [Purchases] decides on. */
enum class BillingResponse {
    OK,
    USER_CANCELED,

    /** The product is owned already (another device, before a reinstall): ask what is owned. */
    ITEM_ALREADY_OWNED,

    /** Not reachable right now (offline, Play Store updating): try again later. */
    UNAVAILABLE,
    ERROR,
}

/**
 * The part of the Play Billing Library [Purchases] uses, as a narrow wrapper: [PlayBillingGateway] forwards to the
 * real `BillingClient`, tests hand in a fake that answers as the test wants. Callbacks may come on any thread.
 */
interface BillingGateway {
    /** What the billing connection reports on its own. */
    interface Listener {
        /** The connection set up by [connect] is ready ([BillingResponse.OK]) or failed. */
        fun onSetupFinished(response: BillingResponse)

        /** The connection dropped; the next call reconnects or [connect] is called again. */
        fun onDisconnected()

        /** A purchase flow finished, or a pending purchase changed while the app runs. */
        fun onPurchasesUpdated(response: BillingResponse, purchases: List<PurchaseInfo>)
    }

    /** True while connected, so queries and purchases can run. */
    val isReady: Boolean

    /** Connects (again); [listener] hears the setup result and every later purchase update. */
    fun connect(listener: Listener)

    /** Asks for every one-time purchase the account holds, including pending ones (`queryPurchasesAsync`). */
    fun queryPurchases(onResult: (BillingResponse, List<PurchaseInfo>) -> Unit)

    /** Asks for the store prices of [productIds]; products the store does not sell are missing from the map. */
    fun queryPrices(productIds: List<String>, onResult: (BillingResponse, Map<String, String>) -> Unit)

    /** Opens the purchase flow of [productId]; false if its details are unknown. The result comes as an update. */
    fun launchPurchase(productId: String): Boolean

    /** Acknowledges the purchase [token]; Play refunds purchases that are not acknowledged within three days. */
    fun acknowledge(token: String, onResult: (BillingResponse) -> Unit)

    /** Ends the connection. */
    fun end()
}

/** Where the products owned at the last check are kept, so purchases work offline ([MonetizationStore]). */
interface OwnedProductsStore {
    var owned: Set<String>
}
