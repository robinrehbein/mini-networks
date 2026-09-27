package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The daily challenge (docs/TOP100.md C1): date to seed, rule rotation, the rules themselves and the local streak. */
@OptIn(DebugApi::class)
class DailyChallengeTest {

    /** 2026-09-27 00:00 UTC. */
    private val sept27 = 1_790_467_200_000L

    @Test
    fun theDayIsTheUtcDateSoEveryoneGetsTheSameChallenge() {
        val day = DailyChallenge.dayOf(sept27)
        assertEquals(20_723L, day)
        // Any moment of that UTC day, wherever the phone is, gives the same challenge.
        assertEquals(DailyChallenge.at(sept27), DailyChallenge.at(sept27 + 86_399_999L))
        assertEquals(day + 1, DailyChallenge.dayOf(sept27 + 86_400_000L))
        // Before 1970 the day still counts down, not towards zero.
        assertEquals(-1L, DailyChallenge.dayOf(-1L))
    }

    @Test
    fun seedsAreFixedPerDayAndDifferBetweenDays() {
        // Pinned, so a code change can never give players of one day different maps.
        assertEquals(DailyChallenge.seedOf(20_723L), DailyChallenge.of(20_723L).seed)
        assertEquals(DailyChallenge.of(20_723L), DailyChallenge.of(20_723L))
        val seeds = (20_000L until 20_400L).map(DailyChallenge::seedOf).toSet()
        assertEquals("400 days, 400 seeds", 400, seeds.size)
        assertNotEquals(DailyChallenge.seedOf(1L), DailyChallenge.seedOf(2L))
    }

    @Test
    fun sameDaySameMapAndStartForEveryPlayer() {
        val c = DailyChallenge.of(20_723L)
        val a = World(c.scenario, seed = c.seed, daily = c)
        val b = World(c.scenario, seed = c.seed, daily = c)
        assertEquals(Save.encode(a), Save.encode(b))
        repeat(60 * 20) { a.update(1f / 60f); b.update(1f / 60f) }
        assertEquals("untouched, both games run the same", Save.encode(a), Save.encode(b))
    }

    @Test
    fun rulesRotateSoAWeekShowsEveryRuleOnce() {
        val week = (20_720L until 20_720L + DailyRule.entries.size).map { DailyChallenge.of(it).rule }
        assertEquals(DailyRule.entries.toSet(), week.toSet())
        for (d in 20_000L until 20_100L) {
            assertNotEquals("the rule changes every day", DailyChallenge.of(d).rule, DailyChallenge.of(d + 1).rule)
            assertTrue(DailyChallenge.of(d).scenario in DailyChallenge.SCENARIOS)
        }
        // Rule and scenery pair up differently from one week to the next.
        val pairs = (20_000L until 20_000L + 21).map { DailyChallenge.of(it).let { c -> c.rule to c.scenario.id } }.toSet()
        assertEquals(21, pairs.size)
    }

    private fun daily(rule: DailyRule, scenario: Scenario = Scenarios.RIVER_TOWN) =
        World(scenario, seed = 5L, daily = DailyChallenge(1L, 5L, scenario, rule))

    @Test
    fun tightBudgetStartsPoorerAndPaysLess() {
        val normal = World(Scenarios.RIVER_TOWN, seed = 5L)
        val tight = daily(DailyRule.TIGHT_BUDGET)
        assertEquals((World.Tuning.START_BUDGET * World.Tuning.TIGHT_START_SHARE).toInt(), tight.budget)
        val before = tight.budget
        tight.advanceToNextWeek()
        assertEquals(before + World.Tuning.TIGHT_WEEK_BUDGET, tight.budget)
        val n = normal.budget
        normal.advanceToNextWeek()
        assertEquals(n + World.Tuning.WEEK_BUDGET, normal.budget)
    }

    @Test
    fun fiberDayInventsEveryCableFromTheStart() {
        val w = daily(DailyRule.FIBER_DAY)
        assertEquals(1, w.week)
        assertEquals(CableType.entries, w.unlockedCables)
        val a = w.addRouter(1, 1)
        val b = w.addRouter(1, 3)
        assertTrue(w.connect(a, b, CableType.FIBER))
        assertEquals(listOf(CableType.ISDN), World(Scenarios.RIVER_TOWN, seed = 5L).unlockedCables)
    }

    @Test
    fun fewRoutersAndWideLand() {
        assertEquals(World.Tuning.FEW_ROUTERS, daily(DailyRule.FEW_ROUTERS).routersAvailable)
        val wide = daily(DailyRule.WIDE_LAND).unlocked
        val normal = World(Scenarios.RIVER_TOWN, seed = 5L).unlocked
        assertEquals(normal.width + 2 * World.Tuning.WIDE_RINGS, wide.width)
        assertEquals(normal.height + 2 * World.Tuning.WIDE_RINGS, wide.height)
    }

    /** Runs [w] for [seconds] with every queue emptied each step (so nothing overloads), picking the first reward. */
    private fun calm(w: World, seconds: Int, each: () -> Unit = {}) {
        w.incidentsEnabled = false
        repeat(60 * seconds) {
            w.update(1f / 60f)
            w.rewardOffer?.let { w.chooseReward(0) }
            each()
            for (n in w.nodes) n.pending.clear()
        }
    }

    @Test
    fun rushHourAsksMoreOften() {
        fun requests(w: World): Int {
            val phone = w.addClient(Device.PHONE, w.unlocked.left + 1, w.unlocked.top + 1)
            var count = 0
            calm(w, 90) { count += phone.pending.size }
            return count
        }
        val normal = requests(World(Scenarios.RIVER_TOWN, seed = 5L))
        val rush = requests(daily(DailyRule.RUSH_HOUR))
        assertTrue("rush $rush vs normal $normal", rush > normal * 1.15f)
    }

    @Test
    fun crowdBringsMoreDevices() {
        fun clients(w: World): Int {
            calm(w, 90)
            return w.nodes.count { it.kind == NodeKind.CLIENT }
        }
        val calmCount = clients(World(Scenarios.RIVER_TOWN, seed = 5L))
        val crowd = clients(daily(DailyRule.CROWD))
        assertTrue("crowd $crowd vs normal $calmCount", crowd > calmCount)
    }

    @Test
    fun crowdFillsOverloadRingsSlower() {
        fun ringAfter(w: World): Float {
            val phone = w.addClient(Device.PHONE, 1, 1)
            w.jumpToWeek(w.week + World.Tuning.EARLY_WEEKS) // past the early grace
            repeat(60 * 5) {
                while (phone.pending.size < World.Tuning.MAX_PENDING) phone.pending.addLast(Service.CALL)
                w.update(1f / 60f)
            }
            return phone.overload
        }
        val normal = ringAfter(World(Scenarios.RIVER_TOWN, seed = 5L, spawnInitialNodes = false))
        val crowd = ringAfter(World(Scenarios.RIVER_TOWN, seed = 5L, spawnInitialNodes = false, daily = DailyChallenge(1L, 5L, Scenarios.RIVER_TOWN, DailyRule.CROWD)))
        assertEquals(normal / World.Tuning.CROWD_OVERLOAD_SLOWDOWN, crowd, 0.01f)
    }

    @Test
    fun stormBringsIncidentsWeeksEarlier() {
        fun firstIncidentWeek(w: World): Int? {
            val a = w.addRouter(w.unlocked.left + 1, w.unlocked.top + 1)
            val b = w.addRouter(w.unlocked.left + 1, w.unlocked.top + 5)
            w.connect(a, b, CableType.ISDN)
            repeat(60 * 45 * 4) {
                w.update(1f / 60f)
                w.rewardOffer?.let { w.chooseReward(0) }
                for (n in w.nodes) n.pending.clear()
                if (w.incidents.isNotEmpty()) return w.weeksPlayed
            }
            return null
        }
        val normal = firstIncidentWeek(World(Scenarios.RIVER_TOWN, seed = 9L, spawnInitialNodes = false))
        val storm = firstIncidentWeek(World(Scenarios.RIVER_TOWN, seed = 9L, spawnInitialNodes = false, daily = DailyChallenge(1L, 9L, Scenarios.RIVER_TOWN, DailyRule.STORM)))
        assertEquals(Incidents.FIRST_WEEK, normal)
        assertEquals(Incidents.FIRST_WEEK - World.Tuning.STORM_WEEKS_AHEAD, storm)
    }

    @Test
    fun dailyGamesSaveAndLoadWithTheirRule() {
        val c = DailyChallenge.of(20_724L)
        val w = World(c.scenario, seed = c.seed, daily = c)
        repeat(60 * 10) { w.update(1f / 60f) }
        val back = Save.decode(Save.encode(w))!!
        assertEquals(c, back.daily)
        assertEquals(c.rule, back.rule)
        assertEquals(Save.encode(w), Save.encode(back))
        // A save that claims a daily of another scenery or seed is damaged and does not load.
        val forged = w.snapshot().copy(dailyDay = c.day + 1)
        assertEquals(null, Save.decode(kotlinx.serialization.json.Json.encodeToString(WorldSnapshot.serializer(), forged)))
    }

    @Test
    fun streakCountsDaysInARowAndKeepsTheBest() {
        var s = DailyStreak()
        assertEquals(0, s.currentOn(100))
        s = s.record(100)
        assertEquals(DailyStreak(100, 1, 1), s)
        assertEquals("the same day twice counts once", s, s.record(100))
        s = s.record(101).record(102)
        assertEquals(3, s.current)
        assertEquals(3, s.currentOn(102))
        assertEquals("still alive the next day, before playing", 3, s.currentOn(103))
        assertEquals("broken after a day without", 0, s.currentOn(104))
        assertEquals("an older day changes nothing", s, s.record(99))
        s = s.record(105)
        assertEquals(DailyStreak(105, 1, 3), s)
        assertTrue(s.counted(105))
        assertFalse(s.counted(106))
        repeat(5) { s = s.record(106L + it) }
        assertEquals(6, s.current)
        assertEquals(6, s.best)
    }
}
