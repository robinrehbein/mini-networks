package com.mininetworks.game.game

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Difficulty guard and balancing report with the [GreedyBot] (docs/BALANCING.md).
 *
 * The guards run with every build and fail if the first scenery suddenly gets much harder or any scenery can be lost
 * in its first weeks. The report plays every
 * scenery for [REPORT_SEEDS] seeds and rewrites the tables in docs/BALANCING.md:
 * `BALANCING_REPORT=1 ./gradlew :core:test --tests '*BalancingTest*'`.
 */
class BalancingTest {

    @Test
    fun botSurvivesTheFirstSceneryLongEnough() {
        for (seed in GUARD_SEEDS) {
            val run = BotRunner.play(Scenarios.RIVER_TOWN, seed, GUARD_WEEKS)
            assertTrue("seed $seed: the bot lasted only ${run.weeks} weeks (${run.cause})", run.survived)
        }
    }

    /** Paid sceneries must be fair too: no scenery may be lost within its first [GUARD_MIN_WEEKS] weeks. */
    @Test
    fun noSceneryIsLostInTheFirstWeeks() {
        val runs = Scenarios.all.flatMap { s -> GUARD_SEEDS.map { s to it } }.parallelStream()
            .map { (s, seed) -> BotRunner.play(s, seed, GUARD_MIN_WEEKS.toInt() + 1) }.toList()
        for (run in runs) {
            assertTrue("${run.scenario} seed ${run.seed}: lost after ${run.weeks} weeks (${run.cause})", run.weeks >= GUARD_MIN_WEEKS)
        }
    }

    @Test
    fun report() {
        assumeTrue("set BALANCING_REPORT=1 to rewrite docs/BALANCING.md", System.getProperty("balancing.report") != null)
        val seeds = System.getProperty("balancing.seeds")?.toLongOrNull() ?: REPORT_SEEDS
        val jobs = Scenarios.all.flatMap { s -> (1L..seeds).map { s to it } }
        val runs = jobs.parallelStream().map { (s, seed) -> BotRunner.play(s, seed, REPORT_MAX_WEEKS) }.toList()
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
        val guard = buildString {
            appendLine("| Seed | Wochen | Pakete | Ende |")
            appendLine("|---|---|---|---|")
            for (r in runs.filter { it.scenario == Scenarios.RIVER_TOWN.id && it.seed in GUARD_SEEDS }) {
                appendLine("| ${r.seed} | ${fmt(r.weeks)} | ${r.delivered} | ${r.cause.ifEmpty { "–" }} |")
            }
        }
        println(table)
        println(guard)
        val file = File(requireNotNull(System.getProperty("balancing.file")))
        file.writeText(replaceBetween(replaceBetween(file.readText(), "summary", table), "guard", guard))
    }

    /** [text] with the lines between the markers `<!-- name:start -->` and `<!-- name:end -->` replaced by [content]. */
    private fun replaceBetween(text: String, name: String, content: String): String {
        val start = "<!-- $name:start -->"
        val end = "<!-- $name:end -->"
        val a = text.indexOf(start)
        val b = text.indexOf(end)
        require(a >= 0 && b > a) { "markers for $name missing in docs/BALANCING.md" }
        return text.substring(0, a + start.length) + "\n" + content + text.substring(b)
    }

    private fun median(sorted: List<Float>) =
        if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f

    private fun fmt(f: Float) = String.format(Locale.GERMAN, "%.1f", f)

    companion object {
        /** The bot must last this many weeks on the first scenery with every guard seed. */
        const val GUARD_WEEKS = 4
        val GUARD_SEEDS = listOf(1L, 2L, 3L)
        /** Every scenery must last this many weeks with every guard seed. */
        const val GUARD_MIN_WEEKS = 2f
        const val REPORT_SEEDS = 20L
        const val REPORT_MAX_WEEKS = 25
    }
}
