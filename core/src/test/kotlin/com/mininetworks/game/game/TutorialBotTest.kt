package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/TOP100.md B1/B2: a tutorial bot plays the tutorial like a new player, with a human reaction time per step
 * (reading the bubble, finding the spot, dragging), and measures the game time from the first launch to the first
 * delivered packet. It also makes the typical mistakes the steps are built around (ISDN for the streaming TV, DSL
 * across the river for gaming, a third cable at a PC with 2 ports) and fixes them the way the tutorial explains.
 */
class TutorialBotTest {

    private val step = 1f / 60f

    /** Advances [seconds] of game time frame by frame, letting the tutorial check its goal every frame. */
    private fun wait(t: Tutorial, seconds: Float) = repeat((seconds * 60).toInt()) { t.world.update(step); t.update() }

    /** Game time from the start until [done] holds, frame by frame; fails after [limit] seconds. */
    private fun until(t: Tutorial, limit: Float = 120f, done: () -> Boolean): Float {
        val start = t.world.time
        while (!done()) {
            t.world.update(step)
            t.update()
            check(t.world.time - start < limit) { "not reached within $limit s" }
        }
        return t.world.time
    }

    @Test
    fun firstPacketIsDeliveredWithinThirtySecondsOfTheFirstLaunch() {
        val t = Tutorial.start()
        assertEquals("a mail is already waiting at the PC", listOf(Service.MAIL), t.pc.pending.toList())
        // A slow reader: the first bubble plus orientation, then a deliberate drag.
        wait(t, READ_SECONDS + DRAG_SECONDS)
        assertTrue(t.world.connect(t.pc, t.mailServer, CableType.ISDN))
        val first = until(t) { t.world.delivered >= 1 }
        println("TutorialBotTest: first packet delivered after %.1f s of game time (target ≤ %.0f s)".format(first, TARGET_SECONDS))
        assertTrue("first delivery after $first s", first <= TARGET_SECONDS)
        // The packet itself needs only a few seconds once the cable is there: the start state is not the bottleneck.
        assertTrue("round trip ${first - READ_SECONDS - DRAG_SECONDS} s", first - READ_SECONDS - DRAG_SECONDS < 12f)
    }

    @Test
    fun botPlaysAllSixStepsIncludingTheMistakesTheyTeachWith() {
        val t = Tutorial.start()
        val w = t.world
        assertEquals(6, Tutorial.STEPS)
        wait(t, READ_SECONDS)
        assertTrue(w.connect(t.pc, t.mailServer, CableType.ISDN))
        until(t) { t.step == TutorialStep.PLACE_ROUTER }

        wait(t, READ_SECONDS)
        val at = w.nearestFree(Cell(t.phones[0].cellX - 2, t.phones[0].cellY + 1))!!
        val router = w.placeRouter(at.x, at.y)!!
        for (n in t.phones + t.callServer!!) assertTrue(w.connect(n, router, CableType.ISDN))
        until(t) { t.step == TutorialStep.BANDWIDTH }

        // Bandwidth by playing: ISDN first, the TV's streams are too narrow, then DSL.
        wait(t, READ_SECONDS)
        assertTrue(w.connect(t.tv!!, t.streamServer!!, CableType.ISDN))
        wait(t, 2f)
        assertTrue("the bot sees the too-narrow badge", t.tooNarrow())
        assertEquals(TutorialFocus.CableButton(CableType.DSL), t.focus(CableType.ISDN))
        assertTrue(w.upgrade(w.cableBetween(t.tv!!, t.streamServer!!)!!, CableType.DSL))
        until(t) { t.step == TutorialStep.PING }

        // Ping by playing: DSL across the river is too slow, fiber fixes it.
        wait(t, READ_SECONDS)
        assertTrue(w.connect(t.pc, t.gameServer!!, CableType.DSL))
        wait(t, 2f)
        assertTrue("the bot sees the ping", t.tooSlowPingMs()!! > Service.GAMING.maxPingMs!!)
        assertTrue(w.upgrade(w.cableBetween(t.pc, t.gameServer!!)!!, CableType.FIBER))
        until(t) { t.step == TutorialStep.OVERLOAD }

        wait(t, READ_SECONDS)
        assertTrue(w.connect(t.newPc!!, t.mailServer, CableType.DSL))
        until(t) { t.step == TutorialStep.PORTS }

        // Ports by playing: the first PC is full (mail and gaming), so a new PC cannot hang off it; a router can.
        wait(t, READ_SECONDS)
        val pcs = t.officePcs
        assertEquals(ConnectError.TO_PORTS_FULL, w.connectError(pcs[0], t.pc, CableType.DSL))
        val spot = w.nearestFree(Cell(pcs[1].cellX + 1, pcs[1].cellY))!!
        val shared = w.placeRouter(spot.x, spot.y)!!
        for (n in pcs + t.mailServer) assertTrue(w.connect(n, shared, CableType.DSL))
        until(t) { t.finished }
        assertTrue("budget left: ${w.budget}", w.budget >= 0)
        println("TutorialBotTest: tutorial finished after %.1f s of game time, %d packets delivered".format(w.time, w.delivered))
    }

    private companion object {
        /** docs/TOP100.md section 4: first delivery at most 30 s after the first launch. */
        const val TARGET_SECONDS = 30f
        /** Reading a bubble (about 15 words at 150 words per minute) and finding the spot. */
        const val READ_SECONDS = 10f
        const val DRAG_SECONDS = 2f
    }
}
