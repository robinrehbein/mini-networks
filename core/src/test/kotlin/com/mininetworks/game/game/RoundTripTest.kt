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
        assertTrue("room again: the response enters the cable", response.progress >= 0f)
        assertEquals(response.size, w.cableLoad(cable))
    }

    @Test
    fun waitingResponsesGoBeforeNewRequests() {
        val w = dryWorld()
        w.jumpToWeek(CableType.DSL.unlockWeek)
        val pc = w.addClient(Device.PC, 1, 1)
        val router = w.addRouter(4, 1)
        val server = w.addServer(Service.STREAMING, 7, 1)
        assertTrue(w.connect(pc, router, CableType.DSL))
        assertTrue(w.connect(router, server, CableType.DSL))
        val uplink = w.cableBetween(router, server)!!
        val route = listOf(pc, router, server)
        fun request(service: Service, progress: Float) = Packet(service, pc, route).apply { hop = 1; this.progress = progress }
        val inTransit = List(2) { request(Service.CALL, 0.5f) }
        val response = Packet(Service.STREAMING, pc, route.asReversed(), isResponse = true)
        val waitingRequest = request(Service.CALL, -1f)
        w.packets += inTransit
        w.packets += waitingRequest
        w.packets += response
        server.tokens = 0f

        w.update(1f / 600f)
        assertTrue("2 + 3 > 4: the response does not fit yet", response.progress < 0f)
        assertTrue("the small request would fit, but the room is the response's", waitingRequest.progress < 0f)
        assertEquals(2, w.cableLoad(uplink))

        w.packets.removeAll(inTransit)
        w.update(1f / 600f)
        assertTrue("room again: the response enters first", response.progress >= 0f)
        assertTrue("3 + 1 <= 4: now the request fits behind it", waitingRequest.progress >= 0f)
    }

    @Test
    fun slotFreedByArrivingRequestGoesToItsResponse() {
        val w = dryWorld()
        val phone = w.addClient(Device.PHONE, 1, 1)
        val router = w.addRouter(4, 1)
        val server = w.addServer(Service.CALL, 7, 1)
        assertTrue(w.connect(phone, router, CableType.ISDN))
        assertTrue(w.connect(router, server, CableType.ISDN))
        val uplink = w.cableBetween(router, server)!!
        val route = listOf(phone, router, server)
        // The uplink is full; one request arrives at the server now while another waits at the router.
        val arriving = Packet(Service.CALL, phone, route).apply { hop = 1; progress = 0.9999f }
        val onUplink = Packet(Service.CALL, phone, route).apply { hop = 1; progress = 0.1f }
        val waitingRequest = Packet(Service.CALL, phone, route).apply { hop = 1; progress = -1f }
        w.packets += listOf(onUplink, waitingRequest, arriving)
        server.tokens = 1f
        w.update(1f / 600f)
        val response = w.packets.single { it.isResponse }
        assertTrue("the answer takes the slot its request freed", response.progress >= 0f)
        assertTrue("the request at the router keeps waiting", waitingRequest.progress < 0f)
        assertEquals(uplink.capacity, w.cableLoad(uplink))
    }

    @Test
    fun backlogOnDirectIsdnCableKeepsDelivering() = assertSustainedThroughput(CableType.ISDN, serverLevel = 1)

    @Test
    fun backlogOnDirectDslCableKeepsDelivering() = assertSustainedThroughput(CableType.DSL, serverLevel = 3)

    @Test
    fun backlogOnSaturatedServerKeepsDelivering() = assertSustainedThroughput(CableType.COAX, serverLevel = 1)

    @Test
    fun backlogThroughRouterKeepsDelivering() =
        assertSustainedThroughput(CableType.FIBER, uplink = CableType.ISDN, serverLevel = 3)

    @Test
    fun clientsSharingRouterUplinkKeepDelivering() =
        assertSustainedThroughput(CableType.DSL, uplink = CableType.DSL, clients = 3, serverLevel = 1)

    @Test
    fun clientsSharingRouterUplinkToFastServerKeepDelivering() =
        assertSustainedThroughput(CableType.COAX, uplink = CableType.DSL, clients = 3, serverLevel = 3)

    /**
     * Phones with a long backlog, either on a direct cable to the server or through one router ([uplink] is the
     * router-server cable). Every delivery occupies one slot of each cable for the way there and one for the way back,
     * so the route carries about min(capacity / round-trip-time of each cable, server rate, dispatch rate) answers
     * per second, and answers never pile up behind waiting requests.
     */
    private fun assertSustainedThroughput(access: CableType, serverLevel: Int, uplink: CableType? = null, clients: Int = 1) {
        val w = dryWorld()
        w.jumpToWeek(maxOf(access.unlockWeek, uplink?.unlockWeek ?: 1))
        val server = w.addServer(Service.CALL, if (uplink == null) 5 else 8, 4)
        repeat(serverLevel - 1) { assertTrue(w.upgradeServer(server)) }
        val hub = if (uplink == null) server else w.addRouter(5, 4).also { assertTrue(w.connect(it, server, uplink)) }
        val phones = List(clients) { i -> w.addClient(Device.PHONE, 2, 3 + i) }
        for (phone in phones) {
            assertTrue(w.connect(phone, hub, access))
            phone.requestTimer = Float.MAX_VALUE
            repeat(24) { phone.pending.addLast(Service.CALL) }
        }
        // Shorter than OVERLOAD_SECONDS: the backlog itself would end the game after that.
        val seconds = 16f
        var maxWaiting = 0
        repeat((seconds / step).toInt()) {
            w.update(step)
            maxWaiting = maxOf(maxWaiting, w.packets.count { it.isResponse && it.progress < 0f })
        }
        assertFalse(w.gameOver)
        val accessCables = phones.map { w.cableBetween(it, hub)!! }
        val uplinkCable = w.cableBetween(hub, server)
        fun roundTrip(c: Cable) = 2 * c.length / c.type.speed
        var rate = minOf(w.serverRate(server), clients / World.Tuning.DISPATCH_COOLDOWN)
        rate = minOf(rate, accessCables.sumOf { (it.capacity / roundTrip(it)).toDouble() }.toFloat())
        if (uplinkCable != null && uplinkCable !in accessCables) rate = minOf(rate, uplinkCable.capacity / roundTrip(uplinkCable))
        val latency = roundTrip(accessCables.first()) + (uplinkCable?.takeIf { it !in accessCables }?.let(::roundTrip) ?: 0f)
        val expected = (seconds - latency) * rate
        assertTrue("delivered ${w.delivered}, expected about $expected", w.delivered >= (0.8f * expected).toInt())
        val slots = accessCables.sumOf { it.capacity } + (uplinkCable?.takeIf { it !in accessCables }?.capacity ?: 0)
        assertTrue("answers do not pile up: $maxWaiting waiting", maxWaiting <= slots)
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
