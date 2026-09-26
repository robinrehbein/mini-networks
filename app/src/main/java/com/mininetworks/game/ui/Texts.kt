package com.mininetworks.game.ui

import android.content.Context
import com.mininetworks.game.R
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.ConnectError
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Incident
import com.mininetworks.game.game.IncidentKind
import com.mininetworks.game.game.Incidents
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.RadioType
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
        val items = n.cables.map(::cable) + n.devices.map(::device) + n.servers.map(::server) + n.radios.map(::radio)
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
}
