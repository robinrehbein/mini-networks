package com.mininetworks.game.render

import com.mininetworks.game.game.Bend
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.Cell
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decorations and ground variation of the isometric style, on the plain JVM. */
@OptIn(DebugApi::class)
class SceneryTest {

    private fun plan(seed: Long) = (0 until 20).flatMap { y -> (0 until 32).map { x -> Scenery.planned(seed, x, y) } }

    @Test
    fun sameSeedSameScenery() {
        assertEquals(plan(7L), plan(7L))
        assertNotEquals(plan(7L), plan(8L))
    }

    @Test
    fun sceneryIsSparseWithEveryKind() {
        val w = World(seed = 7L, spawnInitialNodes = false)
        val dry = (0 until w.rows).sumOf { y -> (0 until w.cols).count { x -> !w.isWater(x, y) } }
        val decor = Scenery.decorations(w)
        val share = decor.size.toFloat() / dry
        assertTrue("share $share", share in 0.08f..0.35f)
        for (kind in Decor.entries) assertTrue("$kind appears", decor.any { it.second == kind })
        assertTrue("houses stay rare", decor.count { it.second == Decor.HOUSE } < decor.size / 4)
    }

    @Test
    fun neverOnWaterNodesOrCables() {
        val w = World(seed = 7L)
        w.grant(200)
        val server = w.nodes.first { it.service == Service.MAIL }
        for (c in w.nodes.filter { it.device != null }) w.connect(c, server, CableType.ISDN)
        val decor = Scenery.decorations(w)
        val cables = w.cables.flatMap { it.layout.cells }.toSet()
        for ((cell, _) in decor) {
            assertFalse(w.isWater(cell.x, cell.y))
            assertTrue(w.nodeAt(cell) == null)
            assertFalse(cell in cables)
        }
    }

    @Test
    fun aNodeOrCableReplacesTheDecoration() {
        val w = World(seed = 7L, spawnInitialNodes = false)
        w.grant(200)
        val (a, b) = Scenery.decorations(w).map { it.first }.filter { it.x in 1..14 && it.y in 1..14 }.let { it[0] to it[1] }
        w.addClient(Device.PC, a.x, a.y)
        assertFalse(Scenery.decorations(w).any { it.first == a })
        val left = w.addClient(Device.PC, b.x - 1, b.y)
        val right = w.addServer(Service.MAIL, b.x + 1, b.y)
        assertTrue(w.connect(left, right, CableType.ISDN, Bend.HORIZONTAL_FIRST))
        assertFalse("the cable runs over ${Cell(b.x, b.y)}", Scenery.decorations(w).any { it.first == b })
    }

    @Test
    fun decorationsComeBackToFront() {
        val keys = Scenery.decorations(World(seed = 3L, spawnInitialNodes = false)).map { it.first.x + it.first.y }
        assertEquals(keys.sorted(), keys)
    }

    @Test
    fun tileVariationStaysInRange() {
        val values = (0 until 200).map { Scenery.tileVariation(3L, it % 20, it / 20) }
        assertTrue(values.all { it in -1f..1f })
        assertTrue("tiles differ", values.toSet().size > 150)
    }
}
