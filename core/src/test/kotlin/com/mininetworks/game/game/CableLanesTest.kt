package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Cables over the same cells run side by side in lanes ([CableLanes]) instead of hiding one another. */
@OptIn(DebugApi::class)
class CableLanesTest {

    private fun world() = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false)

    /** A cable between two clients that runs straight along row 1 from column [from] to column [to]. */
    private fun row(w: World, from: Int, to: Int, y: Int = 1): Cable {
        val a = w.addClient(Device.PC, from, y)
        val b = w.addRouter(to, y)
        return Cable(a, b, CableType.ISDN, 0, CableLayout(listOf(a.center, b.center)), 0)
    }

    private fun centreY(c: Cable) = c.layout.start.y

    @Test
    fun aCableAloneStaysOnTheMiddleOfItsCells() {
        val w = world()
        val c = row(w, 1, 6)
        CableLanes.assign(listOf(c))
        assertSame(c.layout.waypoints, c.path)
    }

    @Test
    fun cablesOverTheSameCellsRunSideBySide() {
        val w = world()
        val a = row(w, 1, 6, y = 1)
        val b = row(w, 2, 7, y = 1)
        CableLanes.assign(listOf(a, b))
        val ya = a.path.first().y
        val yb = b.path.first().y
        assertEquals("one lane apart", CableLanes.SPACING, yb - ya, 1e-4f)
        assertEquals("centred on the cells", centreY(a), (ya + yb) / 2f, 1e-4f)
        assertEquals("along the cable nothing moves", a.layout.start.x, a.path.first().x, 0f)
        assertEquals(a.path.first().y, a.path.last().y, 0f)
    }

    @Test
    fun cablesThatOnlyCrossStayOnTheirCells() {
        val w = world()
        val a = row(w, 1, 6, y = 3)
        val x = w.addClient(Device.PC, 3, 1)
        val y = w.addRouter(3, 6)
        val b = Cable(x, y, CableType.ISDN, 0, CableLayout(listOf(x.center, y.center)), 0)
        CableLanes.assign(listOf(a, b))
        assertSame(a.layout.waypoints, a.path)
        assertSame(b.layout.waypoints, b.path)
    }

    @Test
    fun aCableKeepsItsLaneWhenAnotherArrives() {
        val w = world()
        val a = row(w, 1, 6)
        val b = row(w, 2, 7)
        val c = row(w, 3, 8)
        CableLanes.assign(listOf(a, b))
        CableLanes.assign(listOf(a, b, c))
        val ys = listOf(a, b, c).map { it.path.first().y }
        assertTrue("same order of lanes: $ys", ys[0] < ys[1] && ys[1] < ys[2])
        assertEquals(CableLanes.SPACING, ys[1] - ys[0], 1e-4f)
        assertEquals(CableLanes.SPACING, ys[2] - ys[1], 1e-4f)
    }

    @Test
    fun moreThanTheMaximumShareTheLastLane() {
        val w = world()
        val cables = (0 until 6).map { row(w, 1 + it, 7 + it) }
        CableLanes.assign(cables)
        val half = (CableLanes.MAX_LANES - 1) / 2f * CableLanes.SPACING
        for (c in cables) assertTrue("${c.path.first().y} stays inside the cells", abs(c.path.first().y - centreY(c)) <= half + 1e-4f)
        assertEquals("every lane is used", CableLanes.MAX_LANES, cables.map { it.path.first().y }.distinct().size)
    }

    @Test
    fun aBentCableMeetsItsCornerOnItsLanes() {
        val w = world()
        val a = w.addClient(Device.PC, 1, 1)
        val b = w.addRouter(5, 4)
        val bent = Cable(a, b, CableType.ISDN, 0, CableLayout(listOf(a.center, Vec2(5.5f, 1.5f), b.center)), 0)
        val straight = row(w, 2, 7, y = 1)
        CableLanes.assign(listOf(bent, straight))
        val p = bent.path
        assertEquals(3, p.size)
        assertEquals("the corner is on the horizontal run's lane", p[0].y, p[1].y, 0f)
        assertEquals("and on the vertical run's cell", 5.5f, p[1].x, 1e-4f)
        assertEquals(p[2].x, p[1].x, 0f)
    }

    @Test
    fun packetsTravelOnTheLane() {
        val w = world()
        val a = row(w, 1, 6)
        val b = row(w, 2, 7)
        CableLanes.assign(listOf(a, b))
        for (c in listOf(a, b)) {
            val mid = c.pointFrom(c.a, 0.5f)
            assertEquals(c.path.first().y, mid.y, 1e-4f)
            val back = c.pointFrom(c.b, 0.5f)
            assertEquals("the same point from either end", mid.x, back.x, 1e-3f)
            assertEquals(mid.y, back.y, 1e-3f)
        }
    }

    @Test
    fun theWorldAssignsLanesWhenCablesAreLaidAndRemoved() {
        val w = world()
        w.grant(200)
        val pc1 = w.addClient(Device.PC, 1, 1)
        val pc2 = w.addClient(Device.PC, 2, 1)
        val hub = w.addRouter(8, 3)
        val hub2 = w.addRouter(9, 3)
        assertTrue(w.connect(pc1, hub, CableType.ISDN))
        assertTrue(w.connect(pc2, hub, CableType.ISDN, null))
        val shared = w.cables.filter { c -> w.cables.any { o -> o !== c && o.layout.cells.intersect(c.layout.cells.toSet()).size > 1 } }
        assertEquals("both cables run over shared cells", 2, shared.size)
        run {
            assertTrue("laid cables on shared cells get different paths", shared[0].path != shared[1].path)
            w.removeCable(shared[1])
            assertSame("alone again, back on the middle", shared[0].layout.waypoints, shared[0].path)
        }
        assertTrue(hub2.cell != hub.cell)
    }
}
