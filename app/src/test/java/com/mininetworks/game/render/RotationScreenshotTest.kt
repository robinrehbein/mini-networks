package com.mininetworks.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.RadioType
import com.mininetworks.game.game.Scenario
import com.mininetworks.game.game.Scenarios
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World
import com.mininetworks.game.ui.GameView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * docs/TOP100.md B5: the turned map, rendered with Robolectric's native graphics into docs/screenshots/rotation/.
 * One busy scene (river, decorations, houses, every node kind, a data center, cables of every type, packets, a WLAN
 * radius, an excavator and a power outage) in the game frame with its HUD at 0°, 90°, 180°, 270° and 37°, the flat
 * overview at 37°, and the mountain village and the metropolis (relief and towers) at 37° and 200°.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "de-xxhdpi")
@OptIn(DebugApi::class)
class RotationScreenshotTest {

    @Before
    fun tutorialSeen() {
        SettingsStore(RuntimeEnvironment.getApplication()).tutorialSeen = true
    }

    private val shots get() = File(System.getProperty("screenshots.dir") ?: "build/screenshots", "rotation").apply { mkdirs() }

    private fun save(bmp: Bitmap, name: String) = File(shots, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

    /** Everything that is drawn differently when turned, in one river town. */
    private fun scene(): World {
        val w = World(cols = 18, rows = 11, seed = 3L, spawnInitialNodes = false)
        w.incidentsEnabled = false
        w.jumpToWeek(9)
        w.grant(600)
        val mail = w.addServer(Service.MAIL, 2, 1)
        val call = w.addServer(Service.CALL, 15, 1)
        val cdn = w.addServer(Service.STREAMING, 15, 8)
        val west = w.addRouter(4, 5)
        val east = w.addRouter(12, 5)
        val ap = w.addRadio(RadioType.WLAN, 4, 8)
        w.connect(west, east, CableType.FIBER)
        w.connect(west, mail, CableType.FIBER)
        w.connect(east, call, CableType.DSL)
        w.connect(east, cdn, CableType.COAX)
        w.connect(ap, west, CableType.DSL)
        listOf(call, cdn).forEach { w.upgradeServer(it) }
        repeat(3) { w.upgradeServer(mail) }
        for ((device, x, y) in listOf(
            Triple(Device.PC, 1, 5), Triple(Device.LAPTOP, 7, 2), Triple(Device.PHONE, 10, 8), Triple(Device.TV, 16, 5),
            Triple(Device.SMARTPHONE, 6, 9), Triple(Device.TABLET, 3, 9), Triple(Device.CONSOLE, 13, 8),
        )) {
            val c = w.addClient(device, x, y)
            if (device != Device.SMARTPHONE && device != Device.TABLET) w.connect(c, if (x < 9) west else east, CableType.ISDN)
        }
        repeat(60 * 6) { w.update(1f / 60f) }
        w.announceExcavator(w.cableBetween(west, east)!!)
        w.announcePowerOutage(ap)
        repeat(60 * 3) { w.update(1f / 60f) }
        return w
    }

    @Test
    fun gameFrameAtFourRightAnglesAndAtAnOddOne() {
        val world = scene()
        val view = GameView(RuntimeEnvironment.getApplication())
        val bmp = Bitmap.createBitmap(2400, 1080, Bitmap.Config.ARGB_8888)
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        assertNull("no compass while facing north", view.hudTarget("compass"))
        val r = view.activeRenderer
        for (angle in listOf(0f, 90f, 180f, 270f, 37f)) {
            r.rotateBy(Camera.shortestTurn(r.camera.angle, angle), r.camera.centerX, r.camera.centerY, world)
            val canvas = Canvas(bmp)
            view.drawCurrent(canvas)
            if (angle != 0f) assertNotNull("compass at $angle°", view.hudTarget("compass"))
            save(bmp, "iso-%03d.png".format(Locale.ROOT, angle.toInt()))
        }
        val flat = FlatRenderer()
        val small = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        flat.layout(small.width, small.height, world)
        for (angle in listOf(37f, 90f)) {
            flat.rotateBy(angle - flat.camera.angle, 800f, 450f, world)
            flat.draw(Canvas(small), world, drag = null, time = 1.3f)
            save(small, "flat-%03d.png".format(Locale.ROOT, angle.toInt()))
        }
    }

    /** Relief (mountains with snow, downtown towers) and the ground cache at an odd angle. */
    @Test
    fun reliefAtOddAngles() {
        for (s in listOf(Scenarios.MOUNTAIN_VILLAGE, Scenarios.METROPOLIS)) {
            val world = wiredStart(s)
            val r = IsoRenderer()
            val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
            r.layout(bmp.width, bmp.height, world)
            r.camera.zoomBy(0.75f, 800f, 450f)
            for (angle in listOf(37f, 200f)) {
                r.rotateBy(angle - r.camera.angle, 800f, 450f, world)
                // Twice: the second frame comes from the ground cache, which must match the turned view.
                val direct = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                r.draw(Canvas(direct), world, drag = null, time = 1.3f)
                r.draw(Canvas(bmp), world, drag = null, time = 1.3f)
                assertEquals("${s.id} at $angle°: the cached ground matches", 0, differingPixels(direct, bmp))
                save(bmp, "${s.id}-%03d.png".format(Locale.ROOT, angle.toInt()))
            }
        }
    }

    private fun differingPixels(a: Bitmap, b: Bitmap): Int {
        var n = 0
        for (y in 0 until a.height step 3) for (x in 0 until a.width step 3) {
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            if (abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)) > 8 || abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)) > 8 || abs((p and 0xFF) - (q and 0xFF)) > 8) n++
        }
        return n
    }

    private fun wiredStart(s: Scenario): World {
        val w = World(s, seed = 4L)
        w.incidentsEnabled = false
        w.grant(300)
        repeat(60 * 12) { w.update(1f / 60f) }
        val cable = w.unlockedCables.last()
        for (client in w.nodes.filter { it.kind == NodeKind.CLIENT }) {
            val server = w.nodes
                .filter { it.kind == NodeKind.SERVER && it.service in client.device!!.services && w.ports(it) < it.maxPorts }
                .minByOrNull { abs(it.cellX - client.cellX) + abs(it.cellY - client.cellY) } ?: continue
            w.connect(client, server, cable)
        }
        repeat(60 * 6) { w.update(1f / 60f) }
        return w
    }
}
