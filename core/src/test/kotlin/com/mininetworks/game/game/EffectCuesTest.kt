package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cues the renderers animate: when a cable was laid and where packets arrived. */
@OptIn(DebugApi::class)
class EffectCuesTest {

    private val step = 1f / 60f

    private fun dryWorld() = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false).also { w ->
        for (row in w.water) row.fill(false)
        w.grant(200)
    }

    @Test
    fun cableRemembersWhenItWasLaid() {
        val w = dryWorld()
        val phone = w.addClient(Device.PHONE, 1, 1)
        val server = w.addServer(Service.CALL, 4, 1)
        repeat(90) { w.update(step) }
        assertTrue(w.connect(phone, server, CableType.ISDN))
        assertEquals(w.time, w.cables.single().builtAt, 0f)
    }

    @Test
    fun loadedCablesCountAsLaidLongAgo() {
        val w = dryWorld()
        w.connect(w.addClient(Device.PHONE, 1, 1), w.addServer(Service.CALL, 4, 1), CableType.ISDN)
        val loaded = Save.decode(Save.encode(w))!!
        assertEquals(Float.NEGATIVE_INFINITY, loaded.cables.single().builtAt, 0f)
    }

    @Test
    fun arrivalsMarkServerHitsAndDeliveries() {
        val w = dryWorld()
        val phone = w.addClient(Device.PHONE, 1, 1)
        val server = w.addServer(Service.CALL, 4, 1)
        w.connect(phone, server, CableType.ISDN)
        phone.pending.addLast(Service.CALL)
        var hit: Arrival? = null
        var s = 0
        while (w.delivered == 0 && s++ < 60 * 20) {
            w.update(step)
            if (hit == null) hit = w.arrivals.firstOrNull()
        }
        assertSame("the request arrives at the server first", server, hit!!.node)
        assertFalse(hit.isResponse)
        assertEquals(Service.CALL, hit.service)
        val delivery = w.arrivals.last()
        assertSame(phone, delivery.node)
        assertTrue(delivery.isResponse)
        assertEquals(w.time, delivery.time, 0f)
    }

    @Test
    fun arrivalsFadeOutAfterASecond() {
        val w = dryWorld()
        val phone = w.addClient(Device.PHONE, 1, 1)
        val server = w.addServer(Service.CALL, 3, 1)
        w.connect(phone, server, CableType.ISDN)
        phone.pending.addLast(Service.CALL)
        var s = 0
        while (w.delivered == 0 && s++ < 60 * 20) w.update(step)
        assertTrue(w.arrivals.isNotEmpty())
        phone.requestTimer = 1000f
        repeat((World.Tuning.ARRIVAL_SECONDS / step).toInt() + 2) { w.update(step) }
        assertTrue(w.arrivals.isEmpty())
    }

    @Test
    fun arrivalsAreNotSaved() {
        val w = dryWorld()
        val phone = w.addClient(Device.PHONE, 1, 1)
        w.connect(phone, w.addServer(Service.CALL, 3, 1), CableType.ISDN)
        phone.pending.addLast(Service.CALL)
        var s = 0
        while (w.arrivals.isEmpty() && s++ < 60 * 20) w.update(step)
        assertTrue(w.arrivals.isNotEmpty())
        assertTrue(Save.decode(Save.encode(w))!!.arrivals.isEmpty())
    }
}
