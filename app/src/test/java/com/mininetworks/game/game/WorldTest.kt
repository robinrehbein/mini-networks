package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldTest {

    /** Empty world without river on the left half, so tests control every node. */
    private fun world() = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false).also { w ->
        for (y in 0 until w.rows) for (x in 0 until 6) assertFalse("test area must be dry", w.water[y][x])
    }

    @Test
    fun routeFindsServerOfMatchingTypeThroughRouter() {
        val w = world()
        val client = w.addNodeAt(NodeKind.CLIENT, DataType.VIDEO, 1, 1)
        val router = w.addNodeAt(NodeKind.ROUTER, null, 3, 1)
        val mail = w.addNodeAt(NodeKind.SERVER, DataType.MAIL, 3, 4)
        val video = w.addNodeAt(NodeKind.SERVER, DataType.VIDEO, 5, 1)
        assertTrue(w.connect(client, router))
        assertTrue(w.connect(router, mail))
        assertNull("no video server reachable yet", w.routeFor(client))
        assertTrue(w.connect(router, video))
        assertEquals(listOf(client, router, video), w.routeFor(client))
    }

    @Test
    fun serversOfOtherTypeDoNotForward() {
        val w = world()
        val client = w.addNodeAt(NodeKind.CLIENT, DataType.VIDEO, 1, 1)
        val mail = w.addNodeAt(NodeKind.SERVER, DataType.MAIL, 3, 1)
        val video = w.addNodeAt(NodeKind.SERVER, DataType.VIDEO, 5, 1)
        w.connect(client, mail)
        w.connect(mail, video)
        assertNull(w.routeFor(client))
    }

    @Test
    fun cablesCostBudgetAndRespectPorts() {
        val w = world()
        val budget = w.cableBudget
        val c = w.addNodeAt(NodeKind.CLIENT, DataType.MAIL, 1, 1)
        val a = w.addNodeAt(NodeKind.ROUTER, null, 1, 3)
        val b = w.addNodeAt(NodeKind.ROUTER, null, 3, 3)
        val d = w.addNodeAt(NodeKind.ROUTER, null, 4, 1)
        assertTrue(w.connect(c, a))
        assertEquals(budget - 2, w.cableBudget)
        assertTrue(w.connect(c, b))
        assertNotNull("client has only 2 ports", w.connectError(c, d))
        w.removeCable(w.cableBetween(c, a)!!)
        assertEquals(budget - 2, w.cableBudget)
        assertNull(w.connectError(c, d))
    }

    @Test
    fun packetsGetDeliveredOverTime() {
        val w = world()
        val client = w.addNodeAt(NodeKind.CLIENT, DataType.MAIL, 1, 1)
        val server = w.addNodeAt(NodeKind.SERVER, DataType.MAIL, 4, 1)
        w.connect(client, server)
        repeat(60 * 20) { w.update(1f / 60f) }
        assertTrue("expected deliveries, got ${w.delivered}", w.delivered > 0)
        assertFalse(w.gameOver)
    }

    @Test
    fun unconnectedClientEventuallyOverloads() {
        val w = world()
        w.addNodeAt(NodeKind.SERVER, DataType.MAIL, 4, 1)
        w.addNodeAt(NodeKind.CLIENT, DataType.MAIL, 1, 1)
        repeat(60 * 180) { if (!w.gameOver) w.update(1f / 60f) }
        assertTrue(w.gameOver)
        assertEquals(NodeKind.CLIENT, w.failedNode?.kind)
    }

    @Test
    fun waterMakesCablesMoreExpensive() {
        val w = World(seed = 3L, spawnInitialNodes = false)
        val y = 4
        val riverX = (0 until w.cols).first { w.water[y][it] }
        val left = w.addNodeAt(NodeKind.ROUTER, null, riverX - 2, y)
        val right = w.addNodeAt(NodeKind.ROUTER, null, riverX + 2, y)
        assertEquals(4 + (World.Tuning.WATER_COST_FACTOR - 1), w.cableCost(left, right))
    }
}
