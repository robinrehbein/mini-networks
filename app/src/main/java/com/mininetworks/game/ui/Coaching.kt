package com.mininetworks.game.ui

import com.mininetworks.game.game.IncidentKind
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.RadioType
import com.mininetworks.game.game.RouteProblem
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World

/**
 * Just-in-time coaching: a one-time tip the first time a mechanic matters in a game (a ring starts, a jam, a busy
 * server, a route that is too narrow, an excavator, a radio in stock, an old device that wants a new service). Each
 * tip shows once per install ([GameView] keeps the shown ones in the settings), never in the tutorial and not in the
 * daily challenge (a scored run is no lesson); the tips about game-over pressure stay out of creative mode, as does
 * the one about new services (all its servers stand from the start). The order is the priority when several are due
 * at once: the most urgent first.
 */
enum class Coaching {
    /** An excavator is announced: a cut cable is repaired by tapping it. */
    INCIDENT,

    /** A device's overload ring started: pause and keep building. */
    PAUSE,

    /** Requests queue at a server: tap it twice to upgrade it. */
    SERVER_QUEUE,

    /** A route that exists is jammed: a wider or a second cable. */
    JAM,

    /** A request has no route that is wide enough (the "too narrow" badge): pick a wider cable and tap a cable. */
    CABLE_UPGRADE,

    /** An old device wants a service whose first server came later: link it to a server of that service. */
    NEW_SERVICE,

    /** A Wi-Fi access point or a cell tower is in stock: drag it from the toolbar onto the map. */
    RADIO_STOCK,
    ;

    /** The key the shown tip is stored under; never renamed, or the tip shows again. */
    val key: String = name.lowercase()

    /** True if this tip makes sense in [world]'s kind of game at all. */
    fun appliesTo(world: World): Boolean = when (this) {
        PAUSE, INCIDENT, RADIO_STOCK, NEW_SERVICE -> !world.unlimited
        else -> true
    }

    /** True for a tip that cannot wait out [GAP_SECONDS]: the excavator's warning lasts only seconds. */
    val urgent: Boolean get() = this == INCIDENT

    /**
     * What the tip is about if it is due in [world] right now (the node, the service or the radio its text names, or
     * [Unit]), else null. Linear in the nodes (the server queue: servers times packets) and without allocations beyond
     * what [World] answers anyway; [GameView] asks a few times a second, not every frame.
     */
    fun due(world: World): Any? = when (this) {
        INCIDENT -> world.incidents.firstOrNull { it.kind == IncidentKind.EXCAVATOR }
        PAUSE -> world.nodes.firstOrNull { it.kind == NodeKind.CLIENT && it.overload > 0f }
        SERVER_QUEUE -> world.nodes.firstOrNull { it.kind == NodeKind.SERVER && world.waitingAt(it) >= GameView.BUSY_HINT_WAITING }
        JAM -> world.nodes.firstOrNull { world.isJammed(it) }
        CABLE_UPGRADE -> world.nodes.firstOrNull { n -> n.kind == NodeKind.CLIENT && tooNarrow(world, n) }
        NEW_SERVICE -> {
            firstServerIds(world)
            world.nodes.firstNotNullOfOrNull { n ->
                if (n.kind != NodeKind.CLIENT) null
                else world.firstUnreachableService(n)?.takeIf { it.serverWeek > world.scenario.startWeek && n.id < firstServerId[it.ordinal] }?.let { n to it }
            }
        }
        RADIO_STOCK -> RadioType.entries.firstOrNull { world.radiosAvailable(it) > 0 }
    }

    /**
     * Fills [firstServerId] in one pass: per service, the smallest node id of its servers (node ids only grow, so a
     * device with a smaller id stood on the map before the service's first server came).
     */
    private fun firstServerIds(world: World) {
        firstServerId.fill(Int.MAX_VALUE)
        val nodes = world.nodes
        for (i in nodes.indices) {
            val s = nodes[i]
            val service = s.service ?: continue
            if (s.kind == NodeKind.SERVER && s.id < firstServerId[service.ordinal]) firstServerId[service.ordinal] = s.id
        }
    }

    private fun tooNarrow(world: World, n: Node): Boolean {
        val pending = n.pending
        for (i in 0 until minOf(pending.size, 8)) if (world.routeProblem(n, pending[i]) == RouteProblem.TOO_NARROW) return true
        return false
    }

    companion object {
        /**
         * Seconds at least between two tips, so they never pile up on top of the news, the badges and the alarms of a
         * first game ([urgent] tips excepted).
         */
        const val GAP_SECONDS = 45f

        /** Per service ([Service.ordinal]), the smallest server id ([firstServerIds]); reused, game thread only. */
        private val firstServerId = IntArray(Service.entries.size)

        /**
         * The first tip of [entries] due in [world] and not in [seen], with its subject; null if none. The pause tip
         * waits while the clock is already [paused]; [urgentOnly] looks only at the [urgent] ones (within the gap).
         */
        fun next(world: World, seen: Set<String>, paused: Boolean = false, urgentOnly: Boolean = false): Pair<Coaching, Any>? {
            val all = entries
            for (i in all.indices) {
                val c = all[i]
                if (c.key in seen || !c.appliesTo(world) || (paused && c == PAUSE) || (urgentOnly && !c.urgent)) continue
                val subject = c.due(world) ?: continue
                return c to subject
            }
            return null
        }

        /** The service a [NEW_SERVICE] subject names. */
        @Suppress("UNCHECKED_CAST")
        fun newService(subject: Any): Pair<Node, Service> = subject as Pair<Node, Service>
    }
}
