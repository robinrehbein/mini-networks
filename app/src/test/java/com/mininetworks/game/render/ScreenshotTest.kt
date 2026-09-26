package com.mininetworks.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import com.mininetworks.game.data.HighscoreStore
import com.mininetworks.game.game.Bend
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.Reward
import com.mininetworks.game.game.RewardOffer
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World
import com.mininetworks.game.ui.GameView
import com.mininetworks.game.ui.RewardDialog
import com.mininetworks.game.ui.Texts
import com.mininetworks.game.ui.menu.MenuAction
import com.mininetworks.game.ui.menu.Screen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * Renders a fixed scene with every renderer into docs/screenshots/, using Robolectric's native graphics.
 * Texts are German (the default language) unless a test asks for another locale.
 * Lets anyone (human or agent) check visuals without an emulator:
 *   ./gradlew testDebugUnitTest --tests '*ScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "de")
@OptIn(DebugApi::class)
class ScreenshotTest {

    private fun scene(): World {
        val w = World(cols = 16, rows = 10, seed = 3L, spawnInitialNodes = false)
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
        repeat(3) { check(w.upgradeServer(mail)) { "mail server becomes a data center" } }
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

    /**
     * Close-up of the round trip and the data center: requests (filled) and responses (smaller, outlined) share
     * the cables; the tier-4 server covers 2×2 cells.
     */
    @Test
    fun renderRoundTripAndDataCenter() {
        val out = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        val w = World(cols = 8, rows = 5, seed = 3L, spawnInitialNodes = false)
        for (row in w.water) row.fill(false)
        w.jumpToWeek(5)
        w.grant(200)
        val dc = w.addServer(Service.MAIL, 5, 1)
        val router = w.addRouter(3, 3)
        w.connect(router, dc, CableType.FIBER)
        for ((x, y) in listOf(1 to 1, 1 to 4, 6 to 4)) {
            val pc = w.addClient(Device.PC, x, y)
            w.connect(pc, router, CableType.DSL)
            repeat(4) { pc.pending.addLast(Service.MAIL) }
        }
        repeat(3) { check(w.upgradeServer(dc)) }
        repeat(60 * 4) { w.update(1f / 60f) }
        check(w.packets.any { it.isResponse } && w.packets.any { !it.isResponse }) { "scene should show both directions" }
        for (r in listOf(FlatRenderer(), IsoRenderer())) {
            val bmp = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
            r.layout(bmp.width, bmp.height, w)
            r.draw(Canvas(bmp), w, drag = null, time = 1.3f)
            save(bmp, File(out, "round-trip-${r.name.lowercase()}.png"))
        }
    }

    /** A cable being dragged: the preview shows the exact grid layout and price that a release would build. */
    @Test
    fun renderDragPreview() {
        val out = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        val world = scene()
        val from = world.nodes.first { it.device == Device.TABLET }
        val target = world.nodes.first { it.service == Service.GAMING }
        val bend = Bend.VERTICAL_FIRST
        val preview = DragPreview(
            from = from,
            end = target.center,
            target = target,
            type = CableType.FIBER,
            layout = world.planLayout(from, target, bend),
            blocked = world.connectError(from, target, CableType.FIBER, bend) != null,
            label = "${Texts(RuntimeEnvironment.getApplication()).cable(CableType.FIBER)} · ${world.cableCost(from, target, CableType.FIBER, bend)}",
        )
        for (r in listOf(FlatRenderer(), IsoRenderer())) {
            val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
            r.layout(bmp.width, bmp.height, world)
            r.draw(Canvas(bmp), world, preview, time = 1.3f)
            save(bmp, File(out, "drag-preview-${r.name.lowercase()}.png"))
        }
    }

    /** Full game frame as the SurfaceView draws it: default (isometric) renderer plus HUD. */
    @Test
    fun renderGameFrameWithHud() {
        val out = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        val view = GameView(RuntimeEnvironment.getApplication())
        val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        view.drawSnapshot(Canvas(bmp), scene(), bmp.width, bmp.height, time = 1.3f)
        save(bmp, File(out, "game-hud.png"))
    }

    /** Week change: the map pauses under the reward choice (isometric main style), the unlock message stays visible. */
    @Test
    fun renderRewardChoice() {
        val out = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        val world = scene()
        world.jumpToWeek(5)
        world.advanceToNextWeek()
        val view = GameView(RuntimeEnvironment.getApplication())
        val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        save(bmp, File(out, "reward-choice.png"))
    }

    /** Every reward card, drawn by the dialog alone on a phone-shaped canvas. */
    @Test
    fun renderRewardCards() {
        val out = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        val world = scene()
        val dialog = RewardDialog(RuntimeEnvironment.getApplication())
        val rewards = Reward.entries
        val bmp = Bitmap.createBitmap(1200, 1080, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFFEEF3EA.toInt())
        canvas.save()
        canvas.scale(0.5f, 0.5f)
        dialog.draw(canvas, world, RewardOffer(7, listOf(rewards[0], rewards[1])), 2400, 1080, time = 0.4f)
        canvas.translate(0f, 1080f)
        dialog.draw(canvas, world, RewardOffer(7, listOf(rewards[2], rewards[0])), 2400, 1080, time = 0.9f, pressed = 0)
        canvas.restore()
        save(bmp, File(out, "reward-cards.png"))
    }

    /**
     * A 32×20 city in week 5: two rings have grown around the 16×10 start block. Every client is wired to the
     * nearest server it can use so the network carries traffic.
     */
    private fun grownCity(): World {
        val w = World(seed = 4L)
        repeat(60 * 45 * 4 + 60 * 5) {
            if (w.rewardOffer != null) w.chooseReward(0)
            w.nodes.forEach { it.pending.clear() }
            w.update(1f / 60f)
        }
        w.grant(600)
        for (client in w.nodes.filter { it.kind == NodeKind.CLIENT }) {
            val server = w.nodes
                .filter { it.kind == NodeKind.SERVER && it.service in client.device!!.services && w.ports(it) < it.maxPorts }
                .minByOrNull { abs(it.cellX - client.cellX) + abs(it.cellY - client.cellY) } ?: continue
            w.connect(client, server, CableType.DSL)
        }
        repeat(60 * 3) { w.update(1f / 60f) }
        return w
    }

    /** The grown city: fitted to the unlocked block, and zoomed out to the whole grid with the locked rings dimmed. */
    @Test
    fun renderGrowingMap() {
        val out = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        val world = grownCity()
        check(world.unlocked == world.unlockedArea(5) && world.unlocked != world.unlockedArea(1))
        for (r in listOf(FlatRenderer(), IsoRenderer())) {
            val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
            r.layout(bmp.width, bmp.height, world)
            r.draw(Canvas(bmp), world, drag = null, time = 1.3f)
            save(bmp, File(out, "map-grown-${r.name.lowercase()}.png"))
            r.camera.zoomBy(0.01f, bmp.width / 2f, bmp.height / 2f)
            bmp.eraseColor(0)
            r.draw(Canvas(bmp), world, drag = null, time = 1.3f)
            save(bmp, File(out, "map-overview-${r.name.lowercase()}.png"))
        }
    }

    /** Pinch-zoomed close-up (isometric main style) around a spot off the screen centre. */
    @Test
    fun renderZoomedIn() {
        val out = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        val world = grownCity()
        val r = IsoRenderer()
        val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        r.layout(bmp.width, bmp.height, world)
        val g = TwoFingerGesture()
        g.start(700f, 400f, 900f, 400f)
        g.move(560f, 380f, 1000f, 380f, r.camera)
        r.draw(Canvas(bmp), world, drag = null, time = 1.3f)
        save(bmp, File(out, "camera-zoom-iso.png"))
    }

    /** First frame of a new game in the isometric main style: the view fits the 16×10 start block between the HUD rows. */
    @Test
    fun renderNewGameIso() {
        val out = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        val view = GameView(RuntimeEnvironment.getApplication())
        val world = World(seed = 4L)
        repeat(60 * 20) { world.update(1f / 60f) }
        val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        save(bmp, File(out, "new-game-iso.png"))
    }

    private val shots get() = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    /** Landscape phone: 800 × 360 dp at 2x. */
    private fun phoneBitmap() = Bitmap.createBitmap(1600, 720, Bitmap.Config.ARGB_8888)

    /** Main menu over the demo town, with a best score (German, the default language). */
    @Test
    @Config(qualifiers = "de-xhdpi")
    fun renderMainMenu() {
        HighscoreStore(RuntimeEnvironment.getApplication()).submit(1234)
        val view = GameView(RuntimeEnvironment.getApplication())
        val bmp = phoneBitmap()
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = null)
        save(bmp, File(shots, "menu-main.png"))
    }

    /** The main menu with an English system language. */
    @Test
    @Config(qualifiers = "en-xhdpi")
    fun renderMainMenuEnglish() {
        val view = GameView(RuntimeEnvironment.getApplication())
        val bmp = phoneBitmap()
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = null)
        save(bmp, File(shots, "menu-main-en.png"))
    }

    /** Pause menu over the running game, and the settings reached from it. */
    @Test
    @Config(qualifiers = "de-xhdpi")
    fun renderPauseAndSettings() {
        val view = GameView(RuntimeEnvironment.getApplication())
        val world = scene()
        val bmp = phoneBitmap()
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        view.back()
        view.advance(0f)
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, screen = null)
        save(bmp, File(shots, "menu-pause.png"))
        val settings = view.menuTarget(MenuAction.SETTINGS)!!
        view.injectTouch(MotionEvent.ACTION_DOWN, settings.centerX(), settings.centerY())
        view.injectTouch(MotionEvent.ACTION_UP, settings.centerX(), settings.centerY())
        bmp.eraseColor(0)
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, screen = null)
        save(bmp, File(shots, "menu-settings.png"))
    }

    /** Game over: score, and the previous best that it did not beat. */
    @Test
    @Config(qualifiers = "de-xhdpi")
    fun renderGameOver() {
        HighscoreStore(RuntimeEnvironment.getApplication()).submit(480)
        val world = scene()
        val phone = world.addClient(Device.PHONE, 8, 9)
        repeat(World.Tuning.MAX_PENDING) { phone.pending.addLast(Service.CALL) }
        val view = GameView(RuntimeEnvironment.getApplication())
        val bmp = phoneBitmap()
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        repeat(60 * 20) { view.advance(1f / 60f) }
        check(view.currentScreen == Screen.GAME_OVER)
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, screen = null)
        save(bmp, File(shots, "game-over.png"))
    }

    /** The isometric scene with the colorblind palette. */
    @Test
    fun renderColorblindPalette() {
        val world = scene()
        ServiceColors.colorblind = true
        try {
            val r = IsoRenderer()
            val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
            r.layout(bmp.width, bmp.height, world)
            r.draw(Canvas(bmp), world, drag = null, time = 1.3f)
            save(bmp, File(shots, "palette-colorblind-iso.png"))
        } finally {
            ServiceColors.colorblind = false
        }
    }

    private fun save(bmp: Bitmap, file: File) = file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
