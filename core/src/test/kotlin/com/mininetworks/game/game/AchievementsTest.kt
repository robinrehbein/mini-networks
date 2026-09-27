package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Achievements and missions (docs/TOP100.md C2) and the cosmetics they unlock (C5). */
@OptIn(DebugApi::class)
class AchievementsTest {

    @Test
    fun atLeastThirtyWithUniqueIdsAndSensibleTargets() {
        val all = Achievements.all
        assertTrue("${all.size} achievements", all.size >= 30)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertTrue(all.all { it.target > 0 && it.id.matches(Regex("[a-z0-9_]+")) })
        // Tiers of one metric come in rising order, so the screen reads as a list of missions.
        for ((_, tiers) in all.groupBy { it.metric }) assertEquals(tiers.map { it.target }.sorted(), tiers.map { it.target })
        assertTrue("every metric is used", Metric.entries.all { m -> all.any { it.metric == m } })
    }

    @Test
    fun progressIsClampedAndUnlockingFollowsTheTarget() {
        val a = Achievements.byId("delivered_100")!!
        assertEquals(0L, a.progress(PlayerStats()))
        assertEquals(42L, a.progress(PlayerStats(delivered = 42)))
        assertFalse(a.reached(PlayerStats(delivered = 99)))
        assertTrue(a.reached(PlayerStats(delivered = 100)))
        assertEquals(100L, a.progress(PlayerStats(delivered = 5000)))
        assertEquals(setOf("delivered_1", "delivered_100", "delivered_1000"), Achievements.unlocked(PlayerStats(delivered = 1000)))
    }

    @Test
    fun statsSurviveJsonAndDamagedDataGivesEmptyStats() {
        val s = PlayerStats(delivered = 12_345, bestGame = 321, sceneries = setOf("river_town", "metropolis"), bestStreak = 4, richest = 99)
        assertEquals(s, PlayerStats.decode(s.encode()))
        assertEquals(PlayerStats(), PlayerStats.decode(null))
        assertEquals(PlayerStats(), PlayerStats.decode(""))
        assertEquals(PlayerStats(), PlayerStats.decode("{\"delivered\":"))
        assertEquals(PlayerStats(), PlayerStats.decode("[1,2,3]"))
        assertEquals("unknown fields from a newer version are ignored", PlayerStats(delivered = 7), PlayerStats.decode("{\"delivered\":7,\"future\":1}"))
    }

    /** A small cabled world where a phone's calls go through. */
    private fun cabledWorld(mode: GameMode = GameMode.NORMAL): Pair<World, Node> {
        val w = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false, mode = mode)
        w.incidentsEnabled = false
        val phone = w.addClient(Device.PHONE, 1, 1)
        val call = w.addServer(Service.CALL, 4, 1)
        assertTrue(w.connect(phone, call, CableType.ISDN))
        return w to phone
    }

    private fun play(w: World, phone: Node, seconds: Int) = repeat(60 * seconds) {
        if (phone.pending.size < 2) phone.pending.addLast(Service.CALL)
        w.update(1f / 60f)
        w.rewardOffer?.let { w.chooseReward(0) }
    }

    @Test
    fun trackerCountsAGameAndReportsUnlocksOnce() {
        val (w, phone) = cabledWorld()
        val t = AchievementTracker()
        val started = t.begin(w, fresh = true)
        assertTrue(started.isEmpty())
        assertEquals(setOf(w.scenario.id), t.stats.sceneries)
        assertEquals("the cable laid before begin() does not count", 0, t.stats.cablesLaid)
        val unlocked = ArrayList<String>()
        repeat(60 * 10) {
            if (phone.pending.size < 2) phone.pending.addLast(Service.CALL)
            w.update(1f / 60f)
            t.observe(w).mapTo(unlocked) { it.id }
        }
        assertTrue(w.delivered > 0)
        assertEquals(listOf("delivered_1"), unlocked)
        assertEquals(w.delivered.toLong(), t.stats.delivered)
        assertEquals(w.delivered, t.stats.bestGame)
        assertTrue(t.dirty)
        t.saved()
        assertFalse(t.dirty)
        assertTrue("nothing new, nothing reported", t.observe(w).isEmpty())
        assertFalse(t.dirty)
        val r = w.placeRouter(1, 4)
        assertNotNull(r)
        w.connect(r!!, phone, CableType.ISDN)
        t.observe(w)
        assertEquals(1, t.stats.routersPlaced)
        assertEquals(1, t.stats.cablesLaid)
        val before = t.stats.gamesFinished
        assertTrue(t.gameOver(w).any { it.id == "games_1" })
        assertEquals(before + 1, t.stats.gamesFinished)
    }

    @Test
    fun aContinuedGameOnlyCountsWhatIsNew() {
        val (w, phone) = cabledWorld()
        play(w, phone, 20)
        val restored = Save.decode(Save.encode(w))!!
        val t = AchievementTracker(PlayerStats(delivered = 500, bestGame = 400))
        t.begin(restored, fresh = false)
        assertTrue("a save does not count its scenery again", t.stats.sceneries.isEmpty())
        t.observe(restored)
        assertEquals("the delivered packets of the save were counted when they happened", 500L, t.stats.delivered)
        assertEquals(400, t.stats.bestGame)
        val phone2 = restored.nodes.first { it.kind == NodeKind.CLIENT }
        play(restored, phone2, 10)
        t.observe(restored)
        assertEquals(500L + restored.delivered - w.delivered, t.stats.delivered)
    }

    @Test
    fun endlessCountsTotalsButNotOneGameRecordsAndCreativeCountsOnlyAsPlayed() {
        val (e, phone) = cabledWorld(GameMode.ENDLESS)
        val t = AchievementTracker()
        t.begin(e, fresh = true)
        play(e, phone, 100)
        t.observe(e)
        assertTrue(t.stats.delivered > 0)
        assertEquals(0, t.stats.bestGame)
        assertEquals(0, t.stats.bestWeek)
        assertEquals(e.weeksPlayed, t.stats.endlessBestWeek)
        assertEquals(e.weeksPlayed - 1, t.stats.weeksPlayed)
        assertTrue("an endless game never counts as finished", t.gameOver(e).none { it.id == "games_1" })
        assertEquals(0, t.stats.gamesFinished)

        val (c, cPhone) = cabledWorld(GameMode.CREATIVE)
        val u = AchievementTracker()
        assertEquals(listOf("creative_1"), u.begin(c, fresh = true).map { it.id })
        c.placeRouter(1, 5)
        play(c, cPhone, 20)
        u.observe(c)
        assertEquals(PlayerStats(creativeGames = 1), u.stats)
    }

    @Test
    fun theTutorialCountsForNothing() {
        val tutorial = Tutorial.start()
        val t = AchievementTracker()
        t.begin(tutorial.world, fresh = true)
        tutorial.world.connect(tutorial.pc, tutorial.mailServer, CableType.ISDN)
        repeat(60 * 10) { tutorial.world.update(1f / 60f); t.observe(tutorial.world) }
        assertEquals(PlayerStats(), t.stats)
    }

    @Test
    fun dailyStreakAndSecondChanceCount() {
        val t = AchievementTracker()
        var streak = DailyStreak()
        val unlocked = ArrayList<String>()
        for (day in 10L..16L) {
            streak = streak.record(day)
            t.dailyCounted(streak).mapTo(unlocked) { it.id }
        }
        assertEquals(listOf("daily_1", "streak_3", "streak_7"), unlocked)
        assertEquals(7, t.stats.dailyDone)
        assertEquals(7, t.stats.bestStreak)
        assertEquals(listOf("second_chance_1"), t.secondChance().map { it.id })
    }

    @Test
    fun cosmeticsAreUnlockedByAchievementsOnly() {
        val ids = Achievements.all.map { it.id }.toSet()
        for (s in CableSkin.entries) assertTrue("$s", s.unlockedBy == null || s.unlockedBy in ids)
        for (c in ColorTheme.entries) assertTrue("$c", c.unlockedBy == null || c.unlockedBy in ids)
        assertEquals("one default skin", 1, CableSkin.entries.count { it.unlockedBy == null })
        assertEquals("one default theme", 1, ColorTheme.entries.count { it.unlockedBy == null })
        assertEquals(listOf(CableSkin.CLASSIC), Cosmetics.skins(emptySet()))
        assertEquals(listOf(ColorTheme.MEADOW), Cosmetics.themes(emptySet()))
        val some = Achievements.unlocked(PlayerStats(cablesLaid = 100, bestWeek = 10))
        assertEquals(listOf(CableSkin.CLASSIC, CableSkin.COPPER), Cosmetics.skins(some))
        assertEquals(listOf(ColorTheme.MEADOW, ColorTheme.AUTUMN), Cosmetics.themes(some))
        assertEquals(CableSkin.COPPER, Cosmetics.skinFor("cables_100"))
        assertEquals(ColorTheme.AUTUMN, Cosmetics.themeFor("week_10"))
        assertEquals(CableSkin.COPPER, Cosmetics.next(CableSkin.CLASSIC, Cosmetics.skins(some)))
        assertEquals(CableSkin.CLASSIC, Cosmetics.next(CableSkin.COPPER, Cosmetics.skins(some)))
        assertEquals("a skin no longer available falls back", CableSkin.CLASSIC, Cosmetics.next(CableSkin.NEON, Cosmetics.skins(some)))
    }

    @Test
    fun cosmeticsNeverTouchTheGame() {
        // Skins and themes live outside the rules: nothing in the world or its save refers to them.
        val text = Save.encode(World(Scenarios.RIVER_TOWN, seed = 1L))
        for (name in CableSkin.entries.map { it.name } + ColorTheme.entries.map { it.name }) assertFalse(name, text.contains(name))
        val worldFields = World::class.java.declaredFields.map { it.type }
        assertFalse(worldFields.any { it == CableSkin::class.java || it == ColorTheme::class.java })
    }
}
