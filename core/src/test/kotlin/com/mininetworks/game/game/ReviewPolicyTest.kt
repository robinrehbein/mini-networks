package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/TOP100.md D1: ask for a rating after a positive moment, at most every 30 days, never after a frustrating game. */
class ReviewPolicyTest {

    private val good = FinishedGame(score = 120, previousBest = 100, weeks = 8)
    private val ordinary = FinishedGame(score = 80, previousBest = 100, weeks = 6)

    private fun play(state: ReviewState, vararg games: FinishedGame, now: Long = T0): List<Boolean> {
        var s = state
        return games.map { g -> ReviewPolicy.onGameOver(s, g, now).also { s = it.state }.ask }
    }

    @Test
    fun theThirdFinishedGameAsks() {
        assertEquals(listOf(false, false, true), play(ReviewState(), ordinary, ordinary, ordinary))
        val d = ReviewPolicy.onGameOver(ReviewState(finishedGames = 2), ordinary, T0)
        assertTrue(d.ask)
        assertEquals(ReviewState(finishedGames = 3, lastAskedAt = T0), d.state)
    }

    @Test
    fun aNewRecordAsksEvenEarly() {
        assertTrue(ReviewPolicy.onGameOver(ReviewState(), good, T0).ask)
        assertTrue(ReviewPolicy.newRecord(good))
        assertFalse("the very first game sets no record", ReviewPolicy.newRecord(FinishedGame(50, previousBest = 0, weeks = 5)))
        assertFalse(ReviewPolicy.onGameOver(ReviewState(), FinishedGame(50, previousBest = 0, weeks = 5), T0).ask)
        assertFalse("a tie is no record", ReviewPolicy.newRecord(FinishedGame(100, 100, 6)))
    }

    @Test
    fun atMostOnceInThirtyDays() {
        val asked = ReviewState(finishedGames = 7, lastAskedAt = T0)
        assertFalse(ReviewPolicy.onGameOver(asked, good, T0 + 29 * DAY).ask)
        assertFalse(ReviewPolicy.onGameOver(asked, good, T0 + 30 * DAY - 1).ask)
        assertTrue(ReviewPolicy.onGameOver(asked, good, T0 + 30 * DAY).ask)
        assertFalse("a clock set back never counts as later", ReviewPolicy.onGameOver(asked, good, T0 - 400 * DAY).ask)
        // After the first request only records ask, not every further game.
        assertFalse(ReviewPolicy.onGameOver(asked, ordinary, T0 + 90 * DAY).ask)
        // Two records the same day: only the first asks.
        assertEquals(listOf(true, false), play(ReviewState(finishedGames = 5), good, good.copy(score = 150, previousBest = 120)))
    }

    @Test
    fun neverAfterAFrustratingGameOver() {
        val quickLoss = FinishedGame(score = 150, previousBest = 100, weeks = ReviewPolicy.MIN_WEEKS - 1)
        val farBelow = FinishedGame(score = 40, previousBest = 100, weeks = 9)
        val lostAgain = good.copy(continued = true)
        for (g in listOf(quickLoss, farBelow, lostAgain)) {
            assertTrue("$g", ReviewPolicy.frustrating(g))
            assertFalse("$g", ReviewPolicy.onGameOver(ReviewState(finishedGames = 2), g, T0).ask)
        }
        assertEquals("the game after its second chance was counted before", 4, ReviewPolicy.onGameOver(ReviewState(finishedGames = 4), lostAgain, T0).state.finishedGames)
        assertFalse(ReviewPolicy.frustrating(good))
        assertFalse(ReviewPolicy.frustrating(ordinary))
        assertFalse("half the best exactly is fine", ReviewPolicy.frustrating(FinishedGame(50, 100, 5)))
    }

    @Test
    fun aFrustratingThirdGameMovesTheRequestToTheNextGoodOne() {
        val quickLoss = ordinary.copy(weeks = 1)
        assertEquals(listOf(false, false, false, true), play(ReviewState(), ordinary, ordinary, quickLoss, ordinary))
    }

    @Test
    fun everyGameIsCounted() {
        var s = ReviewState()
        repeat(10) { s = ReviewPolicy.onGameOver(s, ordinary.copy(weeks = 1), T0).state }
        assertEquals(10, s.finishedGames)
        assertEquals(null, s.lastAskedAt)
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
        const val T0 = 20_358L * DAY
    }
}
