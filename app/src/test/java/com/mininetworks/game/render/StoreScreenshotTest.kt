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
 * floating in the middle. Marketing shots hide the HUD ([GameView.hudHidden]) except the wireless one, which shows the
 * controls. Each slide has its own accent colour (one of the service colours), a packet in that colour before the
 * caption and a faint cable pattern in the backdrop; the first one carries the logo mark as the hero frame.
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
    enum class StoreDevice(val id: String, val width: Int, val height: Int, val qualifiers: String) {
        PHONE("phone", 1920, 1080, "w731dp-h411dp-land-420dpi"),
        TABLET_7("tablet-7", 1920, 1080, "w1097dp-h617dp-land-280dpi"),
        TABLET_10("tablet-10", 2560, 1440, "w1280dp-h720dp-land-xhdpi"),
    }

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
                val dir = File(storeDir, "screenshots/${device.id}/$lang").apply { mkdirs() }
                val shots = shots()
                assertEquals(8, shots.size)
                assertEquals(8, captions.size)
                for ((i, shot) in shots.withIndex()) {
                    Cosmetic.reset()
                    val card = cardRect(device)
                    val game = Bitmap.createBitmap(card.width().toInt(), card.height().toInt(), Bitmap.Config.ARGB_8888)
                    shot.draw(game)
                    Cosmetic.reset()
                    val framed = frame(device, game, captions[i], i)
                    writeRgbPng(framed, File(dir, "%02d-%s.png".format(i + 1, shot.name)))
                    assertEquals(device.width, framed.width)
                    assertEquals(device.height, framed.height)
                }
            }
        }
    }

    /** One of the eight motifs: a name for the file and how to draw it on the game surface. */
    private class Shot(val name: String, val draw: (Bitmap) -> Unit)

    /** Where the game card sits in the frame of [d]: 93 % of the width, from under the caption band to near the bottom. */
    private fun cardRect(d: StoreDevice): RectF {
        val w = d.width.toFloat()
        val h = d.height.toFloat()
        val side = w * 0.035f
        return RectF(side, h * BAND, w - side, h - h * 0.04f)
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
        cam.zoomBy(zoom, bmp.width / 2f, bmp.height / 2f)
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
            val focus = Vec2(nodes.map { it.center.x }.average().toFloat(), nodes.map { it.center.y }.average().toFloat())
            game(bmp, world, zoom = 1.45f, focus = focus)
        },
        // 2. Laying a cable, close up: fiber picked, the finger drags from the tablet to the game server; glow, touch
        //    trail and the price bubble.
        Shot("drag") { bmp ->
            val world = Scenes.hud()
            val from = world.nodes.first { it.device == Device.TABLET }
            val to = world.nodes.first { it.service == Service.GAMING }
            // Slightly above the middle of the drag, so the board fills the lower right of the card too.
            val mid = Vec2((from.center.x + to.center.x) / 2f - 1f, (from.center.y + to.center.y) / 2f - 1.5f)
            val v = view(hud = true)
            game(bmp, world, zoom = 1.75f, view = v, focus = mid) { gv ->
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
                    gv.injectTouch(MotionEvent.ACTION_MOVE, a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t - bow)
                }
            }
        },
        // 3. Wireless with the controls: Wi-Fi channels, 5 GHz, a cell tower, traffic on every link.
        Shot("wireless") { bmp -> game(bmp, busyWireless(), zoom = 1.3f, view = view(hud = true)) },
        // 4. The turned map, in the winter theme: the metropolis at an odd angle, with a turn gesture drawn over it.
        Shot("rotation") { bmp ->
            val world = wiredStart()
            game(bmp, world, zoom = 1.3f, theme = ColorTheme.WINTER) { v ->
                val r = v.activeRenderer
                r.rotateBy(Camera.shortestTurn(r.camera.angle, 34f), r.camera.centerX, r.camera.centerY, world)
            }
            rotationHint(bmp)
        },
        // 5. The daily challenge: today's map with the streak flame, in the autumn theme.
        Shot("daily") { bmp ->
            val v = view()
            v.drawSnapshot(Canvas(bmp), v.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = Screen.DAILY)
            Cosmetic.theme = ColorTheme.AUTUMN
            bmp.eraseColor(0)
            v.drawCurrent(Canvas(bmp))
        },
        // 6. The week reward: two cards and the weekly pay (no ad button in store art).
        Shot("reward") { bmp ->
            view(hud = true).drawSnapshot(Canvas(bmp), FormFactorScreenshotTest.rewardWorld(), bmp.width, bmp.height, time = 1.3f, style = "Iso")
        },
        // 7. Five sceneries, each in colour, as a collage of their maps.
        Shot("sceneries") { bmp -> sceneryCollage(bmp) },
        // 8. Incidents, close up and in the desert theme: an excavator cuts a cable, a router is dark.
        Shot("incidents") { bmp ->
            val world = Scenes.incidents()
            repeat(60 * 2) { world.update(1f / 60f) }
            val focus = world.incidents.first { it.struck && it.cable != null }.spot
            game(bmp, world, zoom = 1.6f, focus = focus, theme = ColorTheme.DESERT)
        },
    )

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

    /** The wireless scene a few seconds on, so packets fly over cables and radio links. */
    private fun busyWireless(): World = Scenes.wireless().also { w -> repeat(60 * 4) { w.update(1f / 60f) }; rushHour(w, seconds = 1.6f, rounds = 1) }

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
    private fun sceneryCollage(bmp: Bitmap) {
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
            val pw = maxOf(name.measureText(title), era.measureText(sub)) + 24f * d
            val ph = name.textSize + era.textSize + 22f * d
            val pr = RectF(r.left + 10f * d, r.bottom - 10f * d - ph, r.left + 10f * d + pw, r.bottom - 10f * d)
            c.drawRoundRect(pr, 12f * d, 12f * d, plate)
            c.drawText(title, pr.left + 12f * d, pr.top + 8f * d + name.textSize * 0.9f, name)
            c.drawText(sub, pr.left + 12f * d, pr.bottom - 9f * d, era)
        }
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

    /** [game] on its card in the frame of [d], under [caption]; slide [index] picks the accent colour. */
    private fun frame(d: StoreDevice, game: Bitmap, caption: String, index: Int): Bitmap {
        val out = Bitmap.createBitmap(d.width, d.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val w = d.width.toFloat()
        val h = d.height.toFloat()
        val service = SLIDE_SERVICES[index]
        val accent = ServiceColors.defaultOf(service)
        background(c, w, h, accent, index)
        val dst = cardRect(d)
        val radius = w * 0.016f
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x73000000; maskFilter = BlurMaskFilter(w * 0.012f, BlurMaskFilter.Blur.NORMAL) }
        c.drawRoundRect(RectF(dst.left, dst.top + w * 0.006f, dst.right, dst.bottom + w * 0.006f), radius, radius, shadow)
        c.save()
        c.clipPath(Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) })
        c.drawBitmap(game, dst.left, dst.top, Paint(Paint.FILTER_BITMAP_FLAG))
        c.restore()
        // A thin rim in the slide's accent ties the card to the backdrop.
        c.drawRoundRect(dst, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.0022f; color = accent and 0xFFFFFF or 0xCC000000.toInt() })

        // Caption: a packet in the accent colour, then the headline in a heavy weight, centered in the band above.
        val band = dst.top
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = display; textAlign = Paint.Align.LEFT }
        text.textSize = band * 0.5f
        val hero = index == 0
        val markSize = band * (if (hero) 0.7f else 0.34f)
        val maxW = w * 0.86f - markSize * 1.4f
        while (text.measureText(caption) > maxW && text.textSize > band * 0.3f) text.textSize *= 0.96f
        val lines = TextWrap.wrap(caption, maxW) { text.measureText(it) }
        assertTrue("caption \"$caption\" fits ${d.id}: $lines", lines.size <= 2 && lines.all { text.measureText(it) <= maxW })
        if (lines.size == 2) text.textSize = minOf(text.textSize, band * 0.36f)
        val lineH = text.textSize * 1.08f
        val textW = lines.maxOf { text.measureText(it) }
        val total = markSize * 1.4f + textW
        val x0 = (w - total) / 2f
        val cy = band * 0.5f
        if (hero) {
            LogoMark(app).draw(c, x0 + markSize / 2f, cy, markSize)
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
            Shapes.draw(c, service.shape, x0 + markSize / 2f, cy, markSize * 0.62f, p)
            p.color = accent
            Shapes.draw(c, service.shape, x0 + markSize / 2f, cy, markSize * 0.5f, p)
        }
        val firstBaseline = cy - (lines.size - 1) * lineH / 2f + text.textSize * 0.36f
        lines.forEachIndexed { i, l -> c.drawText(l, x0 + markSize * 1.4f, firstBaseline + i * lineH, text) }
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
     * The feature graphic (1024 × 500, no alpha): the busy late-game town across the whole graphic, a clean diagonal
     * split to a dusk-blue panel edged by a glowing fiber cable with packets, and on it the logo mark, the name in a
     * heavy weight and the tagline. Nothing important lies in the outer 10 % (Play may crop it or lay buttons over it).
     */
    @Test
    @Config(qualifiers = "en-w731dp-h411dp-land-420dpi")
    fun featureGraphic() {
        val w = 1024
        val h = 500
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFF1B3848.toInt())
        // The town, drawn by the isometric renderer alone (no HUD), across the whole graphic, centered left of the panel.
        val world = lateTown()
        val town = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val r = IsoRenderer()
        r.density = 1.6f
        r.layout(740, h, world)
        val focus = Vec2(world.nodes.map { it.center.x }.average().toFloat(), world.nodes.map { it.center.y }.average().toFloat())
        r.toScreen(focus).let { p -> r.camera.panBy(370f - p.x, h / 2f - p.y) }
        r.camera.zoomBy(1.45f, 370f, h / 2f)
        r.draw(Canvas(town), world, drag = null, time = 1.3f)
        c.drawBitmap(town, 0f, 0f, null)
        // The panel: a diagonal edge from (600, 0) to (520, 500).
        val panel = Path().apply { moveTo(600f, 0f); lineTo(w.toFloat(), 0f); lineTo(w.toFloat(), h.toFloat()); lineTo(520f, h.toFloat()); close() }
        val pp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(560f, 0f, w.toFloat(), h.toFloat(), 0xF2204A5E.toInt(), 0xFF112634.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawPath(panel, pp)
        // The fiber cable along the edge: glow, line, core, and packets riding it.
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        edge.color = 0x55F28C28; edge.strokeWidth = 22f; c.drawLine(600f, -10f, 520f, h + 10f, edge)
        edge.color = 0xFFF28C28.toInt(); edge.strokeWidth = 8f; c.drawLine(600f, -10f, 520f, h + 10f, edge)
        edge.color = 0xFFFFE2B8.toInt(); edge.strokeWidth = 2.5f; c.drawLine(600f, -10f, 520f, h + 10f, edge)
        val services = listOf(Service.MAIL, Service.GAMING, Service.CALL, Service.STREAMING, Service.VIDEO_CALL)
        services.forEachIndexed { i, s ->
            val t = 0.12f + i * 0.19f
            val x = 600f - 80f * t
            val y = h * t
            val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
            Shapes.draw(c, s.shape, x, y, 13f, dp)
            dp.color = ServiceColors.defaultOf(s)
            Shapes.draw(c, s.shape, x, y, 9.5f, dp)
        }
        // Logo mark, name and tagline, centered in the panel, inside the safe area.
        val cx = 790f
        LogoMark(app).draw(c, cx, 150f, 130f)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = display; textAlign = Paint.Align.CENTER; textSize = 62f }
        val name = app.getString(R.string.app_name)
        while (title.measureText(name) / 2f > minOf(cx - 560f, w * 0.9f - cx) - 4f) title.textSize -= 1f
        c.drawText(name, cx, 290f, title)
        val half = title.measureText(name) / 2f
        assertTrue("name inside the safe area", cx - half >= w * 0.1f && cx + half <= w * 0.9f)
        val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD9A8.toInt(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; textSize = 28f }
        val maxTag = minOf(cx - 580f, w * 0.9f - cx) * 2f - 8f
        var tagLines = TextWrap.wrap(app.getString(R.string.menu_tagline), maxTag) { tag.measureText(it) }
        while (tagLines.size > 2 && tag.textSize > 18f) {
            tag.textSize -= 1f
            tagLines = TextWrap.wrap(app.getString(R.string.menu_tagline), maxTag) { tag.measureText(it) }
        }
        assertTrue("tagline fits in two lines: $tagLines", tagLines.size <= 2)
        tagLines.forEachIndexed { i, l -> c.drawText(l, cx, 340f + i * tag.textSize * 1.2f, tag) }
        for (x in 0 until w step 64) for (yy in 0 until h step 50) assertEquals("opaque at $x,$yy", 0xFF, out.getPixel(x, yy) ushr 24)
        writeRgbPng(out, File(storeDir, "feature-graphic.png"))
        assertEquals(w, out.width)
        assertEquals(h, out.height)
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
        /** Height of the caption band, as a share of the frame. */
        const val BAND = 0.17f

        /** The service whose colour and packet shape mark each of the eight slides. */
        val SLIDE_SERVICES = listOf(
            Service.STREAMING, Service.GAMING, Service.VIDEO_CALL, Service.CALL,
            Service.CAMERA_UPLOAD, Service.CLOUD_BACKUP, Service.MAIL, Service.STREAMING,
        )

        /** Captions of the eight screenshots in every language of the listing (docs/store/<language>.md). */
        val LANGUAGES = mapOf(
            "de" to listOf("Verkabel deine Stadt", "Ein Wisch, ein Kabel", "WLAN, 4G und 5G", "Dreh die Karte, wie du willst", "Jeden Tag eine neue Aufgabe", "Jede Woche eine Wahl", "Fünf Szenerien", "Achtung, Bagger!"),
            "en" to listOf("Wire up your town", "One swipe, one cable", "Wi-Fi, 4G and 5G", "Turn the map any way you like", "A new challenge every day", "A choice every week", "Five sceneries", "Mind the excavator!"),
            "fr" to listOf("Câble ta ville", "Un geste, un câble", "Wi-Fi, 4G et 5G", "Tourne la carte à ta guise", "Un nouveau défi chaque jour", "Un choix chaque semaine", "Cinq décors", "Attention, pelleteuse !"),
            "es" to listOf("Conecta tu pueblo", "Un gesto, un cable", "Wi-Fi, 4G y 5G", "Gira el mapa a tu gusto", "Un reto nuevo cada día", "Una elección cada semana", "Cinco escenarios", "¡Cuidado con la excavadora!"),
            "it" to listOf("Cabla la tua città", "Un gesto, un cavo", "Wi-Fi, 4G e 5G", "Ruota la mappa come vuoi", "Una nuova sfida ogni giorno", "Una scelta ogni settimana", "Cinque scenari", "Attenti alla ruspa!"),
            "pt-rBR" to listOf("Conecte sua cidade", "Um gesto, um cabo", "Wi-Fi, 4G e 5G", "Gire o mapa como quiser", "Um desafio novo por dia", "Uma escolha por semana", "Cinco cenários", "Cuidado com a escavadeira!"),
            "pl" to listOf("Okabluj swoje miasto", "Jeden ruch, jeden kabel", "Wi-Fi, 4G i 5G", "Obracaj mapę, jak chcesz", "Codziennie nowe wyzwanie", "Co tydzień wybór", "Pięć scenerii", "Uwaga, koparka!"),
            "nl" to listOf("Verbind je stad", "Eén veeg, één kabel", "Wifi, 4G en 5G", "Draai de kaart zoals je wilt", "Elke dag een nieuwe opdracht", "Elke week een keuze", "Vijf landschappen", "Pas op voor de graafmachine!"),
            "tr" to listOf("Şehrini kabloyla bağla", "Bir kaydırma, bir kablo", "Wi-Fi, 4G ve 5G", "Haritayı dilediğin gibi döndür", "Her gün yeni bir görev", "Her hafta bir seçim", "Beş manzara", "Dikkat, kepçe!"),
            "ja" to listOf("町をケーブルでつなごう", "なぞるだけでケーブル", "Wi-Fi、4G、5G", "地図を自由に回転", "毎日新しいチャレンジ", "毎週選べるボーナス", "5つのステージ", "ショベルカーに注意！"),
            "ko" to listOf("도시를 케이블로 연결하세요", "한 번 밀면 케이블 하나", "Wi-Fi, 4G, 5G", "지도를 마음대로 회전", "매일 새로운 도전", "매주 고르는 보상", "다섯 가지 배경", "굴착기 주의!"),
            "zh-rCN" to listOf("为你的城镇铺设网络", "一划即是一条电缆", "Wi-Fi、4G 和 5G", "随心旋转地图", "每天一个新挑战", "每周一次选择", "五个场景", "小心挖掘机！"),
        )
    }
}
