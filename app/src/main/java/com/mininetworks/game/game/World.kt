package com.mininetworks.game.game

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The complete game state and rules. Pure Kotlin, no Android types, so it can be unit-tested on the JVM
 * and drawn by any renderer (flat, isometric, pixel ...).
 */
class World(
    val cols: Int = 16,
    val rows: Int = 10,
    seed: Long = 7L,
    private val spawnInitialNodes: Boolean = true,
) {
    object Tuning {
        const val WEEK_SECONDS = 40f
        const val PACKET_SPEED = 2.2f // cells per second
        const val MAX_PENDING = 6
        const val OVERLOAD_SECONDS = 18f
        const val RECOVER_SECONDS = 30f
        const val DISPATCH_COOLDOWN = 0.45f
        const val START_CABLE_BUDGET = 22
        const val START_ROUTERS = 2
        const val WEEKLY_CABLE_BONUS = 10
        const val WATER_COST_FACTOR = 3
    }

    private val rng = Random(seed)
    private var nextId = 0

    val water = Array(rows) { BooleanArray(cols) }
    val nodes = mutableListOf<Node>()
    val cables = mutableListOf<Cable>()
    val packets = mutableListOf<Packet>()

    var time = 0f; private set
    var week = 1; private set
    var delivered = 0; private set
    var cableBudget = Tuning.START_CABLE_BUDGET; private set
    var routersAvailable = Tuning.START_ROUTERS; private set
    var gameOver = false; private set
    var failedNode: Node? = null; private set

    /** Set by the UI for one frame to show a toast-like hint, e.g. "Neuer Server: Games". */
    var lastEvent: String? = null
    var lastEventTime = 0f; private set

    val weekProgress get() = (time % Tuning.WEEK_SECONDS) / Tuning.WEEK_SECONDS

    private var clientSpawnTimer = 6f
    private val routeCache = HashMap<Int, List<Node>?>()

    init {
        carveRiver()
        if (spawnInitialNodes) {
            addNodeAt(NodeKind.SERVER, DataType.VIDEO, 2, 2)
            addNodeAt(NodeKind.SERVER, DataType.MAIL, cols - 3, rows - 3)
            repeat(3) { spawnClient() }
        }
    }

    // ---------------------------------------------------------------- setup

    private fun carveRiver() {
        val base = cols / 2
        val phase = rng.nextFloat() * 6f
        for (y in 0 until rows) {
            val x = (base + sin(y * 0.6f + phase) * 1.3f).roundToInt().coerceIn(1, cols - 2)
            water[y][x] = true
        }
    }

    fun isWater(cx: Int, cy: Int) = cy in 0 until rows && cx in 0 until cols && water[cy][cx]

    fun isFree(cx: Int, cy: Int): Boolean =
        cx in 0 until cols && cy in 0 until rows && !water[cy][cx] &&
            nodes.none { it.cellX == cx && it.cellY == cy }

    fun addNodeAt(kind: NodeKind, type: DataType?, cx: Int, cy: Int): Node {
        val n = Node(nextId++, kind, type, cx, cy)
        n.requestTimer = 2f + rng.nextFloat() * 3f
        nodes += n
        routeCache.clear()
        return n
    }

    private fun randomFreeCell(minSpacing: Int = 2): Pair<Int, Int>? {
        repeat(300) {
            val cx = 1 + rng.nextInt(cols - 2)
            val cy = 1 + rng.nextInt(rows - 2)
            if (isFree(cx, cy) && nodes.all { max(abs(it.cellX - cx), abs(it.cellY - cy)) >= minSpacing }) return cx to cy
        }
        return null
    }

    private fun spawnClient() {
        val types = nodes.filter { it.kind == NodeKind.SERVER }.mapNotNull { it.type }.distinct()
        if (types.isEmpty()) return
        val (cx, cy) = randomFreeCell() ?: return
        addNodeAt(NodeKind.CLIENT, types[rng.nextInt(types.size)], cx, cy)
    }

    private fun spawnServer(type: DataType) {
        val (cx, cy) = randomFreeCell(3) ?: randomFreeCell() ?: return
        addNodeAt(NodeKind.SERVER, type, cx, cy)
        event("Neuer Server: ${type.label}")
    }

    private fun event(msg: String) {
        lastEvent = msg
        lastEventTime = time
    }

    // ---------------------------------------------------------------- player actions

    fun cableBetween(a: Node, b: Node) = cables.firstOrNull { (it.a === a && it.b === b) || (it.a === b && it.b === a) }

    fun ports(n: Node) = cables.count { it.connects(n) }

    /** Cable cost in budget units: grid length, water cells cost extra. */
    fun cableCost(a: Node, b: Node): Int = Geometry.chebyshev(a, b) + waterCellsOn(a, b) * (Tuning.WATER_COST_FACTOR - 1)

    fun waterCellsOn(a: Node, b: Node): Int {
        val pts = Geometry.octo(a.center, b.center)
        val len = Geometry.polylineLength(pts)
        val steps = max(1, (len * 4).toInt())
        val seen = HashSet<Int>()
        for (i in 0..steps) {
            val p = Geometry.pointAlong(pts, i / steps.toFloat())
            val cx = p.x.toInt()
            val cy = p.y.toInt()
            if (isWater(cx, cy)) seen += cy * cols + cx
        }
        return seen.size
    }

    /** Null when the cable is allowed, otherwise a short German reason for the UI. */
    fun connectError(a: Node, b: Node): String? = when {
        a === b -> "Gleicher Knoten"
        cableBetween(a, b) != null -> "Schon verbunden"
        ports(a) >= a.kind.maxPorts -> "${a.kind.label}: alle Ports belegt"
        ports(b) >= b.kind.maxPorts -> "${b.kind.label}: alle Ports belegt"
        cableCost(a, b) > cableBudget -> "Zu wenig Kabel"
        else -> null
    }

    fun connect(a: Node, b: Node): Boolean {
        if (gameOver || connectError(a, b) != null) return false
        val cost = cableCost(a, b)
        cables += Cable(a, b, cost, waterCellsOn(a, b) > 0)
        cableBudget -= cost
        routeCache.clear()
        return true
    }

    fun removeCable(c: Cable) {
        if (!cables.remove(c)) return
        cableBudget += c.cost
        // Packets on or heading into this cable are bounced back to their client.
        val lost = packets.filter { p -> p.hop < p.route.size - 1 && cableBetween(p.from, p.to) == null }
        lost.forEach { it.origin.pending++ }
        packets.removeAll(lost.toSet())
        routeCache.clear()
    }

    fun placeRouter(cx: Int, cy: Int): Node? {
        if (gameOver || routersAvailable <= 0 || !isFree(cx, cy)) return null
        routersAvailable--
        return addNodeAt(NodeKind.ROUTER, null, cx, cy)
    }

    fun nodeNear(p: Vec2, radius: Float = 0.7f): Node? =
        nodes.minByOrNull { hypot(it.center.x - p.x, it.center.y - p.y) }
            ?.takeIf { hypot(it.center.x - p.x, it.center.y - p.y) <= radius }

    // ---------------------------------------------------------------- routing

    /** Shortest path (by cable length) from a client to any server of its type, or null. */
    fun routeFor(client: Node): List<Node>? = routeCache.getOrPut(client.id) { computeRoute(client) }

    private fun computeRoute(client: Node): List<Node>? {
        val want = client.type ?: return null
        val dist = HashMap<Node, Float>().apply { put(client, 0f) }
        val prev = HashMap<Node, Node>()
        val open = mutableListOf(client)
        val done = HashSet<Node>()
        while (open.isNotEmpty()) {
            val cur = open.minBy { dist.getValue(it) }
            open.remove(cur)
            if (!done.add(cur)) continue
            if (cur.kind == NodeKind.SERVER && cur.type == want) {
                val path = ArrayList<Node>()
                var n: Node? = cur
                while (n != null) { path.add(0, n); n = prev[n] }
                return path
            }
            // Servers of another type do not forward traffic.
            if (cur !== client && cur.kind == NodeKind.SERVER) continue
            for (c in cables) {
                if (!c.connects(cur)) continue
                val nb = c.other(cur)
                val d = dist.getValue(cur) + c.length
                if (d < (dist[nb] ?: Float.MAX_VALUE)) {
                    dist[nb] = d
                    prev[nb] = cur
                    open += nb
                }
            }
        }
        return null
    }

    fun cableLoad(c: Cable) = packets.count { it.progress >= 0f && cableBetween(it.from, it.to) === c }

    // ---------------------------------------------------------------- simulation

    fun update(dt: Float) {
        if (gameOver) return
        val prevWeek = week
        time += dt
        week = 1 + (time / Tuning.WEEK_SECONDS).toInt()
        if (week != prevWeek) onNewWeek()

        clientSpawnTimer -= dt
        if (clientSpawnTimer <= 0f) {
            spawnClient()
            clientSpawnTimer = max(4f, 11f - week * 1.2f) + rng.nextFloat() * 2f
        }

        for (n in nodes) {
            if (n.kind != NodeKind.CLIENT) continue
            n.requestTimer -= dt
            if (n.requestTimer <= 0f) {
                n.pending++
                n.requestTimer = max(1.6f, 5.5f - week * 0.35f) + rng.nextFloat() * 2f
            }
            n.dispatchCooldown -= dt
            if (n.pending > 0 && n.dispatchCooldown <= 0f) dispatch(n)
        }

        movePackets(dt)

        for (n in nodes) {
            if (n.kind != NodeKind.CLIENT) continue
            n.overload = if (n.pending >= Tuning.MAX_PENDING) n.overload + dt / Tuning.OVERLOAD_SECONDS
            else max(0f, n.overload - dt / Tuning.RECOVER_SECONDS)
            if (n.overload >= 1f) {
                n.overload = 1f
                gameOver = true
                failedNode = n
                return
            }
        }
    }

    private fun dispatch(client: Node) {
        val route = routeFor(client) ?: return
        val first = cableBetween(route[0], route[1]) ?: return
        if (cableLoad(first) >= first.capacity) return
        packets += Packet(client.type!!, client, route).apply { progress = 0f }
        client.pending--
        client.dispatchCooldown = Tuning.DISPATCH_COOLDOWN
    }

    private fun movePackets(dt: Float) {
        val arrived = ArrayList<Packet>()
        for (p in packets) {
            val cable = cableBetween(p.from, p.to)
            if (cable == null) { arrived += p; p.origin.pending++; continue }
            if (p.progress < 0f) {
                if (cableLoad(cable) < cable.capacity) p.progress = 0f else continue
            }
            p.progress += Tuning.PACKET_SPEED * dt / cable.length
            if (p.progress >= 1f) {
                p.hop++
                if (p.hop >= p.route.size - 1) { arrived += p; delivered++ } else p.progress = -1f
            }
        }
        if (arrived.isNotEmpty()) packets.removeAll(arrived.toSet())
    }

    private fun onNewWeek() {
        cableBudget += Tuning.WEEKLY_CABLE_BONUS
        routersAvailable++
        when {
            week == 3 -> spawnServer(DataType.GAME)
            week >= 5 && week % 2 == 1 -> spawnServer(DataType.entries[rng.nextInt(DataType.entries.size)])
            else -> event("Woche $week: +${Tuning.WEEKLY_CABLE_BONUS} Kabel, +1 Router")
        }
    }
}

val DataType.label get() = when (this) {
    DataType.VIDEO -> "Video"
    DataType.MAIL -> "Mail"
    DataType.GAME -> "Games"
}

val NodeKind.label get() = when (this) {
    NodeKind.CLIENT -> "Kunde"
    NodeKind.SERVER -> "Server"
    NodeKind.ROUTER -> "Router"
}
