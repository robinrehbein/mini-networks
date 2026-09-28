package com.mininetworks.game.ui

import android.content.Context
import com.mininetworks.game.R
import com.mininetworks.game.game.Achievement
import com.mininetworks.game.game.CableSkin
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.ColorTheme
import com.mininetworks.game.game.DailyRule
import com.mininetworks.game.game.GameMode
import com.mininetworks.game.game.Metric
import com.mininetworks.game.game.ConnectError
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Incident
import com.mininetworks.game.game.IncidentKind
import com.mininetworks.game.game.Incidents
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.RadioType
import com.mininetworks.game.game.RerouteError
import com.mininetworks.game.game.Scenario
import com.mininetworks.game.game.Scenarios
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.WeekNews
import kotlin.math.ceil

/** Display texts for the ids the game logic hands out; everything comes from string resources. */
class Texts(private val context: Context) {

    /** Name of scenery [s] ("Kleinstadt am Fluss" …). */
    fun scenario(s: Scenario) = context.getString(
        when (s.id) {
            Scenarios.METROPOLIS.id -> R.string.scenery_metropolis
            Scenarios.ISLAND.id -> R.string.scenery_island_harbor
            Scenarios.MOUNTAIN_VILLAGE.id -> R.string.scenery_mountain_village
            Scenarios.FUTURE.id -> R.string.scenery_future_2030
            else -> R.string.scenery_river_town
        },
    )

    /** One line on what makes scenery [s] special. */
    fun scenarioDescription(s: Scenario) = context.getString(
        when (s.id) {
            Scenarios.METROPOLIS.id -> R.string.scenery_metropolis_desc
            Scenarios.ISLAND.id -> R.string.scenery_island_harbor_desc
            Scenarios.MOUNTAIN_VILLAGE.id -> R.string.scenery_mountain_village_desc
            Scenarios.FUTURE.id -> R.string.scenery_future_2030_desc
            else -> R.string.scenery_river_town_desc
        },
    )

    fun service(s: Service) = context.getString(
        when (s) {
            Service.MAIL -> R.string.service_mail
            Service.CALL -> R.string.service_call
            Service.GAMING -> R.string.service_gaming
            Service.STREAMING -> R.string.service_streaming
            Service.VIDEO_CALL -> R.string.service_video_call
            Service.CAMERA_UPLOAD -> R.string.service_camera_upload
            Service.CLOUD_BACKUP -> R.string.service_cloud_backup
        },
    )

    fun device(d: Device) = context.getString(
        when (d) {
            Device.PC -> R.string.device_pc
            Device.PHONE -> R.string.device_phone
            Device.LAPTOP -> R.string.device_laptop
            Device.CONSOLE -> R.string.device_console
            Device.SMARTPHONE -> R.string.device_smartphone
            Device.TV -> R.string.device_tv
            Device.TABLET -> R.string.device_tablet
            Device.WATCH -> R.string.device_watch
            Device.CAMERA -> R.string.device_camera
            Device.SMART_HOME -> R.string.device_smart_home
        },
    )

    /** A short name for a chip without room for [cable] (at most about five letters). */
    fun cableShort(t: CableType) = context.getString(
        when (t) {
            CableType.ISDN -> R.string.cable_short_isdn
            CableType.DSL -> R.string.cable_short_dsl
            CableType.COAX -> R.string.cable_short_coax
            CableType.FIBER -> R.string.cable_short_fiber
        },
    )

    fun cable(t: CableType) = context.getString(
        when (t) {
            CableType.ISDN -> R.string.cable_isdn
            CableType.DSL -> R.string.cable_dsl
            CableType.COAX -> R.string.cable_coax
            CableType.FIBER -> R.string.cable_fiber
        },
    )

    fun server(s: Service) = context.getString(R.string.node_server, service(s))

    fun node(n: Node) = when (n.kind) {
        NodeKind.CLIENT -> device(n.device!!)
        NodeKind.SERVER -> server(n.service!!)
        NodeKind.ROUTER -> context.getString(R.string.node_router)
        NodeKind.ACCESS_POINT, NodeKind.CELL_TOWER -> radio(n.radio!!)
    }

    fun radio(t: RadioType) = context.getString(
        when (t) {
            RadioType.WLAN -> R.string.node_access_point
            RadioType.CELL -> R.string.node_cell_tower
        },
    )

    /** Clock time for an in-game [hour], cut down to ten minutes; hours past 24 wrap to the next day. */
    fun clock(hour: Float): String {
        val minutes = (hour * 6f + 1e-3f).toInt() * 10
        return context.getString(R.string.clock_time, minutes / 60 % 24, minutes % 60)
    }

    /** "New: DSL, Laptop" and, with [withYear], "1998 · New: DSL, Laptop". */
    fun news(n: WeekNews, withYear: Boolean): String {
        val items = n.cables.map(::cable) + n.devices.map(::device) + n.servers.map(::server) + n.radios.map(::radio) +
            n.cellGenerations.map { context.getString(R.string.news_cell_generation, it.longLabel) }
        val text = context.getString(R.string.news_items, items.joinToString(context.getString(R.string.list_separator)))
        return if (withYear) context.getString(R.string.news_with_year, n.year, text) else text
    }

    /** Why a cable from [from] to [to] of [type] cannot be laid. */
    fun connectError(e: ConnectError, from: Node, to: Node, type: CableType): String = when (e) {
        ConnectError.SAME_NODE -> context.getString(R.string.connect_error_same_node)
        ConnectError.ALREADY_CONNECTED -> context.getString(R.string.connect_error_already_connected)
        ConnectError.NOT_INVENTED -> context.getString(R.string.connect_error_not_invented, cable(type))
        ConnectError.FROM_PORTS_FULL -> context.getString(R.string.connect_error_ports_full, node(from))
        ConnectError.TO_PORTS_FULL -> context.getString(R.string.connect_error_ports_full, node(to))
        ConnectError.NO_BUDGET -> context.getString(R.string.connect_error_no_budget)
    }

    /** Why a cable cannot be re-routed onto [to]; null when nothing needs saying ([RerouteError.UNCHANGED]). */
    fun rerouteError(e: RerouteError, to: Node): String? = when (e) {
        RerouteError.SAME_NODE -> context.getString(R.string.connect_error_same_node)
        RerouteError.ALREADY_CONNECTED -> context.getString(R.string.connect_error_already_connected)
        RerouteError.PORTS_FULL -> context.getString(R.string.connect_error_ports_full, node(to))
        RerouteError.NO_BUDGET -> context.getString(R.string.connect_error_no_budget)
        RerouteError.INCIDENT -> context.getString(R.string.reroute_error_incident)
        RerouteError.UNCHANGED, RerouteError.GONE -> null
    }

    /** The HUD line for incident [i]: the announcement with its countdown, or the effect and how long it lasts. */
    fun incident(i: Incident): String {
        val seconds = ceil(if (i.struck) i.remaining else i.warning).toInt().coerceAtLeast(1)
        return when (i.kind) {
            IncidentKind.EXCAVATOR ->
                if (i.struck) context.getString(R.string.incident_cut, Incidents.REPAIR_COST, seconds)
                else context.getString(R.string.incident_excavator_warning, seconds)
            IncidentKind.POWER_OUTAGE ->
                context.getString(if (i.struck) R.string.incident_outage else R.string.incident_outage_warning, node(i.node!!), seconds)
        }
    }

    // ---------------------------------------------------------------- retention (docs/TOP100.md C1, C2, C4, C5)

    fun mode(m: GameMode) = context.getString(
        when (m) {
            GameMode.NORMAL -> R.string.mode_normal
            GameMode.ENDLESS -> R.string.mode_endless
            GameMode.CREATIVE -> R.string.mode_creative
        },
    )

    /** One line on what makes mode [m] special, null for the normal game. */
    fun modeDescription(m: GameMode): String? = when (m) {
        GameMode.NORMAL -> null
        GameMode.ENDLESS -> context.getString(R.string.mode_endless_desc)
        GameMode.CREATIVE -> context.getString(R.string.mode_creative_desc)
    }

    fun rule(r: DailyRule) = context.getString(
        when (r) {
            DailyRule.TIGHT_BUDGET -> R.string.rule_tight_budget
            DailyRule.RUSH_HOUR -> R.string.rule_rush_hour
            DailyRule.FIBER_DAY -> R.string.rule_fiber_day
            DailyRule.FEW_ROUTERS -> R.string.rule_few_routers
            DailyRule.STORM -> R.string.rule_storm
            DailyRule.WIDE_LAND -> R.string.rule_wide_land
            DailyRule.CROWD -> R.string.rule_crowd
        },
    )

    fun ruleDescription(r: DailyRule) = context.getString(
        when (r) {
            DailyRule.TIGHT_BUDGET -> R.string.rule_tight_budget_desc
            DailyRule.RUSH_HOUR -> R.string.rule_rush_hour_desc
            DailyRule.FIBER_DAY -> R.string.rule_fiber_day_desc
            DailyRule.FEW_ROUTERS -> R.string.rule_few_routers_desc
            DailyRule.STORM -> R.string.rule_storm_desc
            DailyRule.WIDE_LAND -> R.string.rule_wide_land_desc
            DailyRule.CROWD -> R.string.rule_crowd_desc
        },
    )

    fun skin(s: CableSkin) = context.getString(
        when (s) {
            CableSkin.CLASSIC -> R.string.skin_classic
            CableSkin.COPPER -> R.string.skin_copper
            CableSkin.NEON -> R.string.skin_neon
            CableSkin.PASTEL -> R.string.skin_pastel
            CableSkin.GOLD -> R.string.skin_gold
        },
    )

    fun theme(t: ColorTheme) = context.getString(
        when (t) {
            ColorTheme.MEADOW -> R.string.theme_meadow
            ColorTheme.AUTUMN -> R.string.theme_autumn
            ColorTheme.WINTER -> R.string.theme_winter
            ColorTheme.DESERT -> R.string.theme_desert
        },
    )

    /** Title of achievement [a]. */
    fun achievementTitle(a: Achievement) = context.getString(ACHIEVEMENT_TITLES[a.id] ?: R.string.menu_achievements)

    /** What to do for achievement [a], with its target. */
    fun achievementDescription(a: Achievement): String {
        val res = when (a.metric) {
            Metric.DELIVERED -> R.plurals.ach_desc_delivered
            Metric.BEST_GAME -> R.plurals.ach_desc_best_game
            Metric.BEST_WEEK -> R.plurals.ach_desc_best_week
            Metric.CABLES -> R.plurals.ach_desc_cables
            Metric.FIBER -> R.plurals.ach_desc_fiber
            Metric.CABLE_UPGRADES -> R.plurals.ach_desc_cable_upgrades
            Metric.ROUTERS -> R.plurals.ach_desc_routers
            Metric.ACCESS_POINTS -> R.plurals.ach_desc_access_points
            Metric.CELL_TOWERS -> R.plurals.ach_desc_cell_towers
            Metric.SERVER_UPGRADES -> R.plurals.ach_desc_server_upgrades
            Metric.DATA_CENTERS -> R.plurals.ach_desc_data_centers
            Metric.REPAIRS -> R.plurals.ach_desc_repairs
            Metric.GAMES_FINISHED -> R.plurals.ach_desc_games_finished
            Metric.WEEKS_TOTAL -> R.plurals.ach_desc_weeks_total
            Metric.SCENERIES -> R.plurals.ach_desc_sceneries
            Metric.STREAMING -> R.plurals.ach_desc_streaming
            Metric.DAILY_DONE -> R.plurals.ach_desc_daily_done
            Metric.BEST_STREAK -> R.plurals.ach_desc_best_streak
            Metric.ENDLESS_BEST_WEEK -> R.plurals.ach_desc_endless_best_week
            Metric.CREATIVE_GAMES -> R.plurals.ach_desc_creative_games
            Metric.SECOND_CHANCES -> R.plurals.ach_desc_second_chances
            Metric.RICHEST -> R.plurals.ach_desc_richest
        }
        val n = a.target.toInt()
        return context.resources.getQuantityString(res, n, n)
    }

    private companion object {
        val ACHIEVEMENT_TITLES = mapOf(
            "delivered_1" to R.string.ach_delivered_1,
            "delivered_100" to R.string.ach_delivered_100,
            "delivered_1000" to R.string.ach_delivered_1000,
            "delivered_10000" to R.string.ach_delivered_10000,
            "game_100" to R.string.ach_game_100,
            "game_300" to R.string.ach_game_300,
            "game_600" to R.string.ach_game_600,
            "game_1000" to R.string.ach_game_1000,
            "week_5" to R.string.ach_week_5,
            "week_10" to R.string.ach_week_10,
            "week_15" to R.string.ach_week_15,
            "cables_10" to R.string.ach_cables_10,
            "cables_100" to R.string.ach_cables_100,
            "cables_500" to R.string.ach_cables_500,
            "fiber_1" to R.string.ach_fiber_1,
            "fiber_25" to R.string.ach_fiber_25,
            "upgrades_10" to R.string.ach_upgrades_10,
            "routers_5" to R.string.ach_routers_5,
            "routers_50" to R.string.ach_routers_50,
            "server_1" to R.string.ach_server_1,
            "server_20" to R.string.ach_server_20,
            "data_center_1" to R.string.ach_data_center_1,
            "access_points_3" to R.string.ach_access_points_3,
            "cell_towers_3" to R.string.ach_cell_towers_3,
            "repairs_5" to R.string.ach_repairs_5,
            "games_1" to R.string.ach_games_1,
            "games_10" to R.string.ach_games_10,
            "games_50" to R.string.ach_games_50,
            "weeks_50" to R.string.ach_weeks_50,
            "sceneries_3" to R.string.ach_sceneries_3,
            "streaming_500" to R.string.ach_streaming_500,
            "daily_1" to R.string.ach_daily_1,
            "daily_10" to R.string.ach_daily_10,
            "streak_3" to R.string.ach_streak_3,
            "streak_7" to R.string.ach_streak_7,
            "endless_12" to R.string.ach_endless_12,
            "creative_1" to R.string.ach_creative_1,
            "second_chance_1" to R.string.ach_second_chance_1,
            "budget_300" to R.string.ach_budget_300,
        )
    }

    /** True if every achievement has its own title, for tests. */
    internal fun hasTitle(a: Achievement) = a.id in ACHIEVEMENT_TITLES
}
