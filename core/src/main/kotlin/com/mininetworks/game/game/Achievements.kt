package com.mininetworks.game.game

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Everything achievements and missions count, over all games on this device (docs/TOP100.md C2). Plain data that the
 * app stores as JSON ([encode], [decode]); fields added later need defaults so older data still loads.
 */
@Serializable
data class PlayerStats(
    /** Packets delivered in all normal, daily and endless games. */
    val delivered: Long = 0,
    /** Most packets delivered in one normal or daily game. */
    val bestGame: Int = 0,
    /** Most weeks played in one normal or daily game. */
    val bestWeek: Int = 0,
    val cablesLaid: Int = 0,
    /** Fiber cables laid or upgraded to fiber. */
    val fiberLaid: Int = 0,
    val cableUpgrades: Int = 0,
    val routersPlaced: Int = 0,
    val accessPoints: Int = 0,
    val cellTowers: Int = 0,
    val serverUpgrades: Int = 0,
    val dataCenters: Int = 0,
    val repairs: Int = 0,
    /** Normal and daily games that ended with a game over. */
    val gamesFinished: Int = 0,
    /** Week changes seen in all normal, daily and endless games. */
    val weeksPlayed: Int = 0,
    /** Scenery ids a game (not creative) was started in. */
    val sceneries: Set<String> = emptySet(),
    val streamingDelivered: Int = 0,
    /** Days whose daily challenge counted ([DailyChallenge.STREAK_PACKETS]). */
    val dailyDone: Int = 0,
    /** Longest daily streak ([DailyStreak.best]). */
    val bestStreak: Int = 0,
    /** Most weeks played in one endless game. */
    val endlessBestWeek: Int = 0,
    val creativeGames: Int = 0,
    /** Games continued once after a game over. */
    val secondChances: Int = 0,
    /** Most budget held at once in a normal or daily game (endless budget grows without limit, creative has none). */
    val richest: Int = 0,
) {
    fun value(m: Metric): Long = when (m) {
        Metric.DELIVERED -> delivered
        Metric.BEST_GAME -> bestGame.toLong()
        Metric.BEST_WEEK -> bestWeek.toLong()
        Metric.CABLES -> cablesLaid.toLong()
        Metric.FIBER -> fiberLaid.toLong()
        Metric.CABLE_UPGRADES -> cableUpgrades.toLong()
        Metric.ROUTERS -> routersPlaced.toLong()
        Metric.ACCESS_POINTS -> accessPoints.toLong()
        Metric.CELL_TOWERS -> cellTowers.toLong()
        Metric.SERVER_UPGRADES -> serverUpgrades.toLong()
        Metric.DATA_CENTERS -> dataCenters.toLong()
        Metric.REPAIRS -> repairs.toLong()
        Metric.GAMES_FINISHED -> gamesFinished.toLong()
        Metric.WEEKS_TOTAL -> weeksPlayed.toLong()
        Metric.SCENERIES -> sceneries.size.toLong()
        Metric.STREAMING -> streamingDelivered.toLong()
        Metric.DAILY_DONE -> dailyDone.toLong()
        Metric.BEST_STREAK -> bestStreak.toLong()
        Metric.ENDLESS_BEST_WEEK -> endlessBestWeek.toLong()
        Metric.CREATIVE_GAMES -> creativeGames.toLong()
        Metric.SECOND_CHANCES -> secondChances.toLong()
        Metric.RICHEST -> richest.toLong()
    }

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** The stats in [text], or empty stats if it is missing or damaged, so a broken file never blocks the game. */
        fun decode(text: String?): PlayerStats {
            if (text.isNullOrBlank()) return PlayerStats()
            return try {
                json.decodeFromString(serializer(), text)
            } catch (_: SerializationException) {
                PlayerStats()
            } catch (_: IllegalArgumentException) {
                PlayerStats()
            }
        }
    }
}

/** What an achievement counts; the UI has one description per metric ("Deliver %d packets in total" …). */
enum class Metric {
    DELIVERED, BEST_GAME, BEST_WEEK, CABLES, FIBER, CABLE_UPGRADES, ROUTERS, ACCESS_POINTS, CELL_TOWERS, SERVER_UPGRADES,
    DATA_CENTERS, REPAIRS, GAMES_FINISHED, WEEKS_TOTAL, SCENERIES, STREAMING, DAILY_DONE, BEST_STREAK, ENDLESS_BEST_WEEK,
    CREATIVE_GAMES, SECOND_CHANCES, RICHEST,
}

/**
 * One achievement: reached once [metric] is at least [target]. Tiers of the same metric work as missions: the screen
 * always shows the progress towards the next one. Titles come from the UI's string resources, keyed by [id].
 */
data class Achievement(val id: String, val metric: Metric, val target: Long) {
    fun reached(stats: PlayerStats) = stats.value(metric) >= target

    /** Progress towards [target], at most [target]. */
    fun progress(stats: PlayerStats) = stats.value(metric).coerceAtMost(target)
}

/** The list of achievements and missions, in screen order. */
object Achievements {
    val all: List<Achievement> = listOf(
        Achievement("delivered_1", Metric.DELIVERED, 1),
        Achievement("delivered_100", Metric.DELIVERED, 100),
        Achievement("delivered_1000", Metric.DELIVERED, 1_000),
        Achievement("delivered_10000", Metric.DELIVERED, 10_000),
        Achievement("game_100", Metric.BEST_GAME, 100),
        Achievement("game_300", Metric.BEST_GAME, 300),
        Achievement("game_600", Metric.BEST_GAME, 600),
        Achievement("game_1000", Metric.BEST_GAME, 1_000),
        Achievement("week_5", Metric.BEST_WEEK, 5),
        Achievement("week_10", Metric.BEST_WEEK, 10),
        Achievement("week_15", Metric.BEST_WEEK, 15),
        Achievement("cables_10", Metric.CABLES, 10),
        Achievement("cables_100", Metric.CABLES, 100),
        Achievement("cables_500", Metric.CABLES, 500),
        Achievement("fiber_1", Metric.FIBER, 1),
        Achievement("fiber_25", Metric.FIBER, 25),
        Achievement("upgrades_10", Metric.CABLE_UPGRADES, 10),
        Achievement("routers_5", Metric.ROUTERS, 5),
        Achievement("routers_50", Metric.ROUTERS, 50),
        Achievement("server_1", Metric.SERVER_UPGRADES, 1),
        Achievement("server_20", Metric.SERVER_UPGRADES, 20),
        Achievement("data_center_1", Metric.DATA_CENTERS, 1),
        Achievement("access_points_3", Metric.ACCESS_POINTS, 3),
        Achievement("cell_towers_3", Metric.CELL_TOWERS, 3),
        Achievement("repairs_5", Metric.REPAIRS, 5),
        Achievement("games_1", Metric.GAMES_FINISHED, 1),
        Achievement("games_10", Metric.GAMES_FINISHED, 10),
        Achievement("games_50", Metric.GAMES_FINISHED, 50),
        Achievement("weeks_50", Metric.WEEKS_TOTAL, 50),
        Achievement("sceneries_3", Metric.SCENERIES, 3),
        Achievement("streaming_500", Metric.STREAMING, 500),
        Achievement("daily_1", Metric.DAILY_DONE, 1),
        Achievement("daily_10", Metric.DAILY_DONE, 10),
        Achievement("streak_3", Metric.BEST_STREAK, 3),
        Achievement("streak_7", Metric.BEST_STREAK, 7),
        Achievement("endless_12", Metric.ENDLESS_BEST_WEEK, 12),
        Achievement("creative_1", Metric.CREATIVE_GAMES, 1),
        Achievement("second_chance_1", Metric.SECOND_CHANCES, 1),
        Achievement("budget_300", Metric.RICHEST, 300),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }

    /** Ids of the achievements [stats] has reached. */
    fun unlocked(stats: PlayerStats): Set<String> = all.filter { it.reached(stats) }.mapTo(LinkedHashSet()) { it.id }
}

/**
 * Keeps [stats] up to date while games run and says which achievements a change unlocked, for the unlock toast.
 * Pure logic: the app feeds it the running [World] every frame ([observe]) and the moments it cannot see (a game over,
 * a counted daily challenge, a second chance), and stores [stats] when [dirty].
 *
 * What counts: normal and daily games count for everything; endless games count for the totals and their own best
 * week, but not for the one-game records (best game, best week, richest: no game over there and an endless budget,
 * so they would be free); creative games (unlimited budget) only count as played. The tutorial ([World.guided])
 * counts for nothing. The counters behind the totals ignore free undo loops (see [GameCounters]).
 */
class AchievementTracker(stats: PlayerStats = PlayerStats()) {
    var stats = stats; private set

    /** True once [stats] changed since the app last stored them ([saved]). */
    var dirty = false; private set

    private var world: World? = null
    private var baseDelivered = 0
    private var baseWeeks = 0
    private var baseStreaming = 0
    private val base = IntArray(COUNTERS)

    /** Ids of the achievements reached so far. */
    val unlocked: Set<String> get() = Achievements.unlocked(stats)

    /** The app stored [stats]. */
    fun saved() {
        dirty = false
    }

    /** Takes [stats] as they are, e.g. after the app restored them from elsewhere; nothing counts as unlocked by it. */
    fun replace(stats: PlayerStats) {
        this.stats = stats
        dirty = false
    }

    /**
     * Starts following [world]. A [fresh] game (not one continued from a save) marks its scenery as played, and a fresh
     * creative game counts as one. Returns what that unlocked.
     */
    fun begin(world: World, fresh: Boolean): List<Achievement> {
        this.world = world
        baseDelivered = world.delivered
        baseWeeks = world.weeksPlayed
        baseStreaming = world.counters.delivered(Service.STREAMING)
        read(world.counters, base)
        if (!fresh || world.guided) return emptyList()
        return change {
            if (world.mode == GameMode.CREATIVE) it.copy(creativeGames = it.creativeGames + 1)
            else it.copy(sceneries = it.sceneries + world.scenario.id)
        }
    }

    /** Stops following the current world, e.g. when the player leaves to the main menu. */
    fun end() {
        world = null
    }

    /** Counts what happened in [world] since the last call; returns the achievements this unlocked, in list order. */
    fun observe(world: World): List<Achievement> {
        if (world !== this.world) begin(world, fresh = false)
        if (world.guided || world.mode == GameMode.CREATIVE) return emptyList()
        val now = IntArray(COUNTERS).also { read(world.counters, it) }
        val delivered = world.delivered - baseDelivered
        val weeks = world.weeksPlayed - baseWeeks
        val streaming = world.counters.delivered(Service.STREAMING) - baseStreaming
        val d = IntArray(COUNTERS) { now[it] - base[it] }
        val normal = world.mode == GameMode.NORMAL
        val s = stats
        val bestGame = if (normal) maxOf(s.bestGame, world.delivered) else s.bestGame
        val bestWeek = if (normal) maxOf(s.bestWeek, world.weeksPlayed) else s.bestWeek
        val endlessWeek = if (world.mode == GameMode.ENDLESS) maxOf(s.endlessBestWeek, world.weeksPlayed) else s.endlessBestWeek
        // A one-game record like the best game: endless budget grows without limit, so it would come for free there.
        val richest = if (normal) maxOf(s.richest, world.budget) else s.richest
        val anything = delivered != 0 || weeks != 0 || streaming != 0 || d.any { it != 0 } || bestGame != s.bestGame ||
            bestWeek != s.bestWeek || endlessWeek != s.endlessBestWeek || richest != s.richest
        if (!anything) return emptyList()
        baseDelivered = world.delivered
        baseWeeks = world.weeksPlayed
        baseStreaming = world.counters.delivered(Service.STREAMING)
        now.copyInto(base)
        return change {
            it.copy(
                delivered = it.delivered + delivered.coerceAtLeast(0),
                weeksPlayed = it.weeksPlayed + weeks.coerceAtLeast(0),
                streamingDelivered = it.streamingDelivered + streaming.coerceAtLeast(0),
                cablesLaid = it.cablesLaid + d[0],
                fiberLaid = it.fiberLaid + d[1],
                cableUpgrades = it.cableUpgrades + d[2],
                routersPlaced = it.routersPlaced + d[3],
                accessPoints = it.accessPoints + d[4],
                cellTowers = it.cellTowers + d[5],
                serverUpgrades = it.serverUpgrades + d[6],
                dataCenters = it.dataCenters + d[7],
                repairs = it.repairs + d[8],
                bestGame = bestGame,
                bestWeek = bestWeek,
                endlessBestWeek = endlessWeek,
                richest = richest,
            )
        }
    }

    /** A normal or daily game ended in [world]: counts what was left and one finished game. */
    fun gameOver(world: World): List<Achievement> {
        val first = observe(world)
        if (world.guided || !world.mode.endsOnOverload) return first
        return first + change { it.copy(gamesFinished = it.gamesFinished + 1) }
    }

    /** The daily challenge of a day counted; [streak] is the streak after it. */
    fun dailyCounted(streak: DailyStreak): List<Achievement> =
        change { it.copy(dailyDone = it.dailyDone + 1, bestStreak = maxOf(it.bestStreak, streak.best)) }

    /** A lost game went on once. */
    fun secondChance(): List<Achievement> = change { it.copy(secondChances = it.secondChances + 1) }

    private inline fun change(f: (PlayerStats) -> PlayerStats): List<Achievement> {
        val before = stats
        val after = f(before)
        if (after == before) return emptyList()
        stats = after
        dirty = true
        return Achievements.all.filter { !it.reached(before) && it.reached(after) }
    }

    private companion object {
        const val COUNTERS = 9

        fun read(c: GameCounters, out: IntArray) {
            out[0] = c.cablesLaid
            out[1] = c.fiberLaid
            out[2] = c.cableUpgrades
            out[3] = c.routersPlaced
            out[4] = c.accessPoints
            out[5] = c.cellTowers
            out[6] = c.serverUpgrades
            out[7] = c.dataCenters
            out[8] = c.repairs
        }
    }
}

/**
 * Cosmetic cable looks (docs/TOP100.md C5). A skin only changes colors; widths, speeds and every rule stay the same,
 * and the [World] never sees it. Every skin but [CLASSIC] is unlocked by the achievement [unlockedBy].
 */
enum class CableSkin(val unlockedBy: String?) {
    CLASSIC(null),
    COPPER("cables_100"),
    NEON("fiber_25"),
    PASTEL("daily_1"),
    GOLD("delivered_1000"),
}

/** Cosmetic color themes of the map (ground, water, board, trees), unlocked like [CableSkin]. */
enum class ColorTheme(val unlockedBy: String?) {
    MEADOW(null),
    AUTUMN("week_10"),
    WINTER("streak_3"),
    DESERT("sceneries_3"),
}

/** Which cosmetics a player may pick. */
object Cosmetics {
    fun skins(unlocked: Set<String>) = CableSkin.entries.filter { it.unlockedBy == null || it.unlockedBy in unlocked }

    fun themes(unlocked: Set<String>) = ColorTheme.entries.filter { it.unlockedBy == null || it.unlockedBy in unlocked }

    /** The cable skin an achievement unlocks, if any. */
    fun skinFor(achievementId: String) = CableSkin.entries.firstOrNull { it.unlockedBy == achievementId }

    /** The color theme an achievement unlocks, if any. */
    fun themeFor(achievementId: String) = ColorTheme.entries.firstOrNull { it.unlockedBy == achievementId }

    /** The entry after [current] in [available], wrapping around; the first one if [current] is not available. */
    fun <T> next(current: T, available: List<T>): T {
        val i = available.indexOf(current)
        return available[if (i < 0) 0 else (i + 1) % available.size]
    }
}
