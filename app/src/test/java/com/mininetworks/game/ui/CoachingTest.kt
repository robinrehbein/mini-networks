package com.mininetworks.game.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import com.mininetworks.game.R
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.Cell
import com.mininetworks.game.game.DailyChallenge
import com.mininetworks.game.game.GameMode
import com.mininetworks.game.game.Scenarios
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World
import com.mininetworks.game.ui.menu.LegendPanel
import com.mininetworks.game.ui.menu.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Just-in-time coaching: each one-time tip shows the first time its mechanic matters, once per install (also in a
 * later game), at most one per [Coaching.GAP_SECONDS], never in the tutorial or the daily challenge. And the HUD's
 * "?" button opens the legend straight from the map, with the clock stopped, and its back pill leads back to the map.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(DebugApi::class)
class CoachingTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
    private var clock = 0L

    private companion object {
        val LANGUAGES = listOf("de", "en", "fr", "es", "it", "pt-rBR", "pl", "nl", "tr", "ja", "ko", "zh-rCN")
        /** Longer than any tip stays on the hint line. */
        const val COACH_MAX_SECONDS = 12f
    }

    @Before
    fun setUp() {
        SettingsStore(app).tutorialSeen = true
    }

    private fun emptyWorld(daily: DailyChallenge? = null) = World(seed = 2L, spawnInitialNodes = false, daily = daily).also { w ->
        w.incidentsEnabled = false
        for (row in w.water) row.fill(false)
    }

    private fun World.cell(dx: Int, dy: Int) = Cell(unlocked.left + dx, unlocked.top + dy)

    /** A world in which [tip] is due (and only the clock-dependent pause tip needs the clock running). */
    private fun scene(tip: Coaching): World {
        val w = emptyWorld()
        fun at(dx: Int, dy: Int) = w.cell(dx, dy).let { it.x to it.y }
        when (tip) {
            Coaching.INCIDENT -> {
                val (ax, ay) = at(2, 2); val (bx, by) = at(8, 2)
                val pc = w.addClient(Device.PC, ax, ay)
                val mail = w.addServer(Service.MAIL, bx, by)
                assertTrue(w.connect(pc, mail, CableType.ISDN))
                w.announceExcavator(w.cables.single())
            }
            Coaching.PAUSE -> {
                val (ax, ay) = at(2, 2); val (bx, by) = at(8, 6)
                w.addServer(Service.CALL, bx, by)
                val phone = w.addClient(Device.PHONE, ax, ay)
                repeat(World.Tuning.MAX_PENDING) { phone.pending.addLast(Service.CALL) }
                phone.overload = 0.05f
            }
            Coaching.SERVER_QUEUE -> {
                val (sx, sy) = at(8, 4)
                val mail = w.addServer(Service.MAIL, sx, sy)
                val pcs = (0..2).map { i -> at(2, 1 + 3 * i).let { (x, y) -> w.addClient(Device.PC, x, y) } }
                pcs.forEach { assertTrue(w.connect(it, mail, CableType.ISDN)) }
                var t = 0f
                while (w.waitingAt(mail) < GameView.BUSY_HINT_WAITING && t < 60f) {
                    pcs.forEach { while (it.pending.size < 3) it.pending.addLast(Service.MAIL) }
                    w.update(0.05f)
                    t += 0.05f
                }
                assertTrue("requests queue at the server", w.waitingAt(mail) >= GameView.BUSY_HINT_WAITING)
            }
            Coaching.JAM -> {
                val (ax, ay) = at(2, 2); val (bx, by) = at(6, 2)
                val pc = w.addClient(Device.PC, ax, ay)
                val mail = w.addServer(Service.MAIL, bx, by)
                assertTrue(w.connect(pc, mail, CableType.ISDN))
                // A mail server that cannot keep up: three requests always wait (see ProblemBadgeTest).
                var t = 0f
                while (t < World.Tuning.JAM_SECONDS * 2) {
                    while (pc.pending.size < 3) pc.pending.addLast(Service.MAIL)
                    w.update(0.1f)
                    t += 0.1f
                }
                assertTrue(w.isJammed(pc))
            }
            Coaching.CABLE_UPGRADE -> {
                val (ax, ay) = at(2, 2); val (bx, by) = at(7, 2)
                val tv = w.addClient(Device.TV, ax, ay)
                val stream = w.addServer(Service.STREAMING, bx, by)
                assertTrue(w.connect(tv, stream, CableType.ISDN))
                tv.pending.addLast(Service.STREAMING)
            }
            Coaching.NEW_SERVICE -> {
                val (ax, ay) = at(2, 2); val (bx, by) = at(7, 2); val (cx, cy) = at(7, 7)
                val laptop = w.addClient(Device.LAPTOP, ax, ay)
                val mail = w.addServer(Service.MAIL, bx, by)
                w.addServer(Service.STREAMING, cx, cy)
                assertTrue(w.connect(laptop, mail, CableType.ISDN))
            }
            Coaching.RADIO_STOCK -> w.grant(0, extraAccessPoints = 1)
        }
        return w
    }

    private fun expected(tip: Coaching, w: World): String {
        val texts = Texts(app)
        val subject = tip.due(w)!!
        return InlineGlyphs.plain(
            when (tip) {
                Coaching.INCIDENT -> app.getString(R.string.coach_incident)
                Coaching.PAUSE -> app.getString(R.string.coach_pause)
                Coaching.SERVER_QUEUE -> app.getString(R.string.coach_server_queue, texts.node(subject as com.mininetworks.game.game.Node))
                Coaching.JAM -> app.getString(R.string.coach_jam, texts.node(subject as com.mininetworks.game.game.Node))
                Coaching.CABLE_UPGRADE -> app.getString(R.string.coach_cable_upgrade)
                Coaching.NEW_SERVICE -> Coaching.newService(subject).let { (n, s) -> app.getString(R.string.coach_new_service, texts.node(n), texts.service(s)) }
                Coaching.RADIO_STOCK -> app.getString(R.string.coach_radio_stock, texts.radio(subject as com.mininetworks.game.game.RadioType))
            },
        )
    }

    private fun seen(keys: Set<String>) =
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putStringSet("coaching_seen", keys).commit()

    private fun draw(view: GameView, w: World) = view.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 0f, style = "Iso")

    private fun tapAt(view: GameView, x: Float, y: Float) {
        clock += 400L
        view.injectTouch(MotionEvent.ACTION_DOWN, x, y, time = clock)
        view.injectTouch(MotionEvent.ACTION_UP, x, y, time = clock + 50L)
    }

    private fun tapCell(view: GameView, c: Cell) = view.activeRenderer.toScreen(c.center).let { tapAt(view, it.x, it.y) }

    private fun drag(view: GameView, a: com.mininetworks.game.game.Node, b: com.mininetworks.game.game.Node) {
        val p = view.activeRenderer.toScreen(a.center)
        val q = view.activeRenderer.toScreen(b.center)
        clock += 400L
        view.injectTouch(MotionEvent.ACTION_DOWN, p.x, p.y, time = clock)
        view.injectTouch(MotionEvent.ACTION_MOVE, (p.x + q.x) / 2f, (p.y + q.y) / 2f, time = clock + 50L)
        view.injectTouch(MotionEvent.ACTION_UP, q.x, q.y, time = clock + 100L)
    }

    private fun tapHud(view: GameView, id: String) {
        val r = view.hudTarget(id) ?: throw AssertionError("no HUD button $id")
        tapAt(view, r.centerX(), r.centerY())
    }

    /** Runs [seconds] of frames and returns every hint shown meanwhile. */
    private fun run(view: GameView, seconds: Float): List<String> {
        val shown = ArrayList<String>()
        repeat((seconds * 60).toInt()) {
            view.advance(1f / 60f)
            view.shownHint?.let { if (shown.lastOrNull() != it) shown += it }
        }
        return shown
    }

    /** A view on [w], paused in place unless the tip needs the clock (the pause tip waits while it stands). */
    private fun viewOn(w: World, tip: Coaching): GameView {
        val view = GameView(app)
        draw(view, w)
        if (tip != Coaching.PAUSE) {
            tapHud(view, "pause")
            assertTrue(view.pausedInPlace)
        }
        return view
    }

    @Test
    fun eachTipShowsOnceAndOnlyOncePerInstall() {
        for (tip in Coaching.entries) {
            // Only this tip is still open, so its trigger alone decides.
            seen(Coaching.entries.filter { it != tip }.map { it.key }.toSet())
            val first = scene(tip)
            val text = expected(tip, first)
            val view = viewOn(first, tip)
            val seconds = if (tip == Coaching.PAUSE) 1f else Coaching.GAP_SECONDS * 2
            var shown = run(view, seconds)
            if (tip == Coaching.PAUSE) {
                // The player does what the tip says, and it stays on the paused map for its full time.
                assertFalse("not read yet", tip.key in view.coachingShown)
                tapHud(view, "pause")
                shown = shown + run(view, COACH_MAX_SECONDS).filter { it != shown.lastOrNull() }
            }
            assertEquals("$tip shows once in its game: $shown", 1, shown.count { it == text })
            assertTrue("$tip is stored as shown", tip.key in SettingsStore(app).coachingSeen)
            assertTrue(tip.key in view.coachingShown)
            // A later game (a new view, as after a restart) with the same situation stays quiet.
            val again = scene(tip)
            val later = viewOn(again, tip)
            val laterShown = run(later, seconds)
            assertFalse("$tip never shows twice: $laterShown", text in laterShown)
            assertFalse(text in later.queuedHints)
        }
    }

    @Test
    fun atMostOneTipPerGap() {
        // A radio in stock and a jam at once: the jam first (it comes first), the radio only after the gap.
        val w = scene(Coaching.JAM).also { it.grant(0, extraAccessPoints = 1) }
        val jam = expected(Coaching.JAM, w)
        val radio = expected(Coaching.RADIO_STOCK, w)
        val view = viewOn(w, Coaching.JAM)
        val early = run(view, Coaching.GAP_SECONDS - 1f)
        assertTrue(early.toString(), jam in early)
        assertFalse("not within the gap: $early", radio in early)
        assertTrue(Coaching.JAM.key in view.coachingShown)
        assertFalse(Coaching.RADIO_STOCK.key in view.coachingShown)
        val late = run(view, 3f)
        assertTrue(late.toString(), radio in late)
    }

    @Test
    fun aTapAnswerOrAnErrorCutsATipShortAndItComesBack() {
        seen(Coaching.entries.filter { it != Coaching.RADIO_STOCK }.map { it.key }.toSet())
        val w = scene(Coaching.RADIO_STOCK)
        val pc = w.addClient(Device.PC, w.cell(2, 2).x, w.cell(2, 2).y)
        val mail = w.addServer(Service.MAIL, w.cell(8, 2).x, w.cell(8, 2).y)
        val tip = expected(Coaching.RADIO_STOCK, w)
        val view = viewOn(w, Coaching.RADIO_STOCK)
        run(view, 0.5f)
        assertEquals(tip, view.shownHint)
        // A device tap is answered at once, not after the tip.
        tapCell(view, pc.cell)
        val info = view.shownHint
        assertNotNull(info)
        assertFalse("the answer to the tap shows at once", info == tip)
        assertTrue(view.pendingHintTexts.isEmpty())
        assertFalse("cut short early: not read", Coaching.RADIO_STOCK.key in view.coachingShown)
        assertTrue("the tip comes back", tip in run(view, GameView.LONG_HINT_SECONDS + 1f))
        // A refused action cuts it short as well; it comes back after the error.
        w.grant(-w.budget)
        drag(view, pc, mail)
        assertTrue(view.shownHintIsError)
        assertFalse(Coaching.RADIO_STOCK.key in view.coachingShown)
        val after = run(view, GameView.ERROR_HINT_SECONDS + 1f)
        assertTrue(after.toString(), tip in after)
        run(view, COACH_MAX_SECONDS)
        assertTrue("read once it had its time", Coaching.RADIO_STOCK.key in view.coachingShown)
        assertTrue(Coaching.RADIO_STOCK.key in SettingsStore(app).coachingSeen)
    }

    @Test
    fun aTipOnScreenForMostOfItsTimeCountsAsRead() {
        seen(Coaching.entries.filter { it != Coaching.RADIO_STOCK }.map { it.key }.toSet())
        val w = scene(Coaching.RADIO_STOCK)
        val pc = w.addClient(Device.PC, w.cell(2, 2).x, w.cell(2, 2).y)
        val tip = expected(Coaching.RADIO_STOCK, w)
        val view = viewOn(w, Coaching.RADIO_STOCK)
        run(view, 0.3f)
        assertEquals(tip, view.shownHint)
        run(view, GameView.COACH_HINT_SECONDS * GameView.COACH_READ_SHARE + 0.5f)
        assertEquals(tip, view.shownHint)
        tapCell(view, pc.cell)
        assertTrue("cut short late: read", Coaching.RADIO_STOCK.key in view.coachingShown)
        assertFalse("and it does not come again", tip in run(view, Coaching.GAP_SECONDS + 1f))
    }

    @Test
    fun doingWhatATipSaysEndsItAndPlacingCoversItsTimer() {
        seen(Coaching.entries.filter { it != Coaching.RADIO_STOCK }.map { it.key }.toSet())
        val w = scene(Coaching.RADIO_STOCK)
        val tip = expected(Coaching.RADIO_STOCK, w)
        val view = viewOn(w, Coaching.RADIO_STOCK)
        run(view, 0.3f)
        assertEquals(tip, view.shownHint)
        // Armed for placing: the hint line shows the placing instruction, so the tip's time stands.
        tapHud(view, "radio:WLAN")
        assertTrue(view.placingArmed)
        run(view, COACH_MAX_SECONDS)
        assertFalse("the placing instruction covered it", Coaching.RADIO_STOCK.key in view.coachingShown)
        tapCell(view, w.cell(4, 4))
        assertFalse(view.placingArmed)
        assertTrue("placed: learned", Coaching.RADIO_STOCK.key in view.coachingShown)
        assertTrue(Coaching.RADIO_STOCK.key in SettingsStore(app).coachingSeen)
        assertFalse(tip in run(view, COACH_MAX_SECONDS))
    }

    @Test
    fun aTipWhoseSituationIsOverIsDroppedUnread() {
        seen(Coaching.entries.filter { it != Coaching.INCIDENT }.map { it.key }.toSet())
        val w = scene(Coaching.INCIDENT)
        val pc = w.nodes.first { it.kind == com.mininetworks.game.game.NodeKind.CLIENT }
        val view = viewOn(w, Coaching.INCIDENT)
        // An error holds the line, the tip waits; meanwhile the excavator is gone.
        w.grant(-w.budget)
        drag(view, pc, w.addServer(Service.MAIL, w.cell(2, 7).x, w.cell(2, 7).y))
        assertTrue(view.shownHintIsError)
        run(view, 0.5f)
        w.removeCable(w.cables.single())
        assertTrue(w.incidents.isEmpty())
        val shown = run(view, GameView.ERROR_HINT_SECONDS + 1f)
        assertFalse("no stale tip: $shown", shown.any { it == InlineGlyphs.plain(app.getString(R.string.coach_incident)) })
        assertFalse(Coaching.INCIDENT.key in view.coachingShown)
    }

    @Test
    fun aMenuOverATipKeepsItsTimeAndANewGameBringsItBack() {
        seen(Coaching.entries.filter { it != Coaching.RADIO_STOCK }.map { it.key }.toSet())
        val w = scene(Coaching.RADIO_STOCK)
        val tip = expected(Coaching.RADIO_STOCK, w)
        val view = viewOn(w, Coaching.RADIO_STOCK)
        run(view, 0.5f)
        assertEquals(tip, view.shownHint)
        tapHud(view, "help")
        assertEquals(Screen.LEGEND, view.currentScreen)
        run(view, COACH_MAX_SECONDS)
        assertFalse("the legend covered it", Coaching.RADIO_STOCK.key in view.coachingShown)
        view.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 0f, screen = null)
        val back = view.legendTarget(LegendPanel.BACK)!!
        tapAt(view, back.centerX(), back.centerY())
        assertEquals(Screen.PLAYING, view.currentScreen)
        assertEquals("back on the map, the tip is still there", tip, run(view, 0.1f).lastOrNull())
        // A new game before it was read: not stored, so it comes again the next time it is due.
        view.drawSnapshot(Canvas(bmp), scene(Coaching.RADIO_STOCK), bmp.width, bmp.height, time = 0f)
        assertFalse(Coaching.RADIO_STOCK.key in SettingsStore(app).coachingSeen)
        assertTrue(tip in run(view, Coaching.GAP_SECONDS + 1f))
    }

    @Test
    fun aBusyServerWaitsForItsTipInsteadOfTheShortLine() {
        seen(Coaching.entries.filter { it != Coaching.SERVER_QUEUE }.map { it.key }.toSet())
        val w = scene(Coaching.SERVER_QUEUE)
        val tip = expected(Coaching.SERVER_QUEUE, w)
        val view = viewOn(w, Coaching.SERVER_QUEUE)
        val shown = run(view, Coaching.GAP_SECONDS)
        assertEquals("only the tip, not the short line as well: $shown", listOf(tip), shown)
    }

    @Test
    fun creativeModeHasNoPressureOrNewServiceTips() {
        val w = World(seed = 2L, mode = GameMode.CREATIVE)
        for (tip in listOf(Coaching.PAUSE, Coaching.INCIDENT, Coaching.RADIO_STOCK, Coaching.NEW_SERVICE)) assertFalse("$tip", tip.appliesTo(w))
        seen(emptySet())
        val view = viewOn(w, Coaching.RADIO_STOCK)
        val shown = run(view, 3f)
        assertTrue("no tip at the start of a creative game: $shown", shown.none { it.startsWith("Tipp") })
    }

    @Test
    fun aNewServiceTipOnlyForADeviceOlderThanTheService() {
        // The streaming server stood before the laptop came: nothing new for it.
        val newer = emptyWorld()
        newer.addServer(Service.STREAMING, newer.cell(7, 7).x, newer.cell(7, 7).y)
        val laptop = newer.addClient(Device.LAPTOP, newer.cell(2, 2).x, newer.cell(2, 2).y)
        val mail = newer.addServer(Service.MAIL, newer.cell(7, 2).x, newer.cell(7, 2).y)
        assertTrue(newer.connect(laptop, mail, CableType.ISDN))
        assertEquals(Service.STREAMING, newer.firstUnreachableService(laptop))
        assertEquals(null, Coaching.NEW_SERVICE.due(newer))
        // A scenario that starts with streaming invented: never a new service, however old the device.
        val island = World(scenario = Scenarios.ISLAND, seed = 2L, spawnInitialNodes = false).also { w ->
            w.incidentsEnabled = false
            for (row in w.water) row.fill(false)
        }
        val tv = island.addClient(Device.LAPTOP, island.cell(2, 2).x, island.cell(2, 2).y)
        val m = island.addServer(Service.MAIL, island.cell(7, 2).x, island.cell(7, 2).y)
        island.addServer(Service.STREAMING, island.cell(7, 7).x, island.cell(7, 7).y)
        assertTrue(island.connect(tv, m, CableType.ISDN))
        assertEquals(Service.STREAMING, island.firstUnreachableService(tv))
        assertEquals(null, Coaching.NEW_SERVICE.due(island))
        // The scene of the tip itself: the laptop was there first.
        assertNotNull(Coaching.NEW_SERVICE.due(scene(Coaching.NEW_SERVICE)))
    }

    /**
     * Every tip keeps its advice: never cut with "…" in any language at 200 % text, on a 360 dp wide portrait phone
     * and on the lowest landscape phone.
     */
    @Test
    fun everyTipFitsAtLargeTextInEveryLanguage() {
        val cuts = ArrayList<String>()
        try {
            for (lang in LANGUAGES) for (size in listOf("w360dp-h800dp-port-xxhdpi" to (1080 to 2400), "w640dp-h360dp-land-xxhdpi" to (1920 to 1080))) {
                RuntimeEnvironment.setQualifiers("$lang-${size.first}")
                RuntimeEnvironment.setFontScale(2f)
                val big = Bitmap.createBitmap(size.second.first, size.second.second, Bitmap.Config.ARGB_8888)
                for (tip in Coaching.entries) {
                    seen(Coaching.entries.filter { it != tip }.map { it.key }.toSet())
                    val w = scene(tip)
                    val view = GameView(app)
                    view.accessibilityLayer.forceActive = true
                    view.drawSnapshot(Canvas(big), w, big.width, big.height, time = 0f, style = "Iso")
                    if (tip != Coaching.PAUSE) tapHud(view, "pause")
                    run(view, 0.5f)
                    view.drawCurrent(Canvas(big))
                    val hint = view.accessibilityLayer.nodes.singleOrNull { it.key == "hud:hint" }
                    if (hint == null) cuts += "$lang ${size.first} $tip: no hint" else if (hint.shortened) cuts += "$lang ${size.first} $tip: ${hint.text}"
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
            RuntimeEnvironment.setQualifiers("de")
        }
        assertTrue("tips cut with …:\n${cuts.joinToString("\n")}", cuts.isEmpty())
    }

    /**
     * A narrow portrait phone at 200 % text with radios in stock and every cable: the "?" leaves the controls' row,
     * stays a 48 dp target clear of every other HUD element, and opens the legend.
     */
    @Test
    fun theQuestionMarkStaysClearOnANarrowPortraitPhone() {
        try {
            RuntimeEnvironment.setQualifiers("de-w360dp-h800dp-port-xxhdpi")
            RuntimeEnvironment.setFontScale(2f)
            val w = World(scenario = Scenarios.FUTURE, seed = 2L, spawnInitialNodes = false).also { it.incidentsEnabled = false }
            w.grant(0, extraRouters = 2, extraAccessPoints = 2, extraCellTowers = 2)
            val big = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
            val view = GameView(app)
            view.accessibilityLayer.forceActive = true
            view.drawSnapshot(Canvas(big), w, big.width, big.height, time = 0f, style = "Iso")
            val help = view.hudTarget("help")!!
            val pause = view.hudTarget("pause")!!
            val density = app.resources.displayMetrics.density
            assertTrue("a 48 dp touch target", help.width() >= 48 * density - 0.5f && help.height() >= 48 * density - 0.5f)
            assertTrue("the \"?\" moved off the controls' row: $help vs $pause", help.bottom <= pause.top)
            val others = view.accessibilityLayer.nodes.filter { it.key.startsWith("hud:") && it.key != "hud:map" && it.bounds != help }
            assertTrue(others.isNotEmpty())
            for (o in others) assertFalse("\"?\" overlaps ${o.key} ${o.bounds}", RectF.intersects(help, o.bounds))
            // Nor does it touch a chip's price badge, which sticks out over the chip's top right corner: 8 dp at least.
            val br = view.chipBadgeRadius
            for (chip in view.accessibilityLayer.nodes.filter { view.chipBadges && it.key.startsWith("hud:cable:") }) {
                val c = chip.bounds
                val badge = RectF(c.right - 1.7f * br - 2 * density, c.top - 0.65f * br - 2 * density, c.right + 0.3f * br + 2 * density, c.top + 1.35f * br + 2 * density)
                val clear = RectF(badge).apply { inset(-8 * density + 0.5f, -8 * density + 0.5f) }
                assertFalse("\"?\" $help within 8 dp of the badge of ${chip.key} $badge", RectF.intersects(help, clear))
            }
            tapHud(view, "help")
            assertEquals(Screen.LEGEND, view.currentScreen)
        } finally {
            RuntimeEnvironment.setFontScale(1f)
            RuntimeEnvironment.setQualifiers("de")
        }
    }

    @Test
    fun noTipInTheTutorial() {
        SettingsStore(app).tutorialSeen = false
        val view = GameView(app)
        view.drawSnapshot(Canvas(bmp), view.currentWorld, bmp.width, bmp.height, time = 0f, screen = null)
        assertNotNull(view.currentTutorial)
        view.currentWorld.grant(0, extraAccessPoints = 1)
        run(view, 2f)
        assertTrue(view.coachingShown.isEmpty())
        assertTrue(SettingsStore(app).coachingSeen.isEmpty())
    }

    @Test
    fun noTipInTheDailyChallenge() {
        val w = emptyWorld(daily = DailyChallenge.at(0L)).also { it.grant(0, extraAccessPoints = 1) }
        val view = viewOn(w, Coaching.RADIO_STOCK)
        run(view, 2f)
        assertTrue(view.coachingShown.isEmpty())
    }

    @Test
    fun theQuestionMarkOpensTheLegendWithTheClockStopped() {
        val w = scene(Coaching.RADIO_STOCK)
        val view = GameView(app)
        draw(view, w)
        val help = view.hudTarget("help")
        assertNotNull("the HUD has a \"?\" button", help)
        val density = app.resources.displayMetrics.density
        assertTrue("a 48 dp touch target", help!!.width() >= 48 * density - 0.5f && help.height() >= 48 * density - 0.5f)
        tapHud(view, "help")
        assertEquals(Screen.LEGEND, view.currentScreen)
        val time = w.time
        run(view, 2f)
        assertEquals("the game stands while the legend is open", time, w.time, 0f)
        view.drawSnapshot(Canvas(bmp), w, bmp.width, bmp.height, time = 0f, screen = null)
        val back = view.legendTarget(LegendPanel.BACK)!!
        tapAt(view, back.centerX(), back.centerY())
        assertEquals("back leads to the map, not the pause menu", Screen.PLAYING, view.currentScreen)
        run(view, 1f)
        assertTrue("the game runs on", w.time > time)
    }
}
