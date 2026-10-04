package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(DebugApi::class)
class IncidentsTest {

    private val dt = 1f / 60f

    private fun dryWorld(seed: Long): World {
        val w = World(cols = 24, rows = 12, seed = seed, spawnInitialNodes = false)
        for (row in w.water) row.fill(false)
        w.grant(500)
        return w
    }

    /** One step; a week change's reward choice is taken at once, so the clock keeps running. */
    private fun step(w: World) {
        w.rewardOffer?.let { w.chooseReward(0) }
        w.update(dt)
    }

    private fun run(w: World, seconds: Float) = repeat((seconds / dt).toInt()) { step(w) }

    /** Runs until [done] holds, at most [limit] seconds. */
    private fun runUntil(w: World, limit: Float = World.Tuning.WEEK_SECONDS, done: () -> Boolean) {
        var t = 0f
        while (!done()) {
            check(t < limit) { "condition not reached within $limit s" }
            step(w)
            t += dt
        }
    }

    /** A seed whose first incident in [week] prefers [kind]. */
    private fun seedFor(kind: IncidentKind, week: Int = Incidents.FIRST_WEEK) =
        (1L..1000L).first { Incidents.plan(it, week).first().kind == kind }

    // ---------------------------------------------------------------- plan

    @Test
    fun noneInTheFirstThreeWeeksThenMoreAndMore() {
        assertEquals(0, Incidents.countIn(1))
        assertEquals(0, Incidents.countIn(2))
        assertEquals("week 3 already brings TV cable, consoles and gaming (docs/BALANCING.md, T-Human)", 0, Incidents.countIn(3))
        assertEquals(4, Incidents.FIRST_WEEK)
        assertEquals(1, Incidents.countIn(4))
        val counts = (3..30).map(Incidents::countIn)
        assertEquals("never fewer in a later week", counts.sorted(), counts)
        assertTrue(Incidents.countIn(12) > Incidents.countIn(Incidents.FIRST_WEEK))
        assertEquals(Incidents.MAX_PER_WEEK, Incidents.countIn(100))
        assertEquals("rare: one a week for the first weeks", 1, Incidents.countIn(Incidents.FIRST_WEEK + Incidents.WEEKS_PER_STEP - 1))
        assertTrue("rare: never more than two a week", Incidents.MAX_PER_WEEK <= 2)
        for (seed in 1L..20L) {
            assertTrue(Incidents.plan(seed, 1).isEmpty())
            assertTrue(Incidents.plan(seed, 2).isEmpty())
            assertTrue(Incidents.plan(seed, 3).isEmpty())
        }
    }

    @Test
    fun planIsDeterministicFromSeedAndWeek() {
        for (week in Incidents.FIRST_WEEK..15) {
            assertEquals(Incidents.plan(42L, week), Incidents.plan(42L, week))
            assertEquals(Incidents.countIn(week), Incidents.plan(42L, week).size)
        }
        assertNotEquals(Incidents.plan(1L, 6), Incidents.plan(2L, 6))
        val kinds = (1L..50L).map { Incidents.plan(it, Incidents.FIRST_WEEK).first().kind }.toSet()
        assertEquals("both kinds occur", IncidentKind.entries.toSet(), kinds)
    }

    @Test
    fun announcementsStayInsideTheWeekAndNeverOverlap() {
        for (seed in 1L..30L) for (week in Incidents.FIRST_WEEK..20) {
            val plan = Incidents.plan(seed, week)
            for (p in plan) {
                assertTrue(p.at >= Incidents.START_MARGIN)
                assertTrue("strikes before the week ends", p.at + Incidents.WARNING_SECONDS <= World.Tuning.WEEK_SECONDS - Incidents.END_MARGIN + 1e-3f)
            }
            for ((a, b) in plan.zipWithNext()) assertTrue("the last one struck before the next is announced", b.at - a.at >= Incidents.WARNING_SECONDS - 1e-3f)
        }
    }

    @Test
    fun noIncidentBeforeTheFirstIncidentWeekOfAGame() {
        val w = World(seed = 5L)
        w.grant(500, extraRouters = 4)
        val clients = w.nodes.filter { it.kind == NodeKind.CLIENT }
        val server = w.nodes.first { it.kind == NodeKind.SERVER }
        val free = (w.unlocked.left until w.unlocked.right).flatMap { x -> (w.unlocked.top until w.unlocked.bottom).map { Cell(x, it) } }
            .first { w.isFree(it.x, it.y) }
        val router = w.addRouter(free.x, free.y)
        assertTrue(w.connect(router, server, CableType.ISDN))
        clients.forEach { w.connect(it, router, CableType.ISDN) }
        while (w.week < Incidents.FIRST_WEEK) {
            w.rewardOffer?.let { w.chooseReward(0) }
            w.update(dt)
            if (w.week < Incidents.FIRST_WEEK) assertTrue(w.incidents.isEmpty())
            w.nodes.forEach { it.pending.clear() }
        }
    }

    @Test
    fun incidentIsAnnouncedAtThePlannedTime() {
        val w = dryWorld(seedFor(IncidentKind.EXCAVATOR))
        w.jumpToWeek(Incidents.FIRST_WEEK)
        val pc = w.addClient(Device.PC, 2, 2)
        val mail = w.addServer(Service.MAIL, 8, 2)
        assertTrue(w.connect(pc, mail, CableType.DSL))
        val at = (Incidents.FIRST_WEEK - 1) * World.Tuning.WEEK_SECONDS + Incidents.plan(w.seed, Incidents.FIRST_WEEK).first().at
        runUntil(w) { w.incidents.isNotEmpty() }
        assertEquals(at, w.time, dt * 1.01f)
    }

    // ---------------------------------------------------------------- excavator

    /** PC and mail server with a single cable; the excavator is the only possible incident. */
    private fun cabledPair(seed: Long): Triple<World, Node, Cable> {
        val w = dryWorld(seed)
        w.jumpToWeek(Incidents.FIRST_WEEK)
        val pc = w.addClient(Device.PC, 2, 2)
        val mail = w.addServer(Service.MAIL, 8, 2)
        assertTrue(w.connect(pc, mail, CableType.DSL))
        return Triple(w, pc, w.cableBetween(pc, mail)!!)
    }

    @Test
    fun excavatorWarnsThenCutsTheCable() {
        val (w, pc, cable) = cabledPair(seedFor(IncidentKind.POWER_OUTAGE))
        runUntil(w) { w.incidents.isNotEmpty() }
        val incident = w.incidents.single()
        assertEquals("no router: the excavator takes over", IncidentKind.EXCAVATOR, incident.kind)
        assertSame(cable, incident.cable)
        assertFalse(incident.struck)
        assertTrue(incident.cutAt > 0f && incident.cutAt < 1f)
        val spot = incident.spot
        assertEquals("the excavator digs on a cell between the ends", 0f, spot.x - spot.x.toInt() - 0.5f, 1e-4f)
        assertNull(w.nodeAt(Cell(spot.x.toInt(), spot.y.toInt())))

        run(w, Incidents.WARNING_SECONDS - 0.2f)
        assertFalse("still only announced", w.isCut(cable))
        assertNotNull(w.routeFor(pc, Service.MAIL))

        pc.pending.addLast(Service.MAIL)
        runUntil(w, 1f) { incident.struck }
        assertTrue(w.isCut(cable))
        assertFalse(w.isUp(cable))
        assertNull(w.routeFor(pc, Service.MAIL))
        assertTrue("nothing travels on a cut cable", w.packets.none { it.route.contains(pc) })
        assertTrue(Service.MAIL in pc.pending)
        assertTrue("the cable stays in place", cable in w.cables)
    }

    @Test
    fun cutCableRepairsItselfAfterTwentySeconds() {
        val (w, pc, cable) = cabledPair(seedFor(IncidentKind.EXCAVATOR))
        runUntil(w) { w.incidents.any { it.struck } }
        run(w, Incidents.CUT_SECONDS - 0.5f)
        assertTrue(w.isCut(cable))
        run(w, 0.6f)
        assertFalse(w.isCut(cable))
        assertTrue(w.incidents.isEmpty())
        assertNotNull(w.routeFor(pc, Service.MAIL))
    }

    @Test
    fun tappingACutCableRepairsItForASmallFee() {
        val (w, pc, cable) = cabledPair(seedFor(IncidentKind.EXCAVATOR))
        runUntil(w) { w.incidents.isNotEmpty() }
        assertEquals("nothing to repair during the warning", RepairError.NOT_CUT, w.repairError(cable))
        assertFalse(w.repair(cable))
        runUntil(w, Incidents.WARNING_SECONDS + 1f) { w.isCut(cable) }
        val budget = w.budget
        assertNull(w.repairError(cable))
        assertTrue(w.repair(cable))
        assertEquals(budget - Incidents.REPAIR_COST, w.budget)
        assertFalse(w.isCut(cable))
        assertTrue(w.incidents.isEmpty())
        assertNotNull(w.routeFor(pc, Service.MAIL))
        pc.pending.addLast(Service.MAIL)
        run(w, 3f)
        assertTrue("traffic flows again", w.delivered > 0)
    }

    @Test
    fun repairNeedsBudget() {
        val (w, _, cable) = cabledPair(seedFor(IncidentKind.EXCAVATOR))
        runUntil(w) { w.incidents.any { it.struck } }
        w.grant(Incidents.REPAIR_COST - 1 - w.budget)
        assertEquals(RepairError.NO_BUDGET, w.repairError(cable))
        assertFalse(w.repair(cable))
        assertTrue(w.isCut(cable))
    }

    @Test
    fun removingTheCableCallsTheExcavatorOff() {
        val (w, _, cable) = cabledPair(seedFor(IncidentKind.EXCAVATOR))
        runUntil(w) { w.incidents.isNotEmpty() }
        w.removeCable(cable)
        assertTrue(w.incidents.isEmpty())
    }

    @Test
    fun dodgingTheExcavatorByRelayingTheCableCostsBudget() {
        val (w, _, cable) = cabledPair(seedFor(IncidentKind.EXCAVATOR))
        assertEquals(cable.cost, w.refundOf(cable))
        runUntil(w) { w.incidents.isNotEmpty() }
        assertFalse(w.incidents.single().struck)
        assertEquals(0, w.refundOf(cable))
        val before = w.budget
        w.removeCable(cable)
        assertEquals("no refund under an announced excavator", before, w.budget)
        assertTrue(w.connect(cable.a, cable.b, cable.type))
        assertEquals(before - cable.cost, w.budget)
        assertTrue(cable.cost > 0)
    }

    @Test
    fun removingACutCableRefundsNothing() {
        val (w, _, cable) = cabledPair(seedFor(IncidentKind.EXCAVATOR))
        runUntil(w) { w.incidents.any { it.struck } }
        val before = w.budget
        w.removeCable(cable)
        assertEquals(before, w.budget)
    }

    @Test
    fun removingAnUntouchedCableRefundsItsCost() {
        val (w, _, cable) = cabledPair(seedFor(IncidentKind.EXCAVATOR))
        val before = w.budget
        w.removeCable(cable)
        assertEquals(before + cable.cost, w.budget)
    }

    @Test
    fun trafficTakesASecondCableAroundTheCut() {
        val w = dryWorld(seedFor(IncidentKind.EXCAVATOR))
        w.jumpToWeek(Incidents.FIRST_WEEK)
        val pc = w.addClient(Device.PC, 2, 2)
        val mail = w.addServer(Service.MAIL, 8, 2)
        val router = w.addRouter(5, 6)
        assertTrue(w.connect(pc, mail, CableType.DSL))
        runUntil(w) { w.incidents.isNotEmpty() }
        val cut = w.incidents.single().cable!!
        // The warning gives time to lay a detour; the router is new, so the excavator stays on its cable.
        assertTrue(w.connect(pc, router, CableType.DSL))
        assertTrue(w.connect(router, mail, CableType.DSL))
        runUntil(w, Incidents.WARNING_SECONDS + 1f) { w.isCut(cut) }
        val route = w.routeFor(pc, Service.MAIL)
        assertNotNull(route)
        assertTrue(router in route!!.nodes)
    }

    // ---------------------------------------------------------------- power outage

    /** PC - router - mail server. */
    private fun routedPair(seed: Long): Triple<World, Node, Node> {
        val w = dryWorld(seed)
        w.jumpToWeek(Incidents.FIRST_WEEK)
        val pc = w.addClient(Device.PC, 2, 2)
        val mail = w.addServer(Service.MAIL, 8, 2)
        val router = w.addRouter(5, 2)
        assertTrue(w.connect(pc, router, CableType.DSL))
        assertTrue(w.connect(router, mail, CableType.DSL))
        return Triple(w, pc, router)
    }

    @Test
    fun powerOutageSwitchesARouterOffForTenSeconds() {
        val (w, pc, router) = routedPair(seedFor(IncidentKind.POWER_OUTAGE))
        runUntil(w) { w.incidents.isNotEmpty() }
        val incident = w.incidents.single()
        assertEquals(IncidentKind.POWER_OUTAGE, incident.kind)
        assertSame(router, incident.node)
        assertEquals(router.center, incident.spot)
        run(w, Incidents.WARNING_SECONDS - 0.2f)
        assertFalse(w.isDark(router))
        assertNotNull(w.routeFor(pc, Service.MAIL))

        repeat(3) { pc.pending.addLast(Service.MAIL) }
        runUntil(w, 1f) { incident.struck }
        assertTrue(w.isDark(router))
        assertNull(w.routeFor(pc, Service.MAIL))
        assertTrue("nothing waits at or travels through a dark router", w.packets.none { router in it.route })
        assertTrue(w.cables.none { w.isUp(it) })

        run(w, Incidents.OUTAGE_SECONDS - 0.5f)
        assertTrue(w.isDark(router))
        run(w, 0.6f)
        assertFalse(w.isDark(router))
        assertTrue(w.incidents.isEmpty())
        assertNotNull(w.routeFor(pc, Service.MAIL))
    }

    @Test
    fun darkAccessPointLinksNobodyAndStopsInterfering() {
        val w = dryWorld(seedFor(IncidentKind.POWER_OUTAGE, week = 6))
        w.jumpToWeek(6)
        val mail = w.addServer(Service.MAIL, 10, 2)
        val ap = w.addRadio(RadioType.WLAN, 5, 5)
        val neighbour = w.addRadio(RadioType.WLAN, 7, 5)
        assertTrue(w.connect(ap, mail, CableType.DSL))
        assertTrue(w.connect(neighbour, mail, CableType.DSL))
        w.addClient(Device.LAPTOP, 5, 6)
        assertEquals(listOf(neighbour), w.interferers(ap))
        assertTrue(w.radioLinks.isNotEmpty())
        runUntil(w) { w.incidents.any { it.struck } }
        val dark = w.incidents.first { it.struck }.node!!
        assertTrue(w.isDark(dark))
        assertTrue(w.radioLinks.none { it.radio === dark })
        val other = if (dark === ap) neighbour else ap
        assertTrue("no interference from a dark access point", w.interferers(other).isEmpty())
        assertTrue(w.interferers(dark).isEmpty())
    }

    @Test
    fun outageOnlyHitsCabledRoutersAndAccessPoints() {
        val w = dryWorld(seedFor(IncidentKind.POWER_OUTAGE))
        w.jumpToWeek(Incidents.FIRST_WEEK)
        w.addRouter(12, 8)
        val pc = w.addClient(Device.PC, 2, 2)
        val mail = w.addServer(Service.MAIL, 8, 2)
        assertTrue(w.connect(pc, mail, CableType.DSL))
        runUntil(w) { w.incidents.isNotEmpty() }
        assertEquals("the lone router is skipped, the cable gets the excavator", IncidentKind.EXCAVATOR, w.incidents.single().kind)
    }

    @Test
    fun nothingHappensWithoutATarget() {
        val w = dryWorld(seedFor(IncidentKind.EXCAVATOR))
        w.jumpToWeek(Incidents.FIRST_WEEK)
        w.addClient(Device.PC, 2, 2)
        w.addServer(Service.MAIL, 8, 2)
        run(w, World.Tuning.WEEK_SECONDS - 1f)
        assertTrue(w.incidents.isEmpty())
    }

    @Test
    fun laterWeeksBringMoreIncidents() {
        fun announced(week: Int): Int {
            val w = dryWorld(11L)
            w.jumpToWeek(week)
            val mail = w.addServer(Service.MAIL, 12, 6)
            var last = mail
            for (i in 0 until 6) {
                val r = w.addRouter(3 + i * 3, 2)
                assertTrue(w.connect(r, last, CableType.DSL))
                last = r
            }
            var count = 0
            var seen = emptySet<Incident>()
            repeat((World.Tuning.WEEK_SECONDS / dt).toInt() - 2) {
                w.update(dt)
                count += (w.incidents.toSet() - seen).size
                seen = w.incidents.toSet()
            }
            return count
        }
        assertEquals(Incidents.countIn(Incidents.FIRST_WEEK), announced(Incidents.FIRST_WEEK))
        assertEquals(Incidents.countIn(12), announced(12))
        assertTrue(announced(12) > announced(Incidents.FIRST_WEEK))
    }

    // ---------------------------------------------------------------- determinism and saving

    @Test
    fun sameSeedSameIncidents() {
        fun trace(): List<String> {
            val (w, _, _) = routedPair(seedFor(IncidentKind.POWER_OUTAGE))
            w.addRouter(12, 8).also { assertTrue(w.connect(it, w.nodes.first { n -> n.kind == NodeKind.SERVER }, CableType.DSL)) }
            val log = ArrayList<String>()
            repeat((World.Tuning.WEEK_SECONDS / dt).toInt() - 2) {
                w.update(dt)
                w.incidents.forEach { log += "${it.kind} ${it.node?.id} ${it.cable?.a?.id} ${it.cutAt} ${it.struck}" }
            }
            return log.distinct()
        }
        val a = trace()
        assertTrue(a.isNotEmpty())
        assertEquals(a, trace())
    }

    @Test
    fun savedGameKeepsAnnouncedAndActiveIncidents() {
        val (w, pc, _) = routedPair(seedFor(IncidentKind.EXCAVATOR))
        repeat(4) { pc.pending.addLast(Service.MAIL) }
        runUntil(w) { w.incidents.isNotEmpty() }
        run(w, 1f)
        val announced = Save.decode(Save.encode(w))!!
        assertEquals(1, announced.incidents.size)
        assertFalse(announced.incidents.single().struck)
        run(w, Incidents.WARNING_SECONDS)
        val active = Save.decode(Save.encode(w))!!
        val cut = active.incidents.single()
        assertTrue(cut.struck)
        assertTrue(active.isCut(cut.cable!!))
        assertSame(active.cableBetween(cut.cable.a, cut.cable.b), cut.cable)

        run(announced, Incidents.WARNING_SECONDS)
        assertEquals(w.snapshot(), announced.snapshot())
        for (world in listOf(w, active, announced)) run(world, 30f)
        assertEquals(w.snapshot(), active.snapshot())
        assertEquals(w.snapshot(), announced.snapshot())
    }

    @Test
    fun oldSavesWithoutIncidentsStillLoad() {
        val w = dryWorld(3L)
        val text = Save.encode(w).replace(Regex(",\"incidentsEnabled\":true,\"incidents\":\\[]"), "")
        assertFalse(text.contains("incidents"))
        val restored = Save.decode(text)!!
        assertTrue(restored.incidents.isEmpty())
        assertTrue(restored.incidentsEnabled)
    }

    @Test
    fun debugHooksAnnounceAtOnce() {
        val (w, _, router) = routedPair(1L)
        w.jumpToWeek(1)
        val outage = w.announcePowerOutage(router)
        val cable = w.cables.first()
        val cut = w.announceExcavator(cable)
        assertEquals(listOf(outage, cut), w.incidents)
        assertEquals("first dry cell between the ends", 1f / cable.layout.steps, cut.cutAt, 1e-4f)
        run(w, Incidents.WARNING_SECONDS + 0.1f)
        assertTrue(w.isDark(router))
        assertTrue(w.isCut(cable))
    }
}
