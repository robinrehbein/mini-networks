package com.mininetworks.game.game

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Difficulty guards and balancing report with the [GreedyBot] (docs/BALANCING.md, docs/TOP100.md G1 and G2).
 *
 * The guards run with every build: the first scenery lasts 8–14 weeks in the median of [SEEDS] seeds and every later
 * scenery is measurably harder (G1), no one-sided bot comes close to the balanced one (G2), and no scenery can be lost
 * in its first weeks. The seeds run in parallel and the balanced games are shared between the tests. The report
 * rewrites the tables in docs/BALANCING.md: `BALANCING_REPORT=1 ./gradlew :core:test --tests '*BalancingTest*'`.
 */
class BalancingTest {

    @Test
    fun botSurvivesTheFirstSceneryLongEnough() {
        for (run in balanced.filter { it.scenario == Scenarios.RIVER_TOWN.id && it.seed in GUARD_SEEDS }) {
            assertTrue("seed ${run.seed}: the bot lasted only ${run.weeks} weeks (${run.cause})", run.weeks >= GUARD_WEEKS)
        }
    }

    /** Paid sceneries must be fair too: no scenery may be lost within its first [GUARD_MIN_WEEKS] weeks. */
    @Test
    fun noSceneryIsLostInTheFirstWeeks() {
        for (run in balanced.filter { it.seed in GUARD_SEEDS }) {
            assertTrue("${run.scenario} seed ${run.seed}: lost after ${run.weeks} weeks (${run.cause})", run.weeks >= GUARD_MIN_WEEKS)
        }
    }

    /** G1: the balanced bot lasts [MIN_MEDIAN_WEEKS]–[MAX_MEDIAN_WEEKS] weeks on the first scenery, median of [SEEDS] seeds. */
    @Test
    fun firstSceneryLastsEightToFourteenWeeks() {
        val median = median(weeksOf(Scenarios.RIVER_TOWN))
        assertTrue("median $median weeks on the first scenery", median in MIN_MEDIAN_WEEKS..MAX_MEDIAN_WEEKS)
    }

    /** G1: in menu order, every scenery's median is at least [HARDER_BY] weeks below the one before. */
    @Test
    fun everySceneryIsHarderThanTheOneBefore() {
        for ((easier, harder) in Scenarios.all.zipWithNext()) {
            val a = median(weeksOf(easier))
            val b = median(weeksOf(harder))
            assertTrue("${harder.id} ($b weeks) is not measurably harder than ${easier.id} ($a weeks)", b <= a - HARDER_BY)
        }
    }

    /**
     * G2, no dominant strategy: on the first scenery and on the one where every cable and radio is there from the start,
     * every one-sided bot's median score (delivered packets) is at least [ONE_SIDED_GAP] below the balanced bot's.
     */
    @Test
    fun oneSidedBotsScoreClearlyLower() {
        for (s in G2_SCENERIES) {
            val best = median(scoresOf(balanced, s))
            for (bot in ONE_SIDED) {
                val score = median(scoresOf(oneSided, s, bot))
                assertTrue("${bot.id} on ${s.id}: median $score vs balanced $best", score <= best * (1f - ONE_SIDED_GAP))
            }
        }
    }

    /**
     * No progression wall: every score unlock is reachable in an ordinary good run. Its target lies at or below the
     * balanced bot's median score in the scenery before it, so at least half of the bot's games get there
     * (docs/BALANCING.md, "Freischalt-Ziele").
     */
    @Test
    fun scoreUnlocksAreWithinReachOfAMedianRun() {
        for (s in Scenarios.all) {
            val unlock = s.unlock as? Unlock.Score ?: continue
            val before = Scenarios.byId(unlock.after)!!
            val scores = scoresOf(balanced, before)
            val median = median(scores)
            assertTrue("${s.id}: target ${unlock.packets} above the median $median of ${before.id}", unlock.packets <= median)
            val reached = scores.count { it >= unlock.packets }
            assertTrue("${s.id}: only $reached of ${scores.size} bot games reach ${unlock.packets}", reached * 2 >= scores.size)
        }
    }

    @Test
    fun report() {
        assumeTrue("set BALANCING_REPORT=1 to rewrite docs/BALANCING.md", System.getProperty("balancing.report") != null)
        val seeds = System.getProperty("balancing.seeds")?.toLongOrNull() ?: SEEDS
        val runs = if (seeds == SEEDS) balanced else play(Scenarios.all, listOf(BotStrategy.BALANCED), seeds)
        val bots = if (seeds == SEEDS) oneSided + play(Scenarios.all - G2_SCENERIES.toSet(), ONE_SIDED, seeds)
        else play(Scenarios.all, ONE_SIDED, seeds)
        val table = buildString {
            appendLine("| Szenerie | Wochen (Median) | Wochen (Min–Max) | Pakete (Median) | Pakete (Min–Max) | bis Woche $REPORT_MAX_WEEKS | häufigstes Ende |")
            appendLine("|---|---|---|---|---|---|---|")
            for (s in Scenarios.all) {
                val mine = runs.filter { it.scenario == s.id }
                val weeks = mine.map { it.weeks }.sorted()
                val packets = mine.map { it.delivered.toFloat() }.sorted()
                val cause = mine.filter { !it.survived }.groupingBy { it.cause }.eachCount().maxByOrNull { it.value }
                appendLine(
                    "| ${s.id} | ${fmt(median(weeks))} | ${fmt(weeks.first())}–${fmt(weeks.last())} | " +
                        "${median(packets).toInt()} | ${packets.first().toInt()}–${packets.last().toInt()} | " +
                        "${mine.count { it.survived }}/${mine.size} | ${cause?.let { "${it.key} (${it.value}×)" } ?: "–"} |",
                )
            }
        }
        val strategies = buildString {
            append("| Szenerie | ausgewogen |")
            for (b in ONE_SIDED) append(" ${b.id} |")
            appendLine()
            appendLine("|---|---|" + "---|".repeat(ONE_SIDED.size))
            for (s in Scenarios.all) {
                val best = median(scoresOf(runs, s))
                append("| ${s.id} | ${best.toInt()} (${fmt(median(weeksOf(runs, s)))} W.) |")
                for (b in ONE_SIDED) {
                    val score = median(scoresOf(bots, s, b))
                    append(" ${score.toInt()} (${fmt(median(weeksOf(bots, s, b)))} W., ${percentBelow(score, best)}) |")
                }
                appendLine()
            }
        }
        val guard = buildString {
            appendLine("| Seed | Wochen | Pakete | Ende |")
            appendLine("|---|---|---|---|")
            for (r in runs.filter { it.scenario == Scenarios.RIVER_TOWN.id && it.seed in GUARD_SEEDS }) {
                appendLine("| ${r.seed} | ${fmt(r.weeks)} | ${r.delivered} | ${r.cause.ifEmpty { "–" }} |")
            }
        }
        println(table)
        println(strategies)
        println(guard)
        val file = File(requireNotNull(System.getProperty("balancing.file")))
        var text = file.readText()
        text = replaceBetween(text, "summary", table)
        text = replaceBetween(text, "strategies", strategies)
        text = replaceBetween(text, "guard", guard)
        file.writeText(text)
    }

    /** "−81 %": how far [score] lies below [best]. */
    private fun percentBelow(score: Float, best: Float) =
        if (best <= 0f) "–" else "−${((1f - score / best) * 100f).roundToInt()} %"

    /** The lines between the markers `<!-- name:start -->` and `<!-- name:end -->` in [text] replaced by [content]. */
    private fun replaceBetween(text: String, name: String, content: String): String {
        val start = "<!-- $name:start -->"
        val end = "<!-- $name:end -->"
        val a = text.indexOf(start)
        val b = text.indexOf(end)
        require(a >= 0 && b > a) { "markers for $name missing in docs/BALANCING.md" }
        return text.substring(0, a + start.length) + "\n" + content + text.substring(b)
    }

    private fun fmt(f: Float) = String.format(Locale.GERMAN, "%.1f", f)

    companion object {
        /** Seeds per scenery and bot for G1, G2 and the report (docs/TOP100.md: at least 20). */
        const val SEEDS = 20L
        /** G1: median weeks of the balanced bot on the first scenery. */
        const val MIN_MEDIAN_WEEKS = 8f
        const val MAX_MEDIAN_WEEKS = 14f
        /** G1, "measurably harder": each scenery's median lies at least this many weeks below the one before it. */
        const val HARDER_BY = 0.5f
        /** G2, "clearly worse": a one-sided bot's median score lies at least this share below the balanced bot's. */
        const val ONE_SIDED_GAP = 0.2f
        /** G2 is checked on the first scenery and on the one that has every cable and radio from the start. */
        val G2_SCENERIES = listOf(Scenarios.RIVER_TOWN, Scenarios.FUTURE)
        val ONE_SIDED = BotStrategy.all - BotStrategy.BALANCED
        /** The bot must last this many weeks on the first scenery with every guard seed. */
        const val GUARD_WEEKS = 4f
        val GUARD_SEEDS = listOf(1L, 2L, 3L)
        /** Every scenery must last this many weeks with every guard seed. */
        const val GUARD_MIN_WEEKS = 2f
        const val REPORT_MAX_WEEKS = 25

        /** Balanced bot, every scenery, [SEEDS] seeds; played once per test run and shared. */
        val balanced by lazy { play(Scenarios.all, listOf(BotStrategy.BALANCED), SEEDS) }

        /** The one-sided bots on the [G2_SCENERIES]. */
        val oneSided by lazy { play(G2_SCENERIES, ONE_SIDED, SEEDS) }

        fun play(sceneries: List<Scenario>, bots: List<BotStrategy>, seeds: Long): List<BotRun> =
            sceneries.flatMap { s -> bots.flatMap { b -> (1L..seeds).map { Triple(s, b, it) } } }
                .parallelStream().map { (s, b, seed) -> BotRunner.play(s, seed, REPORT_MAX_WEEKS, b) }.toList()

        fun weeksOf(s: Scenario) = weeksOf(balanced, s)

        fun weeksOf(runs: List<BotRun>, s: Scenario, bot: BotStrategy = BotStrategy.BALANCED) =
            runs.filter { it.scenario == s.id && it.bot == bot.id }.map { it.weeks }

        fun scoresOf(runs: List<BotRun>, s: Scenario, bot: BotStrategy = BotStrategy.BALANCED) =
            runs.filter { it.scenario == s.id && it.bot == bot.id }.map { it.delivered.toFloat() }

        fun median(values: List<Float>): Float {
            require(values.isNotEmpty()) { "no runs" }
            val sorted = values.sorted()
            return if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
        }
    }
}
