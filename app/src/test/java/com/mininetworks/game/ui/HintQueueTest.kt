package com.mininetworks.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.Cell
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Errors and info hints apart: a refused action gets a reddish plate with a "!" mark and stays longer, and an info hint
 * that comes meanwhile waits for it instead of wiping it away (only the newest). The newest error and a preview that
 * answers the latest tap show at once. Info replaces info.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(DebugApi::class)
class HintQueueTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var view: GameView
    private lateinit var world: World
    private val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
    private lateinit var pc: Node
    private lateinit var mail: Node
    private lateinit var far: Node
    private lateinit var phone: Node

    @Before
    fun setUp() {
        SettingsStore(app).tutorialSeen = true
        world = World(seed = 2L, spawnInitialNodes = false).also { w ->
            w.incidentsEnabled = false
            for (row in w.water) row.fill(false)
        }
        pc = world.addClient(Device.PC, cell(2, 2).x, cell(2, 2).y)
        mail = world.addServer(Service.MAIL, cell(8, 2).x, cell(8, 2).y)
        far = world.addServer(Service.MAIL, cell(2, 7).x, cell(2, 7).y)
        phone = world.addClient(Device.PHONE, cell(5, 6).x, cell(5, 6).y)
        world.grant(-world.budget)
        view = GameView(app)
        view.accessibilityLayer.forceActive = true
        draw()
        // Paused in place: the hint timers run on, the network stands still (no requests, no game over).
        tapHud("pause")
        assertTrue(view.pausedInPlace)
    }

    private fun draw() = view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 0f, style = "Iso")

    private fun cell(dx: Int, dy: Int) = Cell(world.unlocked.left + dx, world.unlocked.top + dy)

    private var clock = 0L

    private fun tapAt(x: Float, y: Float) {
        clock += 400L
        view.injectTouch(MotionEvent.ACTION_DOWN, x, y, time = clock)
        view.injectTouch(MotionEvent.ACTION_UP, x, y, time = clock + 50L)
    }

    private fun tapCell(c: Cell) = view.activeRenderer.toScreen(c.center).let { tapAt(it.x, it.y) }

    private fun tapHud(id: String) {
        val r = view.hudTarget(id) ?: throw AssertionError("no HUD button $id")
        tapAt(r.centerX(), r.centerY())
    }

    private fun drag(a: Node, b: Node) {
        val p = view.activeRenderer.toScreen(a.center)
        val q = view.activeRenderer.toScreen(b.center)
        clock += 400L
        view.injectTouch(MotionEvent.ACTION_DOWN, p.x, p.y, time = clock)
        view.injectTouch(MotionEvent.ACTION_MOVE, (p.x + q.x) / 2f, (p.y + q.y) / 2f, time = clock + 50L)
        view.injectTouch(MotionEvent.ACTION_UP, q.x, q.y, time = clock + 100L)
    }

    private fun run(seconds: Float) = repeat((seconds * 60).toInt()) { view.advance(1f / 60f) }

    @Test
    fun anErrorIsMarkedAndStaysLongerThanAnInfo() {
        drag(pc, mail)
        val error = view.shownHint
        assertNotNull(error)
        assertTrue(error!!, error.startsWith("Zu wenig Budget"))
        assertTrue(view.shownHintIsError)
        run(GameView.HINT_SECONDS + 1f)
        assertEquals("an error outlasts an info hint", error, view.shownHint)
        run(GameView.ERROR_HINT_SECONDS - GameView.HINT_SECONDS)
        assertNotEquals(error, view.shownHint)
        // Info hints keep the calm plate.
        tapCell(pc.cell)
        assertNotNull(view.shownHint)
        assertFalse(view.shownHintIsError)
    }

    @Test
    fun anInfoHintWaitsBehindAnErrorAndInfoReplacesInfo() {
        drag(pc, mail)
        val error = view.shownHint!!
        tapCell(pc.cell)
        val first = view.pendingHintTexts.single()
        assertEquals("the error is not wiped away", error, view.shownHint)
        tapCell(phone.cell)
        val newest = view.pendingHintTexts.single()
        assertNotEquals("only the newest info waits", first, newest)
        run(GameView.ERROR_HINT_SECONDS + 0.1f)
        assertEquals(newest, view.shownHint)
        assertFalse(view.shownHintIsError)
        assertTrue(view.pendingHintTexts.isEmpty())
        // An info on show is simply replaced by the next one.
        tapCell(pc.cell)
        assertEquals(first, view.shownHint)
        assertTrue(view.pendingHintTexts.isEmpty())
    }

    @Test
    fun theNewestErrorShowsAtOnceAndTheInfoWaitsBehindIt() {
        drag(pc, mail)
        val a = view.shownHint!!
        drag(pc, mail)
        assertEquals("the same error again only renews it", a, view.shownHint)
        assertTrue(view.pendingHintTexts.isEmpty())
        tapCell(pc.cell)
        val info = view.pendingHintTexts.single()
        drag(pc, far)
        val b = view.shownHint!!
        assertNotEquals("its reason shows with its buzz, not later", a, b)
        assertTrue(view.shownHintIsError)
        assertEquals("errors never wait; the info still does", listOf(info), view.pendingHintTexts)
        run(GameView.ERROR_HINT_SECONDS + 0.1f)
        assertEquals(info, view.shownHint)
        assertFalse(view.shownHintIsError)
    }

    @Test
    fun aPreviewForTheLatestTapShowsOverAnUnrelatedError() {
        drag(pc, mail)
        val error = view.shownHint!!
        world.grant(100)
        tapCell(far.cell)
        val preview = view.shownHint!!
        assertNotEquals("the price shows before a second tap can spend it", error, preview)
        assertFalse(view.shownHintIsError)
        assertTrue(view.pendingHintTexts.isEmpty())
        world.grant(-world.budget)
        drag(pc, mail)
        assertTrue(view.shownHintIsError)
        tapHud("cable:ISDN")
        assertFalse("a picked cable names itself at once", view.shownHintIsError)
    }

    @Test
    fun anUnrelatedErrorDoesNotHideThePlacingInstruction() {
        drag(pc, mail)
        assertTrue(view.shownHintIsError)
        tapHud("router")
        assertTrue(view.placingArmed)
        view.drawCurrent(Canvas(bmp))
        assertEquals(app.getString(com.mininetworks.game.R.string.hint_place_router), hintNodeText())
        // The placement's own refusal shows over it, read out as an error.
        tapCell(mail.cell)
        assertTrue(view.placingArmed)
        assertTrue(view.shownHintIsError)
        view.drawCurrent(Canvas(bmp))
        assertEquals(app.getString(com.mininetworks.game.R.string.hint_error_prefix, view.shownHint), hintNodeText())
    }

    @Test
    fun aToolDragOverAnOccupiedCellIsAnError() {
        val tile = view.hudTarget("router")!!
        val q = view.activeRenderer.toScreen(mail.center)
        clock += 400L
        view.injectTouch(MotionEvent.ACTION_DOWN, tile.centerX(), tile.centerY(), time = clock)
        view.injectTouch(MotionEvent.ACTION_MOVE, (tile.centerX() + q.x) / 2f, (tile.centerY() + q.y) / 2f, time = clock + 50L)
        view.injectTouch(MotionEvent.ACTION_MOVE, q.x, q.y, time = clock + 100L)
        view.drawCurrent(Canvas(bmp))
        assertTrue(hintNodeText(), hintNodeText().startsWith("Fehler: "))
        view.injectTouch(MotionEvent.ACTION_CANCEL, q.x, q.y, time = clock + 150L)
    }

    @Test
    fun anErrorFitsANarrowPhoneAtLargeText() {
        RuntimeEnvironment.setQualifiers("de-w360dp-h800dp-port-xxhdpi")
        RuntimeEnvironment.setFontScale(2f)
        try {
            val big = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
            view = GameView(app)
            view.accessibilityLayer.forceActive = true
            view.drawSnapshot(Canvas(big), world, big.width, big.height, time = 0f, style = "Flat")
            tapHud("pause")
            drag(pc, mail)
            assertTrue(view.shownHintIsError)
            view.drawCurrent(Canvas(big))
            val node = view.accessibilityLayer.nodes.single { it.key == "hud:hint" }
            assertTrue("${node.bounds} inside the screen", node.bounds.left >= 0f && node.bounds.right <= big.width && node.bounds.top >= 0f)
            assertFalse("cut: ${node.text}", node.shortened)
        } finally {
            RuntimeEnvironment.setFontScale(1f)
            RuntimeEnvironment.setQualifiers("de")
        }
    }

    private fun hintNodeText(): String = view.accessibilityLayer.nodes.single { it.key == "hud:hint" }.text

    @Test
    fun retryingTheSameThingReplacesItsError() {
        tapCell(mail.cell)
        val error = view.shownHint!!
        assertTrue(error, view.shownHintIsError)
        world.grant(100)
        tapCell(mail.cell)
        assertNotEquals("the retried server's preview replaces its stale error", error, view.shownHint)
        assertFalse(view.shownHintIsError)
        assertTrue(view.pendingHintTexts.isEmpty())
    }

    @Test
    fun theErrorPlateIsTintedRedWithAMark() {
        drag(pc, mail)
        draw()
        val box = view.hudBounds("hud:hint")!!
        val red = reddish(box)
        world.grant(100)
        run(GameView.ERROR_HINT_SECONDS + 0.1f)
        tapCell(pc.cell)
        assertFalse(view.shownHintIsError)
        view.drawCurrent(Canvas(bmp))
        val calm = reddish(view.hudBounds("hud:hint")!!)
        assertTrue("error plate red pixels $red vs info plate $calm", red > calm + 50)
    }

    /** Pixels in [box] that are clearly red (the mark, the tint, the rim). */
    private fun reddish(box: android.graphics.RectF): Int {
        var n = 0
        for (y in box.top.toInt().coerceAtLeast(0) until box.bottom.toInt().coerceAtMost(bmp.height)) {
            for (x in box.left.toInt().coerceAtLeast(0) until box.right.toInt().coerceAtMost(bmp.width)) {
                val p = bmp.getPixel(x, y)
                if (Color.red(p) - Color.green(p) > 60) n++
            }
        }
        return n
    }
}
