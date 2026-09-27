package com.mininetworks.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import com.mininetworks.game.data.ProgressStore
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.CableSkin
import com.mininetworks.game.game.ColorTheme
import com.mininetworks.game.game.DailyStreak
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.GameMode
import com.mininetworks.game.game.PlayerStats
import com.mininetworks.game.game.Scenarios
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World
import com.mininetworks.game.ui.GameView
import com.mininetworks.game.ui.menu.MenuAction
import com.mininetworks.game.ui.menu.SceneryPicker
import com.mininetworks.game.ui.menu.Screen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The retention screens (docs/TOP100.md C1, C2, C4, C5), rendered into docs/screenshots/retention/ on a landscape phone
 * (800 × 360 dp at xxhdpi): the main menu with its new entries, the daily challenge's card, the achievements screen,
 * an unlock toast in a game, the scenery picker in endless mode, a creative game's HUD, an endless jam, the settings
 * with the cosmetics, and every cable skin and color theme in both styles.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "de-w800dp-h360dp-land-xxhdpi")
@OptIn(DebugApi::class)
class RetentionScreenshotTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun tutorialSeen() {
        SettingsStore(app).tutorialSeen = true
    }

    @After
    fun resetCosmetics() {
        Cosmetic.reset()
    }

    private val shots get() = File(System.getProperty("screenshots.dir") ?: "build/screenshots", "retention").apply { mkdirs() }

    private fun save(bmp: Bitmap, name: String) = File(shots, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun phone() = Bitmap.createBitmap(2400, 1080, Bitmap.Config.ARGB_8888)

    /** 2026-09-27 15:00 UTC: Island & harbour, "Few routers". */
    private val now = 1_790_467_200_000L + 15 * 3_600_000L

    /** A player a few days in: some achievements reached, two cosmetics unlocked. */
    private val someStats = PlayerStats(
        delivered = 1_240, bestGame = 412, bestWeek = 9, cablesLaid = 131, fiberLaid = 12, cableUpgrades = 7, routersPlaced = 23,
        accessPoints = 2, serverUpgrades = 6, dataCenters = 1, repairs = 3, gamesFinished = 8, weeksPlayed = 61,
        sceneries = setOf("river_town", "metropolis"), streamingDelivered = 180, dailyDone = 4, bestStreak = 3, richest = 212,
    )

    private fun view(): GameView = GameView(app).also { it.wallClock = { now } }

    private fun menuShot(view: GameView, bmp: Bitmap, name: String) {
        bmp.eraseColor(0)
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = null)
        save(bmp, name)
    }

    private fun tap(view: GameView, bmp: Bitmap, action: MenuAction) {
        val r = view.menuTarget(action) ?: throw AssertionError("$action not on ${view.currentScreen}")
        view.injectTouch(MotionEvent.ACTION_DOWN, r.centerX(), r.centerY())
        view.injectTouch(MotionEvent.ACTION_UP, r.centerX(), r.centerY())
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = null)
    }

    @Test
    fun menusDailyAndAchievements() {
        ProgressStore(app).streak = DailyStreak(20_722L, 3, 3)
        val v = view()
        v.setAchievementStats(someStats)
        val bmp = phone()
        menuShot(v, bmp, "menu-main.png")
        tap(v, bmp, MenuAction.DAILY)
        assertEquals(Screen.DAILY, v.currentScreen)
        menuShot(v, bmp, "daily.png")
        tap(v, bmp, MenuAction.BACK)
        tap(v, bmp, MenuAction.ACHIEVEMENTS)
        menuShot(v, bmp, "achievements.png")
        // Scrolled down to the daily and mode missions.
        v.injectTouch(MotionEvent.ACTION_DOWN, 1200f, 900f)
        v.injectTouch(MotionEvent.ACTION_MOVE, 1200f, 500f)
        v.injectTouch(MotionEvent.ACTION_MOVE, 1200f, 100f)
        v.injectTouch(MotionEvent.ACTION_UP, 1200f, 100f)
        menuShot(v, bmp, "achievements-scrolled.png")
    }

    @Test
    @Config(qualifiers = "en-w800dp-h360dp-land-xxhdpi")
    fun achievementsAndDailyInEnglish() {
        val v = view()
        v.setAchievementStats(someStats)
        val bmp = phone()
        v.drawSnapshot(Canvas(bmp), v.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = Screen.ACHIEVEMENTS)
        save(bmp, "achievements-en.png")
        bmp.eraseColor(0)
        v.drawSnapshot(Canvas(bmp), v.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = Screen.DAILY)
        save(bmp, "daily-en.png")
    }

    @Test
    fun settingsWithCosmetics() {
        val v = view()
        v.setAchievementStats(someStats)
        val bmp = phone()
        v.drawSnapshot(Canvas(bmp), v.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = null)
        tap(v, bmp, MenuAction.SETTINGS)
        tap(v, bmp, MenuAction.APPEARANCE)
        tap(v, bmp, MenuAction.CABLE_SKIN)
        tap(v, bmp, MenuAction.COLOR_THEME)
        assertEquals(CableSkin.COPPER, Cosmetic.skin)
        // Unlocked: the default theme and winter (three days in a row).
        assertEquals(ColorTheme.WINTER, Cosmetic.theme)
        menuShot(v, bmp, "settings-cosmetics.png")
    }

    @Test
    fun unlockToastInAGame() {
        val v = view()
        val bmp = phone()
        val w = Scenes.hud()
        v.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        // The first frames count the packets delivered so far: "Erstes Paket" and "Postbote" wait in the queue.
        repeat(30) { v.advance(1f / 60f) }
        assertNotNull(v.shownToast)
        v.drawCurrent(Canvas(bmp))
        save(bmp, "toast.png")
    }

    @Test
    fun pickerInEndlessMode() {
        val v = view()
        val bmp = phone()
        v.drawSnapshot(Canvas(bmp), v.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = null)
        tap(v, bmp, MenuAction.PLAY)
        val mode = v.sceneryTarget(SceneryPicker.MODE)!!
        v.injectTouch(MotionEvent.ACTION_DOWN, mode.centerX(), mode.centerY())
        v.injectTouch(MotionEvent.ACTION_UP, mode.centerX(), mode.centerY())
        assertEquals(GameMode.ENDLESS, v.sceneryMode)
        menuShot(v, bmp, "picker-endless.png")
    }

    @Test
    fun creativeHudAndEndlessJam() {
        val bmp = phone()
        val creative = World(Scenarios.RIVER_TOWN, seed = 4L, mode = GameMode.CREATIVE)
        repeat(60 * 8) { creative.update(1f / 60f) }
        val v = view()
        v.drawSnapshot(Canvas(bmp), creative, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        save(bmp, "creative-hud.png")

        val endless = World(cols = 16, rows = 10, seed = 3L, spawnInitialNodes = false, mode = GameMode.ENDLESS)
        endless.incidentsEnabled = false
        endless.addServer(Service.CALL, 12, 5)
        val phoneNode = endless.addClient(Device.PHONE, 4, 4)
        endless.addClient(Device.PHONE, 6, 5)
        endless.addClient(Device.PHONE, 3, 6)
        repeat(World.Tuning.MAX_PENDING) { phoneNode.pending.addLast(Service.CALL) }
        val e = view()
        e.drawSnapshot(Canvas(bmp), endless, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        var frames = 0
        while (!(phoneNode.overload >= 1f && e.shownHint != null) && frames++ < 60 * 60) {
            while (phoneNode.pending.size < World.Tuning.MAX_PENDING) phoneNode.pending.addLast(Service.CALL)
            e.advance(1f / 60f)
        }
        assertEquals(1f, phoneNode.overload)
        assertEquals(GameMode.ENDLESS, e.currentWorld.mode)
        e.drawCurrent(Canvas(bmp))
        save(bmp, "endless-jam.png")
    }

    /** Every cable skin (rows) in the iso and the flat style (columns), on the same town with all four technologies. */
    @Test
    fun cableSkinsInBothStyles() = sheet("skins.png", CableSkin.entries.map { it.name }) { i -> Cosmetic.skin = CableSkin.entries[i] }

    /** Every color theme (rows) in both styles. */
    @Test
    fun colorThemesInBothStyles() = sheet("themes.png", ColorTheme.entries.map { it.name }) { i -> Cosmetic.theme = ColorTheme.entries[i] }

    private fun sheet(name: String, labels: List<String>, apply: (Int) -> Unit) {
        val w = Scenes.hud()
        val cellW = 900
        val cellH = 520
        val sheet = Bitmap.createBitmap(2 * cellW, labels.size * cellH, Bitmap.Config.ARGB_8888)
        val out = Canvas(sheet)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f; typeface = Typeface.DEFAULT_BOLD; color = 0xFF262B33.toInt() }
        for ((row, text) in labels.withIndex()) {
            apply(row)
            for ((col, r) in listOf(IsoRenderer(), FlatRenderer()).withIndex()) {
                val bmp = Bitmap.createBitmap(cellW, cellH, Bitmap.Config.ARGB_8888)
                r.layout(bmp.width, bmp.height, w)
                r.draw(Canvas(bmp), w, drag = null, time = 1.3f)
                out.drawBitmap(bmp, (col * cellW).toFloat(), (row * cellH).toFloat(), null)
                out.drawText("$text · ${r.name}", col * cellW + 20f, row * cellH + 44f, label)
            }
        }
        Cosmetic.reset()
        save(sheet, name)
    }
}
