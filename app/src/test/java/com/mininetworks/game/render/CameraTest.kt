package com.mininetworks.game.render

import com.mininetworks.game.game.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Camera math on the plain JVM: fitting, zoom around a pivot, pan, limits and the two-finger gesture. */
class CameraTest {

    private fun camera() = Camera().apply {
        setViewport(1000, 600, ViewInsets(0f, 50f, 0f, 50f))
        setZoomRange(10f, 400f)
    }

    private fun assertNear(expected: Vec2, actual: Vec2, eps: Float = 1e-3f) {
        assertEquals("x", expected.x, actual.x, eps)
        assertEquals("y", expected.y, actual.y, eps)
    }

    @Test
    fun fitCentersAreaInsideInsets() {
        val c = camera()
        val r = MapRect(0f, 0f, 20f, 10f)
        c.fit(r)
        assertEquals("width 1000/20 vs inset height 500/10", 50f, c.scale, 1e-4f)
        assertNear(Vec2(500f, 300f), c.toScreen(Vec2(10f, 5f)))
        assertNear(Vec2(0f, 50f), c.toScreen(Vec2(0f, 0f)))
        assertNear(Vec2(1000f, 550f), c.toScreen(Vec2(20f, 10f)))
        assertTrue(c.followsArea)
    }

    @Test
    fun portraitFillsTowardsTheHeight() {
        val c = camera()
        val r = MapRect(0f, 0f, 20f, 10f)
        assertEquals("landscape: plain fit", c.fitScale(r), c.fillScale(r, 1.5f), 1e-4f)
        c.setViewport(600, 1200)
        assertEquals("portrait fit is width-bound", 30f, c.fitScale(r), 1e-4f)
        assertEquals("zooms in, but overflows the sides by at most 1.5×", 45f, c.fillScale(r, 1.5f), 1e-4f)
        assertEquals("a tall area just fills the height", 60f, c.fillScale(MapRect(0f, 0f, 10f, 20f), 1.5f), 1e-4f)
    }

    @Test
    fun screenAndMapRoundTripAtAnyZoomAndPan() {
        val c = camera()
        c.fit(MapRect(-3f, 2f, 17f, 12f))
        val points = listOf(Vec2(0f, 0f), Vec2(4.25f, 7.5f), Vec2(-3f, 12f), Vec2(16.9f, 2.1f))
        for ((factor, pan) in listOf(1f to Vec2(0f, 0f), 2.5f to Vec2(40f, -25f), 0.4f to Vec2(-300f, 120f), 7f to Vec2(3f, 3f))) {
            c.zoomBy(factor, 321f, 123f)
            c.panBy(pan.x, pan.y)
            for (p in points) {
                assertNear(p, c.toMap(c.toScreen(p).x, c.toScreen(p).y))
                val s = Vec2(p.x * 37f, p.y * 11f)
                assertNear(s, c.toScreen(c.toMap(s.x, s.y)), 1e-2f)
            }
        }
    }

    @Test
    fun zoomKeepsPivotInPlaceAndClamps() {
        val c = camera()
        c.fit(MapRect(0f, 0f, 20f, 10f))
        val under = c.toMap(700f, 200f)
        c.zoomBy(2f, 700f, 200f)
        assertEquals(100f, c.scale, 1e-4f)
        assertNear(Vec2(700f, 200f), c.toScreen(under))
        assertFalse("player zoom stops following the area", c.followsArea)
        c.zoomBy(100f, 700f, 200f)
        assertEquals(400f, c.scale, 0f)
        c.zoomBy(0.0001f, 700f, 200f)
        assertEquals(10f, c.scale, 0f)
    }

    @Test
    fun panMovesPictureWithFingerAndStaysOverBounds() {
        val c = camera()
        c.fit(MapRect(0f, 0f, 20f, 10f))
        c.panBounds = MapRect(0f, 0f, 20f, 10f)
        val before = c.toScreen(Vec2(3f, 3f))
        c.panBy(60f, -40f)
        assertNear(Vec2(before.x + 60f, before.y - 40f), c.toScreen(Vec2(3f, 3f)))
        c.panBy(1e6f, 1e6f)
        assertNear(Vec2(0f, 0f), c.toMap(500f, 300f))
        c.panBy(-1e6f, -1e6f)
        assertNear(Vec2(20f, 10f), c.toMap(500f, 300f))
    }

    @Test
    fun animatedFitGlidesToTarget() {
        val c = camera()
        c.fit(MapRect(0f, 0f, 20f, 10f))
        c.zoomBy(3f, 100f, 100f)
        c.fit(MapRect(-2f, -1f, 22f, 11f), animate = true)
        assertTrue(c.isAnimating)
        assertTrue(c.followsArea)
        repeat(120) { c.step(1f / 60f) }
        assertFalse(c.isAnimating)
        assertEquals(c.fitScale(MapRect(-2f, -1f, 22f, 11f)), c.scale, 1e-4f)
        assertNear(Vec2(500f, 300f), c.toScreen(Vec2(10f, 5f)))
    }

    @Test
    fun gentleGlideIsSlowerAndEndsOnTheTarget() {
        val gentle = camera().apply { fit(MapRect(0f, 0f, 20f, 10f)) }
        val quick = camera().apply { fit(MapRect(0f, 0f, 20f, 10f)) }
        gentle.glideTo(4f, 3f, 80f, gentle = true)
        quick.glideTo(4f, 3f, 80f)
        assertFalse("a focus is not the fitted area", gentle.followsArea)
        repeat(10) { gentle.step(1f / 60f); quick.step(1f / 60f) }
        assertTrue("gentle glide has further to go", abs(80f - gentle.scale) > abs(80f - quick.scale) * 2f)
        repeat(300) { gentle.step(1f / 60f) }
        assertFalse(gentle.isAnimating)
        assertEquals(80f, gentle.scale, 1e-4f)
        assertNear(Vec2(500f, 300f), gentle.toScreen(Vec2(4f, 3f)))
    }

    @Test
    fun glideStaysInZoomRangeAndPanBounds() {
        val c = camera()
        c.fit(MapRect(0f, 0f, 20f, 10f))
        c.panBounds = MapRect(0f, 0f, 20f, 10f)
        c.glideTo(-50f, 40f, 5000f)
        repeat(300) { c.step(1f / 60f) }
        assertEquals(400f, c.scale, 0f)
        assertNear(Vec2(0f, 10f), c.toMap(500f, 300f))
    }

    @Test
    fun playerInputStopsAnimation() {
        val c = camera()
        c.fit(MapRect(0f, 0f, 20f, 10f))
        c.fit(MapRect(-5f, -5f, 25f, 15f), animate = true)
        c.step(1f / 60f)
        c.panBy(10f, 0f)
        assertFalse(c.isAnimating)
        assertFalse(c.followsArea)
    }

    @Test
    fun twoFingersPanAndZoomAroundTheirMidpoint() {
        val c = camera()
        c.fit(MapRect(0f, 0f, 20f, 10f))
        val g = TwoFingerGesture()
        g.start(400f, 300f, 600f, 300f)
        val between = c.toMap(500f, 300f)
        // Fingers spread to twice the distance while their midpoint moves by (+50, +20).
        g.move(350f, 320f, 750f, 320f, c)
        assertEquals(100f, c.scale, 1e-3f)
        assertNear(Vec2(550f, 320f), c.toScreen(between), 1e-2f)
        g.stop()
        assertFalse(g.isActive)
    }
}
