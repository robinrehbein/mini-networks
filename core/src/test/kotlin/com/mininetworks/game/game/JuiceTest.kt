package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/TOP100.md B3: the model records what the effects need, and the growth recorder keeps the time-lapse. */
@OptIn(DebugApi::class)
class JuiceTest {

    /** A guided world: nothing spawns on its own and the game cannot be lost, so only the test changes the network. */
    private fun world() = World(seed = 5L, spawnInitialNodes = false, guided = true).also { w ->
        for (row in w.water) row.fill(false)
        w.advanceEra(CableType.FIBER.unlockWeek)
        w.grant(5000)
    }

    @Test
    fun upgradesAreTimedForTheirEffect() {
        val w = world()
        val pc = w.addClient(Device.PC, 10, 7)
        val mail = w.addServer(Service.MAIL, 14, 9)
        assertTrue(w.connect(pc, mail, CableType.ISDN))
        val cable = w.cableBetween(pc, mail)!!
        assertEquals(Float.NEGATIVE_INFINITY, cable.upgradedAt, 0f)
        assertEquals(Float.NEGATIVE_INFINITY, mail.upgradedAt, 0f)
        repeat(90) { w.update(1f / 60f) }
        assertTrue(w.upgrade(cable, CableType.DSL))
        assertEquals(w.time, cable.upgradedAt, 0f)
        repeat(30) { w.update(1f / 60f) }
        assertTrue(w.upgradeServer(mail))
        assertEquals(w.time, mail.upgradedAt, 0f)
        assertTrue("the cable was upgraded before the server", cable.upgradedAt < mail.upgradedAt)
        val ap = w.addRadio(RadioType.WLAN, 5, 5)
        assertTrue(w.upgradeTo5Ghz(ap))
        assertEquals(w.time, ap.upgradedAt, 0f)
    }

    @Test
    fun recorderKeepsAFrameForEveryChangeAndTheEndState() {
        val w = world()
        val r = GrowthRecorder()
        r.sample(w)
        assertEquals("the empty start", 1, r.frames.size)
        repeat(60) { w.update(1f / 60f); r.sample(w) }
        assertEquals("nothing changed, nothing recorded", 1, r.frames.size)
        val pc = w.addClient(Device.PC, 10, 7)
        val mail = w.addServer(Service.MAIL, 14, 9)
        repeat(3 * 60) { w.update(1f / 60f); r.sample(w) }
        assertTrue(w.connect(pc, mail, CableType.ISDN))
        repeat(30) { w.update(1f / 60f); r.sample(w) }
        assertTrue(w.upgrade(w.cableBetween(pc, mail)!!, CableType.FIBER))
        r.sample(w)
        val last = r.frames.last()
        assertEquals(2, last.nodes.size)
        assertEquals(listOf(CableType.FIBER), last.cables.map { it.type })
        assertTrue("frames are in time order", r.frames.zipWithNext().all { (a, b) -> a.time <= b.time })
        assertEquals("start, nodes, cable; the upgrade within 2 s replaces the cable frame", 3, r.frames.size)
        assertEquals(w.cableBetween(pc, mail)!!.layout.waypoints, last.cables.single().points)
    }

    @Test
    fun longGamesAreThinnedButKeepStartAndEnd() {
        val w = world()
        val r = GrowthRecorder()
        r.sample(w)
        val first = r.frames.first()
        var x = 0
        repeat(400) {
            w.addRouter(1 + x % 30, 1 + (x / 30) % 18).also { x++ }
            repeat(3 * 60) { _ -> w.update(1f / 60f) }
            r.sample(w)
        }
        assertTrue(r.frames.size <= GrowthRecorder.MAX_FRAMES)
        assertTrue(r.frames.size >= GrowthRecorder.MAX_FRAMES / 2)
        assertEquals("the start stays", first, r.frames.first())
        assertEquals("the end is the last state", w.nodes.size, r.frames.last().nodes.size)
        val other = World(seed = 6L)
        r.sample(other)
        assertEquals("a new world starts a new recording", 1, r.frames.size)
    }
}
