package com.mininetworks.game.render

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.view.MotionEvent
import com.mininetworks.game.R
import com.mininetworks.game.data.HighscoreStore
import com.mininetworks.game.data.ProgressStore
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.DailyStreak
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.Scenarios
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World
import com.mininetworks.game.monetization.Entitlements
import com.mininetworks.game.monetization.FakeMonetization
import com.mininetworks.game.ui.GameView
import com.mininetworks.game.ui.TextWrap
import com.mininetworks.game.ui.menu.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * Every picture is the real game (GameView with its HUD, menus and cards) in the language of the listing, framed on the
 * dusk blue of the launcher icon with a short caption above it (at most about 15 % of the image). 16:9 as Play asks for
 * phones and tablets (1080–7680 px per side): phone 1920 × 1080 at 420 dpi (731 × 411 dp), 7" tablet 1920 × 1080 at
 * xhdpi (960 × 540 dp), 10" tablet 2560 × 1440 at xhdpi (1280 × 720 dp).
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
        TABLET_7("tablet-7", 1920, 1080, "w960dp-h540dp-land-xhdpi"),
        TABLET_10("tablet-10", 2560, 1440, "w1280dp-h720dp-land-xhdpi"),
    }

    private val app get() = RuntimeEnvironment.getApplication()
    private val storeDir get() = File(System.getProperty("store.dir") ?: "build/store").apply { mkdirs() }

    /** 2026-09-27 15:00 UTC, as in the retention screenshots: Island & harbour, "Few routers". */
    private val now = 1_790_467_200_000L + 15 * 3_600_000L

    @Before
    fun setUp() {
        SettingsStore(app).tutorialSeen = true
        HighscoreStore(app).submit(1234)
        ProgressStore(app).streak = DailyStreak(20_722L, 4, 9)
    }

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
                    val inner = innerSize(device)
                    val game = Bitmap.createBitmap(inner.width(), inner.height(), Bitmap.Config.ARGB_8888)
                    shot.draw(game)
                    val framed = frame(device, game, captions[i])
                    writeRgbPng(framed, File(dir, "%02d-%s.png".format(i + 1, shot.name)))
                    assertEquals(device.width, framed.width)
                    assertEquals(device.height, framed.height)
                }
            }
        }
    }

    /** One of the eight motifs: a name for the file and how to draw it on the game surface. */
    private class Shot(val name: String, val draw: (Bitmap) -> Unit)

    /** The game surface inside the frame: the device's full resolution, so the game lays out exactly as on the device. */
    private fun innerSize(d: StoreDevice) = Rect(0, 0, d.width, d.height)

    private fun view(): GameView = GameView(app).also {
        it.wallClock = { now }
        it.monetization = FakeMonetization(prices = mapOf(Entitlements.SCENERY_PACK to "4,99 €", Entitlements.REMOVE_ADS to "2,99 €"))
    }

    /** [world] in the game frame with its HUD, the map zoomed in by [zoom] around the screen center. */
    private fun game(bmp: Bitmap, world: World, zoom: Float, view: GameView = view(), before: (GameView) -> Unit = {}) {
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        view.activeRenderer.camera.zoomBy(zoom, bmp.width / 2f, bmp.height / 2f)
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
        // 1. A lively town: every service, routers, a data center, packets on every cable.
        Shot("town") { bmp -> game(bmp, heroTown(), zoom = 1.25f) },
        // 2. Laying a cable: fiber picked, the finger drags from the tablet to the game server, the preview shows path and price.
        Shot("drag") { bmp ->
            val world = Scenes.hud()
            game(bmp, world, zoom = 1.25f) { v ->
                v.drawCurrent(Canvas(bmp))
                tap(v, "cable:${CableType.FIBER.name}")
                val from = world.nodes.first { it.device == Device.TABLET }
                val to = world.nodes.first { it.service == Service.GAMING }
                val a = v.activeRenderer.toScreen(from.center)
                val b = v.activeRenderer.toScreen(to.center)
                v.injectTouch(MotionEvent.ACTION_DOWN, a.x, a.y)
                for (k in 1..8) v.injectTouch(MotionEvent.ACTION_MOVE, a.x + (b.x - a.x) * k / 8f, a.y + (b.y - a.y) * k / 8f)
            }
        },
        // 3. Wireless: WLAN channels, 5 GHz, a cell tower.
        Shot("wireless") { bmp -> game(bmp, Scenes.wireless(), zoom = 1.3f) },
        // 4. The turned map: the metropolis with its towers at an odd angle, compass in the HUD.
        Shot("rotation") { bmp ->
            val world = wiredStart()
            game(bmp, world, zoom = 1.35f) { v ->
                val r = v.activeRenderer
                r.rotateBy(Camera.shortestTurn(r.camera.angle, 34f), r.camera.centerX, r.camera.centerY, world)
            }
        },
        // 5. The daily challenge's card over the demo town.
        Shot("daily") { bmp ->
            val v = view()
            v.drawSnapshot(Canvas(bmp), v.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = Screen.DAILY)
        },
        // 6. The week reward: two cards and the weekly pay.
        Shot("reward") { bmp ->
            view().drawSnapshot(Canvas(bmp), FormFactorScreenshotTest.rewardWorld(), bmp.width, bmp.height, time = 1.3f, style = "Iso")
        },
        // 7. Five sceneries.
        Shot("sceneries") { bmp ->
            val v = view()
            v.drawSnapshot(Canvas(bmp), v.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = Screen.SCENERIES)
        },
        // 8. Incidents: an excavator cuts a cable, a power outage.
        Shot("incidents") { bmp -> game(bmp, Scenes.incidents(), zoom = 1.3f) },
    )

    /** The busy HUD town a few weeks later, with more devices and traffic. */
    private fun heroTown(): World = FormFactorScreenshotTest.busyHud()

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

    /** [game] scaled into the frame of [d] under [caption]. */
    private fun frame(d: StoreDevice, game: Bitmap, caption: String): Bitmap {
        val out = Bitmap.createBitmap(d.width, d.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val w = d.width.toFloat()
        val h = d.height.toFloat()
        background(c, w, h)
        // The game takes 80 % of the width, centered, flush with a small margin at the bottom.
        val scale = 0.8f
        val gw = w * scale
        val gh = gw * game.height / game.width
        val left = (w - gw) / 2f
        val top = h - gh - h * 0.035f
        val dst = RectF(left, top, left + gw, top + gh)
        val radius = w * 0.018f
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000; maskFilter = BlurMaskFilter(w * 0.012f, BlurMaskFilter.Blur.NORMAL) }
        c.drawRoundRect(RectF(dst.left, dst.top + w * 0.006f, dst.right, dst.bottom + w * 0.006f), radius, radius, shadow)
        c.save()
        c.clipPath(Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) })
        c.drawBitmap(game, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        c.restore()
        // Caption: white, bold, centered in the band above the game; shrinks to fit one line (two for long CJK-free text).
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        val band = top
        text.textSize = band * 0.42f
        val maxW = w * 0.86f
        while (text.measureText(caption) > maxW && text.textSize > band * 0.26f) text.textSize *= 0.96f
        val lines = TextWrap.wrap(caption, maxW) { text.measureText(it) }
        assertTrue("caption \"$caption\" fits ${d.id}: $lines", lines.size <= 2 && lines.all { text.measureText(it) <= maxW })
        val lineH = text.textSize * 1.12f
        val firstBaseline = band / 2f - (lines.size - 1) * lineH / 2f + text.textSize * 0.36f
        lines.forEachIndexed { i, l -> c.drawText(l, w / 2f, firstBaseline + i * lineH, text) }
        return out
    }

    /** Dusk blue of the launcher icon, lighter in the upper middle. */
    private fun background(c: Canvas, w: Float, h: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(w / 2f, h * 0.1f, w * 0.75f, 0xFF2F6275.toInt(), 0xFF16303F.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
    }

    /**
     * The feature graphic (1024 × 500, no alpha): the lively town on the left and middle, the icon and the name on the
     * dusk blue on the right. Nothing important lies in the outer 10 % (Play may crop it or lay buttons over it).
     */
    @Test
    @Config(qualifiers = "en-w731dp-h411dp-land-420dpi")
    fun featureGraphic() {
        val w = 1024
        val h = 500
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        background(c, w.toFloat(), h.toFloat())
        // The town, drawn by the isometric renderer alone (no HUD), a bit larger than the graphic and shifted left.
        val world = heroTown()
        val town = Bitmap.createBitmap(820, 500, Bitmap.Config.ARGB_8888)
        val r = IsoRenderer()
        r.density = 1.4f
        r.layout(town.width, town.height, world)
        r.camera.zoomBy(1.18f, town.width / 2f, town.height / 2f)
        r.draw(Canvas(town), world, drag = null, time = 1.3f)
        c.drawBitmap(town, 0f, 0f, null)
        // Fade the town into the blue towards the right.
        val fade = Paint().apply {
            shader = LinearGradient(460f, 0f, 660f, 0f, 0x0016303F, 0xFF1B3848.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(460f, 0f, 660f, h.toFloat(), fade)
        c.drawRect(660f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = 0xFF1B3848.toInt() })
        // Icon and name, centered in the right third, inside the safe area.
        val icon = app.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val size = 190
        val cx = 766f
        val iconTop = 95f
        c.save()
        c.translate(cx - size / 2f, iconTop)
        val inset = size * 18f / 108f
        c.clipPath(Path().apply { addRoundRect(inset, inset, size - inset, size - inset, size * 18f / 108f, size * 18f / 108f, Path.Direction.CW) })
        for (layer in listOf(icon.background, icon.foreground)) { layer.setBounds(0, 0, size, size); layer.draw(c) }
        c.restore()
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; textSize = 50f }
        val name = app.getString(R.string.app_name)
        while (title.measureText(name) / 2f > minOf(cx - w * 0.1f, w * 0.9f - cx) - 4f) title.textSize -= 1f
        c.drawText(name, cx, iconTop + size - inset + 70f, title)
        // Play may crop or cover the outer 10 %: the name stays inside.
        val half = title.measureText(name) / 2f
        assertTrue("name inside the safe area", cx - half >= w * 0.1f && cx + half <= w * 0.9f)
        // Service colors as a row of small dots under the name, like packets on a cable.
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val colors = Service.entries.map { ServiceColors.defaultOf(it) }
        val y = iconTop + size - inset + 108f
        val step = 22f
        colors.forEachIndexed { i, col ->
            dot.color = col
            c.drawCircle(cx + (i - (colors.size - 1) / 2f) * step, y, 6f, dot)
        }
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
