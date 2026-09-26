package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor

@OptIn(DebugApi::class)
class CableLayoutTest {

    /** Empty world with the river removed, so each test places its own water. */
    private fun dryWorld() = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false).also { w ->
        for (row in w.water) row.fill(false)
        w.grant(200)
    }

    private fun isOnGrid(p: Vec2) = abs(p.x - floor(p.x) - 0.5f) < 1e-4f || abs(p.y - floor(p.y) - 0.5f) < 1e-4f

    @Test
    fun lShapesFollowTheGrid() {
        val h = CableLayout.between(Cell(1, 1), Cell(4, 3), Bend.HORIZONTAL_FIRST)
        assertEquals(listOf(Vec2(1.5f, 1.5f), Vec2(4.5f, 1.5f), Vec2(4.5f, 3.5f)), h.waypoints)
        val v = CableLayout.between(Cell(1, 1), Cell(4, 3), Bend.VERTICAL_FIRST)
        assertEquals(listOf(Vec2(1.5f, 1.5f), Vec2(1.5f, 3.5f), Vec2(4.5f, 3.5f)), v.waypoints)
        assertEquals(5, h.steps)
        assertEquals(5f, v.length)
        assertEquals(listOf(Cell(1, 1), Cell(2, 1), Cell(3, 1), Cell(4, 1), Cell(4, 2), Cell(4, 3)), h.cells)
        val straight = CableLayout.between(Cell(2, 5), Cell(2, 1), Bend.HORIZONTAL_FIRST)
        assertEquals(2, straight.waypoints.size)
        assertEquals(4, straight.steps)
    }

    @Test(expected = IllegalArgumentException::class)
    fun diagonalsAreRejected() {
        CableLayout(listOf(Vec2(0.5f, 0.5f), Vec2(2.5f, 1.5f)))
    }

    @Test
    fun costMatchesLayout() {
        val w = dryWorld()
        w.jumpToWeek(CableType.COAX.unlockWeek)
        w.water[1][3] = true
        val a = w.addRouter(1, 1)
        val b = w.addRouter(5, 4)
        val budget = w.budget
        assertTrue(w.connect(a, b, CableType.COAX, Bend.HORIZONTAL_FIRST))
        val c = w.cableBetween(a, b)!!
        assertEquals(7, c.layout.steps)
        assertEquals(1, c.waterCells)
        assertTrue(c.crossesWater)
        assertEquals(7 * CableType.COAX.costPerCell + World.Tuning.WATER_EXTRA_PER_CELL, c.cost)
        assertEquals(w.cableCost(c.layout, CableType.COAX), c.cost)
        assertEquals(budget - c.cost, w.budget)
        assertEquals(c.layout.length, c.length)
        assertEquals(c.length * CableType.COAX.msPerCell, c.latencyMs)

        // Upgrades are priced on the stored layout, not re-planned.
        w.jumpToWeek(CableType.FIBER.unlockWeek)
        val before = w.budget
        assertTrue(w.upgrade(c, CableType.FIBER))
        assertEquals(7 * CableType.FIBER.costPerCell + World.Tuning.WATER_EXTRA_PER_CELL, c.cost)
        assertEquals(before - 7 * (CableType.FIBER.costPerCell - CableType.COAX.costPerCell), w.budget)
    }

    @Test
    fun waterCrossingIsDetectedOnTheLayout() {
        val w = dryWorld()
        w.water[3][1] = true
        w.water[4][1] = true
        val a = w.addRouter(1, 1)
        val b = w.addRouter(4, 5)
        val down = w.planLayout(a, b, Bend.VERTICAL_FIRST)
        val across = w.planLayout(a, b, Bend.HORIZONTAL_FIRST)
        assertEquals(2, w.waterCellsOn(down))
        assertEquals(0, w.waterCellsOn(across))
        assertEquals(7 + 2 * World.Tuning.WATER_EXTRA_PER_CELL, w.cableCost(down, CableType.ISDN))
        assertEquals(7, w.cableCost(across, CableType.ISDN))
        assertEquals("default avoids water", across.waypoints, w.planLayout(a, b).waypoints)

        // Water under the horizontal leg instead: the default flips to vertical first.
        w.water[3][1] = false
        w.water[4][1] = false
        w.water[1][3] = true
        assertEquals(w.planLayout(a, b, Bend.VERTICAL_FIRST).waypoints, w.planLayout(a, b).waypoints)
        assertTrue(w.connect(a, b, CableType.ISDN))
        assertFalse(w.cableBetween(a, b)!!.crossesWater)
    }

    @Test
    fun explicitBendWinsOverWater() {
        val w = dryWorld()
        w.water[1][3] = true
        val a = w.addRouter(1, 1)
        val b = w.addRouter(5, 4)
        assertTrue(w.connect(a, b, CableType.ISDN, Bend.HORIZONTAL_FIRST))
        assertEquals(Vec2(5.5f, 1.5f), w.cableBetween(a, b)!!.layout.waypoints[1])
        assertEquals(1, w.cableBetween(a, b)!!.waterCells)
    }

    @Test
    fun dragTrailSuggestsTheBend() {
        val a = Cell(1, 1)
        val b = Cell(6, 5)
        val alongTop = listOf(Vec2(2.5f, 1.6f), Vec2(4f, 1.4f), Vec2(6.4f, 2f), Vec2(6.5f, 4f))
        val alongLeft = listOf(Vec2(1.4f, 3f), Vec2(1.7f, 5.2f), Vec2(4f, 5.5f))
        val diagonal = listOf(Vec2(2.5f, 2.3f), Vec2(3.7f, 3.3f), Vec2(4.9f, 4.3f))
        assertEquals(Bend.HORIZONTAL_FIRST, CableLayout.suggestBend(a, b, alongTop))
        assertEquals(Bend.VERTICAL_FIRST, CableLayout.suggestBend(a, b, alongLeft))
        assertNull(CableLayout.suggestBend(a, b, diagonal))
        assertNull("straight cables have no bend", CableLayout.suggestBend(a, Cell(6, 1), alongTop))
    }

    @Test
    fun packetsMoveAlongTheStoredLayout() {
        val w = dryWorld()
        val phone = w.addClient(Device.PHONE, 1, 1)
        val server = w.addServer(Service.CALL, 4, 4)
        // Stored from the server's side, so the packet travels the layout backwards.
        assertTrue(w.connect(server, phone, CableType.ISDN, Bend.VERTICAL_FIRST))
        val cable = w.cableBetween(phone, server)!!
        assertEquals(Vec2(4.5f, 1.5f), cable.layout.waypoints[1])
        var sawCorner = false
        var checked = 0
        repeat(60 * 20) {
            w.update(1f / 60f)
            for (p in w.packets) {
                if (!p.inTransit || p.progress < 0f) continue
                val pos = w.packetPosition(p)
                assertTrue("off grid: $pos", isOnGrid(pos))
                assertTrue(Geometry.distToPolyline(pos, cable.layout.waypoints) < 1e-4f)
                // Requests run from the phone (layout end) to the server, responses the other way.
                assertEquals(cable.layout.pointAt(if (p.isResponse) p.progress else 1f - p.progress), pos)
                if (pos.x > 4f && pos.y < 2f) sawCorner = true
                checked++
            }
        }
        assertTrue(checked > 0)
        assertTrue("packets pass the corner at (4,1)", sawCorner)
        assertTrue(w.delivered > 0)
    }

    @Test
    fun packetTravelTimeFollowsLayoutLength() {
        val w = dryWorld()
        val phone = w.addClient(Device.PHONE, 1, 1)
        val server = w.addServer(Service.CALL, 4, 4)
        assertTrue(w.connect(phone, server, CableType.ISDN))
        assertEquals(6f, w.cableBetween(phone, server)!!.length)
        var packet: Packet? = null
        var sentAt = 0
        var step = 0
        while (step < 60 * 30) {
            w.update(1f / 60f)
            step++
            if (packet == null) packet = w.packets.firstOrNull()?.also { sentAt = step }
            else if (packet !in w.packets) break
        }
        assertNotNull(packet)
        val seconds = (step - sentAt) / 60f
        assertEquals("6 cells at ISDN speed", 6f / CableType.ISDN.speed, seconds, 0.05f)
    }
}
