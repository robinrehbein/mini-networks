package com.mininetworks.game.monetization

/**
 * A [BillingGateway] like Play's billing test mode (license testers, test cards), in memory: the "account" holds
 * [held] purchases across app installs, so a fresh [Purchases] with an empty store stands for a reinstall. Answers come
 * at once unless [autoSetup] is off; the test finishes purchase flows with [completeFlow], [payPending] or [cancelFlow].
 */
class FakeBillingGateway(
    val prices: Map<String, String> = Entitlements.PRODUCTS.associateWith { "1,99 €" },
) : BillingGateway {
    /** The purchases the Play account holds, as queryPurchases reports them. */
    val held = LinkedHashMap<String, PurchaseInfo>()

    var setupResponse = BillingResponse.OK
    var queryResponse = BillingResponse.OK
    var acknowledgeResponse = BillingResponse.OK

    /** If false, the setup result waits for [finishSetup]. */
    var autoSetup = true

    val launched = ArrayList<String>()
    val acknowledged = ArrayList<String>()
    var connects = 0
    var purchaseQueries = 0
    var ended = false

    private var listener: BillingGateway.Listener? = null
    private var tokens = 0
    override var isReady = false; private set

    override fun connect(listener: BillingGateway.Listener) {
        connects++
        this.listener = listener
        if (autoSetup) finishSetup()
    }

    fun finishSetup() {
        isReady = setupResponse == BillingResponse.OK
        listener!!.onSetupFinished(setupResponse)
    }

    fun disconnect() {
        isReady = false
        listener!!.onDisconnected()
    }

    override fun queryPurchases(onResult: (BillingResponse, List<PurchaseInfo>) -> Unit) {
        purchaseQueries++
        if (queryResponse == BillingResponse.OK) onResult(BillingResponse.OK, held.values.toList()) else onResult(queryResponse, emptyList())
    }

    override fun queryPrices(productIds: List<String>, onResult: (BillingResponse, Map<String, String>) -> Unit) {
        onResult(BillingResponse.OK, prices.filterKeys { it in productIds })
    }

    override fun launchPurchase(productId: String): Boolean {
        if (productId !in prices) return false
        launched += productId
        return true
    }

    override fun acknowledge(token: String, onResult: (BillingResponse) -> Unit) {
        if (acknowledgeResponse == BillingResponse.OK) {
            acknowledged += token
            held.entries.find { it.value.token == token }?.let { it.setValue(it.value.copy(acknowledged = true)) }
        }
        onResult(acknowledgeResponse)
    }

    override fun end() {
        ended = true
        isReady = false
    }

    /** The open flow of [productId] ends paid: Play holds the purchase and reports it. */
    fun completeFlow(productId: String) = finishFlow(productId, PurchaseInfo.State.PURCHASED)

    /** The open flow of [productId] ends with a payment that is still pending (e.g. cash at a shop). */
    fun pendingFlow(productId: String) = finishFlow(productId, PurchaseInfo.State.PENDING)

    /** The pending purchase of [productId] was paid; [whileRunning] reports it as an update, else only queries see it. */
    fun payPending(productId: String, whileRunning: Boolean) {
        val p = held.getValue(productId).copy(state = PurchaseInfo.State.PURCHASED)
        held[productId] = p
        if (whileRunning) listener!!.onPurchasesUpdated(BillingResponse.OK, listOf(p))
    }

    fun cancelFlow() {
        listener!!.onPurchasesUpdated(BillingResponse.USER_CANCELED, emptyList())
    }

    /** A flow for [productId] that Play refuses because the account owns it already. */
    fun alreadyOwnedFlow(productId: String) {
        held[productId] = PurchaseInfo(listOf(productId), PurchaseInfo.State.PURCHASED, acknowledged = true, token = "t${++tokens}")
        listener!!.onPurchasesUpdated(BillingResponse.ITEM_ALREADY_OWNED, emptyList())
    }

    /** Play refunded [productId]: the purchase is gone from the account. */
    fun refund(productId: String) {
        held.remove(productId)
    }

    private fun finishFlow(productId: String, state: PurchaseInfo.State) {
        check(productId in launched) { "no flow for $productId" }
        val p = PurchaseInfo(listOf(productId), state, acknowledged = false, token = "t${++tokens}")
        held[productId] = p
        listener!!.onPurchasesUpdated(BillingResponse.OK, listOf(p))
    }
}

/** An [OwnedProductsStore] in memory; a new one stands for a fresh install (the stored set is not backed up). */
class MemoryOwnedStore(override var owned: Set<String> = emptySet()) : OwnedProductsStore
