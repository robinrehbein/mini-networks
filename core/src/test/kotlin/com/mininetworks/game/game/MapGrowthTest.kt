package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(DebugApi::class)
class MapGrowthTest {

    @Test
    fun startsWithCenteredBlockOnLargeGrid() {
        val w = World(seed = 1L, spawnInitialNodes = false)
        assertEquals(CellRect(0, 0, 32, 20), w.bounds)
        assertEquals(CellRect(8, 5, 24, 15), w.unlocked)
        assertEquals(World.Tuning.START_COLS, w.unlocked.width)
        assertEquals(World.Tuning.START_ROWS, w.unlocked.height)
    }

    @Test
    fun growsOneRingEveryTwoWeeks() {
        val w = World(seed = 1L, spawnInitialNodes = false)
        val start = w.unlocked
        w.advanceToNextWeek()
        assertEquals("week 2: unchanged", start, w.unlocked)
        w.chooseReward(0)
        w.advanceToNextWeek()
        assertEquals("week 3: one ring", CellRect(7, 4, 25, 16), w.unlocked)
        w.chooseReward(0)
        w.advanceToNextWeek()
        assertEquals("week 4: unchanged", CellRect(7, 4, 25, 16), w.unlocked)
        w.chooseReward(0)
        w.advanceToNextWeek()
        assertEquals("week 5: two rings", CellRect(6, 3, 26, 17), w.unlocked)
    }

    @Test
    fun growthHappensDuringSimulation() {
        val w = World(seed = 1L, spawnInitialNodes = false)
        val dt = 1f / 60f
        repeat(10_000) {
            if (w.rewardOffer != null) w.chooseReward(0)
            if (w.week < 3) w.update(dt)
        }
        assertEquals(3, w.week)
        assertEquals(w.unlockedArea(3), w.unlocked)
        assertEquals(18, w.unlocked.width)
    }

    @Test
    fun growthStopsAtGridEdgeAndEachSideClampsOnItsOwn() {
        val w = World(seed = 1L, spawnInitialNodes = false)
        assertEquals("rows fill after 5 rings, columns keep growing", CellRect(3, 0, 29, 20), w.unlockedArea(11))
        assertEquals(w.bounds, w.unlockedArea(17))
        assertEquals(w.bounds, w.unlockedArea(40))
        w.jumpToWeek(17)
        assertEquals(w.bounds, w.unlocked)
    }

    @Test
    fun smallGridIsFullyUnlocked() {
        val w = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false)
        assertEquals(w.bounds, w.unlocked)
        val tiny = World(cols = 8, rows = 5, seed = 1L, spawnInitialNodes = false)
        assertEquals(tiny.bounds, tiny.unlocked)
    }

    @Test
    fun spawnsStayInsideUnlockedBlock() {
        for (seed in 1L..6L) {
            val w = World(seed = seed)
            val seen = HashSet<Int>()
            var outerSpawns = 0
            repeat(60 * 45 * 9) {
                if (w.rewardOffer != null) w.chooseReward(0)
                // Keep the network from overloading so the game runs long enough to grow.
                w.nodes.forEach { it.pending.clear() }
                w.update(1f / 60f)
                for (n in w.nodes) if (seen.add(n.id)) {
                    assertTrue("$n outside ${w.unlocked} (seed $seed)", n.cell in w.unlocked)
                    if (n.cell !in w.unlockedArea(1)) outerSpawns++
                }
            }
            assertFalse(w.gameOver)
            assertEquals(9, w.week)
            assertTrue("new nodes also use the grown rings (seed $seed)", outerSpawns > 0)
            for (n in w.nodes) n.footprint.forEach { assertTrue("$n footprint in ${w.unlocked}", it in w.unlocked) }
        }
    }

    @Test
    fun initialServersAreInsideStartBlock() {
        val w = World(seed = 5L)
        val servers = w.nodes.filter { it.kind == NodeKind.SERVER }
        assertEquals(2, servers.size)
        servers.forEach { assertTrue(it.cell in w.unlocked) }
    }

    @Test
    fun routersOnlyOnUnlockedCells() {
        val w = World(seed = 1L, spawnInitialNodes = false)
        for (row in w.water) row.fill(false)
        assertNull("outside the block", w.placeRouter(2, 2))
        assertEquals(World.Tuning.START_ROUTERS, w.routersAvailable)
        assertNotNull(w.placeRouter(8, 5))
        assertFalse(w.isFree(7, 5))
        w.jumpToWeek(3)
        assertTrue("unlocked after one ring", w.isFree(7, 5))
        assertNotNull(w.placeRouter(7, 5))
    }

    @Test
    fun dataCenterNeedsUnlockedCells() {
        val w = World(seed = 1L, spawnInitialNodes = false)
        for (row in w.water) row.fill(false)
        w.grant(500)
        // Server on the top-left corner of the block: only the block to its bottom right is unlocked.
        val server = w.addServer(Service.MAIL, 8, 5)
        assertEquals(listOf(Cell(8, 5), Cell(9, 5), Cell(8, 6), Cell(9, 6)), w.dataCenterFootprint(server))
        w.addRouter(9, 6)
        assertNull("the three other blocks reach into locked cells", w.dataCenterFootprint(server))
        repeat(2) { w.upgradeServer(server) }
        assertEquals(ServerUpgradeError.NO_SPACE, w.serverUpgradeError(server))
    }

    @Test
    fun cellRectExpandClampsPerSide() {
        val limit = CellRect(0, 0, 10, 6)
        assertEquals(CellRect(0, 1, 9, 5), CellRect(1, 2, 8, 4).expand(1, limit))
        assertEquals(limit, CellRect(1, 2, 8, 4).expand(9, limit))
        assertEquals(CellRect(3, 1, 7, 5), CellRect.centered(limit, 4, 4))
        assertEquals(limit, CellRect.centered(limit, 20, 20))
    }
}
