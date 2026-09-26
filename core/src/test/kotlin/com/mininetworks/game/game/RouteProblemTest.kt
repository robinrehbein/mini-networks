package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Why requests cannot leave ([World.routeProblem]), why a game was lost ([World.failure]) and planned cables ([World.checkCable]). */
@OptIn(DebugApi::class)
class RouteProblemTest {

    /** Empty world in fiber times; the river never reaches the left 6 columns. */
    private fun world() = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false).also { w ->
        for (y in 0 until w.rows) for (x in 0 until 6) assertFalse("test area must be dry", w.water[y][x])
        w.incidentsEnabled = false
        w.jumpToWeek(CableType.FIBER.unlockWeek)
        w.grant(500)
    }

    /** Runs until the game is lost, feeding [client] [perStep] requests of [s] every half second. */
    private fun loseWith(w: World, client: Node, s: Service, perStep: Int = 1) {
        while (!w.gameOver) {
            if (w.rewardOffer != null) w.chooseReward(0)
            repeat(perStep) { client.pending.addLast(s) }
            w.update(0.5f)
        }
    }

    @Test
    fun noRouteNarrowAndSlowAreTold() {
        val w = world()
        val tv = w.addClient(Device.TV, 1, 1)
        val cdn = w.addServer(Service.STREAMING, 4, 1)
        assertEquals(RouteProblem.NO_ROUTE, w.routeProblem(tv, Service.STREAMING))
        w.connect(tv, cdn, CableType.ISDN)
        assertEquals("ISDN carries 2, streaming needs 3", RouteProblem.TOO_NARROW, w.routeProblem(tv, Service.STREAMING))
        w.upgrade(w.cableBetween(tv, cdn)!!, CableType.DSL)
        assertNull(w.routeProblem(tv, Service.STREAMING))

        val console = w.addClient(Device.CONSOLE, 1, 9)
        val game = w.addServer(Service.GAMING, 5, 0)
        w.connect(console, game, CableType.ISDN)
        assertEquals(RouteProblem.PING_TOO_HIGH, w.routeProblem(console, Service.GAMING))
        w.upgrade(w.cableBetween(console, game)!!, CableType.FIBER)
        assertNull(w.routeProblem(console, Service.GAMING))
    }

    @Test
    fun failureNamesServiceAndCause() {
        val w = world()
        w.addServer(Service.GAMING, 5, 0)
        val console = w.addClient(Device.CONSOLE, 1, 9)
        w.connect(console, w.nodes.first { it.service == Service.GAMING }, CableType.ISDN)
        assertNull("still playing", w.failure)
        loseWith(w, console, Service.GAMING)
        val f = w.failure!!
        assertSame(console, f.node)
        assertEquals(Service.GAMING, f.service)
        assertEquals(RouteProblem.PING_TOO_HIGH, f.problem)
        assertTrue(f.pingMs!! > Service.GAMING.maxPingMs!!)
    }

    @Test
    fun jammedRouteHasNoProblem() {
        val w = world()
        val mail = w.addServer(Service.MAIL, 4, 1)
        val pc = w.addClient(Device.PC, 1, 1)
        w.connect(pc, mail, CableType.ISDN)
        loseWith(w, pc, Service.MAIL, perStep = 4)
        assertNull("a route was there: congestion", w.failure!!.problem)
    }

    @Test
    fun plannedCableTellsWidthAndPing() {
        val w = world()
        val tv = w.addClient(Device.TV, 1, 1)
        val cdn = w.addServer(Service.STREAMING, 4, 1)
        assertEquals(RouteProblem.TOO_NARROW, w.checkCable(tv, cdn, CableType.ISDN)!!.problem)
        assertNull("wide enough and no ping limit", w.checkCable(tv, cdn, CableType.DSL))

        val console = w.addClient(Device.CONSOLE, 1, 9)
        val game = w.addServer(Service.GAMING, 5, 0)
        val slow = w.checkCable(console, game, CableType.ISDN)!!
        assertEquals(RouteProblem.PING_TOO_HIGH, slow.problem)
        assertEquals(Service.GAMING, slow.service)
        assertEquals(140, slow.limitMs)
        val fast = w.checkCable(console, game, CableType.FIBER)!!
        assertNull(fast.problem)
        assertTrue(fast.pingMs!! < 140f)
        assertTrue("checking lays nothing", w.cables.none { it.connects(console) })

        val router = w.addRouter(3, 6)
        assertNull("nothing reachable through the router yet", w.checkCable(console, router, CableType.FIBER))
        w.connect(router, game, CableType.FIBER)
        assertNotNull(w.checkCable(console, router, CableType.FIBER)!!.pingMs)
        assertNull("only clients have services", w.checkCable(router, game, CableType.FIBER))
    }
}
