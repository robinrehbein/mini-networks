package com.mininetworks.game.game

import kotlinx.serialization.Serializable
import kotlin.math.hypot
import kotlin.math.min

/** Point in world space. One unit = one grid cell. */
data class Vec2(val x: Float, val y: Float)

enum class Shape { CIRCLE, SQUARE, TRIANGLE, DIAMOND, PENTAGON, HEXAGON, PLUS }

/** When a client asks for a service. */
enum class Demand {
    /** Now and then: each request picks one of the device's random-demand services, faster as the weeks go by. */
    RANDOM,

    /** A steady stream: one request every [World.Tuning.STREAM_SECONDS], the same in every week. */
    STREAM,

    /** Only at the nightly backup time ([World.Tuning.BACKUP_HOUR]), then [World.Tuning.BACKUP_BURST] requests at once. */
    NIGHTLY,
}

/**
 * What a device wants from the network. Each service has a bandwidth need (packet size in capacity units)
 * and optionally a round-trip ping limit: real-time services fail on slow routes even when bandwidth is free.
 * The first server of a service appears in [serverWeek]; [demand] says when clients ask for it.
 * An [upload] sends its data with the request; the response is only a small acknowledgement ([responseSize]).
 * The shape is the primary signal (colorblind-safe), color only supports it. Names come from the UI's string resources.
 */
enum class Service(
    val shape: Shape,
    val bandwidth: Int,
    val maxPingMs: Int?,
    val serverWeek: Int,
    val demand: Demand = Demand.RANDOM,
    val upload: Boolean = false,
) {
    MAIL(Shape.SQUARE, 1, null, 1),
    CALL(Shape.DIAMOND, 1, 300, 1),
    GAMING(Shape.TRIANGLE, 1, 140, 3),
    STREAMING(Shape.CIRCLE, 3, null, 4),
    VIDEO_CALL(Shape.PENTAGON, 2, 240, 6),
    CAMERA_UPLOAD(Shape.HEXAGON, 2, null, 7, Demand.STREAM, upload = true),
    CLOUD_BACKUP(Shape.PLUS, 4, null, 8, Demand.NIGHTLY, upload = true),
    ;

    /** Size of the response in capacity units: the full [bandwidth], or [ACK_SIZE] for an [upload]. */
    val responseSize get() = if (upload) ACK_SIZE else bandwidth

    companion object {
        const val ACK_SIZE = 1
    }
}

/**
 * Client devices. They appear over the eras and each asks for a mix of services, see [Service.demand].
 * [mobile] devices can also use a cell tower ([RadioType.CELL]).
 */
enum class Device(val services: List<Service>, val unlockWeek: Int, val mobile: Boolean = false) {
    PC(listOf(Service.MAIL, Service.GAMING, Service.CLOUD_BACKUP), 1),
    PHONE(listOf(Service.CALL), 1),
    LAPTOP(listOf(Service.MAIL, Service.STREAMING, Service.CALL, Service.VIDEO_CALL, Service.CLOUD_BACKUP), 2),
    CONSOLE(listOf(Service.GAMING), 3),
    SMARTPHONE(listOf(Service.CALL, Service.STREAMING, Service.MAIL, Service.VIDEO_CALL), 4, mobile = true),
    TV(listOf(Service.STREAMING), 4),
    TABLET(listOf(Service.STREAMING, Service.MAIL, Service.VIDEO_CALL), 5, mobile = true),
    WATCH(listOf(Service.CALL), 6, mobile = true),

    /** Security camera: streams its picture upward all the time. */
    CAMERA(listOf(Service.CAMERA_UPLOAD), 7),

    /** Smart-home hub: small messages by day, a backup at night. */
    SMART_HOME(listOf(Service.MAIL, Service.CLOUD_BACKUP), 7),
    ;

    /** The service this device streams without pause, if any; such a device asks for nothing else. */
    val stream get() = services.firstOrNull { it.demand == Demand.STREAM }
}

/**
 * Cable technologies, unlocked era by era.
 * capacity: bandwidth units in flight at once. msPerCell: latency per grid cell. speed: visual packet speed.
 */
enum class CableType(
    val capacity: Int,
    val msPerCell: Float,
    val speed: Float,
    val costPerCell: Int,
    val unlockWeek: Int,
) {
    ISDN(2, 14f, 1.4f, 1, 1),
    DSL(4, 7f, 2.0f, 1, 2),
    COAX(6, 5f, 2.4f, 2, 3),
    FIBER(12, 2.5f, 3.6f, 3, 5),
}

/**
 * Node roles. [maxPorts] is the default cable port count; a data center server has more (see [Node.maxPorts]).
 * Radio nodes ([ACCESS_POINT], [CELL_TOWER]) are cabled like routers and reach clients wirelessly, see [RadioType].
 */
enum class NodeKind(val maxPorts: Int) {
    CLIENT(2),
    SERVER(4),
    ROUTER(6),
    ACCESS_POINT(2),
    CELL_TOWER(4),
}

/** Why a server cannot be upgraded right now. The UI maps these to texts. */
enum class ServerUpgradeError { NOT_A_SERVER, MAX_LEVEL, NO_BUDGET, NO_SPACE }

/** Why a cable cannot be laid. [FROM_PORTS_FULL] and [TO_PORTS_FULL] name the drag's start or end node. */
enum class ConnectError { SAME_NODE, ALREADY_CONNECTED, NOT_INVENTED, FROM_PORTS_FULL, TO_PORTS_FULL, NO_BUDGET }

/** Why a cable cannot be swapped to another technology. */
enum class CableUpgradeError { NOT_AN_UPGRADE, NOT_INVENTED, NO_BUDGET }

/** Why an access point cannot switch to 5 GHz. */
enum class WifiUpgradeError { NOT_AN_ACCESS_POINT, ALREADY_5_GHZ, NO_BUDGET }

/** What a week change brought: the UI shows it as "year · New: ...". */
@Serializable
data class WeekNews(
    val year: Int,
    val cables: List<CableType>,
    val devices: List<Device>,
    val servers: List<Service>,
    val radios: List<RadioType> = emptyList(),
)

class Node(
    val id: Int,
    val kind: NodeKind,
    /** Set for clients. */
    val device: Device?,
    /** Set for servers: the one service this server delivers. */
    val service: Service?,
    val cellX: Int,
    val cellY: Int,
) {
    val cell = Cell(cellX, cellY)

    /** Where cables attach: the center of the node's own cell. */
    val center = cell.center

    /**
     * Cells the node occupies, [cell] included. One cell, except for a data center (server tier 4), which covers a
     * 2×2 block; the first entry is then the block's top-left cell.
     */
    var footprint: List<Cell> = listOf(cell)
        internal set

    /** Visual center of the [footprint]; equals [center] for one-cell nodes. */
    val footprintCenter get() = Vec2(footprint.map { it.x }.average().toFloat() + 0.5f, footprint.map { it.y }.average().toFloat() + 0.5f)

    /** True for a server at tier [World.Tuning.DATA_CENTER_LEVEL]. */
    val isDataCenter get() = kind == NodeKind.SERVER && level >= World.Tuning.DATA_CENTER_LEVEL

    val maxPorts get() = if (isDataCenter) World.Tuning.DATA_CENTER_PORTS else kind.maxPorts

    /** Requests waiting to be sent (clients only), oldest first. */
    val pending = ArrayDeque<Service>()

    /** 0..1, game over when a client reaches 1. */
    var overload = 0f

    /** Server hardware tier 1..MAX_SERVER_LEVEL: more throughput, drawn as a taller stack; the top tier is a data center. */
    var level = 1

    /** Set for radio nodes. */
    val radio get() = RadioType.of(kind)

    /** WLAN channel of an access point (see [Wifi]); 0 for every other node. */
    var channel = 0
        internal set

    /** True once an access point was switched to 5 GHz: more channels, smaller [radius]. */
    var fiveGhz = false
        internal set

    /** Reach of a radio node in cells, measured between cell centers; 0 for other nodes. */
    val radius get() = if (fiveGhz) Wifi.RADIUS_5_GHZ else radio?.radius ?: 0f

    /** Token bucket for server throughput: one token per delivered packet. */
    internal var tokens = 0f

    internal var requestTimer = 0f
    internal var dispatchCooldown = 0f

    override fun toString() = "$kind#$id(${device ?: service ?: ""})@$cellX,$cellY"
}

/** A connection packets travel on between [a] and [b]: a laid [Cable] or an automatic [RadioLink]. */
sealed interface Link {
    val a: Node
    val b: Node

    /** Bandwidth units in flight at once on the whole [medium]. */
    val capacity: Int
    val length: Float
    val latencyMs: Float

    /** Visual packet speed in cells per second. */
    val speed: Float

    /** Links with the same medium share [capacity]: a cable is its own medium, all links of one radio share the radio. */
    val medium: Any

    fun other(n: Node) = if (n === a) b else a
    fun connects(n: Node) = n === a || n === b

    /** Point at fraction [f] of the way when travelling from [from] to the other end. */
    fun pointFrom(from: Node, f: Float): Vec2
}

/** A laid cable. [layout] runs from [a] to [b]; [waterCells] of it are sea cable. */
class Cable(
    override val a: Node,
    override val b: Node,
    var type: CableType,
    var cost: Int,
    val layout: CableLayout,
    val waterCells: Int,
) : Link {
    init {
        require(layout.start == a.center && layout.end == b.center) { "layout must run from a to b" }
    }

    override val capacity get() = type.capacity
    override val length get() = layout.length
    override val latencyMs get() = length * type.msPerCell
    override val speed get() = type.speed
    override val medium get() = this
    val crossesWater get() = waterCells > 0

    /** [World.time] when the cable was laid, for the laying animation; minus infinity for loaded cables. */
    var builtAt = Float.NEGATIVE_INFINITY
        internal set

    override fun pointFrom(from: Node, f: Float): Vec2 = layout.pointAt(if (from === a) f else 1f - f)
}

/** A path from a client to a server. [pingMs] is the round trip: request there plus response back the same way. */
class Route(val nodes: List<Node>, val pingMs: Float) {
    val oneWayMs get() = pingMs / 2f
}

/**
 * A packet reaching the end of a leg, for effects: a request arriving at its server ([isResponse] false, [node] is the
 * server) or a response delivered to its client ([isResponse] true, [node] is the client), at [World.time] [time].
 */
class Arrival(val node: Node, val service: Service, val isResponse: Boolean, val time: Float)

/**
 * A packet on its way. A request travels [route] from its [origin] client to a server; there it turns into a response
 * ([isResponse]) that travels the same route back and counts as delivered when it reaches [origin].
 * Both directions share cable capacity.
 */
class Packet(val service: Service, val origin: Node, val route: List<Node>, val isResponse: Boolean = false) {
    /** Capacity units the packet takes: the service bandwidth, or the acknowledgement size for an upload's response. */
    val size get() = if (isResponse) service.responseSize else service.bandwidth

    /** Index of the node the packet last left (or waits at). */
    var hop = 0

    /** Progress 0..1 along the cable between route[hop] and route[hop + 1]; -1 while waiting at route[hop]. */
    var progress = -1f

    /** False once the packet reached the end of its route (it is removed at the end of the frame). */
    val inTransit get() = hop < route.size - 1

    val from get() = route[hop]
    val to get() = route[hop + 1]
}

object Geometry {
    fun polylineLength(pts: List<Vec2>): Float {
        var l = 0f
        for (i in 0 until pts.size - 1) l += hypot(pts[i + 1].x - pts[i].x, pts[i + 1].y - pts[i].y)
        return l
    }

    fun pointAlong(pts: List<Vec2>, f: Float): Vec2 {
        val total = polylineLength(pts)
        var d = f.coerceIn(0f, 1f) * total
        for (i in 0 until pts.size - 1) {
            val seg = hypot(pts[i + 1].x - pts[i].x, pts[i + 1].y - pts[i].y)
            if (d <= seg || i == pts.size - 2) {
                val u = if (seg > 0f) min(1f, d / seg) else 0f
                return Vec2(pts[i].x + (pts[i + 1].x - pts[i].x) * u, pts[i].y + (pts[i + 1].y - pts[i].y) * u)
            }
            d -= seg
        }
        return pts.last()
    }

    fun distToSegment(p: Vec2, a: Vec2, b: Vec2): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val l = dx * dx + dy * dy
        val u = if (l > 0f) (((p.x - a.x) * dx + (p.y - a.y) * dy) / l).coerceIn(0f, 1f) else 0f
        return hypot(p.x - a.x - u * dx, p.y - a.y - u * dy)
    }

    fun distToPolyline(p: Vec2, pts: List<Vec2>): Float {
        var d = Float.MAX_VALUE
        for (i in 0 until pts.size - 1) d = min(d, distToSegment(p, pts[i], pts[i + 1]))
        return d
    }
}
