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
}
