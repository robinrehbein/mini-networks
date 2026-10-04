package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two silent failures that get a badge: a jam on a route that exists, and a device linked to no server it needs. */
@OptIn(DebugApi::class)
class JamBadgeTest {

    private fun world() = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false).also { w ->
        for (row in w.water) row.fill(false)
        w.incidentsEnabled = false
        w.jumpToWeek(CableType.FIBER.unlockWeek)
        w.grant(500)
    }

    /** Runs [seconds] in small steps, topping [client] up to [backlog] waiting requests of [s] before each. */
    private fun run(w: World, client: Node, s: Service, seconds: Float, backlog: Int) {
        var t = 0f
        while (t < seconds) {
            while (client.pending.size < backlog) client.pending.addLast(s)
            w.update(STEP)
            t += STEP
        }
    }

    @Test
    fun fullCableJamsTheDeviceAfterAWhile() {
        val w = world()
        val pc = w.addClient(Device.PC, 1, 1)
        val mail = w.addServer(Service.MAIL, 5, 1)
        w.connect(pc, mail, CableType.ISDN)
        val cable = w.cableBetween(pc, mail)!!
        run(w, pc, Service.MAIL, 0.5f, backlog = 5)
        assertFalse("a moment of full load is no jam yet", w.isJammed(pc))
        run(w, pc, Service.MAIL, World.Tuning.JAM_SECONDS * 2, backlog = 5)
        assertNull("the route is there", w.routeProblem(pc, Service.MAIL))
        assertSame(cable, w.jamLink(pc))
        assertTrue(w.isJammed(pc))
        assertTrue(w.isJammed(cable))
        assertEquals(listOf(cable), w.jammedCables())
    }

    @Test
    fun jamClearsOnceTheCableKeepsUp() {
        val w = world()
        val pc = w.addClient(Device.PC, 1, 1)
        val mail = w.addServer(Service.MAIL, 5, 1)
        w.connect(pc, mail, CableType.ISDN)
        run(w, pc, Service.MAIL, World.Tuning.JAM_SECONDS * 2, backlog = 5)
        assertTrue(w.isJammed(pc))
        pc.pending.clear()
        run(w, pc, Service.MAIL, 3f, backlog = 0)
        assertFalse(w.isJammed(pc))
        assertNull(w.jamLink(pc))
        assertTrue(w.jammedCables().isEmpty())
    }

    @Test
    fun aShownJamHoldsThroughABriefLetUp() {
        val w = world()
        val pc = w.addClient(Device.PC, 1, 1)
        val mail = w.addServer(Service.MAIL, 5, 1)
        w.connect(pc, mail, CableType.ISDN)
        val cable = w.cableBetween(pc, mail)!!
        run(w, pc, Service.MAIL, World.Tuning.JAM_SECONDS * 2, backlog = 5)
        assertTrue(w.isJammed(pc))
        // The queue dips under the jam threshold for a moment, as single requests get through: no blink.
        while (pc.pending.size >= World.Tuning.JAM_PENDING) pc.pending.removeLast()
        w.update(STEP)
        assertTrue("the badge holds", w.isJammed(pc))
        assertTrue("the halo holds", w.isJammed(cable))
        run(w, pc, Service.MAIL, STEP * 2, backlog = 5)
        assertTrue(w.isJammed(pc))
    }

    @Test
    fun lightTrafficNeverJams() {
        val w = world()
        val pc = w.addClient(Device.PC, 1, 1)
        val mail = w.addServer(Service.MAIL, 5, 1)
        w.connect(pc, mail, CableType.FIBER)
        run(w, pc, Service.MAIL, 5f, backlog = 1)
        assertFalse(w.isJammed(pc))
        assertTrue(w.jammedCables().isEmpty())
    }

    @Test
    fun blockedRequestsAreNoJam() {
        val w = world()
        val tv = w.addClient(Device.TV, 1, 1)
        val cdn = w.addServer(Service.STREAMING, 5, 1)
        w.connect(tv, cdn, CableType.ISDN)
        run(w, tv, Service.STREAMING, World.Tuning.JAM_SECONDS * 2, backlog = 3)
        assertEquals("too narrow, not jammed", RouteProblem.TOO_NARROW, w.routeProblem(tv, Service.STREAMING))
        assertNull(w.jamLink(tv))
        assertFalse(w.isJammed(tv))
    }

    @Test
    fun deviceCabledToTheWrongServerMissesItsService() {
        val w = world()
        val pc = w.addClient(Device.PC, 1, 1)
        val mail = w.addServer(Service.MAIL, 4, 1)
        val game = w.addServer(Service.GAMING, 4, 6)
        assertTrue("unlinked: the device shows that by itself", w.unreachableServices(pc).isEmpty())
        assertNull(w.firstUnreachableService(pc))
        w.connect(pc, mail, CableType.FIBER)
        // Backups have no server on this map, so the PC never asks for one.
        assertEquals(listOf(Service.GAMING), w.unreachableServices(pc))
        assertEquals(Service.GAMING, w.firstUnreachableService(pc))
        val router = w.addRouter(2, 4)
        w.connect(pc, router, CableType.FIBER)
        assertEquals("a router leading nowhere does not help", listOf(Service.GAMING), w.unreachableServices(pc))
        w.connect(router, game, CableType.FIBER)
        assertTrue(w.unreachableServices(pc).isEmpty())
        assertNull(w.firstUnreachableService(pc))
        assertTrue("servers want nothing", w.unreachableServices(mail).isEmpty())
    }

    @Test
    fun slowOrNarrowRoutesAreNotMissingServers() {
        val w = world()
        val tv = w.addClient(Device.TV, 1, 1)
        val cdn = w.addServer(Service.STREAMING, 5, 1)
        w.connect(tv, cdn, CableType.ISDN)
        assertTrue("too narrow has its own badge", w.unreachableServices(tv).isEmpty())
    }

    @Test
    fun fullSecondCableBehindARouterJamsTheDevice() {
        val w = world()
        val pc = w.addClient(Device.PC, 1, 1)
        val router = w.addRouter(3, 1)
        val mail = w.addServer(Service.MAIL, 14, 1)
        mail.level = World.Tuning.SERVER_RATE.size
        w.connect(pc, router, CableType.FIBER)
        w.connect(router, mail, CableType.ISDN)
        val far = w.cableBetween(router, mail)!!
        run(w, pc, Service.MAIL, World.Tuning.JAM_SECONDS * 3, backlog = 5)
        assertSame("the long narrow hop, not the device's own cable", far, w.jamLink(pc))
        assertNull(w.jamServer(pc))
        assertTrue(w.isJammed(pc))
        assertEquals(listOf(far), w.jammedCables())
    }

    @Test
    fun saturatedServerIsBlamedInsteadOfItsCable() {
        val w = world()
        val pc = w.addClient(Device.PC, 1, 1)
        val mail = w.addServer(Service.MAIL, 3, 1)
        w.connect(pc, mail, CableType.FIBER)
        val cable = w.cableBetween(pc, mail)!!
        run(w, pc, Service.MAIL, 30f, backlog = 5)
        assertSame(mail, w.jamServer(pc))
        assertNull("a wider cable would not help", w.jamLink(pc))
        assertTrue(w.isJammed(pc))
        assertFalse(w.isJammed(cable))
    }

    @Test
    fun oneStreamAtATimeOnDslIsNoJam() {
        val w = world()
        val tv = w.addClient(Device.TV, 1, 1)
        val cdn = w.addServer(Service.STREAMING, 14, 8)
        cdn.level = World.Tuning.SERVER_RATE.size
        w.connect(tv, cdn, CableType.DSL)
        tv.requestTimer = Float.MAX_VALUE
        run(w, tv, Service.STREAMING, World.Tuning.JAM_SECONDS * 4, backlog = 1)
        assertFalse("a cable that keeps up with one request at a time", w.isJammed(tv))
        assertTrue(w.jammedCables().isEmpty())
    }

    @Test
    fun cutBackboneIsNoMissingServer() {
        val w = world()
        val pc = w.addClient(Device.PC, 1, 1)
        val router = w.addRouter(3, 1)
        val mail = w.addServer(Service.MAIL, 6, 1)
        w.connect(pc, router, CableType.FIBER)
        w.connect(router, mail, CableType.FIBER)
        val cut = w.announceExcavator(w.cableBetween(router, mail)!!)
        while (!cut.struck) w.update(STEP)
        assertEquals("the route is down", RouteProblem.NO_ROUTE, w.routeProblem(pc, Service.MAIL))
        assertTrue("the incident shows itself", w.unreachableServices(pc).isEmpty())
        assertNull(w.firstUnreachableService(pc))
    }

    private companion object {
        const val STEP = 0.1f
    }
}
