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
    fun tallViewportsAndWhatIsShown() {
        val c = camera()
        assertFalse("landscape", c.isTall)
        c.fit(MapRect(0f, 0f, 20f, 10f))
        assertTrue("the fitted area is shown", c.shows(MapRect(0f, 0f, 20f, 10f)))
        assertFalse("a wider one is not", c.shows(MapRect(-1f, 0f, 20f, 10f)))
        c.setViewport(600, 1200)
        assertTrue("portrait", c.isTall)
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

    // ---------------------------------------------------------------- rotation (docs/TOP100.md B5)

    private val angles = listOf(0f, 37f, 90f, 180f, 270f)

    private fun turnedCamera(projection: MapProjection) = camera().apply {
        this.projection = projection
        fit(MapRect(-8f, -2f, 12f, 9f))
    }

    @Test
    fun screenWorldRoundTripAtEveryAngleWithZoomAndPan() {
        val points = listOf(Vec2(0f, 0f), Vec2(4.25f, 7.5f), Vec2(-3f, 12f), Vec2(16.9f, 2.1f), Vec2(9.5f, 0.5f))
        for (projection in listOf(MapProjection.Identity, IsoProjection)) for (a in angles) {
            val c = turnedCamera(projection)
            c.rotateBy(a, 500f, 300f)
            assertEquals(a, c.angle, 1e-3f)
            for ((factor, pan) in listOf(1f to Vec2(0f, 0f), 2.5f to Vec2(40f, -25f), 0.6f to Vec2(-300f, 120f))) {
                c.zoomBy(factor, 321f, 123f)
                c.panBy(pan.x, pan.y)
                for (p in points) {
                    val s = c.worldToScreen(p)
                    assertNear(p, c.screenToWorld(s.x, s.y), 2e-3f)
                }
                for (s in listOf(Vec2(0f, 0f), Vec2(999f, 599f), Vec2(321f, 123f))) {
                    val w = c.screenToWorld(s.x, s.y)
                    assertNear(s, c.worldToScreen(w), 2e-2f)
                }
            }
        }
    }

    @Test
    fun rightAnglesTurnTheWorldExactly() {
        val c = turnedCamera(MapProjection.Identity)
        val centre = c.screenToWorld(c.centerX, c.centerY)
        c.rotateBy(90f, c.centerX, c.centerY)
        // Turned a quarter clockwise on screen: a step east in the world now points down, a step north points right.
        val o = c.worldToScreen(centre)
        val east = c.worldToScreen(Vec2(centre.x + 1f, centre.y))
        val north = c.worldToScreen(Vec2(centre.x, centre.y - 1f))
        assertNear(Vec2(0f, c.scale), Vec2(east.x - o.x, east.y - o.y), 1e-3f)
        assertNear(Vec2(c.scale, 0f), Vec2(north.x - o.x, north.y - o.y), 1e-3f)
        assertEquals("exact at right angles", 0f, c.cosA, 0f)
        assertEquals(1f, c.sinA, 0f)
        c.rotateBy(270f, c.centerX, c.centerY)
        assertEquals(0f, c.angle, 0f)
        assertEquals(1f, c.cosA, 0f)
    }

    @Test
    fun turningKeepsThePivotInPlace() {
        for (projection in listOf(MapProjection.Identity, IsoProjection)) {
            val c = turnedCamera(projection)
            val under = c.screenToWorld(700f, 180f)
            for (d in listOf(37f, 53f, 90f, -200f, 1f)) {
                c.rotateBy(d, 700f, 180f)
                assertNear(Vec2(700f, 180f), c.worldToScreen(under), 1e-2f)
            }
        }
    }

    @Test
    fun isoSquashComesAfterTheTurn() {
        // A ground circle stays the same ellipse at any angle: the squash is applied to the turned world.
        val c = turnedCamera(IsoProjection)
        fun extent(): Vec2 {
            var w = 0f; var h = 0f
            val o = c.worldToScreen(Vec2(2f, 3f))
            for (k in 0 until 360) {
                val a = Math.toRadians(k.toDouble())
                val p = c.worldToScreen(Vec2(2f + kotlin.math.cos(a).toFloat(), 3f + kotlin.math.sin(a).toFloat()))
                w = maxOf(w, abs(p.x - o.x)); h = maxOf(h, abs(p.y - o.y))
            }
            return Vec2(w, h)
        }
        val before = extent()
        assertEquals("iso ellipse is twice as wide as high", 2f, before.x / before.y, 1e-2f)
        for (a in angles) {
            c.rotateBy(a, 400f, 300f)
            assertNear(before, extent(), 0.05f)
        }
    }

    @Test
    fun releaseSnapsToTheNearestRightAngleUnlessFree() {
        val c = turnedCamera(IsoProjection)
        c.rotateBy(52f, 600f, 250f)
        val pivotWorld = c.screenToWorld(600f, 250f)
        c.settleRotation(snap = true, pivotX = 600f, pivotY = 250f)
        assertTrue(c.isRotating)
        c.step(1f / 60f)
        assertTrue("eases, does not jump", c.angle > 52f && c.angle < 90f)
        repeat(120) { c.step(1f / 60f) }
        assertFalse(c.isRotating)
        assertEquals(90f, c.angle, 0f)
        assertNear(Vec2(600f, 250f), c.worldToScreen(pivotWorld), 1e-2f)

        c.rotateBy(-60f, 500f, 300f)
        c.settleRotation(snap = true)
        repeat(120) { c.step(1f / 60f) }
        assertEquals("30° snaps back to 0°", 0f, c.angle, 0f)

        c.rotateBy(-37f, 500f, 300f)
        c.settleRotation(snap = false)
        repeat(120) { c.step(1f / 60f) }
        assertFalse(c.isRotating)
        assertEquals("free rotation stays where it is", 323f, c.angle, 1e-3f)
    }

    @Test
    fun compassTurnsBackToNorthTheShortWay() {
        val c = turnedCamera(MapProjection.Identity)
        c.rotateBy(300f, 500f, 300f)
        c.rotateTo(0f)
        c.step(1f / 60f)
        assertTrue("300° goes on up to 360°, not back down", c.angle > 300f)
        repeat(200) { c.step(1f / 60f) }
        assertEquals(0f, c.angle, 0f)
        assertEquals(0f, Camera.nearestRightAngle(44.9f), 0f)
        assertEquals(90f, Camera.nearestRightAngle(45.1f), 0f)
        assertEquals(0f, Camera.nearestRightAngle(-44f), 0f)
        assertEquals(270f, Camera.nearestRightAngle(-80f), 0f)
        assertEquals(-20f, Camera.shortestTurn(10f, 350f), 1e-4f)
        assertEquals(20f, Camera.shortestTurn(350f, 10f), 1e-4f)
    }

    @Test
    fun twoFingersTurnZoomAndPanTogetherAroundTheirMidpoint() {
        for (projection in listOf(MapProjection.Identity, IsoProjection)) {
            val c = turnedCamera(projection)
            val g = TwoFingerGesture()
            g.start(400f, 300f, 600f, 300f)
            val between = c.screenToWorld(500f, 300f)
            val scale = c.scale
            // The fingers turn by 30° clockwise, spread to 1.5 times the distance, and their midpoint moves by (+40, -20).
            val r = 150f
            val a = Math.toRadians(30.0)
            val dx = (r * kotlin.math.cos(a)).toFloat(); val dy = (r * kotlin.math.sin(a)).toFloat()
            g.move(540f - dx, 280f - dy, 540f + dx, 280f + dy, c)
            assertEquals(30f, c.angle, 1e-2f)
            assertEquals(30f, g.turned, 1e-2f)
            assertEquals(scale * 1.5f, c.scale, 1e-2f)
            assertNear(Vec2(540f, 280f), c.worldToScreen(between), 1e-1f)
            g.stop()
            val still = TwoFingerGesture()
            val before = c.angle
            still.start(400f, 300f, 600f, 300f)
            still.move(400f, 250f, 600f, 350f, c, rotate = false)
            assertEquals("rotation can be left out", before, c.angle, 0f)
        }
    }
}
