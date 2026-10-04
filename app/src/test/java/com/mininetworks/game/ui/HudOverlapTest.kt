package com.mininetworks.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.Cell
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.Vec2
import com.mininetworks.game.game.World
import com.mininetworks.game.render.DragJuice
import com.mininetworks.game.render.Scenes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Nothing the player must read hides behind the HUD: the automatic framing keeps every node between the top rows and
 * the toolbar, the drag label keeps clear of the view buttons, and countdown pins never cover a server's name plate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de-xxhdpi")
@OptIn(DebugApi::class)
class HudOverlapTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        SettingsStore(app).tutorialSeen = true
    }

    private fun stuck(): World {
        val w = World(cols = 16, rows = 10, seed = 3L, spawnInitialNodes = false)
        for (row in w.water) row.fill(false)
        w.incidentsEnabled = false
        w.jumpToWeek(CableType.FIBER.unlockWeek)
        w.grant(400)
        val cdn = w.addServer(Service.STREAMING, 2, 2)
        val game = w.addServer(Service.GAMING, 14, 1)
        val mail = w.addServer(Service.MAIL, 2, 8)
        val tv = w.addClient(Device.TV, 5, 3)
        val console = w.addClient(Device.CONSOLE, 3, 6)
        val pc = w.addClient(Device.PC, 9, 7)
        check(w.connect(tv, cdn, CableType.ISDN))
        val router = w.addRouter(8, 4)
        check(w.connect(console, router, CableType.DSL))
        check(w.connect(router, game, CableType.DSL))
        check(w.connect(pc, mail, CableType.DSL))
        repeat(60 * 3) { w.update(1f / 60f) }
        return w
    }

    /**
     * Draws [world] at [width] by [height] in [style] and checks that every node lies between the top HUD and the
     * toolbar (its tray and, where it reaches over the node, its raised network row) and under no top HUD box.
     */
    private fun assertFramed(tag: String, world: World, width: Int, height: Int, style: String) {
        val view = GameView(app)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.drawSnapshot(Canvas(bmp), world, width, height, time = 1.3f, style = style)
        val r = view.activeRenderer
        val cam = r.camera
        val toolbar = view.toolbarTopEdge
        val raised = view.toolbarRaisedBox
        val top = minOf(toolbar, raised?.top ?: Float.MAX_VALUE)
        assertTrue("$tag: the framing keeps clear of the toolbar (${cam.insets.bottom} vs ${height - top})", height - cam.insets.bottom <= top + 0.5f)
        // The view controls stand over the map's right edge for a few seconds after it moved, then fade out: not a box
        // the framing keeps nodes out of.
        val hud = listOf("status", "date").mapNotNull { view.hudBounds("hud:$it") }
        // Every corner of every node's ground cell (its building stands on it, its port dots in front).
        for (n in world.nodes) for (cell in n.footprint) for (dx in 0..1) for (dy in 0..1) {
            val s = r.toScreen(Vec2((cell.x + dx).toFloat(), (cell.y + dy).toFloat()))
            val at = "$tag: ${n.kind} at $cell, corner $s"
            val edge = if (raised != null && s.x >= raised.left) minOf(toolbar, raised.top) else toolbar
            assertTrue("$at reaches under the toolbar ($edge)", s.y <= edge + 0.5f)
            assertTrue("$at under the top HUD", s.y >= cam.insets.top - 0.5f)
            assertTrue("$at off the side", s.x >= cam.insets.left - 0.5f && s.x <= width - cam.insets.right + 0.5f)
            for (b in hud) assertTrue("$at under the HUD box $b", !b.contains(s.x, s.y))
        }
    }

    @Test
    fun framingKeepsEveryNodeBetweenTheHudAndTheToolbar() {
        for ((name, make) in listOf("hud" to { Scenes.hud() }, "incidents" to { Scenes.incidents() }, "stuck" to ::stuck)) {
            for ((width, height) in listOf(2400 to 1080, 2160 to 1080, 1920 to 1080)) for (style in listOf("Iso", "Flat")) {
                assertFramed("$name ${width}x$height $style", make(), width, height, style)
            }
        }
    }

    /** The same at a large system font size (taller buttons and captions, a taller tray). */
    @Test
    fun framingKeepsEveryNodeClearAtLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        try {
            for ((name, make) in listOf("hud" to { Scenes.hud() }, "incidents" to { Scenes.incidents() }, "stuck" to ::stuck)) {
                for (style in listOf("Iso", "Flat")) assertFramed("$name large text $style", make(), 2400, 1080, style)
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    /** ... and on a landscape tablet. */
    @Test
    @Config(qualifiers = "de-xhdpi")
    fun framingKeepsEveryNodeClearOnATablet() {
        for ((name, make) in listOf("hud" to { Scenes.hud() }, "incidents" to { Scenes.incidents() }, "stuck" to ::stuck)) {
            for (style in listOf("Iso", "Flat")) assertFramed("$name tablet $style", make(), 2560, 1600, style)
        }
    }

    @Test
    fun dragLabelKeepsClearOfTheViewButtons() {
        val world = stuck()
        val view = GameView(app)
        val bmp = Bitmap.createBitmap(2400, 1080, Bitmap.Config.ARGB_8888)
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        val controls = listOf("rotate:left", "compass", "rotate:right", "tilt:low", "tilt:high").mapNotNull { view.hudBounds("hud:$it") }
        check(controls.size == 5) { "the view buttons show" }
        val r = view.activeRenderer
        val area = world.unlocked
        val free = (area.left until area.right).flatMap { x -> (area.top until area.bottom).map { y -> Cell(x, y) } }.filter { world.nodeAt(it) == null }
        // The map moved so that free ground lies just left of the view buttons, wherever the layout put them.
        val span = RectF(controls.minOf { it.left }, controls.minOf { it.top }, controls.maxOf { it.right }, controls.maxOf { it.bottom })
        val want = Vec2(span.left - 80f, span.centerY())
        val near = free.minBy { r.toScreen(it.center).let { p -> (p.x - want.x) * (p.x - want.x) + (p.y - want.y) * (p.y - want.y) } }
        r.toScreen(near.center).let { r.camera.panBy(want.x - it.x, want.y - it.y) }
        view.drawCurrent(Canvas(bmp))
        val fiber = view.hudTarget("cable:FIBER")!!
        view.injectTouch(MotionEvent.ACTION_DOWN, fiber.centerX(), fiber.centerY(), time = 0L)
        view.injectTouch(MotionEvent.ACTION_UP, fiber.centerX(), fiber.centerY(), time = 50L)
        // From the device nearest to that ground that is still on the map (not under the HUD).
        val cam = r.camera
        val from = world.nodes.filter { it.device != null }.map { r.toScreen(it.center) }
            .filter { it.x in cam.insets.left + 60f..span.left - 60f && it.y in cam.insets.top + 60f..bmp.height - cam.insets.bottom - 60f }
        val a = from.minBy { (it.x - want.x) * (it.x - want.x) + (it.y - want.y) * (it.y - want.y) }
        view.injectTouch(MotionEvent.ACTION_DOWN, a.x, a.y, time = 2000L)
        view.injectTouch(MotionEvent.ACTION_MOVE, a.x + 60f, a.y, time = 2100L)
        // Then to every free cell beside and under the view buttons, where the label would go up under them.
        val targets = free.filter { c ->
            r.toScreen(c.center).let { it.x in span.left - 160f..span.right && it.y in span.top..span.bottom + 160f }
        }
        check(targets.isNotEmpty()) { "cells beside the view buttons" }
        var time = 2200L
        for (c in targets) {
            val p = r.toScreen(c.center)
            view.injectTouch(MotionEvent.ACTION_MOVE, p.x, p.y, time = time)
            time += 100L
            view.drawCurrent(Canvas(bmp))
            val bubble = RectF(DragJuice.lastBubble)
            for (o in controls) assertTrue("drag label $bubble under the view button $o (finger at $p)", !RectF.intersects(bubble, o))
        }
        view.injectTouch(MotionEvent.ACTION_CANCEL, a.x, a.y)
    }

    @Test
    fun countdownPinsNeverCoverAServerPlate() {
        for (style in listOf("Iso", "Flat")) for ((width, height) in listOf(2400 to 1080, 1920 to 1080)) {
            val world = Scenes.incidents()
            val view = GameView(app)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.drawSnapshot(Canvas(bmp), world, width, height, time = 1.3f, style = style)
            val pins = view.incidentPinBoxes()
            check(pins.size == world.incidents.size) { "a pin per incident" }
            val plates = view.activeRenderer.serverLabels.plates()
            // Each pin's pill (its thin stem may cross a plate that took the pin's spot).
            val pills = view.incidentPinOverlays().filterIndexed { i, _ -> i % 2 == 0 }
            for (p in pills) for (q in plates) assertTrue("$style ${width}x$height: pin $p covers plate $q", !RectF.intersects(p, q))
            // No server loses its name to a pin either: every server on screen still has its plate.
            val cam = view.activeRenderer.camera
            val servers = world.nodes.filter { it.kind == NodeKind.SERVER }.map { view.activeRenderer.toScreen(it.center) }
                .count { it.x in 0f..width.toFloat() && it.y in cam.insets.top..height - cam.insets.bottom }
            assertEquals("$style ${width}x$height: a plate per server on screen", servers, plates.size)
            for (i in pins.indices) for (j in i + 1 until pins.size) assertTrue("$style: pins $i and $j overlap", !RectF.intersects(pins[i], pins[j]))
        }
    }

    /**
     * At the automatic framing no server goes without a sign: each one on screen gets its name plate or, where no spot
     * has room for the name, the compact token plate; a nudge of the map keeps them (only zooming out fades them).
     */
    @Test
    fun everyServerKeepsAPlateAtTheAutomaticFraming() {
        for ((name, make) in listOf("hud" to { Scenes.hud() }, "incidents" to { Scenes.incidents() }, "stuck" to ::stuck)) {
            for (style in listOf("Iso", "Flat")) for ((width, height) in listOf(2400 to 1080, 1920 to 1080)) {
                val world = make()
                val view = GameView(app)
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                view.drawSnapshot(Canvas(bmp), world, width, height, time = 1.3f, style = style)
                val r = view.activeRenderer
                fun onScreen() = world.nodes.filter { it.kind == NodeKind.SERVER }.map { r.toScreen(it.center) }
                    .count { it.x in 0f..width.toFloat() && it.y in r.camera.insets.top..height - r.camera.insets.bottom }
                val tag = "$name $style ${width}x$height"
                assertEquals("$tag: a plate per server on screen", onScreen(), r.serverLabels.plates().size)
                // A small pan is no zoom: the plates stay.
                r.camera.panBy(3f, 2f)
                view.drawCurrent(Canvas(bmp))
                assertFalse("$tag: a pan does not end the framing's zoom", r.camera.followsArea)
                assertEquals("$tag: a plate per server after a pan", onScreen(), r.serverLabels.plates().size)
            }
        }
    }
}
