package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(DebugApi::class)
class RoundTripTest {

    private val step = 1f / 60f

    /** Empty world with the river removed. */
    private fun dryWorld() = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false).also { w ->
        for (row in w.water) row.fill(false)
        w.grant(200)
    }

    @Test
    fun requestTurnsIntoResponseAndCountsWhenItIsBack() {
        val w = dryWorld()
        val phone = w.addClient(Device.PHONE, 1, 1)
        val server = w.addServer(Service.CALL, 4, 1)
        assertTrue(w.connect(phone, server, CableType.ISDN))
        var request: Packet? = null
        var response: Packet? = null
        var sentAt = 0
        var turnedAt = 0
        var backAt = 0
        var s = 0
        while (s < 60 * 30 && backAt == 0) {
            w.update(step)
            s++
            if (request == null) {
                request = w.packets.firstOrNull()?.also { assertFalse(it.isResponse); sentAt = s }
            } else if (response == null && request !in w.packets) {
                response = w.packets.single { it.isResponse }
                turnedAt = s
                assertEquals("not delivered before the response is back", 0, w.delivered)
            } else if (response != null && response !in w.packets) {
                backAt = s
            }
            response?.takeIf { it in w.packets && it.progress >= 0f }?.let {
                assertTrue("response travels back towards the phone", w.packetPosition(it).x <= server.center.x)
            }
        }
        assertNotNull(response)
        assertEquals(listOf(server, phone), response!!.route)
        assertSame(phone, response.origin)
        assertEquals(1, w.delivered)
        val oneWay = 3f / CableType.ISDN.speed
        assertEquals("request: 3 cells at ISDN speed", oneWay, (turnedAt - sentAt) * step, 0.05f)
        assertEquals("response takes the same time back", oneWay, (backAt - turnedAt) * step, 0.05f)
    }

    @Test
    fun responsesShareCableCapacity() {
        val w = dryWorld()
        val phone = w.addClient(Device.PHONE, 1, 1)
        val server = w.addServer(Service.CALL, 4, 1)
        assertTrue(w.connect(phone, server, CableType.ISDN))
        val route = listOf(phone, server)
        val requests = List(CableType.ISDN.capacity) { Packet(Service.CALL, phone, route).apply { progress = 0.1f } }
        val response = Packet(Service.CALL, phone, route.asReversed(), isResponse = true)
        w.packets += requests
        w.packets += response
        val cable = w.cableBetween(phone, server)!!
        w.update(step)
        assertEquals(cable.capacity, w.cableLoad(cable))
        assertTrue("cable full: the response waits at the server", response.progress < 0f)
        assertEquals(server.center, w.packetPosition(response))
        w.packets.removeAll(requests)
        w.update(step)
        assertTrue("room again: the response starts", response.progress > 0f)
        assertEquals(response.size, w.cableLoad(cable))
    }

    @Test
    fun waitingResponsesGoBeforeNewRequests() {
        val w = dryWorld()
        val phone = w.addClient(Device.PHONE, 1, 1)
        val server = w.addServer(Service.CALL, 4, 1)
        assertTrue(w.connect(phone, server, CableType.ISDN))
        phone.requestTimer = Float.MAX_VALUE
        repeat(10) { phone.pending.addLast(Service.CALL) }
        var sawWaitingResponse = false
        repeat(60 * 15) {
            val waiting = w.packets.filter { it.isResponse && it.progress < 0f }
            val before = w.packets.toSet()
            w.update(step)
            val dispatched = w.packets.filter { it !in before && !it.isResponse }
            if (waiting.isNotEmpty()) sawWaitingResponse = true
            if (dispatched.isNotEmpty()) {
                assertTrue("no new request while an answer waits for the cable", waiting.none { it in w.packets && it.progress < 0f })
            }
        }
        assertTrue("the scenario makes answers wait", sawWaitingResponse)
    }

    @Test
    fun backlogOnDirectIsdnCableKeepsDelivering() = assertSustainedThroughput(CableType.ISDN, serverLevel = 1)

    @Test
    fun backlogOnDirectDslCableKeepsDelivering() = assertSustainedThroughput(CableType.DSL, serverLevel = 3)

    @Test
    fun backlogOnSaturatedServerKeepsDelivering() = assertSustainedThroughput(CableType.COAX, serverLevel = 1)

    /**
     * A client with a long backlog on a direct 3-cell cable: every delivery occupies one slot for the way there and
     * one for the way back, so the cable carries about capacity / round-trip-time answers per second.
     */
    private fun assertSustainedThroughput(type: CableType, serverLevel: Int) {
        val w = dryWorld()
        w.jumpToWeek(type.unlockWeek)
        val phone = w.addClient(Device.PHONE, 1, 1)
        val server = w.addServer(Service.CALL, 4, 1)
        repeat(serverLevel - 1) { assertTrue(w.upgradeServer(server)) }
        assertTrue(w.connect(phone, server, type))
        val cable = w.cableBetween(phone, server)!!
        phone.requestTimer = Float.MAX_VALUE
        repeat(24) { phone.pending.addLast(Service.CALL) }
        val seconds = 16f
        var maxWaiting = 0
        repeat((seconds / step).toInt()) {
            w.update(step)
            maxWaiting = maxOf(maxWaiting, w.packets.count { it.isResponse && it.progress < 0f })
        }
        assertFalse(w.gameOver)
        val roundTrip = 2 * cable.length / type.speed
        val rate = minOf(cable.capacity / roundTrip, w.serverRate(server))
        val expected = (seconds - roundTrip) * rate
        assertTrue("delivered ${w.delivered}, expected about $expected", w.delivered >= (0.8f * expected).toInt())
        assertTrue("answers do not pile up at the server: $maxWaiting", maxWaiting <= cable.capacity)
    }

    @Test
    fun cutCableSendsResponseBackToTheQueue() {
        val w = dryWorld()
        val phone = w.addClient(Device.PHONE, 1, 1)
        val server = w.addServer(Service.CALL, 4, 1)
        assertTrue(w.connect(phone, server, CableType.ISDN))
        var guard = 0
        while (w.packets.none { it.isResponse }) { w.update(step); check(++guard < 60 * 30) }
        val queued = phone.pending.size
        w.removeCable(w.cableBetween(phone, server)!!)
        assertTrue(w.packets.isEmpty())
        assertEquals(queued + 1, phone.pending.size)
        assertEquals(0, w.delivered)
    }

    @Test
    fun pingCountsBothDirections() {
        val w = dryWorld()
        val pc = w.addClient(Device.PC, 1, 1)
        val router = w.addRouter(3, 1)
        val mail = w.addServer(Service.MAIL, 6, 1)
        assertTrue(w.connect(pc, router, CableType.ISDN))
        assertTrue(w.connect(router, mail, CableType.ISDN))
        val oneWay = 2 * CableType.ISDN.msPerCell + World.Tuning.ROUTER_MS + 3 * CableType.ISDN.msPerCell
        val route = w.routeFor(pc, Service.MAIL)!!
        assertEquals(2 * oneWay, route.pingMs, 1e-3f)
        assertEquals(oneWay, route.oneWayMs, 1e-3f)
    }

    @Test
    fun gamingOverDistanceNeedsFiber() {
        val w = dryWorld()
        w.jumpToWeek(CableType.FIBER.unlockWeek)
        val near = w.addClient(Device.CONSOLE, 1, 1)
        val nearServer = w.addServer(Service.GAMING, 4, 1)
        assertTrue(w.connect(near, nearServer, CableType.DSL))
        assertNotNull("3 cells DSL are fine for gaming", w.routeFor(near, Service.GAMING))

        val far = w.addClient(Device.CONSOLE, 8, 0)
        val farServer = w.addServer(Service.GAMING, 8, 9)
        assertTrue(w.connect(far, farServer, CableType.COAX))
        val cable = w.cableBetween(far, farServer)!!
        // The near server is not linked to the far console, so only the 9-cell cable counts.
        assertNull("9 cells TV cable are too slow both ways", w.routeFor(far, Service.GAMING))
        assertTrue(w.upgrade(cable, CableType.FIBER))
        assertNotNull("fiber makes it", w.routeFor(far, Service.GAMING))
    }
}
