package com.mininetworks.game.game

import kotlin.math.hypot
import kotlin.math.min

/** Point in world space. One unit = one grid cell. */
data class Vec2(val x: Float, val y: Float)

enum class Shape { CIRCLE, SQUARE, TRIANGLE, DIAMOND }

/**
 * What a device wants from the network. Each service has a bandwidth need (packet size in capacity units)
 * and optionally a ping limit: real-time services fail on slow routes even when bandwidth is free.
 * The shape is the primary signal (colorblind-safe), color only supports it.
 */
enum class Service(val label: String, val shape: Shape, val bandwidth: Int, val maxPingMs: Int?) {
    MAIL("Mail", Shape.SQUARE, 1, null),
    CALL("Telefonie", Shape.DIAMOND, 1, 150),
    GAMING("Gaming", Shape.TRIANGLE, 1, 60),
    STREAMING("Streaming", Shape.CIRCLE, 3, null),
}

/** Client devices. They appear over the eras and each asks for a mix of services. */
enum class Device(val label: String, val services: List<Service>, val unlockWeek: Int) {
    PC("PC", listOf(Service.MAIL, Service.GAMING), 1),
    PHONE("Telefon", listOf(Service.CALL), 1),
    LAPTOP("Laptop", listOf(Service.MAIL, Service.STREAMING, Service.CALL), 2),
    CONSOLE("Konsole", listOf(Service.GAMING), 3),
    SMARTPHONE("Smartphone", listOf(Service.CALL, Service.STREAMING, Service.MAIL), 4),
    TV("Smart-TV", listOf(Service.STREAMING), 4),
    TABLET("Tablet", listOf(Service.STREAMING, Service.MAIL), 5),
    WATCH("Smartwatch", listOf(Service.CALL), 6),
}

/**
 * Cable technologies, unlocked era by era.
 * capacity: bandwidth units in flight at once. msPerCell: latency per grid cell. speed: visual packet speed.
 */
enum class CableType(
    val label: String,
    val capacity: Int,
    val msPerCell: Float,
    val speed: Float,
    val costPerCell: Int,
    val unlockWeek: Int,
) {
    ISDN("ISDN", 2, 22f, 1.4f, 1, 1),
    DSL("DSL", 4, 11f, 2.0f, 1, 2),
    COAX("Kabel", 6, 8f, 2.4f, 2, 3),
    FIBER("Glasfaser", 12, 2.5f, 3.6f, 3, 5),
}

enum class NodeKind(val maxPorts: Int) {
    CLIENT(2),
    SERVER(4),
    ROUTER(6),
}

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
    val center = cell.center

    /** Requests waiting to be sent (clients only), oldest first. */
    val pending = ArrayDeque<Service>()

    /** 0..1, game over when a client reaches 1. */
    var overload = 0f

    /** Server hardware tier 1..MAX_SERVER_LEVEL: more throughput, drawn as a taller stack. */
    var level = 1

    /** Token bucket for server throughput: one token per delivered packet. */
    internal var tokens = 0f

    internal var requestTimer = 0f
    internal var dispatchCooldown = 0f

    override fun toString() = "$kind#$id(${device ?: service ?: ""})@$cellX,$cellY"
}

/** A laid cable. [layout] runs from [a] to [b]; [waterCells] of it are sea cable. */
class Cable(val a: Node, val b: Node, var type: CableType, var cost: Int, val layout: CableLayout, val waterCells: Int) {
    init {
        require(layout.start == a.center && layout.end == b.center) { "layout must run from a to b" }
    }

    val capacity get() = type.capacity
    val length get() = layout.length
    val latencyMs get() = length * type.msPerCell
    val crossesWater get() = waterCells > 0

    fun other(n: Node) = if (n === a) b else a
    fun connects(n: Node) = n === a || n === b

    /** Point at fraction [f] of the way when travelling from [from] to the other end. */
    fun pointFrom(from: Node, f: Float): Vec2 = layout.pointAt(if (from === a) f else 1f - f)
}

class Route(val nodes: List<Node>, val pingMs: Float)

/** A request travelling from a client to a matching server. */
class Packet(val service: Service, val origin: Node, val route: List<Node>) {
    val size get() = service.bandwidth

    /** Index of the node the packet last left (or waits at). */
    var hop = 0

    /** Progress 0..1 along the cable between route[hop] and route[hop + 1]; -1 while waiting at route[hop]. */
    var progress = -1f

    /** False once the packet reached its server (it is removed at the end of the frame). */
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
