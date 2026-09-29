package com.mininetworks.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.mininetworks.game.game.Bend
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.Cell
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The isometric ground layer is cached while nothing changes and looks exactly like drawing it directly. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
@OptIn(DebugApi::class)
class IsoGroundCacheTest {

    private fun world() = World(seed = 7L).also { it.incidentsEnabled = false; repeat(60 * 8) { _ -> it.update(1f / 60f) } }

    private fun frame(r: IsoRenderer, w: World) =
        Bitmap.createBitmap(800, 450, Bitmap.Config.ARGB_8888).also { r.draw(Canvas(it), w, drag = null, time = 2f) }

    private fun fresh(w: World) = IsoRenderer().also { it.layout(800, 450, w) }

    @Test
    fun cachedFrameLooksLikeADirectOne() {
        val w = world()
        val r = fresh(w)
        val direct = frame(r, w)
        assertFalse("first frame after a change is drawn directly", r.groundCached(w, 800, 450))
        val cached = frame(r, w)
        assertTrue(r.groundCached(w, 800, 450))
        assertTrue(direct.sameAs(cached))
        assertTrue(cached.sameAs(frame(r, w)))
    }

    @Test
    fun mapChangesRebuildTheCache() {
        val w = world()
        val r = fresh(w)
        frame(r, w); frame(r, w)
        val cell = Scenery.decorations(w).first { w.isFree(it.first.x, it.first.y) }.first
        w.addClient(Device.PC, cell.x, cell.y)
        assertFalse("a new node invalidates the ground", r.groundCached(w, 800, 450))
        val rebuilt = frame(r, w)
        assertTrue(r.groundCached(w, 800, 450))
        assertTrue("the decoration under the new node is gone", rebuilt.sameAs(frame(fresh(w), w)))
    }

    @Test
    fun movingCameraDrawsDirectlyUntilItHoldsStill() {
        val w = world()
        val r = fresh(w)
        frame(r, w); frame(r, w)
        r.camera.panBy(30f, 10f)
        frame(r, w)
        assertFalse(r.groundCached(w, 800, 450))
        r.camera.panBy(30f, 10f)
        frame(r, w)
        assertFalse("still moving", r.groundCached(w, 800, 450))
        val settled = frame(r, w)
        assertTrue(r.groundCached(w, 800, 450))
        val direct = IsoRenderer().also { it.layout(800, 450, w); repeat(2) { _ -> it.camera.panBy(30f, 10f) } }
        assertTrue(settled.sameAs(frame(direct, w)))
    }

    /** The ground depends on the pitch (squash, relief heights, board edge): a tilt redraws it and caches it again. */
    @Test
    fun tiltingRebuildsTheCache() {
        val w = world()
        for (pitch in listOf(Camera.TILT_MIN, Camera.TILT_MAX)) {
            val r = fresh(w)
            frame(r, w); frame(r, w)
            assertTrue(r.groundCached(w, 800, 450))
            r.camera.tiltBy(pitch - r.camera.tilt)
            assertFalse("a new pitch invalidates the ground", r.groundCached(w, 800, 450))
            frame(r, w)
            val settled = frame(r, w)
            assertTrue(r.groundCached(w, 800, 450))
            val direct = IsoRenderer().also { it.layout(800, 450, w); it.camera.tiltBy(pitch - it.camera.tilt) }
            assertTrue("$pitch°: the cached ground looks like a direct one", settled.sameAs(frame(direct, w)))
        }
    }

    @Test
    fun anExcavatorNeverStandsOnADecoration() {
        fun dry(w: World, x: Int, y: Int) = x in 0 until w.cols && y in 0 until w.rows && w.isFree(x, y)
        fun decorated(w: World, x: Int, y: Int) = dry(w, x, y) && Scenery.planned(w.seed, x, y) != null
        // A cable through (x, y + 1) with decorations on both sides: the excavator has to stand on one of them.
        fun spot(w: World) = (0 until w.rows).flatMap { y -> (0 until w.cols).map { x -> Cell(x, y) } }.firstOrNull { (x, y) ->
            decorated(w, x, y) && decorated(w, x, y + 2) && dry(w, x - 1, y + 1) && dry(w, x, y + 1) && dry(w, x + 1, y + 1)
        }
        val (w, tree) = (1L..200L).asSequence().map { World(seed = it, spawnInitialNodes = false) }
            .firstNotNullOf { w -> spot(w)?.let { w to it } }
        w.incidentsEnabled = false
        w.grant(50)
        val pc = w.addClient(Device.PC, tree.x - 1, tree.y + 1)
        val mail = w.addServer(Service.MAIL, tree.x + 1, tree.y + 1)
        assertTrue(w.connect(pc, mail, CableType.ISDN, Bend.HORIZONTAL_FIRST))
        val r = fresh(w)
        frame(r, w); frame(r, w)
        w.announceExcavator(w.cables.single())
        assertFalse("the excavator changes the ground", r.groundCached(w, 800, 450))
        val stand = r.excavatorCells(w).single()
        assertNotNull(Scenery.planned(w.seed, stand.x, stand.y))
        val visible = Scenery.decorations(w, Scenery.occupied(w, r.excavatorCells(w))).map { it.first }
        assertFalse("the decoration under the excavator is hidden", stand in visible)
        assertEquals("only that one", Scenery.decorations(w).size - 1, visible.size)
    }
}
