package com.mininetworks.game.render

import com.mininetworks.game.game.Bend
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Both styles must read cable geometry from the model, so they agree on paths, packets and hit tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(DebugApi::class)
class RendererLayoutTest {

    @Test
    fun stylesShareCablePathsAndPacketPositions() {
        val w = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false)
        for (row in w.water) row.fill(false)
        w.grant(100)
        w.jumpToWeek(CableType.DSL.unlockWeek)
        val phone = w.addClient(Device.PHONE, 1, 1)
        val router = w.addRouter(4, 3)
        val server = w.addServer(Service.CALL, 2, 6)
        w.connect(phone, router, CableType.DSL, Bend.VERTICAL_FIRST)
        w.connect(server, router, CableType.DSL, Bend.HORIZONTAL_FIRST)

        val renderers = listOf(FlatRenderer(), IsoRenderer())
        renderers.forEach { it.layout(1600, 900, w) }
        for (c in w.cables) renderers.forEach { assertEquals(c.layout.waypoints, it.cablePath(c)) }

        var compared = 0
        repeat(60 * 20) {
            w.update(1f / 60f)
            for (p in w.packets) {
                val expected = w.packetPosition(p)
                renderers.forEach { assertEquals(expected, it.packetPosition(w, p)) }
                compared++
            }
        }
        assertTrue("expected packets in flight", compared > 0)

        // Tapping the corner of an L selects that cable in both styles.
        val corner = w.cableBetween(phone, router)!!.layout.waypoints[1]
        renderers.forEach { assertSame(w.cableBetween(phone, router), it.cableNear(w, corner)) }
    }
}
