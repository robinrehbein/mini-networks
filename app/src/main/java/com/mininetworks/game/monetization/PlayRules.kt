package com.mininetworks.game.monetization

/** A purchase as Play reports it, reduced to what [PlayRules] decides on. */
data class PurchaseInfo(val products: List<String>, val state: PurchaseInfo.State, val acknowledged: Boolean, val token: String) {
    enum class State { PURCHASED, PENDING, OTHER }
}

/** What to do with purchases Play reported: the products owned from now on and the purchases to acknowledge. */
data class PurchaseOutcome(val owned: Set<String>, val acknowledge: List<String>)

/**
 * The policy-critical decisions of [PlayMonetization] as pure logic, so they are tested on the JVM: no Mobile Ads SDK
 * and no ad request before consent allows it ([canInitializeAds], [shouldLoadAds]), none at all once ads are removed,
 * the answer of a purchase query replacing the stored set so refunds disappear ([reconcile]), and only completed
 * purchases counting and being acknowledged, never pending ones ([reconcile], [addBought]).
 */
object PlayRules {
    /** True if the Mobile Ads SDK may be initialized now: consent allows ads, ads are not removed, not started yet. */
    fun canInitializeAds(consentAllows: Boolean, adsRemoved: Boolean, started: Boolean, closed: Boolean) =
        consentAllows && !adsRemoved && !started && !closed

    /** True if ads may be requested now: the SDK is ready, consent allows ads and they are not removed. */
    fun shouldLoadAds(sdkReady: Boolean, consentAllows: Boolean, adsRemoved: Boolean, closed: Boolean) =
        sdkReady && consentAllows && !adsRemoved && !closed

    /** The answer to a query of everything owned: it replaces the stored set. */
    fun reconcile(purchases: List<PurchaseInfo>): PurchaseOutcome {
        val bought = purchases.filter { it.state == PurchaseInfo.State.PURCHASED }
        return PurchaseOutcome(bought.flatMap { it.products }.toSet(), bought.filter { !it.acknowledged }.map { it.token })
    }

    /** A purchase flow that just finished: completed purchases join what is [owned]. */
    fun addBought(owned: Set<String>, purchases: List<PurchaseInfo>): PurchaseOutcome {
        val bought = reconcile(purchases)
        return PurchaseOutcome(owned + bought.owned, bought.acknowledge)
    }
}
