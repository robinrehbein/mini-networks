package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every daily challenge can be finished (docs/TOP100.md C1): the balanced [GreedyBot] reaches
 * [DailyChallenge.STREAK_PACKETS] with a margin of [MARGIN]× on every [DailyRule] × [DailyChallenge.SCENARIOS] pair,
 * on the real days' seeds. A tuning change that makes one pairing impossible would break every player's streak on
 * that day, so this runs with every build like [BalancingTest].
 */
class DailyBalancingTest {

    @Test
    fun theDaysCoverEveryRuleOnEveryScenery() {
        val pairs = runs.map { (c, _) -> c.rule to c.scenario.id }.toSet()
        assertEquals(DailyRule.entries.size * DailyChallenge.SCENARIOS.size, pairs.size)
    }

    @Test
    fun theBotFinishesEveryDailyChallengeWithMargin() {
        val target = DailyChallenge.STREAK_PACKETS * MARGIN
        val short = runs.filter { (_, run) -> run.delivered < target }
        assertTrue(
            "daily challenges below $target packets (${MARGIN}× the streak target ${DailyChallenge.STREAK_PACKETS}): " +
                short.joinToString { (c, r) -> "day ${c.day} ${c.scenario.id}+${c.rule}: ${r.delivered} packets, " +
                    "%.1f weeks (%s)".format(r.weeks, r.cause) },
            short.isEmpty(),
        )
    }

    companion object {
        /** The bot must deliver this many times [DailyChallenge.STREAK_PACKETS] (the weakest pairing reached ~98 packets). */
        const val MARGIN = 2
        /** First day of the checked range; [DAYS] consecutive days give every rule × scenery pair twice. */
        const val FIRST_DAY = 20_720L
        val DAYS = 2L * DailyRule.entries.size * DailyChallenge.SCENARIOS.size

        val runs: List<Pair<DailyChallenge, BotRun>> by lazy {
            (FIRST_DAY until FIRST_DAY + DAYS).toList().parallelStream().map { day ->
                val c = DailyChallenge.of(day)
                c to BotRunner.play(c.scenario, c.seed, BalancingTest.REPORT_MAX_WEEKS, daily = c,
                    stopAtPackets = DailyChallenge.STREAK_PACKETS * MARGIN)
            }.toList()
        }
    }
}
