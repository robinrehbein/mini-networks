package com.mininetworks.game.game

import kotlin.random.Random

/**
 * A large, busy, seeded network for performance checks (docs/PLAN.md P4.2), a test fixture of :core: a 5 × 3 grid of
 * routers joined by fiber, two level-3 servers of every service and a few clients at every router, all with full
 * request queues, so a few seconds in more than 200 packets are on their way.
 */
@DebugApi
object StressWorld {
    /** Grid spacing of the routers, in cells. */
    private const val SPACING = 8
    private const val ROUTER_COLS = 5
    private const val ROUTER_ROWS = 3
    private const val SERVERS_PER_SERVICE = 2
    private const val SERVER_LEVEL = 3

    /** Where servers and clients sit around their router, in order of preference. */
    private val SLOTS = listOf(-2 to -2, 2 to -2, -2 to 2, 2 to 2, 0 to -3, -3 to 1, 3 to -1, 0 to 3)

    /** Every era is invented: the map starts in week 8, when all services have servers. */
    val SCENARIO = scenario(ROUTER_COLS, ROUTER_ROWS)

    /** Router grid of the large variant used for the docs/TOP100.md section 4 benchmark (at least 150 nodes). */
    const val LARGE_ROUTER_COLS = 8
    const val LARGE_ROUTER_ROWS = 4

    private fun scenario(routerCols: Int, routerRows: Int) = Scenario(
        id = "stress",
        cols = SPACING * routerCols + 4, rows = SPACING * routerRows + 4,
        startCols = SPACING * routerCols + 4, startRows = SPACING * routerRows + 4,
        startWeek = 8, startYear = 2016,
        terrain = emptyList(),
        unlock = Unlock.Free,
    )

    /** The large variant: an [LARGE_ROUTER_COLS] × [LARGE_ROUTER_ROWS] router grid, same rules as [build]. */
    fun buildLarge(seed: Long = 1L): World = build(seed, LARGE_ROUTER_COLS, LARGE_ROUTER_ROWS)

    fun build(seed: Long = 1L, routerCols: Int = ROUTER_COLS, routerRows: Int = ROUTER_ROWS): World {
        val scenario = if (routerCols == ROUTER_COLS && routerRows == ROUTER_ROWS) SCENARIO else scenario(routerCols, routerRows)
        val w = World(scenario, seed = seed, spawnInitialNodes = false)
        w.incidentsEnabled = false
        w.grant(100_000)
        val rnd = Random(seed)
        val routers = List(routerRows) { j -> List(routerCols) { i -> w.addRouter(4 + SPACING * i, 4 + SPACING * j) } }
        for (row in routers) row.zipWithNext { a, b -> w.connect(a, b, CableType.FIBER) }
        val middle = routerCols / 2
        for (j in 0 until routerRows - 1) w.connect(routers[j][middle], routers[j + 1][middle], CableType.FIBER)
        val servers = Service.entries.flatMap { s -> List(SERVERS_PER_SERVICE) { s } }.iterator()
        val devices = Device.entries
        fun attach(router: Node, make: (Int, Int) -> Node): Boolean {
            if (w.ports(router) >= router.maxPorts) return false
            val (dx, dy) = SLOTS.firstOrNull { (dx, dy) -> w.isFree(router.cellX + dx, router.cellY + dy) } ?: return false
            return w.connect(router, make(router.cellX + dx, router.cellY + dy), CableType.FIBER)
        }
        for (router in routers.flatten()) {
            if (servers.hasNext()) attach(router) { x, y -> w.addServer(servers.next(), x, y).also { it.level = SERVER_LEVEL } }
        }
        for (router in routers.flatten()) {
            while (attach(router) { x, y -> w.addClient(devices[rnd.nextInt(devices.size)], x, y) }) Unit
        }
        val served = w.availableServices
        for (n in w.nodes) {
            if (n.kind != NodeKind.CLIENT) continue
            val wants = n.device!!.services.filter { it in served }
            repeat(World.Tuning.MAX_PENDING - 1) { n.pending.addLast(wants[rnd.nextInt(wants.size)]) }
        }
        return w
    }
}
