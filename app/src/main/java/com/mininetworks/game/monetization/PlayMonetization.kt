package com.mininetworks.game.monetization

import android.app.Activity
import android.os.Handler
import android.os.Looper
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
import com.mininetworks.game.data.GameIo
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
 * "remove ads" is owned. Purchases are handled by [Purchases] over a [PlayBillingGateway]: owned products are kept in
 * [MonetizationStore], so they work offline, and re-queried from Play at every start and every [refresh] (from
 * `onResume`, which also catches promo codes and pending purchases finished outside the app): the answer replaces the
 * stored set, so refunds disappear and purchases come back after a reinstall. New purchases are acknowledged, otherwise
 * Play refunds them after three days. Ad unit ids come from [BuildConfig] (Google's test ids unless real ones are
 * configured, see app/build.gradle.kts).
 */
class PlayMonetization(private val activity: Activity) : Monetization {
    private val main = Handler(Looper.getMainLooper())
    private val reviewerPrefs by lazy { activity.getSharedPreferences("reviewer_access", Activity.MODE_PRIVATE) }
    @Volatile private var reviewerAccess = reviewerPrefs.getBoolean("unlocked", false)
    val reviewerAccessAvailable get() = BuildConfig.REVIEW_ACCESS_CODE.isNotEmpty() && !reviewerAccess

    fun unlockReviewerAccess(code: String): Boolean {
        if (BuildConfig.REVIEW_ACCESS_CODE.isEmpty() || code.trim() != BuildConfig.REVIEW_ACCESS_CODE) return false
        reviewerPrefs.edit().putBoolean("unlocked", true).apply()
        reviewerAccess = true
        interstitial.set(null)
        rewarded.set(null)
        return true
    }
    // Created on the GameIo thread in [start]: reading the stored products is disk work (docs/TOP100.md A3).
    private val store by lazy { MonetizationStore(activity) }
    // Main thread only: the UMP SDK wants its calls there. First used in [gatherConsent].
    private val consent: ConsentInformation by lazy { UserMessagingPlatform.getConsentInformation(activity) }
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

    /** Set on the GameIo thread by [start]; until then nothing is owned and a purchase says "not now". */
    @Volatile private var purchases: Purchases? = null
    private val owned get() = purchases?.owned ?: emptySet()

    override val adsRemoved get() = reviewerAccess || Entitlements.REMOVE_ADS in owned
    override val rewardedReady get() = !adsRemoved && rewarded.get() != null
    override val privacyOptionsRequired get() = privacyRequired

    override fun ownsScenery(sceneryId: String) = reviewerAccess || Entitlements.ownsScenery(owned, sceneryId)

    override fun price(productId: String): String? = purchases?.price(productId)

    /**
     * Call once from `onCreate`; returns at once. On the GameIo thread: the products owned at the last check are read
     * from the store and billing is set up and connects; then, back on the main thread, consent is gathered and ads
     * start. The consent step waits for the stored products so a player who removed ads never has the ads SDK started.
     */
    fun start() {
        GameIo.execute {
            if (!closed) {
                val p = Purchases(PlayBillingGateway(activity), store)
                p.onOwnedChanged = ::ownedChanged
                purchases = p
                p.start()
            }
            main.post { if (!closed) gatherConsent() }
        }
    }

    /**
     * Asks Play again what is owned (and for the prices while they are missing); connects first if the last setup
     * failed. Call from `onResume`.
     */
    fun refresh() {
        if (!closed) purchases?.refresh()
    }

    /** Ends the billing connection and drops pending retries; call from `onDestroy`. */
    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        // After the setup task of [start], should that still be queued.
        GameIo.execute { purchases?.close() }
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

    override fun purchase(productId: String): Boolean = purchases?.purchase(productId) ?: false

    /** Play answered what is owned: without ads once "remove ads" is owned, otherwise the ads SDK may start. */
    private fun ownedChanged(products: Set<String>) {
        if (Entitlements.REMOVE_ADS in products) {
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
