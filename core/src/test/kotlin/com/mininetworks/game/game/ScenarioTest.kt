package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(DebugApi::class)
class ScenarioTest {

    private fun count(w: World, t: Terrain) = (0 until w.rows).sumOf { y -> (0 until w.cols).count { x -> w.terrainAt(x, y) == t } }

    private fun flat(scenario: Scenario = Scenarios.RIVER_TOWN): World =
        World(scenario, cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false).apply {
            for (y in 0 until rows) for (x in 0 until cols) setTerrain(x, y, Terrain.LAND)
            grant(500, extraCellTowers = 2, extraAccessPoints = 2)
        }

    // ---------------------------------------------------------------- data

    @Test
    fun fiveScenariosAsPlanned() {
        assertEquals(listOf("river_town", "metropolis", "island_harbor", "mountain_village", "future_2030"), Scenarios.all.map { it.id })
        assertEquals(listOf(1995, 1998, 2004, 2001, 2030), Scenarios.all.map { it.startYear })
        assertEquals(Unlock.Free, Scenarios.RIVER_TOWN.unlock)
        assertEquals(Unlock.Score("river_town", 900), Scenarios.METROPOLIS.unlock)
        assertEquals(Unlock.Score("metropolis", 650), Scenarios.ISLAND.unlock)
        assertEquals(Unlock.Purchase, Scenarios.MOUNTAIN_VILLAGE.unlock)
        assertEquals(Unlock.Purchase, Scenarios.FUTURE.unlock)
        assertFalse(Scenarios.RIVER_TOWN.purchasable)
        assertTrue(Scenarios.all.drop(1).all { it.purchasable })
        for (s in Scenarios.all) assertEquals(s, Scenarios.byId(s.id))
        assertNull(Scenarios.byId("atlantis"))
    }

    @Test
    fun startWeeksMatchTheEras() {
        // Every scenario but the future one starts in the era week of its start year (docs/PLAN.md 3.2).
        for (s in Scenarios.all - Scenarios.FUTURE) {
            assertEquals(s.id, s.startYear, World.eraYear(s.startWeek))
        }
        assertTrue("2030 has every technology", CableType.entries.all { it.unlockWeek <= Scenarios.FUTURE.startWeek })
        assertTrue(RadioType.entries.all { it.unlockWeek <= Scenarios.FUTURE.startWeek })
        assertTrue(Device.entries.all { it.unlockWeek <= Scenarios.FUTURE.startWeek })
    }

    @Test
    fun unlockByScoreInThePreviousScenarioOrByPurchase() {
        val none = { _: String -> false }
        val best = mutableMapOf<String, Int>()
        fun unlocked(s: Scenario, owns: (String) -> Boolean = none) = Scenarios.isUnlocked(s, { best[it] ?: 0 }, owns)
        assertTrue(unlocked(Scenarios.RIVER_TOWN))
        assertEquals(listOf(true, false, false, false, false), Scenarios.all.map { unlocked(it) })
        best["river_town"] = 899
        assertFalse(unlocked(Scenarios.METROPOLIS))
        best["river_town"] = 900
        assertTrue(unlocked(Scenarios.METROPOLIS))
        assertFalse("the island needs its target in the metropolis", unlocked(Scenarios.ISLAND))
        best["metropolis"] = 650
        assertTrue(unlocked(Scenarios.ISLAND))
        best["river_town"] = 99_999
        assertFalse("no score unlocks a purchase-only scenario", unlocked(Scenarios.MOUNTAIN_VILLAGE))
        assertTrue(unlocked(Scenarios.MOUNTAIN_VILLAGE) { it == "mountain_village" })
        assertFalse(unlocked(Scenarios.FUTURE) { it == "mountain_village" })
        best.clear()
        assertTrue("a purchase skips the score", unlocked(Scenarios.ISLAND) { it == "island_harbor" })
    }

    // ---------------------------------------------------------------- terrain

    @Test
    fun riverTownKeepsThePrototypeRiver() {
        for (seed in 1L..10L) {
            val w = World(seed = seed, spawnInitialNodes = false)
            val phase = ReplayableRandom(seed).nextFloat() * 6f
            for (y in 0 until w.rows) {
                val x = (16 + sin(y * 0.6f + phase) * 1.3f).roundToInt().coerceIn(1, 30)
                assertEquals((0 until w.cols).filter { w.water[y][it] }, listOf(x))
            }
            assertEquals(0, count(w, Terrain.MOUNTAIN) + count(w, Terrain.HIGH_RISE))
        }
    }

    @Test
    fun eachScenarioHasItsLandscape() {
        for (seed in 1L..10L) {
            val metro = World(Scenarios.METROPOLIS, seed = seed)
            assertTrue("two rivers: water in every row and in every column", (0 until metro.rows).all { y -> (0 until metro.cols).any { metro.isWater(it, y) } })
            assertTrue((0 until metro.cols).all { x -> (0 until metro.rows).any { metro.isWater(x, it) } })
            assertTrue("downtown towers", count(metro, Terrain.HIGH_RISE) >= 12)

            val island = World(Scenarios.ISLAND, seed = seed)
            assertTrue("mostly sea", count(island, Terrain.WATER) > island.cols * island.rows * 0.4f)
            assertTrue("sea on every edge", (0 until island.cols).all { island.isWater(it, 0) && island.isWater(it, island.rows - 1) })

            val village = World(Scenarios.MOUNTAIN_VILLAGE, seed = seed)
            assertTrue("mountains", count(village, Terrain.MOUNTAIN) >= 30)
            assertTrue("some inside the start block", village.unlocked.let { u -> (u.left until u.right).any { x -> (u.top until u.bottom).any { village.terrainAt(x, it) == Terrain.MOUNTAIN } } })

            val future = World(Scenarios.FUTURE, seed = seed)
            assertTrue(count(future, Terrain.HIGH_RISE) > 0 && count(future, Terrain.WATER) > 0)
        }
    }

    @Test
    fun terrainIsDeterministicFromTheSeed() {
        for (s in Scenarios.all) {
            val a = World(s, seed = 5L).snapshot()
            val b = World(s, seed = 5L).snapshot()
            assertEquals(a, b)
            assertTrue(s.id, a.water != World(s, seed = 6L).snapshot().water || s.terrain.none { it is TerrainFeature.River })
        }
    }

    @Test
    fun everyScenarioStartsPlayable() {
        for (s in Scenarios.all) for (seed in 1L..20L) {
            val w = World(s, seed = seed)
            assertEquals(s.cols, w.cols)
            assertEquals(s.rows, w.rows)
            assertEquals(s.startCols, w.unlocked.width)
            assertEquals(s.startRows, w.unlocked.height)
            val u = w.unlocked
            val land = (u.left until u.right).sumOf { x -> (u.top until u.bottom).count { w.terrainAt(x, it) == Terrain.LAND } }
            assertTrue("${s.id}/$seed: enough land in the start block", land >= u.width * u.height * 0.55f)
            for (n in w.nodes) for (c in n.footprint) {
                assertEquals("${s.id}/$seed: nodes stand on land", Terrain.LAND, w.terrainAt(c.x, c.y))
                assertTrue(c in u)
            }
            val servers = w.nodes.filter { it.kind == NodeKind.SERVER }.mapNotNull { it.service }.toSet()
            assertEquals("${s.id}/$seed: servers of every service due by the start", s.startServices.toSet(), servers)
            assertTrue("${s.id}/$seed: clients", w.nodes.count { it.kind == NodeKind.CLIENT } >= 2)
        }
    }

    // ---------------------------------------------------------------- eras

    @Test
    fun laterScenariosStartInTheirEra() {
        val w = World(Scenarios.ISLAND, seed = 3L)
        assertEquals(4, w.week)
        assertEquals(2004, w.year)
        assertEquals(1, w.weeksPlayed)
        assertEquals(0f, w.weekProgress, 1e-4f)
        assertEquals("the day starts at dawn", World.Tuning.DAWN_HOUR, w.hourOfDay, 1e-3f)
        assertEquals(listOf(CableType.ISDN, CableType.DSL, CableType.COAX), w.unlockedCables)
        assertEquals(62, w.budget)
        assertEquals(1, w.cellTowersAvailable)
        w.advanceToNextWeek()
        assertEquals(2007, w.year)
        assertEquals(2, w.weeksPlayed)
        assertEquals(RadioType.WLAN, w.lastNews!!.radios.single())
        assertEquals(2007, w.lastNews!!.year)

        val future = World(Scenarios.FUTURE, seed = 3L)
        assertEquals(2030, future.year)
        future.advanceToNextWeek()
        assertEquals(2033, future.year)
    }

    @Test
    fun growthCountsWeeksPlayed() {
        val w = World(Scenarios.FUTURE, seed = 2L, spawnInitialNodes = false)
        val start = w.unlocked
        assertEquals(start, w.unlockedArea(Scenarios.FUTURE.startWeek + World.Tuning.GROWTH_WEEKS - 1))
        assertEquals(start.expand(1, w.bounds), w.unlockedArea(Scenarios.FUTURE.startWeek + World.Tuning.GROWTH_WEEKS))
    }

    @Test
    fun incidentsWaitForTheThirdWeekPlayed() {
        val w = World(Scenarios.ISLAND, seed = 4L)
        w.grant(200)
        val clients = w.nodes.filter { it.kind == NodeKind.CLIENT }
        val server = w.nodes.first { it.kind == NodeKind.SERVER }
        for (c in clients) w.connect(c, server, CableType.ISDN)
        var sawIncident = false
        repeat((2 * World.Tuning.WEEK_SECONDS * 60).toInt() - 30) {
            w.rewardOffer?.let { w.chooseReward(0) }
            w.update(1f / 60f)
            sawIncident = sawIncident || w.incidents.isNotEmpty()
        }
        assertEquals(2, w.weeksPlayed)
        assertFalse("no incident in the first two weeks played", sawIncident)
    }

    // ---------------------------------------------------------------- rules

    @Test
    fun mountainsAndTowersTakeNoNodesAndCostMoreToCross() {
        val w = flat()
        w.setTerrain(6, 4, Terrain.MOUNTAIN)
        w.setTerrain(7, 4, Terrain.MOUNTAIN)
        w.setTerrain(6, 6, Terrain.HIGH_RISE)
        assertFalse(w.isFree(6, 4))
        assertFalse(w.isFree(6, 6))
        assertNull(w.placeRouter(6, 4))
        assertNull(w.placeRouter(6, 6))
        val a = w.addRouter(4, 4)
        val b = w.addRouter(9, 4)
        val straight = w.planLayout(a, b)
        assertEquals(5 * CableType.ISDN.costPerCell + 2 * World.Tuning.MOUNTAIN_EXTRA_PER_CELL, w.cableCost(straight, CableType.ISDN))
        val c = w.addRouter(4, 6)
        val d = w.addRouter(8, 6)
        assertEquals(4 * CableType.ISDN.costPerCell + World.Tuning.HIGH_RISE_EXTRA_PER_CELL, w.cableCost(w.planLayout(c, d), CableType.ISDN))
        assertTrue(w.connect(a, b, CableType.ISDN))
        assertEquals(0, w.cables.single().waterCells)
    }

    @Test
    fun theAutomaticBendAvoidsThePass() {
        val w = flat()
        w.setTerrain(8, 2, Terrain.MOUNTAIN)
        val a = w.addRouter(2, 2)
        val b = w.addRouter(10, 6)
        val layout = w.planLayout(a, b)
        assertFalse(Cell(8, 2) in layout.cells)
        assertEquals(CableLayout.between(a.cell, b.cell, Bend.VERTICAL_FIRST).waypoints, layout.waypoints)
    }

    @Test
    fun mountainsAndTowersBlockRadio() {
        for (blocker in listOf(Terrain.MOUNTAIN, Terrain.HIGH_RISE)) {
            val w = flat()
            val tower = w.placeRadio(RadioType.CELL, 4, 5)!!
            val phone = w.addClient(Device.SMARTPHONE, 7, 5)
            assertNotNull(w.linkBetween(tower, phone))
            w.setTerrain(5, 5, blocker)
            w.placeRouter(2, 2) // any network change rebuilds the radio links
            assertTrue(w.inRadioSight(tower, tower))
            assertFalse(w.inRadioSight(tower, phone))
            assertNull("$blocker blocks the link", w.linkBetween(tower, phone))
        }
        val w = flat()
        w.setTerrain(5, 5, Terrain.WATER)
        val tower = w.placeRadio(RadioType.CELL, 4, 5)!!
        val phone = w.addClient(Device.SMARTPHONE, 7, 5)
        assertNotNull("radio crosses water", w.linkBetween(tower, phone))
    }

    @Test
    fun aClientInAMountainsShadowStaysOffline() {
        val w = flat()
        w.setTerrain(5, 4, Terrain.MOUNTAIN)
        val tower = w.placeRadio(RadioType.CELL, 4, 4)!!
        val server = w.addServer(Service.CALL, 1, 4)
        w.connect(tower, server, CableType.ISDN)
        val seen = w.addClient(Device.SMARTPHONE, 6, 6).also { assertNotNull(w.linkBetween(tower, it)) }
        val behind = w.addClient(Device.SMARTPHONE, 7, 4)
        assertNull(w.linkBetween(tower, behind))
        assertNull(w.routeFor(behind, Service.CALL))
        assertNotNull(w.routeFor(seen, Service.CALL))
    }

    @Test
    fun sixGCellTowersReachEveryDevice() {
        val town = flat()
        val t1 = town.placeRadio(RadioType.CELL, 4, 5)!!
        val pc1 = town.addClient(Device.PC, 6, 5)
        assertNull(town.linkBetween(t1, pc1))

        val future = flat(Scenarios.FUTURE)
        val t2 = future.placeRadio(RadioType.CELL, 4, 5)!!
        val pc2 = future.addClient(Device.PC, 6, 5)
        assertNotNull(future.linkBetween(t2, pc2))
    }

    @Test
    fun orbitalServersStartOneTierHigher() {
        val future = World(Scenarios.FUTURE, seed = 1L)
        assertTrue(future.nodes.filter { it.kind == NodeKind.SERVER }.all { it.level == 2 })
        assertTrue(World(seed = 1L).nodes.filter { it.kind == NodeKind.SERVER }.all { it.level == 1 })
    }

    @Test
    fun excavatorsDigOnlyOnPlainLand() {
        val w = flat()
        for (x in 5..8) w.setTerrain(x, 4, Terrain.MOUNTAIN)
        w.setTerrain(9, 4, Terrain.HIGH_RISE)
        val a = w.addRouter(4, 4)
        val b = w.addRouter(11, 4)
        w.connect(a, b, CableType.ISDN)
        val i = w.announceExcavator(w.cables.single())
        val spot = i.spot
        assertEquals(Terrain.LAND, w.terrainAt(spot.x.toInt(), spot.y.toInt()))
    }

    // ---------------------------------------------------------------- save

    @Test
    fun savesKeepScenarioAndTerrain() {
        for (s in Scenarios.all) {
            val w = World(s, seed = 8L)
            repeat(600) { w.update(1f / 60f) }
            val back = Save.decode(Save.encode(w))!!
            assertEquals(s, back.scenario)
            assertEquals(w.snapshot(), back.snapshot())
            repeat(600) {
                w.rewardOffer?.let { w.chooseReward(0) }; w.update(1f / 60f)
                back.rewardOffer?.let { back.chooseReward(0) }; back.update(1f / 60f)
            }
            assertEquals(w.snapshot(), back.snapshot())
        }
    }

    @Test
    fun oldSavesAreTheRiverTown() {
        val text = Save.encode(World(seed = 3L)).replace("\"scenario\":\"river_town\",", "")
        assertFalse(text.contains("scenario"))
        assertEquals(Scenarios.RIVER_TOWN, Save.decode(text)!!.scenario)
        assertNull("unknown scenario", Save.decode(Save.encode(World(seed = 3L)).replace("river_town", "atlantis")))
    }
}
