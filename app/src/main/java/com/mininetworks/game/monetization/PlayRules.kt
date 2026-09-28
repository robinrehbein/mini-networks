package com.mininetworks.game.monetization

/** A purchase as Play reports it, reduced to what [PlayRules] decides on. */
data class PurchaseInfo(val products: List<String>, val state: PurchaseInfo.State, val acknowledged: Boolean, val token: String) {
    enum class State { PURCHASED, PENDING, OTHER }
}

/**
 * What to do with purchases Play reported: the products owned from now on, the products waiting for payment and the
 * purchases to acknowledge.
 */
data class PurchaseOutcome(val owned: Set<String>, val acknowledge: List<String>, val pending: Set<String> = emptySet())

/**
 * The policy-critical decisions of [PlayMonetization] as pure logic, so they are tested on the JVM: no Mobile Ads SDK
 * and no ad request before consent allows it ([canInitializeAds], [shouldLoadAds]), none at all once ads are removed,
 * the answer of a purchase query replacing the stored set so refunds disappear and purchases come back after a
 * reinstall ([reconcile]), and only completed purchases counting and being acknowledged, never pending ones
 * ([reconcile], [addBought]). [Purchases] applies them.
 */
object PlayRules {
    /** True if the Mobile Ads SDK may be initialized now: consent allows ads, ads are not removed, not started yet. */
    fun canInitializeAds(consentAllows: Boolean, adsRemoved: Boolean, started: Boolean, closed: Boolean) =
        consentAllows && !adsRemoved && !started && !closed

    /** True if ads may be requested now: the SDK is ready, consent allows ads and they are not removed. */
    fun shouldLoadAds(sdkReady: Boolean, consentAllows: Boolean, adsRemoved: Boolean, closed: Boolean) =
        sdkReady && consentAllows && !adsRemoved && !closed

    /** The answer to a query of everything owned (pending purchases included): it replaces the stored set. */
    fun reconcile(purchases: List<PurchaseInfo>): PurchaseOutcome {
        val bought = purchases.filter { it.state == PurchaseInfo.State.PURCHASED }
        val owned = bought.flatMap { it.products }.toSet()
        val pending = purchases.filter { it.state == PurchaseInfo.State.PENDING }.flatMap { it.products }.toSet() - owned
        return PurchaseOutcome(owned, bought.filter { !it.acknowledged }.map { it.token }, pending)
    }

    /**
     * A purchase flow that just finished or a pending purchase that changed while the app runs: completed purchases
     * join what is [owned], new pending ones join [pending], and a completed one leaves [pending].
     */
    fun addBought(owned: Set<String>, pending: Set<String>, purchases: List<PurchaseInfo>): PurchaseOutcome {
        val update = reconcile(purchases)
        val nowOwned = owned + update.owned
        return PurchaseOutcome(nowOwned, update.acknowledge, pending + update.pending - nowOwned)
    }
}
