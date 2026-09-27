package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sound cues: deliveries per service, overload warning once per overload, week chime, silent world swap. */
@OptIn(DebugApi::class)
class SoundCuesTest {

    private val step = 1f / 60f

    private fun dryWorld() = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false).also { w ->
        for (row in w.water) row.fill(false)
        w.grant(200)
        w.incidentsEnabled = false
    }

    /** Runs [w] for [seconds] and collects every cue. */
    private fun run(w: World, cues: SoundCues, seconds: Float): List<SoundCue> {
        val out = ArrayList<SoundCue>()
        repeat((seconds * 60).toInt()) {
            w.update(step)
            out += cues.poll(w)
        }
        return out
    }

    @Test
    fun deliveryPlaysOncePerPacketWithItsService() {
        val w = dryWorld()
        val phone = w.addClient(Device.PHONE, 1, 1)
        val server = w.addServer(Service.CALL, 4, 1)
        w.connect(phone, server, CableType.ISDN)
        val cues = SoundCues()
        assertEquals(emptyList<SoundCue>(), cues.poll(w))
        phone.pending.addLast(Service.CALL)
        val heard = ArrayList<SoundCue>()
        var s = 0
        while (w.delivered == 0 && s++ < 60 * 20) {
            w.update(step)
            heard += cues.poll(w)
        }
        heard += run(w, cues, 0.5f)
        assertEquals("the server hit is silent, the delivery plucks once", listOf(SoundCue.Delivered(Service.CALL)), heard.filterIsInstance<SoundCue.Delivered>())
    }

    @Test
    fun simultaneousDeliveriesCollapseToOneCuePerService() {
        val w = dryWorld()
        val cues = SoundCues()
        val mail = w.addServer(Service.MAIL, 6, 1)
        val call = w.addServer(Service.CALL, 6, 5)
        val clients = (0 until 4).map { i -> w.addClient(if (i < 2) Device.PC else Device.PHONE, 1, 1 + 2 * i) }
        clients.forEachIndexed { i, c -> assertTrue(w.connect(c, if (i < 2) mail else call, CableType.ISDN)) }
        cues.poll(w)
        clients.forEachIndexed { i, c -> repeat(2) { c.pending.addLast(if (i < 2) Service.MAIL else Service.CALL) } }
        val perPoll = ArrayList<List<SoundCue>>()
        repeat(60 * 10) {
            w.update(step)
            perPoll += cues.poll(w)
        }
        assertTrue(w.delivered >= 4)
        for (poll in perPoll) {
            val d = poll.filterIsInstance<SoundCue.Delivered>()
            assertEquals("no service twice in one poll", d.distinct(), d)
            assertEquals("services in fixed order", d.sortedBy { it.service.ordinal }, d)
        }
    }

    @Test
    fun overloadWarnsOnceUntilTheRingHasEmptied() {
        val w = dryWorld()
        val phone = w.addClient(Device.PHONE, 1, 1)
        w.addServer(Service.CALL, 8, 8)
        val cues = SoundCues()
        cues.poll(w)
        repeat(World.Tuning.MAX_PENDING - 1) { phone.pending.addLast(Service.CALL) }
        assertTrue(run(w, cues, 0.2f).none { it == SoundCue.OverloadStarted })
        phone.pending.addLast(Service.CALL)
        w.update(step)
        assertEquals(listOf(SoundCue.OverloadStarted), cues.poll(w))
        assertEquals(emptyList<SoundCue>(), cues.poll(w))
        // Hovering around the limit does not warn again while the ring is not empty.
        phone.overload = 0.3f
        phone.pending.removeLast()
        assertEquals(emptyList<SoundCue>(), cues.poll(w))
        phone.pending.addLast(Service.CALL)
        assertEquals(emptyList<SoundCue>(), cues.poll(w))
        // Once the ring has emptied, the next overload warns again.
        phone.pending.removeLast()
        phone.overload = 0f
        assertEquals(emptyList<SoundCue>(), cues.poll(w))
        phone.pending.addLast(Service.CALL)
        assertEquals(listOf(SoundCue.OverloadStarted), cues.poll(w))
    }

    @Test
    fun newWeekChimesOnce() {
        val w = dryWorld()
        val cues = SoundCues()
        cues.poll(w)
        val heard = run(w, cues, World.Tuning.WEEK_SECONDS + 1f)
        assertEquals(1, heard.count { it == SoundCue.NewWeek })
        assertTrue("the reward choice holds the week", w.rewardOffer != null)
        w.chooseReward(0)
        assertTrue(run(w, cues, 1f).none { it == SoundCue.NewWeek })
    }

    @Test
    fun anotherWorldIsTakenInSilently() {
        val busy = dryWorld()
        val phone = busy.addClient(Device.PHONE, 1, 1)
        busy.addServer(Service.CALL, 8, 8)
        repeat(World.Tuning.MAX_PENDING) { phone.pending.addLast(Service.CALL) }
        busy.jumpToWeek(5)
        busy.update(step)
        val cues = SoundCues()
        cues.poll(dryWorld())
        assertEquals("a loaded game that is already overloaded and in week 5 makes no sound", emptyList<SoundCue>(), cues.poll(busy))
        assertEquals(emptyList<SoundCue>(), cues.poll(busy))
    }
}
