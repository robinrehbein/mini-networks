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
        const val WEEK_SECONDS = 45f
        const val FIRST_YEAR = 1995
        const val YEARS_PER_WEEK = 3
        const val ROUTER_MS = 4f
        const val MAX_PENDING = 6
        const val OVERLOAD_SECONDS = 18f
        const val RECOVER_SECONDS = 30f
        const val DISPATCH_COOLDOWN = 0.45f
        const val START_BUDGET = 24
        const val START_ROUTERS = 2
        const val WEEKLY_BUDGET_BONUS = 12
        const val WATER_EXTRA_PER_CELL = 2
        const val MAX_SERVER_LEVEL = 3
        /** Packets per second a server can take at level 1, 2, 3. */
        val SERVER_RATE = floatArrayOf(1.5f, 3f, 5f)
        /** Budget to reach level 2, 3. */
        val SERVER_UPGRADE_COST = intArrayOf(8, 16)
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
    var budget = Tuning.START_BUDGET; private set
    var routersAvailable = Tuning.START_ROUTERS; private set
    var gameOver = false; private set
    var failedNode: Node? = null; private set

    /** Short German message for the HUD, e.g. "Neu: Glasfaser". */
    var lastEvent: String? = null; private set
    var lastEventTime = 0f; private set

    val weekProgress get() = (time % Tuning.WEEK_SECONDS) / Tuning.WEEK_SECONDS
    val year get() = Tuning.FIRST_YEAR + (week - 1) * Tuning.YEARS_PER_WEEK
    val unlockedCables get() = CableType.entries.filter { it.unlockWeek <= week }
    private val unlockedDevices get() = Device.entries.filter { it.unlockWeek <= week }
    val availableServices get() = nodes.filter { it.kind == NodeKind.SERVER }.mapNotNull { it.service }.toSet()

    private var clientSpawnTimer = 6f
    private val routeCache = HashMap<Pair<Int, Service>, Route?>()

    init {
        carveRiver()
        if (spawnInitialNodes) {
            addServer(Service.MAIL, 2, 2)
            addServer(Service.CALL, cols - 3, rows - 3)
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

    private fun addNode(kind: NodeKind, device: Device?, service: Service?, cx: Int, cy: Int): Node {
        val n = Node(nextId++, kind, device, service, cx, cy)
        n.requestTimer = 2f + rng.nextFloat() * 3f
        nodes += n
        routeCache.clear()
        return n
    }

    fun addClient(device: Device, cx: Int, cy: Int) = addNode(NodeKind.CLIENT, device, null, cx, cy)
    fun addServer(service: Service, cx: Int, cy: Int) = addNode(NodeKind.SERVER, null, service, cx, cy)
    fun addRouter(cx: Int, cy: Int) = addNode(NodeKind.ROUTER, null, null, cx, cy)

    private fun randomFreeCell(minSpacing: Int = 2): Pair<Int, Int>? {
        repeat(300) {
            val cx = 1 + rng.nextInt(cols - 2)
            val cy = 1 + rng.nextInt(rows - 2)
            if (isFree(cx, cy) && nodes.all { max(abs(it.cellX - cx), abs(it.cellY - cy)) >= minSpacing }) return cx to cy
        }
        return null
    }

    private fun spawnClient() {
        val served = availableServices
        val candidates = unlockedDevices.filter { d -> d.services.any { it in served } }
        if (candidates.isEmpty()) return
        // Newer devices show up more often once unlocked.
        val weighted = candidates.flatMap { d -> List(1 + d.unlockWeek) { d } }
        val (cx, cy) = randomFreeCell() ?: return
        addClient(weighted[rng.nextInt(weighted.size)], cx, cy)
    }

    private fun spawnServer(service: Service): Boolean {
        val (cx, cy) = randomFreeCell(3) ?: randomFreeCell() ?: return false
        addServer(service, cx, cy)
        return true
    }

    private fun event(msg: String) {
        lastEvent = msg
        lastEventTime = time
    }

    // ---------------------------------------------------------------- player actions

    fun cableBetween(a: Node, b: Node) = cables.firstOrNull { (it.a === a && it.b === b) || (it.a === b && it.b === a) }

    fun ports(n: Node) = cables.count { it.connects(n) }

    /**
     * The layout a new cable from [a] to [b] gets: an L along the grid with the given [bend], or, without one,
     * the bend that crosses fewer water cells (horizontal first on a tie).
     */
    fun planLayout(a: Cell, b: Cell, bend: Bend? = null): CableLayout {
        if (bend != null) return CableLayout.between(a, b, bend)
        val h = CableLayout.between(a, b, Bend.HORIZONTAL_FIRST)
        val v = CableLayout.between(a, b, Bend.VERTICAL_FIRST)
        return if (waterCellsOn(v) < waterCellsOn(h)) v else h
    }

    fun planLayout(a: Node, b: Node, bend: Bend? = null) = planLayout(a.cell, b.cell, bend)

    fun waterCellsOn(layout: CableLayout) = layout.cells.count { isWater(it.x, it.y) }

    /** Cable cost in budget units: cells walked times type price, water cells cost extra (sea cable). */
    fun cableCost(layout: CableLayout, type: CableType): Int =
        layout.steps * type.costPerCell + waterCellsOn(layout) * Tuning.WATER_EXTRA_PER_CELL

    fun cableCost(a: Node, b: Node, type: CableType, bend: Bend? = null) = cableCost(planLayout(a, b, bend), type)

    /** Null when the cable is allowed, otherwise a short German reason for the UI. */
    fun connectError(a: Node, b: Node, type: CableType, bend: Bend? = null): String? = when {
        a === b || a.cell == b.cell -> "Gleicher Knoten"
        cableBetween(a, b) != null -> "Schon verbunden"
        type.unlockWeek > week -> "${type.label} noch nicht erfunden"
        ports(a) >= a.kind.maxPorts -> "${a.label}: alle Ports belegt"
        ports(b) >= b.kind.maxPorts -> "${b.label}: alle Ports belegt"
        cableCost(a, b, type, bend) > budget -> "Budget reicht nicht"
        else -> null
    }

    /** Lays a cable along [planLayout] with [bend]. */
    fun connect(a: Node, b: Node, type: CableType, bend: Bend? = null): Boolean {
        if (gameOver || connectError(a, b, type, bend) != null) return false
        val layout = planLayout(a, b, bend)
        val cost = cableCost(layout, type)
        cables += Cable(a, b, type, cost, layout, waterCellsOn(layout))
        budget -= cost
        routeCache.clear()
        return true
    }

    /** Swap an existing cable to a better technology, paying only the difference. */
    fun upgradeError(c: Cable, type: CableType): String? {
        val diff = cableCost(c.layout, type) - c.cost
        return when {
            type.ordinal <= c.type.ordinal -> "Kein Upgrade"
            type.unlockWeek > week -> "${type.label} noch nicht erfunden"
            diff > budget -> "Budget reicht nicht"
            else -> null
        }
    }

    fun upgrade(c: Cable, type: CableType): Boolean {
        if (gameOver || upgradeError(c, type) != null) return false
        val newCost = cableCost(c.layout, type)
        budget -= newCost - c.cost
        c.cost = newCost
        c.type = type
        routeCache.clear()
        return true
    }

    fun removeCable(c: Cable) {
        if (!cables.remove(c)) return
        budget += c.cost
        // Packets on or heading into this cable go back into their client's queue.
        val lost = packets.filter { p -> cableBetween(p.from, p.to) == null }
        lost.forEach { it.origin.pending.addFirst(it.service) }
        packets.removeAll(lost.toSet())
        routeCache.clear()
    }

    fun serverRate(n: Node) = Tuning.SERVER_RATE[n.level - 1]

    /** Null when the server can be upgraded, otherwise a short German reason for the UI. */
    fun serverUpgradeError(n: Node): String? = when {
        n.kind != NodeKind.SERVER -> "Kein Server"
        n.level >= Tuning.MAX_SERVER_LEVEL -> "Maximale Stufe"
        Tuning.SERVER_UPGRADE_COST[n.level - 1] > budget -> "Budget reicht nicht"
        else -> null
    }

    fun upgradeServer(n: Node): Boolean {
        if (gameOver || serverUpgradeError(n) != null) return false
        budget -= Tuning.SERVER_UPGRADE_COST[n.level - 1]
        n.level++
        return true
    }

    /** True while a server has no capacity left and packets queue on its cables. */
    fun serverBusy(n: Node) = n.kind == NodeKind.SERVER && n.tokens < 1f

    fun placeRouter(cx: Int, cy: Int): Node? {
        if (gameOver || routersAvailable <= 0 || !isFree(cx, cy)) return null
        routersAvailable--
        return addRouter(cx, cy)
    }

    fun nodeNear(p: Vec2, radius: Float = 0.7f): Node? =
        nodes.minByOrNull { hypot(it.center.x - p.x, it.center.y - p.y) }
            ?.takeIf { hypot(it.center.x - p.x, it.center.y - p.y) <= radius }

    // ---------------------------------------------------------------- routing

    /**
     * Lowest-ping route from [client] to any server of [service], using only cables wide enough for the
     * service. Null if none exists or if the best route breaks the service's ping limit.
     */
    fun routeFor(client: Node, service: Service): Route? {
        val r = bestRoute(client, service) ?: return null
        val limit = service.maxPingMs ?: return r
        return r.takeIf { it.pingMs <= limit }
    }

    /** Best route ignoring the ping limit, so the UI can explain "Ping zu hoch". */
    fun bestRoute(client: Node, service: Service): Route? =
        routeCache.getOrPut(client.id to service) { computeRoute(client, service) }

    private fun computeRoute(client: Node, service: Service): Route? {
        val dist = HashMap<Node, Float>().apply { put(client, 0f) }
        val prev = HashMap<Node, Node>()
        val open = mutableListOf(client)
        val done = HashSet<Node>()
        while (open.isNotEmpty()) {
            val cur = open.minBy { dist.getValue(it) }
            open.remove(cur)
            if (!done.add(cur)) continue
            if (cur.kind == NodeKind.SERVER && cur.service == service) {
                val path = ArrayList<Node>()
                var n: Node? = cur
                while (n != null) { path.add(0, n); n = prev[n] }
                return Route(path, dist.getValue(cur))
            }
            // Only the origin and routers/other clients forward traffic; foreign servers are dead ends.
            if (cur !== client && cur.kind == NodeKind.SERVER) continue
            val hopCost = if (cur === client) 0f else Tuning.ROUTER_MS
            for (c in cables) {
                if (!c.connects(cur) || c.capacity < service.bandwidth) continue
                val nb = c.other(cur)
                val d = dist.getValue(cur) + hopCost + c.latencyMs
                if (d < (dist[nb] ?: Float.MAX_VALUE)) {
                    dist[nb] = d
                    prev[nb] = cur
                    open += nb
                }
            }
        }
        return null
    }

    /** Where [p] is in world space: on its cable's layout, or at the node it waits at. */
    fun packetPosition(p: Packet): Vec2 {
        if (!p.inTransit || p.progress < 0f) return if (p.inTransit) p.from.center else p.route.last().center
        val cable = cableBetween(p.from, p.to) ?: return p.from.center
        return cable.pointFrom(p.from, p.progress)
    }

    /** Bandwidth units currently travelling on [c]. */
    fun cableLoad(c: Cable) = packets.sumOf { if (it.inTransit && it.progress >= 0f && cableBetween(it.from, it.to) === c) it.size else 0 }

    // ---------------------------------------------------------------- simulation

    /** Adds budget and routers, for tests and a future debug menu. */
    @DebugApi
    fun grant(extraBudget: Int, extraRouters: Int = 0) {
        budget += extraBudget
        routersAvailable += extraRouters
    }

    /** Jumps the calendar without simulating, for tests and a future debug menu. */
    @DebugApi
    fun jumpToWeek(target: Int) {
        time = (target - 1) * Tuning.WEEK_SECONDS
        week = target
    }

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

        val served = availableServices
        for (n in nodes) {
            if (n.kind != NodeKind.CLIENT) continue
            n.requestTimer -= dt
            if (n.requestTimer <= 0f) {
                val wants = n.device!!.services.filter { it in served }
                if (wants.isNotEmpty()) n.pending.addLast(wants[rng.nextInt(wants.size)])
                n.requestTimer = max(1.6f, 5.5f - week * 0.35f) + rng.nextFloat() * 2f
            }
            n.dispatchCooldown -= dt
            if (n.pending.isNotEmpty() && n.dispatchCooldown <= 0f) dispatch(n)
        }

        movePackets(dt)

        for (n in nodes) {
            if (n.kind != NodeKind.CLIENT) continue
            n.overload = if (n.pending.size >= Tuning.MAX_PENDING) n.overload + dt / Tuning.OVERLOAD_SECONDS
            else max(0f, n.overload - dt / Tuning.RECOVER_SECONDS)
            if (n.overload >= 1f) {
                n.overload = 1f
                gameOver = true
                failedNode = n
                return
            }
        }
    }

    /** Sends the oldest request that currently has a valid route and room on its first cable. */
    private fun dispatch(client: Node) {
        for (service in client.pending) {
            val route = routeFor(client, service) ?: continue
            val first = cableBetween(route.nodes[0], route.nodes[1]) ?: continue
            if (cableLoad(first) + service.bandwidth > first.capacity) continue
            packets += Packet(service, client, route.nodes).apply { progress = 0f }
            client.pending.remove(service)
            client.dispatchCooldown = Tuning.DISPATCH_COOLDOWN
            return
        }
    }

    private fun movePackets(dt: Float) {
        for (n in nodes) if (n.kind == NodeKind.SERVER) n.tokens = minOf(serverRate(n), n.tokens + serverRate(n) * dt)
        val arrived = ArrayList<Packet>()
        for (p in packets) {
            val cable = cableBetween(p.from, p.to)
            if (cable == null) { arrived += p; p.origin.pending.addFirst(p.service); continue }
            if (p.progress < 0f) {
                if (cableLoad(cable) + p.size <= cable.capacity) p.progress = 0f else continue
            }
            p.progress += cable.type.speed * dt / cable.length
            if (p.progress >= 1f) {
                val last = p.hop + 1 >= p.route.size - 1
                if (last && p.to.tokens < 1f) {
                    // Server is saturated: the packet waits at the end of the cable and keeps blocking it.
                    p.progress = 0.999f
                    continue
                }
                p.hop++
                if (last) { p.route.last().tokens -= 1f; arrived += p; delivered++ } else p.progress = -1f
            }
        }
        if (arrived.isNotEmpty()) packets.removeAll(arrived.toSet())
    }

    private fun onNewWeek() {
        budget += Tuning.WEEKLY_BUDGET_BONUS
        routersAvailable++
        val news = ArrayList<String>()
        CableType.entries.filter { it.unlockWeek == week }.forEach { news += it.label }
        Device.entries.filter { it.unlockWeek == week }.forEach { news += it.label }
        val server = when {
            week == 3 -> Service.GAMING
            week == 4 -> Service.STREAMING
            week >= 6 && week % 2 == 0 -> Service.entries[rng.nextInt(Service.entries.size)]
            else -> null
        }
        if (server != null && spawnServer(server)) news += "${server.label}-Server"
        event(if (news.isEmpty()) "$year · +${Tuning.WEEKLY_BUDGET_BONUS} Budget, +1 Router" else "$year · Neu: ${news.joinToString(", ")}")
    }
}

val Node.label get() = device?.label ?: when (kind) {
    NodeKind.SERVER -> "Server"
    NodeKind.ROUTER -> "Router"
    NodeKind.CLIENT -> "Kunde"
}
