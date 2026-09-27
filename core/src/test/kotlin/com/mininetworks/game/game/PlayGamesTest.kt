package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/TOP100.md C2, C3: which scores go to which leaderboard, and which achievements Play Games still has to hear of. */
@OptIn(DebugApi::class)
class PlayGamesTest {

    /** A game in [scenario]/[mode] (or the daily [daily]) that delivered [packets] and then ended in game over. */
    private fun lost(packets: Int, scenario: Scenario = Scenarios.RIVER_TOWN, mode: GameMode = GameMode.NORMAL, daily: DailyChallenge? = null): World {
        val w = World(scenario, cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false, mode = mode, daily = daily)
        w.incidentsEnabled = false
        w.grant(50)
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
        var guard = 0
        while (!w.gameOver && guard++ < 60 * 180) {
            if (w.rewardOffer != null) w.chooseReward(0)
            w.update(STEP)
        }
        return w
    }

    @Test
    fun everySceneryAndTheDailyChallengeHaveABoard() {
        assertEquals(Scenarios.all.size + 1, Leaderboards.all.size)
        assertEquals(Leaderboards.all.size, Leaderboards.all.toSet().size)
        for (s in Scenarios.all) assertTrue(Leaderboards.scenery(s.id) in Leaderboards.all)
        assertTrue(Leaderboards.DAILY in Leaderboards.all)
    }

    @Test
    fun aNormalGameOverGoesToItsSceneryBoard() {
        for (s in listOf(Scenarios.RIVER_TOWN, Scenarios.ISLAND)) {
            val w = lost(4, s)
            assertTrue(w.gameOver)
            assertEquals(LeaderboardScore(Leaderboards.scenery(s.id), w.delivered.toLong()), Leaderboards.forGameOver(w, NOON))
        }
    }

    @Test
    fun theDailyChallengeGoesToTheDailyBoardOnlyOnItsDay() {
        val c = DailyChallenge.at(NOON)
        val w = lost(5, c.scenario, daily = c)
        assertTrue(w.gameOver)
        assertEquals(LeaderboardScore(Leaderboards.DAILY, w.delivered.toLong(), "day${c.day}"), Leaderboards.forGameOver(w, NOON))
        assertNull("finished after its UTC day: no score", Leaderboards.forGameOver(w, NOON + DAY))
    }

    @Test
    fun endlessCreativeTutorialRunningAndEmptyGamesSubmitNothing() {
        assertNull(Leaderboards.forGameOver(lost(0), NOON))
        val running = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false)
        assertNull(Leaderboards.forGameOver(running, NOON))
        // Endless and creative never end; even if they did, they would not count.
        for (mode in listOf(GameMode.ENDLESS, GameMode.CREATIVE)) {
            val w = lost(3, mode = mode)
            assertNull(mode.name, Leaderboards.forGameOver(w, NOON))
        }
        val tutorial = Tutorial.start().world
        assertNull(Leaderboards.forGameOver(tutorial, NOON))
    }

    @Test
    fun onlyNewlyReachedAchievementsArePending() {
        assertEquals(emptyList<String>(), AchievementSync.pending(PlayerStats(), emptySet()))
        val stats = PlayerStats(delivered = 150, cablesLaid = 12, gamesFinished = 1)
        val reached = listOf("delivered_1", "delivered_100", "cables_10", "games_1")
        assertEquals("in list order, also those reached before signing in", reached, AchievementSync.pending(stats, emptySet()))
        assertEquals(listOf("cables_10", "games_1"), AchievementSync.pending(stats, setOf("delivered_1", "delivered_100")))
        assertEquals(emptyList<String>(), AchievementSync.pending(stats, reached.toSet()))
        // A synced id that is no longer reached (e.g. local data reset) is not sent again and does no harm.
        assertEquals(emptyList<String>(), AchievementSync.pending(PlayerStats(), reached.toSet()))
    }

    private companion object {
        const val STEP = 1f / 60f
        const val DAY = 24L * 60 * 60 * 1000
        /** 2026-09-27 12:00 UTC. */
        const val NOON = 20_358L * DAY + DAY / 2
    }
}
