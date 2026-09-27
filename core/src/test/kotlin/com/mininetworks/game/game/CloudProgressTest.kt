package com.mininetworks.game.game

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** docs/TOP100.md C6: the cloud save round-trips, survives damage and merges two devices without losing progress. */
class CloudProgressTest {

    private val phone = CloudProgress(
        savedAt = 1_000,
        stats = PlayerStats(delivered = 900, bestGame = 320, cablesLaid = 80, sceneries = setOf("river_town", "metropolis"), richest = 250),
        best = mapOf("river_town" to 320, "metropolis" to 140),
        streakDay = 20_000, streakCurrent = 4, streakBest = 6,
        dailyDay = 20_000, dailyBest = 55,
    )
    private val tablet = CloudProgress(
        savedAt = 2_000,
        stats = PlayerStats(delivered = 700, bestGame = 410, fiberLaid = 9, sceneries = setOf("river_town", "island"), secondChances = 2),
        best = mapOf("river_town" to 410, "island" to 90, "endless_river_town" to 800),
        streakDay = 20_001, streakCurrent = 1, streakBest = 3,
        dailyDay = 20_001, dailyBest = 31,
    )

    @Test
    fun roundTripsAndRejectsDamage() {
        assertEquals(phone, CloudProgress.decode(phone.encode()))
        assertNull(CloudProgress.decode(null))
        assertNull(CloudProgress.decode(ByteArray(0)))
        assertNull(CloudProgress.decode("{".toByteArray()))
        assertNull(CloudProgress.decode("[1,2]".toByteArray()))
        val random = Random(5)
        val bytes = phone.encode()
        repeat(500) {
            val broken = bytes.copyOf(random.nextInt(bytes.size))
            if (broken.isNotEmpty()) broken[random.nextInt(broken.size)] = random.nextInt(256).toByte()
            // Never throws; whatever loads has no negative values.
            CloudProgress.decode(broken)?.let { p ->
                assertTrue(p.best.values.all { it > 0 })
                assertTrue(p.streakCurrent >= 0 && p.streakBest >= 0 && p.dailyBest >= 0)
            }
        }
    }

    @Test
    fun unknownFieldsFromANewerVersionLoadButAreNeverOverwritten() {
        val newer = """{"version":2,"stats":{"delivered":5,"futureStat":3},"best":{"river_town":7},"futureField":true}"""
        val p = CloudProgress.decode(newer.toByteArray())!!
        assertEquals(5L, p.stats.delivered)
        assertEquals(mapOf("river_town" to 7), p.best)
        assertTrue(p.newerFormat)
        assertFalse(phone.newerFormat)
    }

    @Test
    fun mergeKeepsTheMostProgressOfBothDevices() {
        val m = CloudProgress.merge(phone, tablet)
        assertEquals(900L, m.stats.delivered)
        assertEquals(410, m.stats.bestGame)
        assertEquals(80, m.stats.cablesLaid)
        assertEquals(9, m.stats.fiberLaid)
        assertEquals(2, m.stats.secondChances)
        assertEquals(250, m.stats.richest)
        assertEquals(setOf("river_town", "metropolis", "island"), m.stats.sceneries)
        assertEquals(mapOf("endless_river_town" to 800, "island" to 90, "metropolis" to 140, "river_town" to 410), m.best)
        assertEquals(2_000L, m.savedAt)
        // Everything reached on either device stays reached.
        assertTrue(Achievements.unlocked(m.stats).containsAll(Achievements.unlocked(phone.stats) + Achievements.unlocked(tablet.stats)))
    }

    @Test
    fun streakAndDailyBestComeFromTheNewerDay() {
        val m = CloudProgress.merge(phone, tablet)
        assertEquals("the tablet played the later day", DailyStreak(20_001, 1, 6), m.streak)
        assertEquals(20_001L, m.dailyDay)
        assertEquals(31, m.dailyBest)
        // The same day on both: the longer streak and the higher daily best.
        val sameDay = tablet.copy(streakDay = 20_000, streakCurrent = 2, dailyDay = 20_000, dailyBest = 70)
        val s = CloudProgress.merge(phone, sameDay)
        assertEquals(DailyStreak(20_000, 4, 6), s.streak)
        assertEquals(70, s.dailyBest)
        // Nothing on one side: the other side as it is.
        val empty = CloudProgress()
        assertEquals(phone.streak, CloudProgress.merge(empty, phone).streak)
        assertEquals(55, CloudProgress.merge(phone, empty).dailyBest)
    }

    @Test
    fun mergeIsSymmetricIdempotentAndAssociative() {
        val third = CloudProgress(stats = PlayerStats(repairs = 7, dailyDone = 3), best = mapOf("future" to 12), streakDay = 19_990, streakCurrent = 9, streakBest = 9)
        val ab = CloudProgress.merge(phone, tablet)
        assertEquals(ab, CloudProgress.merge(tablet, phone))
        assertEquals(ab, CloudProgress.merge(ab, ab))
        assertEquals(ab, CloudProgress.merge(ab, phone))
        assertEquals(CloudProgress.merge(ab, third), CloudProgress.merge(phone, CloudProgress.merge(tablet, third)))
        assertEquals("the old streak of 9 stays the record", 9, CloudProgress.merge(ab, third).streakBest)
        assertArrayEquals(ab.encode(), CloudProgress.decode(ab.encode())!!.encode())
    }

    @Test
    fun everyStatsFieldIsMerged() {
        // Each field alone on one side survives the merge, whatever side it is on (also fields added later: the merge
        // works on the JSON form of PlayerStats).
        val full = PlayerStats(
            delivered = 1, bestGame = 2, bestWeek = 3, cablesLaid = 4, fiberLaid = 5, cableUpgrades = 6, routersPlaced = 7,
            accessPoints = 8, cellTowers = 9, serverUpgrades = 10, dataCenters = 11, repairs = 12, gamesFinished = 13,
            weeksPlayed = 14, sceneries = setOf("a"), streamingDelivered = 15, dailyDone = 16, bestStreak = 17,
            endlessBestWeek = 18, creativeGames = 19, secondChances = 20, richest = 21,
        )
        assertEquals(full, CloudProgress.mergeStats(full, PlayerStats()))
        assertEquals(full, CloudProgress.mergeStats(PlayerStats(), full))
        for (m in Metric.entries) assertTrue(m.name, full.value(m) > 0)
    }

    @Test
    fun ofTakesTheLocalStores() {
        val p = CloudProgress.of(phone.stats, phone.best, DailyStreak(20_000, 4, 6), dailyDay = 20_000, dailyBest = 55, savedAt = 1_000)
        assertEquals(phone, p)
        val noDaily = CloudProgress.of(PlayerStats(), emptyMap(), DailyStreak(), dailyDay = 20_000, dailyBest = 0, savedAt = 0)
        assertNull(noDaily.dailyDay)
    }
}
