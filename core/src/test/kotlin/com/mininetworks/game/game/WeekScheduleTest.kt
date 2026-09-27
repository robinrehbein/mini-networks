package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/TOP100.md G3: every week up to at least [LAST_WEEK] brings something new (tech, device, service or event). */
@OptIn(DebugApi::class)
class WeekScheduleTest {

    @Test
    fun everyWeekUpToTwelveBringsSomethingNew() {
        for (week in 1..LAST_WEEK) {
            val c = WeekSchedule.content(week)
            assertFalse("week $week brings nothing new: $c", c.isEmpty)
        }
    }

    /** Stronger than G3 asks: up to week 12, every week brings a technology, a device or a service, not only an event. */
    @Test
    fun everyWeekUpToTwelveBringsTechDeviceOrService() {
        for (week in 1..LAST_WEEK) {
            val c = WeekSchedule.content(week)
            assertTrue(
                "week $week: $c",
                c.cables.isNotEmpty() || c.devices.isNotEmpty() || c.radios.isNotEmpty() || c.server != null || c.randomServer,
            )
        }
    }

    @Test
    fun scheduleFollowsTheUnlockWeeks() {
        assertEquals(listOf(CableType.FIBER), WeekSchedule.cables(CableType.FIBER.unlockWeek))
        assertEquals(listOf(Device.CAMERA), WeekSchedule.devices(Device.CAMERA.unlockWeek))
        assertEquals(listOf(RadioType.WLAN), WeekSchedule.radios(RadioType.WLAN.unlockWeek))
        assertSame(Service.CLOUD_BACKUP, WeekSchedule.firstServer(Service.CLOUD_BACKUP.serverWeek))
        // A due first server takes the week; random servers fill every second week after that.
        assertFalse(WeekSchedule.randomServer(World.Tuning.RANDOM_SERVERS_FROM))
        assertTrue(WeekSchedule.randomServer(12))
        assertFalse(WeekSchedule.randomServer(13))
        assertNull(WeekSchedule.firstServer(12))
        assertTrue(WeekSchedule.moreIncidents(Incidents.FIRST_WEEK))
        assertFalse(WeekSchedule.moreIncidents(Incidents.FIRST_WEEK + 1))
    }

    /** The real game announces news at every week change of the first scenery, weeks 2 to [LAST_WEEK]. */
    @Test
    fun theFirstSceneryAnnouncesNewsEveryWeek() {
        val w = World(Scenarios.RIVER_TOWN, seed = 7L)
        assertEquals(setOf(Service.MAIL, Service.CALL), w.availableServices)
        for (week in 2..LAST_WEEK) {
            w.rewardOffer?.let { w.chooseReward(0) }
            val servers = w.nodes.count { it.kind == NodeKind.SERVER }
            w.advanceToNextWeek()
            assertEquals(week, w.week)
            val news = requireNotNull(w.lastNews) { "no news in week $week" }
            assertEquals("news of week $week is fresh", w.time, w.lastNewsTime, 0f)
            val c = WeekSchedule.content(week)
            assertEquals(c.cables, news.cables)
            assertEquals(c.devices, news.devices)
            assertEquals(c.radios, news.radios)
            val newServer = c.server != null || c.randomServer
            assertEquals("server in week $week", newServer, news.servers.isNotEmpty())
            assertEquals(servers + if (newServer) 1 else 0, w.nodes.count { it.kind == NodeKind.SERVER })
        }
    }

    companion object {
        const val LAST_WEEK = 12
    }
}
