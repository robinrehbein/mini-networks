package com.mininetworks.game.game

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

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
    val center = Vec2(cellX + 0.5f, cellY + 0.5f)

    /** Requests waiting to be sent (clients only), oldest first. */
    val pending = ArrayDeque<Service>()

    /** 0..1, game over when a client reaches 1. */
    var overload = 0f

    internal var requestTimer = 0f
    internal var dispatchCooldown = 0f

    override fun toString() = "$kind#$id(${device ?: service ?: ""})@$cellX,$cellY"
}

class Cable(val a: Node, val b: Node, var type: CableType, var cost: Int, val crossesWater: Boolean) {
    val capacity get() = type.capacity
    val length: Float = Geometry.polylineLength(Geometry.octo(a.center, b.center))
    val latencyMs get() = length * type.msPerCell

    fun other(n: Node) = if (n === a) b else a
    fun connects(n: Node) = n === a || n === b
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
    /** Mini-Metro routing: diagonal first, then straight. Returns 3 points (middle may equal an end). */
    fun octo(a: Vec2, b: Vec2): List<Vec2> {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val d = min(abs(dx), abs(dy))
        return listOf(a, Vec2(a.x + sign(dx) * d, a.y + sign(dy) * d), b)
    }

    /** Grid routing for isometric roads: horizontal first, then vertical. */
    fun lPath(a: Vec2, b: Vec2): List<Vec2> = listOf(a, Vec2(b.x, a.y), b)

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

    fun chebyshev(a: Node, b: Node) = max(abs(a.cellX - b.cellX), abs(a.cellY - b.cellY))
}
