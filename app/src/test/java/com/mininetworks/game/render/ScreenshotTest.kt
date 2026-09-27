package com.mininetworks.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.view.MotionEvent
import com.mininetworks.game.R
import com.mininetworks.game.data.HighscoreStore
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.Bend
import com.mininetworks.game.game.Cell
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.RadioType
import com.mininetworks.game.game.Reward
import com.mininetworks.game.game.Scenario
import com.mininetworks.game.game.Scenarios
import com.mininetworks.game.game.RewardOffer
import com.mininetworks.game.game.RouteProblem
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.StressWorld
import com.mininetworks.game.game.Tutorial
import com.mininetworks.game.game.World
import com.mininetworks.game.monetization.Entitlements
import com.mininetworks.game.monetization.FakeMonetization
import com.mininetworks.game.ui.GameView
import com.mininetworks.game.ui.RewardDialog
import com.mininetworks.game.ui.Texts
import com.mininetworks.game.ui.menu.MenuAction
import com.mininetworks.game.ui.menu.Screen
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

    /** Menu shots show the app after the first launch; the tutorial has its own shots. */
    @Before
    fun tutorialSeen() {
        SettingsStore(RuntimeEnvironment.getApplication()).tutorialSeen = true
    }

    private fun scene(): World = Scenes.hud()

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
     * The performance scene (docs/PLAN.md P4.2): the [StressWorld] after 5 s, 78 nodes and 250+ packets, in both styles.
     * Also prints how long a frame takes to draw here (software canvas, not the phone's GPU; for comparison only) and
     * how much it allocates on the heap, which on a phone turns into garbage-collection pauses.
     */
    @Test
    fun renderStressWorld() {
        val w = StressWorld.build()
        repeat(60 * 5) { w.update(1f / 60f) }
        check(w.nodes.size >= 60 && w.packets.size >= 200) { "stress scene: ${w.nodes.size} nodes, ${w.packets.size} packets" }
        for (r in listOf(FlatRenderer(), IsoRenderer())) {
            val bmp = xxhdpiPhone()
            val canvas = Canvas(bmp)
            r.density = PHONE_DENSITY
            r.layout(bmp.width, bmp.height, w)
            repeat(FRAMES) { r.draw(canvas, w, drag = null, time = 1.3f) }
            val bytes = allocatedBytes()
            val start = System.nanoTime()
            repeat(FRAMES) { r.draw(canvas, w, drag = null, time = 1.3f) }
            val ms = (System.nanoTime() - start) / 1e6 / FRAMES
            val kb = (allocatedBytes() - bytes) / 1024.0 / FRAMES
            println(
                "ScreenshotTest: ${r.name} frame of the stress world (${w.packets.size} packets): " +
                    "%.1f ms, %.1f KiB allocated on the JVM heap".format(Locale.ROOT, ms, kb),
            )
            save(bmp, File(shots, "stress-${r.name.lowercase()}.png"))
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
        w.jumpToWeek(CableType.FIBER.unlockWeek)
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
            label = RuntimeEnvironment.getApplication().getString(
                R.string.drag_cost, Texts(RuntimeEnvironment.getApplication()).cable(CableType.FIBER), world.cableCost(from, target, CableType.FIBER, bend),
            ),
        )
        for (r in listOf(FlatRenderer(), IsoRenderer())) {
            val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
            r.layout(bmp.width, bmp.height, world)
            r.draw(Canvas(bmp), world, preview, time = 1.3f)
            save(bmp, File(out, "drag-preview-${r.name.lowercase()}.png"))
        }
    }

    /** Full game frame as the SurfaceView draws it on a landscape phone (2400 × 1080, xxhdpi): isometric style plus HUD. */
    @Test
    @Config(qualifiers = "de-xxhdpi")
    fun renderGameFrameWithHud() {
        val view = GameView(RuntimeEnvironment.getApplication())
        val bmp = xxhdpiPhone()
        view.drawSnapshot(Canvas(bmp), scene(), bmp.width, bmp.height, time = 1.3f)
        save(bmp, File(shots, "game-hud.png"))
    }

    /**
     * Edge to edge (docs/TOP100.md A5): the same frame on a phone with a notch on the left long edge (landscape). The map
     * runs under the notch, the HUD keeps clear of it; the notch itself is painted black as the display would hide it.
     */
    @Test
    @Config(qualifiers = "de-xxhdpi")
    fun renderGameFrameWithCutout() {
        val view = GameView(RuntimeEnvironment.getApplication())
        val bmp = xxhdpiPhone()
        val notch = 100
        view.drawSnapshot(Canvas(bmp), scene(), bmp.width, bmp.height, time = 1.3f)
        view.dispatchApplyWindowInsets(
            android.view.WindowInsets.Builder()
                .setInsets(android.view.WindowInsets.Type.displayCutout(), android.graphics.Insets.of(notch, 0, 0, 0)).build(),
        )
        view.advance(0f)
        val canvas = Canvas(bmp)
        view.drawCurrent(canvas)
        val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
        canvas.drawRoundRect(0f, bmp.height / 2f - 160f, notch - 20f, bmp.height / 2f + 160f, 40f, 40f, black)
        canvas.drawRect(0f, bmp.height / 2f - 160f, 40f, bmp.height / 2f + 160f, black)
        save(bmp, File(shots, "game-hud-cutout.png"))
        val cable = view.hudTarget("cable:${CableType.ISDN.name}")!!
        check(cable.left >= notch) { "the cable picker sits under the notch: $cable" }
    }

    /**
     * Requests that cannot leave, on a phone: a smart TV on ISDN (too narrow for streaming, squeeze badge) and a
     * console on long DSL cables over a router (ping too high, clock badge); both styles, then the HUD with a drag whose label shows
     * the ping the console would get over fiber, and the hint after tapping the TV.
     */
    @Test
    @Config(qualifiers = "de-xxhdpi")
    fun renderStuckRequests() {
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
        repeat(60 * 12) { w.update(1f / 60f) }
        check(w.routeProblem(tv, Service.STREAMING) == RouteProblem.TOO_NARROW)
        check(w.routeProblem(console, Service.GAMING) == RouteProblem.PING_TOO_HIGH)
        for (r in listOf(FlatRenderer(), IsoRenderer())) {
            val bmp = xxhdpiPhone()
            r.density = PHONE_DENSITY
            r.layout(bmp.width, bmp.height, w)
            r.draw(Canvas(bmp), w, drag = null, time = 1.3f)
            save(bmp, File(shots, "stuck-requests-${r.name.lowercase()}.png"))
        }
        val view = GameView(RuntimeEnvironment.getApplication())
        val bmp = xxhdpiPhone()
        view.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        val fiber = view.hudTarget("cable:FIBER")!!
        view.injectTouch(MotionEvent.ACTION_DOWN, fiber.centerX(), fiber.centerY())
        view.injectTouch(MotionEvent.ACTION_UP, fiber.centerX(), fiber.centerY())
        val t = view.activeRenderer.toScreen(tv.center)
        view.injectTouch(MotionEvent.ACTION_DOWN, t.x, t.y, time = 1000L)
        view.injectTouch(MotionEvent.ACTION_UP, t.x, t.y, time = 1050L)
        val a = view.activeRenderer.toScreen(console.center)
        val b = view.activeRenderer.toScreen(w.nodes.first { it.service == Service.GAMING }.center)
        view.injectTouch(MotionEvent.ACTION_DOWN, a.x, a.y, time = 2000L)
        view.injectTouch(MotionEvent.ACTION_MOVE, (a.x + b.x) / 2f, a.y, time = 2100L)
        view.injectTouch(MotionEvent.ACTION_MOVE, b.x, b.y, time = 2200L)
        view.drawCurrent(Canvas(bmp))
        save(bmp, File(shots, "stuck-requests-hud.png"))
        view.injectTouch(MotionEvent.ACTION_CANCEL, b.x, b.y)
    }

    /**
     * The deliberate controls on a phone: the clock paused in place (banner, play icon), a cable selected by a first
     * tap (yellow glow, hint with the refund), and a server's upgrade preview.
     */
    @Test
    @Config(qualifiers = "de-xxhdpi")
    fun renderControls() {
        val world = scene()
        val view = GameView(RuntimeEnvironment.getApplication())
        val bmp = xxhdpiPhone()
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        fun tap(x: Float, y: Float, at: Long) {
            view.injectTouch(MotionEvent.ACTION_DOWN, x, y, time = at)
            view.injectTouch(MotionEvent.ACTION_UP, x, y, time = at + 50L)
        }
        view.hudTarget("pause")!!.let { tap(it.centerX(), it.centerY(), 0L) }
        val cable = world.cables.first { it.type == CableType.FIBER && it.a.kind == NodeKind.ROUTER && it.b.kind == NodeKind.ROUTER }
        val p = view.activeRenderer.toScreen(cable.layout.pointAt(0.5f))
        tap(p.x, p.y, 1000L)
        view.drawCurrent(Canvas(bmp))
        save(bmp, File(shots, "controls-selected-cable.png"))
        val server = world.nodes.first { it.service == Service.CALL }
        val s = view.activeRenderer.toScreen(server.center)
        tap(s.x, s.y, 2000L)
        view.drawCurrent(Canvas(bmp))
        save(bmp, File(shots, "controls-server-preview.png"))
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
        val bmp = Bitmap.createBitmap(1200, 1620, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFFEEF3EA.toInt())
        canvas.save()
        canvas.scale(0.5f, 0.5f)
        dialog.draw(canvas, world, RewardOffer(7, listOf(Reward.BUDGET, Reward.ROUTERS)), 2400, 1080, time = 0.4f)
        canvas.translate(0f, 1080f)
        dialog.draw(canvas, world, RewardOffer(7, listOf(Reward.SERVER_VOUCHER, Reward.BUDGET)), 2400, 1080, time = 0.9f, pressed = 0)
        canvas.translate(0f, 1080f)
        dialog.draw(canvas, world, RewardOffer(7, listOf(Reward.ACCESS_POINT, Reward.CELL_TOWER)), 2400, 1080, time = 1.3f)
        canvas.restore()
        save(bmp, File(out, "reward-cards.png"))
    }

    @Test
    fun renderWireless() {
        val world = Scenes.wireless()
        for (r in listOf(FlatRenderer(), IsoRenderer())) {
            val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
            r.layout(bmp.width, bmp.height, world)
            r.draw(Canvas(bmp), world, drag = null, time = 1.3f)
            save(bmp, File(shots, "wireless-${r.name.lowercase()}.png"))
        }
    }

    /** The wireless scene with the HUD (WLAN and mast buttons) on a phone, and the hold ring on an access point. */
    @Test
    @Config(qualifiers = "de-xxhdpi")
    fun renderWirelessHud() {
        val world = Scenes.wireless()
        val view = GameView(RuntimeEnvironment.getApplication())
        val bmp = xxhdpiPhone()
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        save(bmp, File(shots, "wireless-hud.png"))

        // Holding the channel-6 access point: the ring fills up until it switches to 5 GHz.
        val ap = world.nodes.first { it.kind == NodeKind.ACCESS_POINT && it.channel == 6 }
        val p = view.activeRenderer.toScreen(ap.center)
        view.injectTouch(MotionEvent.ACTION_DOWN, p.x, p.y)
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f + 0.35f)
        save(bmp, File(shots, "wireless-hold.png"))
        view.injectTouch(MotionEvent.ACTION_CANCEL, p.x, p.y)
    }

    /**
     * Week 10 at night, shortly after the backup burst: video calls (pentagon), two security cameras streaming uploads
     * (hexagon) and cloud backups (plus) from PCs, laptops and smart-home hubs; upload answers are small acks.
     */
    private fun newServicesScene(): World {
        val w = World(cols = 16, rows = 10, seed = 3L, spawnInitialNodes = false)
        for (row in w.water) row.fill(false)
        w.incidentsEnabled = false
        w.jumpToWeek(Service.CLOUD_BACKUP.serverWeek)
        w.grant(400)
        val video = w.addServer(Service.VIDEO_CALL, 13, 1)
        val upload = w.addServer(Service.CAMERA_UPLOAD, 13, 8)
        val backup = w.addServer(Service.CLOUD_BACKUP, 2, 1)
        val mail = w.addServer(Service.MAIL, 2, 8)
        val west = w.addRouter(5, 4)
        val east = w.addRouter(10, 4)
        check(w.connect(west, east, CableType.FIBER))
        listOf(backup, mail).forEach { check(w.connect(west, it, CableType.FIBER)) }
        listOf(video, upload).forEach { check(w.connect(east, it, CableType.FIBER)) }
        listOf(video, upload, backup).forEach { s -> repeat(2) { w.upgradeServer(s) } }
        val clients = listOf(
            w.addClient(Device.CAMERA, 12, 6) to east, w.addClient(Device.CAMERA, 8, 7) to east,
            w.addClient(Device.SMART_HOME, 4, 7) to west, w.addClient(Device.SMART_HOME, 7, 2) to west,
            w.addClient(Device.LAPTOP, 3, 3) to west, w.addClient(Device.TABLET, 11, 2) to east,
        )
        for ((c, r) in clients) check(w.connect(c, r, CableType.DSL))
        check(w.connect(w.addClient(Device.PC, 6, 7), clients[2].first, CableType.DSL))
        w.addClient(Device.PC, 14, 4)
        repeat(60 * 14) { w.update(1f / 60f) }
        check(w.isNight) { "scene is set at night" }
        return w
    }

    @Test
    @Config(qualifiers = "de-xxhdpi")
    fun renderNewServices() {
        val world = newServicesScene()
        for (r in listOf(FlatRenderer(), IsoRenderer())) {
            val bmp = xxhdpiPhone()
            r.density = PHONE_DENSITY
            r.layout(bmp.width, bmp.height, world)
            r.draw(Canvas(bmp), world, drag = null, time = 1.3f)
            save(bmp, File(shots, "new-services-${r.name.lowercase()}.png"))
        }
        val view = GameView(RuntimeEnvironment.getApplication())
        val bmp = xxhdpiPhone()
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        save(bmp, File(shots, "new-services-hud.png"))
    }

    @Test
    fun renderIncidents() {
        val world = Scenes.incidents()
        for (r in listOf(FlatRenderer(), IsoRenderer())) {
            val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
            r.layout(bmp.width, bmp.height, world)
            r.draw(Canvas(bmp), world, drag = null, time = 1.3f)
            save(bmp, File(shots, "incidents-${r.name.lowercase()}.png"))
        }
        val r = IsoRenderer()
        val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        r.layout(bmp.width, bmp.height, world)
        val g = TwoFingerGesture()
        val c = r.toScreen(world.incidents.first().spot)
        g.start(c.x - 100f, c.y, c.x + 100f, c.y)
        g.move(c.x - 260f, c.y, c.x + 260f, c.y, r.camera)
        r.draw(Canvas(bmp), world, drag = null, time = 1.3f)
        save(bmp, File(shots, "incidents-zoom-iso.png"))
    }

    /** The incident scene on a phone: one line at the top, a countdown pin over each cable or node it is about. */
    @Test
    @Config(qualifiers = "de-xxhdpi")
    fun renderIncidentsHud() {
        val world = Scenes.incidents()
        val view = GameView(RuntimeEnvironment.getApplication())
        val bmp = xxhdpiPhone()
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        save(bmp, File(shots, "incidents-hud.png"))
    }

    /**
     * Portrait windows (split screen, resizable windows, or a tablet held upright): the game with its HUD, the week
     * reward choice, the main menu and the scenery picker on a 1080 × 2400 xxhdpi phone.
     */
    @Test
    @Config(qualifiers = "de-port-xxhdpi")
    fun renderPortrait() {
        val app = RuntimeEnvironment.getApplication()
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        val game = GameView(app)
        game.drawSnapshot(Canvas(bmp), scene(), bmp.width, bmp.height, time = 1.3f, style = "Iso")
        save(bmp, File(shots, "portrait-game.png"))
        val week = scene()
        week.jumpToWeek(5)
        week.advanceToNextWeek()
        val reward = GameView(app)
        bmp.eraseColor(0)
        reward.drawSnapshot(Canvas(bmp), week, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        save(bmp, File(shots, "portrait-reward.png"))
        val menu = GameView(app)
        bmp.eraseColor(0)
        menu.drawSnapshot(Canvas(bmp), menu.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = null)
        save(bmp, File(shots, "portrait-menu.png"))
        bmp.eraseColor(0)
        menu.drawSnapshot(Canvas(bmp), menu.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = Screen.SCENERIES)
        save(bmp, File(shots, "portrait-sceneries.png"))
    }

    /** Every service shape in both palettes, requests filled and responses outlined, plus every device icon. */
    @Test
    fun renderServiceShapes() {
        val bmp = Bitmap.createBitmap(1400, 560, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFDDE9D6.toInt())
        val stroke = stroke(0, 5f)
        for ((row, colorblind) in listOf(false, true).withIndex()) {
            ServiceColors.colorblind = colorblind
            for ((i, svc) in Service.entries.withIndex()) {
                val x = 100f + i * 200f
                val y = 80f + row * 150f
                Shapes.draw(c, svc.shape, x - 40f, y, 34f, fill(ServiceColors.of(svc)))
                Shapes.draw(c, svc.shape, x + 45f, y, 22f, fill(0xFFFFFFFF.toInt()))
                stroke.color = ServiceColors.of(svc)
                Shapes.draw(c, svc.shape, x + 45f, y, 22f, stroke)
            }
        }
        ServiceColors.colorblind = false
        val icons = DeviceIcons()
        for ((i, d) in Device.entries.withIndex()) icons.device(c, d, 70f + i * 130f, 440f, 40f)
        save(bmp, File(shots, "service-shapes.png"))
    }

    /** The grown city: fitted to the unlocked block, and zoomed out to the whole grid with the locked rings dimmed. */
    @Test
    fun renderGrowingMap() {
        val out = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        val world = Scenes.grownCity()
        check(world.unlocked == world.unlockedArea(5) && world.unlocked != world.unlockedArea(1))
        for (r in listOf(FlatRenderer(), IsoRenderer())) {
            val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
            r.layout(bmp.width, bmp.height, world)
            r.draw(Canvas(bmp), world, drag = null, time = 1.3f)
            save(bmp, File(out, "map-grown-${r.name.lowercase()}.png"))
            // The overview: the whole grid fitted to the view, the locked rings dimmed.
            r.camera.fit(r.mapBounds(world.bounds), animate = false)
            bmp.eraseColor(0)
            r.draw(Canvas(bmp), world, drag = null, time = 1.3f)
            save(bmp, File(out, "map-overview-${r.name.lowercase()}.png"))
        }
    }

    /** Pinch-zoomed close-up (isometric main style) around a spot off the screen centre. */
    @Test
    fun renderZoomedIn() {
        val out = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        val world = Scenes.grownCity()
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

    /** Landscape phone as sold today: 2400 × 1080 pixels at xxhdpi, 800 × 360 dp. */
    private fun xxhdpiPhone() = Bitmap.createBitmap(2400, 1080, Bitmap.Config.ARGB_8888)

    /** Main menu over the demo town, with a best score (German, the default language). */
    @Test
    @Config(qualifiers = "de-xhdpi")
    fun renderMainMenu() {
        HighscoreStore(RuntimeEnvironment.getApplication()).submit(1187)
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

    /**
     * Monetization on a landscape phone (docs/PLAN.md 5.1): "remove ads" in the main menu, the pack pill on the scenery
     * picker, "continue with a video" on the game-over card and the extra router on the week reward screen.
     */
    @Test
    @Config(qualifiers = "de-xhdpi")
    fun renderMonetization() {
        val app = RuntimeEnvironment.getApplication()
        HighscoreStore(app).submit(480)
        val shop = FakeMonetization(prices = mapOf(Entitlements.REMOVE_ADS to "2,99 €", Entitlements.SCENERY_PACK to "4,99 €"))
        val bmp = phoneBitmap()
        fun shot(view: GameView, name: String) {
            bmp.eraseColor(0)
            view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = null)
            save(bmp, File(shots, name))
        }

        val menu = GameView(app).also { it.monetization = shop }
        shot(menu, "monetization-main-menu.png")
        menu.drawSnapshot(Canvas(bmp), menu.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = Screen.SCENERIES)
        shot(menu, "monetization-sceneries.png")

        val world = scene()
        val phone = world.addClient(Device.PHONE, 8, 9)
        repeat(World.Tuning.MAX_PENDING) { phone.pending.addLast(Service.CALL) }
        val over = GameView(app).also { it.monetization = shop }
        over.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        repeat(60 * 20) { over.advance(1f / 60f) }
        check(over.currentScreen == Screen.GAME_OVER)
        shot(over, "monetization-game-over.png")

        val week = scene()
        week.jumpToWeek(5)
        week.advanceToNextWeek()
        val reward = GameView(app).also { it.monetization = shop }
        reward.drawSnapshot(Canvas(bmp), week, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        shot(reward, "monetization-reward.png")
    }

    /**
     * The isometric style up close: trees, pines, bushes and a house on free land, soft shadows, the river shimmer,
     * a cable being laid (half grown, spark at its tip), rings where requests reach the server and delivered packets
     * rising above their devices.
     */
    @Test
    fun renderIsoPolish() {
        val w = World(cols = 12, rows = 8, seed = 5L, spawnInitialNodes = false)
        w.incidentsEnabled = false
        w.jumpToWeek(3)
        w.grant(200)
        val mail = w.addServer(Service.MAIL, 2, 2)
        val router = w.addRouter(4, 5)
        w.connect(router, mail, CableType.DSL)
        val clients = listOf(w.addClient(Device.PC, 1, 5), w.addClient(Device.LAPTOP, 4, 1), w.addClient(Device.PHONE, 2, 7))
        for (c in clients) w.connect(c, router, CableType.DSL)
        repeat(2) { check(w.upgradeServer(mail)) }
        val late = w.addClient(Device.TV, 9, 6)
        var s = 0
        while (s++ < 60 * 30) {
            clients.forEach { if (it.pending.size < 2) it.pending.addLast(Service.MAIL) }
            w.update(1f / 60f)
            val fresh = w.arrivals.filter { w.time - it.time < 0.25f }
            if (s > 60 * 6 && fresh.any { it.isResponse } && fresh.any { !it.isResponse }) break
        }
        check(w.connect(late, router, CableType.COAX))
        repeat(12) { w.update(1f / 60f) }
        val r = IsoRenderer()
        val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        r.layout(bmp.width, bmp.height, w)
        r.camera.zoomBy(1.35f, bmp.width / 2f, bmp.height / 2f)
        r.draw(Canvas(bmp), w, drag = null, time = 1.3f)
        save(bmp, File(shots, "iso-polish.png"))
    }

    /** Game over, first second: the camera glides towards the device whose queue overflowed, red ripples around it. */
    @Test
    @Config(qualifiers = "de-xhdpi")
    fun renderGameOverFocus() {
        val world = scene()
        val phone = world.addClient(Device.PHONE, 8, 9)
        repeat(World.Tuning.MAX_PENDING) { phone.pending.addLast(Service.CALL) }
        val view = GameView(RuntimeEnvironment.getApplication())
        val bmp = phoneBitmap()
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        var s = 0
        while (!world.gameOver && s++ < 60 * 30) view.advance(1f / 60f)
        repeat(60) { view.advance(1f / 60f) }
        check(view.currentScreen == Screen.PLAYING)
        view.drawCurrent(Canvas(bmp))
        save(bmp, File(shots, "game-over-focus.png"))
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

    private companion object {
        /** Frames drawn to warm up and then to time in [renderStressWorld]. */
        const val FRAMES = 20
        /** Pixels per dp of [xxhdpiPhone], for renderers drawn without a view. */
        const val PHONE_DENSITY = 3f
    }

    /** Bytes this thread allocated so far (HotSpot's thread bean, by reflection: android.jar has no java.lang.management). */
    /**
     * The adaptive launcher icon (P4.3): full 108 dp canvas with the 66 dp safe zone marked, then as the launcher shows it
     * (circle and rounded-square masks), the themed monochrome layer, and at home-screen size; plus the 512 px Play Store icon.
     */
    @Test
    fun renderLauncherIcon() {
        val out = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        val icon = RuntimeEnvironment.getApplication().getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val size = 432
        val gap = 24
        val bmp = Bitmap.createBitmap(5 * size + 6 * gap, size + 2 * gap, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFFEEF3EA.toInt())
        fun layers(x: Float, y: Float, s: Int, mask: Path?, monochrome: Boolean = false) {
            canvas.save()
            canvas.translate(x, y)
            mask?.let { canvas.clipPath(it) }
            if (monochrome) {
                canvas.drawColor(0xFFDCE6F0.toInt())
                icon.monochrome!!.apply { setBounds(0, 0, s, s); setTint(0xFF2B4A63.toInt()); draw(canvas) }
            } else {
                for (d in listOf(icon.background, icon.foreground)) { d.setBounds(0, 0, s, s); d.draw(canvas) }
            }
            canvas.restore()
        }
        fun circle(s: Int) = Path().apply { addCircle(s / 2f, s / 2f, s * 36f / 108f, Path.Direction.CW) }
        fun squircle(s: Int) = Path().apply {
            val inset = s * 18f / 108f
            addRoundRect(inset, inset, s - inset, s - inset, s * 16f / 108f, s * 16f / 108f, Path.Direction.CW)
        }
        val y = gap.toFloat()
        layers(gap.toFloat(), y, size, null)
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xAAFFFFFF.toInt()
            canvas.drawCircle(gap + size / 2f, y + size / 2f, size * 33f / 108f, this)
        }
        layers(2f * gap + size, y, size, circle(size))
        layers(3f * gap + 2 * size, y, size, squircle(size))
        layers(4f * gap + 3 * size, y, size, circle(size), monochrome = true)
        // Home-screen size: 48 dp at xxhdpi (144 px for the visible circle of a 216 px canvas), plus half of that.
        val x = 5f * gap + 4 * size
        layers(x, y, 216, circle(216))
        layers(x + 40f, y + 240f, 108, circle(108))
        save(bmp, File(out, "launcher-icon.png"))
        // Play Store hi-res icon: the central 72 dp a launcher shows, as a full 512 px square (Play applies its own mask).
        val store = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val storeCanvas = Canvas(store)
        val full = 512 * 108 / 72
        val offset = (512 - full) / 2
        for (d in listOf(icon.background, icon.foreground)) { d.setBounds(offset, offset, offset + full, offset + full); d.draw(storeCanvas) }
        save(store, File(out, "store-icon-512.png"))
    }

    private fun allocatedBytes(): Long {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        return Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes").invoke(bean) as Long
    }

    private fun save(bmp: Bitmap, file: File) = file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

    /**
     * A new game of [s] a few seconds in: every client cabled to the nearest server it needs (cheapest invented cable),
     * so the start map looks like it is being played.
     */
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

    /**
     * Every scenery at its start: the game frame in the isometric style with its HUD on a phone, and the whole map in
     * the flat overview.
     */
    @Test
    @Config(qualifiers = "de-xxhdpi")
    fun renderSceneries() {
        for (s in Scenarios.all) {
            val world = wiredStart(s)
            val view = GameView(RuntimeEnvironment.getApplication())
            val phone = xxhdpiPhone()
            view.drawSnapshot(Canvas(phone), world, phone.width, phone.height, time = 1.3f, style = "Iso")
            save(phone, File(shots, "scenery-${s.id}.png"))
            val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
            val flat = FlatRenderer()
            flat.layout(bmp.width, bmp.height, world)
            flat.camera.fit(flat.mapBounds(world.bounds), animate = false)
            flat.draw(Canvas(bmp), world, drag = null, time = 1.3f)
            save(bmp, File(shots, "scenery-${s.id}-flat.png"))
        }
    }

    /**
     * The scenery picker on a landscape phone: the metropolis unlocked by a river town score, the island on its way
     * (progress bar), the mountain village and 2030 in the shop, and the hint after tapping a locked card.
     */
    @Test
    @Config(qualifiers = "de-xhdpi")
    fun renderSceneryPicker() {
        val scores = HighscoreStore(RuntimeEnvironment.getApplication())
        scores.submit(1834, Scenarios.RIVER_TOWN.id)
        scores.submit(Scenarios.ISLAND_TARGET * 3 / 4, Scenarios.METROPOLIS.id)
        val view = GameView(RuntimeEnvironment.getApplication())
        val bmp = phoneBitmap()
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = null)
        val play = view.menuTarget(MenuAction.PLAY)!!
        view.injectTouch(MotionEvent.ACTION_DOWN, play.centerX(), play.centerY())
        view.injectTouch(MotionEvent.ACTION_UP, play.centerX(), play.centerY())
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = null)
        val island = view.sceneryTarget(Scenarios.ISLAND.id)!!
        view.injectTouch(MotionEvent.ACTION_DOWN, island.centerX(), island.centerY())
        view.injectTouch(MotionEvent.ACTION_UP, island.centerX(), island.centerY())
        bmp.eraseColor(0)
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = null)
        save(bmp, File(shots, "menu-sceneries.png"))
    }

    /** The picker in English. */
    @Test
    @Config(qualifiers = "en-xhdpi")
    fun renderSceneryPickerEnglish() {
        val view = GameView(RuntimeEnvironment.getApplication())
        val bmp = phoneBitmap()
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = Screen.SCENERIES)
        bmp.eraseColor(0)
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = null)
        save(bmp, File(shots, "menu-sceneries-en.png"))
    }

    /**
     * The tutorial on a landscape phone, one shot per step as the player gets there: the dragged cable, the router
     * button, the TV on ISDN too narrow for streaming, a DSL cable too slow for gaming, the overload ring, and the finish.
     */
    @Test
    @Config(qualifiers = "de-xhdpi")
    fun renderTutorial() = tutorialShots("tutorial", allSteps = true)

    /** The first tutorial step in English. */
    @Test
    @Config(qualifiers = "en-xhdpi")
    fun renderTutorialEnglish() = tutorialShots("tutorial-en", allSteps = false)

    private fun tutorialShots(prefix: String, allSteps: Boolean) {
        val app = RuntimeEnvironment.getApplication()
        SettingsStore(app).tutorialSeen = false
        val view = GameView(app)
        val t: Tutorial = view.currentTutorial!!
        val w = t.world
        val bmp = phoneBitmap()
        var time = 0.3f
        fun shot(name: String) {
            bmp.eraseColor(0)
            view.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time, screen = null)
            save(bmp, File(shots, "$prefix-$name.png"))
        }
        fun play(seconds: Float) {
            repeat((seconds * 60).toInt()) { view.advance(1f / 60f) }
            time += seconds
        }
        fun tapHud(id: String) {
            val r = view.hudTarget(id)!!
            view.injectTouch(MotionEvent.ACTION_DOWN, r.centerX(), r.centerY())
            view.injectTouch(MotionEvent.ACTION_UP, r.centerX(), r.centerY())
        }
        shot("1-cable")
        play(1f)
        shot("1-cable")
        if (!allSteps) return
        w.connect(t.pc, t.mailServer, CableType.ISDN)
        play(0.6f)
        shot("2-router")
        val router = w.nearestFree(Cell(t.phones[0].cellX - 2, t.phones[0].cellY + 1))!!.let { w.placeRouter(it.x, it.y)!! }
        for (n in t.phones + t.callServer!!) w.connect(n, router, CableType.ISDN)
        play(0.6f)
        shot("3-bandwidth")
        w.connect(t.tv!!, t.streamServer!!, CableType.ISDN)
        play(3f)
        shot("3-bandwidth-narrow")
        tapHud("cable:DSL")
        w.upgrade(w.cableBetween(t.tv!!, t.streamServer!!)!!, CableType.DSL)
        play(0.2f)
        w.connect(t.pc, t.gameServer!!, CableType.DSL)
        play(3f)
        shot("4-ping")
        tapHud("cable:FIBER")
        w.upgrade(w.cableBetween(t.pc, t.gameServer!!)!!, CableType.FIBER)
        play(5f)
        shot("5-overload")
        w.connect(t.newPc!!, t.mailServer, CableType.DSL)
        play(1.5f)
        shot("done")
    }
}
