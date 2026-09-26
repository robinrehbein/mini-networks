package com.mininetworks.game.monetization

/**
 * Paid content the player owns (docs/PLAN.md 5.1). The monetization package (P3.4) backs this with Google Play
 * Billing; until then [NoEntitlements] owns nothing and cannot sell anything. Read on the game thread every frame,
 * so implementations answer from memory and pick up finished purchases on their own.
 */
interface Entitlements {
    /** True if scenery [sceneryId] was bought on its own or with the [SCENERY_PACK]. */
    fun ownsScenery(sceneryId: String): Boolean

    /** Starts buying scenery [sceneryId]; false if purchases are not available, so the UI can say so. */
    fun purchaseScenery(sceneryId: String): Boolean

    companion object {
        /** Play product id of a single scenery. */
        fun sceneryProduct(sceneryId: String) = "scenery_$sceneryId"

        /** Play product id of the pack with every purchasable scenery. */
        const val SCENERY_PACK = "scenery_pack"
    }
}

/** No purchases: the default until billing exists, and for debug builds and tests. */
object NoEntitlements : Entitlements {
    override fun ownsScenery(sceneryId: String) = false
    override fun purchaseScenery(sceneryId: String) = false
}
