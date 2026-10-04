package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Queue pressure: how close a device is to overload, and the pre-warning a few requests before its ring starts. */
@OptIn(DebugApi::class)
class QueuePressureTest {

    private fun dryWorld() = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false).also { w ->
        for (row in w.water) row.fill(false)
        w.incidentsEnabled = false
    }

    @Test
    fun pressureRisesWithTheQueueAndCapsAtTheLimit() {
        val pc = dryWorld().addClient(Device.PC, 1, 1)
        assertEquals(0f, pc.pressure, 0f)
        repeat(World.Tuning.MAX_PENDING / 2) { pc.pending.addLast(Service.MAIL) }
        assertEquals(0.5f, pc.pressure, 1e-6f)
        repeat(World.Tuning.MAX_PENDING) { pc.pending.addLast(Service.MAIL) }
        assertEquals(1f, pc.pressure, 0f)
    }

    @Test
    fun nearOverloadStartsTwoRequestsBeforeTheRing() {
        assertEquals(World.Tuning.MAX_PENDING - 2, World.Tuning.PREWARN_PENDING)
        val pc = dryWorld().addClient(Device.PC, 1, 1)
        repeat(World.Tuning.PREWARN_PENDING - 1) { pc.pending.addLast(Service.MAIL) }
        assertFalse(pc.nearOverload)
        pc.pending.addLast(Service.MAIL)
        assertTrue(pc.nearOverload)
        repeat(2) { pc.pending.addLast(Service.MAIL) }
        assertTrue(pc.nearOverload)
    }

    @Test
    fun serversNeverWarn() {
        val server = dryWorld().addServer(Service.MAIL, 4, 1)
        repeat(World.Tuning.MAX_PENDING) { server.pending.addLast(Service.MAIL) }
        assertEquals(0f, server.pressure, 0f)
        assertFalse(server.nearOverload)
    }
}
