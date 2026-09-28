package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Re-routing a laid cable to other ends or the other bend ([World.reroute]), like re-drawing a line in Mini Metro. */
@OptIn(DebugApi::class)
class RerouteTest {

    private val dt = 1f / 60f

    /** Empty world without incidents; the river never reaches the left 6 columns. */
    private fun world() = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false).also { w ->
        for (y in 0 until w.rows) for (x in 0 until 6) assertFalse("test area must be dry", w.water[y][x])
        w.incidentsEnabled = false
        w.grant(200)
    }

    @Test
    fun movingOneEndPaysOnlyTheDifferenceAndKeepsTheCable() {
        val w = world()
        val pc = w.addClient(Device.PC, 1, 1)
        val near = w.addRouter(3, 1)
        val far = w.addRouter(1, 5)
        assertTrue(w.connect(pc, near, CableType.ISDN))
        val c = w.cableBetween(pc, near)!!
        val budget = w.budget
        assertEquals("2 cells to (3,1), 4 cells to (1,5)", 2, w.rerouteCost(c, pc, far))
        assertNull(w.rerouteError(c, pc, far))
        assertTrue(w.reroute(c, pc, far))
        assertEquals(budget - 2, w.budget)
        assertNull("the old way is gone", w.cableBetween(pc, near))
        val moved = w.cableBetween(pc, far)!!
        assertEquals(1, w.cables.size)
        assertEquals(CableType.ISDN, moved.type)
        assertEquals(4, moved.cost)
        assertEquals(0, w.ports(near))
        assertEquals(1, w.ports(pc))
        assertEquals("laid again now, for the laying animation", w.time, moved.builtAt)
    }

    @Test
    fun aShorterWayRefundsTheDifference() {
        val w = world()
        val pc = w.addClient(Device.PC, 1, 1)
        val far = w.addRouter(5, 4)
        val near = w.addRouter(2, 1)
        assertTrue(w.connect(pc, far, CableType.ISDN))
        val c = w.cableBetween(pc, far)!!
        val budget = w.budget
        assertEquals(-6, w.rerouteCost(c, pc, near))
        assertTrue(w.reroute(c, pc, near))
        assertEquals(budget + 6, w.budget)
        // Moving back pays the full price again: shuffling the cable around never makes money.
        assertTrue(w.reroute(w.cableBetween(pc, near)!!, pc, far))
        assertEquals(budget, w.budget)
    }

    @Test
    fun theOtherBendCostsOnlyTheTerrainDifference() {
        val w = world()
        val a = w.addRouter(1, 1)
        val b = w.addRouter(4, 4)
        w.setTerrain(4, 1, Terrain.MOUNTAIN)
        assertTrue(w.connect(a, b, CableType.ISDN, Bend.HORIZONTAL_FIRST))
        val c = w.cableBetween(a, b)!!
        assertEquals(6 + World.Tuning.MOUNTAIN_EXTRA_PER_CELL, c.cost)
        val budget = w.budget
        assertEquals(RerouteError.UNCHANGED, w.rerouteError(c, a, b, Bend.HORIZONTAL_FIRST))
        assertEquals(-World.Tuning.MOUNTAIN_EXTRA_PER_CELL, w.rerouteCost(c, a, b, Bend.VERTICAL_FIRST))
        assertTrue(w.reroute(c, a, b, Bend.VERTICAL_FIRST))
        assertEquals(budget + World.Tuning.MOUNTAIN_EXTRA_PER_CELL, w.budget)
        val moved = w.cableBetween(a, b)!!
        assertEquals(Cell(1, 4).center, moved.layout.waypoints[1])
        assertEquals(1, w.ports(a))
        assertEquals(1, w.ports(b))
        // Seen from the other end, the same way is the other bend: nothing changes.
        assertEquals(RerouteError.UNCHANGED, w.rerouteError(moved, b, a, Bend.HORIZONTAL_FIRST))
    }

    @Test
    fun aStraightCableHasNoOtherBend() {
        val w = world()
        val a = w.addRouter(1, 1)
        val b = w.addRouter(4, 1)
        assertTrue(w.connect(a, b, CableType.ISDN))
        assertEquals(RerouteError.UNCHANGED, w.rerouteError(w.cables.single(), a, b, Bend.VERTICAL_FIRST))
    }

    @Test
    fun errorsNameWhyNothingHappens() {
        val w = world()
        val pc = w.addClient(Device.PC, 1, 1)
        val a = w.addRouter(3, 1)
        val b = w.addRouter(3, 3)
        val phone = w.addClient(Device.PHONE, 5, 5)
        val other = w.addRouter(5, 3)
        assertTrue(w.connect(pc, a, CableType.ISDN))
        assertTrue(w.connect(pc, b, CableType.ISDN))
        val c = w.cableBetween(pc, a)!!
        assertEquals(RerouteError.SAME_NODE, w.rerouteError(c, pc, pc))
        assertEquals(RerouteError.ALREADY_CONNECTED, w.rerouteError(c, pc, b))
        // The phone's two ports are taken; the pc end that stays keeps its port although both of its ports are used.
        assertTrue(w.connect(phone, other, CableType.ISDN))
        assertTrue(w.connect(phone, b, CableType.ISDN))
        assertEquals(RerouteError.PORTS_FULL, w.rerouteError(c, pc, phone))
        assertNull("moving the far end keeps the pc's port", w.rerouteError(c, pc, other))
        drainTo(w, 3)
        assertEquals(RerouteError.NO_BUDGET, w.rerouteError(c, pc, other))
        assertFalse(w.reroute(c, pc, other))
        assertSame("nothing changed", c, w.cableBetween(pc, a))
        assertNull("a cheaper way needs no budget", w.rerouteError(w.cableBetween(pc, b)!!, pc, w.addRouter(1, 2)))
        w.removeCable(c)
        assertEquals(RerouteError.GONE, w.rerouteError(c, pc, other))
    }

    /** Spends budget down to [left] with cables dug up without refund (an excavator announced at them). */
    private fun drainTo(w: World, left: Int) {
        val x = w.addRouter(1, 8)
        val y = w.addRouter(2, 8)
        while (w.budget > left) {
            assertTrue(w.connect(x, y, CableType.ISDN))
            val c = w.cableBetween(x, y)!!
            w.announceExcavator(c)
            w.removeCable(c)
        }
    }

    @Test
    fun aCableWithAnExcavatorOrACutCannotMove() {
        val w = world()
        val a = w.addRouter(1, 1)
        val b = w.addRouter(4, 1)
        val c2 = w.addRouter(1, 4)
        assertTrue(w.connect(a, b, CableType.ISDN))
        val c = w.cables.single()
        w.announceExcavator(c)
        assertEquals("announced", RerouteError.INCIDENT, w.rerouteError(c, a, c2))
        w.incidentsEnabled = true
        var guard = 0
        while (!w.isCut(c) && guard++ < 60 * 30) w.update(dt)
        assertTrue(w.isCut(c))
        val budget = w.budget
        assertEquals("cut", RerouteError.INCIDENT, w.rerouteError(c, a, c2))
        assertFalse(w.reroute(c, a, c2))
        assertEquals(budget, w.budget)
        assertTrue(w.repair(c))
        assertNull("repaired, it may move again", w.rerouteError(c, a, c2))
    }

    @Test
    fun aRerouteKeepsTechnologyAndCountsNothingNew() {
        val w = world()
        w.jumpToWeek(CableType.FIBER.unlockWeek)
        val pc = w.addClient(Device.PC, 1, 1)
        val a = w.addRouter(3, 1)
        val b = w.addRouter(1, 4)
        assertTrue(w.connect(pc, a, CableType.DSL))
        assertTrue(w.upgrade(w.cables.single(), CableType.FIBER))
        val upgradedAt = w.cables.single().upgradedAt
        val c = w.counters
        val laid = c.cablesLaid
        val fiber = c.fiberLaid
        val upgrades = c.cableUpgrades
        repeat(20) {
            assertTrue(w.reroute(w.cables.single(), pc, if (it % 2 == 0) b else a))
        }
        assertEquals(laid, c.cablesLaid)
        assertEquals(fiber, c.fiberLaid)
        assertEquals(upgrades, c.cableUpgrades)
        val moved = w.cables.single()
        assertEquals(CableType.FIBER, moved.type)
        assertEquals(upgradedAt, moved.upgradedAt)
        // Removing the moved cable for a full refund still hands its counts back as credit.
        w.removeCable(moved)
        assertTrue(w.connect(pc, a, CableType.FIBER))
        assertEquals(laid, c.cablesLaid)
        assertEquals(fiber, c.fiberLaid)
    }

    @Test
    fun aMovedCableKeepsItsPlaceInTheDrawingOrder() {
        val w = world()
        val a = w.addRouter(1, 1)
        val b = w.addRouter(3, 1)
        val c = w.addRouter(5, 1)
        val d = w.addRouter(1, 3)
        assertTrue(w.connect(a, b, CableType.ISDN))
        assertTrue(w.connect(b, c, CableType.ISDN))
        assertTrue(w.reroute(w.cableBetween(a, b)!!, d, b))
        assertEquals(listOf(w.cableBetween(d, b), w.cableBetween(b, c)), w.cables)
    }

    @Test
    fun routesFollowTheNewWay() {
        val w = world()
        val pc = w.addClient(Device.PC, 1, 1)
        val router = w.addRouter(3, 1)
        val mail = w.addServer(Service.MAIL, 3, 4)
        val spare = w.addRouter(1, 4)
        assertTrue(w.connect(pc, router, CableType.ISDN))
        assertTrue(w.connect(router, mail, CableType.ISDN))
        assertNotNull(w.routeFor(pc, Service.MAIL))
        assertTrue(w.reroute(w.cableBetween(pc, router)!!, pc, spare))
        assertNull("the pc hangs off a router without a way on", w.routeFor(pc, Service.MAIL))
        assertTrue(w.reroute(w.cableBetween(pc, spare)!!, pc, mail))
        assertEquals(listOf(pc, mail), w.routeFor(pc, Service.MAIL)!!.nodes)
    }

    @Test
    fun packetsOnAMovedEndGoBackToTheQueueAndABendChangeKeepsThemGoing() {
        val w = world()
        val pc = w.addClient(Device.PC, 1, 1)
        val mail = w.addServer(Service.MAIL, 4, 4)
        val other = w.addServer(Service.MAIL, 1, 5)
        assertTrue(w.connect(pc, mail, CableType.ISDN, Bend.HORIZONTAL_FIRST))
        pc.pending.addLast(Service.MAIL)
        var guard = 0
        while (w.packets.none { it.progress > 0f } && guard++ < 600) w.update(dt)
        val p = w.packets.single()
        val progress = p.progress
        // Only the bend: the link between the same nodes still exists, the packet keeps its progress.
        assertTrue(w.reroute(w.cables.single(), pc, mail, Bend.VERTICAL_FIRST))
        assertTrue(p in w.packets)
        assertEquals(progress, p.progress, 0f)
        assertTrue(pc.pending.isEmpty())
        w.update(dt)
        assertTrue("it moves on along the new way", p.progress > progress)
        // A new end: the packet's link is gone, so it goes back into the pc's queue, as on a removed cable.
        assertTrue(w.reroute(w.cables.single(), pc, other))
        assertFalse(p in w.packets)
        assertEquals(listOf(Service.MAIL), pc.pending.toList())
        guard = 0
        while (w.packets.isEmpty() && guard++ < 600) w.update(dt)
        assertEquals("sent again over the new way", listOf(pc, other), w.packets.single().route)
    }

    @Test
    fun creativeModeNeitherPaysNorRefunds() {
        val w = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false, mode = GameMode.CREATIVE)
        w.incidentsEnabled = false
        val pc = w.addClient(Device.PC, 1, 1)
        val a = w.addRouter(5, 5)
        val b = w.addRouter(2, 1)
        assertTrue(w.connect(pc, a, CableType.ISDN))
        val budget = w.budget
        assertTrue(w.reroute(w.cables.single(), pc, b))
        assertEquals(budget, w.budget)
    }

    @Test
    fun aMovedCableSurvivesSaveAndLoad() {
        val w = world()
        val a = w.addRouter(1, 1)
        val b = w.addRouter(4, 4)
        val c = w.addRouter(1, 5)
        assertTrue(w.connect(a, b, CableType.ISDN, Bend.HORIZONTAL_FIRST))
        assertTrue(w.reroute(w.cables.single(), c, b, Bend.VERTICAL_FIRST))
        val loaded = World.restore(w.snapshot())
        val moved = loaded.cables.single()
        assertEquals(w.cables.single().layout.waypoints, moved.layout.waypoints)
        assertEquals(w.cables.single().cost, moved.cost)
        assertEquals(listOf(c.id, b.id), listOf(moved.a.id, moved.b.id))
        assertNotSame(moved, w.cables.single())
    }
}
