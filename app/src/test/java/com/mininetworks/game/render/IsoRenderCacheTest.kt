package com.mininetworks.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.StressWorld
import com.mininetworks.game.game.World
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The frame-time caches of the [IsoRenderer] (docs/TOP100.md section 4, "Iso-Rendering, volle Szene"): laid cables live
 * in the ground layer and packets are copied from pre-drawn sprites once the zoom holds still. Both must look like
 * drawing everything directly and must follow every change of the network.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
@OptIn(DebugApi::class)
class IsoRenderCacheTest {

    private val width = 1200
    private val height = 540

    private fun stress() = StressWorld.build().also { w -> repeat(60 * 5) { w.update(1f / 60f) } }

    private fun fresh(w: World) = IsoRenderer().also { it.density = 2f; it.layout(width, height, w) }

    private fun frame(r: IsoRenderer, w: World) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { r.draw(Canvas(it), w, drag = null, time = 1.3f) }

    @Test
    fun spritesAndCachedCablesLookLikeDrawingDirectly() {
        val w = stress()
        assertTrue("the scene has packets", w.packets.size > 100)
        val first = fresh(w)
        val direct = frame(first, w)
        assertFalse("the first frame draws packets directly", first.packetSpritesOn)
        val r = fresh(w)
        frame(r, w)
        val cached = frame(r, w)
        assertTrue(r.packetSpritesOn)
        assertTrue(r.groundCached(w, width, height))
        // Sprites sit on whole pixels, so an edge may move by up to half a pixel (more where packets pile up): every
        // pixel must match one within a pixel of it in the other frame.
        val off = mismatches(direct, cached) + mismatches(cached, direct)
        assertTrue("$off pixels have no match within one pixel", off <= MAX_MISMATCHES)
    }

    @Test
    fun aCableLeavesTheGroundWhileItGrowsOrIsCutAndReturnsAfterwards() {
        val w = stress()
        val r = fresh(w)
        frame(r, w); frame(r, w)
        assertTrue(r.groundCached(w, width, height))
        val cable = w.cables.first()
        w.announceExcavator(cable)
        repeat(60 * 30) { if (!w.isCut(cable)) w.update(1f / 60f) }
        assertTrue(w.isCut(cable))
        assertFalse("a cut cable is drawn over the ground, not in it", r.groundCached(w, width, height))
        frame(r, w)
        val cut = frame(r, w)
        assertTrue(r.groundCached(w, width, height))
        assertTrue("the same frame drawn from scratch", matchesWithinAPixel(cut, frame(fresh(w).also { frame(it, w) }, w)))
    }

    @Test
    fun anUpgradedCableRebuildsTheGround() {
        val w = World(cols = 12, rows = 7, seed = 3L, spawnInitialNodes = false)
        for (row in w.water) row.fill(false)
        w.incidentsEnabled = false
        w.jumpToWeek(CableType.FIBER.unlockWeek)
        w.grant(400)
        val mail = w.addServer(Service.MAIL, 2, 1)
        val pc = w.addClient(Device.PC, 8, 4)
        assertTrue(w.connect(pc, mail, CableType.ISDN))
        repeat(60 * 3) { w.update(1f / 60f) }
        val r = fresh(w)
        frame(r, w); frame(r, w)
        assertTrue(r.groundCached(w, width, height))
        assertTrue(w.upgrade(w.cableBetween(pc, mail)!!, CableType.FIBER))
        assertFalse("the new cable colour must reach the ground", r.groundCached(w, width, height))
        frame(r, w)
        val upgraded = frame(r, w)
        assertTrue(r.groundCached(w, width, height))
        assertTrue(matchesWithinAPixel(upgraded, frame(fresh(w).also { frame(it, w) }, w)))
    }

    @Test
    fun packetSpritesFollowTheZoom() {
        val w = stress()
        val r = fresh(w)
        frame(r, w); frame(r, w)
        assertTrue(r.packetSpritesOn)
        r.camera.zoomBy(1.3f, width / 2f, height / 2f)
        frame(r, w)
        assertFalse("while the zoom changes packets are drawn directly", r.packetSpritesOn)
        val zoomed = frame(r, w)
        assertTrue(r.packetSpritesOn)
        val direct = fresh(w).also { it.camera.zoomBy(1.3f, width / 2f, height / 2f) }
        assertTrue("sprites redrawn for the new zoom", matchesWithinAPixel(zoomed, frame(direct, w)))
    }

    private fun matchesWithinAPixel(a: Bitmap, b: Bitmap) = mismatches(a, b) + mismatches(b, a) <= MAX_MISMATCHES

    /** Pixels of [a] (every second row and column) with no pixel of [b] within one pixel that is close in colour. */
    private fun mismatches(a: Bitmap, b: Bitmap): Int {
        var n = 0
        for (y in 1 until height - 1 step 2) for (x in 1 until width - 1 step 2) {
            val p = a.getPixel(x, y)
            var found = false
            loop@ for (dy in -1..1) for (dx in -1..1) {
                if (close(p, b.getPixel(x + dx, y + dy))) { found = true; break@loop }
            }
            if (!found) n++
        }
        return n
    }

    private fun close(p: Int, q: Int) =
        abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)) <= TOLERANCE && abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)) <= TOLERANCE &&
            abs((p and 0xFF) - (q and 0xFF)) <= TOLERANCE

    private companion object {
        /** Largest channel difference of an anti-aliased edge that moved by up to half a pixel. */
        const val TOLERANCE = 64
        /** Sampled pixels (a quarter of the frame) allowed without a match: well under one packet's worth each. */
        const val MAX_MISMATCHES = 60
    }
}
