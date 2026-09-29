package com.mininetworks.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.Bend
import com.mininetworks.game.game.Cable
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.Cell
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.Vec2
import com.mininetworks.game.game.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Re-routing a laid cable on the map (like re-drawing a line in Mini Metro): the handles of a selected cable, dragging
 * its middle to flip the bend, and a long press that grabs a cable's nearer end at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de")
@OptIn(DebugApi::class)
class GameViewRerouteTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var view: GameView
    private lateinit var world: World
    private val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
    private lateinit var pc: Node
    private lateinit var near: Node
    private lateinit var far: Node

    @Before
    fun setUp() {
        SettingsStore(app).tutorialSeen = true
        world = World(seed = 2L, spawnInitialNodes = false)
        world.incidentsEnabled = false
        for (row in world.water) row.fill(false)
        world.grant(100)
        pc = world.addClient(Device.PC, cell(2, 2).x, cell(2, 2).y)
        near = world.addRouter(cell(5, 2).x, cell(5, 2).y)
        far = world.addRouter(cell(2, 6).x, cell(2, 6).y)
        assertTrue(world.connect(pc, near, CableType.ISDN))
        view = GameView(app)
        draw()
    }

    private fun draw(style: String = "Iso") = view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 0f, style = style)

    private var clock = 0L

    private fun tapAt(p: Vec2) {
        clock += 400L
        view.injectTouch(MotionEvent.ACTION_DOWN, p.x, p.y, time = clock)
        view.injectTouch(MotionEvent.ACTION_UP, p.x, p.y, time = clock + 50L)
    }

    /** A one-finger drag from [a] to [b] in [steps] moves. */
    private fun drag(a: Vec2, b: Vec2, steps: Int = 8, down: Boolean = true) {
        clock += 400L
        if (down) view.injectTouch(MotionEvent.ACTION_DOWN, a.x, a.y, time = clock)
        for (i in 1..steps) {
            val f = i / steps.toFloat()
            view.injectTouch(MotionEvent.ACTION_MOVE, a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, time = clock + 20L * i)
        }
        view.injectTouch(MotionEvent.ACTION_UP, b.x, b.y, time = clock + 20L * steps + 10L)
    }

    private fun cell(dx: Int, dy: Int) = Cell(world.unlocked.left + dx, world.unlocked.top + dy)
    private fun screen(p: Vec2) = view.activeRenderer.toScreen(p)
    private fun middleOf(c: Cable) = screen(c.layout.pointAt(0.5f))

    @Test
    fun aSelectedCableShowsHandlesAndDraggingOneMovesThatEnd() {
        val cable = world.cables.single()
        assertTrue(view.handleTargets().isEmpty())
        tapAt(middleOf(cable))
        assertSame(cable, view.selected)
        assertTrue("the hint names the handles", view.shownHint!!.contains("Ende ziehen"))
        val handles = view.handleTargets()
        assertEquals(2, handles.size)
        val budget = world.budget
        drag(handles[1], screen(far.center))
        assertNull("the old way is gone", world.cableBetween(pc, near))
        val moved = world.cableBetween(pc, far)!!
        assertEquals(CableType.ISDN, moved.type)
        assertEquals("4 cells instead of 3", budget - 1, world.budget)
        assertEquals("Kabel neu verlegt · −1", view.shownHint)
        assertSame("the moved cable stays selected for the next change", moved, view.selected)
    }

    @Test
    fun releasingOnEmptyGroundOrABadTargetChangesNothing() {
        val cable = world.cables.single()
        tapAt(middleOf(cable))
        val budget = world.budget
        drag(view.handleTargets()[1], screen(cell(7, 7).center))
        assertSame("empty ground cancels", cable, world.cables.single())
        // The pc's other port goes to another router: moving the near end onto it would lay a second cable between them.
        val other = world.addRouter(cell(2, 4).x, cell(2, 4).y)
        assertTrue(world.connect(pc, other, CableType.ISDN))
        draw()
        assertSame("a cancelled drag keeps the cable selected", cable, view.selected)
        drag(view.handleTargets()[1], screen(other.center))
        assertSame("already connected: nothing changes", cable, world.cableBetween(pc, near))
        assertEquals(budget - 2, world.budget)
    }

    @Test
    fun draggingTheMiddleOfABentCableFlipsItsBend() {
        world.removeCable(world.cables.single())
        assertTrue(world.connect(pc, far, CableType.ISDN))
        val corner = world.addRouter(cell(6, 6).x, cell(6, 6).y)
        assertTrue(world.connect(near, corner, CableType.ISDN, Bend.VERTICAL_FIRST))
        draw()
        val cable = world.cableBetween(near, corner)!!
        assertEquals(cell(5, 6).center, cable.layout.waypoints[1])
        tapAt(screen(cell(5, 5).center))
        assertSame(cable, view.selected)
        drag(screen(cell(5, 5).center), screen(cell(6, 2).center))
        val flipped = world.cableBetween(near, corner)!!
        assertEquals("pulled to the other corner", cell(6, 2).center, flipped.layout.waypoints[1])
    }

    @Test
    fun aTapOnAHandleKeepsTheCableAndATapOnItsMiddleRemovesIt() {
        val cable = world.cables.single()
        tapAt(middleOf(cable))
        tapAt(view.handleTargets()[0])
        assertSame(cable, world.cables.single())
        assertSame(cable, view.selected)
        tapAt(middleOf(cable))
        assertTrue("the second tap on the cable removes it", world.cables.isEmpty())
    }

    @Test
    fun aLongPressGrabsTheNearerEndOfAnyCable() {
        val cable = world.cables.single()
        val p = screen(cable.layout.pointAt(0.6f))
        clock += 400L
        view.injectTouch(MotionEvent.ACTION_DOWN, p.x, p.y, time = clock)
        view.injectTouch(MotionEvent.ACTION_MOVE, p.x + 1f, p.y, time = clock + GameView.LONG_PRESS_MS + 10L)
        assertSame("the hold selects it", cable, view.selected)
        val f = screen(far.center)
        view.injectTouch(MotionEvent.ACTION_MOVE, (p.x + f.x) / 2f, (p.y + f.y) / 2f, time = clock + 700L)
        view.injectTouch(MotionEvent.ACTION_MOVE, f.x, f.y, time = clock + 750L)
        view.injectTouch(MotionEvent.ACTION_UP, f.x, f.y, time = clock + 800L)
        assertNotNull("the end nearer the finger moved", world.cableBetween(pc, far))
        assertNull(world.cableBetween(pc, near))
    }

    @Test
    fun aLongPressAlsoFiresWhileTheFingerRestsAndAPanStillPans() {
        val cable = world.cables.single()
        val p = screen(cable.layout.pointAt(0.6f))
        view.injectTouch(MotionEvent.ACTION_DOWN, p.x, p.y, time = 10_000L)
        repeat(40) { view.advance(1f / 60f) }
        assertSame("the frame loop fires the hold", cable, view.selected)
        view.injectTouch(MotionEvent.ACTION_UP, p.x, p.y, time = 10_700L)
        assertSame("released in place: nothing changes", cable, world.cables.single())

        // A drag that leaves the tap slop at once is a pan, not a hold.
        val camera = view.activeRenderer.camera
        val before = camera.focusX to camera.focusY
        view.injectTouch(MotionEvent.ACTION_DOWN, p.x, p.y, time = 20_000L)
        view.injectTouch(MotionEvent.ACTION_MOVE, p.x, p.y + 200f, time = 20_050L)
        view.injectTouch(MotionEvent.ACTION_MOVE, p.x, p.y + 260f, time = 20_700L)
        view.injectTouch(MotionEvent.ACTION_UP, p.x, p.y + 260f, time = 20_750L)
        assertTrue("the map moved", before != (camera.focusX to camera.focusY))
        assertSame(cable, world.cables.single())
    }

    @Test
    fun handlesWorkOnATurnedMapInBothStyles() {
        for (style in listOf("Iso", "Flat")) {
            draw(style)
            view.activeRenderer.rotateBy(90f, 800f, 450f, world)
            draw(style)
            tapAt(screen(cell(9, 9).center))
            val cable = world.cableBetween(pc, near)!!
            tapAt(middleOf(cable))
            assertSame(style, cable, view.selected)
            drag(view.handleTargets()[if (cable.a === near) 0 else 1], screen(far.center))
            val moved = world.cableBetween(pc, far)
            assertNotNull(style, moved)
            // And back again for the next style, from the handle at the far end.
            drag(view.handleTargets()[if (moved!!.a === far) 0 else 1], screen(near.center))
            assertNotNull(style, world.cableBetween(pc, near))
        }
    }
}
