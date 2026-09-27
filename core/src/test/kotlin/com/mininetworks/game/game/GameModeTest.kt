package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Normal, endless and creative mode (docs/TOP100.md C4). */
@OptIn(DebugApi::class)
class GameModeTest {

    private fun empty(mode: GameMode) = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false, mode = mode).also { w ->
        for (y in 0 until w.rows) for (x in 0 until 6) assertFalse("test area must be dry", w.water[y][x])
        w.incidentsEnabled = false
    }

    /** Runs [w] for [seconds], keeping [client]'s queue full so its ring fills. */
    private fun jam(w: World, client: Node, seconds: Float, othersEmpty: Boolean = false) {
        repeat((seconds * 60).toInt()) {
            while (client.pending.size < World.Tuning.MAX_PENDING) client.pending.addLast(Service.CALL)
            w.update(1f / 60f)
            w.rewardOffer?.let { w.chooseReward(0) }
            if (othersEmpty) for (n in w.nodes) if (n !== client) n.pending.clear()
        }
    }

    @Test
    fun normalGameEndsWhenARingCloses() {
        val w = empty(GameMode.NORMAL)
        val phone = w.addClient(Device.PHONE, 1, 1)
        jam(w, phone, World.Tuning.OVERLOAD_SECONDS * World.Tuning.EARLY_OVERLOAD_SLOWDOWN + 1f)
        assertTrue(w.gameOver)
        assertEquals(phone, w.failedNode)
        assertFalse(w.isSlowed(phone))
    }

    @Test
    fun endlessNeverEndsButTheOverloadSlowsTheArea() {
        val w = empty(GameMode.ENDLESS)
        val phone = w.addClient(Device.PHONE, 1, 1)
        val neighbour = w.addClient(Device.PHONE, 3, 2)
        val far = w.addClient(Device.PHONE, 5, 8)
        w.addServer(Service.CALL, 5, 4) // not cabled: the phones ask for calls, but nothing gets through
        assertFalse(w.isSlowed(neighbour))
        jam(w, phone, World.Tuning.OVERLOAD_SECONDS * World.Tuning.EARLY_OVERLOAD_SLOWDOWN * 3, othersEmpty = true)
        assertFalse("no game over in endless mode", w.gameOver)
        assertNull(w.failedNode)
        assertEquals(1f, phone.overload)
        assertTrue(w.isSlowed(phone))
        assertTrue("within ${World.Tuning.SLOW_RADIUS} cells", w.isSlowed(neighbour))
        assertFalse("too far away", w.isSlowed(far))
        // The jammed area asks half as often: count the requests of the neighbour against the far device.
        var near = 0
        var away = 0
        repeat(60 * 90) {
            while (phone.pending.size < World.Tuning.MAX_PENDING) phone.pending.addLast(Service.CALL)
            w.update(1f / 60f)
            w.rewardOffer?.let { w.chooseReward(0) }
            near += neighbour.pending.size
            away += far.pending.size
            for (n in w.nodes) if (n !== phone) n.pending.clear()
        }
        assertTrue("near $near vs far $away", near < away * 0.7f)
        // Its ring drains once the jam is gone, and the area speeds up again.
        phone.pending.clear()
        repeat(60 * 40) { w.update(1f / 60f); w.rewardOffer?.let { w.chooseReward(0) }; for (n in w.nodes) n.pending.clear() }
        assertTrue(phone.overload < 1f)
        assertFalse(w.isSlowed(neighbour))
    }

    @Test
    fun endlessQueuesStayBounded() {
        val w = World(Scenarios.RIVER_TOWN, seed = 3L, mode = GameMode.ENDLESS)
        repeat(60 * 45 * 6) {
            w.update(1f / 60f)
            w.rewardOffer?.let { w.chooseReward(0) }
        }
        assertFalse(w.gameOver)
        assertTrue(w.weeksPlayed >= 6)
        for (n in w.nodes) assertTrue("${n.pending.size} waiting at $n", n.pending.size <= World.Tuning.MAX_PENDING)
        assertTrue("devices overloaded, nothing is cabled", w.nodes.any { it.overload >= 1f })
    }

    @Test
    fun creativeHasUnlimitedBudgetStockAndEveryTechnology() {
        val w = World(Scenarios.RIVER_TOWN, seed = 3L, mode = GameMode.CREATIVE)
        assertEquals(1, w.week)
        assertEquals(CableType.entries, w.unlockedCables)
        assertEquals("a server of every service", Service.entries.toSet(), w.availableServices)
        val budget = w.budget
        val routers = w.routersAvailable
        val u = w.unlocked
        val placed = (0 until 12).mapNotNull { i -> w.nearestFree(Cell(u.left + 1 + i, u.top + 1))?.let { w.placeRouter(it.x, it.y) } }
        assertEquals("routers never run out", 12, placed.size)
        val ap = w.nearestFree(Cell(u.left + 2, u.bottom - 2))!!.let { w.placeRadio(RadioType.WLAN, it.x, it.y) }
        val tower = w.nearestFree(Cell(u.right - 2, u.bottom - 2))!!.let { w.placeRadio(RadioType.CELL, it.x, it.y) }
        assertNotNull(ap)
        assertNotNull(tower)
        for (i in 0 until placed.size - 1) assertTrue(w.connect(placed[i], placed[i + 1], CableType.FIBER))
        assertTrue(w.upgradeTo5Ghz(ap!!))
        val server = w.nodes.first { it.kind == NodeKind.SERVER }
        while (w.serverUpgradeError(server) == null) assertTrue(w.upgradeServer(server))
        assertEquals("nothing cost anything", budget, w.budget)
        assertEquals(routers, w.routersAvailable)
        val cable = w.cables.first()
        assertEquals("no refund to farm", 0, w.refundOf(cable))
        w.removeCable(cable)
        assertEquals(budget, w.budget)
    }

    @Test
    fun creativeHasNoRewardsNoIncidentsAndNoGameOver() {
        val w = World(Scenarios.RIVER_TOWN, seed = 3L, mode = GameMode.CREATIVE)
        val a = w.placeRouter(w.unlocked.left + 1, w.unlocked.top + 1)!!
        val b = w.placeRouter(w.unlocked.left + 1, w.unlocked.top + 4)!!
        w.connect(a, b, CableType.DSL)
        repeat(60 * 45 * 5) {
            w.update(1f / 60f)
            assertNull("no reward to pick", w.rewardOffer)
            assertTrue("no incidents", w.incidents.isEmpty())
        }
        assertFalse(w.gameOver)
        assertTrue(w.weeksPlayed >= 5)
    }

    @Test
    fun modesSaveAndLoad() {
        for (mode in GameMode.entries) {
            val w = World(Scenarios.RIVER_TOWN, seed = 3L, mode = mode)
            repeat(60 * 20) { w.update(1f / 60f) }
            val back = Save.decode(Save.encode(w))!!
            assertEquals(mode, back.mode)
            assertEquals(Save.encode(w), Save.encode(back))
            repeat(60 * 5) { w.update(1f / 60f); back.update(1f / 60f) }
            assertEquals("continues exactly like the original", Save.encode(w), Save.encode(back))
        }
        // An old save without the field is a normal game.
        val old = Save.encode(World(Scenarios.RIVER_TOWN, seed = 3L)).replace(",\"mode\":\"NORMAL\"", "")
        assertFalse(old.contains("\"mode\""))
        assertEquals(GameMode.NORMAL, Save.decode(old)!!.mode)
    }

    @Test
    fun countersFollowThePlayersActions() {
        val w = empty(GameMode.NORMAL)
        w.grant(500, extraRouters = 3, extraAccessPoints = 1, extraCellTowers = 1)
        w.jumpToWeek(CableType.FIBER.unlockWeek)
        val pc = w.addClient(Device.PC, 1, 1)
        val mail = w.addServer(Service.MAIL, 4, 1)
        val r = w.placeRouter(1, 4)!!
        w.placeRadio(RadioType.WLAN, 3, 6)
        w.placeRadio(RadioType.CELL, 5, 6)
        assertTrue(w.connect(pc, mail, CableType.ISDN))
        assertTrue(w.connect(r, mail, CableType.FIBER))
        assertTrue(w.upgrade(w.cableBetween(pc, mail)!!, CableType.FIBER))
        repeat(3) { assertTrue(w.upgradeServer(mail)) }
        val cut = w.cableBetween(r, mail)!!
        w.announceExcavator(cut)
        repeat(60 * 6) { w.update(1f / 60f) }
        assertTrue(w.isCut(cut))
        assertTrue(w.repair(cut))
        pc.pending.addLast(Service.MAIL)
        repeat(60 * 6) { w.update(1f / 60f) }
        val c = w.counters
        assertEquals(2, c.cablesLaid)
        assertEquals("one laid, one upgraded", 2, c.fiberLaid)
        assertEquals(1, c.cableUpgrades)
        assertEquals(1, c.routersPlaced)
        assertEquals(1, c.accessPoints)
        assertEquals(1, c.cellTowers)
        assertEquals(3, c.serverUpgrades)
        assertEquals(1, c.dataCenters)
        assertEquals(1, c.repairs)
        assertTrue(c.delivered(Service.MAIL) >= 1)
        assertEquals(w.delivered, Service.entries.sumOf { c.delivered(it) })
    }

    /** docs/TOP100.md C2: laying and removing a cable for a full refund, or picking a router up and placing it again, farms nothing. */
    @Test
    fun freeUndoLoopsDoNotFarmTheCounters() {
        val w = empty(GameMode.NORMAL)
        w.grant(500, extraRouters = 1)
        w.jumpToWeek(CableType.FIBER.unlockWeek)
        val pc = w.addClient(Device.PC, 1, 1)
        val mail = w.addServer(Service.MAIL, 4, 1)
        val budget = w.budget
        repeat(50) {
            assertTrue(w.connect(pc, mail, CableType.ISDN))
            assertTrue(w.upgrade(w.cableBetween(pc, mail)!!, CableType.FIBER))
            w.removeCable(w.cableBetween(pc, mail)!!)
        }
        assertEquals("every loop was refunded in full", budget, w.budget)
        val c = w.counters
        assertEquals("50 loops count as one cable", 1, c.cablesLaid)
        assertEquals(1, c.fiberLaid)
        assertEquals(1, c.cableUpgrades)
        repeat(50) {
            val r = w.placeRouter(1, 5)!!
            assertTrue(w.pickUp(r))
        }
        assertEquals("50 pick-ups count as one router", 1, c.routersPlaced)
        // Real building still counts: a second cable and router while the first ones stay.
        assertTrue(w.connect(pc, mail, CableType.DSL))
        val r = w.placeRouter(1, 5)!!
        val r2 = w.placeRouter(3, 5)
        assertEquals("the credit of the first undo covers the re-laid cable", 1, c.cablesLaid)
        assertTrue(w.connect(r, mail, CableType.ISDN))
        assertEquals(2, c.cablesLaid)
        assertEquals("one credit used, then a new router", if (r2 != null) 2 else 1, c.routersPlaced)
        // A cable removed without refund (excavator on it) paid for itself: re-laying it counts.
        val dug = w.cableBetween(r, mail)!!
        w.announceExcavator(dug)
        assertEquals(0, w.refundOf(dug))
        w.removeCable(dug)
        assertTrue(w.connect(r, mail, CableType.ISDN))
        assertEquals(3, c.cablesLaid)
    }

    /** Creative mode invents everything at the start, so a week change never announces cables, devices or radios. */
    @Test
    fun creativeWeeksAnnounceNoNewTechnology() {
        val w = World(Scenarios.RIVER_TOWN, seed = 3L, mode = GameMode.CREATIVE)
        val normal = World(Scenarios.RIVER_TOWN, seed = 3L)
        val news = ArrayList<WeekNews>()
        val normalNews = ArrayList<WeekNews>()
        val target = maxOf(CableType.FIBER.unlockWeek, RadioType.entries.maxOf { it.unlockWeek }, Device.entries.maxOf { it.unlockWeek })
        while (w.week < target) {
            w.advanceToNextWeek()
            w.lastNews?.let { if (it !in news) news += it }
        }
        normal.incidentsEnabled = false
        while (normal.week < target) {
            normal.rewardOffer?.let { normal.chooseReward(0) }
            normal.advanceToNextWeek()
            normal.lastNews?.let { if (it !in normalNews) normalNews += it }
        }
        assertTrue("a normal game announces new cables", normalNews.any { it.cables.isNotEmpty() })
        assertTrue("creative: no cables in the news", news.all { it.cables.isEmpty() })
        assertTrue("creative: no devices in the news", news.all { it.devices.isEmpty() })
        assertTrue("creative: no radios in the news", news.all { it.radios.isEmpty() })
    }
}
