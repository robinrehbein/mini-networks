package com.mininetworks.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World
import com.mininetworks.game.ui.GameView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders a fixed scene with every renderer into docs/screenshots/, using Robolectric's native graphics.
 * Lets anyone (human or agent) check visuals without an emulator:
 *   ./gradlew testDebugUnitTest --tests '*ScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
@OptIn(DebugApi::class)
class ScreenshotTest {

    private fun scene(): World {
        val w = World(seed = 3L, spawnInitialNodes = false)
        w.jumpToWeek(6)
        w.grant(200)
        val mail = w.addServer(Service.MAIL, 2, 1)
        val call = w.addServer(Service.CALL, 13, 7)
        val game = w.addServer(Service.GAMING, 13, 1)
        val cdn = w.addServer(Service.STREAMING, 2, 8)
        val r1 = w.addRouter(4, 4)
        val r2 = w.addRouter(11, 4)
        val pc = w.addClient(Device.PC, 5, 1)
        val phone = w.addClient(Device.PHONE, 1, 5)
        val laptop = w.addClient(Device.LAPTOP, 6, 7)
        val console = w.addClient(Device.CONSOLE, 10, 2)
        val smartphone = w.addClient(Device.SMARTPHONE, 14, 4)
        val tv = w.addClient(Device.TV, 4, 8)
        val tablet = w.addClient(Device.TABLET, 10, 8)
        val watch = w.addClient(Device.WATCH, 6, 3)
        w.connect(mail, pc, CableType.ISDN)
        w.connect(pc, r1, CableType.DSL)
        w.connect(phone, r1, CableType.ISDN)
        w.connect(r1, laptop, CableType.DSL)
        w.connect(r1, r2, CableType.FIBER)
        w.connect(r2, console, CableType.FIBER)
        w.connect(console, game, CableType.FIBER)
        w.connect(r2, smartphone, CableType.COAX)
        w.connect(r2, call, CableType.DSL)
        w.connect(cdn, tv, CableType.COAX)
        w.connect(tv, laptop, CableType.COAX)
        w.connect(r2, tablet, CableType.COAX)
        w.connect(watch, r1, CableType.DSL)
        w.connect(watch, pc, CableType.DSL)
        w.upgradeServer(game)
        w.upgradeServer(cdn)
        w.upgradeServer(cdn)
        repeat(60 * 25) { w.update(1f / 60f) }
        return w
    }

    @Test
    fun renderAllStyles() {
        val out = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        val world = scene()
        for (r in listOf(FlatRenderer(), IsoRenderer())) {
            val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
            r.layout(bmp.width, bmp.height, world)
            r.draw(Canvas(bmp), world, drag = null, time = 1.3f)
            save(bmp, File(out, "prototype-${r.name.lowercase()}.png"))
        }
    }

    /** Full game frame as the SurfaceView draws it: default (flat) renderer plus HUD. */
    @Test
    fun renderGameFrameWithHud() {
        val out = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        val view = GameView(RuntimeEnvironment.getApplication())
        val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        view.drawSnapshot(Canvas(bmp), scene(), bmp.width, bmp.height, time = 1.3f)
        save(bmp, File(out, "game-hud.png"))
    }

    private fun save(bmp: Bitmap, file: File) = file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
