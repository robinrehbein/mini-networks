package com.mininetworks.game.game

/** The guided steps of the [Tutorial] in order, then [DONE]. */
enum class TutorialStep {
    /** Drag a cable from the PC to the mail server. */
    LAY_CABLE,

    /** Place a router and cable the phones and the telephony server to it. */
    PLACE_ROUTER,

    /**
     * 1998: a TV wants to stream from a new streaming server. Streaming needs bandwidth 3, ISDN carries only 2: a TV on
     * ISDN gets a "too narrow" badge, so the player picks DSL (capacity 4) and lays or upgrades the cable.
     */
    BANDWIDTH,

    /** 2010: the PC wants to play online, across the river; its short ping takes fiber. */
    PING,

    /** A new PC without a cable piles up requests and its overload ring fills: connect it. */
    OVERLOAD,

    /**
     * Two new PCs next to the first one, whose 2 ports are both in use by now (mail and gaming): a PC has only 2 ports,
     * so both are joined to the mail server through one router (6 ports).
     */
    PORTS,

    /** All steps done. */
    DONE,
}

/** What the tutorial points at right now; the UI draws a highlight there. */
sealed interface TutorialFocus {
    /** Drag a cable from [from] to [to]. */
    data class Drag(val from: Node, val to: Node) : TutorialFocus

    /** These nodes matter now. */
    data class Nodes(val nodes: List<Node>) : TutorialFocus

    /** Tap one of these cables. */
    data class Cables(val cables: List<Cable>) : TutorialFocus

    /** The HUD tile that places a router (tap it, or drag it onto the map). */
    data object RouterButton : TutorialFocus

    /** The HUD button that picks cable technology [type]. */
    data class CableButton(val type: CableType) : TutorialFocus

    data object None : TutorialFocus
}

/**
 * The six-step tutorial on the "Kleinstadt am Fluss" map (docs/PLAN.md P3.3, docs/TOP100.md B2): lay a cable, place a
 * router, give a streaming TV enough bandwidth, fix a ping that is too high for gaming, rescue a device whose overload
 * ring fills, and join two PCs to a server through one router because a PC has only 2 ports. Bandwidth and ping are taught by playing: the player may first build what does not work (ISDN
 * for the TV, DSL across the river), sees why on the map (a "too narrow" or "ping" badge) and then fixes it.
 *
 * The first packet is delivered within seconds of the first launch (B1): the PC already has a mail waiting, so it
 * leaves the moment the first cable is laid.
 *
 * It plays in its own [guided] world with a fixed seed: nothing spawns on its own, the calendar only moves when a step
 * needs a newer era, and the game cannot be lost. Each step sets its scene when it begins; [update], called after the
 * world advanced, checks the step's goal in the world and moves on when it is reached, so steps follow what the player
 * actually did. [focus] says what to highlight. Texts come from the UI's string resources, keyed by [step].
 */
class Tutorial private constructor(val world: World) {

    var step = TutorialStep.LAY_CABLE
        private set

    /** True once the player skipped the rest; [step] is then [TutorialStep.DONE] as well. */
    var skipped = false
        private set

    val finished get() = step == TutorialStep.DONE

    /** 1-based number of the current step, [STEPS] when done. */
    val number get() = minOf(step.ordinal + 1, STEPS)

    /** Step 1: the PC and the mail server it needs; in step 4 the same PC wants to play. */
    val pc: Node
    val mailServer: Node

    /** Step 2: two phones and the telephony server, to be joined through a router. */
    var phones: List<Node> = emptyList(); private set
    var callServer: Node? = null; private set

    /** Step 3: the TV that wants to stream, and the streaming server. */
    var tv: Node? = null; private set
    var streamServer: Node? = null; private set

    /** Step 4: the game server across the river. */
    var gameServer: Node? = null; private set

    /** Step 5: the new PC without a cable. */
    var newPc: Node? = null; private set

    /** Step 6: the two PCs to be joined to the mail server through one router. */
    var officePcs: List<Node> = emptyList(); private set

    /** Routers on the map when step 6 began; the ones after them were placed for it. */
    private var routersBefore = 0

    init {
        mailServer = addServer(Service.MAIL, 1, 1)
        pc = addClient(Device.PC, 4, 2)
        // A mail is already waiting, so the first cable delivers at once (docs/TOP100.md B1).
        pc.pending.addLast(Service.MAIL)
    }

    /**
     * Checks the current step's goal and, if it is reached, starts the next step. Returns true if the step changed.
     * Call once per frame after the world advanced.
     */
    fun update(): Boolean {
        if (finished || !goalReached()) return false
        enter(TutorialStep.entries[step.ordinal + 1])
        return true
    }

    /** Ends the tutorial early. */
    fun skip() {
        skipped = true
        step = TutorialStep.DONE
    }

    private fun goalReached(): Boolean = when (step) {
        TutorialStep.LAY_CABLE -> world.routeFor(pc, Service.MAIL) != null
        TutorialStep.PLACE_ROUTER -> world.nodes.any { it.kind == NodeKind.ROUTER && world.ports(it) >= 2 }
        TutorialStep.BANDWIDTH -> world.routeFor(tv!!, Service.STREAMING) != null
        TutorialStep.PING -> world.routeFor(pc, Service.GAMING) != null
        TutorialStep.OVERLOAD -> newPc!!.let { world.routeFor(it, Service.MAIL) != null && it.pending.size < World.Tuning.MAX_PENDING }
        TutorialStep.PORTS -> sharedRouter() != null && officePcs.all { world.routeFor(it, Service.MAIL) != null }
        TutorialStep.DONE -> false
    }

    private fun enter(next: TutorialStep) {
        step = next
        when (next) {
            TutorialStep.PLACE_ROUTER -> {
                callServer = addServer(Service.CALL, 1, 8)
                phones = listOf(addClient(Device.PHONE, 4, 6), addClient(Device.PHONE, 5, 8))
            }
            TutorialStep.BANDWIDTH -> {
                world.advanceEra(DSL_WEEK)
                streamServer = addServer(Service.STREAMING, 6, 0)
                tv = addClient(Device.TV, 6, 2)
            }
            TutorialStep.PING -> {
                world.advanceEra(FIBER_WEEK)
                gameServer = addServer(Service.GAMING, 15, 8)
            }
            TutorialStep.OVERLOAD -> newPc = addClient(Device.PC, 2, 4).also { pc ->
                repeat(World.Tuning.MAX_PENDING) { pc.pending.addLast(Service.MAIL) }
            }
            TutorialStep.PORTS -> {
                routersBefore = world.nodes.count { it.kind == NodeKind.ROUTER }
                officePcs = listOf(addClient(Device.PC, 6, 4), addClient(Device.PC, 7, 6)).onEach { it.pending.addLast(Service.MAIL) }
            }
            TutorialStep.LAY_CABLE, TutorialStep.DONE -> Unit
        }
    }

    /** What to highlight, given the cable technology the player has picked in the HUD. */
    fun focus(selected: CableType): TutorialFocus = when (step) {
        TutorialStep.LAY_CABLE -> TutorialFocus.Drag(pc, mailServer)
        TutorialStep.PLACE_ROUTER -> {
            val routers = world.nodes.filter { it.kind == NodeKind.ROUTER }
            if (routers.isEmpty()) TutorialFocus.RouterButton else TutorialFocus.Nodes(routers + phones + listOfNotNull(callServer))
        }
        TutorialStep.BANDWIDTH -> {
            val tv = tv!!
            when {
                world.routeProblem(tv, Service.STREAMING) != RouteProblem.TOO_NARROW -> TutorialFocus.Drag(tv, streamServer!!)
                selected.capacity < Service.STREAMING.bandwidth -> TutorialFocus.CableButton(CableType.DSL)
                else -> TutorialFocus.Cables(narrowCables())
            }
        }
        TutorialStep.PING -> {
            val route = world.bestRoute(pc, Service.GAMING)
            when {
                route == null -> TutorialFocus.Drag(pc, gameServer!!)
                selected != CableType.FIBER -> TutorialFocus.CableButton(CableType.FIBER)
                else -> TutorialFocus.Cables(route.nodes.zipWithNext { a, b -> world.cableBetween(a, b) }.filterNotNull().filter { it.type < CableType.FIBER })
            }
        }
        TutorialStep.OVERLOAD -> TutorialFocus.Drag(newPc!!, mailServer)
        TutorialStep.PORTS -> {
            // A router placed in this step (or one already cabled to a PC here) and what to join to it; before that
            // the router tile. The router from step 2 would do as well, but it stands far off by the phones.
            val routers = world.nodes.filter { it.kind == NodeKind.ROUTER }
            val here = routers.drop(routersBefore) + routers.filter { r -> officePcs.any { world.cableBetween(it, r) != null } }
            when {
                here.isNotEmpty() -> TutorialFocus.Nodes(here.distinct() + officePcs + mailServer)
                world.routersAvailable > 0 -> TutorialFocus.RouterButton
                else -> TutorialFocus.Nodes(routers + officePcs + mailServer)
            }
        }
        TutorialStep.DONE -> TutorialFocus.None
    }

    /** The nodes a router being placed should go next to: the phones in step 2, the two PCs in step 6. */
    fun placeNear(): List<Node> = if (step == TutorialStep.PORTS) officePcs else phones

    /** A router cabled to both PCs of step 6, or null. */
    fun sharedRouter(): Node? = world.nodes.firstOrNull { r ->
        r.kind == NodeKind.ROUTER && officePcs.isNotEmpty() && officePcs.all { world.cableBetween(it, r) != null }
    }

    /** True in step 3 while the TV is cabled but a link on the way is too narrow for streaming. */
    fun tooNarrow(): Boolean = step == TutorialStep.BANDWIDTH && world.routeProblem(tv!!, Service.STREAMING) == RouteProblem.TOO_NARROW

    /** Cables too narrow for streaming at the TV or the streaming server, the ones to upgrade in step 3. */
    private fun narrowCables(): List<Cable> =
        world.cables.filter { it.capacity < Service.STREAMING.bandwidth && (it.a === tv || it.b === tv || it.a === streamServer || it.b === streamServer) }

    /**
     * Round-trip ping of the PC's best route to the game server while it is too slow for gaming, in whole milliseconds;
     * null outside step 4, before there is a route at all and once the route is fast enough.
     */
    fun tooSlowPingMs(): Int? {
        if (step != TutorialStep.PING || world.routeFor(pc, Service.GAMING) != null) return null
        return world.bestRoute(pc, Service.GAMING)?.pingMs?.toInt()
    }

    /** Places a node near ([dx], [dy]) counted from the top-left cell of the playable block. */
    private fun addClient(device: Device, dx: Int, dy: Int): Node = spot(dx, dy).let { world.addClient(device, it.x, it.y) }

    private fun addServer(service: Service, dx: Int, dy: Int): Node = spot(dx, dy).let { world.addServer(service, it.x, it.y) }

    private fun spot(dx: Int, dy: Int): Cell =
        checkNotNull(world.nearestFree(Cell(world.unlocked.left + dx, world.unlocked.top + dy))) { "no room on the tutorial map" }

    companion object {
        /** Guided steps, not counting [TutorialStep.DONE]. */
        const val STEPS = 6
        /** The tutorial map is always the same. */
        const val SEED = 7L
        /** Enough for every step with room for a detour. */
        const val BUDGET = 100
        /** Weeks the tutorial jumps to: DSL (1998) for the bandwidth, fiber (2010) for the ping. */
        val DSL_WEEK = CableType.DSL.unlockWeek
        val FIBER_WEEK = CableType.FIBER.unlockWeek

        /** A fresh tutorial at its first step, on the river town map. */
        fun start(): Tutorial =
            Tutorial(World(Scenarios.RIVER_TOWN.copy(startBudget = BUDGET), seed = SEED, spawnInitialNodes = false, guided = true))
    }
}
