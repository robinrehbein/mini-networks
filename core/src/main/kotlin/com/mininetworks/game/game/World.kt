package com.mininetworks.game.game

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random

/**
 * The complete game state and rules. Pure Kotlin, no Android types, so it can be unit-tested on the JVM
 * and drawn by any renderer (flat, isometric, pixel ...).
 *
 * The map comes from a [Scenario]: its grid is fixed at [cols] × [rows] with the scenario's terrain, but only the
 * [unlocked] block in its middle is in play. It starts at the scenario's start size and grows by one ring of cells
 * every [Tuning.GROWTH_WEEKS] weeks.
 *
 * A [guided] world (the tutorial, see [Tutorial]) only moves on when told to: its calendar stands still until
 * [advanceEra], no clients appear on their own, no incidents are announced, and an overloaded client's ring stops just
 * short of closing ([Tuning.GUIDED_MAX_OVERLOAD]), so the game never ends. Guided worlds are not saved.
 */
class World(
    val scenario: Scenario = Scenarios.RIVER_TOWN,
    val cols: Int = scenario.cols,
    val rows: Int = scenario.rows,
    val seed: Long = 7L,
    private val spawnInitialNodes: Boolean = true,
    val guided: Boolean = false,
    /** How this game is played: normal, endless or creative (docs/TOP100.md C4). */
    val mode: GameMode = GameMode.NORMAL,
    /** Set for the daily challenge (docs/TOP100.md C1): its [DailyChallenge.rule] applies to this world. */
    val daily: DailyChallenge? = null,
) {
    object Tuning {
        /** Cell towers handed out in the week mobile radio is invented ([RadioType.CELL]). */
        const val FIRST_CELL_TOWERS = 1

        /**
         * First week a [Reward.CELL_TOWER] is offered. Two weeks after the tower itself: an extra reward in the pool from
         * week 5 displaced the others and made island_harbor harder than mountain_village (docs/BALANCING.md G1).
         */
        const val CELL_TOWER_REWARD_WEEK = 7

        const val WEEK_SECONDS = 45f
        const val FIRST_YEAR = 1995
        /**
         * Calendar year of era weeks 1, 2, 3 … (docs/PLAN.md 3.2): three years per week at first,
         * then slower until the calendar reaches today, where it stays.
         */
        val ERA_YEARS = intArrayOf(FIRST_YEAR, 1998, 2001, 2004, 2007, 2010, 2013, 2016, 2019, 2022, 2024, 2026)
        /** A scenery starting after today counts its years like the era table from this week: +3, +3, +2, +2, then stops. */
        const val FUTURE_PACE_WEEK = 8
        const val ROUTER_MS = 4f
        const val MAX_PENDING = 6
        /** From this many waiting requests a device warns (orange bubble, off-screen chip) before its ring starts. */
        const val PREWARN_PENDING = MAX_PENDING - 2
        const val OVERLOAD_SECONDS = 18f
        /** A device counts as jammed ([isJammed]) once it has been held back this long without a break. */
        const val JAM_SECONDS = 1.5f
        /** A device or cable shown as jammed stays so this long after the jam lets up, so its badge does not blink. */
        const val JAM_HOLD = 1f
        /**
         * A device only counts as jammed while at least this many requests wait: half full, a step before the
         * pre-warning ([PREWARN_PENDING]), so the cause shows just before the symptom gets urgent.
         */
        const val JAM_PENDING = MAX_PENDING / 2
        /**
         * A full overload ring empties in this long once the device is served again; short, so a device saved at the
         * last moment is safe soon (docs/BALANCING.md, T-Human). Intended side effect: a device that keeps hitting
         * [MAX_PENDING] only overloads if it sits at the limit more than RECOVER / (RECOVER + fill time) of the time,
         * about 45 % at full speed and 70 % in the [EARLY_WEEKS] (with 30 s it was 37.5 % and 55 %).
         */
        const val RECOVER_SECONDS = 15f
        const val DISPATCH_COOLDOWN = 0.45f
        /** Budget and routers at the start of [Scenarios.RIVER_TOWN]; other scenarios set their own (docs/BALANCING.md). */
        const val START_BUDGET = 50
        const val START_ROUTERS = 3
        /** Budget credited at every week change, on top of the reward the player picks. */
        const val WEEK_BUDGET = 80
        /** Seconds until the first client appears on its own. */
        const val FIRST_SPAWN_SECONDS = 6f
        /**
         * Pause between two new clients: [SPAWN_SECONDS] minus [SPAWN_SPEEDUP] per week played, at least
         * [MIN_SPAWN_SECONDS], plus up to [SPAWN_JITTER] at random.
         */
        const val SPAWN_SECONDS = 13f
        const val SPAWN_SPEEDUP = 0.5f
        const val MIN_SPAWN_SECONDS = 4f
        const val SPAWN_JITTER = 2f
        /**
         * No new client appears while the map already holds this many clients per unlocked cell ([isCrowded]). Clients
         * came faster than the map grew (0.04 per cell in week 2, 0.09 in week 10 for a human-paced bot), so cables ran
         * over each other and the map was unreadable on a phone (docs/BALANCING.md, T-Clutter); now the density stops
         * rising where the map is still readable, and every new ring of cells makes room for a few more.
         */
        const val MAX_CLIENT_DENSITY = 0.07f
        /** A spawn held back by [MAX_CLIENT_DENSITY] looks again after this long. */
        const val CROWDED_RETRY_SECONDS = 2f
        /**
         * Pause between two requests of a client with [Demand.RANDOM] services: [REQUEST_SECONDS] minus [REQUEST_SPEEDUP]
         * per week played, at least [MIN_REQUEST_SECONDS], plus up to [REQUEST_JITTER] at random.
         */
        const val REQUEST_SECONDS = 7f
        const val REQUEST_SPEEDUP = 0.15f
        const val MIN_REQUEST_SECONDS = 1.6f
        const val REQUEST_JITTER = 2f
        const val WATER_EXTRA_PER_CELL = 2
        /** Extra budget per cable cell over a mountain pass and through the downtown towers (see [Terrain]). */
        const val MOUNTAIN_EXTRA_PER_CELL = 3
        const val HIGH_RISE_EXTRA_PER_CELL = 1
        const val MAX_SERVER_LEVEL = 4
        /** Tier 4 "Rechenzentrum": covers a 2×2 block of cells and has [DATA_CENTER_PORTS] ports. */
        const val DATA_CENTER_LEVEL = 4
        const val DATA_CENTER_PORTS = 8
        /** Requests per second a server can take at level 1, 2, 3, 4. */
        val SERVER_RATE = floatArrayOf(1.5f, 3f, 5f, 8f)
        /** Budget to reach level 2, 3, 4. */
        val SERVER_UPGRADE_COST = intArrayOf(8, 16, 28)
        /** Size of the playable block at the start of [Scenarios.RIVER_TOWN]; other scenarios set their own. */
        const val START_COLS = 16
        const val START_ROWS = 10
        /** The playable block grows by one ring of cells every this many weeks. */
        const val GROWTH_WEEKS = 2
        /**
         * New servers appear in the middle of the block, in a part this fraction of its width and height (plus the
         * one-cell margin), so every device can reach them within the ping limits of their era.
         */
        const val SERVER_AREA = 0.3f
        /** From this week on, every second week brings a server of a random service (earlier ones follow [Service.serverWeek]; see [WeekSchedule]). */
        const val RANDOM_SERVERS_FROM = 10
        /**
         * A new client picks a device weighted by 1 + its unlock week, so newer devices show up more often; weeks past
         * this count no more, so the late inventions do not crowd out everything else in the long era thread.
         */
        const val DEVICE_WEIGHT_WEEKS = 8
        /** A [Demand.STREAM] service sends one request this often, in every week. */
        const val STREAM_SECONDS = 2.5f
        /** Length of one in-game day; the clock shows [DAWN_HOUR] at time 0. */
        const val DAY_SECONDS = 15f
        const val DAWN_HOUR = 6f
        /** Night lasts from [DUSK_HOUR] to [DAWN_HOUR]. */
        const val DUSK_HOUR = 22f
        /** Every night at this hour, clients queue [BACKUP_BURST] requests of each [Demand.NIGHTLY] service they use. */
        const val BACKUP_HOUR = 2f
        const val BACKUP_BURST = 2
        /** How long [arrivals] keeps an event, in seconds. */
        const val ARRIVAL_SECONDS = 1f
        /** Highest overload a client reaches in a [guided] world: the ring nearly closes, but the game goes on. */
        const val GUIDED_MAX_OVERLOAD = 0.95f
        /**
         * Fair start: during the first this many weeks played, new clients only appear where every service they want
         * that has a server can be reached with one direct cable of an invented technology, within its ping limit
         * ([EARLY_PING_SHARE] of it, room for a router on the way) and for at most [EARLY_CABLE_BUDGET].
         */
        const val EARLY_WEEKS = 3
        const val EARLY_PING_SHARE = 0.8f
        const val EARLY_CABLE_BUDGET = 24
        /** Grace in those weeks too: overload rings fill this much slower, so the first week's pay can still come. */
        const val EARLY_OVERLOAD_SLOWDOWN = 2f

        /** [GameMode.ENDLESS] and [GameMode.CREATIVE]: devices within this many cells of a full overload ring slow down. */
        const val SLOW_RADIUS = 3f
        /** Slowed devices ask this much as often as usual ([World.isSlowed]). */
        const val SLOW_FACTOR = 0.5f

        /** Daily rules ([DailyRule]). [DailyRule.TIGHT_BUDGET]: share of the start budget, and the pay per week. */
        const val TIGHT_START_SHARE = 0.6f
        const val TIGHT_WEEK_BUDGET = 40
        /** [DailyRule.RUSH_HOUR]: pause between two requests, times this. */
        const val RUSH_REQUEST_FACTOR = 0.75f
        /** [DailyRule.FEW_ROUTERS]: routers in stock at the start. */
        const val FEW_ROUTERS = 1
        /** [DailyRule.STORM]: the incident plan runs this many weeks ahead of the weeks played. */
        const val STORM_WEEKS_AHEAD = 2
        /** [DailyRule.WIDE_LAND]: extra rings of the start block. */
        const val WIDE_RINGS = 2
        /** [DailyRule.CROWD]: pause between two new devices times this, and overload rings fill this much slower. */
        const val CROWD_SPAWN_FACTOR = 0.7f
        const val CROWD_OVERLOAD_SLOWDOWN = 1.25f
    }

    /** The special rule of the [daily] challenge, if this is one. */
    val rule: DailyRule? get() = daily?.rule

    /** True in [GameMode.CREATIVE]: nothing costs budget and stock never runs out. */
    val unlimited get() = mode == GameMode.CREATIVE

    /** What happened in this game so far, for achievements (docs/TOP100.md C2); not saved, see [GameCounters]. */
    val counters = GameCounters()

    private var rng = ReplayableRandom(seed)
    private var nextId = 0

    val water = Array(rows) { BooleanArray(cols) }
    /** Mountain cells ([Terrain.MOUNTAIN]). */
    val mountains = Array(rows) { BooleanArray(cols) }
    /** Downtown tower cells ([Terrain.HIGH_RISE]). */
    val highRises = Array(rows) { BooleanArray(cols) }

    /** The whole grid, including cells that are not unlocked yet. */
    val bounds = CellRect(0, 0, cols, rows)

    /** The playable block: nodes spawn and routers are placed only here; the rest of the grid is drawn dimmed. */
    var unlocked = unlockedArea(scenario.startWeek); private set

    private val nodeList = ArrayList<Node>()
    private val cableList = ArrayList<Cable>()

    /** Every node, in the order it appeared; only an uncabled router is ever removed again ([pickUp]). */
    val nodes: List<Node> get() = nodeList
    val cables: List<Cable> get() = cableList
    val packets = mutableListOf<Packet>()

    private val arrivalList = ArrayList<Arrival>()

    /**
     * Requests that reached their server and responses delivered to their client within the last
     * [Tuning.ARRIVAL_SECONDS], oldest first. Only for effects: not saved, no influence on the simulation.
     */
    val arrivals: List<Arrival> get() = arrivalList

    private val radioLinkList = ArrayList<RadioLink>()

    /**
     * Wireless links, derived from the radio nodes and never stored: every radio links the clients it serves within its
     * radius, nearest first (ties by node id), up to its [radioSlots]. A client already cabled to the radio gets no
     * radio link to it. Rebuilt whenever nodes, cables, channels or bands change.
     */
    val radioLinks: List<RadioLink> get() = radioLinkList

    private val incidentList = ArrayList<Incident>()

    /**
     * Disturbances announced or in effect, oldest first (see [Incidents]): excavators that cut a cable and power outages
     * that switch a router or access point off. A cut cable ([isCut]) and a node without power ([isDark]) carry no traffic.
     */
    val incidents: List<Incident> get() = incidentList

    /** False stops new incidents from being announced, for tests about other rules and a future debug menu. */
    @DebugApi
    var incidentsEnabled = true

    /** The week [weekPlan] was made for; the plan only depends on seed and week. */
    private var planWeek = 0
    private var weekPlan = emptyList<PlannedIncident>()

    var time = (scenario.startWeek - 1) * Tuning.WEEK_SECONDS; private set
    var week = scenario.startWeek; private set
    var delivered = 0; private set
    var budget = if (daily?.rule == DailyRule.TIGHT_BUDGET) (scenario.startBudget * Tuning.TIGHT_START_SHARE).toInt() else scenario.startBudget
        private set
    var routersAvailable = if (daily?.rule == DailyRule.FEW_ROUTERS) Tuning.FEW_ROUTERS else scenario.startRouters
        private set
    /** WLAN access points in stock, won as [Reward.ACCESS_POINT]. */
    var accessPointsAvailable = scenario.startAccessPoints; private set
    /** Cell towers in stock, won as [Reward.CELL_TOWER]. */
    var cellTowersAvailable = scenario.startCellTowers; private set
    var gameOver = false; private set
    var failedNode: Node? = null; private set

    /** True once this game went on after a game over ([continueAfterGameOver]); that works only once per game. */
    var continued = false; private set

    /**
     * Rewarded extras taken so far ([claimBonusRouter]): routers on top of a week's free choice. Together with
     * [continued] they make the run [assisted].
     */
    var bonusRoutersClaimed = 0; private set

    /**
     * True once this run had help from outside the rules everyone plays by: it went on after a game over or took the
     * weekly bonus router (both come from a rewarded video or "remove ads"). Such a run plays on normally but is not a
     * fair result: it submits to no leaderboard ([Leaderboards.forGameOver]) and sets no best that unlocks a scenery
     * (docs/TOP100.md E, fairness).
     */
    val assisted get() = continued || bonusRoutersClaimed > 0

    /**
     * False in a daily challenge: it promises the same game for everyone, so neither the bonus router nor going on
     * after a game over exists there (its "few routers" rule would be undone by one video).
     */
    val extrasAllowed get() = daily == null && !guided

    /** True while the game is over and may still go on once ([continueAfterGameOver]); never in a [guided] world or a daily challenge. */
    val canContinue get() = gameOver && !continued && extrasAllowed

    /** Open week reward choice. While set, the simulation is paused until [chooseReward] is called. */
    var rewardOffer: RewardOffer? = null; private set

    /** Free server tier upgrades won as [Reward.SERVER_VOUCHER]; the next server upgrades spend these before budget. */
    var serverVouchers = 0; private set

    /** What the last week change unlocked, for the HUD; set at [lastNewsTime]. */
    var lastNews: WeekNews? = null; private set
    var lastNewsTime = 0f; private set

    /** How far the current week has run, 0 until 1; always 0 in a [guided] world, whose calendar stands still. */
    val weekProgress get() = if (guided) 0f else (time % Tuning.WEEK_SECONDS) / Tuning.WEEK_SECONDS

    /** In-game clock, 0 until 24: [Tuning.DAWN_HOUR] at time 0, one day per [Tuning.DAY_SECONDS]. */
    val hourOfDay get() = hourAt(time)

    /** True between [Tuning.DUSK_HOUR] and [Tuning.DAWN_HOUR]. */
    val isNight get() = hourOfDay.let { it >= Tuning.DUSK_HOUR || it < Tuning.DAWN_HOUR }

    /**
     * The calendar: [Tuning.ERA_YEARS] of the current week, shifted so the scenario starts in its [Scenario.startYear].
     * It stops once the era table ends. A scenery that starts after today (the future one, whose start week already
     * ends the table) moves on at the pace of the table from [Tuning.FUTURE_PACE_WEEK] on instead.
     */
    val year: Int
        get() = if (scenario.startYear > Tuning.ERA_YEARS.last()) {
            scenario.startYear + eraYear(Tuning.FUTURE_PACE_WEEK + weeksPlayed - 1) - eraYear(Tuning.FUTURE_PACE_WEEK)
        } else {
            scenario.startYear + eraYear(week) - eraYear(scenario.startWeek)
        }

    /** The calendar year of era [week] in this game, e.g. when a later invention comes; see [year]. */
    fun yearOfWeek(week: Int) = scenario.startYear + eraYear(week) - eraYear(scenario.startWeek)

    /** Weeks played in this game, 1 in the scenario's start week: pacing, growth and incidents follow this. */
    val weeksPlayed get() = week - scenario.startWeek + 1
    val unlockedCables get() = CableType.entries.filter(::invented)
    private val unlockedDevices get() = Device.entries.filter { unlimited || it.unlockWeek <= week }

    /** True if cable technology [t] can be laid: invented by now, or always in creative mode and on a fiber day. */
    fun invented(t: CableType) = unlimited || rule == DailyRule.FIBER_DAY || t.unlockWeek <= week

    fun invented(g: CellGeneration) = unlimited || g.unlockWeek <= week

    /** The generation a cell tower built now sends with. */
    fun newestCellGeneration() = CellGeneration.entries.last { it == CellGeneration.G3 || invented(it) }

    /** True if [cost] can be paid now; always in creative mode. */
    fun canPay(cost: Int) = unlimited || cost <= budget

    private fun pay(cost: Int) {
        if (!unlimited) budget -= cost
    }

    /** Services that have a server on the map; servers never go away, so this only grows. */
    var availableServices: Set<Service> = emptySet(); private set

    /**
     * The [unlocked] block in [week]: the scenario's start block plus one ring per [Tuning.GROWTH_WEEKS] weeks played,
     * within [bounds].
     */
    fun unlockedArea(week: Int): CellRect =
        CellRect.centered(bounds, scenario.startCols, scenario.startRows)
            .expand(((week - scenario.startWeek) / Tuning.GROWTH_WEEKS).coerceAtLeast(0) + if (rule == DailyRule.WIDE_LAND) Tuning.WIDE_RINGS else 0, bounds)

    private var clientSpawnTimer = Tuning.FIRST_SPAWN_SECONDS

    /** Best routes per client, indexed by [Service.ordinal]; [NO_ROUTE] marks a computed "none". Cleared on changes. */
    private val routeCache = HashMap<Node, Array<Route?>>()

    /** Per client and [Service.ordinal]: is there a route at any link width ([YES], [NO], [UNKNOWN])? For [routeProblem]. */
    private val wideCache = HashMap<Node, ByteArray>()

    /** Like [wideCache], for a route at any width with every incident counted as repaired. For [missingServer]. */
    private val islandCache = HashMap<Node, ByteArray>()

    /** Scratch tables of [computeRoute]. */
    private var routeDist = FloatArray(0)
    private var routePrev = IntArray(0)
    private var routeDone = BooleanArray(0)
    private val routeOpen = ArrayList<Node>()

    /** Same-channel neighbours of every access point, derived with the [radioLinks]. */
    private val interference = HashMap<Node, List<Node>>()

    /** Bandwidth on and waiting for every medium, see [LoadTally]. */
    private val tallies = HashMap<Any, LoadTally>()

    /** Scratch lists of [movePackets] and [admitWaiting], reused every step. */
    private val arrivedScratch = ArrayList<Packet>()
    private val responseScratch = ArrayList<Packet>()
    private val waitingScratch = ArrayList<Packet>()
    private val removeScratch = HashSet<Packet>()

    init {
        Scenarios.carve(scenario, this, rng)
        if (spawnInitialNodes) {
            // Mail and telephony in opposite corners of the start block, every other server due by the start week anywhere.
            for ((service, cell) in listOf(
                Service.MAIL to Cell(unlocked.left + 2, unlocked.top + 2),
                Service.CALL to Cell(unlocked.right - 3, unlocked.bottom - 3),
            )) {
                val at = nearestFree(cell) ?: continue
                addServer(service, at.x, at.y)
            }
            for (service in scenario.startServices) if (service != Service.MAIL && service != Service.CALL) spawnServer(service)
            // Creative mode has every technology, so a server of every service stands on the map from the start.
            if (unlimited) for (service in Service.entries) if (service !in availableServices) spawnServer(service)
            repeat(3) { spawnClient() }
        }
    }

    // ---------------------------------------------------------------- setup

    /** What cell ([cx], [cy]) is made of; [Terrain.LAND] outside the grid. */
    fun terrainAt(cx: Int, cy: Int): Terrain = when {
        cy !in 0 until rows || cx !in 0 until cols -> Terrain.LAND
        water[cy][cx] -> Terrain.WATER
        mountains[cy][cx] -> Terrain.MOUNTAIN
        highRises[cy][cx] -> Terrain.HIGH_RISE
        else -> Terrain.LAND
    }

    /** Makes cell ([cx], [cy]) [t], for the scenario's terrain and for tests. */
    fun setTerrain(cx: Int, cy: Int, t: Terrain) {
        water[cy][cx] = t == Terrain.WATER
        mountains[cy][cx] = t == Terrain.MOUNTAIN
        highRises[cy][cx] = t == Terrain.HIGH_RISE
    }

    fun isWater(cx: Int, cy: Int) = cy in 0 until rows && cx in 0 until cols && water[cy][cx]

    /** True if ([cx], [cy]) is unlocked, plain land and not covered by a node. */
    fun isFree(cx: Int, cy: Int): Boolean =
        unlocked.contains(cx, cy) && terrainAt(cx, cy) == Terrain.LAND && nodeAt(Cell(cx, cy)) == null

    /** [cell] if it is free, otherwise the closest free cell inside the unlocked block (ring by ring), or null. */
    fun nearestFree(cell: Cell): Cell? {
        for (r in 0..maxOf(cols, rows)) {
            for (dy in -r..r) for (dx in -r..r) {
                if (maxOf(abs(dx), abs(dy)) != r) continue
                if (isFree(cell.x + dx, cell.y + dy)) return Cell(cell.x + dx, cell.y + dy)
            }
        }
        return null
    }

    /** The node whose footprint covers [cell], if any. */
    fun nodeAt(cell: Cell): Node? = nodeList.firstOrNull { cell in it.footprint }

    private fun addNode(kind: NodeKind, device: Device?, service: Service?, cx: Int, cy: Int): Node {
        val n = Node(nextId++, kind, device, service, cx, cy)
        n.requestTimer = 2f + rng.nextFloat() * 3f
        if (kind == NodeKind.SERVER && ScenarioRule.ORBITAL_SERVERS in scenario.rules) n.level = 2
        if (kind == NodeKind.ACCESS_POINT) n.channel = Wifi.CHANNELS_2_4_GHZ.first()
        if (kind == NodeKind.CELL_TOWER) n.cellGeneration = newestCellGeneration()
        n.index = nodeList.size
        nodeList += n
        if (service != null && service !in availableServices) availableServices = availableServices + service
        networkChanged()
        return n
    }

    fun addClient(device: Device, cx: Int, cy: Int) = addNode(NodeKind.CLIENT, device, null, cx, cy)
    fun addServer(service: Service, cx: Int, cy: Int) = addNode(NodeKind.SERVER, null, service, cx, cy)
    fun addRouter(cx: Int, cy: Int) = addNode(NodeKind.ROUTER, null, null, cx, cy)

    /** Adds a radio node without using stock, for tests and setups; see [placeRadio]. */
    fun addRadio(type: RadioType, cx: Int, cy: Int) = addNode(type.kind, null, null, cx, cy)

    /**
     * A random free cell inside [area] (by default the [unlocked] block), one cell away from its edge, that [accept]s.
     */
    private fun randomFreeCell(
        minSpacing: Int = 2,
        area: CellRect = unlocked,
        accept: (Cell) -> Boolean = { true },
    ): Pair<Int, Int>? {
        if (area.width < 3 || area.height < 3) return null
        repeat(300) {
            val cx = area.left + 1 + rng.nextInt(area.width - 2)
            val cy = area.top + 1 + rng.nextInt(area.height - 2)
            if (isFree(cx, cy) && nodes.all { n -> n.footprint.all { max(abs(it.x - cx), abs(it.y - cy)) >= minSpacing } } &&
                accept(Cell(cx, cy))
            ) return cx to cy
        }
        return null
    }

    /** True while the map holds as many clients per unlocked cell as [Tuning.MAX_CLIENT_DENSITY] allows (the crowd rule has no limit). */
    fun isCrowded(): Boolean {
        if (rule == DailyRule.CROWD) return false
        val area = unlocked.let { (it.right - it.left) * (it.bottom - it.top) }
        return nodeList.count { it.kind == NodeKind.CLIENT } >= area * scenario.clientDensity
    }

    /**
     * A new client of a random invented device that wants a service with a server; newer devices show up more often.
     * In the first [Tuning.EARLY_WEEKS] it only goes where it can be served ([fairStart]); if the drawn device fits
     * nowhere, the first other device that does is taken instead.
     */
    private fun spawnClient() {
        val served = availableServices
        val candidates = unlockedDevices.filter { d -> d.services.any { it in served } }
        if (candidates.isEmpty()) return
        val weighted = candidates.flatMap { d -> List(1 + d.unlockWeek.coerceAtMost(Tuning.DEVICE_WEIGHT_WEEKS)) { d } }
        val device = weighted[rng.nextInt(weighted.size)]
        if (weeksPlayed <= Tuning.EARLY_WEEKS) {
            for (d in listOf(device) + (candidates - device)) {
                val (cx, cy) = randomFreeCell { fairStart(d, it) } ?: continue
                addClient(d, cx, cy)
                return
            }
        }
        val (cx, cy) = randomFreeCell() ?: return
        addClient(device, cx, cy)
    }

    /**
     * True if device [d] on [cell] can reach a server of every service it wants that has one, each with one direct
     * cable of an invented technology that is wide enough, fits [Tuning.EARLY_PING_SHARE] of the ping limit with a
     * router hop to spare, and costs at most [Tuning.EARLY_CABLE_BUDGET].
     */
    private fun fairStart(d: Device, cell: Cell): Boolean = d.services.all { s ->
        s !in availableServices || nodeList.any { n -> n.kind == NodeKind.SERVER && n.service == s && directCableFits(cell, n.cell, s) }
    }

    private fun directCableFits(from: Cell, to: Cell, s: Service): Boolean {
        val layout = planLayout(from, to)
        val limit = s.maxPingMs
        return unlockedCables.any { t ->
            t.capacity >= s.bandwidth && cableCost(layout, t) <= Tuning.EARLY_CABLE_BUDGET &&
                (limit == null || 2f * (layout.length * t.msPerCell + Tuning.ROUTER_MS) <= limit * Tuning.EARLY_PING_SHARE)
        }
    }

    /** A new server appears in the middle of the block ([Tuning.SERVER_AREA]), or anywhere in it if that is full. */
    private fun spawnServer(service: Service): Boolean {
        val middle = CellRect.centered(
            unlocked, (unlocked.width * Tuning.SERVER_AREA).toInt() + 2, (unlocked.height * Tuning.SERVER_AREA).toInt() + 2,
        )
        val (cx, cy) = randomFreeCell(3, middle) ?: randomFreeCell(2, middle) ?: randomFreeCell(1, middle)
            ?: randomFreeCell() ?: return false
        addServer(service, cx, cy)
        return true
    }

    // ---------------------------------------------------------------- player actions

    /** The cable between [a] and [b], if any; looks only at the few links of [a]. */
    fun cableBetween(a: Node, b: Node): Cable? {
        val links = a.links
        for (i in links.indices) {
            val l = links[i]
            if (l is Cable && l.other(a) === b) return l
        }
        return null
    }

    fun ports(n: Node): Int {
        var count = 0
        for (i in n.links.indices) if (n.links[i] is Cable) count++
        return count
    }

    /**
     * The layout a new cable from [a] to [b] gets: an L along the grid with the given [bend], or, without one,
     * the bend that pays less for terrain (water, mountains; horizontal first on a tie).
     */
    fun planLayout(a: Cell, b: Cell, bend: Bend? = null): CableLayout {
        if (bend != null) return CableLayout.between(a, b, bend)
        val h = CableLayout.between(a, b, Bend.HORIZONTAL_FIRST)
        val v = CableLayout.between(a, b, Bend.VERTICAL_FIRST)
        return if (terrainExtraOn(v) < terrainExtraOn(h)) v else h
    }

    fun planLayout(a: Node, b: Node, bend: Bend? = null) = planLayout(a.cell, b.cell, bend)

    fun waterCellsOn(layout: CableLayout) = layout.cells.count { isWater(it.x, it.y) }

    /** Budget a cable along [layout] pays for its terrain on top of the technology: sea cable, passes, downtown. */
    fun terrainExtraOn(layout: CableLayout) = layout.cells.sumOf { terrainAt(it.x, it.y).cableExtra }

    /** Cable cost in budget units: cells walked times type price, plus [terrainExtraOn] (water, mountains, towers). */
    fun cableCost(layout: CableLayout, type: CableType): Int =
        layout.steps * type.costPerCell + terrainExtraOn(layout)

    fun cableCost(a: Node, b: Node, type: CableType, bend: Bend? = null) = cableCost(planLayout(a, b, bend), type)

    /** Null when the cable from [a] to [b] is allowed, otherwise the reason. */
    fun connectError(a: Node, b: Node, type: CableType, bend: Bend? = null): ConnectError? = when {
        a === b || a.cell == b.cell -> ConnectError.SAME_NODE
        cableBetween(a, b) != null -> ConnectError.ALREADY_CONNECTED
        !invented(type) -> ConnectError.NOT_INVENTED
        ports(a) >= a.maxPorts -> ConnectError.FROM_PORTS_FULL
        ports(b) >= b.maxPorts -> ConnectError.TO_PORTS_FULL
        !canPay(cableCost(a, b, type, bend)) -> ConnectError.NO_BUDGET
        else -> null
    }

    /** Lays a cable along [planLayout] with [bend]. */
    fun connect(a: Node, b: Node, type: CableType, bend: Bend? = null): Boolean {
        if (gameOver || connectError(a, b, type, bend) != null) return false
        val layout = planLayout(a, b, bend)
        val cost = cableCost(layout, type)
        val cable = Cable(a, b, type, cost, layout, waterCellsOn(layout)).also { it.builtAt = time }
        cableList += cable
        pay(cost)
        cable.countedLaid = true
        if (counters.cableCredit > 0) counters.cableCredit-- else counters.cablesLaid++
        if (type == CableType.FIBER) countFiber(cable)
        networkChanged()
        return true
    }

    /** Swap an existing cable to a better technology, paying only the difference. */
    fun upgradeError(c: Cable, type: CableType): CableUpgradeError? {
        val diff = cableCost(c.layout, type) - c.cost
        return when {
            type.ordinal <= c.type.ordinal -> CableUpgradeError.NOT_AN_UPGRADE
            !invented(type) -> CableUpgradeError.NOT_INVENTED
            !canPay(diff) -> CableUpgradeError.NO_BUDGET
            else -> null
        }
    }

    fun upgrade(c: Cable, type: CableType): Boolean {
        if (gameOver || upgradeError(c, type) != null) return false
        val newCost = cableCost(c.layout, type)
        pay(newCost - c.cost)
        c.cost = newCost
        c.type = type
        c.upgradedAt = time
        c.countedUpgrades++
        if (counters.upgradeCredit > 0) counters.upgradeCredit-- else counters.cableUpgrades++
        if (type == CableType.FIBER) countFiber(c)
        forgetRoutes()
        return true
    }

    /** Counts [c] as fiber laid, unless a fiber cable removed for a refund left a credit (see [GameCounters]). */
    private fun countFiber(c: Cable) {
        if (c.countedFiber) return
        c.countedFiber = true
        if (counters.fiberCredit > 0) counters.fiberCredit-- else counters.fiberLaid++
    }

    /**
     * Budget [removeCable] gives back for [c]: its cost, or nothing while an excavator is announced at it or has cut it,
     * so calling the excavator off by removing and re-laying the cable is never free.
     */
    fun refundOf(c: Cable) = if (unlimited || incidentList.any { it.cable === c }) 0 else c.cost

    /** Removes [c] and refunds [refundOf]; an excavator waiting at it or a cut on it goes with it. */
    fun removeCable(c: Cable) {
        val refund = refundOf(c)
        if (!cableList.remove(c)) return
        budget += refund
        // A full refund undoes the cable: what it counted comes back as credit, so re-laying it counts nothing new.
        if (refund > 0 && refund >= c.cost) {
            if (c.countedLaid) counters.cableCredit++
            if (c.countedFiber) counters.fiberCredit++
            counters.upgradeCredit += c.countedUpgrades
        }
        incidentList.removeAll { it.cable === c }
        networkChanged()
    }

    /**
     * What re-routing [c] to run from [newA] to [newB] with [bend] costs: the new layout's price in [c]'s technology
     * minus what [c] cost so far. Negative when the new way is cheaper; that much comes back (see [reroute]).
     */
    fun rerouteCost(c: Cable, newA: Node, newB: Node, bend: Bend? = null): Int = cableCost(planLayout(newA, newB, bend), c.type) - c.cost

    /**
     * Null when [c] can be re-routed to run from [newA] to [newB] along [planLayout] with [bend], otherwise the reason.
     * An end of [c] that stays keeps its port; a new end needs a free one. A cable with an excavator announced at it or
     * a cut on it cannot move ([RerouteError.INCIDENT]): digging it up elsewhere would call the excavator off for free
     * (removing it gives no refund, see [refundOf]), and a cut is repaired first.
     */
    fun rerouteError(c: Cable, newA: Node, newB: Node, bend: Bend? = null): RerouteError? = when {
        c !in cableList -> RerouteError.GONE
        newA === newB || newA.cell == newB.cell -> RerouteError.SAME_NODE
        cableBetween(newA, newB).let { it != null && it !== c } -> RerouteError.ALREADY_CONNECTED
        incidentList.any { it.cable === c } -> RerouteError.INCIDENT
        planLayout(newA, newB, bend).waypoints.let { it == c.layout.waypoints || it == c.layout.waypoints.asReversed() } ->
            RerouteError.UNCHANGED
        needsPort(c, newA) && ports(newA) >= newA.maxPorts || needsPort(c, newB) && ports(newB) >= newB.maxPorts -> RerouteError.PORTS_FULL
        !canPay(maxOf(0, rerouteCost(c, newA, newB, bend))) -> RerouteError.NO_BUDGET
        else -> null
    }

    private fun needsPort(c: Cable, n: Node) = n !== c.a && n !== c.b

    /**
     * Re-routes [c] in one step, like re-drawing a line in Mini Metro: it runs from [newA] to [newB] along [planLayout]
     * with [bend] (new ends, or only the other bend between the same ends). Pays [rerouteCost], or refunds it when the
     * new way is cheaper; so re-routing never gives back more than [removeCable] would. The cable keeps its technology,
     * what it counted ([GameCounters]: a re-route is neither a new cable nor new fiber) and [Cable.upgradedAt]; it is
     * replaced by a new [Cable] at the same place in [cables] whose [Cable.builtAt] is now, so the new way plays the
     * laying animation. Packets on a link that still exists (same ends) keep going; those on a link that is gone go back
     * into their client's queue, like on a removed cable. Returns false (and changes nothing) on a [rerouteError].
     */
    fun reroute(c: Cable, newA: Node, newB: Node, bend: Bend? = null): Boolean {
        if (gameOver || rerouteError(c, newA, newB, bend) != null) return false
        val layout = planLayout(newA, newB, bend)
        val cost = cableCost(layout, c.type)
        val moved = Cable(newA, newB, c.type, cost, layout, waterCellsOn(layout)).also {
            it.builtAt = time
            it.upgradedAt = c.upgradedAt
            it.countedLaid = c.countedLaid
            it.countedFiber = c.countedFiber
            it.countedUpgrades = c.countedUpgrades
        }
        pay(cost - c.cost)
        cableList[cableList.indexOf(c)] = moved
        networkChanged()
        return true
    }

    /** Null when [c] can be repaired now, otherwise the reason. */
    fun repairError(c: Cable): RepairError? = when {
        !isCut(c) -> RepairError.NOT_CUT
        !canPay(Incidents.REPAIR_COST) -> RepairError.NO_BUDGET
        else -> null
    }

    /** Repairs a cut cable at once for [Incidents.REPAIR_COST] instead of waiting for it to repair itself. */
    fun repair(c: Cable): Boolean {
        if (gameOver || repairError(c) != null) return false
        pay(Incidents.REPAIR_COST)
        counters.repairs++
        incidentList.removeAll { it.cable === c }
        networkChanged()
        return true
    }

    /**
     * Rebuilds the [radioLinks] and the link index, forgets cached routes and recounts the loads. Requests and responses
     * on or heading into a link that no longer exists or is down ([isUp]) go back into their client's queue.
     */
    private fun networkChanged() {
        rebuildRadioLinks()
        forgetRoutes()
        val lost = packets.filter { p -> p.inTransit && usableLink(p.from, p.to) == null }
        if (lost.isNotEmpty()) {
            lost.forEach { it.origin.pending.addFirst(it.service) }
            packets.removeAll(lost.toSet())
        }
        tallies.clear()
        recountLoads()
    }

    /**
     * How far apart the lanes of cables over the same cells are drawn, as a multiple of [CableLanes.SPACING]: the view
     * widens them when the map is zoomed out ([CableLanes.spreadFor]). Only how cables are drawn changes, never the game.
     */
    var laneSpread = 1f
        set(value) {
            if (value == field) return
            field = value
            CableLanes.assign(cableList, value)
        }

    /** Puts every cable into the link lists of its two ends ([Node.links]), which [linkBetween] searches. */
    private fun indexCables() {
        for (n in nodeList) n.links.clear()
        for (c in cableList) {
            c.a.links += c
            c.b.links += c
        }
        CableLanes.assign(cableList, laneSpread)
    }

    /**
     * Radios without power ([isDark]) link nobody; mountains and towers in the way ([inRadioSight]) block a link.
     * Also re-indexes all links (cables before radio links) and the interference between access points.
     */
    private fun rebuildRadioLinks() {
        indexCables()
        radioLinkList.clear()
        interference.clear()
        for (n in nodeList) if (n.kind == NodeKind.ACCESS_POINT) findInterferers(n).takeIf { it.isNotEmpty() }?.let { interference[n] = it }
        for (r in nodeList) {
            val type = r.radio ?: continue
            if (isDark(r)) continue
            val capacity = radioCapacity(r)
            val slots = radioSlots(r) ?: Int.MAX_VALUE
            nodes.asSequence()
                .filter { it.kind == NodeKind.CLIENT && serves(type, it.device!!) && cableBetween(r, it) == null }
                .map { it to hypot(it.center.x - r.center.x, it.center.y - r.center.y) }
                .filter { (c, d) -> d <= r.radius + Wifi.EPSILON && inRadioSight(r, c) }
                .sortedWith(compareBy({ it.second }, { it.first.id }))
                .take(slots)
                .forEach { (client, _) -> radioLinkList += RadioLink(r, client, capacity) }
        }
        for (l in radioLinkList) {
            l.radio.links += l
            l.device.links += l
        }
    }

    /** True if radio [type] can link device [d] in this scenario ([ScenarioRule.SIX_G] opens cell towers to all). */
    fun serves(type: RadioType, d: Device) = type.serves(d) || (type == RadioType.CELL && ScenarioRule.SIX_G in scenario.rules)

    /**
     * True if no terrain that blocks radio ([Terrain.blocksRadio]) lies on the straight line between the centers of
     * [a] and [b]; the cells of the two nodes themselves do not count.
     */
    fun inRadioSight(a: Node, b: Node): Boolean {
        val from = a.center; val to = b.center
        val steps = (hypot(to.x - from.x, to.y - from.y) / SIGHT_STEP).toInt() + 1
        for (i in 1 until steps) {
            val t = i / steps.toFloat()
            val cell = Cell(floor(from.x + (to.x - from.x) * t).toInt(), floor(from.y + (to.y - from.y) * t).toInt())
            if (cell in a.footprint || cell in b.footprint) continue
            if (terrainAt(cell.x, cell.y).blocksRadio) return false
        }
        return true
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
        serverVouchers == 0 && !canPay(Tuning.SERVER_UPGRADE_COST[n.level - 1]) -> ServerUpgradeError.NO_BUDGET
        else -> null
    }

    /**
     * Raises a server one hardware tier, paid with a voucher if the player has one, otherwise with budget.
     * Reaching [Tuning.DATA_CENTER_LEVEL] claims the [dataCenterFootprint].
     */
    fun upgradeServer(n: Node): Boolean {
        if (gameOver || serverUpgradeError(n) != null) return false
        if (serverVouchers > 0) serverVouchers-- else pay(Tuning.SERVER_UPGRADE_COST[n.level - 1])
        if (n.level + 1 == Tuning.DATA_CENTER_LEVEL) n.footprint = dataCenterFootprint(n)!!
        n.level++
        n.upgradedAt = time
        counters.serverUpgrades++
        if (n.isDataCenter) counters.dataCenters++
        return true
    }

    /** True if [n] is a server whose next tier is reachable at all (ignoring budget). */
    private fun canGrow(n: Node) = serverUpgradeError(n).let { it == null || it == ServerUpgradeError.NO_BUDGET }

    /** Rewards that would have an effect right now; a server voucher only while some server can still grow. */
    fun eligibleRewards(): List<Reward> = Reward.entries.filter {
        when (it) {
            Reward.SERVER_VOUCHER -> nodes.any(::canGrow)
            Reward.ACCESS_POINT -> week >= RadioType.WLAN.unlockWeek
            Reward.CELL_TOWER -> week >= Tuning.CELL_TOWER_REWARD_WEEK
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

    /**
     * Adds [Rewards.BONUS_ROUTERS] routers on top of the open [rewardOffer] (the rewarded extra of the week screen),
     * at most once per offer. False if no offer is open, its bonus was already taken, or this is a daily challenge
     * ([extrasAllowed]). A taken bonus makes the run [assisted].
     */
    fun claimBonusRouter(): Boolean {
        if (!extrasAllowed) return false
        val offer = rewardOffer ?: return false
        if (offer.bonusClaimed) return false
        offer.bonusClaimed = true
        bonusRoutersClaimed++
        routersAvailable += Rewards.BONUS_ROUTERS
        return true
    }

    /**
     * Lets a lost game go on once: every overload ring is emptied and the simulation resumes where it stopped.
     * Waiting requests stay, so the player has one ring's time to fix the jam. False unless [canContinue].
     */
    fun continueAfterGameOver(): Boolean {
        if (!canContinue) return false
        for (n in nodes) n.overload = 0f
        gameOver = false
        failedNode = null
        continued = true
        return true
    }

    /** True while a server has no capacity left and packets queue on its cables. */
    fun serverBusy(n: Node) = n.kind == NodeKind.SERVER && n.tokens < 1f

    /** Requests that reached [server] and wait at the end of their cable for its throughput. */
    fun waitingAt(server: Node): Int {
        var count = 0
        for (i in packets.indices) {
            val p = packets[i]
            if (!p.isResponse && p.inTransit && p.to === server && p.progress >= SERVER_WAIT && p.hop + 2 == p.route.size) count++
        }
        return count
    }

    /** Null when a node of [kind] (a router or a radio) from stock can go on cell ([cx], [cy]), otherwise the reason. */
    fun placeError(kind: NodeKind, cx: Int, cy: Int): PlaceError? = when {
        !unlimited && (RadioType.of(kind)?.let(::radiosAvailable) ?: routersAvailable) <= 0 -> PlaceError.NO_STOCK
        !unlocked.contains(cx, cy) -> PlaceError.LOCKED
        nodeAt(Cell(cx, cy)) != null -> PlaceError.OCCUPIED
        terrainAt(cx, cy) != Terrain.LAND -> PlaceError.TERRAIN
        else -> null
    }

    fun placeRouter(cx: Int, cy: Int): Node? {
        if (gameOver || placeError(NodeKind.ROUTER, cx, cy) != null) return null
        if (!unlimited) routersAvailable--
        if (counters.routerCredit > 0) counters.routerCredit-- else counters.routersPlaced++
        return addRouter(cx, cy).also { it.countedPlacement = true }
    }

    /** Null when [n] can go back into stock ([pickUp]), otherwise the reason. */
    fun pickUpError(n: Node): PickUpError? = when {
        n.kind != NodeKind.ROUTER || n !in nodeList -> PickUpError.NOT_A_ROUTER
        ports(n) > 0 -> PickUpError.HAS_CABLES
        incidentList.any { it.node === n } -> PickUpError.INCIDENT
        else -> null
    }

    /** Takes a router without cables off the map and back into stock, so a misplaced one costs nothing. */
    fun pickUp(n: Node): Boolean {
        if (gameOver || pickUpError(n) != null) return false
        nodeList.remove(n)
        for (i in nodeList.indices) nodeList[i].index = i
        if (!unlimited) routersAvailable++
        // Back in stock for free: placing it again counts nothing new (see [GameCounters]).
        if (n.countedPlacement) counters.routerCredit++
        networkChanged()
        return true
    }

    /** Radios of [type] in stock. */
    fun radiosAvailable(type: RadioType) = when (type) {
        RadioType.WLAN -> accessPointsAvailable
        RadioType.CELL -> cellTowersAvailable
    }

    /** Places a radio from stock on a free cell, like [placeRouter]. A new access point starts on channel 1. */
    fun placeRadio(type: RadioType, cx: Int, cy: Int): Node? {
        if (gameOver || placeError(type.kind, cx, cy) != null) return null
        when (type) {
            RadioType.WLAN -> {
                if (!unlimited) accessPointsAvailable--
                counters.accessPoints++
            }
            RadioType.CELL -> {
                if (!unlimited) cellTowersAvailable--
                counters.cellTowers++
            }
        }
        return addRadio(type, cx, cy)
    }

    // ---------------------------------------------------------------- wireless

    /**
     * Access points that disturb [n]: other access points on the same channel whose radio circles overlap its own.
     * Empty for every other node. An access point without power ([isDark]) neither sends nor disturbs.
     * Derived with the [radioLinks], so reading it (every frame) costs nothing.
     */
    fun interferers(n: Node): List<Node> = interference[n] ?: emptyList()

    private fun findInterferers(n: Node): List<Node> {
        if (n.kind != NodeKind.ACCESS_POINT || isDark(n)) return emptyList()
        return nodeList.filter {
            it !== n && it.kind == NodeKind.ACCESS_POINT && it.channel == n.channel && !isDark(it) &&
                Wifi.overlaps(n.center, n.radius, it.center, it.radius)
        }
    }

    /** Shared capacity of radio [n] after interference ([Wifi.reduced]); 0 for other nodes. */
    fun radioCapacity(n: Node): Int {
        val type = n.radio ?: return 0
        return Wifi.reduced(n.cellGeneration?.capacity ?: type.capacity, interferers(n).size)
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
        !canPay(Wifi.UPGRADE_5_GHZ_COST) -> WifiUpgradeError.NO_BUDGET
        else -> null
    }

    /** Switches [ap] to 5 GHz for [Wifi.UPGRADE_5_GHZ_COST]: first 5 GHz channel, radius [Wifi.RADIUS_5_GHZ]. */
    fun upgradeTo5Ghz(ap: Node): Boolean {
        if (gameOver || wifiUpgradeError(ap) != null) return false
        pay(Wifi.UPGRADE_5_GHZ_COST)
        ap.fiveGhz = true
        ap.channel = Wifi.CHANNELS_5_GHZ.first()
        ap.upgradedAt = time
        networkChanged()
        return true
    }

    /** Null when [tower] can move to its next [CellGeneration], otherwise the reason. */
    fun cellUpgradeError(tower: Node): CellUpgradeError? {
        val next = tower.cellGeneration?.next
        return when {
            tower.kind != NodeKind.CELL_TOWER -> CellUpgradeError.NOT_A_CELL_TOWER
            next == null -> CellUpgradeError.NEWEST
            !invented(next) -> CellUpgradeError.NOT_INVENTED
            !canPay(next.upgradeCost) -> CellUpgradeError.NO_BUDGET
            else -> null
        }
    }

    /** Moves [tower] to its next generation for that generation's [CellGeneration.upgradeCost]. */
    fun upgradeCell(tower: Node): Boolean {
        if (gameOver || cellUpgradeError(tower) != null) return false
        val next = tower.cellGeneration!!.next!!
        pay(next.upgradeCost)
        tower.cellGeneration = next
        tower.upgradedAt = time
        networkChanged()
        return true
    }

    // ---------------------------------------------------------------- routing

    /** The cable or radio link between [a] and [b], if any (a cable first); looks only at the few links of [a]. */
    fun linkBetween(a: Node, b: Node): Link? {
        val links = a.links
        for (i in links.indices) if (links[i].other(a) === b) return links[i]
        return null
    }

    /** The link between [a] and [b] if packets can use it right now. */
    private fun usableLink(a: Node, b: Node): Link? = linkBetween(a, b)?.takeIf(::isUp)

    /** True if packets can use [l] right now: it is not cut by an excavator and both ends have power. */
    fun isUp(l: Link) = !(l is Cable && isCut(l)) && !isDark(l.a) && !isDark(l.b)

    /** True while an excavator has cut [c]: it carries nothing until it is repaired or repairs itself. */
    fun isCut(c: Cable) = incidentList.any { it.cable === c && it.struck }

    /** True while a power outage has switched [n] off: it forwards nothing, an access point links no clients. */
    fun isDark(n: Node) = incidentList.any { it.node === n && it.struck }

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
    fun bestRoute(client: Node, service: Service): Route? {
        val row = routeCache.getOrPut(client) { arrayOfNulls(Service.entries.size) }
        val cached = row[service.ordinal]
        if (cached != null) return cached.takeIf { it !== NO_ROUTE }
        val route = computeRoute(client, service)
        row[service.ordinal] = route ?: NO_ROUTE
        return route
    }

    /** Why [client]'s requests for [service] cannot leave right now; null if they have a route ([routeFor]). */
    fun routeProblem(client: Node, service: Service): RouteProblem? {
        if (bestRoute(client, service) != null) return if (routeFor(client, service) == null) RouteProblem.PING_TOO_HIGH else null
        val row = wideCache.getOrPut(client) { ByteArray(Service.entries.size) }
        if (row[service.ordinal] == UNKNOWN) row[service.ordinal] = if (computeRoute(client, service, minCapacity = 0) != null) YES else NO
        return if (row[service.ordinal] == YES) RouteProblem.TOO_NARROW else RouteProblem.NO_ROUTE
    }

    /** Why the game was lost, from the [failedNode] and the service most of its waiting requests ask for. */
    val failure: Failure?
        get() {
            val n = failedNode ?: return null
            val service = n.pending.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return null
            val problem = routeProblem(n, service)
            return Failure(n, service, problem, if (problem == RouteProblem.PING_TOO_HIGH) bestRoute(n, service)?.pingMs else null)
        }

    /**
     * The tip against [f], from its cause: a route that is only too narrow or too slow wants a wider or a faster cable,
     * unless repairing a cut cable would bring back one that is wide and fast enough ([repairWouldServe]); a jam a
     * second or wider cable, or an upgrade of the saturated server behind it ([jamServer]) while it can still grow, and
     * a second server of the service once it cannot. Without any route, a way that exists over the links an incident
     * took down ([missingServer] false) wants a repair if the cut cables are what blocks it, else a way around the
     * dark router ([LossTip.OUTAGE]); a device with all ports taken, or a service whose servers have all theirs taken
     * ([serverPortsFull]), a router; an unlinked device a cable; a linked one a way to a server of the service.
     */
    fun lossTip(f: Failure): LossTip {
        val n = f.node
        return when (f.problem) {
            RouteProblem.TOO_NARROW, RouteProblem.PING_TOO_HIGH -> when {
                repairWouldServe(n, f.service, f.service.bandwidth) -> LossTip.REPAIR
                f.problem == RouteProblem.TOO_NARROW -> LossTip.WIDER_CABLE
                else -> LossTip.FASTER_CABLE
            }
            null -> {
                val server = jamServer(n, f.service)
                when {
                    server == null -> LossTip.SECOND_CABLE
                    canGrow(server) -> LossTip.UPGRADE_SERVER
                    else -> LossTip.SECOND_SERVER
                }
            }
            RouteProblem.NO_ROUTE -> when {
                !missingServer(n, f.service) -> if (repairWouldServe(n, f.service, 0)) LossTip.REPAIR else LossTip.OUTAGE
                ports(n) >= n.maxPorts || serverPortsFull(f.service) -> LossTip.ROUTER
                !isLinkedIn(n) -> LossTip.CONNECT
                else -> LossTip.NEEDS_SERVER
            }
        }
    }

    /**
     * True if repairing the cables an excavator cut would give [client] a route to [service] at least [minCapacity]
     * wide and within its ping limit, with the routers and access points a power outage switched off still dark.
     */
    private fun repairWouldServe(client: Node, service: Service, minCapacity: Int): Boolean {
        if (incidentList.none { it.struck && it.kind == IncidentKind.EXCAVATOR }) return false
        val r = computeRoute(client, service, minCapacity, ignoreCuts = true) ?: return false
        val limit = service.maxPingMs ?: return true
        return r.pingMs <= limit
    }

    /** True if [service] has servers and every one of them has all its ports taken: a new cable to one is refused. */
    fun serverPortsFull(service: Service): Boolean {
        var any = false
        for (i in nodeList.indices) {
            val s = nodeList[i]
            if (s.kind != NodeKind.SERVER || s.service != service) continue
            if (ports(s) < s.maxPorts) return false
            any = true
        }
        return any
    }

    /**
     * What a cable of [type] from [client] to [other] (along [planLayout] with [bend]) would mean for the client's
     * services that have a server: the first one it is too narrow for, else the first whose best route through it
     * would break the ping limit, else the ping-limited one with the least room left. Null if nothing to say
     * (no ping-limited service reachable through it yet, or [client] is not a client).
     */
    fun checkCable(client: Node, other: Node, type: CableType, bend: Bend? = null): ServiceCheck? {
        val device = client.device ?: return null
        val services = device.services.filter { it in availableServices }
        services.firstOrNull { it.bandwidth > type.capacity }?.let { return ServiceCheck(it, RouteProblem.TOO_NARROW, null, it.maxPingMs) }
        if (client === other || client.cell == other.cell) return null
        val cable = Cable(client, other, type, 0, planLayout(client, other, bend), 0)
        var tightest: ServiceCheck? = null
        for (s in services) {
            val limit = s.maxPingMs ?: continue
            val ping = computeRoute(client, s, extra = cable)?.pingMs ?: continue
            if (ping > limit) return ServiceCheck(s, RouteProblem.PING_TOO_HIGH, ping, limit)
            if (tightest == null || limit - ping < tightest.limitMs!! - tightest.pingMs!!) tightest = ServiceCheck(s, null, ping, limit)
        }
        return tightest
    }

    private fun forgetRoutes() {
        routeCache.clear()
        wideCache.clear()
        islandCache.clear()
    }

    /**
     * Dijkstra over the node links, with its tables in arrays indexed by [Node.index] that are reused between calls.
     * The open list may hold a node twice; the first entry with the lowest distance is taken next. Links narrower than
     * [minCapacity] are left out; [extra] is a planned cable that is not laid yet but counts as if it were.
     */
    private fun computeRoute(
        client: Node, service: Service, minCapacity: Int = service.bandwidth, extra: Cable? = null, ignoreIncidents: Boolean = false,
        ignoreCuts: Boolean = false,
    ): Route? {
        val count = nodeList.size
        if (routeDist.size < count) {
            routeDist = FloatArray(count * 2)
            routePrev = IntArray(count * 2)
            routeDone = BooleanArray(count * 2)
        }
        val dist = routeDist
        val prev = routePrev
        val done = routeDone
        dist.fill(Float.MAX_VALUE, 0, count)
        prev.fill(-1, 0, count)
        done.fill(false, 0, count)
        val open = routeOpen
        open.clear()
        dist[client.index] = 0f
        open += client
        while (open.isNotEmpty()) {
            var best = 0
            for (i in 1 until open.size) if (dist[open[i].index] < dist[open[best].index]) best = i
            val cur = open.removeAt(best)
            if (done[cur.index]) continue
            done[cur.index] = true
            if (cur.kind == NodeKind.SERVER && cur.service == service) {
                val path = ArrayList<Node>()
                var n = cur.index
                while (n >= 0) { path.add(0, nodeList[n]); n = prev[n] }
                open.clear()
                return Route(path, 2f * dist[cur.index])
            }
            // Only the origin and routers, radios or other clients forward traffic; foreign servers are dead ends.
            if (cur !== client && cur.kind == NodeKind.SERVER) continue
            val hopCost = if (cur === client) 0f else Tuning.ROUTER_MS
            val links = cur.links
            for (i in links.indices) relax(client, cur, links[i], minCapacity, hopCost, ignoreIncidents, ignoreCuts)
            if (extra != null && extra.connects(cur)) relax(client, cur, extra, minCapacity, hopCost, ignoreIncidents, ignoreCuts)
        }
        return null
    }

    /**
     * One edge of [computeRoute]: reaching the far end of [c] from [cur] through it, if that is shorter. [ignoreCuts]
     * counts a cut cable as repaired but keeps dark nodes dark; [ignoreIncidents] counts every incident as over.
     */
    private fun relax(client: Node, cur: Node, c: Link, minCapacity: Int, hopCost: Float, ignoreIncidents: Boolean, ignoreCuts: Boolean) {
        if (c.capacity < minCapacity) return
        if (!ignoreIncidents && (if (ignoreCuts) isDark(c.a) || isDark(c.b) else !isUp(c))) return
        // A radio link only carries its device's own traffic, as the first hop: a radio reaches the network
        // through its cables, and cabled devices cannot ride on a wireless client.
        if (c is RadioLink && !(cur === client && c.device === client)) return
        val nb = c.other(cur)
        val d = routeDist[cur.index] + hopCost + c.latencyMs
        if (d < routeDist[nb.index]) {
            routeDist[nb.index] = d
            routePrev[nb.index] = cur.index
            routeOpen += nb
        }
    }

    /** Where [p] is in world space: on its link, or at the node it waits at. */
    fun packetPosition(p: Packet): Vec2 {
        if (!p.inTransit || p.progress < 0f) return if (p.inTransit) p.from.center else p.route.last().center
        val link = linkBetween(p.from, p.to) ?: return p.from.center
        return link.pointFrom(p.from, p.progress)
    }

    /** Like [packetPosition], but writes x and y into [out] instead of allocating, for drawing every frame. */
    fun packetPosition(p: Packet, out: FloatArray) {
        val link = if (p.inTransit && p.progress >= 0f) linkBetween(p.from, p.to) else null
        if (link != null) return link.pointFrom(p.from, p.progress, out)
        val at = if (p.inTransit) p.from.center else p.route.last().center
        out[0] = at.x
        out[1] = at.y
    }

    /** Bandwidth units currently travelling on [c]. */
    fun cableLoad(c: Cable) = linkLoad(c)

    /**
     * Bandwidth units currently travelling on the medium of [l]: the cable, or every link of the radio. Read from the
     * load counters, so it is exact after every [update] and every change of the network.
     */
    fun linkLoad(l: Link) = tallies[l.medium]?.moving ?: 0

    /** Bandwidth units waiting at either end of a link on the medium of [l] to enter it. */
    internal fun linkWaiting(l: Link) = tallies[l.medium]?.waiting ?: 0

    /**
     * The link holding back [client]'s oldest request that has a route ([routeFor]) right now: the first one on that
     * route whose medium has no room left for it. Null if the client waits for nothing, every link has room, or the
     * server is what holds it back ([jamServer]); a request without a route has a [routeProblem] instead.
     */
    fun jamLink(client: Node): Link? = jamBlock(client) as? Link

    /**
     * The saturated server holding back [client]'s oldest routed request: requests are parked at it for its throughput
     * ([serverBusy], [waitingAt]) and fill its cable, so a wider cable would not help. Null otherwise.
     */
    fun jamServer(client: Node): Node? = jamBlock(client) as? Node

    /** Like [jamServer], for [client]'s requests of [service] only, whichever request waits first. */
    fun jamServer(client: Node, service: Service): Node? {
        if (client.kind != NodeKind.CLIENT) return null
        val route = routeFor(client, service)?.nodes ?: return null
        return blockOn(route, service, null) as? Node
    }

    /** True if the medium of [link] has no room left for a request of [service]. */
    private fun full(link: Link, service: Service): Boolean {
        val t = tallies[link.medium] ?: return false
        return t.moving + t.waiting + service.bandwidth > link.capacity
    }

    /**
     * What holds [client] back: a full [Link], a saturated server [Node] behind its route's last link, or null.
     * [parked] holds [waitingAt] per [Node.index] when the caller counted it once for every client.
     */
    private fun jamBlock(client: Node, parked: IntArray? = null): Any? {
        if (client.kind != NodeKind.CLIENT) return null
        val pending = client.pending
        for (i in pending.indices) {
            val service = pending[i]
            val route = routeFor(client, service)?.nodes ?: continue
            return blockOn(route, service, parked)
        }
        return null
    }

    /** What holds back a request of [service] on [route]: its first full [Link], a saturated server [Node], or null. */
    private fun blockOn(route: List<Node>, service: Service, parked: IntArray?): Any? {
        val server = route.last()
        for (k in 0 until route.size - 2) {
            val link = linkBetween(route[k], route[k + 1]) ?: continue
            if (full(link, service)) return link
        }
        // Requests parked at the server keep its cable full whenever it has room for a moment: the server it is.
        if (serverBusy(server) && (parked?.get(server.index) ?: waitingAt(server)) > 0) return server
        return linkBetween(route[route.size - 2], server)?.takeIf { full(it, service) }
    }

    /**
     * True once client [n] has been held back by a full link ([jamLink]) or a saturated server ([jamServer]) for
     * [Tuning.JAM_SECONDS] with a queue of [Tuning.JAM_PENDING] or more, and for [Tuning.JAM_HOLD] after that ends:
     * its requests have a route but stand in a jam.
     */
    fun isJammed(n: Node) = n.kind == NodeKind.CLIENT && n.jamTime >= Tuning.JAM_SECONDS

    /** [waitingAt] per [Node.index], counted once per step by [trackJams]. */
    private var parkedScratch = IntArray(0)

    /** True while cable [c] is the [jamLink] of a jammed client ([isJammed]), or was within [Tuning.JAM_HOLD]. */
    fun isJammed(c: Cable) = c.jamTime >= Tuning.JAM_SECONDS

    /** The cables that are jammed right now ([isJammed]). */
    fun jammedCables(): List<Cable> = cableList.filter(::isJammed)

    /** True if [client] is linked to the network: at least one of its cables or radio links can carry packets. */
    fun isLinkedIn(client: Node): Boolean {
        val links = client.links
        for (i in links.indices) if (isUp(links[i])) return true
        return false
    }

    /**
     * Services [client] wants that have a server somewhere ([availableServices]) but none it can reach, although it
     * is linked in ([isLinkedIn]): typically a device cabled to a server of another service. Empty for an unlinked
     * device (it shows that by itself) and for other nodes.
     */
    fun unreachableServices(client: Node): List<Service> {
        val device = client.device ?: return emptyList()
        if (!isLinkedIn(client)) return emptyList()
        return device.services.filter { it in availableServices && missingServer(client, it) }
    }

    /** The first of [unreachableServices], without building the list; for drawing every frame. */
    fun firstUnreachableService(client: Node): Service? {
        val device = client.device ?: return null
        if (!isLinkedIn(client)) return null
        val services = device.services
        for (i in services.indices) {
            val s = services[i]
            if (s in availableServices && missingServer(client, s)) return s
        }
        return null
    }

    /**
     * True if [client] has no route to a server of [s] even with the links incidents took down ([isUp]) counted as
     * up: a cut cable or a dark router is an incident with its own look, not a missing server.
     */
    private fun missingServer(client: Node, s: Service): Boolean {
        if (routeProblem(client, s) != RouteProblem.NO_ROUTE) return false
        if (incidentList.none { it.struck }) return true
        // Drawn every frame for each device while an incident is struck: the search is cached like [wideCache].
        val row = islandCache.getOrPut(client) { ByteArray(Service.entries.size) }
        if (row[s.ordinal] == UNKNOWN) row[s.ordinal] = if (computeRoute(client, s, minCapacity = 0, ignoreIncidents = true) != null) YES else NO
        return row[s.ordinal] == NO
    }

    /**
     * Advances the jam timers once per step. A client grows its [Node.jamTime] while a [jamLink] or [jamServer] holds
     * back a queue of [Tuning.JAM_PENDING] or more; one still building up drops to 0 as soon as nothing holds it back,
     * one already shown as jammed stays so for [Tuning.JAM_HOLD] more, so a queue swinging around the threshold does
     * not blink. A cable is jammed only as the [jamLink] of a jammed client, with the same hold: a busy cable that keeps
     * up stays calm, and a server's own cable never glows for the server's slowness.
     */
    private fun trackJams(dt: Float) {
        if (parkedScratch.size < nodeList.size) parkedScratch = IntArray(nodeList.size * 2)
        val parked = parkedScratch
        parked.fill(0, 0, nodeList.size)
        for (i in packets.indices) {
            val p = packets[i]
            if (!p.isResponse && p.inTransit && p.progress >= SERVER_WAIT && p.hop + 2 == p.route.size) parked[p.to.index]++
        }
        val shown = Tuning.JAM_SECONDS + Tuning.JAM_HOLD
        for (i in cableList.indices) cableList[i].let { it.jamTime = if (it.jamTime > Tuning.JAM_SECONDS) it.jamTime - dt else 0f }
        for (i in nodeList.indices) {
            val n = nodeList[i]
            if (n.kind != NodeKind.CLIENT) continue
            // One request waiting for the one in flight is a cable that keeps up; a jam is a queue that builds.
            val block = if (n.pending.size < Tuning.JAM_PENDING) null else jamBlock(n, parked)
            n.jamTime = when {
                block != null -> minOf(n.jamTime + dt, shown)
                n.jamTime > Tuning.JAM_SECONDS -> n.jamTime - dt
                else -> 0f
            }
            if (block is Cable && isJammed(n)) block.jamTime = shown
        }
    }

    /**
     * Load counters of one medium: bandwidth units travelling on it ([moving]), waiting at one of its ends to enter it
     * ([waiting]), and the room waiting responses claim while [admitWaiting] runs ([blocked]).
     */
    private class LoadTally {
        var moving = 0
        var waiting = 0
        var blocked = 0
    }

    private fun tally(medium: Any) = tallies.getOrPut(medium) { LoadTally() }

    /**
     * Counts every packet in transit onto its medium, in one pass. Runs after network changes and once per step, so
     * packets added or removed from outside (tests, restore) are counted too; within a step the counters follow every
     * packet incrementally.
     */
    private fun recountLoads() {
        for (t in tallies.values) { t.moving = 0; t.waiting = 0 }
        for (i in packets.indices) {
            val p = packets[i]
            if (!p.inTransit) continue
            val link = linkBetween(p.from, p.to) ?: continue
            val t = tally(link.medium)
            if (p.progress >= 0f) t.moving += p.size else t.waiting += p.size
        }
    }

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

    /**
     * Announces an excavator at [cable] right now, digging at [cutAt] (fraction of its length; by default the first spot
     * the plan could pick), for tests and a future debug menu.
     */
    @DebugApi
    fun announceExcavator(cable: Cable, cutAt: Float = cutSpots(cable).firstOrNull() ?: 0.5f): Incident {
        require(cable in cables && incidentList.none { it.cable === cable }) { "no free cable" }
        return Incident(IncidentKind.EXCAVATOR, cable, null, cutAt).also { incidentList += it }
    }

    /** Announces a power outage at [node] right now, for tests and a future debug menu. */
    @DebugApi
    fun announcePowerOutage(node: Node): Incident {
        require(node in nodes && incidentList.none { it.node === node }) { "no free node" }
        return Incident(IncidentKind.POWER_OUTAGE, null, node, 0f).also { incidentList += it }
    }

    /** Advances the simulation by [dt] seconds. Does nothing after game over or while a [rewardOffer] is open. */
    fun update(dt: Float) {
        if (gameOver || rewardOffer != null) return
        val prevWeek = week
        val prevTime = time
        time += dt
        while (arrivalList.isNotEmpty() && time - arrivalList[0].time > Tuning.ARRIVAL_SECONDS) arrivalList.removeAt(0)
        if (backupRuns(prevTime, time)) queueBackups()
        if (!guided) week = 1 + (time / Tuning.WEEK_SECONDS).toInt()
        if (week != prevWeek) {
            onNewWeek()
            return
        }
        advanceIncidents(dt)
        if (!guided) startIncidents(prevTime, time)

        if (!guided) clientSpawnTimer -= dt
        if (clientSpawnTimer <= 0f && isCrowded()) {
            clientSpawnTimer = Tuning.CROWDED_RETRY_SECONDS
        } else if (clientSpawnTimer <= 0f) {
            spawnClient()
            clientSpawnTimer = (max(Tuning.MIN_SPAWN_SECONDS, Tuning.SPAWN_SECONDS - weeksPlayed * Tuning.SPAWN_SPEEDUP) +
                rng.nextFloat() * Tuning.SPAWN_JITTER) * if (rule == DailyRule.CROWD) Tuning.CROWD_SPAWN_FACTOR else 1f
        }

        recountLoads()
        findJammed()
        val served = availableServices
        for (i in nodeList.indices) {
            val n = nodeList[i]
            if (n.kind != NodeKind.CLIENT) continue
            n.requestTimer -= if (jammed.isNotEmpty() && nearJam(n)) dt * Tuning.SLOW_FACTOR else dt
            if (n.requestTimer <= 0f) request(n, served)
            n.dispatchCooldown -= dt
            if (n.pending.isNotEmpty() && n.dispatchCooldown <= 0f) dispatch(n)
        }

        movePackets(dt)
        trackJams(dt)

        val fillSeconds = Tuning.OVERLOAD_SECONDS * (if (weeksPlayed <= Tuning.EARLY_WEEKS) Tuning.EARLY_OVERLOAD_SLOWDOWN else 1f) *
            (if (rule == DailyRule.CROWD) Tuning.CROWD_OVERLOAD_SLOWDOWN else 1f)
        for (i in nodeList.indices) {
            val n = nodeList[i]
            if (n.kind != NodeKind.CLIENT) continue
            n.overload = if (n.pending.size >= Tuning.MAX_PENDING) n.overload + dt / fillSeconds
            else max(0f, n.overload - dt / Tuning.RECOVER_SECONDS)
            if (guided) n.overload = n.overload.coerceAtMost(Tuning.GUIDED_MAX_OVERLOAD)
            if (n.overload >= 1f) {
                n.overload = 1f
                if (!mode.endsOnOverload) continue
                gameOver = true
                failedNode = n
                return
            }
        }
    }

    /** Devices at a full overload ring in a mode without game over, found once per [update] step. */
    private val jammed = ArrayList<Node>()

    private fun findJammed() {
        jammed.clear()
        if (mode.endsOnOverload) return
        for (i in nodeList.indices) {
            val n = nodeList[i]
            if (n.kind == NodeKind.CLIENT && n.overload >= 1f) jammed += n
        }
    }

    /**
     * True if client [n] is slowed down ([GameMode.ENDLESS], [GameMode.CREATIVE]): a device within [Tuning.SLOW_RADIUS]
     * cells of it (itself included) has a full overload ring, so it asks only [Tuning.SLOW_FACTOR] as often. The jam
     * costs the area deliveries instead of ending the game. Always false in a normal game.
     */
    fun isSlowed(n: Node): Boolean {
        if (mode.endsOnOverload || n.kind != NodeKind.CLIENT) return false
        for (i in nodeList.indices) {
            val j = nodeList[i]
            if (j.kind == NodeKind.CLIENT && j.overload >= 1f &&
                hypot(j.center.x - n.center.x, j.center.y - n.center.y) <= Tuning.SLOW_RADIUS + Wifi.EPSILON
            ) return true
        }
        return false
    }

    private fun nearJam(n: Node): Boolean {
        for (i in jammed.indices) {
            val j = jammed[i]
            if (hypot(j.center.x - n.center.x, j.center.y - n.center.y) <= Tuning.SLOW_RADIUS + Wifi.EPSILON) return true
        }
        return false
    }

    /**
     * True if [n] may queue one more request for [service]. In a mode without game over a queue holds at most
     * [Tuning.MAX_PENDING] requests; further ones are lost.
     *
     * In a normal game a request that has a route always queues: a backlog behind a busy route is the jam the overload
     * ring measures. A request for a service [n] has no route to ([routeFor]: not connected, cable cut, too narrow or
     * too slow) only fills the queue up to [Tuning.MAX_PENDING] and is lost beyond that. A forgotten device still
     * reaches the limit and overloads at the usual speed, so ignoring devices does not pay; but its queue no longer
     * grows without end while it waits, so once the player connects it (or the cut cable is repaired), its first sent
     * request takes it below the limit and the ring starts to empty at once, instead of after a long backlog drained.
     */
    private fun queueHasRoom(n: Node, service: Service) =
        n.pending.size < Tuning.MAX_PENDING || (mode.endsOnOverload && routeFor(n, service) != null)

    /**
     * Queues the client's next request. A streaming device ([Device.stream]) asks for its stream in a fixed rhythm;
     * any other device picks one of its [Demand.RANDOM] services, and its pause shrinks week by week.
     */
    private fun request(n: Node, served: Set<Service>) {
        val device = n.device!!
        val stream = device.stream
        if (stream != null) {
            if (stream in served && queueHasRoom(n, stream)) n.pending.addLast(stream)
            n.requestTimer += Tuning.STREAM_SECONDS
            return
        }
        val wants = device.services.filter { it.demand == Demand.RANDOM && it in served }
        if (wants.isNotEmpty()) {
            val s = wants[rng.nextInt(wants.size)]
            if (queueHasRoom(n, s)) n.pending.addLast(s)
        }
        n.requestTimer = (max(Tuning.MIN_REQUEST_SECONDS, Tuning.REQUEST_SECONDS - weeksPlayed * Tuning.REQUEST_SPEEDUP) +
            rng.nextFloat() * Tuning.REQUEST_JITTER) * if (rule == DailyRule.RUSH_HOUR) Tuning.RUSH_REQUEST_FACTOR else 1f
    }

    /** True if the nightly backup time ([Tuning.BACKUP_HOUR]) lies in (from, to]. */
    private fun backupRuns(from: Float, to: Float): Boolean {
        val offset = ((Tuning.BACKUP_HOUR - Tuning.DAWN_HOUR + 24f) % 24f) / 24f * Tuning.DAY_SECONDS
        return floor((to - offset) / Tuning.DAY_SECONDS) > floor((from - offset) / Tuning.DAY_SECONDS)
    }

    /**
     * The nightly load peak: every client queues [Tuning.BACKUP_BURST] requests of each [Demand.NIGHTLY] service it uses
     * and that has a server, all at the same moment. A client whose last backup still waits starts no new one.
     */
    private fun queueBackups() {
        val served = availableServices
        for (n in nodes) {
            if (n.kind != NodeKind.CLIENT) continue
            for (s in n.device!!.services) {
                if (s.demand != Demand.NIGHTLY || s !in served || s in n.pending) continue
                repeat(Tuning.BACKUP_BURST) { if (queueHasRoom(n, s)) n.pending.addLast(s) }
            }
        }
    }

    /**
     * Sends the oldest request that currently has a valid route and room on its first link. Packets already waiting
     * to enter that link's medium at either end (answers on their way back, traffic passing through) keep their claim on it.
     */
    private fun dispatch(client: Node) {
        val pending = client.pending
        for (i in pending.indices) {
            val service = pending[i]
            val route = routeFor(client, service) ?: continue
            val first = linkBetween(route.nodes[0], route.nodes[1]) ?: continue
            val load = tally(first.medium)
            if (load.moving + load.waiting + service.bandwidth > first.capacity) continue
            packets += Packet(service, client, route.nodes).apply { progress = 0f }
            load.moving += service.bandwidth
            pending.removeAt(i)
            client.dispatchCooldown = Tuning.DISPATCH_COOLDOWN
            return
        }
    }

    /**
     * Moves requests and responses along their routes. A request that reaches its server takes one throughput token
     * and turns into a response waiting at the server; a response that reaches its client counts as delivered.
     * Packets first travel and arrive, then waiting packets enter their next cable: responses before requests, and a
     * request only takes room that no waiting response on that cable needs. Otherwise requests queued behind a busy
     * cable (at a client or at a router) could grab every freed slot and keep the answers from ever leaving.
     * [dispatch] leaves room for waiting packets too, so a client with a backlog cannot refill such a slot either.
     */
    private fun movePackets(dt: Float) {
        for (i in nodeList.indices) {
            val n = nodeList[i]
            if (n.kind == NodeKind.SERVER) n.tokens = minOf(serverRate(n), n.tokens + serverRate(n) * dt)
        }
        val arrived = arrivedScratch
        val responses = responseScratch
        for (i in packets.indices) {
            val p = packets[i]
            if (p.progress < 0f) continue
            val on = linkBetween(p.from, p.to)
            val link = on?.takeIf(::isUp)
            if (link == null) {
                arrived += p
                p.origin.pending.addFirst(p.service)
                if (on != null) tally(on.medium).moving -= p.size
                continue
            }
            p.progress += link.speed * dt / maxOf(link.length, MIN_LINK_LENGTH)
            if (p.progress < 1f) continue
            val last = p.hop + 1 >= p.route.size - 1
            if (last && !p.isResponse && p.to.tokens < 1f) {
                // Server is saturated: the request waits at the end of the cable and keeps blocking it.
                p.progress = SERVER_WAIT
                continue
            }
            tally(link.medium).moving -= p.size
            p.hop++
            when {
                !last -> {
                    p.progress = -1f
                    linkBetween(p.from, p.to)?.let { tally(it.medium).waiting += p.size }
                }
                p.isResponse -> {
                    arrived += p
                    delivered++
                    counters.deliveredBy[p.service.ordinal]++
                    arrivalList += Arrival(p.origin, p.service, isResponse = true, time)
                }
                else -> {
                    p.route.last().tokens -= 1f
                    arrivalList += Arrival(p.route.last(), p.service, isResponse = false, time)
                    arrived += p
                    val response = Packet(p.service, p.origin, p.route.asReversed(), isResponse = true)
                    responses += response
                    tally(link.medium).waiting += response.size
                }
            }
        }
        removePackets(arrived)
        packets += responses
        responses.clear()
        admitWaiting()
    }

    /** Removes [gone] from [packets] in one pass and empties it. */
    private fun removePackets(gone: MutableList<Packet>) {
        if (gone.isEmpty()) return
        removeScratch.addAll(gone)
        packets.removeAll(removeScratch)
        removeScratch.clear()
        gone.clear()
    }

    /**
     * Lets waiting packets enter their next link where its medium has room, responses first. A packet whose next link
     * is gone or down goes back into its client's queue, like one on a removed cable.
     */
    private fun admitWaiting() {
        val waiting = waitingScratch
        for (i in packets.indices) {
            val p = packets[i]
            if (p.inTransit && p.progress < 0f) waiting += p
        }
        if (waiting.isEmpty()) return
        val stranded = arrivedScratch
        for (i in waiting.indices) {
            val p = waiting[i]
            if (usableLink(p.from, p.to) != null) continue
            stranded += p
            p.origin.pending.addFirst(p.service)
            linkBetween(p.from, p.to)?.let { tally(it.medium).waiting -= p.size }
        }
        removePackets(stranded)
        for (t in tallies.values) t.blocked = 0
        for (responsesFirst in RESPONSES_FIRST) for (i in waiting.indices) {
            val p = waiting[i]
            if (p.isResponse != responsesFirst) continue
            val link = usableLink(p.from, p.to) ?: continue
            val t = tally(link.medium)
            val reserved = if (p.isResponse) 0 else t.blocked
            if (t.moving + reserved + p.size <= link.capacity) {
                p.progress = 0f
                t.moving += p.size
                t.waiting -= p.size
            } else if (p.isResponse) {
                t.blocked += p.size
            }
        }
        waiting.clear()
    }

    // ---------------------------------------------------------------- incidents

    /** Counts down announcements and effects; an incident that strikes or ends changes the network. */
    private fun advanceIncidents(dt: Float) {
        if (incidentList.isEmpty()) return
        var changed = false
        val ended = ArrayList<Incident>()
        for (i in incidentList) {
            if (!i.struck) {
                i.warning -= dt
                if (i.struck) changed = true
            } else {
                i.remaining -= dt
                if (i.remaining <= 0f) ended += i
            }
        }
        if (ended.isNotEmpty()) {
            incidentList.removeAll(ended.toSet())
            changed = true
        }
        if (changed) networkChanged()
    }

    /** Announces every incident of this week's [Incidents.plan] whose time lies in (from, to]. */
    @OptIn(DebugApi::class)
    private fun startIncidents(from: Float, to: Float) {
        if (!incidentsEnabled || unlimited) return
        if (planWeek != week) {
            planWeek = week
            weekPlan = Incidents.plan(seed, weeksPlayed + if (rule == DailyRule.STORM) Tuning.STORM_WEEKS_AHEAD else 0)
        }
        val weekStart = (week - 1) * Tuning.WEEK_SECONDS
        for (p in weekPlan) if (p.at > from - weekStart && p.at <= to - weekStart) startIncident(p)
    }

    /**
     * Announces [p] at a target drawn with its own random stream: the planned kind if it finds one, otherwise the other
     * kind; with no target at all nothing happens.
     */
    private fun startIncident(p: PlannedIncident) {
        val r = Random(p.pick)
        val kinds = if (p.kind == IncidentKind.EXCAVATOR) IncidentKind.entries else IncidentKind.entries.reversed()
        for (kind in kinds) {
            val incident = when (kind) {
                IncidentKind.EXCAVATOR -> excavatorTarget(r)
                IncidentKind.POWER_OUTAGE -> outageTarget(r)
            }
            if (incident != null) {
                incidentList += incident
                return
            }
        }
    }

    /** A cable no other incident is at, and a dry spot on it that no node covers. */
    private fun excavatorTarget(r: Random): Incident? {
        val candidates = cables.mapNotNull { c -> cutSpots(c).takeIf { it.isNotEmpty() && incidentList.none { i -> i.cable === c } }?.let { c to it } }
        if (candidates.isEmpty()) return null
        val (cable, spots) = candidates[r.nextInt(candidates.size)]
        return Incident(IncidentKind.EXCAVATOR, cable, null, spots[r.nextInt(spots.size)])
    }

    /**
     * Where an excavator can dig on [c], as fractions of its length: the centers of its plain land cells between the
     * ends that no node covers, or the middle of a one-step cable.
     */
    private fun cutSpots(c: Cable): List<Float> {
        val cells = c.layout.cells
        if (cells.size == 2) return listOf(0.5f)
        return (1 until cells.size - 1)
            .filter { i -> cells[i].let { terrainAt(it.x, it.y) == Terrain.LAND && nodeAt(it) == null } }
            .map { it / c.layout.steps.toFloat() }
    }

    /** A cabled router or access point no other incident is at. */
    private fun outageTarget(r: Random): Incident? {
        val candidates = nodes.filter { n ->
            (n.kind == NodeKind.ROUTER || n.kind == NodeKind.ACCESS_POINT) && ports(n) > 0 && incidentList.none { it.node === n }
        }
        if (candidates.isEmpty()) return null
        return Incident(IncidentKind.POWER_OUTAGE, null, candidates[r.nextInt(candidates.size)], 0f)
    }

    /**
     * Moves a [guided] world's calendar on to week [target] at once: its cables, devices and radios become invented and
     * are announced together as [lastNews]. Unlike a week change in a normal game, no server appears, the map does not
     * grow and no reward is offered; the tutorial sets the scene itself.
     */
    fun advanceEra(target: Int) {
        require(guided) { "only a guided world changes eras on demand" }
        if (target <= week) return
        val weeks = week + 1..target
        week = target
        lastNews = WeekNews(
            year,
            CableType.entries.filter { it.unlockWeek in weeks },
            Device.entries.filter { it.unlockWeek in weeks },
            emptyList(),
            RadioType.entries.filter { it.unlockWeek in weeks },
            CellGeneration.entries.filter { it.unlockWeek in weeks && it.unlockWeek > RadioType.CELL.unlockWeek },
        )
        lastNewsTime = time
        forgetRoutes()
    }

    private fun onNewWeek() {
        unlocked = unlockedArea(week)
        // Creative mode has unlimited everything: no pay, nothing to pick.
        if (!unlimited) {
            budget += if (rule == DailyRule.TIGHT_BUDGET) Tuning.TIGHT_WEEK_BUDGET else Tuning.WEEK_BUDGET
            rewardOffer = RewardOffer(week, Rewards.offer(seed, week, eligibleRewards()))
        }
        // Only what was not there before is news: creative mode (and a fiber day, for cables) invents it all at the start.
        val newCables = if (unlimited || rule == DailyRule.FIBER_DAY) emptyList() else WeekSchedule.cables(week)
        val newDevices = if (unlimited) emptyList() else WeekSchedule.devices(week)
        val server = WeekSchedule.firstServer(week)
            ?: if (WeekSchedule.randomServer(week)) Service.entries[rng.nextInt(Service.entries.size)] else null
        val newServers = if (server != null && spawnServer(server)) listOf(server) else emptyList()
        val newRadios = if (unlimited) emptyList() else WeekSchedule.radios(week)
        val newGenerations = if (unlimited) emptyList() else WeekSchedule.cellGenerations(week)
        // Mobile radio comes with the smartphone: one 3G tower as a gift, more are won from Tuning.CELL_TOWER_REWARD_WEEK.
        if (!unlimited && RadioType.CELL in newRadios) cellTowersAvailable += Tuning.FIRST_CELL_TOWERS
        if (newCables.isNotEmpty() || newDevices.isNotEmpty() || newServers.isNotEmpty() || newRadios.isNotEmpty() ||
            newGenerations.isNotEmpty()
        ) {
            lastNews = WeekNews(year, newCables, newDevices, newServers, newRadios, newGenerations)
            lastNewsTime = time
        }
    }

    // ---------------------------------------------------------------- save

    /** The complete state as plain data, see [Save]. */
    @OptIn(DebugApi::class)
    fun snapshot() = WorldSnapshot(
        scenario = scenario.id,
        cols = cols,
        rows = rows,
        seed = seed,
        randomDraws = rng.draws,
        nextId = nextId,
        water = (0 until rows).map { y -> String(CharArray(cols) { x -> TERRAIN_CHARS[terrainAt(x, y).ordinal] }) },
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
        rewardOffer = rewardOffer?.let { RewardOfferSnapshot(it.week, it.choices, it.bonusClaimed) },
        continued = continued,
        bonusRoutersClaimed = bonusRoutersClaimed,
        serverVouchers = serverVouchers,
        lastNews = lastNews,
        lastNewsTime = lastNewsTime,
        clientSpawnTimer = clientSpawnTimer,
        nodes = nodes.map {
            NodeSnapshot(
                it.id, it.kind, it.device, it.service, it.cellX, it.cellY, it.footprint, it.pending.toList(),
                it.overload, it.level, it.tokens, it.requestTimer, it.dispatchCooldown, it.channel, it.fiveGhz,
                it.cellGeneration,
            )
        },
        cables = cables.map { CableSnapshot(it.a.id, it.b.id, it.type, it.cost, it.layout.waypoints.map(::cellOf), it.waterCells) },
        packets = packets.map { PacketSnapshot(it.service, it.origin.id, it.route.map(Node::id), it.isResponse, it.hop, it.progress) },
        incidentsEnabled = incidentsEnabled,
        incidents = incidentList.map {
            IncidentSnapshot(it.kind, it.cable?.a?.id, it.cable?.b?.id, it.node?.id, it.cutAt, it.warning, it.remaining)
        },
        mode = mode,
        dailyDay = daily?.day,
    )

    companion object {
        /** Save characters of [Terrain], by ordinal: land, water, mountain, high-rise. */
        private const val TERRAIN_CHARS = ".~^#"
        /** Radio links to a neighbour can be short; packets on them still take a visible moment. */
        private const val MIN_LINK_LENGTH = 0.5f
        /** Sampling step along a radio's line of sight, in cells. */
        private const val SIGHT_STEP = 0.1f
        /** Order of the two admission passes of [admitWaiting]. */
        private val RESPONSES_FIRST = booleanArrayOf(true, false)
        /** Cache marker of [bestRoute] for "no route". */
        private val NO_ROUTE = Route(emptyList(), Float.MAX_VALUE)
        private const val UNKNOWN: Byte = 0
        private const val YES: Byte = 1
        private const val NO: Byte = 2
        /** Progress at which a request waits at the end of its cable for a busy server. */
        private const val SERVER_WAIT = 0.999f

        /** [Tuning.ERA_YEARS] of era [week]; weeks past the table stay in its last year. */
        fun eraYear(week: Int) = Tuning.ERA_YEARS[(week - 1).coerceIn(0, Tuning.ERA_YEARS.lastIndex)]

        /** Clock hour at game time [t], see [hourOfDay]. */
        fun hourAt(t: Float) = (Tuning.DAWN_HOUR + 24f * (t % Tuning.DAY_SECONDS) / Tuning.DAY_SECONDS) % 24f

        private fun cellOf(p: Vec2) = Cell(p.x.toInt(), p.y.toInt())

        /** Largest grid a save may have; the scenarios stay far below. */
        private const val MAX_GRID = 256
        /** Most random draws a save may replay; a long game takes well under a million. */
        private const val MAX_DRAWS = 50_000_000L

        /** A saved node must be what its kind says and lie on the grid; see [restore]. */
        private fun validate(n: NodeSnapshot, grid: CellRect) {
            val ok = when (n.kind) {
                NodeKind.CLIENT -> n.device != null && n.service == null
                NodeKind.SERVER -> n.service != null && n.device == null
                else -> n.device == null && n.service == null
            }
            require(ok) { "node ${n.id} does not match its kind" }
            require(n.level in 1..Tuning.MAX_SERVER_LEVEL) { "node ${n.id} has level ${n.level}" }
            val dataCenter = n.kind == NodeKind.SERVER && n.level >= Tuning.DATA_CENTER_LEVEL
            require(n.footprint.size == (if (dataCenter) 4 else 1)) { "node ${n.id} has a bad footprint" }
            require(Cell(n.cellX, n.cellY) in n.footprint && n.footprint.all { it in grid }) { "node ${n.id} is off the grid" }
            if (n.kind == NodeKind.ACCESS_POINT) {
                require(n.channel in (if (n.fiveGhz) Wifi.CHANNELS_5_GHZ else Wifi.CHANNELS_2_4_GHZ)) { "node ${n.id} has a bad channel" }
            }
            require(n.overload in 0f..1f && n.tokens.isFinite() && n.requestTimer.isFinite() && n.dispatchCooldown.isFinite()) {
                "node ${n.id} has bad counters"
            }
        }

        /**
         * Rebuilds a world from [s]. The restored world continues exactly like the saved one would have, random draws
         * included. Throws [IllegalArgumentException] if the snapshot is inconsistent: anything the simulation or the
         * renderers would trip over later (a cell off the grid, a client without a device, a packet on a link that does
         * not exist …) is rejected here, so a damaged save never loads.
         */
        @OptIn(DebugApi::class)
        fun restore(s: WorldSnapshot): World {
            require(s.cols in 1..MAX_GRID && s.rows in 1..MAX_GRID) { "bad grid size" }
            require(s.water.size == s.rows && s.water.all { it.length == s.cols }) { "terrain does not match the grid" }
            val grid = CellRect(0, 0, s.cols, s.rows)
            val u = s.unlocked
            require(u.left >= 0 && u.top >= 0 && u.right <= s.cols && u.bottom <= s.rows && u.width >= 3 && u.height >= 3) { "bad unlocked block" }
            require(s.week >= 1 && s.time >= 0f && s.time.isFinite()) { "bad calendar" }
            require(s.nodes.all { it.id < s.nextId }) { "next id already used" }
            require(s.randomDraws in 0..MAX_DRAWS) { "bad random draw count" }
            val scenario = requireNotNull(Scenarios.byId(s.scenario)) { "unknown scenario ${s.scenario}" }
            val daily = s.dailyDay?.let(DailyChallenge::of)
            require(daily == null || (daily.scenario == scenario && daily.seed == s.seed && s.mode == GameMode.NORMAL)) { "daily challenge does not match" }
            val w = World(scenario, s.cols, s.rows, s.seed, spawnInitialNodes = false, mode = s.mode, daily = daily)
            for (y in 0 until s.rows) for (x in 0 until s.cols) {
                val t = TERRAIN_CHARS.indexOf(s.water[y][x])
                require(t >= 0) { "unknown terrain '${s.water[y][x]}'" }
                w.setTerrain(x, y, Terrain.entries[t])
            }
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
            w.rewardOffer = s.rewardOffer?.let { RewardOffer(it.week, it.choices).apply { bonusClaimed = it.bonusClaimed } }
            w.continued = s.continued
            require(s.bonusRoutersClaimed >= 0) { "bad bonus count" }
            // A save from before the count still knows a bonus taken on its open week screen.
            w.bonusRoutersClaimed = maxOf(s.bonusRoutersClaimed, if (s.rewardOffer?.bonusClaimed == true) 1 else 0)
            w.lastNews = s.lastNews
            w.lastNewsTime = s.lastNewsTime
            w.clientSpawnTimer = s.clientSpawnTimer
            val byId = HashMap<Int, Node>()
            for (n in s.nodes) {
                validate(n, grid)
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
                    cellGeneration = if (n.kind == NodeKind.CELL_TOWER) n.cellGeneration ?: CellGeneration.LEGACY else null
                }
                require(byId.put(n.id, node) == null) { "duplicate node id ${n.id}" }
                node.index = w.nodeList.size
                w.nodeList += node
                node.service?.let { if (it !in w.availableServices) w.availableServices = w.availableServices + it }
            }
            fun node(id: Int) = requireNotNull(byId[id]) { "unknown node $id" }
            for (c in s.cables) {
                require(c.waypoints.size >= 2 && c.waypoints.all { it in grid } && c.cost >= 0) { "bad cable" }
                w.cableList += Cable(node(c.a), node(c.b), c.type, c.cost, CableLayout(c.waypoints.map { it.center }), c.waterCells)
            }
            w.indexCables()
            w.incidentsEnabled = s.incidentsEnabled
            for (i in s.incidents) {
                val cable = if (i.cableA != null && i.cableB != null) {
                    requireNotNull(w.cableBetween(node(i.cableA), node(i.cableB))) { "incident at a missing cable" }
                } else null
                require((cable != null) == (i.kind == IncidentKind.EXCAVATOR) && (i.node != null) == (i.kind == IncidentKind.POWER_OUTAGE)) {
                    "incident without its target"
                }
                require(i.cutAt in 0f..1f) { "bad cut spot" }
                w.incidentList += Incident(i.kind, cable, i.node?.let(::node), i.cutAt, i.warning, i.remaining)
            }
            w.rebuildRadioLinks()
            for (p in s.packets) {
                require(p.route.size >= 2 && p.hop in 0 until p.route.size - 1) { "bad packet route" }
                require(p.progress == -1f || p.progress in 0f..1f) { "bad packet progress" }
                val route = p.route.map(::node)
                // Only the hop the packet is on must exist: cables further along (or already passed) may have been
                // removed since it set off, which the running game handles when the packet gets there (it goes back
                // into its client's queue), so such a save is valid and must load.
                require(w.linkBetween(route[p.hop], route[p.hop + 1]) != null) { "packet is on a link that does not exist" }
                val origin = node(p.origin)
                require(origin.kind == NodeKind.CLIENT && origin === (if (p.isResponse) route.last() else route.first())) { "bad packet origin" }
                w.packets += Packet(p.service, origin, route, p.isResponse).apply {
                    hop = p.hop
                    progress = p.progress
                }
            }
            w.recountLoads()
            w.failedNode = s.failedNodeId?.let(::node)
            return w
        }
    }
}

/** Block origins relative to the server cell for [World.dataCenterFootprint], in order of preference. */
private val DATA_CENTER_ORIGINS = listOf(0 to 0, -1 to 0, 0 to -1, -1 to -1)
