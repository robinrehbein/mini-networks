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
 * which sell the gesture and the tension with the real play screen.
 *
 * One template for the whole set (judge panel): the game full bleed under a caption bar in the brand's dusk blue
 * with a thin fiber-orange edge, one key word of each short caption in the slide's accent colour; only slide 1 carries
 * the app icon. Each slide has its own mood (day meadow, the island in the sun, winter, the red alarm of the play screen,
 * warm desert, the city of 2030 at night, the metropolis at dusk, the collage), so the series never reads as one
 * picture eight times. Portrait phones get all eight, reframed for the tall screen.
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
        PHONE_PORTRAIT("phone-portrait", 1080, 1920, "w411dp-h731dp-port-420dpi"),
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
                    hiddenTop = 0f
                    year = null
                    val game = Bitmap.createBitmap(card.width().toInt(), card.height().toInt(), Bitmap.Config.ARGB_8888)
                    shot.draw(game)
                    hiddenTop = 0f
                    Cosmetic.reset()
                    val era = year?.toString() ?: if (shot.name == "sceneries") "1995–2030" else null
                    val framed = frame(device, game, captions[i], shot, first = i == 0, keyword = KEYWORDS.getValue(lang)[i], era = era)
                    writeRgbPng(framed, File(dir, "%02d-%s.png".format(i + 1, shot.name)))
                    assertEquals(device.width, framed.width)
                    assertEquals(device.height, framed.height)
                }
            }
        }
    }

    /**
     * One of the eight motifs: a name for the file, the [accent] of its caption's underline and how to draw it on the
     * game surface below the brand bar. (Formerly: a full-bleed shot filled
     * the whole frame and its caption sits on a soft gradient over the map; the others (real HUD, dialog, collage)
     * start below the caption band. [tint] colours the caption's gradient and [accent] its underline, one pair per
     * motif, so the set reads as one family without every slide looking the same (judge panel).
     */
    private class Shot(val name: String, val accent: Int, val draw: (Bitmap) -> Unit)

    /** Height of the caption band of [d]. */
    private fun band(d: StoreDevice) = if (d.height > d.width) d.height * 0.11f else d.height * BAND

    /** The in-game year of the world last drawn by [game], shown on the caption's era pill; null for a collage. */
    private var year: Int? = null

    /** Height of the map hidden under the caption of a full-bleed shot while it is drawn; focus points move below it. */
    private var hiddenTop = 0f

    /** Where the game of [shot] sits in the frame of [d]: all of it, or from under the caption band to the bottom. */
    private fun cardRect(d: StoreDevice, @Suppress("UNUSED_PARAMETER") shot: Shot): RectF =
        RectF(0f, band(d), d.width.toFloat(), d.height.toFloat())

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
        year = world.year
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
        // 1. The hero, one moment filling the frame (day, the meadow): the busiest server of a late-game town has just
        //    grown into a data center, cables of every technology run into it and packets travel every line. The camera
        //    sits tight on it (no board edge, no fog) and a soft vignette puts the eye on the building first.
        Shot("town", 0xFFF28C28.toInt()) { bmp ->
            val tall = bmp.height > bmp.width
            // Upright, a town some weeks further on: its grown land fills the tall picture instead of the fog beyond.
            val world = lateTown(weeks = if (tall) 9 else 5, week = if (tall) 12 else 10, rounds = 1)
            world.nodes.forEach { it.overload = 0f }
            // The hub grows into a data center (its fourth tier): one big, bright building the eye finds first.
            val hub = hubServer(world)
            check(world.upgradeServer(hub) && hub.isDataCenter) { "the hub must become a data center" }
            // Its new ports take the devices nearby that still wait for a line, with fiber.
            world.nodes.filter { n -> n.kind == NodeKind.CLIENT && world.cables.none { it.a === n || it.b === n } && hub.service in n.device!!.services }
                .sortedBy { abs(it.cellX - hub.cellX) + abs(it.cellY - hub.cellY) }
                .forEach { if (world.ports(hub) < hub.maxPorts) world.connect(it, hub, world.unlockedCables.last()) }
            // A few seconds on, so the packets spread out along the lines instead of queueing at the new building.
            repeat(60 * 6) { world.update(1f / 60f) }
            world.nodes.forEach { it.overload = 0f }
            calm(world, keep = 2, near = hub.center)
            // The building a little above the middle, so the cables fanning out towards the viewer fill the lower half.
            val look = Vec2(hub.footprintCenter.x + 0.6f, hub.footprintCenter.y + 0.6f)
            game(bmp, world, zoom = if (tall) 4.6f else 2.3f, focus = look)
            vignette(bmp, strength = 0.42f)
        },
        // 2. The gesture, close up, on the island in the sun (sand and turquoise sea, a second mood after the meadow):
        //    the best cable picked, a finger drags from a device that still waits for its line to its server; the glow,
        //    the touch trail and the price bubble fill the frame.
        Shot("drag", 0xFFFFC21A.toInt()) { bmp ->
            val tall = bmp.height > bmp.width
            val (world, from, to) = islandDrag(tall)
            // The drag a little below the middle, so the price bubble above the finger stays clear of the caption;
            // upright a little higher, so sand (not only sea) fills the lower half.
            val lift = if (tall) -1.1f else -0.5f
            val mid = Vec2((from.center.x + to.center.x) / 2f + lift, (from.center.y + to.center.y) / 2f + lift)
            val v = view(hud = true)
            var tip = Vec2(0f, 0f)
            var start = Vec2(0f, 0f)
            game(bmp, world, zoom = if (tall) 6f else 4.4f, view = v, focus = mid) { gv ->
                gv.drawCurrent(Canvas(bmp))
                tap(gv, "cable:${world.unlockedCables.last().name}")
                gv.hudHidden = true
                val a = gv.activeRenderer.toScreen(from.center)
                val b = gv.activeRenderer.toScreen(to.center)
                start = a
                gv.injectTouch(MotionEvent.ACTION_DOWN, a.x, a.y)
                val steps = 14
                for (k in 1..steps) {
                    val t = k * 0.9f / steps
                    // A finger never moves in a straight line: a slight arc makes the trail readable.
                    val bow = kotlin.math.sin(t * Math.PI).toFloat() * (abs(b.y - a.y) * 0.15f + bmp.height * 0.025f)
                    tip = Vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t - bow)
                    gv.injectTouch(MotionEvent.ACTION_MOVE, tip.x, tip.y)
                }
            }
            finger(bmp, tip.x, tip.y, from = start, scale = 1.35f)
        },
        // 3. Incidents, full bleed and close up in the winter theme: an excavator cuts a cable, a router is dark.
        //    Conflict sells: it comes early in the set (judge panel).
        //    On the set's own green ground under the brand's dusk caption with a hot accent (judge panel: the sand palette
        //    and the brown header clashed), and close on the one excavator so the threat is the hero.
        Shot("incidents", 0xFFFF6B3D.toInt()) { bmp ->
            // A real game in the mountain village (judge panel: five River Town shots in a row): the excavator has
            // just cut a long cable, dust and sparks fly; the camera sits close on the cut.
            val world = mountainCut(grow = if (bmp.height > bmp.width) 4 else 0)
            val focus = world.incidents.first { it.struck && it.cable != null }.spot
            calm(world, keep = 2, near = focus)
            val lift = if (bmp.height > bmp.width) 1.8f else 0.2f
            // In the winter theme a player unlocks with a 3-day streak: snow-white valleys give the set a second mood
            // (judge panel: every slide in the same flat noon green) and the red cut pops on them.
            game(bmp, world, zoom = if (bmp.height > bmp.width) 3.8f else 3.0f, focus = Vec2(focus.x - lift * 0.8f, focus.y - lift * 0.8f), theme = ColorTheme.WINTER)
        },
        // 4. The tension: the real play screen with its HUD (date, packet count, cable bar), two devices whose queues
        //    are full and whose red overload rings are closing, closer in so the pressure shows.
        Shot("overload", 0xFFFF5A5F.toInt()) { bmp ->
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
            // Both rings above the toolbar: the camera looks a little below the pair.
            val other = world.nodes.filter { it.overload > 0f && it !== hot }.firstOrNull()?.center ?: hot.center
            val pair = Vec2((hot.center.x + other.x) / 2f + 0.8f, (hot.center.y + other.y) / 2f + 0.8f)
            game(bmp, world, zoom = if (bmp.height > bmp.width) 2.5f else 1.9f, view = view(hud = true), focus = pair)
            spotlight(bmp)
        },
        // 5. Wireless in the warm desert theme, close on the radios: coverage circles and the dashed radio links are the hero, no queues; the
        //    servers stay whole inside the picture.
        Shot("wireless", 0xFF5CC8FF.toInt()) { bmp ->
            val world = storeWireless()
            world.nodes.forEach { it.pending.clear() }
            val radios = world.nodes.filter { it.kind == NodeKind.ACCESS_POINT || it.kind == NodeKind.CELL_TOWER }
            val all = world.nodes
            // On the radios and the servers behind them, the whole of every coverage circle in the picture.
            val tall = bmp.height > bmp.width
            // Upright, the picture closes in on the 5 GHz access point and the cell tower, with the servers above them.
            val focus = if (tall) radios.sortedBy { it.center.x }.drop(1).let { r ->
                Vec2(r.map { it.center.x }.average().toFloat() - 1.1f, r.map { it.center.y }.average().toFloat() - 1.1f)
            } else Vec2(
                (radios.map { it.center.x }.average().toFloat() + all.map { it.center.x }.average().toFloat()) / 2f + 0.6f,
                (radios.map { it.center.y }.average().toFloat() + all.map { it.center.y }.average().toFloat()) / 2f,
            )
            // In the desert theme (unlocked with three sceneries): warm sand under the blue and green radio zones.
            game(bmp, world, zoom = if (tall) 6f else 1.85f, focus = focus, theme = ColorTheme.DESERT)
        },
        // 6. The big, satisfying network at night: the city of 2030 late in a game, towers lit, cables in every
        //    colour between them and packets on every line; the camera sits in the middle of the skyline.
        Shot("network", 0xFF7BD3FF.toInt()) { bmp ->
            val world = lateFuture()
            world.nodes.forEach { it.overload = 0f }
            val nodes = world.nodes
            val middle = Vec2(nodes.map { it.center.x }.average().toFloat(), nodes.map { it.center.y }.average().toFloat())
            val towers = signature(world, Scenarios.FUTURE) ?: middle
            val tall = bmp.height > bmp.width
            val focus = Vec2((towers.x + middle.x) / 2f, (towers.y + middle.y) / 2f).let { if (tall) Vec2(it.x + 1f, it.y + 1f) else it }
            calm(world, keep = 2, near = focus)
            game(bmp, world, zoom = if (tall) 3.4f else 1.75f, focus = focus)
            vignette(bmp, strength = 0.35f, color = 0x120C2E)
        },
        // 7. The turned map at dusk: the metropolis late in a game in its autumn colours, turned to an odd angle, a
        //    warm low sun over the skyline; two broad arrows trace the two-finger turn.
        Shot("rotation", 0xFFFFB35C.toInt()) { bmp ->
            val world = lateMetropolis()
            world.nodes.forEach { it.overload = 0f }
            val nodes = world.nodes
            val middle = Vec2(nodes.map { it.center.x }.average().toFloat(), nodes.map { it.center.y }.average().toFloat())
            val focus = middle
            calm(world, keep = 2, near = focus)
            game(bmp, world, zoom = if (bmp.height > bmp.width) 3.0f else 1.6f, focus = focus, theme = ColorTheme.AUTUMN) { v ->
                val r = v.activeRenderer
                r.rotateBy(Camera.shortestTurn(r.camera.angle, 34f), r.camera.centerX, r.camera.centerY, world)
            }
            dusk(bmp)
            motionArcs(bmp)
        },
        // 8. Five sceneries, each in its own colours, as a collage of their maps, each named on a small pill.
        Shot("sceneries", 0xFF7BD389.toInt()) { bmp ->
            sceneryCollage(bmp)
        },
    )

    /**
     * The server a town is built around: the one with the most cables, among those near the middle of the network (a
     * hub at the board's edge would put the outskirts in the picture).
     */
    private fun hubServer(w: World): com.mininetworks.game.game.Node {
        val cx = w.nodes.map { it.center.x }.average().toFloat()
        val cy = w.nodes.map { it.center.y }.average().toFloat()
        return w.nodes.filter { it.kind == NodeKind.SERVER && w.serverUpgradeError(it) == null }.maxBy { s ->
            w.cables.count { it.a === s || it.b === s } * 1.0f - (abs(s.center.x - cx) + abs(s.center.y - cy)) * 0.35f
        }
    }

    /** Darkens the picture softly towards its corners, so the middle (the shot's focal point) draws the eye. */
    private fun vignette(bmp: Bitmap, strength: Float, color: Int = 0x0B1E2A) {
        val r = kotlin.math.hypot(bmp.width.toFloat(), bmp.height.toFloat()) * 0.55f
        val a = (strength * 255).toInt().coerceIn(0, 255)
        Canvas(bmp).drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), Paint().apply {
            shader = RadialGradient(
                bmp.width / 2f, bmp.height * 0.46f, r, intArrayOf(color, color, (a shl 24) or color),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
        })
    }

    /**
     * Evening light over a shot: the picture multiplied with a sky running from low-sun amber at the top to dusk violet
     * at the bottom, and a soft sun glow at the top left. Only the light changes; the map is the real one.
     */
    private fun dusk(bmp: Bitmap) {
        val c = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        c.drawRect(0f, 0f, w, h, Paint().apply {
            shader = LinearGradient(0f, 0f, w * 0.35f, h, intArrayOf(0xFFFFEBD0.toInt(), 0xFFF8D2B8.toInt(), 0xFFC9B0DE.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.MULTIPLY)
        })
        c.drawRect(0f, 0f, w, h, Paint().apply {
            shader = RadialGradient(w * 0.12f, -h * 0.05f, maxOf(w, h) * 0.7f, 0x60FFB060, 0x00FFB060, Shader.TileMode.CLAMP)
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
        })
        // Low sun, hard light: a little more contrast, so the evening does not read as haze.
        val lit = bmp.copy(Bitmap.Config.ARGB_8888, false)
        val k = 1.2f
        val t = -0.1f * 255f
        c.drawBitmap(lit, 0f, 0f, Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, t, 0f, k, 0f, 0f, t, 0f, 0f, k, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        })
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
        // Devices no server stands in front of (nearer the viewer), so the red glow is not hidden behind a building.
        val servers = w.nodes.filter { it.kind == NodeKind.SERVER }
        val hot = w.nodes.filter { it.kind == NodeKind.CLIENT }
            .filter { n -> servers.none { s -> s.cellX - n.cellX in 0..2 && s.cellY - n.cellY in 0..2 } }
            .sortedBy { abs(it.center.x - cx) + abs(it.center.y - cy) }.take(3)
        // The crisis escalates (judge panel): three devices, their rings at different stages, queues full.
        hot.forEachIndexed { i, n ->
            val services = n.device!!.services
            n.pending.clear()
            repeat(World.Tuning.MAX_PENDING) { k -> n.pending.addLast(services[k % services.size]) }
            n.overload = when (i) { 0 -> 0.88f; 1 -> 0.62f; else -> 0.38f }
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
     * Tints the picture towards its edges in alarm red (a radial spotlight on the middle, where the overloaded device
     * sits), so the eye lands there first.
     */
    private fun spotlight(bmp: Bitmap) {
        val r = maxOf(bmp.width, bmp.height) * 0.62f
        Canvas(bmp).drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), Paint().apply {
            shader = RadialGradient(bmp.width / 2f, bmp.height / 2f, r, intArrayOf(0, 0x0C8A0E1E, 0x6E8A0E1E), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        })
    }

    /** The metropolis late in a game: grown, every device wired with the cable its distance calls for, servers upgraded, rush hour. */
    private fun lateMetropolis(): World {
        val w = World(Scenarios.METROPOLIS, seed = 4L)
        w.incidentsEnabled = false
        repeat(60 * 45 * 2) {
            if (w.rewardOffer != null) w.chooseReward(0)
            w.nodes.forEach { it.pending.clear() }
            w.update(1f / 60f)
        }
        w.jumpToWeek(w.week + 4)
        w.grant(4000)
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
        check(!w.gameOver) { "the metropolis must still run" }
        return w
    }

    /**
     * A harbour corner of the island (its sand, sea and palms) a few weeks into a game: a few devices already wired to
     * their servers, and a game console that still waits for its line, whose drag to the game server is slide 2. The
     * drag runs across open sand, left to right, so the finger, the glowing line and the price bubble read at once;
     * upright it is shorter, to fit the narrow picture. Returns the world, the console and the server.
     */
    private fun islandDrag(tall: Boolean): Triple<World, com.mininetworks.game.game.Node, com.mininetworks.game.game.Node> {
        val w = World(Scenarios.ISLAND, cols = 30, rows = 22, seed = 3L, spawnInitialNodes = false)
        w.incidentsEnabled = false
        for (y in 0 until w.rows) for (x in 0 until w.cols) {
            // The sea along the front edges and a bay at the back right; sand everywhere else.
            val sea = x + y >= 34 || y >= 17 || (x >= 22 && y <= 6) || (x >= 24 && y <= 9)
            w.setTerrain(x, y, if (sea) com.mininetworks.game.game.Terrain.WATER else com.mininetworks.game.game.Terrain.LAND)
        }
        // Far enough into the game that the whole board is open land (no fog at the picture's edges).
        w.jumpToWeek(16)
        w.grant(500)
        val game = w.addServer(Service.GAMING, 18, 8)
        val mail = w.addServer(Service.MAIL, 8, 8)
        val cdn = w.addServer(Service.STREAMING, 19, 13)
        val call = w.addServer(Service.CALL, 11, 15)
        for ((device, x, y, server, cable) in listOf(
            Wire(Device.PC, 7, 12, mail, CableType.DSL), Wire(Device.LAPTOP, 11, 6, mail, CableType.DSL),
            Wire(Device.TV, 16, 15, cdn, CableType.COAX), Wire(Device.PC, 21, 10, game, CableType.FIBER),
            Wire(Device.SMARTPHONE, 21, 5, game, CableType.COAX), Wire(Device.PHONE, 8, 15, call, CableType.ISDN),
            Wire(Device.WATCH, 14, 16, call, CableType.DSL), Wire(Device.TABLET, 22, 12, cdn, CableType.COAX),
        )) check(w.connect(w.addClient(device, x, y), server, cable)) { "wire $device" }
        listOf(game, mail, cdn, call).forEach { w.upgradeServer(it) }
        val from = if (tall) w.addClient(Device.CONSOLE, 14, 11) else w.addClient(Device.CONSOLE, 11, 12)
        repeat(60 * 8) { w.update(1f / 60f) }
        rushHour(w, seconds = 1.4f, rounds = 1)
        calm(w, keep = 0, near = game.center, except = setOf(from))
        from.pending.clear()
        repeat(2) { from.pending.addLast(Service.GAMING) }
        check(!w.gameOver)
        return Triple(w, from, game)
    }

    /** One wired device of a hand-built scene: what it is, where it stands, its server and its cable. */
    private data class Wire(val device: Device, val x: Int, val y: Int, val server: com.mininetworks.game.game.Node, val cable: CableType)

    /** The mountain village a while into a game, wired, where an excavator has just cut the longest cable. */
    private fun mountainCut(grow: Int = 0): World {
        val w = World(Scenarios.MOUNTAIN_VILLAGE, seed = 4L)
        w.incidentsEnabled = false
        repeat(60 * 45 * 2) {
            if (w.rewardOffer != null) w.chooseReward(0)
            w.nodes.forEach { it.pending.clear() }
            w.update(1f / 60f)
        }
        if (grow > 0) w.jumpToWeek(w.week + grow)
        w.grant(1500)
        wireNearest(w)
        w.nodes.filter { it.kind == NodeKind.SERVER }.forEach { s -> w.upgradeServer(s) }
        repeat(60 * 3) {
            if (w.rewardOffer != null) w.chooseReward(0)
            w.update(1f / 60f)
        }
        val cx = w.nodes.map { it.center.x }.average().toFloat()
        val cy = w.nodes.map { it.center.y }.average().toFloat()
        // A long cable near the middle of the network, so the cut sits among houses, cables and mountains.
        // Nothing tall may stand in front of the cut (nearer the viewer), or it hides the excavator.
        fun open(p: Vec2) = w.nodes.none { n ->
            val reach = if (n.kind == NodeKind.CLIENT) 1.6f else 3f
            n.center.x - p.x in -0.8f..reach && n.center.y - p.y in -0.8f..reach
        }
        // Away from the board's front edges too, so an upright picture shows land (not the outskirts) below the cut.
        fun inland(p: Vec2) = p.x < w.cols - 5f && p.y < w.rows - 4f && p.x > 3f && p.y > 2f
        val cable = w.cables.filter { it.layout.length >= 4f && open(it.layout.pointAt(0.5f)) && inland(it.layout.pointAt(0.5f)) }
            .minByOrNull { c -> c.layout.pointAt(0.5f).let { abs(it.x - cx) + abs(it.y - cy) } }
            ?: w.cables.filter { it.layout.length >= 4f && open(it.layout.pointAt(0.5f)) }
                .minByOrNull { c -> c.layout.pointAt(0.5f).let { abs(it.x - cx) + abs(it.y - cy) } } ?: w.cables.maxBy { it.layout.length }
        w.announceExcavator(cable, cutAt = 0.5f)
        repeat((60 * (com.mininetworks.game.game.Incidents.WARNING_SECONDS + 1.5f)).toInt()) {
            if (w.rewardOffer != null) w.chooseReward(0)
            w.update(1f / 60f)
        }
        check(!w.gameOver) { "the village must still run" }
        check(w.incidents.any { it.struck }) { "the excavator must have struck: ${w.incidents.map { it.struck to it.warning }}" }
        return w
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
    private fun lateTown(grant: Int = 2000, weeks: Int = 3, week: Int = 6, rounds: Int = 2): World {
        val w = World(seed = 4L)
        w.incidentsEnabled = false
        repeat(60 * 45 * weeks) {
            if (w.rewardOffer != null) w.chooseReward(0)
            w.nodes.forEach { it.pending.clear() }
            w.update(1f / 60f)
        }
        // Some years later, so fiber is invented: the long runs get it.
        w.jumpToWeek(week)
        w.grant(grant)
        wireNearest(w)
        w.nodes.filter { it.kind == NodeKind.SERVER }.forEach { s -> repeat(2) { w.upgradeServer(s) } }
        repeat(60 * 7) {
            if (w.rewardOffer != null) w.chooseReward(0)
            w.update(1f / 60f)
        }
        rushHour(w, rounds = rounds)
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

    private fun wireNearest(
        w: World, skip: Set<com.mininetworks.game.game.Node> = emptySet(), keepFree: Set<com.mininetworks.game.game.Node> = emptySet(),
    ) {
        val cables = w.unlockedCables
        for (client in w.nodes.filter { it.kind == NodeKind.CLIENT && it !in skip }) {
            val server = w.nodes
                .filter { it.kind == NodeKind.SERVER && it.service in client.device!!.services && w.ports(it) < it.maxPorts - (if (it in keepFree) 1 else 0) }
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
        // Five cells of open land around the scene on every side, so an upright picture ends in land, not in fog.
        val w = World(cols = 26, rows = 20, seed = 3L, spawnInitialNodes = false)
        for (row in w.water) row.fill(false)
        w.incidentsEnabled = false
        w.jumpToWeek(11)
        w.grant(400, extraAccessPoints = 1, extraCellTowers = 1)
        val call = w.addServer(Service.CALL, 18, 6)
        val cdn = w.addServer(Service.STREAMING, 7, 6)
        val mail = w.addServer(Service.MAIL, 13, 6)
        val west = w.addRouter(10, 8)
        val east = w.addRouter(15, 8)
        check(w.connect(west, east, CableType.FIBER))
        listOf(cdn, mail).forEach { check(w.connect(west, it, CableType.FIBER)) }
        check(w.connect(east, call, CableType.FIBER))
        repeat(2) { listOf(call, cdn, mail).forEach { s -> w.upgradeServer(s) } }
        val ap = w.addRadio(com.mininetworks.game.game.RadioType.WLAN, 9, 11)
        val fast = w.addRadio(com.mininetworks.game.game.RadioType.WLAN, 13, 12)
        val tower = w.addRadio(com.mininetworks.game.game.RadioType.CELL, 17, 11)
        w.upgradeTo5Ghz(fast)
        check(w.connect(ap, west, CableType.FIBER))
        listOf(fast, tower).forEach { check(w.connect(it, east, CableType.FIBER)) }
        for ((device, x, y) in listOf(
            Triple(Device.LAPTOP, 8, 10), Triple(Device.TABLET, 8, 12), Triple(Device.TV, 10, 13),
            Triple(Device.PC, 12, 13), Triple(Device.TABLET, 14, 13), Triple(Device.SMARTPHONE, 13, 14),
            Triple(Device.SMARTPHONE, 18, 12), Triple(Device.WATCH, 16, 13), Triple(Device.TABLET, 18, 10),
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
    private fun finger(bmp: Bitmap, x: Float, y: Float, from: Vec2? = null, scale: Float = 1f) {
        val c = Canvas(bmp)
        val d = uiDensity() * scale
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
     * The turn as motion (judge panel: a round badge in the corner read as a pasted button): two broad curved arrows
     * sweep around the board, one over the top left and one under the bottom right, like the path of the two fingers.
     */
    private fun motionArcs(bmp: Bitmap) {
        val c = Canvas(bmp)
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val rx = w * 0.46f; val ry = h * 0.42f
        val oval = RectF(cx - rx, cy - ry, cx + rx, cy + ry)
        val thick = minOf(w, h) * 0.022f
        for ((start, sweep) in listOf(196f to 58f, 16f to 58f)) {
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = thick * 2.6f; color = 0x552A1B52
                maskFilter = BlurMaskFilter(thick, BlurMaskFilter.Blur.NORMAL)
            }
            c.drawArc(oval, start, sweep, false, glow)
            val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = thick; color = 0xFFFFFFFF.toInt() }
            c.drawArc(oval, start, sweep, false, arc)
            val end = Math.toRadians((start + sweep).toDouble())
            val ex = cx + rx * kotlin.math.cos(end).toFloat()
            val ey = cy + ry * kotlin.math.sin(end).toFloat()
            // Tangent of the ellipse at the end, in the direction of travel.
            var ux = -rx * kotlin.math.sin(end).toFloat()
            var uy = ry * kotlin.math.cos(end).toFloat()
            val len = kotlin.math.hypot(ux, uy); ux /= len; uy /= len
            val hl = thick * 3.2f
            val head = Path().apply {
                moveTo(ex + ux * hl, ey + uy * hl)
                lineTo(ex - uy * hl * 0.8f, ey + ux * hl * 0.8f)
                lineTo(ex + uy * hl * 0.8f, ey - ux * hl * 0.8f)
                close()
            }
            c.drawPath(head, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x552A1B52; maskFilter = BlurMaskFilter(thick, BlurMaskFilter.Blur.NORMAL) })
            c.drawPath(head, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
            // The fingertip at the start of the arc.
            val sa = Math.toRadians(start.toDouble())
            val fx = cx + rx * kotlin.math.cos(sa).toFloat(); val fy = cy + ry * kotlin.math.sin(sa).toFloat()
            c.drawCircle(fx, fy, thick * 1.7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF28C28.toInt() })
            c.drawCircle(fx, fy, thick * 1.7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = thick * 0.5f; color = 0xFFFFFFFF.toInt() })
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
        // The small tiles in the order of their years (judge panel: 2004 stood before 2001).
        val order = listOf(Scenarios.METROPOLIS, Scenarios.RIVER_TOWN, Scenarios.MOUNTAIN_VILLAGE, Scenarios.ISLAND, Scenarios.FUTURE)
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

    /** The 1995 half of the feature graphic a step more saturated still, level with the vivid 2030 half (judge panel). */
    private val vividPast = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        val m = android.graphics.ColorMatrix().apply { setSaturation(1.6f) }
        val k = 1.1f
        val t = -0.06f * 255f
        m.postConcat(android.graphics.ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, t, 0f, k, 0f, 0f, t, 0f, 0f, k, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        colorFilter = android.graphics.ColorMatrixColorFilter(m)
    }

    /**
     * [game] in the frame of [d] under [caption]. A full-bleed shot runs under the caption, which sits on a soft
     * gradient in the shot's [Shot.tint] that fades into the map; the others hang below a slim band in that tint. The
     * headline is set in the game's display face (Nunito Black) with a short underline in the shot's [Shot.accent];
     * only the [first] slide carries the app icon (judge panel: a repeated icon and one dark slab read as a template).
     */
    private fun frame(d: StoreDevice, game: Bitmap, caption: String, shot: Shot, first: Boolean, keyword: String, era: String?): Bitmap {
        assertTrue("keyword \"$keyword\" in \"$caption\"", keyword in caption)
        val out = Bitmap.createBitmap(d.width, d.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val w = d.width.toFloat()
        val h = d.height.toFloat()
        val band = band(d)
        // One brand bar for every slide (judge panel: a hue per slide read as unrelated templates, and the fade into the
        // map hid ~150 px of play): solid dusk navy with a short soft shadow, the game whole below it.
        c.drawColor(BRAND_DARK)
        c.drawBitmap(game, 0f, band, vivid)
        c.drawRect(0f, 0f, w, band, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, band, BRAND_MID, BRAND_DARK, Shader.TileMode.CLAMP)
        })
        c.drawRect(0f, band, w, band * 1.1f, Paint().apply {
            shader = LinearGradient(0f, band, 0f, band * 1.1f, 0x40000000, 0, Shader.TileMode.CLAMP)
        })
        // A thin fiber-orange edge under the bar on every slide: the brand's cable colour ties the set together.
        c.drawRect(0f, band - band * 0.03f, w, band, Paint().apply { color = BRAND_FIBER })
        // A faint light from the top left over the tint.
        c.drawRect(0f, 0f, w, band, Paint().apply {
            shader = RadialGradient(w * 0.2f, -band * 0.6f, w * 0.6f, 0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        })

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = display; textAlign = Paint.Align.LEFT }
        text.setShadowLayer(band * 0.04f, 0f, band * 0.02f, 0x80000000.toInt())
        val tall = d.height > d.width
        text.textSize = band * (if (tall) 0.44f else 0.5f)
        val markSize = if (first) band * 0.58f else 0f
        val markRoom = if (first) markSize * 1.3f else 0f
        // The year of the shot on a pill at the right end of a wide bar: the eras (1995 → 2030) are the game's hook,
        // so every slide says where in them it stands (judge panel).
        val pillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = display; textSize = band * 0.24f }
        val showEra = era != null && !tall
        val pillW = if (showEra) pillP.measureText(era) + band * 0.5f else 0f
        val side = if (showEra) pillW + band * 0.35f else 0f
        val maxW = minOf(w * 0.9f, w - 2 * side) - markRoom
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
        // The headline's key word in the slide's accent colour, underlined by the accent bar (judge panel: a plain white
        // line with a tiny centred bar read as a generic template).
        val hot = Paint(text).apply { color = shot.accent.let { if (it == 0xFFFF5A5F.toInt()) 0xFFFF7A7E.toInt() else it } }
        var barX0 = x0 + markRoom + textW * 0.35f
        var barX1 = x0 + markRoom + textW * 0.65f
        var barLine = lines.size - 1
        lines.forEachIndexed { i, l ->
            val lx = x0 + markRoom + (textW - text.measureText(l)) / 2f
            val by = firstBaseline + i * lineH
            val k = l.indexOf(keyword)
            if (k < 0) {
                c.drawText(l, lx, by, text)
            } else {
                val pre = l.substring(0, k)
                val post = l.substring(k + keyword.length)
                c.drawText(pre, lx, by, text)
                val kx = lx + text.measureText(pre)
                c.drawText(keyword, kx, by, hot)
                c.drawText(post, kx + text.measureText(keyword), by, text)
                barX0 = kx; barX1 = kx + text.measureText(keyword); barLine = i
            }
        }
        val barH = maxOf(5f, band * 0.05f)
        val barY = firstBaseline + barLine * lineH + text.textSize * 0.22f
        c.drawRoundRect(barX0, barY, barX1, barY + barH, barH / 2f, barH / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = shot.accent })
        if (showEra) {
            val ph = band * 0.42f
            val r = RectF(w - band * 0.3f - pillW, cy - ph / 2f, w - band * 0.3f, cy + ph / 2f)
            c.drawRoundRect(r, ph / 2f, ph / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF })
            c.drawRoundRect(r, ph / 2f, ph / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = band * 0.025f; color = shot.accent })
            c.drawText(era!!, r.left + band * 0.25f, r.centerY() + pillP.textSize * 0.36f, pillP)
        }
        return out
    }

    /**
     * The feature graphic (1024 × 500, no alpha), one striking picture: the city of 2030 at night, late in a game, its
     * towers and data centers lit and fiber glowing between them, framed tight on the skyline at the right; the title
     * lockup (icon, the name in Nunito Black, the tagline in fiber orange) sits on the night sky at the left, which
     * deepens into a calm indigo behind the text. Nothing important lies in the outer 10 % (Play may crop it or lay
     * buttons over it).
     */
    @Test
    @Config(qualifiers = "en-w731dp-h411dp-land-420dpi")
    fun featureGraphic() {
        val w = 1024
        val h = 500
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)

        val city = lateFuture()
        city.nodes.forEach { it.overload = 0f }
        val nodes = city.nodes
        val middle = Vec2(nodes.map { it.center.x }.average().toFloat(), nodes.map { it.center.y }.average().toFloat())
        val towers = signature(city, Scenarios.FUTURE) ?: middle
        val focus = Vec2((towers.x + middle.x) / 2f, (towers.y + middle.y) / 2f)
        calm(city, keep = 1, near = focus)
        val map = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        IsoRenderer().apply {
            density = 1.7f
            layout(w, h, city)
            val fx = w * 0.68f
            val fy = h * 0.54f
            toScreen(focus).let { p -> camera.panBy(fx - p.x, fy - p.y) }
            camera.zoomBy(1.9f, fx, fy)
            draw(Canvas(map), city, drag = null, time = 1.3f)
        }
        c.drawColor(0xFF1A1540.toInt())
        c.drawBitmap(map, 0f, 0f, vivid)
        // The night sky over the left: deep indigo behind the title, fading out over the city.
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w * 0.62f, 0f, intArrayOf(0xFA15103A.toInt(), 0xE615103A.toInt(), 0x8015103A.toInt(), 0x0015103A),
                floatArrayOf(0f, 0.45f, 0.72f, 1f), Shader.TileMode.CLAMP)
        })
        // A soft vignette at the top and bottom edges, and a warm glow behind the skyline so it pops.
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0x6015103A, 0x0015103A, 0x0015103A, 0x7015103A),
                floatArrayOf(0f, 0.22f, 0.75f, 1f), Shader.TileMode.CLAMP)
        })
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = RadialGradient(w * 0.7f, h * 0.45f, w * 0.34f, 0x33FF9A3C, 0x00FF9A3C, Shader.TileMode.CLAMP)
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
        })

        val name = app.getString(R.string.app_name)
        val left = w * 0.06f
        val room = w * 0.39f - left
        // The name as big as the panel allows, in two stacked words when that is larger (judge panel: modest title).
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = display; textSize = 110f; setShadowLayer(8f, 0f, 3f, 0x80000000.toInt()) }
        val nameLines = if (' ' in name) name.split(' ', limit = 2) else listOf(name)
        title.textSize = 120f
        while (nameLines.any { title.measureText(it) > room }) title.textSize -= 1f
        val nameH = title.textSize * 0.92f * nameLines.size
        val icon = 104f
        fun write(tagline: String, file: File) {
            val shot = out.copy(Bitmap.Config.ARGB_8888, true)
            val sc = Canvas(shot)
            val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFB057.toInt(); typeface = display; textSize = 32f; setShadowLayer(5f, 0f, 2f, 0x80000000.toInt()) }
            var lines = TextWrap.wrap(tagline, room) { tag.measureText(it) }
            while ((lines.size > 2 || lines.any { tag.measureText(it) > room }) && tag.textSize > 18f) {
                tag.textSize -= 1f
                lines = TextWrap.wrap(tagline, room) { tag.measureText(it) }
            }
            assertTrue("tagline in at most two lines inside the panel: $lines", lines.size <= 2 && lines.all { tag.measureText(it) <= room })
            val blockH = icon + 14f + nameH + 16f + lines.size * tag.textSize * 1.2f
            var y = (h - blockH) / 2f
            LogoMark(app).draw(sc, left + icon / 2f, y + icon / 2f, icon)
            y += icon + 14f + title.textSize * 0.8f
            nameLines.forEachIndexed { i, l ->
                if (i > 0) y += title.textSize * 0.92f
                sc.drawText(l, left, y, title)
            }
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
        // The default graphic carries the English value line (judge panel: "1995 → 2030" said too little); each
        // language that has its listing's graphics rendered also gets one with its own tagline.
        write(FEATURE_TAGLINES.getValue("en"), File(storeDir, "feature-graphic.png"))
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
            "en" to "Wire your town – from dial-up to fiber",
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

        /** The fiber orange of the app icon's cable, the edge under every caption bar. */
        const val BRAND_FIBER = 0xFFF28C28.toInt()

        /** The word of each caption set in the slide's accent colour (it must occur in the caption). */
        val KEYWORDS = mapOf(
            "de" to listOf("Stadt", "Kabel", "Bagger", "Überlastung", "5G", "riesiges", "Karte", "Fünf"),
            "en" to listOf("town", "cable", "Excavators", "overload", "5G", "huge", "Spin", "Five"),
            "fr" to listOf("ville", "câble", "pelleteuses", "surcharge", "5G", "immense", "Tourne", "Cinq"),
            "es" to listOf("pueblo", "cable", "excavadoras", "saturación", "5G", "enorme", "Gira", "Cinco"),
            "it" to listOf("città", "cavo", "ruspe", "sovraccarico", "5G", "enorme", "Ruota", "Cinque"),
            "pt-rBR" to listOf("cidade", "cabo", "Escavadeiras", "sobrecarga", "5G", "enorme", "Gire", "Cinco"),
            "pl" to listOf("miasto", "kabel", "Koparki", "przeciążenia", "5G", "ogromną", "Obracaj", "Pięć"),
            "nl" to listOf("stad", "kabel", "Graafmachines", "overbelasting", "5G", "enorm", "Draai", "Vijf"),
            "tr" to listOf("Şehrini", "kablo", "Kepçeler", "Aşırı yükü", "5G", "Dev", "döndür", "Beş"),
            "ja" to listOf("町", "ケーブル", "ショベルカー", "過負荷", "5G", "巨大", "回転", "5つ"),
            "ko" to listOf("도시", "케이블", "굴착기", "과부하", "5G", "거대한", "회전", "다섯 가지"),
            "zh-rCN" to listOf("城镇", "电缆", "挖掘机", "过载", "5G", "庞大", "旋转", "五个"),
        )

        /** Captions of the eight screenshots in every language of the listing (docs/store/<language>.md). */
        val LANGUAGES = mapOf(
            "de" to listOf("Verkabel deine Stadt", "Ein Wisch, ein Kabel", "Bagger kappen Kabel", "Stopp die Überlastung", "WLAN, 4G und 5G", "Bau ein riesiges Netz", "Dreh die Karte", "Fünf Szenerien"),
            "en" to listOf("Wire up your town", "One swipe, one cable", "Excavators cut your cables", "Don't let it overload", "Wi-Fi, 4G and 5G", "Build a huge network", "Spin the map", "Five sceneries"),
            "fr" to listOf("Câble ta ville", "Un geste, un câble", "Les pelleteuses coupent tes câbles", "Évite la surcharge", "Wi-Fi, 4G et 5G", "Bâtis un immense réseau", "Tourne la carte", "Cinq décors"),
            "es" to listOf("Conecta tu pueblo", "Un gesto, un cable", "Las excavadoras cortan cables", "Evita la saturación", "Wi-Fi, 4G y 5G", "Crea una red enorme", "Gira el mapa", "Cinco escenarios"),
            "it" to listOf("Cabla la tua città", "Un gesto, un cavo", "Le ruspe tagliano i cavi", "Evita il sovraccarico", "Wi-Fi, 4G e 5G", "Costruisci una rete enorme", "Ruota la mappa", "Cinque scenari"),
            "pt-rBR" to listOf("Conecte sua cidade", "Um gesto, um cabo", "Escavadeiras cortam cabos", "Evite a sobrecarga", "Wi-Fi, 4G e 5G", "Crie uma rede enorme", "Gire o mapa", "Cinco cenários"),
            "pl" to listOf("Okabluj swoje miasto", "Jeden ruch, jeden kabel", "Koparki tną kable", "Unikaj przeciążenia", "Wi-Fi, 4G i 5G", "Zbuduj ogromną sieć", "Obracaj mapę", "Pięć scenerii"),
            "nl" to listOf("Verbind je stad", "Eén veeg, één kabel", "Graafmachines knippen kabels", "Voorkom overbelasting", "Wifi, 4G en 5G", "Bouw een enorm netwerk", "Draai de kaart", "Vijf landschappen"),
            "tr" to listOf("Şehrini kabloyla bağla", "Bir kaydırma, bir kablo", "Kepçeler kabloları keser", "Aşırı yükü önle", "Wi-Fi, 4G ve 5G", "Dev bir ağ kur", "Haritayı döndür", "Beş manzara"),
            "ja" to listOf("町をケーブルでつなごう", "なぞるだけでケーブル", "ショベルカーがケーブルを切る！", "過負荷を防ごう", "Wi-Fi、4G、5G", "巨大なネットワークを築こう", "地図を回転", "5つのステージ"),
            "ko" to listOf("도시를 케이블로 연결하세요", "한 번 밀면 케이블 하나", "굴착기가 케이블을 끊어요", "과부하를 막으세요", "Wi-Fi, 4G, 5G", "거대한 네트워크를 만드세요", "지도를 회전", "다섯 가지 배경"),
            "zh-rCN" to listOf("为你的城镇铺设网络", "一划即是一条电缆", "挖掘机会挖断电缆", "别让网络过载", "Wi-Fi、4G 和 5G", "打造庞大的网络", "旋转地图", "五个场景"),
        )
    }
}
