package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(DebugApi::class)
class WorldTest {

    /** Empty world; the river never reaches the left 6 columns, so tests control every node there. */
    private fun world() = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false).also { w ->
        for (y in 0 until w.rows) for (x in 0 until 6) assertFalse("test area must be dry", w.water[y][x])
    }

    @Test
    fun routeFindsServerOfMatchingServiceThroughRouter() {
        val w = world()
        val pc = w.addClient(Device.PC, 1, 1)
        val router = w.addRouter(3, 1)
        val call = w.addServer(Service.CALL, 3, 4)
        val mail = w.addServer(Service.MAIL, 5, 1)
        assertTrue(w.connect(pc, router, CableType.ISDN))
        assertTrue(w.connect(router, call, CableType.ISDN))
        assertNull("no mail server reachable yet", w.routeFor(pc, Service.MAIL))
        assertTrue(w.connect(router, mail, CableType.ISDN))
        assertEquals(listOf(pc, router, mail), w.routeFor(pc, Service.MAIL)!!.nodes)
    }

    @Test
    fun serversOfOtherServiceDoNotForward() {
        val w = world()
        val pc = w.addClient(Device.PC, 1, 1)
        val call = w.addServer(Service.CALL, 3, 1)
        val mail = w.addServer(Service.MAIL, 5, 1)
        w.connect(pc, call, CableType.ISDN)
        w.connect(call, mail, CableType.ISDN)
        assertNull(w.routeFor(pc, Service.MAIL))
    }

    @Test
    fun streamingDoesNotFitThroughIsdn() {
        val w = world()
        val tv = w.addClient(Device.TV, 1, 1)
        val cdn = w.addServer(Service.STREAMING, 4, 1)
        w.connect(tv, cdn, CableType.ISDN)
        assertNull("ISDN capacity 2 < streaming bandwidth 3", w.routeFor(tv, Service.STREAMING))
        w.jumpToWeek(CableType.DSL.unlockWeek)
        assertTrue(w.upgrade(w.cableBetween(tv, cdn)!!, CableType.DSL))
        assertNotNull(w.routeFor(tv, Service.STREAMING))
    }

    @Test
    fun gamingNeedsLowPing() {
        val w = world()
        val console = w.addClient(Device.CONSOLE, 1, 1)
        val game = w.addServer(Service.GAMING, 1, 5)
        w.connect(console, game, CableType.ISDN)
        val route = w.bestRoute(console, Service.GAMING)
        assertNotNull(route)
        assertTrue("4 cells ISDN = ${route!!.pingMs} ms", route.pingMs > Service.GAMING.maxPingMs!!)
        assertNull("too slow for gaming", w.routeFor(console, Service.GAMING))
        w.jumpToWeek(CableType.FIBER.unlockWeek)
        assertTrue(w.upgrade(w.cableBetween(console, game)!!, CableType.FIBER))
        assertNotNull(w.routeFor(console, Service.GAMING))
    }

    @Test
    fun cablesCostBudgetAndRespectPorts() {
        val w = world()
        val budget = w.budget
        val c = w.addClient(Device.PHONE, 1, 1)
        val a = w.addRouter(1, 3)
        val b = w.addRouter(3, 3)
        val d = w.addRouter(4, 1)
        assertTrue(w.connect(c, a, CableType.ISDN))
        assertEquals(budget - 2, w.budget)
        assertTrue(w.connect(c, b, CableType.ISDN))
        assertNotNull("client has only 2 ports", w.connectError(c, d, CableType.ISDN))
        w.removeCable(w.cableBetween(c, a)!!)
        assertEquals(budget - 2, w.budget)
        assertNull(w.connectError(c, d, CableType.ISDN))
    }

    @Test
    fun lockedCableTypesAreRejected() {
        val w = world()
        val a = w.addRouter(1, 1)
        val b = w.addRouter(3, 1)
        assertNotNull(w.connectError(a, b, CableType.FIBER))
    }

    @Test
    fun packetsGetDeliveredOverTime() {
        val w = world()
        val phone = w.addClient(Device.PHONE, 1, 1)
        val server = w.addServer(Service.CALL, 3, 1)
        w.connect(phone, server, CableType.ISDN)
        repeat(60 * 20) { w.update(1f / 60f) }
        assertTrue("expected deliveries, got ${w.delivered}", w.delivered > 0)
        assertFalse(w.gameOver)
    }

    @Test
    fun manyPacketsArrivingInOneFrameDoNotCrash() {
        val w = world()
        val server = w.addServer(Service.CALL, 3, 5)
        val router = w.addRouter(3, 3)
        w.connect(router, server, CableType.ISDN)
        val phones = listOf(w.addClient(Device.PHONE, 1, 1), w.addClient(Device.PHONE, 5, 1))
        phones.forEach { w.connect(it, router, CableType.ISDN) }
        repeat(60 * 60) { w.update(1f / 60f) }
        assertTrue(w.delivered > 5)
    }

    @Test
    fun serverThroughputGrowsWithLevel() {
        fun deliveredWith(level: Int): Int {
            val w = world()
            w.grant(100)
            val server = w.addServer(Service.MAIL, 4, 5)
            val router = w.addRouter(4, 3)
            w.jumpToWeek(CableType.FIBER.unlockWeek)
            w.connect(router, server, CableType.FIBER)
            listOf(1 to 1, 3 to 1, 5 to 1, 2 to 2).forEach { (x, y) -> w.connect(w.addClient(Device.PC, x, y), router, CableType.FIBER) }
            repeat(level - 1) { assertTrue(w.upgradeServer(server)) }
            assertEquals(level, server.level)
            repeat(60 * 60) { w.update(1f / 60f) }
            return w.delivered
        }
        val slow = deliveredWith(1)
        assertTrue("level 1 caps at 1.5/s, got $slow in 60 s", slow <= 60 * 1.5 + 2)
        assertTrue(deliveredWith(3) >= slow)
    }

    @Test
    fun unconnectedClientEventuallyOverloads() {
        val w = world()
        w.addServer(Service.MAIL, 4, 1)
        w.addClient(Device.PC, 1, 1)
        repeat(60 * 180) { if (!w.gameOver) w.update(1f / 60f) }
        assertTrue(w.gameOver)
        assertEquals(NodeKind.CLIENT, w.failedNode?.kind)
    }

    @Test
    fun waterMakesCablesMoreExpensive() {
        val w = World(seed = 3L, spawnInitialNodes = false)
        val y = 4
        val riverX = (0 until w.cols).first { w.water[y][it] }
        val left = w.addRouter(riverX - 2, y)
        val right = w.addRouter(riverX + 2, y)
        assertEquals(4 + World.Tuning.WATER_EXTRA_PER_CELL, w.cableCost(left, right, CableType.ISDN))
    }
}
