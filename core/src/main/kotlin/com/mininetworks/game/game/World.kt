package com.mininetworks.game.game

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The complete game state and rules. Pure Kotlin, no Android types, so it can be unit-tested on the JVM
 * and drawn by any renderer (flat, isometric, pixel ...).
 *
 * The grid is fixed at [cols] × [rows], but only the [unlocked] block in its middle is in play. It starts at
 * [Tuning.START_COLS] × [Tuning.START_ROWS] and grows by one ring of cells every [Tuning.GROWTH_WEEKS] weeks.
 */
class World(
    val cols: Int = 32,
    val rows: Int = 20,
    val seed: Long = 7L,
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
        const val WATER_EXTRA_PER_CELL = 2
        const val MAX_SERVER_LEVEL = 4
        /** Tier 4 "Rechenzentrum": covers a 2×2 block of cells and has [DATA_CENTER_PORTS] ports. */
        const val DATA_CENTER_LEVEL = 4
        const val DATA_CENTER_PORTS = 8
        /** Requests per second a server can take at level 1, 2, 3, 4. */
        val SERVER_RATE = floatArrayOf(1.5f, 3f, 5f, 8f)
        /** Budget to reach level 2, 3, 4. */
        val SERVER_UPGRADE_COST = intArrayOf(8, 16, 28)
        /** Size of the playable block in week 1. */
        const val START_COLS = 16
        const val START_ROWS = 10
        /** The playable block grows by one ring of cells every this many weeks. */
        const val GROWTH_WEEKS = 2
    }

    private var rng = ReplayableRandom(seed)
    private var nextId = 0

    val water = Array(rows) { BooleanArray(cols) }

    /** The whole grid, including cells that are not unlocked yet. */
    val bounds = CellRect(0, 0, cols, rows)

    /** The playable block: nodes spawn and routers are placed only here; the rest of the grid is drawn dimmed. */
    var unlocked = unlockedArea(1); private set

    val nodes = mutableListOf<Node>()
    val cables = mutableListOf<Cable>()
    val packets = mutableListOf<Packet>()

    private val radioLinkList = ArrayList<RadioLink>()

    /**
     * Wireless links, derived from the radio nodes and never stored: every radio links the clients it serves within its
     * radius, nearest first (ties by node id), up to its [radioSlots]. A client already cabled to the radio gets no
     * radio link to it. Rebuilt whenever nodes, cables, channels or bands change.
     */
    val radioLinks: List<RadioLink> get() = radioLinkList

    var time = 0f; private set
    var week = 1; private set
    var delivered = 0; private set
    var budget = Tuning.START_BUDGET; private set
    var routersAvailable = Tuning.START_ROUTERS; private set
    /** WLAN access points in stock, won as [Reward.ACCESS_POINT]. */
    var accessPointsAvailable = 0; private set
    /** Cell towers in stock, won as [Reward.CELL_TOWER]. */
    var cellTowersAvailable = 0; private set
    var gameOver = false; private set
    var failedNode: Node? = null; private set

    /** Open week reward choice. While set, the simulation is paused until [chooseReward] is called. */
    var rewardOffer: RewardOffer? = null; private set

    /** Free server tier upgrades won as [Reward.SERVER_VOUCHER]; the next server upgrades spend these before budget. */
    var serverVouchers = 0; private set

    /** What the last week change unlocked, for the HUD; set at [lastNewsTime]. */
    var lastNews: WeekNews? = null; private set
    var lastNewsTime = 0f; private set

    val weekProgress get() = (time % Tuning.WEEK_SECONDS) / Tuning.WEEK_SECONDS
    val year get() = Tuning.FIRST_YEAR + (week - 1) * Tuning.YEARS_PER_WEEK
    val unlockedCables get() = CableType.entries.filter { it.unlockWeek <= week }
    private val unlockedDevices get() = Device.entries.filter { it.unlockWeek <= week }
    val availableServices get() = nodes.filter { it.kind == NodeKind.SERVER }.mapNotNull { it.service }.toSet()

    /** The [unlocked] block in [week]: the start block plus one ring per [Tuning.GROWTH_WEEKS] weeks, within [bounds]. */
    fun unlockedArea(week: Int): CellRect =
        CellRect.centered(bounds, Tuning.START_COLS, Tuning.START_ROWS).expand((week - 1) / Tuning.GROWTH_WEEKS, bounds)

    private var clientSpawnTimer = 6f
    private val routeCache = HashMap<Pair<Int, Service>, Route?>()

    init {
        carveRiver()
        if (spawnInitialNodes) {
            addServer(Service.MAIL, unlocked.left + 2, unlocked.top + 2)
            addServer(Service.CALL, unlocked.right - 3, unlocked.bottom - 3)
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

    /** True if ([cx], [cy]) is unlocked, dry and not covered by a node. */
    fun isFree(cx: Int, cy: Int): Boolean =
        unlocked.contains(cx, cy) && !water[cy][cx] && nodeAt(Cell(cx, cy)) == null

    /** The node whose footprint covers [cell], if any. */
    fun nodeAt(cell: Cell): Node? = nodes.firstOrNull { cell in it.footprint }

    private fun addNode(kind: NodeKind, device: Device?, service: Service?, cx: Int, cy: Int): Node {
        val n = Node(nextId++, kind, device, service, cx, cy)
        n.requestTimer = 2f + rng.nextFloat() * 3f
        if (kind == NodeKind.ACCESS_POINT) n.channel = Wifi.CHANNELS_2_4_GHZ.first()
        nodes += n
        networkChanged()
        return n
    }

    fun addClient(device: Device, cx: Int, cy: Int) = addNode(NodeKind.CLIENT, device, null, cx, cy)
    fun addServer(service: Service, cx: Int, cy: Int) = addNode(NodeKind.SERVER, null, service, cx, cy)
    fun addRouter(cx: Int, cy: Int) = addNode(NodeKind.ROUTER, null, null, cx, cy)

    /** Adds a radio node without using stock, for tests and setups; see [placeRadio]. */
    fun addRadio(type: RadioType, cx: Int, cy: Int) = addNode(type.kind, null, null, cx, cy)

    /** A random free cell inside the [unlocked] block, one cell away from its edge. */
    private fun randomFreeCell(minSpacing: Int = 2): Pair<Int, Int>? {
        val area = unlocked
        if (area.width < 3 || area.height < 3) return null
        repeat(300) {
            val cx = area.left + 1 + rng.nextInt(area.width - 2)
            val cy = area.top + 1 + rng.nextInt(area.height - 2)
            if (isFree(cx, cy) && nodes.all { n -> n.footprint.all { max(abs(it.x - cx), abs(it.y - cy)) >= minSpacing } }) return cx to cy
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

    /** Null when the cable from [a] to [b] is allowed, otherwise the reason. */
    fun connectError(a: Node, b: Node, type: CableType, bend: Bend? = null): ConnectError? = when {
        a === b || a.cell == b.cell -> ConnectError.SAME_NODE
        cableBetween(a, b) != null -> ConnectError.ALREADY_CONNECTED
        type.unlockWeek > week -> ConnectError.NOT_INVENTED
        ports(a) >= a.maxPorts -> ConnectError.FROM_PORTS_FULL
        ports(b) >= b.maxPorts -> ConnectError.TO_PORTS_FULL
        cableCost(a, b, type, bend) > budget -> ConnectError.NO_BUDGET
        else -> null
    }

    /** Lays a cable along [planLayout] with [bend]. */
    fun connect(a: Node, b: Node, type: CableType, bend: Bend? = null): Boolean {
        if (gameOver || connectError(a, b, type, bend) != null) return false
        val layout = planLayout(a, b, bend)
        val cost = cableCost(layout, type)
        cables += Cable(a, b, type, cost, layout, waterCellsOn(layout))
        budget -= cost
        networkChanged()
        return true
    }

    /** Swap an existing cable to a better technology, paying only the difference. */
    fun upgradeError(c: Cable, type: CableType): CableUpgradeError? {
        val diff = cableCost(c.layout, type) - c.cost
        return when {
            type.ordinal <= c.type.ordinal -> CableUpgradeError.NOT_AN_UPGRADE
            type.unlockWeek > week -> CableUpgradeError.NOT_INVENTED
            diff > budget -> CableUpgradeError.NO_BUDGET
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
        networkChanged()
    }

    /**
     * Rebuilds the [radioLinks] and forgets cached routes. Requests and responses on or heading into a link that no
     * longer exists go back into their client's queue.
     */
    private fun networkChanged() {
        rebuildRadioLinks()
        routeCache.clear()
        val lost = packets.filter { p -> p.inTransit && linkBetween(p.from, p.to) == null }
        if (lost.isEmpty()) return
        lost.forEach { it.origin.pending.addFirst(it.service) }
        packets.removeAll(lost.toSet())
    }

    private fun rebuildRadioLinks() {
        radioLinkList.clear()
        for (r in nodes) {
            val type = r.radio ?: continue
            val capacity = radioCapacity(r)
            val slots = radioSlots(r) ?: Int.MAX_VALUE
            nodes.asSequence()
                .filter { it.kind == NodeKind.CLIENT && type.serves(it.device!!) && cableBetween(r, it) == null }
                .map { it to hypot(it.center.x - r.center.x, it.center.y - r.center.y) }
                .filter { (_, d) -> d <= r.radius + Wifi.EPSILON }
                .sortedWith(compareBy({ it.second }, { it.first.id }))
                .take(slots)
                .forEach { (client, _) -> radioLinkList += RadioLink(r, client, capacity) }
        }
    }

    fun serverRate(n: Node) = Tuning.SERVER_RATE[n.level - 1]

    /**
     * The 2×2 block a data center on [n] would cover, or null if none fits. Candidates are the four blocks that contain
     * the server's cell, tried in the order: server top-left, top-right, bottom-left, bottom-right; the other three cells
     * must be dry, on the map and not covered by another node. Cables may run through them, as through any node cell.
     */
    fun dataCenterFootprint(n: Node): List<Cell>? {
        for ((dx, dy) in DATA_CENTER_ORIGINS) {
            val x0 = n.cellX + dx
            val y0 = n.cellY + dy
            val block = listOf(Cell(x0, y0), Cell(x0 + 1, y0), Cell(x0, y0 + 1), Cell(x0 + 1, y0 + 1))
            if (block.all { it == n.cell || isFree(it.x, it.y) }) return block
        }
        return null
    }

    /** Null when the server can be upgraded, otherwise the reason. */
    fun serverUpgradeError(n: Node): ServerUpgradeError? = when {
        n.kind != NodeKind.SERVER -> ServerUpgradeError.NOT_A_SERVER
        n.level >= Tuning.MAX_SERVER_LEVEL -> ServerUpgradeError.MAX_LEVEL
        n.level + 1 == Tuning.DATA_CENTER_LEVEL && dataCenterFootprint(n) == null -> ServerUpgradeError.NO_SPACE
        serverVouchers == 0 && Tuning.SERVER_UPGRADE_COST[n.level - 1] > budget -> ServerUpgradeError.NO_BUDGET
        else -> null
    }

    /**
     * Raises a server one hardware tier, paid with a voucher if the player has one, otherwise with budget.
     * Reaching [Tuning.DATA_CENTER_LEVEL] claims the [dataCenterFootprint].
     */
    fun upgradeServer(n: Node): Boolean {
        if (gameOver || serverUpgradeError(n) != null) return false
        if (serverVouchers > 0) serverVouchers-- else budget -= Tuning.SERVER_UPGRADE_COST[n.level - 1]
        if (n.level + 1 == Tuning.DATA_CENTER_LEVEL) n.footprint = dataCenterFootprint(n)!!
        n.level++
        return true
    }

    /** True if [n] is a server whose next tier is reachable at all (ignoring budget). */
    private fun canGrow(n: Node) = serverUpgradeError(n).let { it == null || it == ServerUpgradeError.NO_BUDGET }

    /** Rewards that would have an effect right now; a server voucher only while some server can still grow. */
    fun eligibleRewards(): List<Reward> = Reward.entries.filter {
        when (it) {
            Reward.SERVER_VOUCHER -> nodes.any(::canGrow)
            Reward.ACCESS_POINT -> week >= RadioType.WLAN.unlockWeek
            Reward.CELL_TOWER -> week >= RadioType.CELL.unlockWeek
            else -> true
        }
    }

    /** Takes choice [index] of the open [rewardOffer] and resumes the simulation. False if nothing is open. */
    fun chooseReward(index: Int): Boolean {
        val offer = rewardOffer ?: return false
        when (offer.choices.getOrNull(index) ?: return false) {
            Reward.BUDGET -> budget += Rewards.BUDGET
            Reward.ROUTERS -> routersAvailable += Rewards.ROUTERS
            Reward.SERVER_VOUCHER -> serverVouchers++
            Reward.ACCESS_POINT -> accessPointsAvailable += Rewards.ACCESS_POINTS
            Reward.CELL_TOWER -> cellTowersAvailable += Rewards.CELL_TOWERS
        }
        rewardOffer = null
        return true
    }

    /** True while a server has no capacity left and packets queue on its cables. */
    fun serverBusy(n: Node) = n.kind == NodeKind.SERVER && n.tokens < 1f

    fun placeRouter(cx: Int, cy: Int): Node? {
        if (gameOver || routersAvailable <= 0 || !isFree(cx, cy)) return null
        routersAvailable--
        return addRouter(cx, cy)
    }

    /** Radios of [type] in stock. */
    fun radiosAvailable(type: RadioType) = when (type) {
        RadioType.WLAN -> accessPointsAvailable
        RadioType.CELL -> cellTowersAvailable
    }

    /** Places a radio from stock on a free cell, like [placeRouter]. A new access point starts on channel 1. */
    fun placeRadio(type: RadioType, cx: Int, cy: Int): Node? {
        if (gameOver || radiosAvailable(type) <= 0 || !isFree(cx, cy)) return null
        when (type) {
            RadioType.WLAN -> accessPointsAvailable--
            RadioType.CELL -> cellTowersAvailable--
        }
        return addRadio(type, cx, cy)
    }

    // ---------------------------------------------------------------- wireless

    /**
     * Access points that disturb [n]: other access points on the same channel whose radio circles overlap its own.
     * Empty for every other node.
     */
    fun interferers(n: Node): List<Node> {
        if (n.kind != NodeKind.ACCESS_POINT) return emptyList()
        return nodes.filter {
            it !== n && it.kind == NodeKind.ACCESS_POINT && it.channel == n.channel && Wifi.overlaps(n.center, n.radius, it.center, it.radius)
        }
    }

    /** Shared capacity of radio [n] after interference ([Wifi.reduced]); 0 for other nodes. */
    fun radioCapacity(n: Node): Int {
        val type = n.radio ?: return 0
        return Wifi.reduced(type.capacity, interferers(n).size)
    }

    /** How many clients radio [n] links at once after interference, or null for no limit (and for other nodes). */
    fun radioSlots(n: Node): Int? {
        val max = n.radio?.maxDevices ?: return null
        return Wifi.reduced(max, interferers(n).size)
    }

    /** Switches access point [ap] to the next channel of its band (free). False for other nodes. */
    fun cycleChannel(ap: Node): Boolean {
        if (gameOver || ap.kind != NodeKind.ACCESS_POINT) return false
        val channels = if (ap.fiveGhz) Wifi.CHANNELS_5_GHZ else Wifi.CHANNELS_2_4_GHZ
        ap.channel = channels[(channels.indexOf(ap.channel) + 1) % channels.size]
        networkChanged()
        return true
    }

    /** Null when [ap] can switch to 5 GHz, otherwise the reason. */
    fun wifiUpgradeError(ap: Node): WifiUpgradeError? = when {
        ap.kind != NodeKind.ACCESS_POINT -> WifiUpgradeError.NOT_AN_ACCESS_POINT
        ap.fiveGhz -> WifiUpgradeError.ALREADY_5_GHZ
        Wifi.UPGRADE_5_GHZ_COST > budget -> WifiUpgradeError.NO_BUDGET
        else -> null
    }

    /** Switches [ap] to 5 GHz for [Wifi.UPGRADE_5_GHZ_COST]: first 5 GHz channel, radius [Wifi.RADIUS_5_GHZ]. */
    fun upgradeTo5Ghz(ap: Node): Boolean {
        if (gameOver || wifiUpgradeError(ap) != null) return false
        budget -= Wifi.UPGRADE_5_GHZ_COST
        ap.fiveGhz = true
        ap.channel = Wifi.CHANNELS_5_GHZ.first()
        networkChanged()
        return true
    }

    /** The node closest to [p] within [radius], measured to the nearest cell of each node's footprint. */
    fun nodeNear(p: Vec2, radius: Float = 0.7f): Node? =
        nodes.minByOrNull { distance(it, p) }?.takeIf { distance(it, p) <= radius }

    private fun distance(n: Node, p: Vec2) = n.footprint.minOf { hypot(it.center.x - p.x, it.center.y - p.y) }

    // ---------------------------------------------------------------- routing

    /** The cable or radio link between [a] and [b], if any. */
    fun linkBetween(a: Node, b: Node): Link? =
        cableBetween(a, b) ?: radioLinkList.firstOrNull { (it.radio === a && it.device === b) || (it.radio === b && it.device === a) }

    /**
     * Lowest-ping route from [client] to any server of [service], using only cables and radio links wide enough for the
     * service. Null if none exists or if the best route breaks the service's ping limit. The ping counts both ways,
     * since the response travels the same route back.
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
        val links = cables + radioLinkList
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
                return Route(path, 2f * dist.getValue(cur))
            }
            // Only the origin and routers, radios or other clients forward traffic; foreign servers are dead ends.
            if (cur !== client && cur.kind == NodeKind.SERVER) continue
            val hopCost = if (cur === client) 0f else Tuning.ROUTER_MS
            for (c in links) {
                if (!c.connects(cur) || c.capacity < service.bandwidth) continue
                // A radio link only carries a client's own traffic: a radio reaches the network through its cables.
                if (c is RadioLink && cur === c.radio) continue
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

    /** Where [p] is in world space: on its link, or at the node it waits at. */
    fun packetPosition(p: Packet): Vec2 {
        if (!p.inTransit || p.progress < 0f) return if (p.inTransit) p.from.center else p.route.last().center
        val link = linkBetween(p.from, p.to) ?: return p.from.center
        return link.pointFrom(p.from, p.progress)
    }

    /** Bandwidth units currently travelling on [c]. */
    fun cableLoad(c: Cable) = linkLoad(c)

    /** Bandwidth units currently travelling on the medium of [l]: the cable, or every link of the radio. */
    fun linkLoad(l: Link) = packets.sumOf { if (it.inTransit && it.progress >= 0f && linkBetween(it.from, it.to)?.medium === l.medium) it.size else 0 }

    // ---------------------------------------------------------------- simulation

    /** Adds budget, routers and radios, for tests and a future debug menu. */
    @DebugApi
    fun grant(extraBudget: Int, extraRouters: Int = 0, extraAccessPoints: Int = 0, extraCellTowers: Int = 0) {
        budget += extraBudget
        routersAvailable += extraRouters
        accessPointsAvailable += extraAccessPoints
        cellTowersAvailable += extraCellTowers
    }

    /** Jumps the calendar without simulating, for tests and a future debug menu. */
    @DebugApi
    fun jumpToWeek(target: Int) {
        time = (target - 1) * Tuning.WEEK_SECONDS
        week = target
        unlocked = unlockedArea(week)
    }

    /** Runs the next week change right now (unlocks, reward offer), for tests and a future debug menu. */
    @DebugApi
    fun advanceToNextWeek() {
        if (gameOver || rewardOffer != null) return
        week++
        time = (week - 1) * Tuning.WEEK_SECONDS
        onNewWeek()
    }

    /** Advances the simulation by [dt] seconds. Does nothing after game over or while a [rewardOffer] is open. */
    fun update(dt: Float) {
        if (gameOver || rewardOffer != null) return
        val prevWeek = week
        time += dt
        week = 1 + (time / Tuning.WEEK_SECONDS).toInt()
        if (week != prevWeek) {
            onNewWeek()
            return
        }

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

    /**
     * Sends the oldest request that currently has a valid route and room on its first link. Packets already waiting
     * to enter that link's medium at either end (answers on their way back, traffic passing through) keep their claim on it.
     */
    private fun dispatch(client: Node) {
        for (service in client.pending) {
            val route = routeFor(client, service) ?: continue
            val first = linkBetween(route.nodes[0], route.nodes[1]) ?: continue
            if (linkLoad(first) + waitingFor(first) + service.bandwidth > first.capacity) continue
            packets += Packet(service, client, route.nodes).apply { progress = 0f }
            client.pending.remove(service)
            client.dispatchCooldown = Tuning.DISPATCH_COOLDOWN
            return
        }
    }

    /** Bandwidth units waiting at either end of a link on the medium of [l] to enter it. */
    private fun waitingFor(l: Link) =
        packets.sumOf { if (it.inTransit && it.progress < 0f && linkBetween(it.from, it.to)?.medium === l.medium) it.size else 0 }

    /**
     * Moves requests and responses along their routes. A request that reaches its server takes one throughput token
     * and turns into a response waiting at the server; a response that reaches its client counts as delivered.
     * Packets first travel and arrive, then waiting packets enter their next cable: responses before requests, and a
     * request only takes room that no waiting response on that cable needs. Otherwise requests queued behind a busy
     * cable (at a client or at a router) could grab every freed slot and keep the answers from ever leaving.
     * [dispatch] leaves room for waiting packets too, so a client with a backlog cannot refill such a slot either.
     */
    private fun movePackets(dt: Float) {
        for (n in nodes) if (n.kind == NodeKind.SERVER) n.tokens = minOf(serverRate(n), n.tokens + serverRate(n) * dt)
        val arrived = ArrayList<Packet>()
        val responses = ArrayList<Packet>()
        for (p in packets) {
            if (p.progress < 0f) continue
            val link = linkBetween(p.from, p.to)
            if (link == null) { arrived += p; p.origin.pending.addFirst(p.service); continue }
            p.progress += link.speed * dt / maxOf(link.length, MIN_LINK_LENGTH)
            if (p.progress < 1f) continue
            val last = p.hop + 1 >= p.route.size - 1
            if (last && !p.isResponse && p.to.tokens < 1f) {
                // Server is saturated: the request waits at the end of the cable and keeps blocking it.
                p.progress = 0.999f
                continue
            }
            p.hop++
            when {
                !last -> p.progress = -1f
                p.isResponse -> { arrived += p; delivered++ }
                else -> {
                    p.route.last().tokens -= 1f
                    arrived += p
                    responses += Packet(p.service, p.origin, p.route.asReversed(), isResponse = true)
                }
            }
        }
        if (arrived.isNotEmpty()) packets.removeAll(arrived.toSet())
        packets += responses
        admitWaiting()
    }

    /** Lets waiting packets enter their next link where its medium has room, responses first. */
    private fun admitWaiting() {
        val waiting = packets.filter { it.inTransit && it.progress < 0f }
        if (waiting.isEmpty()) return
        val load = HashMap<Any, Int>()
        val blocked = HashMap<Any, Int>()
        for (p in waiting.sortedBy { !it.isResponse }) {
            val link = linkBetween(p.from, p.to) ?: continue
            val medium = link.medium
            val used = load.getOrPut(medium) { linkLoad(link) }
            val reserved = if (p.isResponse) 0 else blocked[medium] ?: 0
            if (used + reserved + p.size <= link.capacity) {
                p.progress = 0f
                load[medium] = used + p.size
            } else if (p.isResponse) {
                blocked[medium] = (blocked[medium] ?: 0) + p.size
            }
        }
    }

    private fun onNewWeek() {
        unlocked = unlockedArea(week)
        rewardOffer = RewardOffer(week, Rewards.offer(seed, week, eligibleRewards()))
        val newCables = CableType.entries.filter { it.unlockWeek == week }
        val newDevices = Device.entries.filter { it.unlockWeek == week }
        val server = when {
            week == 3 -> Service.GAMING
            week == 4 -> Service.STREAMING
            week >= 6 && week % 2 == 0 -> Service.entries[rng.nextInt(Service.entries.size)]
            else -> null
        }
        val newServers = if (server != null && spawnServer(server)) listOf(server) else emptyList()
        val newRadios = RadioType.entries.filter { it.unlockWeek == week }
        if (newCables.isNotEmpty() || newDevices.isNotEmpty() || newServers.isNotEmpty() || newRadios.isNotEmpty()) {
            lastNews = WeekNews(year, newCables, newDevices, newServers, newRadios)
            lastNewsTime = time
        }
    }

    // ---------------------------------------------------------------- save

    /** The complete state as plain data, see [Save]. */
    fun snapshot() = WorldSnapshot(
        cols = cols,
        rows = rows,
        seed = seed,
        randomDraws = rng.draws,
        nextId = nextId,
        water = water.map { row -> String(CharArray(row.size) { if (row[it]) WATER else LAND }) },
        unlocked = unlocked,
        time = time,
        week = week,
        delivered = delivered,
        budget = budget,
        routersAvailable = routersAvailable,
        accessPointsAvailable = accessPointsAvailable,
        cellTowersAvailable = cellTowersAvailable,
        gameOver = gameOver,
        failedNodeId = failedNode?.id,
        rewardOffer = rewardOffer?.let { RewardOfferSnapshot(it.week, it.choices) },
        serverVouchers = serverVouchers,
        lastNews = lastNews,
        lastNewsTime = lastNewsTime,
        clientSpawnTimer = clientSpawnTimer,
        nodes = nodes.map {
            NodeSnapshot(
                it.id, it.kind, it.device, it.service, it.cellX, it.cellY, it.footprint, it.pending.toList(),
                it.overload, it.level, it.tokens, it.requestTimer, it.dispatchCooldown, it.channel, it.fiveGhz,
            )
        },
        cables = cables.map { CableSnapshot(it.a.id, it.b.id, it.type, it.cost, it.layout.waypoints.map(::cellOf), it.waterCells) },
        packets = packets.map { PacketSnapshot(it.service, it.origin.id, it.route.map(Node::id), it.isResponse, it.hop, it.progress) },
    )

    companion object {
        private const val WATER = '~'
        /** Radio links to a neighbour can be short; packets on them still take a visible moment. */
        private const val MIN_LINK_LENGTH = 0.5f
        private const val LAND = '.'

        private fun cellOf(p: Vec2) = Cell(p.x.toInt(), p.y.toInt())

        /**
         * Rebuilds a world from [s]. The restored world continues exactly like the saved one would have, random draws
         * included. Throws [IllegalArgumentException] if the snapshot is inconsistent.
         */
        fun restore(s: WorldSnapshot): World {
            require(s.water.size == s.rows && s.water.all { it.length == s.cols }) { "water does not match the grid" }
            val w = World(s.cols, s.rows, s.seed, spawnInitialNodes = false)
            for (y in 0 until s.rows) for (x in 0 until s.cols) w.water[y][x] = s.water[y][x] == WATER
            w.rng = ReplayableRandom.restore(s.seed, s.randomDraws)
            w.nextId = s.nextId
            w.unlocked = s.unlocked
            w.time = s.time
            w.week = s.week
            w.delivered = s.delivered
            w.budget = s.budget
            w.routersAvailable = s.routersAvailable
            w.accessPointsAvailable = s.accessPointsAvailable
            w.cellTowersAvailable = s.cellTowersAvailable
            w.gameOver = s.gameOver
            w.serverVouchers = s.serverVouchers
            w.rewardOffer = s.rewardOffer?.let { RewardOffer(it.week, it.choices) }
            w.lastNews = s.lastNews
            w.lastNewsTime = s.lastNewsTime
            w.clientSpawnTimer = s.clientSpawnTimer
            val byId = HashMap<Int, Node>()
            for (n in s.nodes) {
                require(n.footprint.isNotEmpty()) { "node ${n.id} has no footprint" }
                val node = Node(n.id, n.kind, n.device, n.service, n.cellX, n.cellY).apply {
                    footprint = n.footprint
                    pending.addAll(n.pending)
                    overload = n.overload
                    level = n.level
                    tokens = n.tokens
                    requestTimer = n.requestTimer
                    dispatchCooldown = n.dispatchCooldown
                    channel = n.channel
                    fiveGhz = n.fiveGhz
                }
                require(byId.put(n.id, node) == null) { "duplicate node id ${n.id}" }
                w.nodes += node
            }
            fun node(id: Int) = requireNotNull(byId[id]) { "unknown node $id" }
            for (c in s.cables) {
                w.cables += Cable(node(c.a), node(c.b), c.type, c.cost, CableLayout(c.waypoints.map { it.center }), c.waterCells)
            }
            w.rebuildRadioLinks()
            for (p in s.packets) {
                require(p.route.size >= 2 && p.hop in p.route.indices) { "bad packet route" }
                w.packets += Packet(p.service, node(p.origin), p.route.map(::node), p.isResponse).apply {
                    hop = p.hop
                    progress = p.progress
                }
            }
            w.failedNode = s.failedNodeId?.let(::node)
            return w
        }
    }
}

/** Block origins relative to the server cell for [World.dataCenterFootprint], in order of preference. */
private val DATA_CENTER_ORIGINS = listOf(0 to 0, -1 to 0, 0 to -1, -1 to -1)
