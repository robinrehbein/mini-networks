package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Test

/** The one tip the game-over card gives against the cause of a loss ([World.lossTip]). */
@OptIn(DebugApi::class)
class LossTipTest {

    private fun world() = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false).also { w ->
        for (row in w.water) row.fill(false)
        w.incidentsEnabled = false
        w.jumpToWeek(CableType.FIBER.unlockWeek)
        w.grant(500)
    }

    /** The tip for [client] failing on [s], with the cause the world sees right now (as [World.failure] records it). */
    private fun tip(w: World, client: Node, s: Service): LossTip {
        val problem = w.routeProblem(client, s)
        return w.lossTip(Failure(client, s, problem, null))
    }

    /** Runs in small steps, topping [client] up to [backlog] waiting requests of [s] before each. */
    private fun run(w: World, client: Node, s: Service, seconds: Float, backlog: Int) {
        var t = 0f
        while (t < seconds) {
            while (client.pending.size < backlog) client.pending.addLast(s)
            w.update(STEP)
            t += STEP
        }
    }

    @Test
    fun unlinkedDeviceIsToldToConnect() {
        val w = world()
        val phone = w.addClient(Device.PHONE, 1, 1)
        w.addServer(Service.CALL, 5, 1)
        assertEquals(LossTip.CONNECT, tip(w, phone, Service.CALL))
    }

    @Test
    fun deviceLinkedToTheWrongServerNeedsItsServer() {
        val w = world()
        val pc = w.addClient(Device.PC, 1, 1)
        val mail = w.addServer(Service.MAIL, 4, 1)
        w.addServer(Service.GAMING, 4, 6)
        w.connect(pc, mail, CableType.FIBER)
        assertEquals(LossTip.NEEDS_SERVER, tip(w, pc, Service.GAMING))
    }

    @Test
    fun deviceWithFullPortsWantsARouter() {
        val w = world()
        val pc = w.addClient(Device.PC, 1, 1)
        w.connect(pc, w.addServer(Service.MAIL, 4, 1), CableType.FIBER)
        w.connect(pc, w.addClient(Device.LAPTOP, 1, 4), CableType.FIBER)
        w.addServer(Service.GAMING, 8, 6)
        assertEquals(pc.maxPorts, w.ports(pc))
        assertEquals(LossTip.ROUTER, tip(w, pc, Service.GAMING))
    }

    @Test
    fun cutCableWantsARepair() {
        val w = world()
        val pc = w.addClient(Device.PC, 1, 1)
        val router = w.addRouter(3, 1)
        val mail = w.addServer(Service.MAIL, 6, 1)
        w.connect(pc, router, CableType.FIBER)
        w.connect(router, mail, CableType.FIBER)
        val cut = w.announceExcavator(w.cableBetween(router, mail)!!)
        while (!cut.struck) w.update(STEP)
        assertEquals(RouteProblem.NO_ROUTE, w.routeProblem(pc, Service.MAIL))
        assertEquals(LossTip.REPAIR, tip(w, pc, Service.MAIL))
    }

    @Test
    fun powerOutageWantsAWayAroundNotARepair() {
        val w = world()
        val pc = w.addClient(Device.PC, 1, 1)
        val router = w.addRouter(3, 1)
        val mail = w.addServer(Service.MAIL, 6, 1)
        w.connect(pc, router, CableType.FIBER)
        w.connect(router, mail, CableType.FIBER)
        val dark = w.announcePowerOutage(router)
        while (!dark.struck) w.update(STEP)
        assertEquals(RouteProblem.NO_ROUTE, w.routeProblem(pc, Service.MAIL))
        assertEquals(LossTip.OUTAGE, tip(w, pc, Service.MAIL))
    }

    @Test
    fun cutWideCableWantsARepairNotAWiderOne() {
        val w = world()
        val tv = w.addClient(Device.TV, 1, 1)
        val stream = w.addServer(Service.STREAMING, 6, 1)
        w.connect(tv, stream, CableType.FIBER)
        val router = w.addRouter(3, 4)
        w.connect(tv, router, CableType.ISDN)
        w.connect(router, stream, CableType.ISDN)
        val cut = w.announceExcavator(w.cableBetween(tv, stream)!!)
        while (!cut.struck) w.update(STEP)
        assertEquals(RouteProblem.TOO_NARROW, w.routeProblem(tv, Service.STREAMING))
        assertEquals(LossTip.REPAIR, tip(w, tv, Service.STREAMING))
    }

    @Test
    fun serverWithFullPortsWantsARouter() {
        val w = world()
        val mail = w.addServer(Service.MAIL, 6, 4)
        repeat(mail.maxPorts) { w.connect(w.addClient(Device.PC, 3 + 3 * (it % 3), 1 + 6 * (it / 3)), mail, CableType.FIBER) }
        assertEquals(mail.maxPorts, w.ports(mail))
        val pc = w.addClient(Device.PC, 1, 4)
        assertEquals(LossTip.ROUTER, tip(w, pc, Service.MAIL))
    }

    @Test
    fun saturatedServerThatCannotGrowWantsASecondServer() {
        val w = world()
        val router = w.addRouter(2, 1)
        val mail = w.addServer(Service.MAIL, 3, 1)
        mail.level = World.Tuning.DATA_CENTER_LEVEL - 1
        // No 2×2 room for a data center around the server.
        for ((x, y) in listOf(2 to 0, 3 to 0, 4 to 0, 4 to 1, 2 to 2, 3 to 2, 4 to 2)) w.addRouter(x, y)
        w.connect(router, mail, CableType.FIBER)
        val pcs = (0 until 4).map { w.addClient(Device.PC, 0, 2 * it + 1).also { pc -> w.connect(pc, router, CableType.FIBER) } }
        assertEquals(ServerUpgradeError.NO_SPACE, w.serverUpgradeError(mail))
        var t = 0f
        while (t < 30f) {
            for (pc in pcs) while (pc.pending.size < 8) pc.pending.addLast(Service.MAIL)
            w.update(STEP)
            t += STEP
        }
        assertEquals(mail, w.jamServer(pcs[0], Service.MAIL))
        assertEquals(LossTip.SECOND_SERVER, w.lossTip(Failure(pcs[0], Service.MAIL, null, null)))
    }

    @Test
    fun narrowCableWantsAWiderOne() {
        val w = world()
        val tv = w.addClient(Device.TV, 1, 1)
        w.connect(tv, w.addServer(Service.STREAMING, 4, 1), CableType.ISDN)
        assertEquals(LossTip.WIDER_CABLE, tip(w, tv, Service.STREAMING))
    }

    @Test
    fun highPingWantsAFasterCable() {
        val w = world()
        val console = w.addClient(Device.CONSOLE, 1, 9)
        w.connect(console, w.addServer(Service.GAMING, 5, 0), CableType.ISDN)
        assertEquals(LossTip.FASTER_CABLE, tip(w, console, Service.GAMING))
    }

    @Test
    fun jammedCableWantsASecondOrWiderCable() {
        val w = world()
        val pc = w.addClient(Device.PC, 1, 1)
        val router = w.addRouter(3, 1)
        val mail = w.addServer(Service.MAIL, 14, 1)
        mail.level = World.Tuning.SERVER_RATE.size
        w.connect(pc, router, CableType.FIBER)
        w.connect(router, mail, CableType.ISDN)
        run(w, pc, Service.MAIL, World.Tuning.JAM_SECONDS * 3, backlog = 5)
        assertEquals(LossTip.SECOND_CABLE, w.lossTip(Failure(pc, Service.MAIL, null, null)))
    }

    @Test
    fun saturatedServerWantsAnUpgradeWhileItCanGrow() {
        val w = world()
        val pc = w.addClient(Device.PC, 1, 1)
        val mail = w.addServer(Service.MAIL, 3, 1)
        w.connect(pc, mail, CableType.FIBER)
        run(w, pc, Service.MAIL, 30f, backlog = 5)
        assertEquals(LossTip.UPGRADE_SERVER, w.lossTip(Failure(pc, Service.MAIL, null, null)))
    }

    @Test
    fun lostGameGetsTheTipOfItsCause() {
        val w = world()
        val console = w.addClient(Device.CONSOLE, 1, 9)
        w.connect(console, w.addServer(Service.GAMING, 5, 0), CableType.ISDN)
        while (!w.gameOver) {
            if (w.rewardOffer != null) w.chooseReward(0)
            console.pending.addLast(Service.GAMING)
            w.update(0.5f)
        }
        assertEquals(LossTip.FASTER_CABLE, w.lossTip(w.failure!!))
    }

    private companion object {
        const val STEP = 0.1f
    }
}
