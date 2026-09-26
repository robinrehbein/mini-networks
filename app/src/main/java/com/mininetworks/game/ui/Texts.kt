package com.mininetworks.game.ui

import android.content.Context
import com.mininetworks.game.R
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.ConnectError
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.WeekNews

/** Display texts for the ids the game logic hands out; everything comes from string resources. */
class Texts(private val context: Context) {

    fun service(s: Service) = context.getString(
        when (s) {
            Service.MAIL -> R.string.service_mail
            Service.CALL -> R.string.service_call
            Service.GAMING -> R.string.service_gaming
            Service.STREAMING -> R.string.service_streaming
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
    }

    /** "New: DSL, Laptop" and, with [withYear], "1998 · New: DSL, Laptop". */
    fun news(n: WeekNews, withYear: Boolean): String {
        val items = n.cables.map(::cable) + n.devices.map(::device) + n.servers.map(::server)
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
}
