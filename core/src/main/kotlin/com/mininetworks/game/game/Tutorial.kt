package com.mininetworks.game.game

/** The guided steps of the [Tutorial] in order, then [DONE]. */
enum class TutorialStep {
    /** Drag a cable from the PC to the mail server. */
    LAY_CABLE,

    /** Place a router and cable the phones and the telephony server to it. */
    PLACE_ROUTER,

    /** 1998, DSL is invented: pick it and upgrade a cable. */
    CABLE_TYPE,

    /** 2007: the PC wants to play online, across the river; its short ping takes fiber. */
    PING,

    /** A new PC without a cable piles up requests and its overload ring fills: connect it. */
    OVERLOAD,

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

    /** The HUD button that arms placing a router. */
    data object RouterButton : TutorialFocus

    /** The HUD button that picks cable technology [type]. */
    data class CableButton(val type: CableType) : TutorialFocus

    data object None : TutorialFocus
}

/**
 * The five-step tutorial on the "Kleinstadt am Fluss" map (docs/PLAN.md P3.3): lay a cable, place a router, upgrade to
 * a better cable type, fix a ping that is too high for gaming, and rescue a device whose overload ring fills.
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

    /** Step 4: the game server across the river. */
    var gameServer: Node? = null; private set

    /** Step 5: the new PC without a cable. */
    var newPc: Node? = null; private set

    init {
        mailServer = addServer(Service.MAIL, 1, 1)
        pc = addClient(Device.PC, 5, 3)
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
        TutorialStep.CABLE_TYPE -> world.cables.any { it.type > CableType.ISDN }
        TutorialStep.PING -> world.routeFor(pc, Service.GAMING) != null
        TutorialStep.OVERLOAD -> newPc!!.let { world.routeFor(it, Service.MAIL) != null && it.pending.size < World.Tuning.MAX_PENDING }
        TutorialStep.DONE -> false
    }

    private fun enter(next: TutorialStep) {
        step = next
        when (next) {
            TutorialStep.PLACE_ROUTER -> {
                callServer = addServer(Service.CALL, 1, 8)
                phones = listOf(addClient(Device.PHONE, 4, 6), addClient(Device.PHONE, 5, 8))
            }
            TutorialStep.CABLE_TYPE -> world.advanceEra(DSL_WEEK)
            TutorialStep.PING -> {
                world.advanceEra(FIBER_WEEK)
                gameServer = addServer(Service.GAMING, 12, 4)
            }
            TutorialStep.OVERLOAD -> newPc = addClient(Device.PC, 2, 4).also { pc ->
                repeat(World.Tuning.MAX_PENDING) { pc.pending.addLast(Service.MAIL) }
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
        TutorialStep.CABLE_TYPE ->
            if (selected == CableType.ISDN) TutorialFocus.CableButton(CableType.DSL)
            else TutorialFocus.Cables(world.cables.filter { it.type < selected })
        TutorialStep.PING -> {
            val route = world.bestRoute(pc, Service.GAMING)
            when {
                route == null -> TutorialFocus.Drag(pc, gameServer!!)
                selected != CableType.FIBER -> TutorialFocus.CableButton(CableType.FIBER)
                else -> TutorialFocus.Cables(route.nodes.zipWithNext { a, b -> world.cableBetween(a, b) }.filterNotNull().filter { it.type < CableType.FIBER })
            }
        }
        TutorialStep.OVERLOAD -> TutorialFocus.Drag(newPc!!, mailServer)
        TutorialStep.DONE -> TutorialFocus.None
    }

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
        const val STEPS = 5
        /** The tutorial map is always the same. */
        const val SEED = 7L
        /** Enough for every step with room for a detour. */
        const val BUDGET = 80
        /** Weeks the tutorial jumps to: DSL (1998) for the cable types, fiber (2007) for the ping. */
        const val DSL_WEEK = 2
        const val FIBER_WEEK = 5

        /** A fresh tutorial at its first step, on the river town map. */
        fun start(): Tutorial =
            Tutorial(World(Scenarios.RIVER_TOWN.copy(startBudget = BUDGET), seed = SEED, spawnInitialNodes = false, guided = true))
    }
}
