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
 * One template for the whole set (judge panel): the game full bleed under a caption band in the brand's dusk blue
 * with a fiber-orange edge, the app icon before every short one-line caption.
 * Portrait phones get seven of them (all but the week reward, whose side-by-side cards need a wide screen).
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
        /** Which of the eight slides this device gets (upright phones: all but the reward, whose two cards need width). */
        val slides: List<Int> = (0 until 8).toList(),
    ) {
        PHONE("phone", 1920, 1080, "w731dp-h411dp-land-420dpi"),
        TABLET_7("tablet-7", 1920, 1080, "w1097dp-h617dp-land-280dpi", zoom = 0.88f),
        TABLET_10("tablet-10", 2560, 1440, "w1280dp-h720dp-land-xhdpi", zoom = 0.82f),
        /** Portrait is what the Play Store carousel shows first on phones. */
        PHONE_PORTRAIT("phone-portrait", 1080, 1920, "w411dp-h731dp-port-420dpi", slides = listOf(0, 1, 2, 3, 4, 6, 7)),
    }

    /** The device being rendered; its [StoreDevice.zoom] scales every shot. */
    private var device = StoreDevice.PHONE

    private val app get() = RuntimeEnvironment.getApplication()

    /** The game's display face (Nunito Black) for the headlines and the feature graphic's title. */
    private val display: Typeface by lazy { com.mininetworks.game.ui.Fonts.display(app) }
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
                    val card = cardRect(device, shot)
                    // A full-bleed shot runs under the caption: its focus moves down into the part below the band.
                    hiddenTop = if (shot.fullBleed) band(device) else 0f
                    val game = Bitmap.createBitmap(card.width().toInt(), card.height().toInt(), Bitmap.Config.ARGB_8888)
                    shot.draw(game)
                    hiddenTop = 0f
                    Cosmetic.reset()
                    val framed = frame(device, game, captions[i], shot, first = i == 0)
                    writeRgbPng(framed, File(dir, "%02d-%s.png".format(i + 1, shot.name)))
                    assertEquals(device.width, framed.width)
                    assertEquals(device.height, framed.height)
                }
            }
        }
    }

    /**
     * One of the eight motifs: a name for the file and how to draw it on the game surface. A [fullBleed] shot fills
     * the whole frame and its caption sits on a soft gradient over the map; the others (real HUD, dialog, collage)
     * start below the caption band. [tint] colours the caption's gradient and [accent] its underline, one pair per
     * motif, so the set reads as one family without every slide looking the same (judge panel).
     */
    private class Shot(val name: String, val tint: Int, val accent: Int, val fullBleed: Boolean = true, val draw: (Bitmap) -> Unit)

    /** Height of the caption band of [d]. */
    private fun band(d: StoreDevice) = if (d.height > d.width) d.height * 0.1f else d.height * BAND

    /** Height of the map hidden under the caption of a full-bleed shot while it is drawn; focus points move below it. */
    private var hiddenTop = 0f

    /** Where the game of [shot] sits in the frame of [d]: all of it, or from under the caption band to the bottom. */
    private fun cardRect(d: StoreDevice, shot: Shot): RectF =
        RectF(0f, if (shot.fullBleed) 0f else band(d), d.width.toFloat(), d.height.toFloat())

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
        val midY = (bmp.height + hiddenTop) / 2f
        if (focus != null) {
            val p = view.activeRenderer.toScreen(focus)
            cam.panBy(bmp.width / 2f - p.x, midY - p.y)
        }
        cam.zoomBy(zoom * device.zoom, bmp.width / 2f, midY)
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
        // 1. The hero: a busy late-game town, every cable technology, packets on every line, and one device starting
        //    to fill up (its ring runs), so the first picture already has a moment of tension.
        Shot("town", BRAND_DARK, 0xFFF28C28.toInt()) { bmp ->
            val world = lateTown()
            val nodes = world.nodes
            // A little right of the network's middle, so the cables at its east edge stay in the picture.
            val focus = Vec2(nodes.map { it.center.x }.average().toFloat() + 0.2f, nodes.map { it.center.y }.average().toFloat() - 0.7f)
            // A calm moment between rushes: packets on the lines, only a few devices waiting (judge panel: a bubble on
            // every device read as clutter).
            calm(world, keep = 3, near = focus)
            world.nodes.forEach { it.overload = 0f }
            // The one under pressure stands where no server hides its ring.
            val servers = world.nodes.filter { it.kind == NodeKind.SERVER }
            world.nodes.filter { it.kind == NodeKind.CLIENT && it.pending.isNotEmpty() }
                .filter { n -> servers.none { s -> s.cellX - n.cellX in -1..2 && s.cellY - n.cellY in -1..2 } }
                .minByOrNull { abs(it.center.x - focus.x) + abs(it.center.y - focus.y) }?.overload = 0.62f
            game(bmp, world, zoom = if (bmp.height > bmp.width) 2.4f else 1.18f, focus = focus)
        },
        // 2. Laying a cable, close up: fiber picked, the finger drags from the tablet to the game server; glow, touch
        //    trail and the price bubble fill the card (no empty board edge).
        Shot("drag", 0xFF10384A.toInt(), 0xFFFFC21A.toInt()) { bmp ->
            val world = Scenes.hud()
            val from = world.nodes.first { it.device == Device.TABLET }
            val to = world.nodes.first { it.service == Service.GAMING }
            // Between the drag and the board's middle (the drag runs near the board's edge), so no empty backdrop shows.
            val tall = bmp.height > bmp.width
            val mid = if (tall) Vec2((from.center.x + to.center.x) / 2f - 0.8f, (from.center.y + to.center.y) / 2f - 0.8f) else Vec2(
                (from.center.x + to.center.x) / 2f * 0.5f + world.cols / 2f * 0.5f,
                (from.center.y + to.center.y) / 2f * 0.5f + world.rows / 2f * 0.5f - 1f,
            )
            // Only the two ends of the drag ask for something: the stack of bubbles by the office read as clutter.
            world.nodes.filter { it.kind == NodeKind.CLIENT && it !== from }.forEach { it.pending.clear() }
            val v = view(hud = true)
            var tip = Vec2(0f, 0f)
            var start = Vec2(0f, 0f)
            game(bmp, world, zoom = if (tall) 1.9f else 2.6f, view = v, focus = mid) { gv ->
                gv.drawCurrent(Canvas(bmp))
                tap(gv, "cable:${CableType.FIBER.name}")
                gv.hudHidden = true
                val a = gv.activeRenderer.toScreen(from.center)
                val b = gv.activeRenderer.toScreen(to.center)
                start = a
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
            finger(bmp, tip.x, tip.y, from = start)
        },
        // 3. Incidents, full bleed and close up in the desert theme: an excavator cuts a cable, a router is dark.
        //    Conflict sells: it comes early in the set (judge panel).
        Shot("incidents", 0xFF3B2410.toInt(), 0xFFFFB347.toInt()) { bmp ->
            val world = Scenes.incidents()
            repeat(60 * 2) { world.update(1f / 60f) }
            val focus = world.incidents.first { it.struck && it.cable != null }.spot
            game(bmp, world, zoom = if (bmp.height > bmp.width) 2.4f else 1.7f, focus = focus, theme = ColorTheme.DESERT)
        },
        // 4. The tension: the real play screen with its HUD (date, packet count, cable bar), two devices whose queues
        //    are full and whose red overload rings are closing, closer in so the pressure shows.
        Shot("overload", 0xFF3E0E18.toInt(), 0xFFFF5A5F.toInt(), fullBleed = false) { bmp ->
            // A believable budget (judge panel: four digits looked like a debug value): the town was wired on a tight purse.
            val world = lateTown(grant = 150)
            // Half a minute more of normal play, so the packet counter shows a game well under way.
            repeat(60 * 30) {
                if (world.rewardOffer != null) world.chooseReward(0)
                world.update(1f / 60f)
                // A steady player: no ring ever fills during this stretch.
                world.nodes.forEach { it.overload = 0f }
            }
            check(!world.gameOver)
            val hot = overloaded(world)
            calm(world, keep = 1, near = hot.center, except = world.nodes.filter { it.overload > 0f }.toSet())
            game(bmp, world, zoom = if (bmp.height > bmp.width) 2.5f else 1.9f, view = view(hud = true), focus = hot.center)
            spotlight(bmp)
        },
        // 5. Wireless, close on the radios: coverage circles and the dashed radio links are the hero, no queues; the
        //    servers stay whole inside the picture.
        Shot("wireless", 0xFF0F2548.toInt(), 0xFF5CC8FF.toInt()) { bmp ->
            val world = storeWireless()
            world.nodes.forEach { it.pending.clear() }
            val radios = world.nodes.filter { it.kind == NodeKind.ACCESS_POINT || it.kind == NodeKind.CELL_TOWER }
            val all = world.nodes
            // On the radios and the servers behind them, the whole of every coverage circle in the picture.
            val tall = bmp.height > bmp.width
            // Upright, the board is too wide for all three zones at a readable size: the picture closes in on the
            // 5 GHz access point and the cell tower instead of leaving half the screen as empty outskirts.
            val focus = if (tall) radios.sortedBy { it.center.x }.drop(1).let { r ->
                Vec2(r.map { it.center.x }.average().toFloat() + 0.3f, r.map { it.center.y }.average().toFloat() - 0.8f)
            } else Vec2(
                (radios.map { it.center.x }.average().toFloat() + all.map { it.center.x }.average().toFloat()) / 2f + 0.6f,
                (radios.map { it.center.y }.average().toFloat() + all.map { it.center.y }.average().toFloat()) / 2f,
            )
            game(bmp, world, zoom = if (tall) 2.7f else 1.2f, focus = focus)
        },
        // 6. The week reward at its moment: confetti around the warm spotlight and the two cards.
        Shot("reward", 0xFF123A4C.toInt(), 0xFFFFC21A.toInt(), fullBleed = false) { bmp ->
            view().drawSnapshot(Canvas(bmp), FormFactorScreenshotTest.rewardWorld(), bmp.width, bmp.height, time = 1.3f, style = "Iso")
            // The confetti stays in the margins beside and above the heading and cards, never over a word or the caption.
            val layer = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            // A few bursts of the game's confetti (the moment a week ends), kept to the margins beside the cards.
            for (k in 0 until 4) com.mininetworks.game.ui.Confetti(uiDensity()).draw(Canvas(layer), bmp.width, bmp.height, 0.4f + 0.1f * k, 6 + k)
            val tall = bmp.height > bmp.width
            val clear = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }
            Canvas(layer).drawRect(
                bmp.width * (if (tall) 0.04f else 0.2f), if (tall) bmp.height * 0.16f else 0f,
                bmp.width * (if (tall) 0.96f else 0.8f), bmp.height.toFloat(), clear,
            )
            Canvas(bmp).drawBitmap(layer, 0f, 0f, null)
        },
        // 7. The turned map, full bleed: the city of 2030 late in a game at an odd angle, the skyline the hero; the
        //    two-finger turn is a small badge in the corner instead of arrows over the city.
        Shot("rotation", 0xFF2A1B52.toInt(), 0xFFB79CFF.toInt()) { bmp ->
            val world = lateFuture()
            val focus = Vec2(world.nodes.map { it.center.x }.average().toFloat(), world.nodes.map { it.center.y }.average().toFloat())
            calm(world, keep = 2, near = focus)
            game(bmp, world, zoom = if (bmp.height > bmp.width) 2.0f else 1.45f, focus = focus) { v ->
                val r = v.activeRenderer
                r.rotateBy(Camera.shortestTurn(r.camera.angle, 34f), r.camera.centerX, r.camera.centerY, world)
            }
            rotationHint(bmp)
        },
        // 8. Five sceneries, each in its own colours, as a collage of their maps, each named on a small pill.
        Shot("sceneries", 0xFF15392C.toInt(), 0xFF7BD389.toInt(), fullBleed = false) { bmp ->
            sceneryCollage(bmp)
        },
    )

    /** Pixels per UI dp of the current qualifiers, as the game computes them. */
    private fun uiDensity() = app.resources.displayMetrics.density * com.mininetworks.game.ui.TextScale.uiScale(app.resources.configuration.smallestScreenWidthDp)

    /**
     * Two devices near the middle of [w] with full queues and closing overload rings (the tension of the game); the
     * worse one is returned.
     */
    private fun overloaded(w: World): com.mininetworks.game.game.Node {
        val cx = w.nodes.map { it.center.x }.average().toFloat()
        val cy = w.nodes.map { it.center.y }.average().toFloat()
        // Devices no server stands in front of (nearer the viewer), so the red glow is not hidden behind a building.
        val servers = w.nodes.filter { it.kind == NodeKind.SERVER }
        val hot = w.nodes.filter { it.kind == NodeKind.CLIENT }
            .filter { n -> servers.none { s -> s.cellX - n.cellX in 0..2 && s.cellY - n.cellY in 0..2 } }
            .sortedBy { abs(it.center.x - cx) + abs(it.center.y - cy) }.take(2)
        hot.forEachIndexed { i, n ->
            val services = n.device!!.services
            n.pending.clear()
            repeat(World.Tuning.MAX_PENDING) { k -> n.pending.addLast(services[k % services.size]) }
            n.overload = if (i == 0) 0.88f else 0.5f
        }
        return hot.first()
    }

    /**
     * Clears the waiting requests of every client but the [keep] nearest to [near] (and those in [except]), which keep
     * at most two: the store pictures show a calm network with a few clear requests instead of a bubble on every device.
     */
    private fun calm(w: World, keep: Int, near: Vec2, except: Set<com.mininetworks.game.game.Node> = emptySet()) {
        val clients = w.nodes.filter { it.kind == NodeKind.CLIENT && it !in except }
            .sortedBy { abs(it.center.x - near.x) + abs(it.center.y - near.y) }
        clients.forEachIndexed { i, n ->
            if (i >= keep) n.pending.clear() else while (n.pending.size > 2) n.pending.removeLast()
        }
    }

    /**
     * Dims the picture towards its edges a little (a soft radial spotlight on the middle, where the overloaded device
     * sits), so the eye lands there first.
     */
    private fun spotlight(bmp: Bitmap) {
        val r = maxOf(bmp.width, bmp.height) * 0.62f
        Canvas(bmp).drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), Paint().apply {
            shader = RadialGradient(bmp.width / 2f, bmp.height / 2f, r, intArrayOf(0, 0, 0x4A0E2A38), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        })
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
     * A busy town late in a game: grown twice, every client wired to its nearest server with the cable a player would
     * pick for the distance (DSL near, coax further, fiber far), servers upgraded, and traffic running.
     */
    private fun lateTown(grant: Int = 2000): World {
        val w = World(seed = 4L)
        w.incidentsEnabled = false
        repeat(60 * 45 * 3) {
            if (w.rewardOffer != null) w.chooseReward(0)
            w.nodes.forEach { it.pending.clear() }
            w.update(1f / 60f)
        }
        // Some years later, so fiber is invented: the long runs get it.
        w.jumpToWeek(6)
        w.grant(grant)
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

    /**
     * A fingertip pressing at ([x], [y]): a soft shadow, a translucent pad and a ring, as screen-recording tools show
     * touches; with [from], a small ring where the swipe started.
     */
    private fun finger(bmp: Bitmap, x: Float, y: Float, from: Vec2? = null) {
        val c = Canvas(bmp)
        val d = uiDensity()
        val r = 26f * d
        from?.let {
            c.drawCircle(it.x, it.y, r * 0.55f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF })
            c.drawCircle(it.x, it.y, r * 0.55f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d; color = 0xFFF28C28.toInt() })
        }
        c.drawCircle(x + 3f * d, y + 5f * d, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000; maskFilter = BlurMaskFilter(8f * d, BlurMaskFilter.Blur.NORMAL) })
        c.drawCircle(x, y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt() })
        c.drawCircle(x, y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f * d; color = 0xFFF28C28.toInt() })
        c.drawCircle(x, y, r * 0.35f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF28C28.toInt() })
    }

    /**
     * The two-finger turn as a small round badge in the bottom right corner (judge panel: big arrows over the city hid
     * the skyline): two curved arrows around two fingertips on a dark disc.
     */
    private fun rotationHint(bmp: Bitmap) {
        val c = Canvas(bmp)
        val d = uiDensity()
        val rr = minOf(bmp.width, bmp.height) * 0.11f
        val cx = bmp.width - rr - 22f * d
        val cy = bmp.height - rr - 22f * d
        c.drawCircle(cx, cy + 4f * d, rr, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44000000; maskFilter = BlurMaskFilter(10f * d, BlurMaskFilter.Blur.NORMAL) })
        c.drawCircle(cx, cy, rr, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE62A1B52.toInt() })
        c.drawCircle(cx, cy, rr, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d; color = 0xFFB79CFF.toInt() })
        val r = rr * 0.66f
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xFFFFFFFF.toInt(); strokeWidth = rr * 0.1f }
        for ((start, sweep) in listOf(200f to 100f, 20f to 100f)) {
            c.drawArc(oval, start, sweep, false, arc)
            val end = Math.toRadians((start + sweep).toDouble())
            val ex = cx + r * kotlin.math.cos(end).toFloat()
            val ey = cy + r * kotlin.math.sin(end).toFloat()
            val ux = -kotlin.math.sin(end).toFloat()
            val uy = kotlin.math.cos(end).toFloat()
            val h = rr * 0.2f
            c.drawPath(Path().apply {
                moveTo(ex + ux * h, ey + uy * h)
                lineTo(ex - uy * h * 0.75f, ey + ux * h * 0.75f)
                lineTo(ex + uy * h * 0.75f, ey - ux * h * 0.75f)
                close()
            }, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        }
        // Two fingertips on the turn.
        for (a in listOf(200.0, 20.0)) {
            val x = cx + r * kotlin.math.cos(Math.toRadians(a)).toFloat()
            val y = cy + r * kotlin.math.sin(Math.toRadians(a)).toFloat()
            c.drawCircle(x, y, rr * 0.17f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF28C28.toInt() })
            c.drawCircle(x, y, rr * 0.17f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = rr * 0.05f; color = 0xFFFFFFFF.toInt() })
        }
    }

    /**
     * The five sceneries as a collage: the metropolis large on the left, the others in a 2 × 2 grid, each its own map
     * in full colour a little into the game, framed on what makes it special (the towers, the sea, the peaks), with
     * its name and era on a small pill at its bottom edge (judge panel: big label cards hid a third of each tile).
     */
    private fun sceneryCollage(bmp: Bitmap): List<RectF> {
        val c = Canvas(bmp)
        c.drawColor(0xFF15392C.toInt())
        val d = uiDensity()
        val gap = 8f * d
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val bigW = (w - 3 * gap) * 0.5f
        val smallW = (w - 3 * gap - bigW - gap) / 2f
        val smallH = (h - 3 * gap) / 2f
        val order = listOf(Scenarios.METROPOLIS, Scenarios.RIVER_TOWN, Scenarios.ISLAND, Scenarios.MOUNTAIN_VILLAGE, Scenarios.FUTURE)
        // Upright: the metropolis across the top, the other four in a 2 × 2 grid below.
        val tallH = (h - 4 * gap) * 0.4f
        val cellW = (w - 3 * gap) / 2f
        val cellH = (h - 4 * gap - tallH) / 2f
        val rects = if (h > w) listOf(
            RectF(gap, gap, w - gap, gap + tallH),
            RectF(gap, 2 * gap + tallH, gap + cellW, 2 * gap + tallH + cellH),
            RectF(2 * gap + cellW, 2 * gap + tallH, w - gap, 2 * gap + tallH + cellH),
            RectF(gap, 3 * gap + tallH + cellH, gap + cellW, h - gap),
            RectF(2 * gap + cellW, 3 * gap + tallH + cellH, w - gap, h - gap),
        ) else listOf(
            RectF(gap, gap, gap + bigW, h - gap),
            RectF(2 * gap + bigW, gap, 2 * gap + bigW + smallW, gap + smallH),
            RectF(3 * gap + bigW + smallW, gap, w - gap, gap + smallH),
            RectF(2 * gap + bigW, 2 * gap + smallH, 2 * gap + bigW + smallW, h - gap),
            RectF(3 * gap + bigW + smallW, 2 * gap + smallH, w - gap, h - gap),
        )
        val texts = com.mininetworks.game.ui.Texts(app)
        val name = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = com.mininetworks.game.ui.Fonts.display(app); color = 0xFF262B33.toInt() }
        val era = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; color = 0xFF2E7D46.toInt() }
        val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }
        for ((s, r) in order.zip(rects)) {
            val world = sceneryWorld(s)
            val tile = Bitmap.createBitmap(r.width().toInt(), r.height().toInt(), Bitmap.Config.ARGB_8888)
            val iso = IsoRenderer()
            iso.density = d
            iso.layout(tile.width, tile.height, world)
            signature(world, s)?.let { f ->
                val p = iso.toScreen(f)
                iso.camera.panBy(tile.width / 2f - p.x, tile.height * 0.45f - p.y)
            }
            iso.camera.zoomBy(if (s == Scenarios.METROPOLIS) 1.3f else 1.5f, tile.width / 2f, tile.height * 0.45f)
            iso.draw(Canvas(tile), world, drag = null, time = 1.3f)
            c.save()
            c.clipPath(Path().apply { addRoundRect(r, 14f * d, 14f * d, Path.Direction.CW) })
            c.drawBitmap(tile, r.left, r.top, null)
            c.restore()
            val title = texts.scenario(s)
            val sub = app.getString(R.string.scenery_from_year, s.startYear)
            // One line: the name, then the era in green; long names shrink to the tile.
            name.textSize = 15f * d
            era.textSize = 12f * d
            val pad = 12f * d
            val room = r.width() - 20f * d - 2 * pad
            val needed = { name.measureText(title) + 8f * d + era.measureText(sub) }
            if (needed() > room) { val k = room / needed(); name.textSize *= k; era.textSize *= k }
            val ph = name.textSize * 1.9f
            val pw = needed() + 2 * pad
            val pr = RectF(r.left + 10f * d, r.bottom - 10f * d - ph, r.left + 10f * d + pw, r.bottom - 10f * d)
            c.drawRoundRect(RectF(pr).apply { offset(0f, 2f * d) }, ph / 2f, ph / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 })
            c.drawRoundRect(pr, ph / 2f, ph / 2f, plate)
            val base = pr.centerY() + name.textSize * 0.36f
            c.drawText(title, pr.left + pad, base, name)
            c.drawText(sub, pr.left + pad + name.measureText(title) + 8f * d, base, era)
        }
        return rects
    }

    /**
     * Where a tile of scenery [s] looks: between the network and the scenery's own feature (the towers of a city, the
     * sea of the island, the peaks of the mountains), within the unlocked area; null keeps the game's own framing.
     */
    private fun signature(w: World, s: com.mininetworks.game.game.Scenario): Vec2? {
        val terrain = when (s) {
            Scenarios.METROPOLIS, Scenarios.FUTURE -> com.mininetworks.game.game.Terrain.HIGH_RISE
            Scenarios.ISLAND, Scenarios.RIVER_TOWN -> com.mininetworks.game.game.Terrain.WATER
            Scenarios.MOUNTAIN_VILLAGE -> com.mininetworks.game.game.Terrain.MOUNTAIN
            else -> return null
        }
        val cells = (0 until w.rows).flatMap { y -> (0 until w.cols).map { x -> com.mininetworks.game.game.Cell(x, y) } }
            .filter { it in w.unlocked && w.terrainAt(it.x, it.y) == terrain }
        if (cells.isEmpty()) return null
        val fx = cells.map { it.x + 0.5f }.average().toFloat()
        val fy = cells.map { it.y + 0.5f }.average().toFloat()
        val nx = w.nodes.map { it.center.x }.average().toFloat()
        val ny = w.nodes.map { it.center.y }.average().toFloat()
        return Vec2((fx + nx) / 2f, (fy + ny) / 2f)
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
        calm(w, keep = 2, near = Vec2(w.cols / 2f, w.rows / 2f))
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
     * [game] in the frame of [d] under [caption]. A full-bleed shot runs under the caption, which sits on a soft
     * gradient in the shot's [Shot.tint] that fades into the map; the others hang below a slim band in that tint. The
     * headline is set in the game's display face (Nunito Black) with a short underline in the shot's [Shot.accent];
     * only the [first] slide carries the app icon (judge panel: a repeated icon and one dark slab read as a template).
     */
    private fun frame(d: StoreDevice, game: Bitmap, caption: String, shot: Shot, first: Boolean): Bitmap {
        val out = Bitmap.createBitmap(d.width, d.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val w = d.width.toFloat()
        val h = d.height.toFloat()
        val band = band(d)
        val tint = shot.tint and 0xFFFFFF
        c.drawColor(shot.tint)
        c.drawBitmap(game, 0f, if (shot.fullBleed) 0f else band, vivid)
        if (shot.fullBleed) {
            val reach = band * 1.5f
            c.drawRect(0f, 0f, w, reach, Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, reach, intArrayOf(0xF2000000.toInt() or tint, 0xC8000000.toInt() or tint, 0x55000000 or tint, tint),
                    floatArrayOf(0f, 0.5f, 0.78f, 1f), Shader.TileMode.CLAMP,
                )
            })
        } else {
            c.drawRect(0f, 0f, w, band, Paint().apply { color = shot.tint })
            c.drawRect(0f, band, w, band * 1.2f, Paint().apply {
                shader = LinearGradient(0f, band, 0f, band * 1.2f, 0x55000000, 0, Shader.TileMode.CLAMP)
            })
        }
        // A faint light from the top left over the tint.
        c.drawRect(0f, 0f, w, band, Paint().apply {
            shader = RadialGradient(w * 0.2f, -band * 0.6f, w * 0.6f, 0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        })

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = display; textAlign = Paint.Align.LEFT }
        text.setShadowLayer(band * 0.04f, 0f, band * 0.02f, 0x80000000.toInt())
        val tall = d.height > d.width
        text.textSize = band * (if (tall) 0.42f else 0.46f)
        val markSize = if (first) band * 0.58f else 0f
        val markRoom = if (first) markSize * 1.3f else 0f
        val maxW = w * 0.9f - markRoom
        while (text.measureText(caption) > maxW && text.textSize > band * 0.3f) text.textSize *= 0.96f
        val lines = TextWrap.wrap(caption, maxW) { text.measureText(it) }
        assertTrue("caption \"$caption\" fits ${d.id}: $lines", lines.size <= 2 && lines.all { text.measureText(it) <= maxW })
        if (lines.size == 2) text.textSize = minOf(text.textSize, band * 0.34f)
        val lineH = text.textSize * 1.08f
        val textW = lines.maxOf { text.measureText(it) }
        val total = markRoom + textW
        val x0 = (w - total) / 2f
        val cy = band * 0.46f
        if (first) LogoMark(app).draw(c, x0 + markSize / 2f, cy, markSize)
        val firstBaseline = cy - (lines.size - 1) * lineH / 2f + text.textSize * 0.36f
        lines.forEachIndexed { i, l -> c.drawText(l, x0 + markRoom, firstBaseline + i * lineH, text) }
        // The accent underline: a short rounded bar under the headline's middle.
        val barW = minOf(textW * 0.3f, w * 0.12f)
        val barH = maxOf(4f, band * 0.045f)
        val barY = firstBaseline + (lines.size - 1) * lineH + text.textSize * 0.26f
        val barX = x0 + markRoom + textW / 2f
        c.drawRoundRect(barX - barW / 2f, barY, barX + barW / 2f, barY + barH, barH / 2f, barH / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = shot.accent })
        return out
    }

    /**
     * The feature graphic (1024 × 500, no alpha): the era of the game across the full width (judge panel: a flat slab
     * and a calm crop had no hook). A busy 1995-style town on meadow (copper ISDN and DSL, one device in overload)
     * blends on a diagonal into the dense city of 2030 (fiber, the skyline, radio coverage), both real maps late in a
     * game; small year tags name the two ends. The title lockup (icon, the name in Nunito Black, the tagline) sits on
     * a soft dusk gradient at the left. Nothing important lies in the outer 10 % (Play may crop it or lay buttons over it).
     */
    @Test
    @Config(qualifiers = "en-w731dp-h411dp-land-420dpi")
    fun featureGraphic() {
        val w = 1024
        val h = 500
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)

        // The past: the town, the drama of one device whose ring is closing next to its busiest server.
        val town = lateTown()
        val hero = town.nodes.filter { it.kind == NodeKind.SERVER }.maxBy { s -> town.cables.count { it.a === s || it.b === s } }
        town.nodes.filter { it.kind == NodeKind.CLIENT }.minBy { abs(it.center.x - hero.center.x) + abs(it.center.y - hero.center.y) }.let { n ->
            n.pending.clear()
            repeat(World.Tuning.MAX_PENDING) { k -> n.pending.addLast(n.device!!.services[k % n.device!!.services.size]) }
            n.overload = 0.8f
        }
        calm(town, keep = 2, near = hero.center, except = town.nodes.filter { it.overload > 0f }.toSet())
        val past = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        IsoRenderer().apply {
            density = 1.6f
            layout(w, h, town)
            val hx = w * 0.58f
            val hy = h * 0.52f
            toScreen(hero.center).let { p -> camera.panBy(hx - p.x, hy - p.y) }
            camera.zoomBy(1.7f, hx, hy)
            draw(Canvas(past), town, drag = null, time = 1.3f)
        }
        // The future: the city of 2030, framed on its towers.
        val city = lateFuture()
        calm(city, keep = 2, near = Vec2(city.cols / 2f, city.rows / 2f))
        val future = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        IsoRenderer().apply {
            density = 1.6f
            layout(w, h, city)
            val focus = signature(city, Scenarios.FUTURE) ?: Vec2(city.cols / 2f, city.rows / 2f)
            val fx = w * 0.83f
            val fy = h * 0.5f
            toScreen(focus).let { p -> camera.panBy(fx - p.x, fy - p.y) }
            camera.zoomBy(1.6f, fx, fy)
            draw(Canvas(future), city, drag = null, time = 1.3f)
        }
        c.drawBitmap(past, 0f, 0f, vivid)
        // The city takes over right of a diagonal seam with a short feather, lined in glowing fiber.
        val top = w * 0.7f
        val bottom = w * 0.6f
        val layer = future.copy(Bitmap.Config.ARGB_8888, true)
        val seam = Path().apply { moveTo(top, 0f); lineTo(w.toFloat(), 0f); lineTo(w.toFloat(), h.toFloat()); lineTo(bottom, h.toFloat()); close() }
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawPath(seam, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL) })
        Canvas(layer).drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) })
        c.drawBitmap(layer, 0f, 0f, vivid)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
        edge.color = 0x40F28C28; edge.strokeWidth = 18f; c.drawLine(top, -10f, bottom, h + 10f, edge)
        edge.color = 0xCCF28C28.toInt(); edge.strokeWidth = 5f; c.drawLine(top, -10f, bottom, h + 10f, edge)
        edge.color = 0xFFFFE2B8.toInt(); edge.strokeWidth = 1.5f; c.drawLine(top, -10f, bottom, h + 10f, edge)
        // The title's ground: a dusk gradient from the left edge that fades into the town.
        c.drawRect(0f, 0f, w * 0.52f, h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w * 0.52f, 0f, intArrayOf(0xF20E2A38.toInt(), 0xD90E2A38.toInt(), 0x590E2A38, 0x000E2A38), floatArrayOf(0f, 0.5f, 0.78f, 1f), Shader.TileMode.CLAMP)
        })
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = RadialGradient(w * 0.12f, -h * 0.2f, w * 0.5f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        })
        // Year tags at the two ends of the era.
        val tagP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = display; textSize = 26f; color = 0xFFFFFFFF.toInt() }
        fun yearTag(text: String, cx: Float, y: Float, color: Int) {
            val tw = tagP.measureText(text) + 28f
            val r = RectF(cx - tw / 2f, y, cx + tw / 2f, y + 40f)
            c.drawRoundRect(RectF(r).apply { offset(0f, 3f) }, 20f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0x40000000 })
            c.drawRoundRect(r, 20f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            c.drawText(text, cx - tagP.measureText(text) / 2f, r.centerY() + tagP.textSize * 0.36f, tagP)
        }
        yearTag("1995", w * 0.52f, h * 0.82f, 0xFF2E7D46.toInt())
        yearTag("2030", w * 0.8f, h * 0.82f, 0xFF5B3FB0.toInt())

        val name = app.getString(R.string.app_name)
        val left = w * 0.1f
        val room = w * 0.44f - left
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = display; textSize = 78f; setShadowLayer(8f, 0f, 3f, 0x80000000.toInt()) }
        while (title.measureText(name) > room) title.textSize -= 1f
        val icon = 84f
        fun write(tagline: String, file: File) {
            val shot = out.copy(Bitmap.Config.ARGB_8888, true)
            val sc = Canvas(shot)
            val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC46B.toInt(); typeface = display; textSize = 32f; setShadowLayer(5f, 0f, 2f, 0x80000000.toInt()) }
            var lines = TextWrap.wrap(tagline, room) { tag.measureText(it) }
            while ((lines.size > 2 || lines.any { tag.measureText(it) > room }) && tag.textSize > 18f) {
                tag.textSize -= 1f
                lines = TextWrap.wrap(tagline, room) { tag.measureText(it) }
            }
            assertTrue("tagline in at most two lines inside the panel: $lines", lines.size <= 2 && lines.all { tag.measureText(it) <= room })
            val blockH = icon + 18f + title.textSize + 16f + lines.size * tag.textSize * 1.2f
            var y = (h - blockH) / 2f
            LogoMark(app).draw(sc, left + icon / 2f, y + icon / 2f, icon)
            y += icon + 18f + title.textSize * 0.8f
            sc.drawText(name, left, y, title)
            // A short fiber-orange bar between the name and the tagline.
            sc.drawRoundRect(left, y + 12f, left + 64f, y + 18f, 3f, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF28C28.toInt() })
            y += 16f + 12f + tag.textSize * 0.95f
            for (l in lines) { sc.drawText(l, left, y, tag); y += tag.textSize * 1.2f }
            assertTrue("text inside the safe area", y <= h * 0.95f && left + room <= w * 0.9f)
            for (x in 0 until w step 64) for (yy in 0 until h step 50) assertEquals("opaque at $x,$yy", 0xFF, shot.getPixel(x, yy) ushr 24)
            assertEquals(w, shot.width)
            assertEquals(h, shot.height)
            writeRgbPng(shot, file)
        }
        // The default graphic carries only the era, in numbers every language reads; each language that has its
        // listing's graphics rendered also gets one with its own tagline (Play takes a feature graphic per language).
        write("1995 → 2030", File(storeDir, "feature-graphic.png"))
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
            "en" to "Build the internet, one cable at a time",
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

        /** Height of the caption band, as a share of the frame (judge panel: 17 % cost too much of every shot). */
        const val BAND = 0.14f

        /** The brand's dusk blue (launcher icon) of the caption band, dark to light. */
        const val BRAND_DARK = 0xFF0E2A38.toInt()
        const val BRAND_MID = 0xFF1B4A5E.toInt()

        /** Captions of the eight screenshots in every language of the listing (docs/store/<language>.md). */
        val LANGUAGES = mapOf(
            "de" to listOf("Verkabel deine Stadt", "Ein Wisch, ein Kabel", "Achtung, Bagger!", "Stopp die Überlastung", "WLAN, 4G und 5G", "Jede Woche eine Wahl", "Dreh die Karte", "Fünf Szenerien"),
            "en" to listOf("Wire up your town", "One swipe, one cable", "Mind the excavator!", "Don't let it overload", "Wi-Fi, 4G and 5G", "A choice every week", "Spin the map", "Five sceneries"),
            "fr" to listOf("Câble ta ville", "Un geste, un câble", "Attention, pelleteuse !", "Évite la surcharge", "Wi-Fi, 4G et 5G", "Un choix chaque semaine", "Tourne la carte", "Cinq décors"),
            "es" to listOf("Conecta tu pueblo", "Un gesto, un cable", "¡Cuidado con la excavadora!", "Evita la saturación", "Wi-Fi, 4G y 5G", "Una elección cada semana", "Gira el mapa", "Cinco escenarios"),
            "it" to listOf("Cabla la tua città", "Un gesto, un cavo", "Attenti alla ruspa!", "Evita il sovraccarico", "Wi-Fi, 4G e 5G", "Una scelta ogni settimana", "Ruota la mappa", "Cinque scenari"),
            "pt-rBR" to listOf("Conecte sua cidade", "Um gesto, um cabo", "Cuidado com a escavadeira!", "Evite a sobrecarga", "Wi-Fi, 4G e 5G", "Uma escolha por semana", "Gire o mapa", "Cinco cenários"),
            "pl" to listOf("Okabluj swoje miasto", "Jeden ruch, jeden kabel", "Uwaga, koparka!", "Unikaj przeciążenia", "Wi-Fi, 4G i 5G", "Co tydzień wybór", "Obracaj mapę", "Pięć scenerii"),
            "nl" to listOf("Verbind je stad", "Eén veeg, één kabel", "Pas op voor de graafmachine!", "Voorkom overbelasting", "Wifi, 4G en 5G", "Elke week een keuze", "Draai de kaart", "Vijf landschappen"),
            "tr" to listOf("Şehrini kabloyla bağla", "Bir kaydırma, bir kablo", "Dikkat, kepçe!", "Aşırı yükü önle", "Wi-Fi, 4G ve 5G", "Her hafta bir seçim", "Haritayı döndür", "Beş manzara"),
            "ja" to listOf("町をケーブルでつなごう", "なぞるだけでケーブル", "ショベルカーに注意！", "過負荷を防ごう", "Wi-Fi、4G、5G", "毎週選べるボーナス", "地図を回転", "5つのステージ"),
            "ko" to listOf("도시를 케이블로 연결하세요", "한 번 밀면 케이블 하나", "굴착기 주의!", "과부하를 막으세요", "Wi-Fi, 4G, 5G", "매주 고르는 보상", "지도를 회전", "다섯 가지 배경"),
            "zh-rCN" to listOf("为你的城镇铺设网络", "一划即是一条电缆", "小心挖掘机！", "别让网络过载", "Wi-Fi、4G 和 5G", "每周一次选择", "旋转地图", "五个场景"),
        )
    }
}
