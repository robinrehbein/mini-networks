package com.mininetworks.game.game

import kotlin.random.Random

/**
 * Rewards the player picks from at each week change (Mini Metro style).
 *
 * The design (docs/PLAN.md 3.4) also lists a WLAN access point and a cache node. Neither item exists in the game yet
 * (WLAN comes with P2.1, caching is unplanned), so they are not part of the pool until their rules are implemented.
 */
enum class Reward {
    /** +[Rewards.BUDGET] budget. */
    BUDGET,

    /** +[Rewards.ROUTERS] routers. */
    ROUTERS,

    /** A voucher for one free server hardware tier upgrade. Only offered while some server can still be upgraded. */
    SERVER_VOUCHER,
}

/** The open choice of one week: exactly [OFFERED][Rewards.OFFERED] distinct rewards. */
class RewardOffer(val week: Int, val choices: List<Reward>)

/** Reward amounts and the deterministic offer draw. */
object Rewards {
    const val BUDGET = 16
    const val ROUTERS = 2
    const val OFFERED = 2

    /**
     * The rewards offered in [week] of a game with [seed]: [OFFERED] distinct entries from [eligible], drawn from a
     * random stream derived only from seed and week, so the offer does not depend on how the week was played.
     */
    fun offer(seed: Long, week: Int, eligible: List<Reward>): List<Reward> {
        val pool = eligible.distinct()
        require(pool.size >= OFFERED) { "need at least $OFFERED eligible rewards" }
        return pool.shuffled(Random(seed * 1_000_003L + week * 7_919L)).take(OFFERED)
    }
}
