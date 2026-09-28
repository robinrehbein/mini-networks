package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fair results (docs/TOP100.md E, C1, C3): extras from a rewarded video or "remove ads" (going on after a game over,
 * the weekly bonus router) never reach a leaderboard or the daily challenge, which promises the same game for everyone.
 */
@OptIn(DebugApi::class)
class FairPlayTest {

    private fun world(daily: DailyChallenge? = null, scenario: Scenario = daily?.scenario ?: Scenarios.RIVER_TOWN): World {
        val w = World(scenario, cols = 16, rows = 10, seed = daily?.seed ?: 1L, spawnInitialNodes = false, daily = daily)
        w.incidentsEnabled = false
        w.grant(50)
        return w
    }

    /** Delivers [packets], then overloads a lonely phone until the game is over. */
    private fun loseAfter(w: World, packets: Int) {
        val mail = w.addServer(Service.MAIL, 1, 1)
        val pc = w.addClient(Device.PC, 3, 1)
        w.connect(pc, mail, CableType.ISDN)
        while (w.delivered < packets) {
            if (pc.pending.isEmpty()) pc.pending.addLast(Service.MAIL)
            w.update(STEP)
        }
        w.removeCable(w.cables.single())
        val lonely = w.addClient(Device.PHONE, 3, 6)
        w.addServer(Service.CALL, 5, 6)
        repeat(World.Tuning.MAX_PENDING) { lonely.pending.addLast(Service.CALL) }
        runUntilOver(w)
    }

    private fun runUntilOver(w: World) {
        var guard = 0
        while (!w.gameOver && guard++ < 60 * 180) {
            if (w.rewardOffer != null) w.chooseReward(0)
            w.update(STEP)
        }
        assertTrue("game over", w.gameOver)
    }

    @Test
    fun aFairRunIsNotAssistedAndGoesToItsBoard() {
        val w = world()
        loseAfter(w, 4)
        assertFalse(w.assisted)
        assertNotNull(Leaderboards.forGameOver(w, NOON))
    }

    @Test
    fun aContinuedRunIsAssistedAndSubmitsNothing() {
        val w = world()
        loseAfter(w, 4)
        assertTrue(w.continueAfterGameOver())
        assertTrue(w.assisted)
        runUntilOver(w)
        assertNull("a continued run is on no board", Leaderboards.forGameOver(w, NOON))
    }

    @Test
    fun aBonusRouterMakesTheRunAssistedAndItSubmitsNothing() {
        val w = world()
        w.advanceToNextWeek()
        assertNotNull(w.rewardOffer)
        val routers = w.routersAvailable
        assertTrue(w.claimBonusRouter())
        assertEquals(routers + Rewards.BONUS_ROUTERS, w.routersAvailable)
        assertEquals(1, w.bonusRoutersClaimed)
        assertTrue(w.assisted)
        w.chooseReward(0)
        loseAfter(w, 4)
        assertNull("a run with a bonus router is on no board", Leaderboards.forGameOver(w, NOON))
    }

    @Test
    fun theDailyChallengeHasNoBonusRouterAndNoContinue() {
        val c = DailyChallenge.at(NOON)
        val w = world(c)
        assertFalse(w.extrasAllowed)
        w.advanceToNextWeek()
        val routers = w.routersAvailable
        assertFalse("no bonus router in the daily challenge", w.claimBonusRouter())
        assertEquals(routers, w.routersAvailable)
        w.chooseReward(0)
        loseAfter(w, 5)
        assertFalse("no second chance in the daily challenge", w.canContinue)
        assertFalse(w.continueAfterGameOver())
        assertTrue(w.gameOver)
        assertFalse(w.assisted)
        assertEquals(LeaderboardScore(Leaderboards.DAILY, w.delivered.toLong(), Leaderboards.dayTag(c.day)), Leaderboards.forGameOver(w, NOON))
    }

    @Test
    fun anAssistedDailyRunFromAnOldSaveCountsForNoStreak() {
        val c = DailyChallenge.at(NOON)
        assertTrue(c.countsFor(DailyStreak(), DailyChallenge.STREAK_PACKETS, NOON))
        assertFalse(c.countsFor(DailyStreak(), DailyChallenge.STREAK_PACKETS, NOON, assisted = true))
    }

    @Test
    fun theAssistanceIsSavedAndLoaded() {
        val w = world()
        w.advanceToNextWeek()
        assertTrue(w.claimBonusRouter())
        val loaded = Save.decode(Save.encode(w))!!
        assertEquals(1, loaded.bonusRoutersClaimed)
        assertTrue(loaded.assisted)
        // A save from before the count: the bonus taken on its open week screen still counts.
        val old = Save.decode(Save.encode(w.snapshot().copy(bonusRoutersClaimed = 0)))!!
        assertTrue(old.assisted)
        val fresh = Save.decode(Save.encode(world()))!!
        assertFalse(fresh.assisted)
    }

    private companion object {
        const val STEP = 1f / 60f
        const val DAY = 24L * 60 * 60 * 1000
        /** Noon (UTC) of a fixed day. */
        const val NOON = 20_358L * DAY + DAY / 2
    }
}
