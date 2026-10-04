package com.mininetworks.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.Cable
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World
import com.mininetworks.game.render.ProblemBadges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Badges for the silent failures: a jam on a route that exists and a device linked to no server it needs. Both are
 * drawn in every style, and a tap on the device explains them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(DebugApi::class)
class ProblemBadgeTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var view: GameView
    private lateinit var world: World
    private val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)

    @Before
    fun setUp() {
        SettingsStore(app).tutorialSeen = true
        world = World(seed = 2L, spawnInitialNodes = false)
        world.incidentsEnabled = false
        for (row in world.water) row.fill(false)
        world.grant(200)
        view = GameView(app)
        draw("Iso")
    }

    private fun draw(style: String) = view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 0f, style = style)

    private fun count(color: Int): Int {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return px.count { it == color }
    }

    private fun tap(n: Node) {
        val p = view.activeRenderer.toScreen(n.center)
        view.injectTouch(MotionEvent.ACTION_DOWN, p.x, p.y)
        view.injectTouch(MotionEvent.ACTION_UP, p.x, p.y)
    }

    /** A PC on an ISDN line to a mail server that cannot keep up: three requests always wait. */
    private fun jammedPc(): Node {
        val u = world.unlocked
        val pc = world.addClient(Device.PC, u.left + 2, u.top + 2)
        val mail = world.addServer(Service.MAIL, u.left + 6, u.top + 2)
        assertTrue(world.connect(pc, mail, CableType.ISDN))
        var t = 0f
        while (t < World.Tuning.JAM_SECONDS * 2) {
            while (pc.pending.size < 3) pc.pending.addLast(Service.MAIL)
            world.update(0.1f)
            t += 0.1f
        }
        assertTrue(world.isJammed(pc))
        // Below the pre-warning, whose orange bubble rim would count as amber too.
        while (pc.pending.size > 3) pc.pending.removeLast()
        return pc
    }

    /** Pixels of [color] in a box of 2 × [half] + 1 pixels around the middle of [cable] on screen. */
    private fun countAtMiddle(cable: Cable, color: Int, half: Int = 12): Int {
        val p = view.activeRenderer.toScreen(cable.layout.pointAt(0.5f))
        val x0 = p.x.toInt() - half
        val y0 = p.y.toInt() - half
        val size = half * 2 + 1
        val px = IntArray(size * size)
        bmp.getPixels(px, 0, size, x0, y0, size, size)
        return px.count { it == color }
    }

    @Test
    fun jamGetsAnAmberBadgeAndCableInEveryStyle() {
        for (style in listOf("Iso", "Flat")) {
            setUp()
            draw(style)
            assertEquals("$style: nothing amber on an empty map", 0, count(ProblemBadges.JAM))
            jammedPc()
            val cable = world.cables.single()
            assertTrue(world.isJammed(cable))
            draw(style)
            val amber = count(ProblemBadges.JAM)
            assertTrue("$style: jam badge drawn ($amber)", amber > 20)
            val halo = countAtMiddle(cable, ProblemBadges.JAM)
            assertTrue("$style: the jammed cable glows amber ($halo)", halo > 10)
            world.nodes.forEach { it.pending.clear() }
            repeat(50) { if (world.isJammed(cable)) world.update(0.1f) }
            assertTrue(!world.isJammed(cable))
            draw(style)
            assertEquals("$style: a cable that is not jammed has no halo", 0, countAtMiddle(cable, ProblemBadges.JAM))
        }
    }

    @Test
    fun tapOnAJammedDeviceNamesTheFullCable() {
        val pc = jammedPc()
        draw("Iso")
        tap(pc)
        val cable = world.cables.single()
        assertEquals(InlineGlyphs.plain(Texts(app).jam(pc, Service.MAIL, cable)), view.shownHint)
        assertTrue(view.shownHint!!.contains("ISDN"))
    }

    @Test
    fun tapOnADeviceHeldBackByItsServerBlamesTheServer() {
        val u = world.unlocked
        val pc = world.addClient(Device.PC, u.left + 2, u.top + 2)
        val mail = world.addServer(Service.MAIL, u.left + 4, u.top + 2)
        world.jumpToWeek(CableType.FIBER.unlockWeek)
        world.grant(500)
        assertTrue(world.connect(pc, mail, CableType.FIBER))
        var t = 0f
        while (t < 30f && !(world.isJammed(pc) && world.jamServer(pc) != null)) {
            while (pc.pending.size < 5) pc.pending.addLast(Service.MAIL)
            world.update(0.1f)
            t += 0.1f
        }
        assertTrue(world.isJammed(pc))
        assertEquals(mail, world.jamServer(pc))
        draw("Iso")
        tap(pc)
        assertEquals(InlineGlyphs.plain(Texts(app).jam(pc, Service.MAIL, null)), view.shownHint)
    }

    @Test
    fun deviceOnTheWrongServerShowsTheMissingServiceAndSaysSo() {
        val u = world.unlocked
        val pc = world.addClient(Device.PC, u.left + 2, u.top + 2)
        val mail = world.addServer(Service.MAIL, u.left + 6, u.top + 2)
        val game = world.addServer(Service.GAMING, u.left + 2, u.top + 4)
        draw("Flat")
        val flatBefore = count(ProblemBadges.ALARM)
        draw("Iso")
        val before = count(ProblemBadges.ALARM)
        assertTrue(world.connect(pc, mail, CableType.ISDN))
        draw("Iso")
        val after = count(ProblemBadges.ALARM)
        assertTrue("the missing-server badge adds a red bar: $before -> $after", after > before + 20)
        tap(pc)
        assertEquals(InlineGlyphs.plain(Texts(app).noServer(pc, Service.GAMING)), view.shownHint)
        draw("Flat")
        val flat = count(ProblemBadges.ALARM)
        assertTrue("Flat draws it too ($flatBefore -> $flat)", flat > flatBefore + 20)
        assertTrue(world.connect(pc, game, CableType.ISDN))
        draw("Flat")
        assertEquals("gone once the server is cabled", flatBefore, count(ProblemBadges.ALARM))
    }
}
