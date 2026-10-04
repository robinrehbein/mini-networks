package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sound cues: deliveries per service, overload warning once per overload, the rising alarm at 50 % and 80 % of the ring,
 * game over, week chime, silent world swap.
 */
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
    fun alarmRisesOnceAtHalfAndAtCriticalRing() {
        val w = dryWorld()
        val phone = w.addClient(Device.PHONE, 1, 1)
        w.addServer(Service.CALL, 8, 8)
        val cues = SoundCues()
        cues.poll(w)
        repeat(World.Tuning.MAX_PENDING) { phone.pending.addLast(Service.CALL) }
        phone.overload = 0.49f
        assertEquals(listOf(SoundCue.OverloadStarted), cues.poll(w))
        phone.overload = 0.5f
        assertEquals(listOf(SoundCue.OverloadHalf), cues.poll(w))
        phone.overload = 0.7f
        assertEquals("once per crossing", emptyList<SoundCue>(), cues.poll(w))
        phone.overload = 0.8f
        assertEquals(listOf(SoundCue.OverloadCritical), cues.poll(w))
        // A stage sounds again only after the ring has fully emptied, however far it drained in between.
        phone.overload = 0.75f
        assertEquals(emptyList<SoundCue>(), cues.poll(w))
        phone.overload = 0.85f
        assertEquals(emptyList<SoundCue>(), cues.poll(w))
        phone.overload = 0.05f
        assertEquals(emptyList<SoundCue>(), cues.poll(w))
        phone.overload = 0.85f
        assertEquals("a half-fixed device refilling stays quiet", emptyList<SoundCue>(), cues.poll(w))
        phone.pending.clear()
        phone.overload = 0f
        assertEquals(emptyList<SoundCue>(), cues.poll(w))
        // A jump straight past both stages sounds only the higher one.
        phone.overload = 0.9f
        assertEquals(listOf(SoundCue.OverloadCritical), cues.poll(w))
    }

    @Test
    fun aQueueHoveringAtTheLimitSoundsTheCriticalAlarmOnce() {
        val w = dryWorld()
        val phone = w.addClient(Device.PHONE, 1, 1)
        w.addServer(Service.CALL, 8, 8)
        val cues = SoundCues()
        cues.poll(w)
        repeat(World.Tuning.MAX_PENDING) { phone.pending.addLast(Service.CALL) }
        phone.overload = 0.79f
        cues.poll(w)
        val heard = ArrayList<SoundCue>()
        // Requests are served and come back one at a time: 6, 5, 6, 5, ... while the ring swings around 80 %.
        repeat(40) { i ->
            if (i % 2 == 0) {
                phone.pending.addLast(Service.CALL)
                phone.overload = 0.85f
            } else {
                phone.pending.removeFirst()
                phone.overload = 0.7f
            }
            heard += cues.poll(w)
        }
        assertEquals(listOf(SoundCue.OverloadCritical), heard.filter { it == SoundCue.OverloadCritical || it == SoundCue.OverloadHalf })
    }

    @Test
    fun aSecondDeviceBelowTheWorstStageStaysQuiet() {
        val w = dryWorld()
        val a = w.addClient(Device.PHONE, 1, 1)
        val b = w.addClient(Device.PHONE, 1, 4)
        w.addServer(Service.CALL, 8, 8)
        val cues = SoundCues()
        cues.poll(w)
        repeat(World.Tuning.MAX_PENDING) { a.pending.addLast(Service.CALL); b.pending.addLast(Service.CALL) }
        a.overload = 0.85f
        b.overload = 0.1f
        assertEquals(listOf(SoundCue.OverloadCritical), cues.poll(w))
        b.overload = 0.55f
        assertEquals("b passing 50 % while a is past 80 % is silent", emptyList<SoundCue>(), cues.poll(w))
        b.overload = 0.85f
        assertEquals(emptyList<SoundCue>(), cues.poll(w))
        a.pending.clear()
        a.overload = 0f
        b.pending.clear()
        b.overload = 0f
        assertEquals(emptyList<SoundCue>(), cues.poll(w))
        repeat(World.Tuning.MAX_PENDING) { b.pending.addLast(Service.CALL) }
        b.overload = 0.6f
        assertEquals("both emptied: the alarm rises again", listOf(SoundCue.OverloadHalf), cues.poll(w))
    }

    @Test
    fun alarmFollowsTheRingWhileTheWorldRuns() {
        val w = dryWorld()
        val phone = w.addClient(Device.PHONE, 1, 1)
        w.addServer(Service.CALL, 8, 8)
        val cues = SoundCues()
        cues.poll(w)
        val heard = ArrayList<SoundCue>()
        var s = 0
        while (!w.gameOver && s++ < 60 * 200) {
            while (phone.pending.size < World.Tuning.MAX_PENDING) phone.pending.addLast(Service.CALL)
            w.update(step)
            heard += cues.poll(w)
        }
        assertTrue("the unserved phone ends the game", w.gameOver)
        val alarm = heard.filter { it != SoundCue.NewWeek && it !is SoundCue.Delivered }
        assertEquals(listOf(SoundCue.OverloadStarted, SoundCue.OverloadHalf, SoundCue.OverloadCritical, SoundCue.GameOver), alarm)
        assertEquals("game over sounds once", emptyList<SoundCue>(), cues.poll(w))
    }

    @Test
    fun aRevivedGameRaisesTheAlarmAgain() {
        val w = dryWorld()
        val phone = w.addClient(Device.PHONE, 1, 1)
        w.addServer(Service.CALL, 8, 8)
        val cues = SoundCues()
        cues.poll(w)
        repeat(World.Tuning.MAX_PENDING) { phone.pending.addLast(Service.CALL) }
        fun fill(): List<SoundCue> {
            val heard = ArrayList<SoundCue>()
            for (ring in floatArrayOf(0.55f, 0.85f)) {
                phone.overload = ring
                heard += cues.poll(w)
            }
            phone.overload = 0.99999f
            w.update(step)
            assertTrue(w.gameOver)
            heard += cues.poll(w)
            return heard
        }
        assertEquals("the start is swallowed by the louder half-ring alarm of the same poll",
            listOf(SoundCue.OverloadHalf, SoundCue.OverloadCritical, SoundCue.GameOver), fill())
        assertTrue(w.continueAfterGameOver())
        assertEquals("the requests still wait: the ring warns as it starts again", listOf(SoundCue.OverloadStarted), cues.poll(w))
        assertEquals("the second run rises all the way again", listOf(SoundCue.OverloadHalf, SoundCue.OverloadCritical, SoundCue.GameOver), fill())
    }

    @Test
    fun noAlarmWhereAFullRingCannotEndTheGame() {
        for (w in listOf(World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false, mode = GameMode.ENDLESS),
            World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false, guided = true))) {
            for (row in w.water) row.fill(false)
            w.incidentsEnabled = false
            val phone = w.addClient(Device.PHONE, 1, 1)
            w.addServer(Service.CALL, 8, 8)
            val cues = SoundCues()
            cues.poll(w)
            repeat(World.Tuning.MAX_PENDING) { phone.pending.addLast(Service.CALL) }
            phone.overload = 0.6f
            assertEquals(listOf(SoundCue.OverloadStarted), cues.poll(w))
            phone.overload = 0.9f
            assertEquals(emptyList<SoundCue>(), cues.poll(w))
        }
    }

    @Test
    fun gameOverSilencesTheRestOfItsPoll() {
        val w = dryWorld()
        val b = w.addClient(Device.PHONE, 1, 4)
        val a = w.addClient(Device.PHONE, 1, 1)
        w.addServer(Service.CALL, 8, 8)
        val cues = SoundCues()
        cues.poll(w)
        repeat(World.Tuning.MAX_PENDING) { a.pending.addLast(Service.CALL); b.pending.addLast(Service.CALL) }
        a.overload = 0.99999f
        assertEquals("one warning per poll, the most urgent", listOf(SoundCue.OverloadCritical), cues.poll(w))
        b.overload = 0.79999f
        w.update(step)
        assertTrue("b passed 80 % in the same step", b.overload >= SoundCues.CRITICAL_RING)
        assertTrue(w.gameOver)
        assertEquals(listOf(SoundCue.GameOver), cues.poll(w))
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
        busy.nodes.first { it === phone }.overload = 0.9f
        assertEquals("a loaded game that is already overloaded and in week 5 makes no sound", emptyList<SoundCue>(), cues.poll(busy))
        assertEquals(emptyList<SoundCue>(), cues.poll(busy))
        val lost = dryWorld()
        val p = lost.addClient(Device.PHONE, 1, 1)
        lost.addServer(Service.CALL, 8, 8)
        repeat(World.Tuning.MAX_PENDING) { p.pending.addLast(Service.CALL) }
        p.overload = 0.9999f
        lost.update(step)
        assertTrue(lost.gameOver)
        assertEquals("a lost game shown again does not sound game over", emptyList<SoundCue>(), cues.poll(lost))
    }
}
