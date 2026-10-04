package com.mininetworks.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.Cell
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.GameMode
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.RadioType
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.Wifi
import com.mininetworks.game.game.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * No silent failures: every refused placement, cable, re-route, upgrade or pick-up says why with a hint and the error
 * buzz, and for lack of budget the HUD's budget line flashes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(DebugApi::class)
class GameViewRefusalTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var view: GameView
    private lateinit var world: World
    private val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)

    @Before
    fun setUp() {
        SettingsStore(app).tutorialSeen = true
        world = newWorld()
        view = GameView(app)
        draw()
    }

    private fun newWorld() = World(seed = 2L, spawnInitialNodes = false).also { w ->
        w.incidentsEnabled = false
        for (row in w.water) row.fill(false)
        w.grant(100)
    }

    private fun draw() = view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 0f, style = "Iso")

    private var clock = 0L

    private fun tapAt(x: Float, y: Float, hold: Long = 50L) {
        clock += 400L
        view.injectTouch(MotionEvent.ACTION_DOWN, x, y, time = clock)
        view.injectTouch(MotionEvent.ACTION_UP, x, y, time = clock + hold)
    }

    private fun tapCell(c: Cell) = view.activeRenderer.toScreen(c.center).let { tapAt(it.x, it.y) }

    private fun tapHud(id: String) {
        val r = view.hudTarget(id) ?: throw AssertionError("no HUD button $id")
        tapAt(r.centerX(), r.centerY())
    }

    /** One finger from [a] to [b], as a player lays a cable. */
    private fun drag(a: Node, b: Node) {
        val p = view.activeRenderer.toScreen(a.center)
        val q = view.activeRenderer.toScreen(b.center)
        clock += 400L
        view.injectTouch(MotionEvent.ACTION_DOWN, p.x, p.y, time = clock)
        view.injectTouch(MotionEvent.ACTION_MOVE, (p.x + q.x) / 2f, (p.y + q.y) / 2f, time = clock + 50L)
        view.injectTouch(MotionEvent.ACTION_UP, q.x, q.y, time = clock + 100L)
    }

    private fun cell(dx: Int, dy: Int) = Cell(world.unlocked.left + dx, world.unlocked.top + dy)

    /** Runs [action] and checks it was refused with the error buzz. */
    private fun assertRefused(action: () -> Unit) {
        val errors = view.errorPulses
        action()
        assertEquals("one error buzz", errors + 1, view.errorPulses)
    }

    @Test
    fun cableWithoutBudgetNamesThePriceAndFlashesTheBudget() {
        val pc = world.addClient(Device.PC, cell(2, 2).x, cell(2, 2).y)
        val mail = world.addServer(Service.MAIL, cell(8, 2).x, cell(8, 2).y)
        world.grant(-world.budget)
        draw()
        assertFalse(view.budgetFlashing)
        assertRefused { drag(pc, mail) }
        assertNull(world.cableBetween(pc, mail))
        val cost = world.cableCost(pc, mail, CableType.ISDN)
        assertEquals("Zu wenig Budget: ISDN kostet hier $cost", view.shownHint)
        assertTrue("the budget line flashes", view.budgetFlashing)
        val flashed = redInBudgetCorner(time = 0f)
        val calm = redInBudgetCorner(time = 10f)
        assertTrue("the budget line and its coin turn red: $flashed vs $calm", flashed > calm + 20)
    }

    @Test
    fun creativeBudgetLineNeverFlashes() {
        val calm = redInBudgetCorner(time = 10f)
        world = World(seed = 2L, spawnInitialNodes = false, mode = GameMode.CREATIVE)
        draw()
        assertFalse(view.budgetFlashing)
        val creative = redInBudgetCorner(time = 10f)
        assertTrue("the unlimited line is not drawn red: $creative vs $calm", creative <= calm + 5)
    }

    @Test
    fun budgetTurnsAmberWhenThePickedCableIsOutOfReach() {
        world.jumpToWeek(CableType.FIBER.unlockWeek)
        world.grant(-world.budget + 3 * GameView.SHORT_CABLE_CELLS)
        draw()
        assertFalse("ISDN still pays for a short cable", view.budgetLow)
        val calm = amberInBudgetCorner()
        tapHud("cable:FIBER")
        draw()
        assertEquals(CableType.FIBER, view.pickedCable)
        assertFalse("fibre for exactly a short cable is still in reach", view.budgetLow)
        world.grant(-1)
        draw()
        assertTrue("one coin short of a short fibre cable", view.budgetLow)
        val low = amberInBudgetCorner()
        assertTrue("the budget line turns amber: $low vs $calm", low > calm + 20)
        assertFalse("a warning tint, not the refusal flash", view.budgetFlashing)

        world = World(seed = 2L, spawnInitialNodes = false, mode = GameMode.CREATIVE)
        draw()
        assertFalse("no limits, no warning", view.budgetLow)
    }

    @Test
    fun refusalFlashWinsOverTheWarningTint() {
        val pc = world.addClient(Device.PC, cell(2, 2).x, cell(2, 2).y)
        val mail = world.addServer(Service.MAIL, cell(8, 2).x, cell(8, 2).y)
        world.grant(-world.budget)
        draw()
        assertTrue(view.budgetLow)
        val amber = amberInBudgetCorner(time = 0f)
        val calmRed = redInBudgetCorner(time = 0f)
        assertRefused { drag(pc, mail) }
        assertTrue(view.budgetFlashing)
        val flashRed = redInBudgetCorner(time = 0f)
        val flashAmber = amberInBudgetCorner(time = 0f)
        assertTrue("red while it flashes: $flashRed vs $calmRed", flashRed > calmRed + 20)
        assertTrue("the amber gives way to the flash: $flashAmber vs $amber", flashAmber < amber / 2)
    }

    /** Dark amber pixels ([GameView.BUDGET_LOW_TINT]) on the HUD's counters in a frame at [time]. */
    private fun amberInBudgetCorner(time: Float = 10f): Int {
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = time, style = "Iso")
        val r0 = view.hudBounds("hud:status")!!
        var n = 0
        for (y in r0.top.toInt() until r0.bottom.toInt()) for (x in r0.left.toInt() until r0.right.toInt()) {
            val c = bmp.getPixel(x, y)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (r in 140..210 && g in 60..120 && b < 50) n++
        }
        return n
    }

    /** Strongly red pixels on the HUD's counters (with the budget line and its coin) in a frame at [time]. */
    private fun redInBudgetCorner(time: Float): Int {
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = time, style = "Iso")
        val r0 = view.hudBounds("hud:status")!!
        var n = 0
        for (y in r0.top.toInt() until r0.bottom.toInt()) for (x in (r0.left - 40f).toInt() until r0.right.toInt()) {
            val c = bmp.getPixel(x, y)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (r > 170 && g < 90 && b < 110) n++
        }
        return n
    }

    @Test
    fun cableUpgradeWithoutBudgetFlashesAndNotInventedSaysSo() {
        world.jumpToWeek(CableType.COAX.unlockWeek)
        val pc = world.addClient(Device.PC, cell(2, 2).x, cell(2, 2).y)
        val mail = world.addServer(Service.MAIL, cell(8, 2).x, cell(8, 2).y)
        assertTrue(world.connect(pc, mail, CableType.ISDN))
        draw()
        tapHud("cable:COAX")
        assertEquals(CableType.COAX, view.pickedCable)
        world.grant(-world.budget)
        val cable = world.cableBetween(pc, mail)!!
        val mid = view.activeRenderer.toScreen(cable.layout.pointAt(0.5f))
        assertRefused { tapAt(mid.x, mid.y) }
        assertEquals(CableType.ISDN, cable.type)
        assertTrue(view.shownHint!!, view.shownHint!!.startsWith("Zu wenig Budget, Aufrüsten kostet"))
        assertTrue(view.budgetFlashing)

        // The same chip in a new game from the start: coax is not invented there yet.
        world = newWorld()
        val pc2 = world.addClient(Device.PC, cell(2, 2).x, cell(2, 2).y)
        val mail2 = world.addServer(Service.MAIL, cell(8, 2).x, cell(8, 2).y)
        assertTrue(world.connect(pc2, mail2, CableType.ISDN))
        draw()
        val mid2 = view.activeRenderer.toScreen(world.cables.single().layout.pointAt(0.5f))
        assertRefused { tapAt(mid2.x, mid2.y) }
        assertEquals(CableType.ISDN, world.cables.single().type)
        assertTrue(view.shownHint!!, view.shownHint!!.endsWith("noch nicht erfunden"))
    }

    @Test
    fun cableBetweenConnectedNodesSaysSo() {
        val pc = world.addClient(Device.PC, cell(2, 2).x, cell(2, 2).y)
        val mail = world.addServer(Service.MAIL, cell(8, 2).x, cell(8, 2).y)
        assertTrue(world.connect(pc, mail, CableType.ISDN))
        draw()
        assertRefused { drag(pc, mail) }
        assertEquals(1, world.cables.size)
        assertTrue(view.shownHint!!, view.shownHint!!.endsWith("sind schon verbunden"))
        assertFalse("not a budget problem", view.budgetFlashing)
    }

    @Test
    fun cableNotInventedYetSaysWhenItComes() {
        world.jumpToWeek(CableType.COAX.unlockWeek)
        draw()
        tapHud("cable:COAX")
        assertEquals(CableType.COAX, view.pickedCable)
        // A new game from the start keeps the picked chip: coax is not invented there yet.
        world = newWorld()
        val pc = world.addClient(Device.PC, cell(2, 2).x, cell(2, 2).y)
        val mail = world.addServer(Service.MAIL, cell(8, 2).x, cell(8, 2).y)
        draw()
        assertRefused { drag(pc, mail) }
        assertNull(world.cableBetween(pc, mail))
        val year = world.yearOfWeek(CableType.COAX.unlockWeek)
        assertTrue(view.shownHint!!, view.shownHint!!.endsWith("gibt es erst ab $year"))
    }

    @Test
    fun placingOnATakenCellBuzzesAndSaysWhy() {
        val pc = world.addClient(Device.PC, cell(4, 3).x, cell(4, 3).y)
        draw()
        tapHud("router")
        assertTrue(view.placingArmed)
        assertRefused { tapCell(pc.cell) }
        assertEquals("Das Feld ist schon belegt", view.shownHint)
    }

    @Test
    fun routerWithCablesSaysWhyItCannotGoBack() {
        val router = world.addRouter(cell(4, 3).x, cell(4, 3).y)
        val pc = world.addClient(Device.PC, cell(2, 3).x, cell(2, 3).y)
        assertTrue(world.connect(pc, router, CableType.ISDN))
        draw()
        val errors = view.errorPulses
        tapCell(router.cell)
        assertEquals("the first tap only selects (it may be a look)", router, view.selected)
        assertEquals(errors, view.errorPulses)
        assertRefused { tapCell(router.cell) }
        assertTrue(view.shownHint!!, view.shownHint!!.startsWith("Router hat noch Kabel"))
        assertTrue(router in world.nodes)
    }

    @Test
    fun accessPointWithout5GhzBudgetFlashesTheBudget() {
        world.jumpToWeek(RadioType.WLAN.unlockWeek)
        val ap = world.addRadio(RadioType.WLAN, cell(4, 3).x, cell(4, 3).y)
        world.grant(-world.budget)
        draw()
        val p = view.activeRenderer.toScreen(ap.center)
        assertRefused { tapAt(p.x, p.y, hold = GameView.LONG_PRESS_MS + 50L) }
        assertFalse(ap.fiveGhz)
        assertTrue(view.shownHint!!, view.shownHint!!.contains(Wifi.UPGRADE_5_GHZ_COST.toString()))
        assertTrue(view.budgetFlashing)
    }

    @Test
    fun holdingA5GhzAccessPointSaysSoWithoutTheBuzz() {
        world.jumpToWeek(RadioType.WLAN.unlockWeek)
        val ap = world.addRadio(RadioType.WLAN, cell(4, 3).x, cell(4, 3).y)
        assertTrue(world.upgradeTo5Ghz(ap))
        draw()
        val errors = view.errorPulses
        val p = view.activeRenderer.toScreen(ap.center)
        tapAt(p.x, p.y, hold = GameView.LONG_PRESS_MS + 50L)
        assertTrue(ap.fiveGhz)
        assertEquals(app.getString(com.mininetworks.game.R.string.wifi_error_already_5ghz), view.shownHint)
        assertEquals("a fact, not a mistake: no error buzz", errors, view.errorPulses)
    }
}
