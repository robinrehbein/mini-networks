package com.mininetworks.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.World
import org.junit.Assert.assertFalse
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
}
