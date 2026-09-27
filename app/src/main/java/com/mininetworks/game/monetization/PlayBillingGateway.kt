package com.mininetworks.game.monetization

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import java.util.concurrent.ConcurrentHashMap

/**
 * [BillingGateway] with the Play Billing Library: one-time products (`INAPP`), pending purchases enabled for them, and
 * automatic reconnects. The client is built at the first [connect] (off the main thread, see [PlayMonetization.start]);
 * the purchase flow is launched on the main thread.
 */
class PlayBillingGateway(private val activity: Activity) : BillingGateway {
    private val main = Handler(Looper.getMainLooper())
    private val details = ConcurrentHashMap<String, ProductDetails>()
    @Volatile private var listener: BillingGateway.Listener? = null
    @Volatile private var client: BillingClient? = null

    override val isReady get() = client?.isReady == true

    override fun connect(listener: BillingGateway.Listener) {
        this.listener = listener
        val client = client ?: BillingClient.newBuilder(activity)
            .setListener { result, purchases -> this.listener?.onPurchasesUpdated(response(result), purchases.orEmpty().map(::info)) }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
            .also { client = it }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                this@PlayBillingGateway.listener?.onSetupFinished(response(result))
            }

            override fun onBillingServiceDisconnected() {
                this@PlayBillingGateway.listener?.onDisconnected()
            }
        })
    }

    override fun queryPurchases(onResult: (BillingResponse, List<PurchaseInfo>) -> Unit) {
        val client = client ?: return
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        client.queryPurchasesAsync(params) { result, purchases -> onResult(response(result), purchases.map(::info)) }
    }

    override fun queryPrices(productIds: List<String>, onResult: (BillingResponse, Map<String, String>) -> Unit) {
        val client = client ?: return
        val products = productIds.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.INAPP).build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        client.queryProductDetailsAsync(params) { result, found ->
            val prices = HashMap<String, String>()
            for (d in found.productDetailsList) {
                details[d.productId] = d
                d.oneTimePurchaseOfferDetails?.formattedPrice?.let { prices[d.productId] = it }
            }
            onResult(response(result), prices)
        }
    }

    override fun launchPurchase(productId: String): Boolean {
        val client = client ?: return false
        val product = details[productId] ?: return false
        main.post {
            val params = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product)
            product.oneTimePurchaseOfferDetails?.offerToken?.let(params::setOfferToken)
            val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params.build())).build()
            client.launchBillingFlow(activity, flow)
        }
        return true
    }

    override fun acknowledge(token: String, onResult: (BillingResponse) -> Unit) {
        val client = client ?: return onResult(BillingResponse.UNAVAILABLE)
        client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()) { onResult(response(it)) }
    }

    override fun end() {
        main.removeCallbacksAndMessages(null)
        client?.endConnection()
    }

    private fun response(result: BillingResult) = when (result.responseCode) {
        BillingClient.BillingResponseCode.OK -> BillingResponse.OK
        BillingClient.BillingResponseCode.USER_CANCELED -> BillingResponse.USER_CANCELED
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> BillingResponse.ITEM_ALREADY_OWNED
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
        BillingClient.BillingResponseCode.NETWORK_ERROR,
        -> BillingResponse.UNAVAILABLE
        else -> BillingResponse.ERROR
    }

    private fun info(p: Purchase) = PurchaseInfo(
        p.products,
        when (p.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseInfo.State.PURCHASED
            Purchase.PurchaseState.PENDING -> PurchaseInfo.State.PENDING
            else -> PurchaseInfo.State.OTHER
        },
        p.isAcknowledged,
        p.purchaseToken,
    )
}
