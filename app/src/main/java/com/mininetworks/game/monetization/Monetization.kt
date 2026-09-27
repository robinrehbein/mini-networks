package com.mininetworks.game.monetization

/** Why a full-screen ad is shown; its result comes back with the same placement. */
enum class AdPlacement {
    /** Between two games, see [AdPolicy]. */
    INTERSTITIAL,

    /** Rewarded: go on once after game over. */
    CONTINUE,

    /** Rewarded: +1 router on the week reward screen. */
    BONUS_ROUTER,
}

/**
 * Ads and purchases (docs/PLAN.md 5.1). The game never shows ads while playing: [showInterstitial] is only called
 * between games (when [AdPolicy] allows it), [showRewarded] only on the game-over card and the week reward screen,
 * and only when the player asks for it. Once "remove ads" is owned ([adsRemoved]) the game hands out rewarded
 * benefits without a video and asks for no interstitials.
 *
 * Getters are read on the game thread; the show and purchase calls may come from any thread and run on the main
 * thread. Their callbacks run on the main thread and are always called once for every call that returned true.
 * [PlayMonetization] is the Google Play implementation, [NoOpMonetization] the default for debug builds and tests.
 */
interface Monetization : Entitlements {
    /** True once "remove ads" is owned. */
    val adsRemoved: Boolean

    /** True if a rewarded video is loaded and [showRewarded] would show it. */
    val rewardedReady: Boolean

    /** True if the privacy options of the consent form must be reachable (UMP, from the settings). */
    val privacyOptionsRequired: Boolean

    /** The store's price of [productId] as text, or null while it cannot be bought (unknown, offline or owned). */
    fun price(productId: String): String?

    /** Starts buying [productId]; false if that is not possible right now. */
    fun purchase(productId: String): Boolean

    /** Shows a loaded interstitial; false if none is ready. [onClosed] follows once it is gone. */
    fun showInterstitial(onClosed: () -> Unit): Boolean

    /** Shows a loaded rewarded video; false if none is ready. [onResult] says whether the reward was earned. */
    fun showRewarded(onResult: (earned: Boolean) -> Unit): Boolean

    /** Opens the consent form again, so the player can change their choice. */
    fun showPrivacyOptions()

    override fun purchaseScenery(sceneryId: String) = purchase(Entitlements.sceneryProduct(sceneryId))
}

/** No ads, no purchases, owns nothing: the default of debug builds and tests. */
object NoOpMonetization : Monetization {
    override val adsRemoved = false
    override val rewardedReady = false
    override val privacyOptionsRequired = false
    override fun ownsScenery(sceneryId: String) = false
    override fun price(productId: String): String? = null
    override fun purchase(productId: String) = false
    override fun showInterstitial(onClosed: () -> Unit) = false
    override fun showRewarded(onResult: (earned: Boolean) -> Unit) = false
    override fun showPrivacyOptions() = Unit
}
