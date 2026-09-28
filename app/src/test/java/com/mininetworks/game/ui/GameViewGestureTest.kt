package com.mininetworks.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.Cell
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Incidents
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.RadioType
import com.mininetworks.game.game.Wifi
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World
import com.mininetworks.game.render.Camera
import com.mininetworks.game.render.TwoFingerGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Touch input on the map: one finger lays cables, two fingers and empty-ground drags move the camera. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(DebugApi::class)
class GameViewGestureTest {

    private lateinit var view: GameView
    private lateinit var world: World

    @Before
    fun setUp() {
        world = World(seed = 2L, spawnInitialNodes = false)
        for (row in world.water) row.fill(false)
        world.grant(100)
        view = GameView(RuntimeEnvironment.getApplication())
        val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 0f, style = "Iso")
    }

    private val camera get() = view.activeRenderer.camera

    @Test
    fun oneFingerDragFromNodeLaysCable() {
        val pc = world.addClient(Device.PC, 10, 7)
        val mail = world.addServer(Service.MAIL, 14, 9)
        val a = view.activeRenderer.toScreen(pc.center)
        val b = view.activeRenderer.toScreen(mail.center)
        val scale = camera.scale
        view.injectTouch(MotionEvent.ACTION_DOWN, a.x + 10f, a.y)
        view.injectTouch(MotionEvent.ACTION_MOVE, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
        view.injectTouch(MotionEvent.ACTION_UP, b.x - 10f, b.y)
        assertNotNull(world.cableBetween(pc, mail))
        assertEquals("laying a cable does not move the camera", scale, camera.scale, 0f)
        assertTrue(camera.followsArea)
    }

    @Test
    fun secondFingerCancelsCableAndPinchZooms() {
        val pc = world.addClient(Device.PC, 10, 7)
        val mail = world.addServer(Service.MAIL, 14, 9)
        val a = view.activeRenderer.toScreen(pc.center)
        val b = view.activeRenderer.toScreen(mail.center)
        val scale = camera.scale
        view.injectTouch(MotionEvent.ACTION_DOWN, a.x, a.y)
        view.injectTouch(MotionEvent.ACTION_POINTER_DOWN, a.x, a.y, floatArrayOf(a.x, a.y, 900f, 450f))
        val spread = floatArrayOf(a.x - 100f, a.y, 1000f, 450f)
        view.injectTouch(MotionEvent.ACTION_MOVE, spread[0], spread[1], spread)
        view.injectTouch(MotionEvent.ACTION_POINTER_UP, spread[0], spread[1], floatArrayOf(1000f, 450f))
        view.injectTouch(MotionEvent.ACTION_MOVE, b.x, b.y, floatArrayOf(b.x, b.y))
        view.injectTouch(MotionEvent.ACTION_UP, b.x, b.y, floatArrayOf())
        assertTrue("fingers spread: zoomed in", camera.scale > scale * 1.1f)
        assertFalse(camera.followsArea)
        assertTrue("no cable from a pinch", world.cables.isEmpty())
    }

    @Test
    fun dragOnEmptyGroundPansAndDoubleTapFits() {
        val before = view.activeRenderer.toWorld(800f, 450f)
        view.injectTouch(MotionEvent.ACTION_DOWN, 800f, 450f, time = 0L)
        view.injectTouch(MotionEvent.ACTION_MOVE, 900f, 500f, time = 50L)
        view.injectTouch(MotionEvent.ACTION_UP, 900f, 500f, time = 100L)
        val moved = view.activeRenderer.toScreen(before)
        assertEquals(900f, moved.x, 0.5f)
        assertEquals(500f, moved.y, 0.5f)
        assertFalse(camera.followsArea)

        view.injectTouch(MotionEvent.ACTION_DOWN, 700f, 300f, time = 1000L)
        view.injectTouch(MotionEvent.ACTION_UP, 700f, 300f, time = 1060L)
        view.injectTouch(MotionEvent.ACTION_DOWN, 705f, 302f, time = 1200L)
        view.injectTouch(MotionEvent.ACTION_UP, 705f, 302f, time = 1260L)
        assertTrue("double tap fits the unlocked area again", camera.followsArea)
        repeat(240) { camera.step(1f / 60f) }
        assertEquals(camera.fitScale(view.activeRenderer.mapBounds(world.unlocked)), camera.scale, 1e-3f)
    }

    @Test
    fun wlanButtonPlacesAccessPointTapCyclesChannelHoldSwitchesTo5Ghz() {
        world.jumpToWeek(6)
        world.grant(0, extraAccessPoints = 1)
        val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 0f, style = "Iso")
        assertNull("cell towers are not invented yet", view.hudTarget("radio:CELL"))
        val button = view.hudTarget("radio:WLAN")!!
        view.injectTouch(MotionEvent.ACTION_DOWN, button.centerX(), button.centerY())
        view.injectTouch(MotionEvent.ACTION_UP, button.centerX(), button.centerY())
        val cell = view.activeRenderer.toScreen(Cell(world.unlocked.left + 4, world.unlocked.top + 4).center)
        view.injectTouch(MotionEvent.ACTION_DOWN, cell.x, cell.y)
        view.injectTouch(MotionEvent.ACTION_UP, cell.x, cell.y)
        val ap = world.nodes.single { it.kind == NodeKind.ACCESS_POINT }
        assertEquals(0, world.accessPointsAvailable)
        assertEquals(1, ap.channel)

        view.injectTouch(MotionEvent.ACTION_DOWN, cell.x, cell.y, time = 1000L)
        view.injectTouch(MotionEvent.ACTION_UP, cell.x, cell.y, time = 1100L)
        assertEquals("a tap switches the channel", 6, ap.channel)

        val budget = world.budget
        view.injectTouch(MotionEvent.ACTION_DOWN, cell.x, cell.y, time = 2000L)
        view.injectTouch(MotionEvent.ACTION_UP, cell.x, cell.y, time = 2700L)
        assertTrue("holding switches to 5 GHz", ap.fiveGhz)
        assertEquals(Wifi.CHANNELS_5_GHZ.first(), ap.channel)
        assertEquals(budget - Wifi.UPGRADE_5_GHZ_COST, world.budget)
    }

    @Test
    fun holdingAnAccessPointSwitchesTo5GhzWhileTheFingerIsStillDown() {
        world.jumpToWeek(6)
        val ap = world.addRadio(RadioType.WLAN, world.unlocked.left + 4, world.unlocked.top + 4)
        val p = view.activeRenderer.toScreen(ap.center)
        val pulses = view.hapticPulses
        view.injectTouch(MotionEvent.ACTION_DOWN, p.x, p.y, time = 0L)
        repeat(20) { view.advance(1f / 60f) }
        assertFalse(ap.fiveGhz)
        repeat(12) { view.advance(1f / 60f) }
        assertTrue("fires after half a second without lifting the finger", ap.fiveGhz)
        assertTrue("with a haptic pulse", view.hapticPulses > pulses)
        view.injectTouch(MotionEvent.ACTION_UP, p.x, p.y, time = 600L)
        assertEquals("lifting afterwards does not also switch the channel", Wifi.CHANNELS_5_GHZ.first(), ap.channel)
    }

    @Test
    fun movingOffTheAccessPointCancelsTheHold() {
        world.jumpToWeek(6)
        val ap = world.addRadio(RadioType.WLAN, world.unlocked.left + 4, world.unlocked.top + 4)
        val p = view.activeRenderer.toScreen(ap.center)
        view.injectTouch(MotionEvent.ACTION_DOWN, p.x, p.y, time = 0L)
        view.injectTouch(MotionEvent.ACTION_MOVE, p.x + 200f, p.y, time = 100L)
        repeat(60) { view.advance(1f / 60f) }
        assertFalse(ap.fiveGhz)
        view.injectTouch(MotionEvent.ACTION_UP, p.x + 200f, p.y, time = 1100L)
        assertFalse(ap.fiveGhz)
    }

    @Test
    fun tappingACutCableRepairsItInsteadOfRemovingIt() {
        val pc = world.addClient(Device.PC, 10, 7)
        val mail = world.addServer(Service.MAIL, 14, 7)
        assertTrue(world.connect(pc, mail, CableType.ISDN))
        val cable = world.cableBetween(pc, mail)!!
        world.announceExcavator(cable)
        repeat(60 * 6) { world.update(1f / 60f) }
        assertTrue(world.isCut(cable))
        val budget = world.budget
        val p = view.activeRenderer.toScreen(cable.layout.pointAt(0.5f))
        view.injectTouch(MotionEvent.ACTION_DOWN, p.x, p.y)
        view.injectTouch(MotionEvent.ACTION_UP, p.x, p.y)
        assertFalse(world.isCut(cable))
        assertTrue("the cable stays", cable in world.cables)
        assertEquals(budget - Incidents.REPAIR_COST, world.budget)
        view.injectTouch(MotionEvent.ACTION_DOWN, p.x, p.y, time = 1000L)
        view.injectTouch(MotionEvent.ACTION_UP, p.x, p.y, time = 1000L)
        assertTrue("an intact cable is only selected by the first tap", cable in world.cables)
        view.injectTouch(MotionEvent.ACTION_DOWN, p.x, p.y, time = 1500L)
        view.injectTouch(MotionEvent.ACTION_UP, p.x, p.y, time = 1500L)
        assertFalse("the second tap removes it", cable in world.cables)
    }

    /** Two fingers turning by [degrees] around (800, 450), spreading nothing, then lifted. */
    private fun twist(degrees: Float, steps: Int = 6) {
        val r = 200f
        fun at(a: Double) = floatArrayOf(
            800f - r * kotlin.math.cos(a).toFloat(), 450f - r * kotlin.math.sin(a).toFloat(),
            800f + r * kotlin.math.cos(a).toFloat(), 450f + r * kotlin.math.sin(a).toFloat(),
        )
        val start = at(0.0)
        view.injectTouch(MotionEvent.ACTION_DOWN, start[0], start[1], floatArrayOf(start[0], start[1]))
        view.injectTouch(MotionEvent.ACTION_POINTER_DOWN, start[2], start[3], start)
        var p = start
        for (k in 1..steps) {
            p = at(Math.toRadians(degrees * k / steps.toDouble()))
            view.injectTouch(MotionEvent.ACTION_MOVE, p[0], p[1], p)
        }
        view.injectTouch(MotionEvent.ACTION_POINTER_UP, p[2], p[3], floatArrayOf(p[0], p[1]))
        view.injectTouch(MotionEvent.ACTION_UP, p[0], p[1], floatArrayOf())
    }

    /** docs/TOP100.md B5: turning with two fingers, snapping on release, the compass, and hits after turning. */
    @Test
    fun twoFingersTurnTheMapWhichSnapsToEighthTurnsAndTheCompassTurnsItBack() {
        val pc = world.addClient(Device.PC, 10, 7)
        val mail = world.addServer(Service.MAIL, 14, 9)
        val under = view.activeRenderer.toWorld(800f, 450f)
        // 90° of finger twist: the first 12° are the dead zone of a pinch, the map follows the other 78°.
        twist(78f + TwoFingerGesture.ROTATE_THRESHOLD)
        assertTrue("turned while the fingers move", camera.angle in 68f..88f || camera.isRotating)
        repeat(90) { view.advance(1f / 60f) }
        assertEquals("snapped to the nearest multiple of 45°", 90f, camera.angle, 0f)
        val back = view.activeRenderer.toScreen(under)
        assertEquals("turned around the fingers' midpoint", 800f, back.x, 1f)
        assertEquals(450f, back.y, 1f)

        // Tapping and dragging hit the same nodes after the turn.
        val a = view.activeRenderer.toScreen(pc.center)
        val b = view.activeRenderer.toScreen(mail.center)
        view.injectTouch(MotionEvent.ACTION_DOWN, a.x, a.y)
        view.injectTouch(MotionEvent.ACTION_MOVE, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
        view.injectTouch(MotionEvent.ACTION_UP, b.x, b.y)
        assertNotNull("a cable dragged on the turned map", world.cableBetween(pc, mail))

        val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        view.drawCurrent(Canvas(bmp))
        val compass = view.hudTarget("compass")!!
        view.injectTouch(MotionEvent.ACTION_DOWN, compass.centerX(), compass.centerY())
        view.injectTouch(MotionEvent.ACTION_UP, compass.centerX(), compass.centerY())
        repeat(90) { view.advance(1f / 60f) }
        assertEquals("the compass turns back to north", 0f, camera.angle, 0f)
        view.drawCurrent(Canvas(bmp))
        assertNotNull("the compass stays in the view controls while facing north", view.hudTarget("compass"))
    }

    private fun tap(id: String) {
        val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        view.drawCurrent(Canvas(bmp))
        val r = view.hudTarget(id) ?: throw AssertionError("no HUD button $id")
        view.injectTouch(MotionEvent.ACTION_DOWN, r.centerX(), r.centerY())
        view.injectTouch(MotionEvent.ACTION_UP, r.centerX(), r.centerY())
    }

    /** The rotate buttons turn by 45° around the centre of the view, the tilt buttons pitch it; taps stay exact. */
    @Test
    fun rotateAndTiltButtonsStepTheView() {
        val pc = world.addClient(Device.PC, 10, 7)
        val mail = world.addServer(Service.MAIL, 14, 9)
        val cx = camera.centerX; val cy = camera.centerY
        val under = view.activeRenderer.toWorld(cx, cy)
        tap("rotate:right")
        repeat(90) { view.advance(1f / 60f) }
        assertEquals("a quarter of a right angle: straight onto a side", 45f, camera.angle, 0f)
        val back = view.activeRenderer.toScreen(under)
        assertEquals("turned around the centre of the view", cx, back.x, 1f)
        assertEquals(cy, back.y, 1f)
        tap("rotate:left")
        tap("rotate:left")
        repeat(90) { view.advance(1f / 60f) }
        assertEquals(315f, camera.angle, 0f)

        tap("tilt:high")
        repeat(90) { view.advance(1f / 60f) }
        assertEquals(Camera.DEFAULT_TILT + Camera.TILT_STEP, camera.tilt, 1e-3f)
        tap("tilt:low")
        tap("tilt:low")
        repeat(90) { view.advance(1f / 60f) }
        assertEquals("one step below the classic pitch is the low end", Camera.TILT_MIN, camera.tilt, 0f)
        val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        view.accessibilityLayer.forceActive = true
        view.drawCurrent(Canvas(bmp))
        assertNull("at the low end the flatter button is off", view.hudTarget("tilt:low"))
        assertFalse(view.accessibilityLayer.nodes.first { it.key == "hud:tilt:low" }.actionable)

        // Dragging a cable on the turned, tilted map hits the same nodes.
        val a = view.activeRenderer.toScreen(pc.center)
        val b = view.activeRenderer.toScreen(mail.center)
        view.injectTouch(MotionEvent.ACTION_DOWN, a.x, a.y)
        view.injectTouch(MotionEvent.ACTION_MOVE, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
        view.injectTouch(MotionEvent.ACTION_UP, b.x, b.y)
        assertNotNull("a cable dragged on the turned and tilted map", world.cableBetween(pc, mail))
    }

    /** Two fingers side by side dragged down tilt the iso view steeper; the flat overview has no pitch and pans. */
    @Test
    fun twoFingersDraggedDownTogetherTiltTheView() {
        fun drag(dy: Float) {
            val start = floatArrayOf(700f, 450f, 900f, 450f)
            view.injectTouch(MotionEvent.ACTION_DOWN, start[0], start[1], floatArrayOf(start[0], start[1]))
            view.injectTouch(MotionEvent.ACTION_POINTER_DOWN, start[2], start[3], start)
            var p = start
            for (k in 1..8) {
                p = floatArrayOf(700f, 450f + dy * k / 8f, 900f, 450f + dy * k / 8f)
                view.injectTouch(MotionEvent.ACTION_MOVE, p[0], p[1], p)
            }
            view.injectTouch(MotionEvent.ACTION_POINTER_UP, p[2], p[3], floatArrayOf(p[0], p[1]))
            view.injectTouch(MotionEvent.ACTION_UP, p[0], p[1], floatArrayOf())
        }
        val scale = camera.scale
        drag(160f)
        assertTrue("steeper after dragging down", camera.tilt > Camera.DEFAULT_TILT + 5f)
        assertEquals("tilting does not zoom", scale, camera.scale, scale * 1e-3f)
        val steep = camera.tilt
        drag(-120f)
        assertTrue("flatter after dragging up", camera.tilt < steep - 5f)
        assertEquals("a tilt stays where it was left", camera.tilt, camera.tilt.also { repeat(30) { view.advance(1f / 60f) } }, 0f)

        val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 0f, style = "Flat")
        val pitch = camera.tilt
        val under = view.activeRenderer.toWorld(800f, 450f)
        drag(100f)
        assertEquals("the overview has no pitch", pitch, camera.tilt, 0f)
        assertEquals("it pans instead", 550f, view.activeRenderer.toScreen(under).y, 1f)
    }

    @Test
    fun freeRotationKeepsTheAngle() {
        val app = RuntimeEnvironment.getApplication()
        com.mininetworks.game.data.SettingsStore(app).save(com.mininetworks.game.data.GameSettings(freeRotation = true))
        view = GameView(app)
        val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 0f, style = "Flat")
        twist(37f + TwoFingerGesture.ROTATE_THRESHOLD)
        repeat(90) { view.advance(1f / 60f) }
        assertEquals(37f, camera.angle, 0.5f)
        val p = view.activeRenderer.toWorld(300f, 200f)
        val s = view.activeRenderer.toScreen(p)
        assertEquals(300f, s.x, 0.05f)
        assertEquals(200f, s.y, 0.05f)
    }
}
