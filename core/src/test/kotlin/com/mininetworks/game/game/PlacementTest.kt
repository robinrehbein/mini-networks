package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Why a router cannot go somewhere ([World.placeError]) and picking an uncabled router up again ([World.pickUp]). */
@OptIn(DebugApi::class)
class PlacementTest {

    /** The river town grid, all dry; its unlocked block is x 8..23, y 5..14. */
    private fun world() = World(seed = 1L, spawnInitialNodes = false).also { w ->
        for (row in w.water) row.fill(false)
    }

    @Test
    fun placeErrorsNameTheReason() {
        val w = world()
        w.setTerrain(10, 10, Terrain.WATER)
        w.addClient(Device.PC, 11, 11)
        assertEquals(PlaceError.TERRAIN, w.placeError(NodeKind.ROUTER, 10, 10))
        assertEquals(PlaceError.OCCUPIED, w.placeError(NodeKind.ROUTER, 11, 11))
        assertEquals(PlaceError.LOCKED, w.placeError(NodeKind.ROUTER, 0, 0))
        assertEquals("no access point in stock", PlaceError.NO_STOCK, w.placeError(NodeKind.ACCESS_POINT, 12, 12))
        assertNull(w.placeError(NodeKind.ROUTER, 12, 12))
        repeat(w.routersAvailable) { assertNotNull(w.placeRouter(12 + it, 6)) }
        assertEquals(PlaceError.NO_STOCK, w.placeError(NodeKind.ROUTER, 12, 12))
    }

    @Test
    fun uncabledRouterGoesBackIntoStock() {
        val w = world()
        val pc = w.addClient(Device.PC, 10, 8)
        val mail = w.addServer(Service.MAIL, 15, 8)
        val stock = w.routersAvailable
        val router = w.placeRouter(12, 8)!!
        assertEquals(PickUpError.NOT_A_ROUTER, w.pickUpError(pc))
        w.connect(pc, router, CableType.ISDN)
        assertEquals(PickUpError.HAS_CABLES, w.pickUpError(router))
        w.removeCable(w.cableBetween(pc, router)!!)
        assertNull(w.pickUpError(router))
        assertTrue(w.pickUp(router))
        assertEquals(stock, w.routersAvailable)
        assertFalse(router in w.nodes)
        assertTrue("the cell is free again", w.isFree(12, 8))
        assertTrue("routing still works after the node list shrank", w.connect(pc, mail, CableType.ISDN))
        assertNotNull(w.routeFor(pc, Service.MAIL))
        assertFalse("only once", w.pickUp(router))
    }

    @Test
    fun routerWithIncidentStays() {
        val w = world()
        val router = w.placeRouter(12, 8)!!
        w.announcePowerOutage(router)
        assertEquals(PickUpError.INCIDENT, w.pickUpError(router))
    }
}
