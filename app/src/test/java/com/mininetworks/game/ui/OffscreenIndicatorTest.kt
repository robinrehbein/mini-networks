package com.mininetworks.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.Cell
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Off-screen chips: a device in trouble outside the map's view gets a chip at the view's edge, in every style and
 * turn, and a tap on it glides the camera there; a device in view, or one without trouble, gets none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de")
@OptIn(DebugApi::class)
class OffscreenIndicatorTest {

    private val chipCap = GameView.MAX_OFFSCREEN_CHIPS

    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var view: GameView
    private lateinit var world: World
    private lateinit var pc: Node
    private lateinit var tv: Node
    private lateinit var calm: Node
    private val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)

    @Before
    fun setUp() {
        SettingsStore(app).tutorialSeen = true
        world = World(seed = 2L, spawnInitialNodes = false)
        world.incidentsEnabled = false
        for (row in world.water) row.fill(false)
        val u = world.unlocked
        pc = world.addClient(Device.PC, u.left + 1, u.top + 1)
        tv = world.addClient(Device.TV, u.right - 2, u.bottom - 2)
        calm = world.addClient(Device.PHONE, u.right - 2, u.top + 1)
        repeat(World.Tuning.MAX_PENDING - 2) { pc.pending.addLast(Service.MAIL) }
        tv.pending.addLast(Service.STREAMING)
        tv.overload = 0.4f
        view = GameView(app)
    }

    private fun draw(style: String) = view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 0f, style = style)

    private fun chip(n: Node): RectF? = view.hudTarget("offscreen:${n.id}")

    private fun inView(n: Node): Boolean {
        val r = view.activeRenderer
        val p = r.toScreen(n.footprintCenter)
        val i = r.camera.insets
        return p.x in i.left..(bmp.width - i.right) && p.y in i.top..(bmp.height - i.bottom)
    }

    /** Zooms in close on the PC (in its corner), which pushes the TV and the phone out of view. */
    private fun zoomOnPc() {
        val r = view.activeRenderer
        val p = r.toScreen(pc.footprintCenter)
        r.camera.zoomBy(6f, p.x, p.y)
        r.camera.panBy(r.camera.centerX - p.x, r.camera.centerY - p.y)
        view.drawCurrent(Canvas(bmp))
    }

    private fun check(style: String, turn: Float) {
        draw(style)
        val r = view.activeRenderer
        if (turn != 0f) {
            r.camera.rotateTo(turn)
            repeat(120) { r.stepCamera(1f / 30f, world) }
            view.drawCurrent(Canvas(bmp))
        }
        // A turned map may tip a corner out of the overview; unturned, both devices are in it.
        for (n in listOf(pc, tv)) {
            if (turn == 0f) assertTrue("$style: ${n.device} in the overview", inView(n))
            if (inView(n)) assertNull("$style/$turn: no chip while ${n.device} is in view", chip(n))
        }

        zoomOnPc()
        assertTrue("$style/$turn: PC in view", inView(pc))
        assertTrue("$style/$turn: TV out of view", !inView(tv))
        assertNull("$style/$turn: a device in view gets no chip", chip(pc))
        assertNull("$style/$turn: a device without trouble gets no chip", chip(calm))
        val c = chip(tv)
        assertNotNull("$style/$turn: the overloading TV gets a chip", c)
        c!!
        val i = r.camera.insets
        assertTrue("$style/$turn: chip inside the view, clear of the HUD: $c", c.left >= i.left && c.top >= i.top && c.right <= bmp.width - i.right && c.bottom <= bmp.height - i.bottom)
        // The chip sits on the TV's side of the view.
        val tvAt = r.toScreen(tv.footprintCenter)
        val dx = tvAt.x - r.camera.centerX; val dy = tvAt.y - r.camera.centerY
        assertTrue("$style/$turn: chip points the TV's way", (c.centerX() - r.camera.centerX) * dx + (c.centerY() - r.camera.centerY) * dy > 0f)

        view.injectTouch(MotionEvent.ACTION_DOWN, c.centerX(), c.centerY(), time = 1000L)
        view.injectTouch(MotionEvent.ACTION_UP, c.centerX(), c.centerY(), time = 1050L)
        assertTrue("$style/$turn: the tap starts a glide", r.camera.isAnimating)
        repeat(120) { r.stepCamera(1f / 30f, world) }
        assertTrue("$style/$turn: the tap brought the TV into view", inView(tv))
        view.drawCurrent(Canvas(bmp))
        assertNull("$style/$turn: no chip once the TV is in view", chip(tv))
    }

    @Test
    fun chipShowsOnlyOffScreenAndATapPansThereIso() = check("Iso", 0f)

    @Test
    fun chipWorksOnATurnedIsoMap() = check("Iso", 90f)

    @Test
    fun chipWorksInTheFlatOverview() = check("Flat", 0f)

    @Test
    fun chipWorksOnATurnedFlatMap() = check("Flat", 45f)

    @Test
    fun chipsStackWithoutOverlapAndAreCapped() {
        view.accessibilityLayer.forceActive = true
        val u = world.unlocked
        val more = (0 until 5).map { k -> world.addClient(Device.PHONE, u.right - 2 - 2 * k, u.bottom - 2) }
        for (n in more) { n.pending.addLast(Service.MAIL); n.overload = 0.2f }
        draw("Iso")
        zoomOnPc()
        val troubled = (more + tv).filter { !inView(it) }
        assertTrue("several troubled devices off screen", troubled.size > chipCap)
        val nodes = view.accessibilityLayer.nodes.filter { it.key.startsWith("hud:") }
        val chips = nodes.filter { it.key.startsWith("hud:offscreen:") }.map { it.bounds }
        assertTrue("some chips: $chips", chips.isNotEmpty())
        assertTrue("at most $chipCap chips: $chips", chips.size <= chipCap)
        for ((i, a) in chips.withIndex()) {
            for (b in chips.drop(i + 1)) assertTrue("chips $a and $b overlap", !RectF.intersects(a, b))
            for (h in nodes) if (!h.key.startsWith("hud:offscreen:") && h.key != "hud:map") assertTrue("chip $a covers ${h.key}", !RectF.intersects(a, h.bounds))
        }
    }

    @Test
    fun noChipsDuringTheRewardOffer() {
        draw("Iso")
        zoomOnPc()
        assertNotNull(chip(tv))
        world.advanceToNextWeek()
        assertNotNull(world.rewardOffer)
        tv.pending.addLast(Service.STREAMING)
        tv.overload = 0.4f
        view.drawCurrent(Canvas(bmp))
        assertTrue(!inView(tv))
        assertNull("no chip while the reward cards are open", chip(tv))
    }

    @Test
    fun noChipsDuringTheTutorial() {
        SettingsStore(app).tutorialSeen = false
        val t = GameView(app)
        val tw = t.currentTutorial!!.world
        t.drawSnapshot(Canvas(bmp), tw, bmp.width, bmp.height, time = 0f, screen = null)
        val u = tw.unlocked
        val far = tw.addClient(Device.TV, u.right - 2, u.bottom - 2)
        far.pending.addLast(Service.STREAMING)
        far.overload = 0.4f
        val r = t.activeRenderer
        r.camera.zoomBy(6f, r.camera.centerX, r.camera.centerY)
        t.drawCurrent(Canvas(bmp))
        assertNull("no chip in the tutorial", t.hudTarget("offscreen:${far.id}"))
    }

    @Test
    fun aDeviceNearingTheLimitWarnsToo() {
        draw("Iso")
        val r = view.activeRenderer
        // Zoom in on the TV now: the PC, a request or two short of overloading, leaves the view.
        val p = r.toScreen(tv.footprintCenter)
        r.camera.zoomBy(6f, p.x, p.y)
        r.camera.panBy(r.camera.centerX - p.x, r.camera.centerY - p.y)
        view.drawCurrent(Canvas(bmp))
        assertTrue(!inView(pc))
        assertNotNull("the PC warns before its ring starts", chip(pc))
        val box = RectF(chip(pc))
        pc.pending.removeFirst()
        view.drawCurrent(Canvas(bmp))
        assertEquals("one request under the warning holds the chip in place", box, chip(pc))
        pc.pending.addLast(Service.MAIL)
        view.drawCurrent(Canvas(bmp))
        assertEquals(box, chip(pc))
        repeat(2) { pc.pending.removeFirst() }
        view.drawCurrent(Canvas(bmp))
        assertNull("two short of the warning is no trouble any more", chip(pc))
        pc.pending.addLast(Service.MAIL)
        view.drawCurrent(Canvas(bmp))
        assertNull("nor is a request more without the chip before", chip(pc))
    }

    @Test
    fun aTapOnTheArrowTipPansToo() {
        draw("Iso")
        zoomOnPc()
        val r = view.activeRenderer
        val c = chip(tv)!!
        val tvAt = r.toScreen(tv.footprintCenter)
        val a = kotlin.math.atan2(tvAt.y - c.centerY(), tvAt.x - c.centerX())
        val reach = c.width() / 2f * 1.4f
        val x = c.centerX() + kotlin.math.cos(a) * reach; val y = c.centerY() + kotlin.math.sin(a) * reach
        assertTrue("the tip lies outside the disc", !c.contains(x, y))
        view.injectTouch(MotionEvent.ACTION_DOWN, x, y, time = 1000L)
        view.injectTouch(MotionEvent.ACTION_UP, x, y, time = 1050L)
        assertTrue("a tap on the arrow starts the glide", r.camera.isAnimating)
    }

    @Test
    fun talkBackReadsTheChipAsAButtonThatPans() {
        val a11y = view.accessibilityLayer
        a11y.forceActive = true
        draw("Iso")
        zoomOnPc()
        val key = "hud:offscreen:${tv.id}"
        val node = a11y.nodes.single { it.key == key }
        assertEquals(UiNode.Kind.BUTTON, node.kind)
        val label = node.text
        assertTrue(label, label.startsWith("Smart-TV außerhalb der Ansicht ") && label.contains("überlastet (1 wartend)"))
        assertTrue(label, listOf("links", "rechts", "oben", "unten").any { " $it " in label })
        val min = 48 * app.resources.displayMetrics.density - 0.5f
        assertTrue("a full touch target: ${node.bounds}", node.bounds.width() >= min && node.bounds.height() >= min)
        // A second TV out of view with a longer queue reads apart from the first.
        val u = world.unlocked
        val tv2 = world.addClient(Device.TV, u.left + 1, u.bottom - 2)
        repeat(2) { tv2.pending.addLast(Service.STREAMING) }
        tv2.overload = 0.3f
        view.drawCurrent(Canvas(bmp))
        val labels = a11y.nodes.filter { it.key.startsWith("hud:offscreen:") }.map { it.text }
        assertEquals("distinct labels: $labels", labels.size, labels.toSet().size)

        assertTrue(a11y.performAction(a11y.idOf(key), AccessibilityNodeInfo.ACTION_CLICK, null))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        view.advance(0f)
        val r = view.activeRenderer
        repeat(120) { r.stepCamera(1f / 30f, world) }
        assertTrue("the click brought the TV into view", inView(tv))
        view.drawCurrent(Canvas(bmp))
        assertTrue("its chip is gone", a11y.nodes.none { it.key == key })
    }
}
