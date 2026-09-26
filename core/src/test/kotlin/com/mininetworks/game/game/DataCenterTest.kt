package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(DebugApi::class)
class DataCenterTest {

    /** Empty world with the river removed and plenty of budget. */
    private fun dryWorld() = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false).also { w ->
        for (row in w.water) row.fill(false)
        w.grant(500)
    }

    private fun World.upgradeTo(n: Node, level: Int) {
        while (n.level < level) assertTrue("upgrade to ${n.level + 1}", upgradeServer(n))
    }

    @Test
    fun tierFourCoversTwoByTwoCells() {
        val w = dryWorld()
        val server = w.addServer(Service.MAIL, 5, 5)
        w.upgradeTo(server, 3)
        assertEquals(listOf(server.cell), server.footprint)
        assertFalse(server.isDataCenter)
        val budget = w.budget
        assertTrue(w.upgradeServer(server))
        assertEquals(World.Tuning.DATA_CENTER_LEVEL, server.level)
        assertTrue(server.isDataCenter)
        assertEquals(budget - World.Tuning.SERVER_UPGRADE_COST[2], w.budget)
        assertEquals(listOf(Cell(5, 5), Cell(6, 5), Cell(5, 6), Cell(6, 6)), server.footprint)
        assertEquals(Vec2(6f, 6f), server.footprintCenter)
        assertEquals("cables still attach at the server's own cell", Cell(5, 5).center, server.center)
        assertEquals(8f, w.serverRate(server))
        assertEquals(ServerUpgradeError.MAX_LEVEL, w.serverUpgradeError(server))
    }

    @Test
    fun blockedNeighbourPicksAnotherBlock() {
        val w = dryWorld()
        val server = w.addServer(Service.MAIL, 5, 5)
        w.addRouter(6, 5)
        w.upgradeTo(server, 4)
        assertEquals("server ends up top-right", listOf(Cell(4, 5), Cell(5, 5), Cell(4, 6), Cell(5, 6)), server.footprint)
    }

    @Test
    fun noFreeBlockIsAnUpgradeError() {
        val w = dryWorld()
        val server = w.addServer(Service.MAIL, 0, 0)
        w.upgradeTo(server, 3)
        w.addRouter(1, 1)
        val budget = w.budget
        assertEquals(ServerUpgradeError.NO_SPACE, w.serverUpgradeError(server))
        assertFalse(w.upgradeServer(server))
        assertEquals(3, server.level)
        assertEquals(budget, w.budget)
        assertEquals(listOf(server.cell), server.footprint)
    }

    @Test
    fun waterBlocksTheDataCenter() {
        val w = dryWorld()
        val server = w.addServer(Service.MAIL, 0, 0)
        w.upgradeTo(server, 3)
        w.water[1][0] = true
        assertEquals(ServerUpgradeError.NO_SPACE, w.serverUpgradeError(server))
        w.water[1][0] = false
        assertNull(w.serverUpgradeError(server))
    }

    @Test
    fun noSpaceWinsOverMissingBudgetAndBlocksVouchers() {
        val w = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false)
        for (row in w.water) row.fill(false)
        w.grant(8 + 16 - w.budget)
        val server = w.addServer(Service.MAIL, 0, 0)
        w.upgradeTo(server, 3)
        assertEquals(ServerUpgradeError.NO_BUDGET, w.serverUpgradeError(server))
        assertTrue("a voucher could still pay tier 4", Reward.SERVER_VOUCHER in w.eligibleRewards())
        w.addRouter(1, 0)
        assertEquals(ServerUpgradeError.NO_SPACE, w.serverUpgradeError(server))
        assertFalse("no server can grow any more", Reward.SERVER_VOUCHER in w.eligibleRewards())
    }

    @Test
    fun footprintIsOccupied() {
        val w = dryWorld()
        val server = w.addServer(Service.MAIL, 5, 5)
        w.upgradeTo(server, 4)
        for (c in server.footprint) {
            assertFalse(w.isFree(c.x, c.y))
            assertSame(server, w.nodeAt(c))
            assertSame("tapping any part selects the data center", server, w.nodeNear(c.center))
        }
        assertNull("no router on the data center", w.placeRouter(6, 6))
        assertTrue(w.isFree(7, 5))
    }

    @Test
    fun dataCenterHasEightPorts() {
        val w = dryWorld()
        val server = w.addServer(Service.MAIL, 7, 4)
        val cells = listOf(1 to 1, 3 to 1, 5 to 1, 7 to 1, 9 to 1, 11 to 1, 13 to 1, 1 to 8, 3 to 8)
        val routers = cells.map { (x, y) -> w.addRouter(x, y) }
        routers.take(NodeKind.SERVER.maxPorts).forEach { assertTrue(w.connect(it, server, CableType.ISDN)) }
        assertNotNull("tier 1 has 4 ports", w.connectError(routers[4], server, CableType.ISDN))
        w.upgradeTo(server, 4)
        assertEquals(World.Tuning.DATA_CENTER_PORTS, server.maxPorts)
        routers.drop(4).take(4).forEach { assertTrue(w.connect(it, server, CableType.ISDN)) }
        assertEquals(8, w.ports(server))
        assertNotNull("all 8 ports used", w.connectError(routers[8], server, CableType.ISDN))
    }

    @Test
    fun dataCenterServesMoreRequests() {
        /** Responses delivered between second 5 (first round trips are back) and second 15, under more demand than 8/s. */
        fun deliveredAt(level: Int): Int {
            val w = dryWorld()
            w.jumpToWeek(CableType.FIBER.unlockWeek)
            val server = w.addServer(Service.MAIL, 7, 7)
            val router = w.addRouter(7, 6)
            w.connect(router, server, CableType.FIBER)
            listOf(4 to 1, 6 to 1, 8 to 1, 10 to 1, 5 to 3).forEach { (x, y) ->
                val pc = w.addClient(Device.PC, x, y)
                w.connect(pc, router, CableType.FIBER)
                repeat(30) { pc.pending.addLast(Service.MAIL) }
            }
            w.upgradeTo(server, level)
            repeat(60 * 5) { w.update(1f / 60f) }
            val before = w.delivered
            repeat(60 * 10) { w.update(1f / 60f) }
            assertFalse(w.gameOver)
            return w.delivered - before
        }
        val tier3 = deliveredAt(3)
        val tier4 = deliveredAt(4)
        assertTrue("tier 3 serves up to 5/s, got $tier3 in 10 s", tier3 in 40..55)
        assertTrue("tier 4 serves up to 8/s, got $tier4 in 10 s", tier4 in 65..88)
    }
}
