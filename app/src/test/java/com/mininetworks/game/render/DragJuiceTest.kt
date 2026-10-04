package com.mininetworks.game.render

import android.graphics.RectF
import com.mininetworks.game.game.CableLayout
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Where the drag bubble goes ([DragJuice.place]) and which full nodes get a red ring ([PortDots.emphasis]). */
@RunWith(RobolectricTestRunner::class)
class DragJuiceTest {
    private val screen = RectF(0f, 100f, 1000f, 900f)
    private val out = RectF()

    private fun place(x: Float, y: Float, w: Float = 200f, h: Float = 80f): Float = DragJuice.place(w, h, x, y, lift = 60f, tail = 12f, margin = 8f, bounds = screen, out = out)

    @Test
    fun bubbleAboveThePointWhenThereIsRoom() {
        place(500f, 500f)
        assertEquals(500f - 60f, out.bottom, 0.01f)
    }

    @Test
    fun bubbleFlipsBelowNearTheTop() {
        place(500f, 120f)
        assertTrue("below the point", out.top > 120f)
        assertTrue(out.top >= screen.top + 8f + 12f)
    }

    @Test
    fun bubbleStaysInsideAtEveryEdge() {
        for (x in listOf(-50f, 0f, 500f, 1000f, 1200f)) for (y in listOf(-50f, 100f, 500f, 900f, 1200f)) {
            place(x, y)
            assertTrue("$x/$y left", out.left >= screen.left + 8f - 0.01f)
            assertTrue("$x/$y right", out.right <= screen.right - 8f + 0.01f)
            assertTrue("$x/$y top", out.top >= screen.top + 8f - 0.01f)
            assertTrue("$x/$y bottom", out.bottom <= screen.bottom - 8f + 0.01f)
        }
    }

    /** A HUD box over the spot above the point (the view buttons): the bubble flips below, or slides beside it. */
    @Test
    fun bubbleKeepsClearOfReservedHudBoxes() {
        val labels = ServerLabels()
        val buttons = RectF(400f, 300f, 700f, 440f)
        labels.reserve(buttons)
        for (x in listOf(420f, 500f, 550f, 680f)) {
            DragJuice.place(200f, 80f, x, 500f, lift = 60f, tail = 12f, margin = 8f, bounds = screen, out = out, avoid = labels)
            assertTrue("$x: $out clear of $buttons", !RectF.intersects(out, buttons))
        }
        // Low down, with no room below, it slides beside the box instead.
        val low = RectF(0f, 100f, 1000f, 520f)
        DragJuice.place(200f, 80f, 550f, 470f, lift = 60f, tail = 12f, margin = 8f, bounds = low, out = out, avoid = labels)
        assertTrue("$out clear of $buttons", !RectF.intersects(out, buttons))
        assertTrue("slid to the side: $out", out.right <= buttons.left || out.left >= buttons.right)
        // Nothing reserved there: as before.
        labels.clearReserved()
        DragJuice.place(200f, 80f, 550f, 500f, lift = 60f, tail = 12f, margin = 8f, bounds = screen, out = out, avoid = labels)
        assertEquals(500f - 60f, out.bottom, 0.01f)
    }

    /** At a large text size the tail is longer than the margin: its tip must not poke into a HUD box either. */
    @Test
    fun bubbleTailKeepsClearOfReservedHudBoxes() {
        val labels = ServerLabels()
        val buttons = RectF(400f, 455f, 700f, 470f)
        labels.reserve(buttons)
        val tail = 40f
        DragJuice.place(200f, 80f, 550f, 500f, lift = 60f, tail = tail, margin = 8f, bounds = screen, out = out, avoid = labels)
        val withTail = RectF(out).apply { if (out.top > 500f) top -= tail else bottom += tail }
        assertTrue("$withTail clear of $buttons", !RectF.intersects(withTail, buttons))
    }

    @Test
    fun onlyFullNodesNearTheFingerGetTheRing() {
        val w = Scenes.hud()
        val full = w.nodes.first { it.device == Device.PC }
        check(w.ports(full) == full.maxPorts)
        val from = w.nodes.first { it.device == Device.WATCH }
        fun drag(end: Vec2) = DragPreview(from, end, null, CableType.FIBER, CableLayout(listOf(end)), false, null)
        val c = full.footprintCenter
        assertEquals(PortDots.Emphasis.FULL, PortDots.emphasis(w, full, drag(Vec2(c.x + 1f, c.y)), false))
        assertEquals(PortDots.Emphasis.FULL_QUIET, PortDots.emphasis(w, full, drag(Vec2(c.x + 40f, c.y)), false))
        assertNotNull(PortDots.emphasis(w, full, drag(Vec2(c.x + 40f, c.y)), false))
    }
}
