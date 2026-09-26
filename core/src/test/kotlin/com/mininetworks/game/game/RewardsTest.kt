package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(DebugApi::class)
class RewardsTest {

    private val step = 1f / 60f

    private fun world(seed: Long = 1L) = World(cols = 16, rows = 10, seed = seed, spawnInitialNodes = false)

    /** Simulates until the first week change opens an offer. */
    private fun untilOffer(w: World): RewardOffer {
        var guard = 0
        while (w.rewardOffer == null) {
            w.update(step)
            check(++guard < 60 * 60) { "no offer within a minute" }
        }
        return w.rewardOffer!!
    }

    @Test
    fun offerHasTwoDistinctEligibleRewards() {
        for (seed in 0L until 50L) for (week in 2..12) {
            val offer = Rewards.offer(seed, week, Reward.entries)
            assertEquals(Rewards.OFFERED, offer.size)
            assertEquals(offer.size, offer.toSet().size)
        }
        val noServers = Rewards.offer(9L, 3, listOf(Reward.BUDGET, Reward.ROUTERS))
        assertEquals(setOf(Reward.BUDGET, Reward.ROUTERS), noServers.toSet())
    }

    @Test
    fun offerIsDeterministicPerSeedAndWeekAndVaries() {
        assertEquals(Rewards.offer(42L, 5, Reward.entries), Rewards.offer(42L, 5, Reward.entries))
        val seen = (0L until 30L).map { Rewards.offer(it, 2, Reward.entries).toSet() }.toSet()
        assertTrue("all three pairs appear across seeds: $seen", seen.size == 3)
    }

    @Test
    fun weekChangeOpensOfferAndPausesSimulation() {
        val w = world()
        w.addServer(Service.MAIL, 1, 1)
        val offer = untilOffer(w)
        assertEquals(2, w.week)
        assertEquals(2, offer.week)
        assertEquals(Rewards.offer(w.seed, 2, w.eligibleRewards()), offer.choices)
        val time = w.time
        repeat(600) { w.update(step) }
        assertEquals("paused while the choice is open", time, w.time)
        assertTrue(w.chooseReward(0))
        assertNull(w.rewardOffer)
        w.update(step)
        assertTrue(w.time > time)
    }

    @Test
    fun sameSeedGivesSameOffers() {
        val a = world(5L).also { it.addServer(Service.MAIL, 1, 1) }
        val b = world(5L).also { it.addServer(Service.MAIL, 1, 1) }
        assertEquals(untilOffer(a).choices, untilOffer(b).choices)
    }

    @Test
    fun budgetAndRouterRewardsApply() {
        for (reward in listOf(Reward.BUDGET, Reward.ROUTERS)) {
            val seed = (0L until 100L).first { reward in Rewards.offer(it, 2, listOf(Reward.BUDGET, Reward.ROUTERS, Reward.SERVER_VOUCHER)) }
            val w = world(seed).also { it.addServer(Service.MAIL, 1, 1) }
            val offer = untilOffer(w)
            val budget = w.budget
            val routers = w.routersAvailable
            assertTrue(w.chooseReward(offer.choices.indexOf(reward)))
            when (reward) {
                Reward.BUDGET -> assertEquals(budget + Rewards.BUDGET, w.budget)
                else -> assertEquals(routers + Rewards.ROUTERS, w.routersAvailable)
            }
        }
    }

    @Test
    fun weekChangeGivesNothingAutomatically() {
        val w = world()
        w.addServer(Service.MAIL, 1, 1)
        val budget = w.budget
        val routers = w.routersAvailable
        untilOffer(w)
        assertEquals(budget, w.budget)
        assertEquals(routers, w.routersAvailable)
    }

    @Test
    fun serverVoucherUpgradesForFree() {
        val seed = (0L until 100L).first { Reward.SERVER_VOUCHER in Rewards.offer(it, 2, Reward.entries) }
        val w = world(seed)
        val server = w.addServer(Service.MAIL, 1, 1)
        val offer = untilOffer(w)
        assertTrue(w.chooseReward(offer.choices.indexOf(Reward.SERVER_VOUCHER)))
        assertEquals(1, w.serverVouchers)
        w.grant(-w.budget)
        assertNull("voucher covers the cost", w.serverUpgradeError(server))
        assertTrue(w.upgradeServer(server))
        assertEquals(2, server.level)
        assertEquals(0, w.budget)
        assertEquals(0, w.serverVouchers)
        assertNotNull("no voucher and no budget left", w.serverUpgradeError(server))
    }

    @Test
    fun voucherOnlyEligibleWhileAServerCanGrow() {
        val w = world()
        assertFalse(Reward.SERVER_VOUCHER in w.eligibleRewards())
        val server = w.addServer(Service.MAIL, 1, 1)
        assertTrue(Reward.SERVER_VOUCHER in w.eligibleRewards())
        w.grant(100)
        while (w.upgradeServer(server)) Unit
        assertEquals(World.Tuning.MAX_SERVER_LEVEL, server.level)
        assertFalse(Reward.SERVER_VOUCHER in w.eligibleRewards())
        val offer = untilOffer(w)
        assertEquals(setOf(Reward.BUDGET, Reward.ROUTERS), offer.choices.toSet())
    }

    @Test
    fun chooseRewardWithoutOfferOrBadIndexIsRejected() {
        val w = world()
        assertFalse(w.chooseReward(0))
        w.addServer(Service.MAIL, 1, 1)
        untilOffer(w)
        assertFalse(w.chooseReward(Rewards.OFFERED))
        assertNotNull(w.rewardOffer)
    }

    @Test
    fun advanceToNextWeekOpensOfferRightAway() {
        val w = world()
        w.addServer(Service.MAIL, 1, 1)
        w.advanceToNextWeek()
        assertEquals(2, w.week)
        assertEquals(World.Tuning.WEEK_SECONDS, w.time)
        assertEquals(untilOffer(world().also { it.addServer(Service.MAIL, 1, 1) }).choices, w.rewardOffer!!.choices)
    }

    @Test
    fun unlockMessageStillShownAtWeekChange() {
        val w = world()
        w.addServer(Service.MAIL, 1, 1)
        untilOffer(w)
        val msg = w.lastEvent
        assertNotNull(msg)
        assertTrue(msg!!, msg.contains(CableType.DSL.label))
        assertNotEquals(-1, msg.indexOf(Device.LAPTOP.label))
    }
}
