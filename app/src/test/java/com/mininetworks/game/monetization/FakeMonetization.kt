package com.mininetworks.game.monetization

/**
 * A [Monetization] for tests: ads are "loaded" as configured, every show call is recorded with its callback (and
 * reported to [onShow], with [AdPlacement.INTERSTITIAL] or null for a rewarded video) so the
 * test decides when and how the ad closes, purchases are recorded and never finish on their own.
 */
class FakeMonetization(
    val owned: MutableSet<String> = mutableSetOf(),
    var interstitialLoaded: Boolean = true,
    var rewardedLoaded: Boolean = true,
    val prices: Map<String, String> = emptyMap(),
    override var privacyOptionsRequired: Boolean = false,
) : Monetization {
    val interstitials = ArrayList<() -> Unit>()
    val rewardeds = ArrayList<(Boolean) -> Unit>()
    val purchases = ArrayList<String>()
    var privacyShown = 0

    /** Called at every ad that shows, before it is recorded; tests use it to check when ads show. */
    var onShow: (AdPlacement?) -> Unit = {}

    override val adsRemoved get() = Entitlements.REMOVE_ADS in owned
    override val rewardedReady get() = !adsRemoved && rewardedLoaded
    override fun ownsScenery(sceneryId: String) = Entitlements.ownsScenery(owned, sceneryId)
    override fun price(productId: String) = if (productId in owned) null else prices[productId]

    override fun purchase(productId: String): Boolean {
        if (productId in owned || productId !in prices) return false
        purchases += productId
        return true
    }

    override fun showInterstitial(onClosed: () -> Unit): Boolean {
        if (adsRemoved || !interstitialLoaded) return false
        onShow(AdPlacement.INTERSTITIAL)
        interstitials += onClosed
        return true
    }

    override fun showRewarded(onResult: (earned: Boolean) -> Unit): Boolean {
        if (!rewardedReady) return false
        onShow(null)
        rewardeds += onResult
        return true
    }

    override fun showPrivacyOptions() {
        privacyShown++
    }
}
