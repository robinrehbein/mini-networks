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
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.mininetworks.game.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * [Monetization] with Google Play: AdMob interstitials and rewarded videos behind the UMP consent form, one-time
 * products with the Play Billing Library. The decisions that policies care about live in [PlayRules]; this class only
 * wires them to the SDKs.
 *
 * [start] first asks UMP for the consent status and shows the form where it is required (EEA, UK); the Mobile Ads SDK
 * is only initialized and ads are only requested once [ConsentInformation.canRequestAds] is true, and never while
 * "remove ads" is owned. Owned products are kept in [MonetizationStore], so they work offline, and re-queried from Play
 * at every start and every [refresh] (from `onResume`, which also catches promo codes and pending purchases finished
 * outside the app): the answer replaces the stored set, so refunds disappear. New purchases are acknowledged, otherwise
 * Play refunds them after three days. Ad unit ids come from [BuildConfig] (Google's test ids unless real ones are
 * configured, see app/build.gradle.kts).
 */
class PlayMonetization(private val activity: Activity) : Monetization, PurchasesUpdatedListener {
    private val main = Handler(Looper.getMainLooper())
    private val store = MonetizationStore(activity)
    @Volatile private var owned: Set<String> = store.owned
    private val details = ConcurrentHashMap<String, ProductDetails>()
    private val consent: ConsentInformation = UserMessagingPlatform.getConsentInformation(activity)
    @Volatile private var privacyRequired = false
    private val adsStarted = AtomicBoolean(false)
    @Volatile private var adsReady = false
    private val interstitial = AtomicReference<InterstitialAd?>()
    private val rewarded = AtomicReference<RewardedAd?>()
    // Main thread only.
    private var loadingInterstitial = false
    private var loadingRewarded = false
    private var retryPending = false
    @Volatile private var closed = false
    @Volatile private var connecting = false

    private val billing: BillingClient = BillingClient.newBuilder(activity)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    override val adsRemoved get() = Entitlements.REMOVE_ADS in owned
    override val rewardedReady get() = !adsRemoved && rewarded.get() != null
    override val privacyOptionsRequired get() = privacyRequired

    override fun ownsScenery(sceneryId: String) = Entitlements.ownsScenery(owned, sceneryId)

    override fun price(productId: String): String? =
        if (productId in owned) null else details[productId]?.oneTimePurchaseOfferDetails?.formattedPrice

    /** Consent first, then ads; billing connects in parallel. Call once from `onCreate`. */
    fun start() {
        gatherConsent()
        connectBilling()
    }

    /**
     * Asks Play again what is owned (and for the prices while they are missing); connects first if the last setup
     * failed. Call from `onResume`.
     */
    fun refresh() {
        if (closed) return
        if (!billing.isReady) {
            connectBilling()
            return
        }
        queryPurchases()
        if (details.isEmpty()) queryProducts()
    }

    private fun connectBilling() {
        if (closed || connecting) return
        connecting = true
        billing.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                if (result.responseCode != BillingClient.BillingResponseCode.OK) return
                queryPurchases()
                queryProducts()
            }

            // Calls reconnect on their own (enableAutoServiceReconnection); a failed setup is retried by refresh().
            override fun onBillingServiceDisconnected() {
                connecting = false
            }
        })
    }

    /** Ends the billing connection and drops pending retries; call from `onDestroy`. */
    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        billing.endConnection()
    }

    // ---------------------------------------------------------------- consent and ads

    private fun gatherConsent() {
        val params = ConsentRequestParameters.Builder().build()
        consent.requestConsentInfoUpdate(
            activity, params,
            {
                updatePrivacyRequired()
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ ->
                    updatePrivacyRequired()
                    startAds()
                }
            },
            { _ -> startAds() },
        )
        // Consent from an earlier session allows ads while the update runs.
        startAds()
    }

    private fun updatePrivacyRequired() {
        privacyRequired = consent.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }

    /** Initializes the Mobile Ads SDK once consent allows it and ads are not removed; no ad is requested before. */
    private fun startAds() {
        if (!PlayRules.canInitializeAds(consent.canRequestAds(), adsRemoved, adsStarted.get(), closed)) return
        if (!adsStarted.compareAndSet(false, true)) return
        val context = activity.applicationContext
        thread(name = "MobileAdsInit") {
            MobileAds.initialize(context) {
                main.post {
                    adsReady = true
                    loadAds()
                }
            }
        }
    }

    /** Loads whatever is missing; nothing once ads are removed (rewarded benefits come without a video then). */
    private fun loadAds() {
        if (!PlayRules.shouldLoadAds(adsReady, consent.canRequestAds(), adsRemoved, closed)) return
        if (interstitial.get() == null && !loadingInterstitial) {
            loadingInterstitial = true
            InterstitialAd.load(activity, BuildConfig.ADMOB_INTERSTITIAL_ID, AdRequest.Builder().build(), object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    loadingInterstitial = false
                    interstitial.set(ad)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadingInterstitial = false
                    retryLater()
                }
            })
        }
        if (rewarded.get() == null && !loadingRewarded) {
            loadingRewarded = true
            RewardedAd.load(activity, BuildConfig.ADMOB_REWARDED_ID, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    loadingRewarded = false
                    rewarded.set(ad)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadingRewarded = false
                    retryLater()
                }
            })
        }
    }

    private fun retryLater() {
        if (retryPending || closed) return
        retryPending = true
        main.postDelayed({
            retryPending = false
            loadAds()
        }, RETRY_MS)
    }

    override fun showInterstitial(onClosed: () -> Unit): Boolean {
        if (adsRemoved) return false
        val ad = interstitial.getAndSet(null) ?: return false
        main.post {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    onClosed()
                    loadAds()
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    onClosed()
                    loadAds()
                }
            }
            ad.show(activity)
        }
        return true
    }

    override fun showRewarded(onResult: (earned: Boolean) -> Unit): Boolean {
        if (adsRemoved) return false
        val ad = rewarded.getAndSet(null) ?: return false
        main.post {
            var earned = false
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    onResult(earned)
                    loadAds()
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    onResult(false)
                    loadAds()
                }
            }
            ad.show(activity) { earned = true }
        }
        return true
    }

    override fun showPrivacyOptions() {
        main.post {
            UserMessagingPlatform.showPrivacyOptionsForm(activity) { _ ->
                updatePrivacyRequired()
                startAds()
            }
        }
    }

    // ---------------------------------------------------------------- billing

    override fun purchase(productId: String): Boolean {
        if (productId in owned || !billing.isReady) return false
        val product = details[productId] ?: return false
        main.post {
            val params = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product)
            product.oneTimePurchaseOfferDetails?.offerToken?.let(params::setOfferToken)
            val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params.build())).build()
            billing.launchBillingFlow(activity, flow)
        }
        return true
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> if (purchases != null) {
                apply(PlayRules.addBought(owned, purchases.map(::info)))
            }
            // Owned on another device or from before a reinstall: ask Play what is owned instead of staying locked.
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> queryPurchases()
        }
    }

    /** What Play says is owned now replaces the stored set; pending purchases do not count yet. */
    private fun queryPurchases() {
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        billing.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            apply(PlayRules.reconcile(purchases.map(::info)))
        }
    }

    private fun apply(outcome: PurchaseOutcome) {
        setOwned(outcome.owned)
        outcome.acknowledge.forEach(::acknowledge)
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

    private fun queryProducts() {
        val products = Entitlements.PRODUCTS.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.INAPP).build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        billing.queryProductDetailsAsync(params) { result, found ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            for (d in found.productDetailsList) details[d.productId] = d
        }
    }

    private fun acknowledge(token: String) {
        billing.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()) { }
    }

    private fun setOwned(products: Set<String>) {
        owned = products
        store.owned = products
        if (adsRemoved) {
            interstitial.set(null)
            rewarded.set(null)
        } else {
            // "Remove ads" refunded or never owned after all: the SDK may start now (if consent allows) and load.
            main.post {
                startAds()
                loadAds()
            }
        }
    }

    private companion object {
        /** Wait before loading again after a failed ad request. */
        const val RETRY_MS = 60_000L
    }
}
