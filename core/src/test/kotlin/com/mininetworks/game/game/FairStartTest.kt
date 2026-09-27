package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fair start ([World.Tuning.EARLY_WEEKS]), the calendar ([World.Tuning.ERA_YEARS]) and busy servers. */
@OptIn(DebugApi::class)
class FairStartTest {

    /** True if a direct cable from [c] to some server of every served service of its device fits the early rule. */
    private fun reachable(w: World, c: Node): Boolean = c.device!!.services.filter { it in w.availableServices }.all { s ->
        w.nodes.filter { it.kind == NodeKind.SERVER && it.service == s }.any { server ->
            val layout = w.planLayout(c.cell, server.cell)
            w.unlockedCables.any { t ->
                t.capacity >= s.bandwidth && w.cableCost(layout, t) <= World.Tuning.EARLY_CABLE_BUDGET &&
                    (s.maxPingMs == null || 2f * (layout.length * t.msPerCell + World.Tuning.ROUTER_MS) <= s.maxPingMs!! * World.Tuning.EARLY_PING_SHARE)
            }
        }
    }

    @Test
    fun earlyClientsCanBeServed() {
        for (scenario in Scenarios.all) for (seed in 1L..6L) {
            val w = World(scenario, seed = seed)
            w.incidentsEnabled = false
            val seen = HashSet<Node>()
            while (w.weeksPlayed <= World.Tuning.EARLY_WEEKS) {
                if (w.rewardOffer != null) w.chooseReward(0)
                for (n in w.nodes) if (n.kind == NodeKind.CLIENT && seen.add(n)) {
                    assertTrue("${scenario.id} seed $seed: $n cannot be reached", reachable(w, n))
                }
                // Keep the game running without building anything.
                for (n in w.nodes) n.pending.clear()
                w.update(0.25f)
            }
            assertTrue("clients appeared", seen.size > 3)
        }
    }

    @Test
    fun overloadFillsSlowerInTheFirstWeeks() {
        fun fill(week: Int): Float {
            val w = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false)
            w.incidentsEnabled = false
            w.jumpToWeek(week)
            val pc = w.addClient(Device.PC, 3, 3)
            w.addServer(Service.MAIL, 1, 1)
            repeat(World.Tuning.MAX_PENDING) { pc.pending.addLast(Service.MAIL) }
            pc.requestTimer = 1000f
            w.update(World.Tuning.OVERLOAD_SECONDS / 2f)
            return pc.overload
        }
        assertEquals(0.25f, fill(1), 1e-3f)
        assertEquals(0.25f, fill(World.Tuning.EARLY_WEEKS), 1e-3f)
        assertEquals(0.5f, fill(World.Tuning.EARLY_WEEKS + 1), 1e-3f)
    }

    @Test
    fun calendarStopsAtToday() {
        val w = World(seed = 2L)
        assertEquals(1995, w.year)
        w.jumpToWeek(8)
        assertEquals(2016, w.year)
        w.jumpToWeek(World.Tuning.ERA_YEARS.size)
        assertEquals(2026, w.year)
        w.jumpToWeek(25)
        assertEquals("the calendar never runs past today", 2026, w.year)
        val future = World(Scenarios.FUTURE, seed = 2L)
        future.jumpToWeek(25)
        assertEquals("the future scenery moves through the 2030s only", 2040, future.year)
    }

    @Test
    fun requestsWaitingAtABusyServerAreCounted() {
        val w = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false)
        w.incidentsEnabled = false
        w.jumpToWeek(CableType.FIBER.unlockWeek)
        w.grant(500)
        val mail = w.addServer(Service.MAIL, 1, 4)
        val router = w.addRouter(3, 4)
        w.connect(router, mail, CableType.FIBER)
        val pcs = listOf(2 to 2, 4 to 2, 2 to 6, 4 to 6, 5 to 4).map { (x, y) -> w.addClient(Device.PC, x, y) }
        for (pc in pcs) w.connect(pc, router, CableType.FIBER)
        assertEquals(0, w.waitingAt(mail))
        var most = 0
        repeat(600) {
            for (pc in pcs) if (pc.pending.size < 3) pc.pending.addLast(Service.MAIL)
            w.update(1f / 60f)
            most = maxOf(most, w.waitingAt(mail))
        }
        assertTrue("a level-1 server cannot keep up with five PCs", most > 0)
    }
}
