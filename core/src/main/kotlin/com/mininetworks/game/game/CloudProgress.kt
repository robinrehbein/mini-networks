package com.mininetworks.game.game

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * The player's progress as one cloud save (Play Games Saved Games, docs/TOP100.md C6): achievement stats, best scores
 * per scenery (and endless), the daily streak and today's daily best. Not the running game: a game in progress stays
 * on the device (it is only worth something on the device it is played on).
 *
 * Two devices that played offline produce two saves; [merge] joins them without losing progress: every counter and
 * record takes the higher value ("most progress"), sets are joined, and what depends on the day (streak, daily best)
 * comes from the newer day ("newest"). Known limit: two devices that both counted from the same total keep the higher
 * total, not the sum (a sum would count the shared part twice).
 */
@Serializable
data class CloudProgress(
    val version: Int = VERSION,
    /** Wall clock of the device that wrote it, for display only; merging never trusts clocks. */
    val savedAt: Long = 0,
    val stats: PlayerStats = PlayerStats(),
    /** Best delivered packets per highscore key (scenery id, "endless_<id>"). */
    val best: Map<String, Int> = emptyMap(),
    val streakDay: Long? = null,
    val streakCurrent: Int = 0,
    val streakBest: Int = 0,
    /** UTC day of [dailyBest]. */
    val dailyDay: Long? = null,
    val dailyBest: Int = 0,
) {
    val streak: DailyStreak get() = DailyStreak(streakDay, streakCurrent, streakBest)

    /** One number for "how far": Play Games shows it with the save and can pick by it. */
    val progress: Long get() = stats.delivered

    /** True if a newer app version wrote this save: merge into it, but never overwrite it (fields would be lost). */
    val newerFormat: Boolean get() = version > VERSION

    fun encode(): ByteArray = json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    companion object {
        const val VERSION = 1

        /** Name of the save in Play Games. */
        const val SNAPSHOT_NAME = "progress"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun of(stats: PlayerStats, best: Map<String, Int>, streak: DailyStreak, dailyDay: Long?, dailyBest: Int, savedAt: Long) =
            CloudProgress(
                savedAt = savedAt, stats = stats, best = best,
                streakDay = streak.lastDay, streakCurrent = streak.current, streakBest = streak.best,
                dailyDay = dailyDay.takeIf { dailyBest > 0 }, dailyBest = if (dailyDay == null) 0 else dailyBest,
            )

        /** The save in [bytes], or null if there is none or it is damaged (a broken cloud save never blocks the game). */
        fun decode(bytes: ByteArray?): CloudProgress? {
            if (bytes == null || bytes.isEmpty()) return null
            return try {
                json.decodeFromString(serializer(), bytes.toString(Charsets.UTF_8)).sanitized()
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /** Joins [a] and [b] without losing progress of either (see the class comment); the result is symmetric. */
        fun merge(a: CloudProgress, b: CloudProgress): CloudProgress {
            val best = (a.best.keys + b.best.keys).sorted().associateWith { maxOf(a.best[it] ?: 0, b.best[it] ?: 0) }
            // The streak of the later day is the current one; the record is the best either side ever had.
            val newer = listOf(a, b).maxWith(compareBy<CloudProgress>({ it.streakDay ?: Long.MIN_VALUE }, { it.streakCurrent }))
            val daily = listOf(a, b).maxWith(compareBy<CloudProgress>({ it.dailyDay ?: Long.MIN_VALUE }, { it.dailyBest }))
            return CloudProgress(
                version = VERSION,
                savedAt = maxOf(a.savedAt, b.savedAt),
                stats = mergeStats(a.stats, b.stats),
                best = best,
                streakDay = newer.streakDay,
                streakCurrent = newer.streakCurrent,
                streakBest = maxOf(a.streakBest, b.streakBest, newer.streakCurrent),
                dailyDay = daily.dailyDay,
                dailyBest = daily.dailyBest,
            )
        }

        /**
         * Every [PlayerStats] value only grows (totals, records, played sceneries), so the higher one is the one with
         * more progress. Done field by field on the JSON form, so a field added to [PlayerStats] later is merged too.
         */
        fun mergeStats(a: PlayerStats, b: PlayerStats): PlayerStats {
            val ja = json.encodeToJsonElement(PlayerStats.serializer(), a).jsonObject
            val jb = json.encodeToJsonElement(PlayerStats.serializer(), b).jsonObject
            val merged = JsonObject((ja.keys + jb.keys).associateWith { mergeValue(ja[it], jb[it]) })
            return json.decodeFromJsonElement(PlayerStats.serializer(), merged)
        }

        private fun mergeValue(a: JsonElement?, b: JsonElement?): JsonElement = when {
            a == null -> b!!
            b == null -> a
            a is JsonArray && b is JsonArray -> JsonArray((a + b).distinct().sortedBy { it.toString() })
            a is JsonPrimitive && b is JsonPrimitive && a.longOrNull != null && b.longOrNull != null ->
                if (b.longOrNull!! > a.longOrNull!!) b else a
            else -> a
        }

        private fun CloudProgress.sanitized() = copy(
            best = best.filterValues { it > 0 },
            streakCurrent = streakCurrent.coerceAtLeast(0),
            streakBest = streakBest.coerceAtLeast(0),
            dailyBest = dailyBest.coerceAtLeast(0),
        )
    }
}
