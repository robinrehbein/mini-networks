package com.mininetworks.game.render

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import com.mininetworks.game.R
import com.mininetworks.game.data.HighscoreStore
import com.mininetworks.game.data.ProgressStore
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.ColorTheme
import com.mininetworks.game.game.DailyStreak
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.Scenarios
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.Vec2
import com.mininetworks.game.game.World
import com.mininetworks.game.monetization.FakeMonetization
import com.mininetworks.game.ui.GameView
import com.mininetworks.game.ui.TextWrap
import com.mininetworks.game.ui.menu.LogoMark
import com.mininetworks.game.ui.menu.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs

/**
 * docs/TOP100.md F3: the Play Store screenshots, 8 per device class, and the 1024 × 500 feature graphic, rendered with
 * Robolectric's native graphics into docs/store/screenshots/<device>/<language>/ and docs/store/feature-graphic.png.
 *
 * Every picture is the real game (GameView with its map, menus and cards) in the language of the listing, on a card
 * that fills most of the frame: the game is laid out at the card's own size, so the map reaches its edges instead of
 * floating in the middle. Marketing shots hide the HUD ([GameView.hudHidden]) except the drag and the overload shot,
 * which sells the tension with the real play screen. Each slide has its own accent colour and a faint cable pattern in
 * the backdrop, the app icon before every caption; two slides are full bleed so the set does not repeat one template.
 * Portrait phones get three of the slides.
 * 16:9 as Play asks for phones and tablets (1080–7680 px per side): phone 1920 × 1080 at 420 dpi (731 × 411 dp),
 * 7" tablet 1920 × 1080 at 280 dpi (1097 × 617 dp, sw600), 10" tablet 2560 × 1440 at xhdpi (1280 × 720 dp, sw720);
 * the UI grows on tablets like it does on the devices ([com.mininetworks.game.ui.TextScale.uiScale]).
 *
 * Languages: German (default listing) and English for every device; the others with
 *   ./gradlew testDebugUnitTest --tests '*StoreScreenshotTest*' -Pstore.locales=fr,es,it,pt-rBR,pl,nl,tr,ja,ko,zh-rCN
 * (StoreScreenshotTest.LANGUAGES has captions for all twelve).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
@OptIn(DebugApi::class)
class StoreScreenshotTest {

    /** A device class of the store listing: the frame size and the density of the game drawn inside it. */
    enum class StoreDevice(
        val id: String, val width: Int, val height: Int, val qualifiers: String,
        /** Tablets show more of the map than a phone (their screens do): the shots' zoom times this. */
        val zoom: Float = 1f,
        /** Which of the eight slides this device gets (portrait phones: the three that read best upright). */
        val slides: List<Int> = (0 until 8).toList(),
    ) {
        PHONE("phone", 1920, 1080, "w731dp-h411dp-land-420dpi"),
        TABLET_7("tablet-7", 1920, 1080, "w1097dp-h617dp-land-280dpi", zoom = 0.88f),
        TABLET_10("tablet-10", 2560, 1440, "w1280dp-h720dp-land-xhdpi", zoom = 0.82f),
        /** Portrait is what the Play Store carousel shows first on phones. */
        PHONE_PORTRAIT("phone-portrait", 1080, 1920, "w411dp-h731dp-port-420dpi", slides = listOf(0, 2, 3)),
    }

    /** The device being rendered; its [StoreDevice.zoom] scales every shot. */
    private var device = StoreDevice.PHONE

    private val app get() = RuntimeEnvironment.getApplication()

    /** The heavy display weight of the headlines (Roboto Black where the platform has it). */
    private val display: Typeface by lazy { Typeface.create("sans-serif-black", Typeface.NORMAL) }
    private val storeDir get() = File(System.getProperty("store.dir") ?: "build/store").apply { mkdirs() }

    /** 2026-09-27 15:00 UTC, as in the retention screenshots: Island & harbour, "Few routers". */
    private val now = 1_790_467_200_000L + 15 * 3_600_000L

    @Before
    fun setUp() {
        SettingsStore(app).tutorialSeen = true
        HighscoreStore(app).submit(1187)
        ProgressStore(app).streak = DailyStreak(20_722L, 4, 9)
    }

    @After
    fun tearDown() = Cosmetic.reset()

    /** The languages to render: German and English, plus those given with -Pstore.locales. */
    private val languages: List<String>
        get() = (listOf("de", "en") + (System.getProperty("store.locales") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }).distinct()

    @Test
    fun eightScreenshotsPerDeviceAndLanguage() {
        for (lang in languages) {
            val captions = LANGUAGES[lang] ?: error("no captions for $lang")
            for (device in StoreDevice.entries) {
                RuntimeEnvironment.setQualifiers("$lang-${device.qualifiers}")
                this.device = device
                val dir = File(storeDir, "screenshots/${device.id}/$lang").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val shots = shots()
                assertEquals(8, shots.size)
                assertEquals(8, captions.size)
                for (i in device.slides) {
                    val shot = shots[i]
                    Cosmetic.reset()
                    val card = cardRect(device, shot.bleed)
                    val game = Bitmap.createBitmap(card.width().toInt(), card.height().toInt(), Bitmap.Config.ARGB_8888)
                    shot.draw(game)
                    Cosmetic.reset()
                    val framed = frame(device, game, captions[i], i, shot.bleed)
                    writeRgbPng(framed, File(dir, "%02d-%s.png".format(i + 1, shot.name)))
                    assertEquals(device.width, framed.width)
                    assertEquals(device.height, framed.height)
                }
            }
        }
    }

    /**
     * One of the eight motifs: a name for the file and how to draw it on the game surface. A [bleed] shot fills the
     * whole frame with the game and carries its caption on a dark gradient, so the set does not repeat one template.
     */
    private class Shot(val name: String, val bleed: Boolean = false, val draw: (Bitmap) -> Unit)

    /** Height of the caption band of [d]. */
    private fun band(d: StoreDevice) = if (d.height > d.width) d.height * 0.12f else d.height * BAND

    /**
     * Where the game sits in the frame of [d]: on a card 93 % of the width, from under the caption band to near the
     * bottom; or the whole frame for a [bleed] shot.
     */
    private fun cardRect(d: StoreDevice, bleed: Boolean = false): RectF {
        val w = d.width.toFloat()
        val h = d.height.toFloat()
        if (bleed) return RectF(0f, 0f, w, h)
        val side = w * 0.035f
        return RectF(side, band(d), w - side, h - h * 0.04f)
    }

    private fun view(hud: Boolean = false): GameView = GameView(app).also {
        it.wallClock = { now }
        // No ad button and no prices in store art: the pictures sell the game, not the shop.
        it.monetization = FakeMonetization(rewardedLoaded = false)
        it.hudHidden = !hud
    }

    /**
     * [world] on the game surface [bmp], the map zoomed by [zoom] around [focus] (a world point, moved to the middle;
     * the screen center without one). [theme] recolours the ground; [before] runs after the camera is set.
     */
    private fun game(
        bmp: Bitmap, world: World, zoom: Float, view: GameView = view(), focus: Vec2? = null, theme: ColorTheme? = null,
        before: (GameView) -> Unit = {},
    ) {
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        theme?.let { Cosmetic.theme = it }
        val cam = view.activeRenderer.camera
        if (focus != null) {
            val p = view.activeRenderer.toScreen(focus)
            cam.panBy(bmp.width / 2f - p.x, bmp.height / 2f - p.y)
        }
        cam.zoomBy(zoom * device.zoom, bmp.width / 2f, bmp.height / 2f)
        before(view)
        bmp.eraseColor(0)
        view.drawCurrent(Canvas(bmp))
    }

    private fun tap(v: GameView, id: String) {
        val r = v.hudTarget(id) ?: throw AssertionError("no HUD button $id")
        v.injectTouch(MotionEvent.ACTION_DOWN, r.centerX(), r.centerY())
        v.injectTouch(MotionEvent.ACTION_UP, r.centerX(), r.centerY())
    }

    private fun shots(): List<Shot> = listOf(
        // 1. The hero: a busy late-game town, every cable technology, packets on every line.
        Shot("town") { bmp ->
            val world = lateTown()
            val nodes = world.nodes
            // A little right of the network's middle, so the cables at its east edge stay in the picture.
            val focus = Vec2(nodes.map { it.center.x }.average().toFloat() + 0.8f, nodes.map { it.center.y }.average().toFloat())
            game(bmp, world, zoom = if (bmp.height > bmp.width) 2.1f else 1.5f, focus = focus)
        },
        // 2. Laying a cable, close up: fiber picked, the finger drags from the tablet to the game server; glow, touch
        //    trail and the price bubble fill the card (no empty board edge).
        Shot("drag") { bmp ->
            val world = Scenes.hud()
            val from = world.nodes.first { it.device == Device.TABLET }
            val to = world.nodes.first { it.service == Service.GAMING }
            // Between the drag and the board's middle (the drag runs near the board's edge), so no empty backdrop shows.
            val mid = Vec2(
                (from.center.x + to.center.x) / 2f * 0.5f + world.cols / 2f * 0.5f,
                (from.center.y + to.center.y) / 2f * 0.5f + world.rows / 2f * 0.5f - 1f,
            )
            val v = view(hud = true)
            var tip = Vec2(0f, 0f)
            game(bmp, world, zoom = 3.1f, view = v, focus = mid) { gv ->
                gv.drawCurrent(Canvas(bmp))
                tap(gv, "cable:${CableType.FIBER.name}")
                gv.hudHidden = true
                val a = gv.activeRenderer.toScreen(from.center)
                val b = gv.activeRenderer.toScreen(to.center)
                gv.injectTouch(MotionEvent.ACTION_DOWN, a.x, a.y)
                val steps = 14
                for (k in 1..steps) {
                    val t = k * 0.93f / steps
                    // A finger never moves in a straight line: a slight arc makes the trail readable.
                    val bow = kotlin.math.sin(t * Math.PI).toFloat() * (b.y - a.y).let { abs(it) * 0.18f + bmp.height * 0.03f }
                    tip = Vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t - bow)
                    gv.injectTouch(MotionEvent.ACTION_MOVE, tip.x, tip.y)
                }
            }
            finger(bmp, tip.x, tip.y)
            // The small tutorial board ends below the drag: crop onto the action so no bare board edge shows.
            cropZoom(bmp, 1.25f, 0.02f, 0.02f)
        },
        // 3. The tension: the real play screen with its HUD (date, packet count, cable bar), two devices whose queues
        //    are full and whose red overload rings are closing.
        Shot("overload") { bmp ->
            val world = lateTown()
            // Half a minute more of normal play, so the packet counter shows a game well under way.
            repeat(60 * 30) {
                if (world.rewardOffer != null) world.chooseReward(0)
                world.update(1f / 60f)
                // A steady player: no ring ever fills during this stretch.
                world.nodes.forEach { it.overload = 0f }
            }
            check(!world.gameOver)
            val hot = overloaded(world)
            game(bmp, world, zoom = if (bmp.height > bmp.width) 1.9f else 1.35f, view = view(hud = true), focus = hot.center)
        },
        // 4. The turned map, full bleed: the dense city of 2030 late in a game, packets on every line, at an odd
        //    angle with a turn gesture drawn over it.
        Shot("rotation", bleed = true) { bmp ->
            val world = lateFuture()
            val focus = Vec2(world.nodes.map { it.center.x }.average().toFloat(), world.nodes.map { it.center.y }.average().toFloat())
            game(bmp, world, zoom = if (bmp.height > bmp.width) 2.2f else 1.55f, focus = focus) { v ->
                val r = v.activeRenderer
                r.rotateBy(Camera.shortestTurn(r.camera.angle, 34f), r.camera.centerX, r.camera.centerY, world)
            }
            rotationHint(bmp)
        },
        // 5. Wireless, close on the radios: coverage circles and the dashed radio links are the hero, no queues.
        Shot("wireless") { bmp ->
            val world = storeWireless()
            world.nodes.forEach { it.pending.clear() }
            val radios = world.nodes.filter { it.kind == NodeKind.ACCESS_POINT || it.kind == NodeKind.CELL_TOWER }
            val focus = Vec2(radios.map { it.center.x }.average().toFloat() + 0.8f, radios.map { it.center.y }.average().toFloat() - 1.3f)
            game(bmp, world, zoom = 1.9f, focus = focus)
        },
        // 6. The week reward at its moment: confetti over the dark-scrimmed map and the two cards.
        Shot("reward") { bmp ->
            view().drawSnapshot(Canvas(bmp), FormFactorScreenshotTest.rewardWorld(), bmp.width, bmp.height, time = 1.3f, style = "Iso")
            com.mininetworks.game.ui.Confetti(uiDensity()).draw(Canvas(bmp), bmp.width, bmp.height, 0.55f, 6)
        },
        // 7. Five sceneries, each in its own colours, as a collage of their maps; the daily challenge as a callout.
        Shot("sceneries") { bmp ->
            // The callout sits in the top corner of the last tile, over its map: it covers none of the five names.
            val tiles = sceneryCollage(bmp)
            dailyCallout(bmp, tiles.last())
        },
        // 8. Incidents, full bleed and close up in the desert theme: an excavator cuts a cable, a router is dark.
        Shot("incidents", bleed = true) { bmp ->
            val world = Scenes.incidents()
            repeat(60 * 2) { world.update(1f / 60f) }
            val focus = world.incidents.first { it.struck && it.cable != null }.spot
            game(bmp, world, zoom = 1.7f, focus = focus, theme = ColorTheme.DESERT)
        },
    )

    /** Enlarges [bmp] in place by [f], keeping the part that starts at ([fx], [fy]) of its size in the top left. */
    private fun cropZoom(bmp: Bitmap, f: Float, fx: Float, fy: Float) {
        val src = bmp.copy(Bitmap.Config.ARGB_8888, false)
        val w = bmp.width / f
        val h = bmp.height / f
        val l = (bmp.width * fx).coerceAtMost(bmp.width - w)
        val t = (bmp.height * fy).coerceAtMost(bmp.height - h)
        bmp.eraseColor(0)
        Canvas(bmp).drawBitmap(src, android.graphics.Rect(l.toInt(), t.toInt(), (l + w).toInt(), (t + h).toInt()), RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /** Pixels per UI dp of the current qualifiers, as the game computes them. */
    private fun uiDensity() = app.resources.displayMetrics.density * com.mininetworks.game.ui.TextScale.uiScale(app.resources.configuration.smallestScreenWidthDp)

    /**
     * Two devices near the middle of [w] with full queues and closing overload rings (the tension of the game); the
     * worse one is returned.
     */
    private fun overloaded(w: World): com.mininetworks.game.game.Node {
        val cx = w.nodes.map { it.center.x }.average().toFloat()
        val cy = w.nodes.map { it.center.y }.average().toFloat()
        val hot = w.nodes.filter { it.kind == NodeKind.CLIENT }.sortedBy { abs(it.center.x - cx) + abs(it.center.y - cy) }.take(2)
        hot.forEachIndexed { i, n ->
            val services = n.device!!.services
            n.pending.clear()
            repeat(World.Tuning.MAX_PENDING) { k -> n.pending.addLast(services[k % services.size]) }
            n.overload = if (i == 0) 0.78f else 0.45f
        }
        return hot.first()
    }

    /** The 2030 scenery late in a game: grown, every device wired, servers upgraded, rush-hour traffic. */
    private fun lateFuture(): World {
        val w = World(Scenarios.FUTURE, seed = 4L)
        w.incidentsEnabled = false
        repeat(60 * 45) {
            if (w.rewardOffer != null) w.chooseReward(0)
            w.nodes.forEach { it.pending.clear() }
            w.update(1f / 60f)
        }
        w.jumpToWeek(w.week + 3)
        w.grant(3000)
        repeat(60 * 30) {
            if (w.rewardOffer != null) w.chooseReward(0)
            w.nodes.forEach { it.pending.clear() }
            w.update(1f / 60f)
        }
        wireNearest(w)
        w.nodes.filter { it.kind == NodeKind.SERVER }.forEach { s -> repeat(2) { w.upgradeServer(s) } }
        repeat(60 * 5) {
            if (w.rewardOffer != null) w.chooseReward(0)
            w.update(1f / 60f)
        }
        rushHour(w)
        check(!w.gameOver) { "the 2030 city must still run" }
        return w
    }

    /**
     * A small callout in the top right corner of [tile]: a flame and "Daily challenge", instead of a whole slide of UI.
     * A long label shrinks so the callout stays inside the tile, clear of the name labels at the tiles' bottoms.
     */
    private fun dailyCallout(bmp: Bitmap, tile: RectF) {
        val c = Canvas(bmp)
        val d = uiDensity()
        val label = app.getString(R.string.menu_daily)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 17f * d; color = 0xFFFFFFFF.toInt() }
        val h = 46f * d
        val room = tile.width() - 20f * d - h * 1.5f
        if (p.measureText(label) > room) p.textSize *= room / p.measureText(label)
        val w = p.measureText(label) + h * 1.5f
        val r = RectF(tile.right - 10f * d - w, tile.top + 10f * d, tile.right - 10f * d, tile.top + 10f * d + h)
        c.drawRoundRect(RectF(r).apply { offset(0f, 3f * d) }, h / 2f, h / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 })
        c.drawRoundRect(r, h / 2f, h / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE4572E.toInt() })
        // The streak flame: an orange drop with a yellow core.
        val fx = r.left + h * 0.55f
        val fy = r.centerY() + h * 0.08f
        fun flame(size: Float, color: Int) {
            val path = Path().apply {
                moveTo(fx, fy - size * 1.25f)
                cubicTo(fx + size * 0.9f, fy - size * 0.4f, fx + size * 0.8f, fy + size * 0.6f, fx, fy + size * 0.65f)
                cubicTo(fx - size * 0.8f, fy + size * 0.6f, fx - size * 0.9f, fy - size * 0.4f, fx, fy - size * 1.25f)
                close()
            }
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }
        flame(h * 0.32f, 0xFFFFFFFF.toInt())
        flame(h * 0.2f, 0xFFFFC21A.toInt())
        c.drawText(label, r.left + h * 1.0f, r.centerY() + p.textSize * 0.36f, p)
    }

    /**
     * A busy town late in a game: grown twice, every client wired to its nearest server with the cable a player would
     * pick for the distance (DSL near, coax further, fiber far), servers upgraded, and traffic running.
     */
    private fun lateTown(): World {
        val w = World(seed = 4L)
        w.incidentsEnabled = false
        repeat(60 * 45 * 3) {
            if (w.rewardOffer != null) w.chooseReward(0)
            w.nodes.forEach { it.pending.clear() }
            w.update(1f / 60f)
        }
        // Some years later, so fiber is invented: the long runs get it.
        w.jumpToWeek(6)
        w.grant(2000)
        wireNearest(w)
        w.nodes.filter { it.kind == NodeKind.SERVER }.forEach { s -> repeat(2) { w.upgradeServer(s) } }
        repeat(60 * 7) {
            if (w.rewardOffer != null) w.chooseReward(0)
            w.update(1f / 60f)
        }
        rushHour(w)
        check(!w.gameOver) { "the late town must still run" }
        return w
    }

    /**
     * Peak traffic: every client asks for each of its services at once, then the network runs for a moment, so the
     * picture catches many packets in flight (between rushes a snapshot often finds the cables empty).
     */
    private fun rushHour(w: World, seconds: Float = 1.1f, rounds: Int = 2) {
        for (n in w.nodes) if (n.kind == NodeKind.CLIENT) {
            n.pending.clear()
            repeat(rounds) { n.device!!.services.forEach { s -> n.pending.addLast(s) } }
        }
        repeat((seconds * 60).toInt()) { w.update(1f / 60f) }
    }

    private fun wireNearest(w: World) {
        val cables = w.unlockedCables
        for (client in w.nodes.filter { it.kind == NodeKind.CLIENT }) {
            val server = w.nodes
                .filter { it.kind == NodeKind.SERVER && it.service in client.device!!.services && w.ports(it) < it.maxPorts }
                .minByOrNull { abs(it.cellX - client.cellX) + abs(it.cellY - client.cellY) } ?: continue
            val d = abs(server.cellX - client.cellX) + abs(server.cellY - client.cellY)
            val type = when {
                d <= 2 -> CableType.ISDN
                d <= 4 -> CableType.DSL
                d <= 7 -> CableType.COAX
                else -> CableType.FIBER
            }.takeIf { it in cables } ?: cables.last()
            w.connect(client, server, type)
        }
    }


    /**
     * Wireless for the store: three clearly separate radio zones (a Wi-Fi access point, a 5 GHz one and a cell tower)
     * with their clients, so each zone reads at thumbnail size (the test scene packs four access points together).
     */
    private fun storeWireless(): World {
        val w = World(cols = 16, rows = 10, seed = 3L, spawnInitialNodes = false)
        for (row in w.water) row.fill(false)
        w.incidentsEnabled = false
        w.jumpToWeek(7)
        w.grant(400, extraAccessPoints = 1, extraCellTowers = 1)
        val call = w.addServer(Service.CALL, 13, 1)
        val cdn = w.addServer(Service.STREAMING, 2, 1)
        val mail = w.addServer(Service.MAIL, 8, 1)
        val west = w.addRouter(5, 3)
        val east = w.addRouter(10, 3)
        check(w.connect(west, east, CableType.FIBER))
        listOf(cdn, mail).forEach { check(w.connect(west, it, CableType.FIBER)) }
        check(w.connect(east, call, CableType.FIBER))
        repeat(2) { listOf(call, cdn, mail).forEach { s -> w.upgradeServer(s) } }
        val ap = w.addRadio(com.mininetworks.game.game.RadioType.WLAN, 3, 6)
        val fast = w.addRadio(com.mininetworks.game.game.RadioType.WLAN, 8, 7)
        val tower = w.addRadio(com.mininetworks.game.game.RadioType.CELL, 13, 6)
        w.upgradeTo5Ghz(fast)
        check(w.connect(ap, west, CableType.FIBER))
        listOf(fast, tower).forEach { check(w.connect(it, east, CableType.FIBER)) }
        for ((device, x, y) in listOf(
            Triple(Device.LAPTOP, 2, 5), Triple(Device.TABLET, 2, 7), Triple(Device.TV, 4, 7),
            Triple(Device.PC, 7, 8), Triple(Device.TABLET, 9, 8), Triple(Device.SMARTPHONE, 8, 9),
            Triple(Device.SMARTPHONE, 14, 7), Triple(Device.WATCH, 12, 8), Triple(Device.TABLET, 14, 5),
        )) w.addClient(device, x, y)
        repeat(60 * 10) { w.update(1f / 60f) }
        rushHour(w, seconds = 1.6f, rounds = 1)
        return w
    }

    /** The metropolis a little after the start, every device wired to the nearest server with the best cable. */
    private fun wiredStart(): World {
        val w = World(Scenarios.METROPOLIS, seed = 4L)
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
        repeat(60 * 30) { w.update(1f / 60f) }
        return w
    }

    /** A fingertip pressing at ([x], [y]): a soft shadow, a translucent pad and a ring, as screen-recording tools show touches. */
    private fun finger(bmp: Bitmap, x: Float, y: Float) {
        val c = Canvas(bmp)
        val d = app.resources.displayMetrics.density * com.mininetworks.game.ui.TextScale.uiScale(app.resources.configuration.smallestScreenWidthDp)
        val r = 26f * d
        c.drawCircle(x + 3f * d, y + 5f * d, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000; maskFilter = BlurMaskFilter(8f * d, BlurMaskFilter.Blur.NORMAL) })
        c.drawCircle(x, y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt() })
        c.drawCircle(x, y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f * d; color = 0xFFF28C28.toInt() })
        c.drawCircle(x, y, r * 0.35f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF28C28.toInt() })
    }

    /** A two-finger turn drawn over the map: a curved double arrow around the middle and two touch points. */
    private fun rotationHint(bmp: Bitmap) {
        val c = Canvas(bmp)
        val d = app.resources.displayMetrics.density
        val cx = bmp.width * 0.5f
        val cy = bmp.height * 0.5f
        val r = bmp.height * 0.34f
        val oval = RectF(cx - r, cy - r * 0.62f, cx + r, cy + r * 0.62f)
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xAAFFFFFF.toInt(); strokeWidth = 14f * d }
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xFF3BA55C.toInt(); strokeWidth = 6f * d }
        for ((start, sweep) in listOf(200f to 70f, 20f to 70f)) {
            c.drawArc(oval, start, sweep, false, halo)
            c.drawArc(oval, start, sweep, false, arc)
            // Arrow head at the end of each arc, pointing along it.
            val end = Math.toRadians((start + sweep).toDouble())
            val ex = cx + r * kotlin.math.cos(end).toFloat()
            val ey = cy + r * 0.62f * kotlin.math.sin(end).toFloat()
            val tx = -kotlin.math.sin(end).toFloat()
            val ty = kotlin.math.cos(end).toFloat() * 0.62f
            val len = kotlin.math.hypot(tx, ty)
            val ux = tx / len; val uy = ty / len
            val s = 16f * d
            val head = Path().apply {
                moveTo(ex + ux * s, ey + uy * s)
                lineTo(ex - uy * s * 0.8f, ey + ux * s * 0.8f)
                lineTo(ex + uy * s * 0.8f, ey - ux * s * 0.8f)
                close()
            }
            c.drawPath(head, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3BA55C.toInt() })
        }
        // Two fingertips where the turn starts.
        val tip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt() }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF3BA55C.toInt(); strokeWidth = 3f * d }
        for (a in listOf(200.0, 20.0)) {
            val x = cx + r * kotlin.math.cos(Math.toRadians(a)).toFloat()
            val y = cy + r * 0.62f * kotlin.math.sin(Math.toRadians(a)).toFloat()
            c.drawCircle(x, y, 18f * d, tip)
            c.drawCircle(x, y, 18f * d, ring)
        }
    }

    /**
     * The five sceneries as a collage: the metropolis large on the left, the others in a 2 × 2 grid, each its own map
     * in full colour a little into the game, with its name and era on a label.
     */
    private fun sceneryCollage(bmp: Bitmap): List<RectF> {
        val c = Canvas(bmp)
        c.drawColor(0xFFF3F1EC.toInt())
        val d = app.resources.displayMetrics.density * com.mininetworks.game.ui.TextScale.uiScale(app.resources.configuration.smallestScreenWidthDp)
        val gap = 8f * d
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val bigW = (w - 3 * gap) * 0.5f
        val smallW = (w - 3 * gap - bigW - gap) / 2f
        val smallH = (h - 3 * gap) / 2f
        val order = listOf(Scenarios.METROPOLIS, Scenarios.RIVER_TOWN, Scenarios.ISLAND, Scenarios.MOUNTAIN_VILLAGE, Scenarios.FUTURE)
        val rects = listOf(
            RectF(gap, gap, gap + bigW, h - gap),
            RectF(2 * gap + bigW, gap, 2 * gap + bigW + smallW, gap + smallH),
            RectF(3 * gap + bigW + smallW, gap, w - gap, gap + smallH),
            RectF(2 * gap + bigW, 2 * gap + smallH, 2 * gap + bigW + smallW, h - gap),
            RectF(3 * gap + bigW + smallW, 2 * gap + smallH, w - gap, h - gap),
        )
        val texts = com.mininetworks.game.ui.Texts(app)
        val name = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; color = 0xFF262B33.toInt(); textSize = 17f * d }
        val era = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; color = 0xFF2E7D46.toInt(); textSize = 13f * d }
        val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }
        for ((s, r) in order.zip(rects)) {
            val world = sceneryWorld(s)
            val tile = Bitmap.createBitmap(r.width().toInt(), r.height().toInt(), Bitmap.Config.ARGB_8888)
            val iso = IsoRenderer()
            iso.density = d
            iso.layout(tile.width, tile.height, world)
            iso.camera.zoomBy(if (s == Scenarios.METROPOLIS) 1.25f else 1.45f, tile.width / 2f, tile.height / 2f)
            iso.draw(Canvas(tile), world, drag = null, time = 1.3f)
            c.save()
            c.clipPath(Path().apply { addRoundRect(r, 14f * d, 14f * d, Path.Direction.CW) })
            c.drawBitmap(tile, r.left, r.top, null)
            c.restore()
            val title = texts.scenario(s)
            val sub = app.getString(R.string.scenery_from_year, s.startYear)
            // Long names ("Kleinstadt am Fluss", French, Polish) shrink to the tile instead of running past the label.
            name.textSize = 17f * d
            val room = r.width() - 20f * d - 24f * d
            if (name.measureText(title) > room) name.textSize *= room / name.measureText(title)
            val pw = maxOf(name.measureText(title), era.measureText(sub)) + 24f * d
            val ph = name.textSize + era.textSize + 22f * d
            val pr = RectF(r.left + 10f * d, r.bottom - 10f * d - ph, r.left + 10f * d + pw, r.bottom - 10f * d)
            c.drawRoundRect(pr, 12f * d, 12f * d, plate)
            c.drawText(title, pr.left + 12f * d, pr.top + 8f * d + name.textSize * 0.9f, name)
            c.drawText(sub, pr.left + 12f * d, pr.bottom - 9f * d, era)
        }
        return rects
    }

    /** Scenery [s] a little into a game, wired and with traffic, for the collage. */
    private fun sceneryWorld(s: com.mininetworks.game.game.Scenario): World {
        val w = World(s, seed = 4L)
        w.incidentsEnabled = false
        repeat(60 * 25) {
            w.nodes.forEach { it.pending.clear() }
            w.update(1f / 60f)
        }
        w.grant(600)
        wireNearest(w)
        repeat(60 * 5) { w.update(1f / 60f) }
        rushHour(w)
        return w
    }

    /** Livelier colours for store art: saturation up by a third and a touch more contrast than the game's calm look. */
    private val vivid = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        val m = android.graphics.ColorMatrix().apply { setSaturation(1.35f) }
        val k = 1.08f
        val t = -0.04f * 255f
        m.postConcat(android.graphics.ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, t, 0f, k, 0f, 0f, t, 0f, 0f, k, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        colorFilter = android.graphics.ColorMatrixColorFilter(m)
    }

    /**
     * [game] in the frame of [d] under [caption]: on a card over the backdrop, or full bleed with the caption on a dark
     * gradient ([bleed]); slide [index] picks the accent colour. Every caption carries the app icon.
     */
    private fun frame(d: StoreDevice, game: Bitmap, caption: String, index: Int, bleed: Boolean): Bitmap {
        val out = Bitmap.createBitmap(d.width, d.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val w = d.width.toFloat()
        val h = d.height.toFloat()
        val service = SLIDE_SERVICES[index]
        val accent = ServiceColors.defaultOf(service)
        val band = band(d)
        if (bleed) {
            c.drawBitmap(game, 0f, 0f, vivid)
            val scrim = Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, band * 2f, intArrayOf(0xF2112634.toInt(), 0xCC112634.toInt(), 0x00112634), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w, band * 2f, scrim)
        } else {
            background(c, w, h, accent, index)
            val dst = cardRect(d)
            val radius = minOf(w, h) * 0.028f
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x73000000; maskFilter = BlurMaskFilter(w * 0.012f, BlurMaskFilter.Blur.NORMAL) }
            c.drawRoundRect(RectF(dst.left, dst.top + w * 0.006f, dst.right, dst.bottom + w * 0.006f), radius, radius, shadow)
            c.save()
            c.clipPath(Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) })
            c.drawBitmap(game, dst.left, dst.top, vivid)
            c.restore()
            // A thin rim in the slide's accent ties the card to the backdrop.
            c.drawRoundRect(dst, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.0022f; color = accent and 0xFFFFFF or 0xCC000000.toInt() })
        }

        // Caption: the app icon, then the headline in a heavy weight, centred in the band above.
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = display; textAlign = Paint.Align.LEFT }
        if (bleed) text.setShadowLayer(band * 0.04f, 0f, band * 0.02f, 0x99000000.toInt())
        text.textSize = band * 0.5f
        val hero = index == 0
        val markSize = band * (if (hero) 0.7f else 0.56f)
        val maxW = w * 0.9f - markSize * 1.3f
        while (text.measureText(caption) > maxW && text.textSize > band * 0.3f) text.textSize *= 0.96f
        val lines = TextWrap.wrap(caption, maxW) { text.measureText(it) }
        assertTrue("caption \"$caption\" fits ${d.id}: $lines", lines.size <= 2 && lines.all { text.measureText(it) <= maxW })
        if (lines.size == 2) text.textSize = minOf(text.textSize, band * 0.36f)
        val lineH = text.textSize * 1.08f
        val textW = lines.maxOf { text.measureText(it) }
        val total = markSize * 1.3f + textW
        val x0 = (w - total) / 2f
        val cy = band * 0.5f
        LogoMark(app).draw(c, x0 + markSize / 2f, cy, markSize)
        val firstBaseline = cy - (lines.size - 1) * lineH / 2f + text.textSize * 0.36f
        lines.forEachIndexed { i, l -> c.drawText(l, x0 + markSize * 1.3f, firstBaseline + i * lineH, text) }
        return out
    }

    /**
     * The backdrop: the launcher's dusk blue, tinted towards the slide's [accent] at the top, with a faint isometric
     * grid and a few cables with packets running through it (the game's pattern), different on every slide.
     */
    private fun background(c: Canvas, w: Float, h: Float, accent: Int, index: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val top = blend(0xFF1B3848.toInt(), accent, 0.38f)
        p.shader = RadialGradient(w * (0.2f + 0.2f * (index % 4)), -h * 0.1f, w * 0.9f, top, 0xFF112634.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        // Isometric grid.
        p.style = Paint.Style.STROKE
        p.strokeWidth = maxOf(1f, w / 1920f)
        p.color = 0x12FFFFFF
        val step = w / 24f
        var x = -h * 2f
        while (x < w + h * 2f) {
            c.drawLine(x, 0f, x + h * 2f, h, p)
            c.drawLine(x, h, x + h * 2f, 0f, p)
            x += step
        }
        // Cables of the pattern: a few iso polylines in the accent and the fiber orange, with packets on them.
        val rnd = java.util.Random(31L * index + 7)
        val cable = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.004f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(3) { k ->
            val col = if (k == 1) 0xFFF28C28.toInt() else accent
            cable.color = col and 0xFFFFFF or 0x55000000
            val path = Path()
            var px = rnd.nextFloat() * w
            var py = h * (0.05f + 0.1f * rnd.nextFloat())
            path.moveTo(px, py)
            val pts = ArrayList<Pair<Float, Float>>()
            repeat(4) { j ->
                val len = step * (2 + rnd.nextInt(4))
                val dir = if ((j + k) % 2 == 0) 1f else -1f
                px += len * dir
                py += len * 0.5f
                path.lineTo(px, py)
                pts += px to py
            }
            c.drawPath(path, cable)
            dot.color = col and 0xFFFFFF or 0x99000000.toInt()
            for ((qx, qy) in pts) c.drawCircle(qx, qy, w * 0.005f, dot)
        }
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(s: Int) = ((((a shr s) and 0xFF) * (1 - f) + ((b shr s) and 0xFF) * f).toInt() and 0xFF) shl s
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    /**
     * The feature graphic (1024 × 500, no alpha): the busy late-game town across the whole width, one server glowing
     * with packets streaming to it as the hero moment, and a dusk-blue gradient rising from the bottom that carries
     * the app icon, the name as a large heavy wordmark and a one-line tagline. Nothing important lies in the outer 10 %
     * (Play may crop it or lay buttons over it).
     */
    @Test
    @Config(qualifiers = "en-w731dp-h411dp-land-420dpi")
    fun featureGraphic() {
        val w = 1024
        val h = 500
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFF1B3848.toInt())
        val world = lateTown()
        val town = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val r = IsoRenderer()
        r.density = 1.7f
        r.layout(w, h, world)
        // The hero: the busiest server, placed a little above the middle, where the gradient does not reach.
        val hero = world.nodes.filter { it.kind == NodeKind.SERVER }.maxBy { s -> world.cables.count { it.a === s || it.b === s } }
        r.toScreen(hero.center).let { p -> r.camera.panBy(w * 0.5f - p.x, h * 0.36f - p.y) }
        r.camera.zoomBy(1.7f, w * 0.5f, h * 0.36f)
        r.draw(Canvas(town), world, drag = null, time = 1.3f)
        c.drawBitmap(town, 0f, 0f, vivid)
        // A soft glow around the hero server and a stream of packets towards it.
        val hp = r.toScreen(hero.center)
        val glowColor = ServiceColors.of(hero.service!!)
        c.drawCircle(hp.x, hp.y - 30f, 150f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(hp.x, hp.y - 30f, 150f, intArrayOf(glowColor and 0xFFFFFF or 0x88000000.toInt(), glowColor and 0xFFFFFF or 0x33000000, 0), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        })
        // The gradient that carries the title.
        c.drawRect(0f, h * 0.45f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, h * 0.45f, 0f, h.toFloat(), intArrayOf(0x00112634, 0xCC112634.toInt(), 0xF2112634.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        })
        // Icon and wordmark, centred as one group inside the safe area; the name is the same in every language.
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = display; textSize = 92f; setShadowLayer(8f, 0f, 3f, 0x80000000.toInt()) }
        val name = app.getString(R.string.app_name)
        val icon = 108f
        val gap = 22f
        while (icon + gap + title.measureText(name) > w * 0.78f) title.textSize -= 1f
        val groupW = icon + gap + title.measureText(name)
        val x0 = (w - groupW) / 2f
        assertTrue("name inside the safe area", x0 >= w * 0.1f && x0 + groupW <= w * 0.9f)
        fun write(tagline: String?, file: File) {
            val shot = out.copy(Bitmap.Config.ARGB_8888, true)
            val sc = Canvas(shot)
            // Without a tagline the wordmark sits lower, where the tagline would be.
            val baseline = if (tagline == null) 432f else 408f
            LogoMark(app).draw(sc, x0 + icon / 2f, baseline - title.textSize * 0.36f, icon)
            sc.drawText(name, x0 + icon + gap, baseline, title)
            if (tagline != null) {
                val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD9A8.toInt(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; textSize = 30f }
                while (tag.measureText(tagline) > w * 0.78f) tag.textSize -= 1f
                sc.drawText(tagline, w / 2f, 448f, tag)
                assertTrue("tagline on one line inside the safe area", tag.measureText(tagline) <= w * 0.8f)
            }
            for (x in 0 until w step 64) for (yy in 0 until h step 50) assertEquals("opaque at $x,$yy", 0xFF, shot.getPixel(x, yy) ushr 24)
            assertEquals(w, shot.width)
            assertEquals(h, shot.height)
            writeRgbPng(shot, file)
        }
        // The default graphic carries no words but the name, so it fits every listing; each language that has its
        // listing's graphics rendered also gets one with its own tagline (Play takes a feature graphic per language).
        write(null, File(storeDir, "feature-graphic.png"))
        val dir = File(storeDir, "feature-graphic").apply { mkdirs() }
        for (lang in languages) write(FEATURE_TAGLINES.getValue(lang), File(dir, "$lang.png"))
    }

    /**
     * Play wants JPEG or 24-bit PNG without alpha, but Bitmap.compress always writes RGBA: [bmp] (opaque) is written
     * here as an 8-bit RGB PNG (color type 2) with java.util.zip.
     */
    private fun writeRgbPng(bmp: Bitmap, file: File) {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val raw = ByteArrayOutputStream()
        DeflaterOutputStream(raw, Deflater(9)).use { z ->
            val row = ByteArray(1 + 3 * w)
            for (y in 0 until h) {
                row[0] = 0
                for (x in 0 until w) {
                    val p = pixels[y * w + x]
                    row[1 + 3 * x] = (p shr 16).toByte()
                    row[2 + 3 * x] = (p shr 8).toByte()
                    row[3 + 3 * x] = p.toByte()
                }
                z.write(row)
            }
        }
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            fun chunk(type: String, data: ByteArray) {
                out.writeInt(data.size)
                val crc = CRC32()
                val t = type.toByteArray(Charsets.US_ASCII)
                out.write(t); crc.update(t)
                out.write(data); crc.update(data)
                out.writeInt(crc.value.toInt())
            }
            val header = ByteArrayOutputStream()
            DataOutputStream(header).use { it.writeInt(w); it.writeInt(h); it.write(byteArrayOf(8, 2, 0, 0, 0)) }
            chunk("IHDR", header.toByteArray())
            chunk("IDAT", raw.toByteArray())
            chunk("IEND", ByteArray(0))
        }
    }

    companion object {
        /**
         * The feature graphic's one-line tagline per language: the game's own arc, from ISDN in 1995 to fiber (as in
         * the store texts). The default graphic has none (docs/store/feature-graphic.png).
         */
        val FEATURE_TAGLINES = mapOf(
            "de" to "Verkabel deine Stadt – von ISDN bis Glasfaser",
            "en" to "Wire up your town, from ISDN to fiber",
            "fr" to "Câble ta ville, du RNIS à la fibre",
            "es" to "Conecta tu pueblo, de la RDSI a la fibra",
            "it" to "Cabla la tua città, dall'ISDN alla fibra",
            "pt-rBR" to "Conecte sua cidade, do ISDN à fibra",
            "pl" to "Okabluj miasto – od ISDN po światłowód",
            "nl" to "Verbind je stad, van ISDN tot glasvezel",
            "tr" to "Şehrini bağla: ISDN'den fibere",
            "ja" to "ISDNから光ファイバーまで、町をつなごう",
            "ko" to "ISDN부터 광케이블까지, 도시를 연결하세요",
            "zh-rCN" to "从 ISDN 到光纤，为城镇铺设网络",
        )

        /** Height of the caption band, as a share of the frame. */
        const val BAND = 0.17f

        /** The service whose colour and packet shape mark each of the eight slides. */
        val SLIDE_SERVICES = listOf(
            Service.STREAMING, Service.GAMING, Service.VIDEO_CALL, Service.CALL,
            Service.CAMERA_UPLOAD, Service.CLOUD_BACKUP, Service.MAIL, Service.STREAMING,
        )

        /** Captions of the eight screenshots in every language of the listing (docs/store/<language>.md). */
        val LANGUAGES = mapOf(
            "de" to listOf("Verkabel deine Stadt", "Ein Wisch, ein Kabel", "Verhindere die Überlastung", "Dreh die Karte, wie du willst", "WLAN, 4G und 5G", "Jede Woche eine Wahl", "Fünf Szenerien", "Achtung, Bagger!"),
            "en" to listOf("Wire up your town", "One swipe, one cable", "Keep the network from overloading", "Turn the map any way you like", "Wi-Fi, 4G and 5G", "A choice every week", "Five sceneries", "Mind the excavator!"),
            "fr" to listOf("Câble ta ville", "Un geste, un câble", "Évite la surcharge du réseau", "Tourne la carte à ta guise", "Wi-Fi, 4G et 5G", "Un choix chaque semaine", "Cinq décors", "Attention, pelleteuse !"),
            "es" to listOf("Conecta tu pueblo", "Un gesto, un cable", "Evita que la red se sature", "Gira el mapa a tu gusto", "Wi-Fi, 4G y 5G", "Una elección cada semana", "Cinco escenarios", "¡Cuidado con la excavadora!"),
            "it" to listOf("Cabla la tua città", "Un gesto, un cavo", "Evita il sovraccarico della rete", "Ruota la mappa come vuoi", "Wi-Fi, 4G e 5G", "Una scelta ogni settimana", "Cinque scenari", "Attenti alla ruspa!"),
            "pt-rBR" to listOf("Conecte sua cidade", "Um gesto, um cabo", "Evite a sobrecarga da rede", "Gire o mapa como quiser", "Wi-Fi, 4G e 5G", "Uma escolha por semana", "Cinco cenários", "Cuidado com a escavadeira!"),
            "pl" to listOf("Okabluj swoje miasto", "Jeden ruch, jeden kabel", "Nie dopuść do przeciążenia sieci", "Obracaj mapę, jak chcesz", "Wi-Fi, 4G i 5G", "Co tydzień wybór", "Pięć scenerii", "Uwaga, koparka!"),
            "nl" to listOf("Verbind je stad", "Eén veeg, één kabel", "Voorkom overbelasting van je netwerk", "Draai de kaart zoals je wilt", "Wifi, 4G en 5G", "Elke week een keuze", "Vijf landschappen", "Pas op voor de graafmachine!"),
            "tr" to listOf("Şehrini kabloyla bağla", "Bir kaydırma, bir kablo", "Ağın aşırı yüklenmesini önle", "Haritayı dilediğin gibi döndür", "Wi-Fi, 4G ve 5G", "Her hafta bir seçim", "Beş manzara", "Dikkat, kepçe!"),
            "ja" to listOf("町をケーブルでつなごう", "なぞるだけでケーブル", "ネットワークの過負荷を防ごう", "地図を自由に回転", "Wi-Fi、4G、5G", "毎週選べるボーナス", "5つのステージ", "ショベルカーに注意！"),
            "ko" to listOf("도시를 케이블로 연결하세요", "한 번 밀면 케이블 하나", "네트워크 과부하를 막으세요", "지도를 마음대로 회전", "Wi-Fi, 4G, 5G", "매주 고르는 보상", "다섯 가지 배경", "굴착기 주의!"),
            "zh-rCN" to listOf("为你的城镇铺设网络", "一划即是一条电缆", "别让网络过载", "随心旋转地图", "Wi-Fi、4G 和 5G", "每周一次选择", "五个场景", "小心挖掘机！"),
        )
    }
}
