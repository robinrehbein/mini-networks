package com.mininetworks.game.games

import android.content.res.Resources
import com.mininetworks.game.R

/**
 * The Play Games Services ids from res/values/games-ids.xml (docs/RELEASE.md 11). The repo only holds placeholders
 * (values starting with `TODO_`): then [configured] is false and the app runs without Play Games, and a single
 * placeholder achievement or leaderboard is skipped ([achievement], [leaderboard] return null).
 */
class GamesIds(private val resources: Resources) {
    /** The project id of Play Games Services; a number once configured. */
    val appId: String get() = resources.getString(R.string.app_id)

    /** True once games-ids.xml holds a real project id. */
    val configured: Boolean get() = isReal(appId) && appId.all(Char::isDigit)

    /** Console id of achievement [id] (Achievements.all), or null while it is a placeholder. */
    fun achievement(id: String): String? = ACHIEVEMENTS[id]?.let(resources::getString)?.takeIf(::isReal)

    /** Console id of leaderboard [key] (Leaderboards.all), or null while it is a placeholder. */
    fun leaderboard(key: String): String? = LEADERBOARDS[key]?.let(resources::getString)?.takeIf(::isReal)

    companion object {
        const val PLACEHOLDER_PREFIX = "TODO_"

        fun isReal(value: String) = value.isNotBlank() && !value.startsWith(PLACEHOLDER_PREFIX)

        /** Achievement id (Achievements.all) to its games-ids.xml resource. */
        val ACHIEVEMENTS: Map<String, Int> = mapOf(
            "delivered_1" to R.string.achievement_delivered_1,
            "delivered_100" to R.string.achievement_delivered_100,
            "delivered_1000" to R.string.achievement_delivered_1000,
            "delivered_10000" to R.string.achievement_delivered_10000,
            "game_100" to R.string.achievement_game_100,
            "game_300" to R.string.achievement_game_300,
            "game_600" to R.string.achievement_game_600,
            "game_1000" to R.string.achievement_game_1000,
            "week_5" to R.string.achievement_week_5,
            "week_10" to R.string.achievement_week_10,
            "week_15" to R.string.achievement_week_15,
            "cables_10" to R.string.achievement_cables_10,
            "cables_100" to R.string.achievement_cables_100,
            "cables_500" to R.string.achievement_cables_500,
            "fiber_1" to R.string.achievement_fiber_1,
            "fiber_25" to R.string.achievement_fiber_25,
            "upgrades_10" to R.string.achievement_upgrades_10,
            "routers_5" to R.string.achievement_routers_5,
            "routers_50" to R.string.achievement_routers_50,
            "server_1" to R.string.achievement_server_1,
            "server_20" to R.string.achievement_server_20,
            "data_center_1" to R.string.achievement_data_center_1,
            "access_points_3" to R.string.achievement_access_points_3,
            "cell_towers_3" to R.string.achievement_cell_towers_3,
            "repairs_5" to R.string.achievement_repairs_5,
            "games_1" to R.string.achievement_games_1,
            "games_10" to R.string.achievement_games_10,
            "games_50" to R.string.achievement_games_50,
            "weeks_50" to R.string.achievement_weeks_50,
            "sceneries_3" to R.string.achievement_sceneries_3,
            "streaming_500" to R.string.achievement_streaming_500,
            "daily_1" to R.string.achievement_daily_1,
            "daily_10" to R.string.achievement_daily_10,
            "streak_3" to R.string.achievement_streak_3,
            "streak_7" to R.string.achievement_streak_7,
            "endless_12" to R.string.achievement_endless_12,
            "creative_1" to R.string.achievement_creative_1,
            "second_chance_1" to R.string.achievement_second_chance_1,
            "budget_300" to R.string.achievement_budget_300,
        )

        /** Leaderboard key (Leaderboards.all) to its games-ids.xml resource. */
        val LEADERBOARDS: Map<String, Int> = mapOf(
            "scenery_river_town" to R.string.leaderboard_scenery_river_town,
            "scenery_metropolis" to R.string.leaderboard_scenery_metropolis,
            "scenery_island_harbor" to R.string.leaderboard_scenery_island_harbor,
            "scenery_mountain_village" to R.string.leaderboard_scenery_mountain_village,
            "scenery_future_2030" to R.string.leaderboard_scenery_future_2030,
            "daily" to R.string.leaderboard_daily,
        )
    }
}
