package com.mininetworks.game.game

import kotlin.random.Random

/**
 * Rewards the player picks from at each week change (Mini Metro style).
 *
 * The design (docs/PLAN.md 3.4) also lists a cache node; it is not part of the pool until its rules exist.
 * New entries go at the end: the offer draw shuffles the eligible list in this order.
 */
enum class Reward {
    /** +[Rewards.BUDGET] budget. */
    BUDGET,

    /** +[Rewards.ROUTERS] routers. */
    ROUTERS,

    /** A voucher for one free server hardware tier upgrade. Only offered while some server can still be upgraded. */
    SERVER_VOUCHER,

    /** +[Rewards.ACCESS_POINTS] WLAN access points. Offered from the week WLAN is invented ([RadioType.WLAN]). */
    ACCESS_POINT,

    /** +[Rewards.CELL_TOWERS] cell towers. Offered from [World.Tuning.CELL_TOWER_REWARD_WEEK]; the first tower comes with [RadioType.CELL]. */
    CELL_TOWER,
}

/**
 * The open choice of one week: exactly [OFFERED][Rewards.OFFERED] distinct rewards. [bonusClaimed] turns true once the
 * extra router of this week was taken ([World.claimBonusRouter]).
 */
class RewardOffer(val week: Int, val choices: List<Reward>) {
    var bonusClaimed = false; internal set
}

/** Reward amounts and the deterministic offer draw. */
object Rewards {
    const val BUDGET = 16
    const val ROUTERS = 2
    const val ACCESS_POINTS = 1
    const val CELL_TOWERS = 1
    const val OFFERED = 2
    /** Routers added by the optional extra on the week screen (a rewarded video, or free without ads). */
    const val BONUS_ROUTERS = 1

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
