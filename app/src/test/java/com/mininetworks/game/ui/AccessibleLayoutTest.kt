package com.mininetworks.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.CableSkin
import com.mininetworks.game.game.ColorTheme
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.World
import com.mininetworks.game.monetization.Entitlements
import com.mininetworks.game.games.FakeGameServices
import com.mininetworks.game.monetization.FakeMonetization
import com.mininetworks.game.render.Cosmetic
import com.mininetworks.game.render.FormFactor
import com.mininetworks.game.render.FormFactorScreenshotTest
import com.mininetworks.game.ui.menu.Screen
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Touch targets and overlap (docs/TOP100.md A6, A7) over the layout rectangles of every screen, in every [FormFactor]
 * (plus a portrait phone window) at font scale 1.0 and 2.0: every tappable element of the HUD, the menus, the scenery
 * picker, the reward choice and the tutorial is at least 48 × 48 dp, lies inside the screen (a scrolling card row
 * excepted), and no two elements (buttons or texts) overlap. The rectangles are the ones the frame was drawn with and
 * that taps and TalkBack use ([UiNode]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "de")
@OptIn(DebugApi::class)
class AccessibleLayoutTest {

    private val app get() = RuntimeEnvironment.getApplication()

    /** One screen size to check: [qualifiers] for Robolectric and the surface in px. */
    private data class Size(val id: String, val qualifiers: String, val width: Int, val height: Int)

    private val sizes = FormFactor.entries.map { Size(it.id, it.qualifiers, it.widthPx, it.heightPx) } +
        Size("phone-portrait", "w360dp-h800dp-port-xxhdpi", 1080, 2400)

    @Before
    fun setUp() {
        SettingsStore(app).tutorialSeen = true
    }

    @After
    fun resetFont() {
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun hudTargetsAreLargeAndApartInEveryFormat() = everywhere { size, view, bmp ->
        view.drawSnapshot(Canvas(bmp), FormFactorScreenshotTest.busyHud(), bmp.width, bmp.height, time = 1.3f, style = "Iso")
        val nodes = view.accessibilityLayer.nodes.filter { it.key != "hud:map" }
        // Menu, pause, router, two radios, four cable types, and the view controls: turn both ways, compass, tilt both ways.
        check(size, "hud", nodes, expectedActions = 14)
    }

    /** docs/TOP100.md B5: with the map turned, the view controls below the counters stay apart from everything else. */
    @Test
    fun hudWithCompassStaysApartInEveryFormat() = everywhere { size, view, bmp ->
        val world = FormFactorScreenshotTest.busyHud()
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        val r = view.activeRenderer
        r.rotateBy(37f, r.camera.centerX, r.camera.centerY, world)
        view.drawCurrent(Canvas(bmp))
        val nodes = view.accessibilityLayer.nodes.filter { it.key != "hud:map" }
        assertTrue("${size}: compass shown", nodes.any { it.key == "hud:compass" })
        check(size, "hud-compass", nodes, expectedActions = 14)
    }

    @Test
    fun hudWithPausedBannerAndLongHintStaysApart() = everywhere { size, view, bmp ->
        val world = FormFactorScreenshotTest.busyHud()
        view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso")
        // Pause in place (banner at the top) and pick fibre (a long hint above the buttons).
        view.accessibilityLayer.performAction(view.accessibilityLayer.idOf("hud:pause"), android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null)
        view.accessibilityLayer.performAction(view.accessibilityLayer.idOf("hud:cable:FIBER"), android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null)
        view.advance(0f)
        view.drawCurrent(Canvas(bmp))
        val nodes = view.accessibilityLayer.nodes.filter { it.key != "hud:map" }
        assertTrue("${size}: paused banner shown", nodes.any { it.key == "hud:paused" })
        assertTrue("${size}: hint shown", nodes.any { it.key == "hud:hint" })
        check(size, "hud-paused", nodes, expectedActions = 9 + viewControls(size, nodes))
    }

    @Test
    fun menusKeepLargeEntriesInEveryFormat() = everywhere { size, view, bmp ->
        view.monetization = FakeMonetization(prices = mapOf(Entitlements.REMOVE_ADS to "2,99 €"), privacyOptionsRequired = true)
        // With Play Games the main menu has its longest list (docs/TOP100.md C3).
        view.gameServices = FakeGameServices()
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = null)
        // Play, daily challenge, achievements, leaderboards, settings, remove ads ("continue" is disabled without a save).
        check(size, "main menu", view.accessibilityLayer.nodes, expectedActions = 6)
        // The daily challenge's card: start and back.
        bmp.eraseColor(0)
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = Screen.DAILY)
        check(size, "daily", view.accessibilityLayer.nodes, expectedActions = 2)
        val game = FormFactorScreenshotTest.busyHud()
        // Pause: resume, legend, settings, restart, main menu;
        // settings: sound, haptics, appearance, tutorial, privacy choices, privacy policy, back;
        // game over: again, share (docs/TOP100.md D2), main menu.
        for ((screen, actions) in listOf(Screen.PAUSED to 5, Screen.SETTINGS to 7, Screen.APPEARANCE to 6, Screen.GAME_OVER to 3)) {
            bmp.eraseColor(0)
            view.drawSnapshot(Canvas(bmp), game, bmp.width, bmp.height, time = 1.3f, style = "Iso", screen = screen)
            check(size, screen.name, view.accessibilityLayer.nodes, expectedActions = actions)
        }
    }

    /**
     * docs/TOP100.md A7/C5: every label of the settings pages is drawn in full, in every format at 200 % text too, so
     * a sighted player can read which switch is which and which cable skin and color theme are active.
     */
    @Test
    fun settingsLabelsAreNeverCutInEveryFormat() = everywhere { size, german, bmp ->
        // Every language (docs/TOP100.md F1), each with the longest cosmetic names unlocked and picked.
        for (lang in LANGUAGES) {
            RuntimeEnvironment.setQualifiers("+$lang")
            val view = if (lang == "de") german else GameView(app).also { it.accessibilityLayer.forceActive = true }
            view.monetization = FakeMonetization(privacyOptionsRequired = true)
            val game = FormFactorScreenshotTest.busyHud()
            for (screen in listOf(Screen.SETTINGS, Screen.APPEARANCE)) {
                bmp.eraseColor(0)
                view.drawSnapshot(Canvas(bmp), game, bmp.width, bmp.height, time = 1.3f, style = "Iso", screen = screen)
                val cut = view.accessibilityLayer.nodes.filter { it.shortened }.map { it.text }
                assertTrue("$size, $lang, $screen: labels cut with …: $cut", cut.isEmpty())
            }
            for ((skin, theme) in CableSkin.entries.zip(ColorTheme.entries + ColorTheme.entries.last())) {
                Cosmetic.skin = skin
                Cosmetic.theme = theme
                bmp.eraseColor(0)
                view.drawSnapshot(Canvas(bmp), game, bmp.width, bmp.height, time = 1.3f, style = "Iso", screen = Screen.APPEARANCE)
                val cut = view.accessibilityLayer.nodes.filter { it.shortened }.map { it.text }
                assertTrue("$size, $lang, $skin/$theme: labels cut with …: $cut", cut.isEmpty())
            }
            Cosmetic.reset()
        }
        RuntimeEnvironment.setQualifiers("+de")
    }

    /**
     * docs/TOP100.md F1: no label of the main menu, the daily challenge's card, the pause menu and the game-over card is
     * cut with "…" in any of the 12 languages, in every format at normal text size.
     */
    @Test
    fun menuLabelsAreNeverCutInAnyLanguage() {
        val cuts = ArrayList<String>()
        for (lang in LANGUAGES) for (size in sizes) {
            RuntimeEnvironment.setQualifiers("$lang-${size.qualifiers}")
            val view = GameView(app)
            view.accessibilityLayer.forceActive = true
            view.monetization = FakeMonetization(prices = mapOf(Entitlements.REMOVE_ADS to "2,99 €"), privacyOptionsRequired = true)
            view.gameServices = FakeGameServices()
            val bmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            val game = FormFactorScreenshotTest.busyHud()
            for (screen in listOf(null, Screen.DAILY, Screen.PAUSED, Screen.GAME_OVER)) {
                // The daily card's footer counts the hours to the next challenge; check the shortest and the longest
                // wording (1 hour, 5 hours, 23 hours) instead of whatever the real clock says right now.
                val clocks = if (screen == Screen.DAILY) DAILY_FOOTER_CLOCKS else listOf(null)
                for (clock in clocks) {
                    if (clock != null) view.wallClock = { clock }
                    bmp.eraseColor(0)
                    val world = if (screen == null || screen == Screen.DAILY) view.currentWorld else game
                    view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso", screen = screen)
                    view.accessibilityLayer.nodes.filter { it.shortened }.forEach { cuts += "$lang, ${size.id}, ${screen ?: "MAIN"}: ${it.text}" }
                }
            }
        }
        RuntimeEnvironment.setQualifiers("de")
        assertTrue("labels cut with …:\n${cuts.joinToString("\n")}", cuts.isEmpty())
    }

    /**
     * The game-over card on a 360 dp high phone (the two-part card with the time-lapse): the reason for the loss, the
     * key learning moment of a run, stays at least [MIN_REASON_SP] sp and the entries at least [MIN_ENTRY_SP] sp in
     * every language, also with the longest list of entries ("continue" offered). The picture gives way first.
     */
    @Test
    fun gameOverCardKeepsReasonAndEntriesReadableOnLowPhones() {
        val problems = ArrayList<String>()
        for (size in sizes.filter { it.id.startsWith("phone-") && !it.id.endsWith("portrait") }) for (lang in LANGUAGES) {
            RuntimeEnvironment.setQualifiers("$lang-${size.qualifiers}")
            val view = lostGameView(app) { it.accessibilityLayer.forceActive = true }
            view.monetization = FakeMonetization()
            val bmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            screen = RectF(0f, 0f, size.width.toFloat(), size.height.toFloat())
            view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = null)
            val nodes = view.accessibilityLayer.nodes
            // Again, continue, share, main menu.
            check("${size.id}, $lang", "game over", nodes, expectedActions = 4)
            val density = app.resources.displayMetrics.density
            val lines = nodes.single { it.key == "menu:lines" }
            assertTrue("$lang: no loss reason on the card", view.currentWorld.failure != null && lines.text.isNotEmpty())
            if (lines.textPx / density < MIN_REASON_SP) problems += "${size.id}, $lang: reason at ${lines.textPx / density} sp"
            for (b in nodes.filter { it.key.startsWith("menu:") && it.kind == UiNode.Kind.BUTTON }) {
                if (b.textPx / density < MIN_ENTRY_SP) problems += "${size.id}, $lang: ${b.text} at ${b.textPx / density} sp"
                if (b.shortened) problems += "${size.id}, $lang: ${b.text} cut with …"
            }
            val picture = nodes.firstOrNull { it.key == "menu:picture" }
            if (picture == null) {
                problems += "${size.id}, $lang: no time-lapse"
            } else {
                // The time-lapse spans the text pane: no empty band above the title's height or below the last entry.
                val title = nodes.single { it.key == "menu:title" }
                val lastEntry = nodes.filter { it.key.startsWith("menu:") && it.kind == UiNode.Kind.BUTTON }.maxOf { it.bounds.bottom }
                if (picture.bounds.top > title.bounds.top + density) problems += "${size.id}, $lang: time-lapse starts below the title"
                if (picture.bounds.bottom < lastEntry - density) problems += "${size.id}, $lang: time-lapse ends above the last entry"
            }
        }
        RuntimeEnvironment.setQualifiers("de")
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun sceneryPickerKeepsLargeTargetsInEveryFormat() = everywhere { size, view, bmp ->
        view.monetization = FakeMonetization(prices = mapOf(Entitlements.SCENERY_PACK to "4,99 €"))
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = Screen.SCENERIES)
        // Back, mode, pack and five sceneries; a row of cards wider than the screen scrolls, so cards may reach past it.
        check(
            size, "sceneries", view.accessibilityLayer.nodes, expectedActions = 8,
            offScreenOk = { it.startsWith("scenery:") && it != "scenery:back" && it != "scenery:pack" && it != "scenery:mode" },
        )
    }

    /** docs/TOP100.md C2: the achievements screen; its grid scrolls, so tiles may reach past the screen, the rest may not. */
    @Test
    fun achievementsKeepLargeTargetsInEveryFormat() = everywhere { size, view, bmp ->
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = Screen.ACHIEVEMENTS)
        val nodes = view.accessibilityLayer.nodes
        assertTrue("$size: every achievement has a tile", nodes.count { it.key.startsWith("achievement:") && it.kind == UiNode.Kind.TEXT } >= 30 + 1)
        check(size, "achievements", nodes, expectedActions = 1, offScreenOk = { it.startsWith("achievement:") && it !in HEADER })
        // Tiles never cover the header, wherever the grid is scrolled to.
        val header = nodes.filter { it.key in HEADER }
        for (t in nodes.filter { it.key !in HEADER && it.bounds.top >= 0f && it.bounds.bottom <= bmp.height }) {
            for (h in header) assertTrue("$size: ${t.key} under ${h.key}", !RectF.intersects(t.bounds, h.bounds))
        }
    }

    @Test
    fun rewardChoiceKeepsLargeTargetsInEveryFormat() = everywhere { size, view, bmp ->
        view.monetization = FakeMonetization(owned = mutableSetOf(Entitlements.REMOVE_ADS))
        view.drawSnapshot(Canvas(bmp), FormFactorScreenshotTest.rewardWorld(), bmp.width, bmp.height, time = 1.3f, style = "Iso")
        // Two cards, the extra router, the menu button.
        check(size, "reward", view.accessibilityLayer.nodes, expectedActions = 4)
    }

    @Test
    fun tutorialPanelKeepsClearOfTheHudInEveryFormat() = everywhere { size, _, bmp ->
        SettingsStore(app).tutorialSeen = false
        val view = GameView(app)
        view.accessibilityLayer.forceActive = true
        val w = view.currentTutorial!!.world
        view.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 0.3f, screen = null)
        view.advance(0.5f)
        view.drawCurrent(Canvas(bmp))
        val nodes = view.accessibilityLayer.nodes.filter { it.key != "hud:map" }
        // Skip, and the HUD: menu, pause, router, ISDN.
        check(size, "tutorial", nodes, expectedActions = 5)
        SettingsStore(app).tutorialSeen = true
    }

    /**
     * docs/TOP100.md F1: in every language the HUD (with the paused banner and a long hint), the week reward and the
     * tutorial keep their elements apart, inside the screen and at least 48 dp, in every format at text size 1.0 and 2.0.
     */
    @Test
    fun hudRewardAndTutorialStayApartInEveryLanguage() {
        for (lang in LANGUAGES) {
            RuntimeEnvironment.setQualifiers(lang)
            everywhere { size, view, bmp ->
                val where = "$lang, $size"
                val world = FormFactorScreenshotTest.busyHud()
                view.drawSnapshot(Canvas(bmp), world, bmp.width, bmp.height, time = 1.3f, style = "Iso")
                check(where, "hud", view.accessibilityLayer.nodes.filter { it.key != "hud:map" }, expectedActions = 14)
                view.accessibilityLayer.performAction(view.accessibilityLayer.idOf("hud:pause"), android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null)
                view.accessibilityLayer.performAction(view.accessibilityLayer.idOf("hud:cable:FIBER"), android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null)
                view.advance(0f)
                view.drawCurrent(Canvas(bmp))
                val paused = view.accessibilityLayer.nodes.filter { it.key != "hud:map" }
                check(where, "hud-paused", paused, expectedActions = 9 + viewControls(where, paused))
                val reward = GameView(app).also { it.accessibilityLayer.forceActive = true; it.monetization = FakeMonetization(owned = mutableSetOf(Entitlements.REMOVE_ADS)) }
                bmp.eraseColor(0)
                reward.drawSnapshot(Canvas(bmp), FormFactorScreenshotTest.rewardWorld(), bmp.width, bmp.height, time = 1.3f, style = "Iso")
                check(where, "reward", reward.accessibilityLayer.nodes, expectedActions = 4)
                SettingsStore(app).tutorialSeen = false
                val tutorial = GameView(app).also { it.accessibilityLayer.forceActive = true }
                val w = tutorial.currentTutorial!!.world
                bmp.eraseColor(0)
                tutorial.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 0.3f, screen = null)
                tutorial.advance(0.5f)
                tutorial.drawCurrent(Canvas(bmp))
                check(where, "tutorial", tutorial.accessibilityLayer.nodes.filter { it.key != "hud:map" }, expectedActions = 5)
                SettingsStore(app).tutorialSeen = true
            }
        }
        RuntimeEnvironment.setQualifiers("de")
    }

    /** Runs [body] with a fresh view for every size at font scale 1.0 and 2.0. */
    private fun everywhere(body: (String, GameView, Bitmap) -> Unit) {
        for (size in sizes) for (font in listOf(1f, 2f)) {
            RuntimeEnvironment.setQualifiers("+${size.qualifiers}")
            RuntimeEnvironment.setFontScale(font)
            val view = GameView(app)
            view.accessibilityLayer.forceActive = true
            screen = RectF(0f, 0f, size.width.toFloat(), size.height.toFloat())
            body("${size.id} at font ${font}x", view, Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888))
        }
        RuntimeEnvironment.setFontScale(1f)
    }

    /** The surface of the size being checked. */
    private var screen = RectF()

    private companion object {
        private const val DAY = 86_400_000L
        private const val HOUR = 3_600_000L

        /** Wall clocks 1, 5 and 23 hours before the next UTC day: every plural form and the widest number. */
        val DAILY_FOOTER_CLOCKS = listOf(20_000 * DAY + 23 * HOUR, 20_000 * DAY + 19 * HOUR, 20_000 * DAY + 1 * HOUR)

        val HEADER = setOf("achievement:back", "achievement:title", "achievement:count")
        /** Resource qualifiers of the 12 languages (docs/TOP100.md F1). */
        val LANGUAGES = listOf("de", "en", "fr", "es", "it", "pt-rBR", "pl", "nl", "tr", "ja", "ko", "zh-rCN")
        /** Smallest size of the loss reason on the game-over card, in sp at font scale 1. */
        const val MIN_REASON_SP = 12f
        /** Smallest label size of the game-over card's entries, in sp at font scale 1. */
        const val MIN_ENTRY_SP = 14f
    }

    /**
     * The HUD's five view controls (turn both ways, compass, tilt both ways) among [nodes]: all of them, or none where a
     * low window with large text has no room for them beside a hint, and they step aside while it shows.
     */
    private fun viewControls(size: String, nodes: List<UiNode>): Int {
        val keys = listOf("hud:rotate:left", "hud:compass", "hud:rotate:right", "hud:tilt:low", "hud:tilt:high")
        val shown = nodes.count { it.key in keys }
        assertTrue("$size: all view controls or none, got $shown", shown == 0 || shown == keys.size)
        if (shown == 0) assertTrue("$size: only a low window steps them aside", screen.height() < 1100f && screen.width() > screen.height())
        return shown
    }

    private fun check(size: String, what: String, nodes: List<UiNode>, expectedActions: Int, offScreenOk: (String) -> Boolean = { false }) {
        val density = app.resources.displayMetrics.density
        val actions = nodes.filter { it.actionable }
        assertTrue("$size, $what: ${actions.size} tappable elements, expected $expectedActions: ${actions.map { it.key }}", actions.size == expectedActions)
        val min = 48f * density - 0.5f
        for (n in actions) {
            assertTrue("$size, $what: ${n.key} is ${n.bounds.width() / density} × ${n.bounds.height() / density} dp", n.bounds.width() >= min && n.bounds.height() >= min)
        }
        for (n in nodes) {
            if (offScreenOk(n.key)) continue
            if (!screen.contains(n.bounds)) fail("$size, $what: ${n.key} ${n.bounds} leaves the screen $screen")
        }
        for (i in nodes.indices) for (j in i + 1 until nodes.size) {
            // The unlock toast is a short announcement over everything (docs/TOP100.md C2); it may cover the HUD.
            if (nodes[i].key == "toast" || nodes[j].key == "toast") continue
            // Tiles scrolled out of the achievements grid lie outside the screen, clipped, where they cannot collide.
            if (!RectF.intersects(nodes[i].bounds, screen) || !RectF.intersects(nodes[j].bounds, screen)) continue
            val a = nodes[i].bounds
            val b = nodes[j].bounds
            val overlap = RectF()
            if (overlap.setIntersect(a, b) && overlap.width() > 1f && overlap.height() > 1f) {
                fail("$size, $what: ${nodes[i].key} $a overlaps ${nodes[j].key} $b")
            }
        }
    }}
