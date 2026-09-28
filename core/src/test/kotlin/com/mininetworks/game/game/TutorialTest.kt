package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tutorial: guided world, the step state machine driven by the world, focus per step, skipping. */
class TutorialTest {

    private val step = 1f / 60f

    /** Advances the world like the game loop does and lets the tutorial look at it every frame. */
    private fun run(t: Tutorial, seconds: Float): Int {
        var changes = 0
        repeat((seconds * 60).toInt()) {
            t.world.update(step)
            if (t.update()) changes++
        }
        return changes
    }

    private fun router(t: Tutorial) = t.world.nodes.single { it.kind == NodeKind.ROUTER }

    /** Plays the tutorial like a player up to the start of [target]. */
    private fun playTo(target: TutorialStep): Tutorial {
        val t = Tutorial.start()
        val w = t.world
        if (t.step < target) {
            assertTrue(w.connect(t.pc, t.mailServer, CableType.ISDN))
            run(t, 0.1f)
        }
        if (t.step < target) {
            val at = w.nearestFree(Cell(t.phones[0].cellX - 2, t.phones[0].cellY + 1))!!
            assertNotNull(w.placeRouter(at.x, at.y))
            for (n in t.phones + t.callServer!!) assertTrue(w.connect(n, router(t), CableType.ISDN))
            run(t, 0.1f)
        }
        if (t.step < target) {
            assertTrue(w.connect(t.tv!!, t.streamServer!!, CableType.DSL))
            run(t, 0.1f)
        }
        if (t.step < target) {
            assertTrue(w.connect(t.pc, t.gameServer!!, CableType.FIBER))
            run(t, 0.1f)
        }
        if (t.step < target) {
            assertTrue(w.connect(t.newPc!!, t.mailServer, CableType.DSL))
            run(t, 1f)
        }
        if (t.step < target) {
            val pcs = t.officePcs
            val at = w.nearestFree(Cell(pcs[0].cellX - 1, pcs[0].cellY + 1))!!
            val r = w.placeRouter(at.x, at.y)!!
            for (n in pcs + t.mailServer) assertTrue(w.connect(n, r, CableType.DSL))
            run(t, 0.1f)
        }
        assertEquals(target, t.step)
        return t
    }

    @Test
    fun startsInAGuidedRiverTownWithThePcAndTheMailServer() {
        val t = Tutorial.start()
        val w = t.world
        assertEquals(Scenarios.RIVER_TOWN.id, w.scenario.id)
        assertTrue(w.guided)
        assertEquals(TutorialStep.LAY_CABLE, t.step)
        assertEquals(1, t.number)
        assertEquals(listOf(t.mailServer, t.pc), w.nodes)
        for (n in w.nodes) assertTrue("$n on dry land in the block", w.unlocked.contains(n.cellX, n.cellY) && w.terrainAt(n.cellX, n.cellY) == Terrain.LAND)
        assertEquals(TutorialFocus.Drag(t.pc, t.mailServer), t.focus(CableType.ISDN))
        assertEquals("always the same map", Tutorial.start().world.snapshot(), w.snapshot())
    }

    @Test
    fun nothingHappensUntilThePlayerActs() {
        val t = Tutorial.start()
        assertEquals(0, run(t, 3 * World.Tuning.WEEK_SECONDS))
        val w = t.world
        assertEquals(TutorialStep.LAY_CABLE, t.step)
        assertEquals("no clients spawn", 2, w.nodes.size)
        assertEquals("the calendar stands still", 1, w.week)
        assertEquals(0f, w.weekProgress)
        assertNull(w.rewardOffer)
        assertTrue(w.incidents.isEmpty())
        assertFalse("waiting requests never end the tutorial", w.gameOver)
        assertEquals(World.Tuning.GUIDED_MAX_OVERLOAD, t.pc.overload)
    }

    @Test
    fun layingTheCableMovesToTheRouterStep() {
        val t = Tutorial.start()
        val w = t.world
        assertTrue(w.connect(t.pc, t.mailServer, CableType.ISDN))
        assertTrue(t.update())
        assertEquals(TutorialStep.PLACE_ROUTER, t.step)
        assertFalse("one step per goal", t.update())
        assertEquals(2, t.phones.size)
        assertEquals(Service.CALL, t.callServer!!.service)
        assertEquals(TutorialFocus.RouterButton, t.focus(CableType.ISDN))
    }

    @Test
    fun routerStepNeedsTheRouterCabledTwice() {
        val t = playTo(TutorialStep.PLACE_ROUTER)
        val w = t.world
        val at = w.nearestFree(Cell(t.phones[0].cellX - 2, t.phones[0].cellY + 1))!!
        val r = w.placeRouter(at.x, at.y)!!
        assertEquals(TutorialFocus.Nodes(listOf(r) + t.phones + t.callServer!!), t.focus(CableType.ISDN))
        assertTrue(w.connect(t.phones[0], r, CableType.ISDN))
        assertFalse("a router with one cable distributes nothing yet", t.update())
        assertTrue(w.connect(r, t.callServer!!, CableType.ISDN))
        assertTrue(t.update())
        assertEquals(TutorialStep.BANDWIDTH, t.step)
    }

    @Test
    fun bandwidthStepJumpsTo1998AndIsLearnedByTryingIsdnFirst() {
        val t = playTo(TutorialStep.BANDWIDTH)
        val w = t.world
        assertEquals(Tutorial.DSL_WEEK, w.week)
        assertEquals(1998, w.year)
        assertEquals(listOf(CableType.ISDN, CableType.DSL), w.unlockedCables)
        assertEquals(listOf(CableType.DSL), w.lastNews!!.cables)
        assertNull("no reward, no growth", w.rewardOffer)
        assertEquals(w.unlockedArea(1), w.unlocked)
        val tv = t.tv!!
        val server = t.streamServer!!
        assertEquals(Device.TV, tv.device)
        assertEquals(Service.STREAMING, server.service)
        assertTrue("streaming needs more than ISDN carries", Service.STREAMING.bandwidth > CableType.ISDN.capacity)
        assertEquals(TutorialFocus.Drag(tv, server), t.focus(CableType.ISDN))
        assertFalse(t.tooNarrow())

        // The player tries ISDN: the TV is cabled, but its streams do not fit.
        assertTrue(w.connect(tv, server, CableType.ISDN))
        run(t, 3f)
        assertEquals(TutorialStep.BANDWIDTH, t.step)
        assertTrue(t.tooNarrow())
        assertEquals(RouteProblem.TOO_NARROW, w.routeProblem(tv, Service.STREAMING))
        assertEquals("pick a wider cable", TutorialFocus.CableButton(CableType.DSL), t.focus(CableType.ISDN))
        val cable = w.cableBetween(tv, server)!!
        assertEquals("then tap the narrow cable", TutorialFocus.Cables(listOf(cable)), t.focus(CableType.DSL))
        assertTrue(w.upgrade(cable, CableType.DSL))
        assertTrue(t.update())
        assertEquals(TutorialStep.PING, t.step)
    }

    @Test
    fun layingDslStraightAwayAlsoPasses() {
        val t = playTo(TutorialStep.BANDWIDTH)
        assertTrue(t.world.connect(t.tv!!, t.streamServer!!, CableType.DSL))
        assertTrue(t.update())
        assertEquals(TutorialStep.PING, t.step)
    }

    @Test
    fun pingStepNeedsFiberAcrossTheRiver() {
        val t = playTo(TutorialStep.PING)
        val w = t.world
        assertEquals(Tutorial.FIBER_WEEK, w.week)
        assertEquals(2010, w.year)
        assertTrue(CableType.FIBER in w.unlockedCables)
        val server = t.gameServer!!
        assertEquals(Service.GAMING, server.service)
        assertEquals(TutorialFocus.Drag(t.pc, server), t.focus(CableType.DSL))
        assertNull(t.tooSlowPingMs())

        assertTrue(w.connect(t.pc, server, CableType.DSL))
        run(t, 1f)
        assertEquals("DSL is too slow for gaming over this distance", TutorialStep.PING, t.step)
        val ping = t.tooSlowPingMs()!!
        assertTrue("$ping ms", ping > Service.GAMING.maxPingMs!!)
        assertEquals(TutorialFocus.CableButton(CableType.FIBER), t.focus(CableType.DSL))
        val cable = w.cableBetween(t.pc, server)!!
        assertEquals(TutorialFocus.Cables(listOf(cable)), t.focus(CableType.FIBER))

        assertTrue(w.upgrade(cable, CableType.COAX))
        run(t, 1f)
        assertEquals("coax is not fast enough either", TutorialStep.PING, t.step)
        assertTrue(w.upgrade(cable, CableType.FIBER))
        assertTrue(t.update())
        assertEquals(TutorialStep.OVERLOAD, t.step)
        assertNull(t.tooSlowPingMs())
    }

    @Test
    fun overloadStepShowsTheRingAndEndsWhenThePcIsServed() {
        val t = playTo(TutorialStep.OVERLOAD)
        val w = t.world
        val pc = t.newPc!!
        assertEquals(World.Tuning.MAX_PENDING, pc.pending.size)
        run(t, 1f)
        assertTrue("the ring starts to fill at once", pc.overload > 0f)
        assertEquals(TutorialFocus.Drag(pc, t.mailServer), t.focus(CableType.DSL))
        assertTrue("the new PC reaches the mail server", w.connectError(pc, t.mailServer, CableType.DSL) == null)
        assertTrue(w.connect(pc, t.mailServer, CableType.DSL))
        assertFalse("its requests have not left yet", t.update())
        run(t, 1f)
        assertEquals(TutorialStep.PORTS, t.step)
    }

    @Test
    fun portsStepJoinsTwoPcsToTheServerThroughOneRouter() {
        val t = playTo(TutorialStep.PORTS)
        val w = t.world
        val pcs = t.officePcs
        assertEquals(2, pcs.size)
        assertTrue(pcs.all { it.device == Device.PC && w.ports(it) == 0 && it.maxPorts == 2 })
        assertEquals("the first PC uses both of its ports by now", t.pc.maxPorts, w.ports(t.pc))
        assertEquals("the first PC cannot take a third cable", ConnectError.TO_PORTS_FULL, w.connectError(pcs[0], t.pc, CableType.DSL))
        assertEquals(TutorialFocus.RouterButton, t.focus(CableType.DSL))
        assertEquals(pcs, t.placeNear())
        // Chaining the PCs to the server works, but fills the first PC's ports: the step waits for a shared router.
        assertTrue(w.connect(pcs[0], t.mailServer, CableType.DSL))
        assertTrue(w.connect(pcs[1], pcs[0], CableType.DSL))
        run(t, 0.5f)
        assertEquals(TutorialStep.PORTS, t.step)
        assertEquals(ConnectError.FROM_PORTS_FULL, w.connectError(pcs[0], t.pc, CableType.DSL))
        w.removeCable(w.cableBetween(pcs[1], pcs[0])!!)
        val at = w.nearestFree(Cell(pcs[1].cellX + 1, pcs[1].cellY))!!
        val r = w.placeRouter(at.x, at.y)!!
        assertEquals(TutorialFocus.Nodes(listOf(r) + pcs + t.mailServer), t.focus(CableType.DSL))
        assertTrue(w.connect(pcs[0], r, CableType.DSL))
        assertNull("one PC is not enough", t.sharedRouter())
        assertTrue(w.connect(pcs[1], r, CableType.DSL))
        assertTrue(t.update())
        assertEquals(TutorialStep.DONE, t.step)
        assertTrue(t.finished)
        assertFalse(t.skipped)
        assertEquals(Tutorial.STEPS, t.number)
        assertEquals(TutorialFocus.None, t.focus(CableType.DSL))
        assertFalse(t.update())
    }

    @Test
    fun ringNeverClosesInTheTutorial() {
        val t = playTo(TutorialStep.OVERLOAD)
        run(t, World.Tuning.OVERLOAD_SECONDS * 2)
        assertEquals(TutorialStep.OVERLOAD, t.step)
        assertEquals(World.Tuning.GUIDED_MAX_OVERLOAD, t.newPc!!.overload)
        assertFalse(t.world.gameOver)
    }

    @Test
    fun skipEndsAtOnce() {
        val t = playTo(TutorialStep.PLACE_ROUTER)
        t.skip()
        assertTrue(t.finished)
        assertTrue(t.skipped)
        assertEquals(TutorialFocus.None, t.focus(CableType.ISDN))
        assertFalse(t.update())
    }

    @Test
    fun onlyGuidedWorldsChangeErasOnDemand() {
        val w = World(seed = 1L, spawnInitialNodes = false)
        assertTrue(runCatching { w.advanceEra(3) }.isFailure)
        val g = World(seed = 1L, spawnInitialNodes = false, guided = true)
        g.advanceEra(3)
        assertEquals(3, g.week)
        assertEquals(listOf(CableType.DSL, CableType.COAX), g.lastNews!!.cables)
        assertEquals(listOf(Device.LAPTOP, Device.CONSOLE), g.lastNews!!.devices)
        assertTrue(g.lastNews!!.servers.isEmpty())
        g.advanceEra(2)
        assertEquals("never back in time", 3, g.week)
    }
}
