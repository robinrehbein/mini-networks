package com.mininetworks.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World
import com.mininetworks.game.ui.GameView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pre-warning on a device's request bubble ([QueueGauge]): calm below [World.Tuning.PREWARN_PENDING] waiting
 * requests, orange from there on, red once the queue is full, in every style.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
@OptIn(DebugApi::class)
class QueueGaugeTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val bmp = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)

    /** Pixels of exactly [color] with [pending] requests at a lone PC, drawn in [style]. */
    private fun pixels(style: String, pending: Int, color: Int): Int {
        render(style, pending)
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return px.count { it == color }
    }

    /** Draws [pending] requests at a lone PC in [style], zoomed in on it; returns the PC. */
    private fun render(style: String, pending: Int): Node {
        SettingsStore(app).tutorialSeen = true
        val world = World(seed = 2L, spawnInitialNodes = false)
        world.incidentsEnabled = false
        for (row in world.water) row.fill(false)
        val u = world.unlocked
        val pc: Node = world.addClient(Device.PC, (u.left + u.right) / 2, (u.top + u.bottom) / 2)
        repeat(pending) { pc.pending.addLast(Service.MAIL) }
        val view = GameView(app)
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 0f, style = style)
        val r = view.activeRenderer
        val p = r.toScreen(pc.footprintCenter)
        r.camera.zoomBy(3f, p.x, p.y)
        view.drawCurrent(Canvas(bmp))
        return pc
    }

    private fun check(style: String) {
        val warn = World.Tuning.PREWARN_PENDING
        val calm = pixels(style, warn - 1, QueueGauge.WARN_FILL)
        val warned = pixels(style, warn, QueueGauge.WARN_FILL)
        assertTrue("$style: orange bubble from $warn requests ($calm -> $warned)", warned > calm + 50)
        val full = pixels(style, World.Tuning.MAX_PENDING, QueueGauge.FULL_FILL)
        val calmRed = pixels(style, warn - 1, QueueGauge.FULL_FILL)
        assertTrue("$style: red bubble once full ($calmRed -> $full)", full > calmRed + 50)
        // Without the hue: one more dark pip per request ...
        val pips = pixels(style, warn, QueueGauge.PIP_DARK)
        val morePips = pixels(style, warn + 1, QueueGauge.PIP_DARK)
        assertTrue("$style: a dark pip per request ($pips -> $morePips)", morePips > pips + 20)
        // ... and bigger request tokens once full, but not at the pre-warning, so a queue swinging around it does not
        // pop in size (the iso bubble shows at most three either way).
        val tokens = { n: Int -> if (style == "Iso") minOf(n, 3) else n } // IsoRenderer.MAX_QUEUE
        val mail = ServiceColors.of(Service.MAIL)
        val small = pixels(style, warn - 1, mail) / tokens(warn - 1).toFloat()
        val same = pixels(style, warn, mail) / tokens(warn).toFloat()
        val big = pixels(style, World.Tuning.MAX_PENDING, mail) / tokens(World.Tuning.MAX_PENDING).toFloat()
        assertEquals("$style: tokens keep their size at the pre-warning ($small -> $same per token)", small, same, small * 0.08f)
        assertTrue("$style: tokens grow once full ($small -> $big per token)", big > small * 1.15f)
    }

    @Test
    fun pipsFollowTheQueue() {
        val world = World(seed = 2L, spawnInitialNodes = false)
        val pc = world.addClient(Device.PC, world.unlocked.left, world.unlocked.top)
        for (k in 0..World.Tuning.MAX_PENDING + 2) {
            assertEquals(minOf(k, World.Tuning.MAX_PENDING), QueueGauge.filled(pc))
            pc.pending.addLast(Service.MAIL)
        }
    }

    @Test
    fun redOnceTheRingRunsEvenWithFewRequests() {
        val world = World(seed = 2L, spawnInitialNodes = false)
        val pc = world.addClient(Device.PC, world.unlocked.left, world.unlocked.top)
        pc.pending.addLast(Service.MAIL)
        assertFalse(QueueGauge.warns(pc))
        pc.overload = 0.3f
        assertTrue(QueueGauge.warns(pc))
        assertTrue(QueueGauge.full(pc))
        assertEquals(ProblemBadges.ALARM, QueueGauge.color(pc))
    }

    /** The iso view's frame leaves room above the top row for a warning bubble. */
    @Test
    fun isoFrameFitsAWarningBubbleOnTheTopRow() {
        val world = World(seed = 2L, spawnInitialNodes = false)
        world.incidentsEnabled = false
        for (row in world.water) row.fill(false)
        val u = world.unlocked
        val pc = world.addClient(Device.PC, u.left, u.top)
        repeat(World.Tuning.PREWARN_PENDING) { pc.pending.addLast(Service.MAIL) }
        val wide = Bitmap.createBitmap(1600, 300, Bitmap.Config.ARGB_8888)
        val r = IsoRenderer()
        r.layout(wide.width, wide.height, world)
        r.camera.fit(r.mapBounds(u))
        assertEquals("height-limited fit", 0f, r.camera.toScreenY(r.mapBounds(u).top), 1f)
        r.draw(Canvas(wide), world, null, 0f)
        val px = IntArray(wide.width * wide.height)
        wide.getPixels(px, 0, wide.width, 0, 0, wide.width, wide.height)
        val rows = px.indices.filter { px[it] == QueueGauge.WARN_FILL }.map { it / wide.width }
        assertTrue("the bubble is drawn", rows.size > 50)
        assertTrue("the bubble stays below the frame's top (top row ${rows.min()})", rows.min() > 1)
    }

    @Test
    fun isoBubbleWarnsBeforeTheRing() = check("Iso")

    @Test
    fun flatQueueWarnsBeforeTheRing() = check("Flat")
}
