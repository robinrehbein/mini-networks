package com.mininetworks.game.render

import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.CellRect
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.Vec2
import com.mininetworks.game.game.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.hypot

/** Both styles project through their [Camera]: round trips, fitting the unlocked area, growth and touch targets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(DebugApi::class)
class RendererCameraTest {

    private val insets = ViewInsets(8f, 56f, 8f, 68f)

    private fun renderers(world: World, width: Int = 1600, height: Int = 900): List<Renderer> =
        listOf(FlatRenderer(), IsoRenderer()).onEach { it.layout(width, height, world, insets) }

    private fun assertNear(msg: String, expected: Vec2, actual: Vec2, eps: Float) {
        assertEquals("$msg x", expected.x, actual.x, eps)
        assertEquals("$msg y", expected.y, actual.y, eps)
    }

    @Test
    fun worldScreenRoundTripIncludingCamera() {
        val w = World(seed = 2L, spawnInitialNodes = false)
        val points = listOf(Vec2(0f, 0f), Vec2(8.5f, 5.5f), Vec2(31.9f, 19.9f), Vec2(16.25f, 3.75f), Vec2(12f, 18f))
        for (r in renderers(w)) {
            for ((zoom, pan) in listOf(1f to Vec2(0f, 0f), 2.2f to Vec2(-150f, 80f), 0.5f to Vec2(300f, -40f), 3f to Vec2(12f, 7f))) {
                r.camera.zoomBy(zoom, 900f, 400f)
                r.camera.panBy(pan.x, pan.y)
                for (p in points) {
                    val s = r.toScreen(p)
                    assertNear("${r.name} world->screen->world at zoom $zoom", p, r.toWorld(s.x, s.y), 1e-3f)
                    val back = r.toWorld(p.x * 50f, p.y * 40f)
                    assertNear("${r.name} screen->world->screen", Vec2(p.x * 50f, p.y * 40f), r.toScreen(back), 0.05f)
                }
                val m = r.toMap(Vec2(7f, 2f))
                assertNear("${r.name} toMap/fromMap", Vec2(7f, 2f), r.fromMap(m.x, m.y), 1e-4f)
            }
        }
    }

    @Test
    fun initialViewFitsUnlockedAreaBetweenHudRows() {
        val w = World(seed = 2L, spawnInitialNodes = false)
        for (r in renderers(w)) {
            val a = w.unlocked
            for (corner in listOf(Vec2(a.left.toFloat(), a.top.toFloat()), Vec2(a.right.toFloat(), a.top.toFloat()),
                Vec2(a.left.toFloat(), a.bottom.toFloat()), Vec2(a.right.toFloat(), a.bottom.toFloat()))) {
                val s = r.toScreen(corner)
                assertTrue("${r.name} $corner at $s inside the view", s.x >= insets.left - 0.5f && s.x <= 1600 - insets.right + 0.5f)
                assertTrue("${r.name} $corner at $s between the HUD rows", s.y >= insets.top - 0.5f && s.y <= 900 - insets.bottom + 0.5f)
            }
            val center = r.toScreen(a.center)
            assertTrue("${r.name} unlocked area is centered", hypot(center.x - 800f, center.y - (56f + (900f - 56f - 68f) / 2f)) < 60f)
            assertTrue(r.camera.followsArea)
        }
    }

    @Test
    fun portraitFramingZoomsTowardsTheHeightButKeepsEveryNode() {
        val w = World(seed = 2L, spawnInitialNodes = false)
        val a = w.unlocked
        val mid = a.center
        w.addClient(Device.PC, mid.x.toInt(), mid.y.toInt())
        w.addClient(Device.PHONE, mid.x.toInt() + 1, mid.y.toInt() - 1)
        for (r in renderers(w, 1080, 2400)) {
            val plainFit = r.camera.fitScale(r.mapBounds(a))
            assertTrue("${r.name} zooms in on the built middle", r.camera.scale > plainFit * 1.2f)
            val content = r.contentBounds(w)!!
            assertTrue("${r.name} shows every node", r.camera.shows(content))
            assertTrue("${r.name} still fits the area's height", r.camera.scale <= r.camera.fitScale(MapRect(content.left, r.mapBounds(a).top, content.right, r.mapBounds(a).bottom)) + 1e-3f)
        }
        val corner = w.addClient(Device.LAPTOP, a.left, a.bottom - 1)
        for (r in renderers(w, 1080, 2400)) {
            r.onContentChanged(w)
            repeat(200) { r.camera.step(0.05f) }
            val m = r.toMap(corner.footprintCenter)
            assertTrue("${r.name} glides out to a device on the corner", r.camera.shows(MapRect(m.x, m.y, m.x, m.y)))
        }
    }

    /**
     * Portrait only happens on large screens (OrientationPolicy: smallest width 600 dp and up). There the iso board,
     * twice as wide as high, is framed by the width, so a band of sky stays above and below it; but every tile is at
     * least as large in dp as on a landscape phone, the format the game is designed for, so devices stay as easy to
     * hit. Zooming further to the height would push nodes at the sides off the screen.
     */
    @Test
    fun portraitTabletsShowTilesAtLeastAsLargeAsALandscapePhone() {
        fun tileDp(width: Int, height: Int, density: Float): Float {
            val world = Scenes.hud()
            val r = IsoRenderer()
            r.density = density
            r.layout(width, height, world, ViewInsets(8f * density, 60f * density, 8f * density, 140f * density))
            if (r.camera.isTall) assertTrue("${width}x$height shows every node", r.camera.shows(r.contentBounds(world)!!))
            return r.mapBounds(CellRect(0, 0, 1, 1)).width * r.camera.scale / density
        }
        val phone = tileDp(2400, 1080, 3f)
        for ((w, h, d) in listOf(Triple(1600, 2560, 2f), Triple(1200, 1920, 1.5f), Triple(1600, 2000, 2f))) {
            val tablet = tileDp(w, h, d)
            assertTrue("${w}x$h at $d: tile $tablet dp, phone $phone dp", tablet >= phone)
        }
    }

    /**
     * A portrait phone window (split screen, docs/screenshots/portrait-game.png): the built network sits in the middle
     * of the space between the HUD rows, not low with empty ground above it, and no device is cut off at the sides,
     * even where the readable zoom would be larger than the width allows.
     */
    @Test
    fun portraitPhoneCentresTheNetworkAndKeepsEveryDeviceOnScreen() {
        val world = Scenes.hud()
        val d = 3f
        val insets = ViewInsets(8f * d, 60f * d, 8f * d, 140f * d)
        for (r in listOf(IsoRenderer(), FlatRenderer())) {
            r.density = d
            r.layout(1080, 2400, world, insets)
            val content = r.contentBounds(world)!!
            assertTrue("${r.javaClass.simpleName} shows every node", r.camera.shows(content))
            val midY = insets.top + (2400f - insets.top - insets.bottom) / 2f
            val y = r.camera.toScreenY(content.centerY)
            assertTrue("${r.javaClass.simpleName}: network centre at $y, view centre $midY", abs(y - midY) < 30f)
            val x = r.camera.toScreenX(content.centerX)
            assertTrue("${r.javaClass.simpleName}: centred across, $x", abs(x - 540f) < 30f)
        }
    }

    /** docs/TOP100.md B4: in landscape the built network fills the view instead of the whole diamond of the area. */
    @Test
    fun landscapeFramingZoomsOnTheNetworkButKeepsEveryNode() {
        val w = World(seed = 2L, spawnInitialNodes = false)
        val a = w.unlocked
        val mid = a.center
        w.addClient(Device.PC, mid.x.toInt() - 1, mid.y.toInt())
        w.addClient(Device.PHONE, mid.x.toInt() + 1, mid.y.toInt() - 1)
        for (r in renderers(w)) {
            val plainFit = r.camera.fitScale(r.mapBounds(a))
            assertTrue("${r.name} zooms in on the built middle", r.camera.scale > plainFit * 1.2f)
            assertTrue("${r.name} shows every node", r.camera.shows(r.contentBounds(w)!!))
        }
        val corner = w.addClient(Device.LAPTOP, a.left, a.bottom - 1)
        for (r in renderers(w)) {
            r.onContentChanged(w)
            repeat(200) { r.camera.step(0.05f) }
            val m = r.toMap(corner.footprintCenter)
            assertTrue("${r.name} glides out to a device on the corner", r.camera.shows(MapRect(m.x, m.y, m.x, m.y)))
        }
    }

    @Test
    fun zoomRangeReachesWholeGridAndCloseUp() {
        val w = World(seed = 2L, spawnInitialNodes = false)
        for (r in renderers(w)) {
            val fit = r.camera.scale
            r.camera.zoomBy(0.01f, 800f, 450f)
            val whole = r.mapBounds(w.bounds)
            assertTrue("${r.name} can zoom out to the whole grid", r.camera.scale <= r.camera.fitScale(whole))
            assertTrue(r.camera.scale < fit)
            r.camera.zoomBy(1000f, 800f, 450f)
            assertEquals(r.camera.fitScale(r.mapBounds(CellRect(0, 0, Renderer.ZOOM_IN_COLS, Renderer.ZOOM_IN_ROWS))), r.camera.scale, 1e-3f)
        }
    }

    @Test
    fun growthRefitsOnlyWhileFollowingTheArea() {
        val w = World(seed = 2L, spawnInitialNodes = false)
        val (follow, moved) = renderers(w)
        moved.camera.panBy(40f, 10f)
        val movedScale = moved.camera.scale
        val before = follow.camera.scale
        w.jumpToWeek(5)
        listOf(follow, moved).forEach { it.onAreaChanged(w) }
        repeat(240) { follow.camera.step(1f / 60f); moved.camera.step(1f / 60f) }
        assertEquals(follow.camera.fitScale(follow.mapBounds(w.unlocked)), follow.camera.scale, 1e-3f)
        assertTrue("zoomed out to show the new ring", follow.camera.scale < before)
        assertEquals("the player's view stays", movedScale, moved.camera.scale, 0f)
        assertFalse(moved.camera.followsArea)
        moved.fitArea(w, animate = false)
        assertTrue(moved.camera.followsArea)
        assertEquals(moved.camera.fitScale(moved.mapBounds(w.unlocked)), moved.camera.scale, 1e-3f)
    }

    @Test
    fun touchTargetsStayAtLeast48dpAtAnyZoom() {
        val w = World(seed = 2L, spawnInitialNodes = false)
        val pc = w.addClient(Device.PC, 12, 8)
        val server = w.addServer(Service.MAIL, 18, 11)
        w.grant(100)
        check(w.connect(pc, server, CableType.ISDN))
        for (density in listOf(1f, 2.75f)) for (r in renderers(w, 2400, 1080)) {
            for (zoom in listOf(0.01f, 1f, 1000f)) {
                r.fitArea(w, animate = false)
                r.camera.zoomBy(zoom, 1200f, 540f)
                val radius = TouchTargets.nodeRadiusPx(r, density)
                assertTrue("${r.name} radius $radius", radius >= TouchTargets.MIN_DP / 2f * density)
                val c = r.toScreen(pc.center)
                val near = radius * 0.95f
                assertSame("${r.name} zoom $zoom density $density", pc, r.nodeAtScreen(w, c.x + near, c.y, radius))
                assertSame(pc, r.nodeAtScreen(w, c.x, c.y - near, radius))
                assertNull("outside the target", r.nodeAtScreen(w, c.x + radius * 1.05f, c.y, radius, except = server))
                assertNull("except skips the node", r.nodeAtScreen(w, c.x, c.y, radius, except = pc)?.takeIf { it === pc })
            }
            // Cables: a finger 20 dp beside the line still hits it when zoomed far out.
            r.camera.zoomBy(0.01f, 1200f, 540f)
            val cable = w.cables.single()
            val mid = r.toScreen(cable.layout.pointAt(0.5f))
            val cableRadius = TouchTargets.cableRadiusPx(r, density)
            assertTrue(cableRadius >= TouchTargets.MIN_DP / 2f * density)
            assertSame(cable, r.cableAtScreen(w, mid.x, mid.y + 20f * density, cableRadius))
        }
    }

    /**
     * docs/TOP100.md B5: after turning the map (0°, 37°, 90°, 180°, 270°, with zoom and pan), screen and world still map
     * onto each other, every cell's centre on screen leads back to the same cell, and a tap on a node or cable finds it.
     */
    @Test
    fun turnedMapsRoundTripAndHitTheSameCells() {
        val w = World(seed = 2L, spawnInitialNodes = false)
        for (row in w.water) row.fill(false)
        w.grant(100)
        val pc = w.addClient(Device.PC, 12, 8)
        val server = w.addServer(Service.MAIL, 18, 11)
        check(w.connect(pc, server, CableType.ISDN))
        val points = listOf(Vec2(0f, 0f), Vec2(8.5f, 5.5f), Vec2(31.9f, 19.9f), Vec2(16.25f, 3.75f))
        for (angle in listOf(0f, 37f, 90f, 180f, 270f)) for (r in renderers(w)) {
            for ((zoom, pan) in listOf(1f to Vec2(0f, 0f), 2.2f to Vec2(-150f, 80f), 0.7f to Vec2(60f, -40f))) {
                r.fitArea(w, animate = false)
                r.rotateBy(angle, 700f, 420f, w)
                assertEquals(angle, r.camera.angle, 1e-3f)
                r.camera.zoomBy(zoom, 900f, 400f)
                r.camera.panBy(pan.x, pan.y)
                val tag = "${r.name} at $angle° zoom $zoom"
                for (p in points) {
                    val s = r.toScreen(p)
                    assertNear("$tag world->screen->world", p, r.toWorld(s.x, s.y), 2e-3f)
                    val back = r.toWorld(p.x * 50f, p.y * 40f)
                    assertNear("$tag screen->world->screen", Vec2(p.x * 50f, p.y * 40f), r.toScreen(back), 0.05f)
                }
                for (y in 0 until w.rows) for (x in 0 until w.cols) {
                    val s = r.toScreen(Vec2(x + 0.5f, y + 0.5f))
                    val back = r.toWorld(s.x, s.y)
                    assertEquals("$tag cell ($x, $y)", x, kotlin.math.floor(back.x).toInt())
                    assertEquals("$tag cell ($x, $y)", y, kotlin.math.floor(back.y).toInt())
                }
                val radius = TouchTargets.nodeRadiusPx(r, 2f)
                val c = r.toScreen(pc.center)
                assertSame(tag, pc, r.nodeAtScreen(w, c.x + radius * 0.5f, c.y - radius * 0.5f, radius))
                val mid = r.toScreen(w.cables.single().layout.pointAt(0.5f))
                assertSame(tag, w.cables.single(), r.cableAtScreen(w, mid.x + 3f, mid.y, TouchTargets.cableRadiusPx(r, 2f)))
                r.rotateBy(-angle, 700f, 420f, w)
            }
        }
    }

    @Test
    fun zoomRangeDoesNotDependOnTheAngle() {
        val w = World(seed = 2L, spawnInitialNodes = false)
        for (r in renderers(w)) {
            val min = r.camera.minScale; val max = r.camera.maxScale
            for (a in listOf(37f, 45f, 90f, 133f)) {
                r.rotateBy(a - r.camera.angle, 800f, 450f, w)
                assertEquals("${r.name} at $a°", min, r.camera.minScale, 1e-4f)
                assertEquals("${r.name} at $a°", max, r.camera.maxScale, 1e-4f)
                val centre = r.toWorld(r.camera.centerX, r.camera.centerY)
                assertTrue("${r.name}: the view centre stays over the grid at $a°", centre.x in -0.5f..w.cols + 0.5f && centre.y in -0.5f..w.rows + 0.5f)
            }
        }
    }

    /** True if all of the playable area (with its buildings and board edge) lies between the HUD insets. */
    private fun showsArea(r: Renderer, w: World) = r.camera.shows(r.mapBounds(w.unlocked))

    /**
     * Tilting keeps the playable area framed: from the automatic framing, the iso view tilted to either end of the
     * range (at once, and step by step as the buttons animate it, from the corner and a side-on view) still shows all
     * of the unlocked area between the HUD rows, and tilting back gives the zoom back.
     */
    @Test
    fun tiltingToTheExtremesKeepsTheAreaInView() {
        val w = World(seed = 2L, spawnInitialNodes = false)
        for (angle in listOf(0f, 45f)) {
            val r = IsoRenderer().also { it.layout(1600, 900, w, insets) }
            r.rotateBy(angle, r.camera.centerX, r.camera.centerY, w)
            r.fitArea(w, animate = false)
            assertTrue("fits at the classic pitch at $angle°", showsArea(r, w))
            val scale = r.camera.scale
            for (pitch in listOf(Camera.TILT_MAX, Camera.TILT_MIN, Camera.TILT_MAX)) {
                r.tiltBy(pitch - r.camera.tilt, r.camera.centerX, r.camera.centerY, w)
                assertEquals(pitch, r.camera.tilt, 0f)
                assertTrue("all of the area on screen at $pitch° pitch, $angle°", showsArea(r, w))
            }
            assertTrue("a steep view zooms out for the taller map", r.camera.scale < scale * 0.8f)
            // The buttons: animated steps.
            while (r.camera.canTilt(-1)) {
                r.camera.tiltStep(-1)
                repeat(40) { r.stepCamera(1f / 60f, w); assertTrue("while tilting flatter at $angle°", showsArea(r, w)) }
            }
            assertEquals(Camera.TILT_MIN, r.camera.tilt, 0f)
            r.camera.tiltTo(Camera.DEFAULT_TILT)
            repeat(120) { r.stepCamera(1f / 60f, w) }
            assertEquals("tilting back gives the zoom back", scale, r.camera.scale, scale * 1e-3f)
            assertTrue(showsArea(r, w))
            assertTrue("the automatic framing still follows the area", r.camera.followsArea)
        }
    }

    /** A player zoomed in closer than the framing keeps the zoom when tilting; the limits follow the pitch. */
    @Test
    fun tiltingKeepsAPlayersCloseZoom() {
        val w = World(seed = 2L, spawnInitialNodes = false)
        val r = IsoRenderer().also { it.layout(1600, 900, w, insets) }
        r.camera.zoomBy(2f, 700f, 400f)
        val scale = r.camera.scale
        val min = r.camera.minScale
        r.tiltBy(Camera.TILT_MAX - r.camera.tilt, 700f, 400f, w)
        assertEquals("zoomed in: the zoom stays", scale, r.camera.scale, 0f)
        assertTrue("a steep view may zoom out further, so the taller grid still fits", r.camera.minScale < min)
        val centre = r.toWorld(r.camera.centerX, r.camera.centerY)
        assertTrue("the view centre stays over the grid", centre.x in -0.5f..w.cols + 0.5f && centre.y in -0.5f..w.rows + 0.5f)
    }
}
