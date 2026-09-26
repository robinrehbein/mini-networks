package com.mininetworks.game.monetization

import com.mininetworks.game.game.Scenarios

/**
 * Paid content the player owns (docs/PLAN.md 5.1), the part of [Monetization] the scenery picker needs. Read on the
 * game thread every frame, so implementations answer from memory and pick up finished purchases on their own.
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

        /** Play product id of the one-time purchase that removes interstitials. */
        const val REMOVE_ADS = "remove_ads"

        /** Every one-time product the game sells: "remove ads", each purchasable scenery and the pack. */
        val PRODUCTS: List<String> =
            listOf(REMOVE_ADS, SCENERY_PACK) + Scenarios.all.filter { it.purchasable }.map { sceneryProduct(it.id) }

        /** True if the [owned] product ids include scenery [sceneryId], on its own or with the pack. */
        fun ownsScenery(owned: Set<String>, sceneryId: String) = SCENERY_PACK in owned || sceneryProduct(sceneryId) in owned
    }
}
