package com.mininetworks.game.game

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/**
 * A simple greedy player for balancing (docs/PLAN.md P4.1, docs/BALANCING.md). It only uses the player's actions of
 * [World] and decides from what the player can see:
 *
 * - every server gets a router next to it while routers are in stock, and the routers are joined into one backbone;
 * - a device without a route to a service it wants gets the cheapest cable (plus the upgrades the route then needs) that
 *   gives it one within bandwidth and ping, from the device or a node near it with a free port to the nearest suitable
 *   router or server, preferring targets that reach more of its services; a far target gets a new router next to the
 *   device in between when one is in stock; with no router or server port left in reach, it hangs the device off a
 *   neighbouring device that already reaches the service (devices forward, see [World.routeFor]);
 * - a route that fails on bandwidth or ping gets its cables upgraded; a congested route gets its narrowest cable
 *   upgraded or a faster way around its busiest cable;
 * - a server that keeps requests waiting or has no port left is upgraded; cut cables are repaired;
 * - radios from rewards go where they reach the most devices;
 * - rewards: a server voucher while a server is busy, routers while fewer than [ROUTER_STOCK] are left or budget is at
 *   least [RICH], otherwise budget.
 *
 * It never removes a cable and never uses the rewarded extras (continue after game over, bonus router).
 *
 * The [strategy] narrows this down for the one-sided bots of docs/BALANCING.md (G2): [BotStrategy.singleCable] lays
 * and upgrades to one cable technology only, [BotStrategy.WIRELESS_ONLY] never cables a device and serves them through
 * radios alone. [BotStrategy.BALANCED] is the full bot above.
 */
class GreedyBot(private val w: World, private val strategy: BotStrategy = BotStrategy.BALANCED) {

    /** Smoothed number of requests waiting at each server, sampled on every [act]. */
    private val serverQueue = HashMap<Node, Float>()

    /** One look at the map: repairs, radios, servers and backbone, missing routes (worst devices first), busy servers, congestion. */
    fun act() {
        if (w.gameOver || w.rewardOffer != null) return
        sampleServers()
        repairCuts()
        placeRadios()
        linkServers()
        linkBackbone()
        val clients = w.nodes.filter { it.kind == NodeKind.CLIENT }
            .sortedWith(compareByDescending<Node> { it.overload }.thenByDescending { it.pending.size }.thenBy { it.id })
        for (c in clients) fixMissing(c)
        upgradeBusyServers()
        if (strategy.cablesDevices) for (c in clients) if (c.pending.size >= CONGESTED) relieve(c)
    }

    /** Picks one reward of the open offer by the heuristic in the class comment. */
    fun chooseReward() {
        val offer = w.rewardOffer ?: return
        val busy = serverQueue.values.any { it >= BUSY_QUEUE / 2 }
        val order = buildList {
            if (!strategy.cablesDevices) {
                add(Reward.ACCESS_POINT)
                add(Reward.CELL_TOWER)
            }
            if (busy) add(Reward.SERVER_VOUCHER)
            if (w.routersAvailable < ROUTER_STOCK || w.budget >= RICH) add(Reward.ROUTERS)
            add(Reward.BUDGET)
            add(Reward.ROUTERS)
            add(Reward.SERVER_VOUCHER)
            add(Reward.CELL_TOWER)
            add(Reward.ACCESS_POINT)
        }
        val pick = order.first { it in offer.choices }
        w.chooseReward(offer.choices.indexOf(pick))
    }

    // ---------------------------------------------------------------- servers

    private fun sampleServers() {
        for (s in w.nodes) {
            if (s.kind != NodeKind.SERVER) continue
            val waiting = w.packets.count { !it.isResponse && it.to === s && it.progress >= 0.99f && it.hop == it.route.size - 2 }
            serverQueue[s] = (serverQueue[s] ?: 0f) * 0.8f + waiting * 0.2f
        }
    }

    private fun upgradeBusyServers() {
        for ((s, queue) in serverQueue.entries.sortedByDescending { it.value }) {
            if (queue < BUSY_QUEUE || w.serverUpgradeError(s) != null) continue
            if (w.serverVouchers > 0 || w.budget - World.Tuning.SERVER_UPGRADE_COST[s.level - 1] >= RESERVE) w.upgradeServer(s)
        }
    }

    /**
     * Gives every server a router of its own: a new one right next to it while routers are in stock, otherwise the
     * nearest router with a free port. Devices then share the router's ports instead of filling the server's, for one
     * extra hop.
     */
    private fun linkServers() {
        for (s in w.nodes.filter { it.kind == NodeKind.SERVER }) {
            if (w.cables.any { it.connects(s) && it.other(s).kind == NodeKind.ROUTER } || w.ports(s) >= s.maxPorts) continue
            val type = typesByPrice(BACKBONE_NEED).firstOrNull() ?: return
            if (w.routersAvailable > 0) {
                val cell = w.nearestFree(s.cell) ?: continue
                if (w.cableCost(w.planLayout(cell, s.cell), type) > w.budget) continue
                w.placeRouter(cell.x, cell.y)?.let { w.connect(it, s, type) }
                continue
            }
            val hub = w.nodes.filter { it.kind == NodeKind.ROUTER && w.ports(it) < it.maxPorts - 1 }.minByOrNull { steps(it, s) } ?: continue
            if (w.cableCost(hub, s, type) <= w.budget - RESERVE) w.connect(hub, s, type)
        }
    }

    /**
     * Joins the routers into one backbone: while they form separate groups (counting links through other routers only),
     * lays the cheapest router-to-router cable between two groups, if affordable. One cable per look.
     */
    private fun linkBackbone() {
        val routers = w.nodes.filter { it.kind == NodeKind.ROUTER }
        val group = HashMap<Node, Int>()
        for (r in routers) {
            if (r in group) continue
            val id = group.size
            val stack = ArrayDeque(listOf(r))
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                if (group.putIfAbsent(n, id) != null) continue
                for (c in w.cables) if (c.connects(n) && c.other(n).kind == NodeKind.ROUTER) stack += c.other(n)
            }
        }
        if (group.values.toSet().size < 2) return
        val type = typesByPrice(BACKBONE_NEED).firstOrNull() ?: return
        val free = routers.filter { w.ports(it) < it.maxPorts }
        var best: Triple<Node, Node, Int>? = null
        for (a in free) for (b in free) {
            if (group[a]!! >= group[b]!!) continue
            val cost = w.cableCost(a, b, type)
            if (best == null || cost < best.third) best = Triple(a, b, cost)
        }
        val (a, b, cost) = best ?: return
        if (cost <= w.budget - RESERVE) w.connect(a, b, type)
    }

    // ---------------------------------------------------------------- incidents

    private fun repairCuts() {
        for (c in w.cables.toList()) if (w.isCut(c) && w.repairError(c) == null) w.repair(c)
    }

    // ---------------------------------------------------------------- devices

    /** Services [c] wants that have a server on the map. */
    private fun wanted(c: Node): List<Service> {
        val served = w.availableServices
        return c.device!!.services.filter { it in served }
    }

    /**
     * Gives [c] a route to each service it wants: upgrades a cable path that is too narrow or too slow, otherwise lays a
     * new cable. A path through a cut cable or a dark node is left alone; the repair or the outage's end fixes it.
     */
    private fun fixMissing(c: Node) {
        for (s in wanted(c)) {
            if (w.routeFor(c, s) != null) continue
            val path = cablePath(c, s)
            if (path != null && path.cables.any { w.isCut(it) || w.isDark(it.a) || w.isDark(it.b) }) continue
            if (path != null && upgradePath(path, s)) continue
            if (!strategy.cablesDevices) continue
            if (!attach(c, s)) growServer(s)
        }
    }

    /**
     * Upgrades a server of [s] whose ports are all taken toward a data center, which has more ports; a voucher pays
     * first, and [RESERVE] budget stays back.
     */
    private fun growServer(s: Service): Boolean {
        val server = w.nodes.filter { it.kind == NodeKind.SERVER && it.service == s && w.ports(it) >= it.maxPorts && w.serverUpgradeError(it) == null }
            .maxByOrNull { it.level } ?: return false
        if (w.serverVouchers == 0 && w.budget - World.Tuning.SERVER_UPGRADE_COST[server.level - 1] < RESERVE) return false
        return w.upgradeServer(server)
    }

    /** Cable upgrades the bot means to buy, and what they cost together. */
    private class Plan(val upgrades: Map<Cable, CableType>, val cost: Int, val oneWayMs: Float)

    /**
     * The upgrades that let [s] through [cables] within its ping, given [fixedMs] of one-way delay on top (router hops,
     * new cables): first every cable too narrow for [s] to the cheapest wide enough, then the slowest cables to the
     * fastest invented one until the ping fits. Null if no upgrade gets there.
     */
    private fun plan(cables: List<Cable>, fixedMs: Float, s: Service): Plan? {
        val upgrades = HashMap<Cable, CableType>()
        for (cable in cables) if (cable.capacity < s.bandwidth) {
            upgrades[cable] = typesByPrice(s.bandwidth).firstOrNull { it.ordinal > cable.type.ordinal } ?: return null
        }
        fun oneWay() = fixedMs + cables.sumOf { ((upgrades[it] ?: it.type).msPerCell * it.length).toDouble() }.toFloat()
        val fastest = allowedCables().minByOrNull { it.msPerCell } ?: return null
        for (cable in cables.sortedByDescending { it.latencyMs }) {
            if (fitsPing(s, oneWay())) break
            if ((upgrades[cable] ?: cable.type).msPerCell > fastest.msPerCell) upgrades[cable] = fastest
        }
        if (!fitsPing(s, oneWay())) return null
        return Plan(upgrades, upgrades.entries.sumOf { (cable, type) -> w.cableCost(cable.layout, type) - cable.cost }, oneWay())
    }

    private fun buy(plan: Plan) {
        for ((cable, type) in plan.upgrades) w.upgrade(cable, type)
    }

    /**
     * A cable the bot could lay: [from] → [to] of [type], with the upgrades [plan] on the rest of the route, for [cost]
     * in total; the bot picks the lowest [score]. [oneWayMs] is the delay of the route it makes.
     */
    private class Option(val from: Node, val to: Node, val type: CableType, val plan: Plan, val cost: Int, val score: Int, val oneWayMs: Float)

    /**
     * Lays the best-scored cable (with the upgrades the route then needs) that gives [c] a route to [s] within bandwidth
     * and ping, from one of its [origins] to a server of [s] or a router that reaches one; only if there is none, to
     * another device that reaches one ([BotStrategy.chains]). With [shorterThanMs] the new
     * route must be faster than that and must not use [avoid]. A target more than [FAR] steps away from [c] gets a new
     * router next to [c] in between when one is in stock.
     */
    private fun attach(c: Node, s: Service, shorterThanMs: Float = Float.MAX_VALUE, avoid: Cable? = null): Boolean {
        var best: Option? = null
        val origins = origins(c)
        for (chain in listOf(false, true)) {
            if (chain && (best != null || !strategy.chains)) break
            for ((from, drop) in origins) for (to in w.nodes) {
                if (chain != (to.kind == NodeKind.CLIENT) || chain && to === c) continue
                val option = option(from, to, s, drop, avoid, wanted(c)) ?: continue
                if (option.oneWayMs >= shorterThanMs) continue
                if (best == null || option.score < best.score) best = option
            }
        }
        best ?: return false
        if (shorterThanMs == Float.MAX_VALUE && best.from === c && steps(c, best.to) > FAR && w.routersAvailable > 0 &&
            viaNewRouter(c, best.to, s)
        ) return true
        if (!w.connect(best.from, best.to, best.type)) return false
        buy(best.plan)
        return true
    }

    /**
     * Where a new cable for [c] can start: [c] itself, or a router or device up to [ORIGIN_DEPTH] cables away with a free
     * port, together with the cables from [c] to it.
     */
    private fun origins(c: Node): List<Pair<Node, List<Cable>>> {
        val result = ArrayList<Pair<Node, List<Cable>>>()
        val seen = hashSetOf(c)
        var frontier = listOf(c to emptyList<Cable>())
        for (depth in 0..ORIGIN_DEPTH) {
            val next = ArrayList<Pair<Node, List<Cable>>>()
            for ((n, via) in frontier) {
                if (w.ports(n) < n.maxPorts) result += n to via
                if (depth == ORIGIN_DEPTH) continue
                for (cable in w.cables) {
                    if (!cable.connects(n)) continue
                    val o = cable.other(n)
                    if (o.kind != NodeKind.ROUTER && o.kind != NodeKind.CLIENT || !seen.add(o)) continue
                    next += o to via + cable
                }
            }
            frontier = next
        }
        return result
    }

    /** The route from [to] on to a server of [s]: no cables for the server itself, null if [to] cannot reach one. */
    private fun tail(to: Node, s: Service): CablePath? = when (to.kind) {
        NodeKind.SERVER -> if (to.service == s) CablePath(emptyList(), 0, 0f) else null
        NodeKind.ROUTER, NodeKind.CLIENT -> cablePath(to, s)?.let { CablePath(it.cables, it.hops + 1, it.oneWayMs) }
        else -> null
    }

    /**
     * The cheapest affordable cable [from] → [to] after which [s] gets through, counting the upgrades on [drop] (the
     * cables from the device to [from]) and on the rest of the route. Its score is the price minus [REACH_BONUS] for
     * every service in [wants] that [to] leads to.
     */
    private fun option(from: Node, to: Node, s: Service, drop: List<Cable>, avoid: Cable?, wants: List<Service>): Option? {
        if (to === from || w.cableBetween(from, to) != null || w.ports(to) >= to.maxPorts - keptPorts(from, to)) return null
        if (drop.any { it.connects(to) } || avoid in drop) return null
        val tail = tail(to, s) ?: return null
        if (avoid in tail.cables) return null
        val cables = drop + tail.cables
        val hopsMs = (tail.hops + drop.size) * World.Tuning.ROUTER_MS
        val layout = w.planLayout(from, to)
        var best: Option? = null
        for (t in typesByPrice(s.bandwidth)) {
            val plan = plan(cables, hopsMs + layout.length * t.msPerCell, s) ?: continue
            val cost = w.cableCost(layout, t) + plan.cost
            if (cost > w.budget || (best != null && cost >= best.cost)) continue
            best = Option(from, to, t, plan, cost, cost, plan.oneWayMs)
        }
        val option = best ?: return null
        val reach = if (to.kind == NodeKind.SERVER) 1 else wants.count { cablePath(to, it) != null }
        return Option(from, to, option.type, option.plan, option.cost, option.cost - REACH_BONUS * reach, option.oneWayMs)
    }

    /** Ports a device leaves free on a server that has no router yet, so [linkServers] can still hang it off one. */
    private fun keptPorts(from: Node, to: Node) =
        if (from.kind == NodeKind.CLIENT && to.kind == NodeKind.SERVER && w.cables.none { it.connects(to) && it.other(to).kind == NodeKind.ROUTER }) SERVER_PORTS_KEPT else 0

    /**
     * Places a router next to [c] on the way to [to], cables it to [to] and [c] to it, if that is affordable with the
     * upgrades the route then needs and meets the ping of [s].
     */
    private fun viaNewRouter(c: Node, to: Node, s: Service): Boolean {
        val dx = sign((to.cellX - c.cellX).toFloat()).toInt()
        val dy = sign((to.cellY - c.cellY).toFloat()).toInt()
        val cell = w.nearestFree(Cell(c.cellX + dx, c.cellY + dy)) ?: return false
        val tail = tail(to, s) ?: return false
        val trunkLayout = w.planLayout(cell, to.cell)
        val dropLayout = w.planLayout(c.cell, cell)
        val drop = typesByPrice(c.device!!.services.maxOf { it.bandwidth }).firstOrNull() ?: return false
        val hopsMs = (tail.hops + 1) * World.Tuning.ROUTER_MS + dropLayout.length * drop.msPerCell
        var best: Triple<CableType, Plan, Int>? = null
        for (t in typesByPrice(maxOf(BACKBONE_NEED, s.bandwidth))) {
            val plan = plan(tail.cables, hopsMs + trunkLayout.length * t.msPerCell, s) ?: continue
            val cost = w.cableCost(trunkLayout, t) + w.cableCost(dropLayout, drop) + plan.cost
            if (cost <= w.budget && (best == null || cost < best.third)) best = Triple(t, plan, cost)
        }
        val (trunk, plan) = best ?: return false
        val r = w.placeRouter(cell.x, cell.y) ?: return false
        w.connect(r, to, trunk)
        w.connect(c, r, drop)
        buy(plan)
        return true
    }

    private fun fitsPing(s: Service, oneWayMs: Float) = s.maxPingMs == null || 2f * oneWayMs <= s.maxPingMs!!

    // ---------------------------------------------------------------- upgrades

    /** Upgrades the cables of [path] so that [s] fits through within its ping. False if nothing to do or not affordable. */
    private fun upgradePath(path: CablePath, s: Service): Boolean {
        val plan = plan(path.cables, path.hops * World.Tuning.ROUTER_MS, s) ?: return false
        if (plan.upgrades.isEmpty() || plan.cost > w.budget) return false
        buy(plan)
        return true
    }

    /**
     * A device with a backlog: upgrades the narrowest cable on the route of its oldest routable request, or, if that
     * cannot get wider, lays a faster route around its busiest cable (routes follow the lowest ping, so only a faster
     * one takes the load off).
     */
    private fun relieve(c: Node) {
        val s = c.pending.firstOrNull { w.routeFor(c, it) != null } ?: return
        val route = w.routeFor(c, s)!!
        val narrow = route.nodes.zipWithNext { a, b -> w.cableBetween(a, b) }.filterNotNull().minByOrNull { it.capacity } ?: return
        val next = typesByPrice(narrow.capacity + 1).firstOrNull { it.capacity > narrow.capacity }
        if (next != null) {
            if (w.cableCost(narrow.layout, next) - narrow.cost <= w.budget - RESERVE) w.upgrade(narrow, next)
            return
        }
        val busiest = route.nodes.zipWithNext { a, b -> w.cableBetween(a, b) }.filterNotNull().maxBy { w.linkLoad(it).toFloat() / it.capacity }
        attach(c, s, route.oneWayMs * SHORTCUT, busiest)
    }

    // ---------------------------------------------------------------- radios

    /**
     * Puts every radio in stock where it links the most devices it can serve (at least two, or one for the wireless-only
     * bot, which counts only devices no radio links yet), cabled to the nearest router.
     */
    private fun placeRadios() {
        for (type in RadioType.entries) {
            if (w.radiosAvailable(type) == 0) continue
            var best: Pair<Cell, Int>? = null
            val area = w.unlocked
            for (y in area.top until area.bottom) for (x in area.left until area.right) {
                if (!w.isFree(x, y)) continue
                val reach = w.nodes.count {
                    it.kind == NodeKind.CLIENT && w.serves(type, it.device!!) && (strategy.cablesDevices || !linkedByRadio(it)) &&
                        hypot(it.center.x - (x + 0.5f), it.center.y - (y + 0.5f)) <= type.radius
                }
                if (reach >= minReach && (best == null || reach > best.second)) best = Cell(x, y) to reach
            }
            val cell = best?.first ?: continue
            val hub = w.nodes.filter { it.kind == NodeKind.ROUTER && w.ports(it) < it.maxPorts }
                .minByOrNull { abs(it.cellX - cell.x) + abs(it.cellY - cell.y) } ?: continue
            val cableType = typesByPrice(BACKBONE_NEED).firstOrNull() ?: continue
            if (w.cableCost(w.planLayout(cell, hub.cell), cableType) > w.budget) continue
            val radio = w.placeRadio(type, cell.x, cell.y) ?: continue
            w.connect(radio, hub, cableType)
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Invented cables that carry [need], cheapest first and the widest on a price tie; if none is wide enough, the
     * widest invented one alone.
     */
    private fun typesByPrice(need: Int): List<CableType> {
        val cables = allowedCables()
        if (cables.isEmpty()) return emptyList()
        return cables.filter { it.capacity >= need }.sortedWith(compareBy<CableType> { it.costPerCell }.thenByDescending { it.capacity })
            .ifEmpty { listOf(cables.maxBy { it.capacity }) }
    }

    /** Invented cable technologies the [strategy] may lay. */
    private fun allowedCables() = w.unlockedCables.filter { it in strategy.cables }

    /** Devices a radio must reach before the bot places it there. */
    private val minReach get() = if (strategy.cablesDevices) 2 else 1

    /** True if a radio already links [c]. */
    private fun linkedByRadio(c: Node) = w.radioLinks.any { it.device === c }

    private fun steps(a: Node, b: Node) = abs(a.cellX - b.cellX) + abs(a.cellY - b.cellY)

    /** Cables from a node to a server; [hops] counts the forwarding nodes, [oneWayMs] the delay like [Route.oneWayMs]. */
    private class CablePath(val cables: List<Cable>, val hops: Int, val oneWayMs: Float)

    /**
     * Lowest-latency cable path from [from] to a server of [s], forwarding like [World.routeFor] (servers of other
     * services are dead ends), ignoring capacity, ping limits, cuts and outages.
     */
    private fun cablePath(from: Node, s: Service): CablePath? {
        val dist = HashMap<Node, Float>().apply { put(from, 0f) }
        val prev = HashMap<Node, Cable>()
        val open = mutableListOf(from)
        val done = HashSet<Node>()
        while (open.isNotEmpty()) {
            val cur = open.minBy { dist.getValue(it) }
            open.remove(cur)
            if (!done.add(cur)) continue
            if (cur.kind == NodeKind.SERVER && cur.service == s) {
                val path = ArrayList<Cable>()
                var n = cur
                while (n !== from) {
                    val c = prev.getValue(n)
                    path.add(0, c)
                    n = c.other(n)
                }
                return CablePath(path, path.size - 1, dist.getValue(cur))
            }
            if (cur !== from && cur.kind == NodeKind.SERVER) continue
            for (c in w.cables) {
                if (!c.connects(cur)) continue
                val nb = c.other(cur)
                val d = dist.getValue(cur) + c.latencyMs + if (cur === from) 0f else World.Tuning.ROUTER_MS
                if (d < (dist[nb] ?: Float.MAX_VALUE)) {
                    dist[nb] = d
                    prev[nb] = c
                    open += nb
                }
            }
        }
        return null
    }

    companion object {
        /** The bot picks routers as a reward while it has fewer than this many in stock, or [RICH] budget or more. */
        const val ROUTER_STOCK = 2
        const val RICH = 60
        /** How many cables away from a device a new cable for it may start. */
        const val ORIGIN_DEPTH = 2
        /** Beyond this many steps a device gets a router of its own on the way, if one is in stock. */
        const val FAR = 5
        /** Budget kept back from the backbone, congestion and server upgrades for the next devices. */
        const val RESERVE = 4
        /** Price the bot is willing to pay extra per service of the device that the cable's target reaches. */
        const val REACH_BONUS = 3
        /** Ports a device leaves free on a server without a router. */
        const val SERVER_PORTS_KEPT = 1
        /** Capacity the bot wants between routers and on a new router's trunk. */
        const val BACKBONE_NEED = 4
        /** A route around a congested cable must bring the delay down to this fraction. */
        const val SHORTCUT = 0.95f
        /** Waiting requests that make the bot look at a device's route. */
        const val CONGESTED = 3
        /** Smoothed requests waiting at a server that make the bot upgrade it. */
        const val BUSY_QUEUE = 1.2f
    }
}

/**
 * What a [GreedyBot] may use: the cable technologies in [cables], and cables to devices only if [cablesDevices]
 * (otherwise devices get through radios alone); [chains] lets it hang a device off another device as a last resort.
 * [id] names the bot in docs/BALANCING.md.
 */
data class BotStrategy(val id: String, val cables: Set<CableType>, val cablesDevices: Boolean, val chains: Boolean = true) {
    companion object {
        /** The full greedy bot: every invented cable where it is cheapest, radios from rewards on top. */
        val BALANCED = BotStrategy("balanced", CableType.entries.toSet(), cablesDevices = true)

        /** Devices only through radios; cables only between routers, servers and radios, never to a device. */
        val WIRELESS_ONLY = BotStrategy("wireless_only", CableType.entries.toSet(), cablesDevices = false)

        /** Lays and upgrades to [type] only, from the week it is invented. */
        fun singleCable(type: CableType) = BotStrategy("only_${type.name.lowercase()}", setOf(type), cablesDevices = true)

        /** The balanced bot and every one-sided bot of G2. */
        val all get() = listOf(BALANCED) + CableType.entries.map(::singleCable) + WIRELESS_ONLY
    }
}

/**
 * One bot game: how many weeks it lasted (fractional, counted from the scenery's start), what it delivered, and why it
 * ended ([cause]: device, service and "unrouted", "narrow", "ping" or "jam", see [World.failure]; empty if it survived).
 */
data class BotRun(
    val scenario: String,
    val seed: Long,
    val weeks: Float,
    val delivered: Int,
    val survived: Boolean,
    val cause: String,
    val bot: String = BotStrategy.BALANCED.id,
)

/** Plays [GreedyBot] games at the game's fixed step. */
object BotRunner {
    const val STEP = 1f / 60f
    /** The bot looks at the map this often, in game seconds. */
    const val THINK_SECONDS = 0.5f

    fun play(scenario: Scenario, seed: Long, maxWeeks: Int, strategy: BotStrategy = BotStrategy.BALANCED): BotRun {
        val w = World(scenario, seed = seed)
        val bot = GreedyBot(w, strategy)
        val start = w.time
        val end = start + maxWeeks * World.Tuning.WEEK_SECONDS
        var think = 0f
        while (!w.gameOver && w.time < end) {
            if (w.rewardOffer != null) { bot.chooseReward(); continue }
            think -= STEP
            if (think <= 0f) { bot.act(); think = THINK_SECONDS }
            w.update(STEP)
        }
        val cause = w.failure?.let { f ->
            val why = when (f.problem) {
                RouteProblem.NO_ROUTE -> "unrouted"
                RouteProblem.TOO_NARROW -> "narrow"
                RouteProblem.PING_TOO_HIGH -> "ping"
                null -> "jam"
            }
            "${f.node.device}:${f.service}:$why"
        } ?: w.failedNode?.let { "${it.device}:?" } ?: ""
        return BotRun(scenario.id, seed, (w.time - start) / World.Tuning.WEEK_SECONDS, w.delivered, !w.gameOver, cause, strategy.id)
    }
}
