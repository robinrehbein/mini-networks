package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two rewarded extras of docs/PLAN.md 5.1: go on once after game over, +1 router on the week screen. */
@OptIn(DebugApi::class)
class SecondChanceTest {

    private val step = 1f / 60f

    /** A lonely PC far from its mail server: it overloads and ends the game. */
    private fun lostWorld(): World {
        val w = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false)
        w.incidentsEnabled = false
        w.addServer(Service.MAIL, 12, 1)
        val pc = w.addClient(Device.PC, 1, 1)
        repeat(World.Tuning.MAX_PENDING) { pc.pending.addLast(Service.MAIL) }
        var guard = 0
        while (!w.gameOver) {
            if (w.rewardOffer != null) w.chooseReward(0)
            w.update(step)
            check(++guard < 60 * 120) { "no game over within two minutes" }
        }
        return w
    }

    private fun untilOffer(w: World): RewardOffer {
        var guard = 0
        while (w.rewardOffer == null) {
            w.update(step)
            check(++guard < 60 * 60) { "no offer within a minute" }
        }
        return w.rewardOffer!!
    }

    @Test
    fun continueResetsRingsOnceAndResumes() {
        val w = lostWorld()
        assertTrue(w.canContinue)
        val failed = w.failedNode!!
        val pending = failed.pending.size
        val time = w.time

        assertTrue(w.continueAfterGameOver())
        assertFalse(w.gameOver)
        assertNull(w.failedNode)
        assertTrue(w.continued)
        assertFalse(w.canContinue)
        assertTrue("every ring is empty", w.nodes.all { it.overload == 0f })
        assertEquals("waiting requests stay", pending, failed.pending.size)
        w.update(step)
        assertTrue("the simulation runs again", w.time > time)
        assertTrue(failed.overload > 0f)

        var guard = 0
        while (!w.gameOver) {
            if (w.rewardOffer != null) w.chooseReward(0)
            w.update(step)
            check(++guard < 60 * 120) { "no second game over" }
        }
        assertFalse("only once per game", w.canContinue)
        assertFalse(w.continueAfterGameOver())
        assertTrue(w.gameOver)
    }

    @Test
    fun continueNeedsAGameOverAndNeverInAGuidedWorld() {
        val w = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false)
        assertFalse(w.canContinue)
        assertFalse(w.continueAfterGameOver())
        assertFalse(w.continued)
        assertFalse(Tutorial.start().world.canContinue)
    }

    @Test
    fun continuedFlagIsSaved() {
        val w = lostWorld()
        w.continueAfterGameOver()
        val loaded = Save.decode(Save.encode(w))
        assertNotNull(loaded)
        assertTrue(loaded!!.continued)
        assertEquals(w.snapshot(), loaded.snapshot())
    }

    @Test
    fun bonusRouterOncePerOffer() {
        val w = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false)
        w.addServer(Service.MAIL, 1, 1)
        assertFalse("no offer open", w.claimBonusRouter())
        val offer = untilOffer(w)
        val routers = w.routersAvailable
        assertFalse(offer.bonusClaimed)
        assertTrue(w.claimBonusRouter())
        assertEquals(routers + Rewards.BONUS_ROUTERS, w.routersAvailable)
        assertTrue(offer.bonusClaimed)
        assertFalse("once per week", w.claimBonusRouter())
        assertEquals(routers + Rewards.BONUS_ROUTERS, w.routersAvailable)
        assertEquals("the offer stays open for the real choice", offer, w.rewardOffer)

        val loaded = Save.decode(Save.encode(w))!!
        assertTrue("the claim is saved", loaded.rewardOffer!!.bonusClaimed)
        assertFalse(loaded.claimBonusRouter())

        assertTrue(w.chooseReward(0))
        w.update(step)
        assertFalse(w.claimBonusRouter())
    }

    @Test
    fun nextWeekHasAFreshBonus() {
        val w = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false)
        w.incidentsEnabled = false
        w.addServer(Service.MAIL, 1, 1)
        untilOffer(w)
        assertTrue(w.claimBonusRouter())
        w.chooseReward(0)
        w.advanceToNextWeek()
        val next = w.rewardOffer!!
        assertFalse(next.bonusClaimed)
        assertTrue(w.claimBonusRouter())
    }
}
