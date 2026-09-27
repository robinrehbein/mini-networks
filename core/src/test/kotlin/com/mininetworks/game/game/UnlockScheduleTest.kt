package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The era thread (docs/PLAN.md 3.2): inventions are spread so that every calendar week brings something new. */
@OptIn(DebugApi::class)
class UnlockScheduleTest {

    /** What each era week invents: cables, radios, devices and the first server of a service. */
    private fun unlocksOf(week: Int): List<Any> =
        CableType.entries.filter { it.unlockWeek == week } +
            RadioType.entries.filter { it.unlockWeek == week } +
            Device.entries.filter { it.unlockWeek == week } +
            Service.entries.filter { it.serverWeek == week }

    @Test
    fun scheduleMatchesThePlan() {
        val expected = mapOf(
            1 to listOf(CableType.ISDN, Device.PC, Device.PHONE, Service.MAIL, Service.CALL),
            2 to listOf(CableType.DSL, Device.LAPTOP),
            3 to listOf(CableType.COAX, Device.CONSOLE, Service.GAMING),
            4 to listOf(Device.TV, Service.STREAMING),
            5 to listOf(RadioType.WLAN, Device.SMARTPHONE),
            6 to listOf(CableType.FIBER, Device.TABLET),
            7 to listOf(RadioType.CELL, Service.VIDEO_CALL),
            8 to listOf(Device.WATCH),
            9 to listOf(Device.CAMERA, Service.CAMERA_UPLOAD),
            10 to listOf(Service.CLOUD_BACKUP),
            11 to listOf(Device.SMART_HOME),
        )
        for (week in 1..World.Tuning.ERA_YEARS.size + 5) {
            assertEquals("week $week", expected[week].orEmpty(), unlocksOf(week))
        }
    }

    @Test
    fun everyInventionComesWithinTheCalendar() {
        val lastWeek = World.Tuning.ERA_YEARS.size
        assertTrue(CableType.entries.all { it.unlockWeek <= lastWeek })
        assertTrue(RadioType.entries.all { it.unlockWeek <= lastWeek })
        assertTrue(Device.entries.all { it.unlockWeek <= lastWeek })
        assertTrue(Service.entries.all { it.serverWeek <= lastWeek })
    }

    @Test
    fun servicesArriveWithADeviceThatWantsThem() {
        for (s in Service.entries) {
            assertTrue("$s is wanted by a device invented by then", Device.entries.any { s in it.services && it.unlockWeek <= s.serverWeek })
        }
    }

    @Test
    fun everyWeekUpToTodayBringsNews() {
        val w = World(cols = 24, rows = 12, seed = 1L, spawnInitialNodes = false)
        for (row in w.water) row.fill(false)
        for (week in 2..World.Tuning.ERA_YEARS.size) {
            w.rewardOffer?.let { w.chooseReward(0) }
            val before = w.lastNews
            w.advanceToNextWeek()
            assertEquals(week, w.week)
            val news = w.lastNews!!
            assertNotSame("week $week brings something new", before, news)
            assertEquals(World.Tuning.ERA_YEARS[week - 1], news.year)
        }
    }
}
