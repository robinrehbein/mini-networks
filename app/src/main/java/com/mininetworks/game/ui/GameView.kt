package com.mininetworks.game.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import com.mininetworks.game.R
import com.mininetworks.game.audio.Sound
import com.mininetworks.game.audio.SoundPlayer
import com.mininetworks.game.data.GameSettings
import com.mininetworks.game.data.HighscoreStore
import com.mininetworks.game.data.SaveSlot
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.Bend
import com.mininetworks.game.game.Cable
import com.mininetworks.game.game.CableLayout
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.CableUpgradeError
import com.mininetworks.game.game.Cell
import com.mininetworks.game.game.Demand
import com.mininetworks.game.game.IncidentKind
import com.mininetworks.game.game.Incidents
import com.mininetworks.game.game.FixedStep
import com.mininetworks.game.game.GrowthRecorder
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.PlaceError
import com.mininetworks.game.game.RadioType
import com.mininetworks.game.game.RepairError
import com.mininetworks.game.game.RouteProblem
import com.mininetworks.game.game.Scenario
import com.mininetworks.game.game.Scenarios
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.ServerUpgradeError
import com.mininetworks.game.game.SoundCues
import com.mininetworks.game.game.Tutorial
import com.mininetworks.game.game.TutorialFocus
import com.mininetworks.game.game.Vec2
import com.mininetworks.game.game.WeekNews
import com.mininetworks.game.game.Wifi
import com.mininetworks.game.game.WifiUpgradeError
import com.mininetworks.game.game.Unlock
import com.mininetworks.game.game.World
import com.mininetworks.game.monetization.AdPlacement
import com.mininetworks.game.monetization.Entitlements
import com.mininetworks.game.monetization.Monetization
import com.mininetworks.game.monetization.MonetizationStore
import com.mininetworks.game.monetization.NoOpMonetization
import com.mininetworks.game.render.CableStyles
import com.mininetworks.game.render.Camera
import com.mininetworks.game.render.DragPreview
import com.mininetworks.game.render.FlatRenderer
import com.mininetworks.game.render.IncidentStyles
import com.mininetworks.game.render.IsoRenderer
import com.mininetworks.game.render.Renderer
import com.mininetworks.game.render.ServiceColors
import com.mininetworks.game.render.TouchTargets
import com.mininetworks.game.render.TwoFingerGesture
import com.mininetworks.game.render.ViewInsets
import com.mininetworks.game.render.fill
import com.mininetworks.game.render.shade
import com.mininetworks.game.ui.menu.DemoCity
import com.mininetworks.game.ui.menu.MenuAction
import com.mininetworks.game.ui.menu.MenuItem
import com.mininetworks.game.ui.menu.MenuPage
import com.mininetworks.game.ui.menu.MenuPanel
import com.mininetworks.game.ui.menu.MenuPicture
import com.mininetworks.game.ui.menu.SceneryCard
import com.mininetworks.game.ui.menu.SceneryPicker
import com.mininetworks.game.ui.menu.Screen
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Hosts the game loop, the HUD, the menus and touch input on a [SurfaceView].
 *
 * A dedicated game thread owns all game state ([World], renderers, HUD and menu state). Each frame it drains the input
 * queue, advances the simulation in fixed 1/60 s steps ([FixedStep], at most 5 per frame) and draws to the surface.
 * The UI thread only enqueues [Input]s, so the world is never touched concurrently.
 *
 * Screens ([Screen]): the app opens on the main menu (play, continue the autosave, settings) over a demo town; on the
 * very first launch it opens in the [Tutorial] instead, which can be skipped and replayed from the settings. The
 * tutorial runs as a game on [Screen.PLAYING] with [TutorialOverlay] on top; it is never saved and records no score.
 * "Play" opens the scenery picker ([SceneryPicker]): a scenery is playable once the packet goal of the one before it
 * is reached or it is bought ([monetization]). The simulation only runs while [Screen.PLAYING]. The game is saved
 * ([SaveSlot], written on the I/O thread) when the pause menu opens, when the player leaves to the main menu and when the activity pauses;
 * game over records the best score of its scenery ([HighscoreStore]) and deletes the save, then the camera glides to
 * the failed device before the result card shows.
 *
 * Monetization ([Monetization], docs/PLAN.md 5.1): never an ad while playing. Leaving a game-over card counts a
 * finished game, and [AdPolicy][com.mininetworks.game.monetization.AdPolicy] decides whether an interstitial shows
 * before the next screen. Rewarded videos are optional: "continue" on the game-over card (once per game, empties the
 * overload rings) and "+1 router" on the week reward screen; with "remove ads" both come without a video. The main
 * menu sells "remove ads", the scenery picker single sceneries and the pack, the settings reopen the consent form.
 * While a full-screen ad is open, touches and back are ignored and the activity pausing does not open the pause menu;
 * if its result never comes, the game gives up waiting after [AD_TIMEOUT_SECONDS] of running (not paused) time.
 *
 * Controls:
 *  - drag from a node to another node: lay a cable along the grid (L-shaped; the drag path picks which way it bends);
 *    the label shows the price and, for a device, the ping it would get or that the cable is too narrow
 *  - drag on empty ground or with two fingers: pan; pinch: zoom; turn two fingers: rotate the map around their midpoint
 *    (all at once); on release it eases to the nearest multiple of 90° unless "free rotation" is on in the settings;
 *    the compass button (only while the map is turned) turns it back to north; double tap on empty ground: fit the
 *    playable area
 *  - pick a cable technology in the bottom-left bar (ISDN, DSL, TV-Kabel, Glasfaser; the coin is the price per cell);
 *    picking one names its bandwidth, speed and price
 *  - tap a server: preview its next hardware tier and price; tap again to upgrade (tier 4 is a data center on 2×2 cells)
 *  - tap a cable: upgrade it to a better picked technology; otherwise the first tap selects it and a second tap removes
 *    it for a refund; a cable cut by an excavator is repaired instead (small fee)
 *  - tap a device: why its requests are stuck (no way, too narrow, ping too high, jam), or what it wants
 *  - "Router" button, then tap an empty cell: place a router (a tap, not the start of a pan or pinch; a cell that does
 *    not work says why); tap a router without cables twice to put it back; "WLAN" and "Mast" place won radios the same
 *    way and only show while some are in stock
 *  - tap an access point: next WLAN channel; hold it: switch it to 5 GHz (costs budget)
 *  - pause button: stops the clock in place, building goes on (like Mini Metro); menu button or back: pause menu
 *    (resume, settings, restart, main menu)
 *  - settings: sound, haptics, overview mode (flat instead of isometric), colorblind palette
 *  - at each week change the world pauses and [RewardDialog] shows two reward cards; tap one to pick it
 *    (the menu button stays tappable above the dialog; resuming returns to the choice)
 *
 * Sound ([SoundPlayer]): a pluck per delivery pitched by service, a click when a cable locks in, a soft warning when a
 * device starts to overload and a chime at each new week ([SoundCues] reads them from the world while playing).
 * Haptics: a tick when a dragged cable snaps onto a target node and a pulse when it is laid.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    /** Events handed from the UI thread to the game thread. */
    private sealed interface Input {
        /**
         * One touch event. [pointers] holds x, y pairs of every finger that stays down after this event
         * (a lifting finger is left out); [time] is the event time in milliseconds.
         */
        class Touch(val action: Int, val x: Float, val y: Float, val pointers: FloatArray, val time: Long) : Input
        data class Resize(val width: Int, val height: Int) : Input
        data object Back : Input
        /** A full-screen ad for [placement] closed; [earned] is true if its reward was earned. */
        data class AdResult(val placement: AdPlacement, val earned: Boolean) : Input
        /** The activity was recreated, see [restoreState]. */
        data class Restore(val inGame: Boolean, val inTutorial: Boolean) : Input
        /** New safe-area insets from the window, see [onApplyWindowInsets]. */
        data class Safe(val insets: ViewInsets) : Input
        /** An accessibility service activated the element [key] (a double tap in TalkBack), see [CanvasAccessibility]. */
        data class Activate(val key: String) : Input
        /** An accessibility service moved its focus onto the element [key]; a scrolled-away scenery card scrolls in. */
        data class Reveal(val key: String) : Input
    }

    // ---------------------------------------------------------------- shared between UI and game thread

    private val inputs = ConcurrentLinkedQueue<Input>()
    private val surfaceLock = ReentrantLock()
    private val surfaceAvailable = surfaceLock.newCondition()
    private var hasSurface = false // guarded by surfaceLock
    @Volatile private var running = false
    private var loop: Thread? = null // UI thread only

    // ---------------------------------------------------------------- game thread only (or UI thread while stopped)

    private val texts = Texts(context)
    // Stores are created and first read on the game thread ([ensureLoaded]), never on the UI thread (docs/TOP100.md A3).
    private val saves = SaveSlot { context.filesDir }
    private val settingsStore by lazy { SettingsStore(context) }
    private val highscores by lazy { HighscoreStore(context) }
    private var settings = GameSettings()
    private val sounds = SoundPlayer(context)
    private val soundCues = SoundCues()
    private var hasSave = false
    /** True once [ensureLoaded] read the settings and looked for a save. */
    private var loaded = false

    /** Called on the UI thread when back is pressed on the main menu. */
    var onExit: (() -> Unit)? = null

    /**
     * Called on the UI thread whenever it changes whether the game has a use for the back gesture: true in a game and
     * every menu below the main menu, false on the main menu, where back leaves the app. The activity registers its
     * back callback only while true, so the system shows its predictive back-to-home animation on the main menu
     * (docs/TOP100.md A5).
     */
    var onBackHandlingChanged: ((Boolean) -> Unit)? = null
    /** The value last handed to [onBackHandlingChanged]; game thread only. */
    private var publishedBackHandling: Boolean? = null

    /** Display cutout (and similar) the HUD keeps clear of, in pixels; game thread only (docs/TOP100.md A5). */
    private var safeInsets = ViewInsets.NONE

    /** Ads and purchases; [NoOpMonetization] unless the activity sets the Play implementation. Read on the game thread. */
    @Volatile var monetization: Monetization = NoOpMonetization
    private val monetizationStore by lazy { MonetizationStore(context) }
    private val adPolicy by lazy { monetizationStore.loadPolicy() }
    /** The full-screen ad that is open right now; its [Input.AdResult] is awaited. */
    private var pendingAd: AdPlacement? = null
    /** What leaving the game-over card does once the interstitial closed. */
    private var afterInterstitial: MenuAction? = null
    private val mainThread = Handler(Looper.getMainLooper())

    private var screen = Screen.MAIN_MENU
    /** Where the settings screen returns to. */
    private var settingsReturn = Screen.MAIN_MENU
    /** True while [world] is a real game that is not over (not the demo town). */
    private var gameInProgress = false
    /**
     * After game over the camera first glides to the failed device; the game-over card follows at this [animTime]
     * (or on the next tap). Null otherwise.
     */
    private var failFocusUntil: Float? = null
    /** A finger went down during the game-over focus; only its release skips the focus, not a gesture from before. */
    private var focusSkipArmed = false
    private var newBest = false
    private val menuPanel = MenuPanel(context)
    private var pressedAction: MenuAction? = null
    private val sceneryPicker = SceneryPicker(context)
    /** Scenery id or [SceneryPicker.BACK] under the finger on the picker. */
    private var pressedScenery: String? = null
    /** Why the last tapped scenery is locked, shown under the cards. */
    private var sceneryHint: String? = null
    /** Sideways drag on a scrolling row of scenery cards. */
    private var sceneryDownX = 0f
    private var sceneryLastX = 0f
    private var sceneryScrolling = false

    private var world = DemoCity.build()
    /** The running tutorial; its world is [world]. Null in a normal game and in the menus. */
    private var tutorial: Tutorial? = null
    private val tutorialOverlay = TutorialOverlay(context)
    /** True from a touch-down on the tutorial bubble until the finger lifts; [tutorialPressed] is the entry under it. */
    private var tutorialGesture = false
    private var tutorialPressed: String? = null
    private val iso = IsoRenderer()
    private val flat = FlatRenderer()
    private val renderers: List<Renderer> = listOf(iso, flat)
    private var renderer: Renderer = iso
    private val clock = FixedStep()
    private var useHardwareCanvas = true
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /** What the next tap on an empty cell places: [NodeKind.ROUTER] or a radio kind; null while not placing. */
    private var placing: NodeKind? = null
    private var cableType = CableType.ISDN
    private var animTime = 0f

    /**
     * A cable, server or router tapped once and highlighted until [selectionUntil]: a second tap on it removes the
     * cable, upgrades the server or puts the router back, so a stray tap never costs anything.
     */
    private var selection: Any? = null
    private var selectionUntil = 0f
    /** True while the player stopped the clock with the pause button; the map stays interactive. */
    private var userPaused = false
    /** Running time spent waiting for [pendingAd]'s result since it was asked for or the activity last resumed. */
    private var pendingAdSeconds = 0f
    /** Set once this game pointed out a server that cannot keep up. */
    private var busyHintShown = false
    /** Hints shown one after the other once the screen is free, e.g. what a new week's services need. */
    private val hintQueue = ArrayDeque<String>()
    /** The week news already turned into hints. */
    private var hintedNews: WeekNews? = null

    /** Short feedback above the bottom bar, e.g. why a server tap did not upgrade; shown until [hintUntil]. */
    private var hint: String? = null
    private var hintUntil = 0f

    private var dragFrom: Node? = null
    private var dragEnd: Vec2? = null
    /** Pointer of the current drag in screen pixels, used to pick the target node in screen space. */
    private var dragEndScreen: Vec2? = null
    /** The node the current drag snapped to, for the haptic tick. */
    private var snapTarget: Node? = null
    /** Pointer samples of the current drag in world space; they decide which way the cable bends. */
    private val dragTrail = ArrayList<Vec2>()
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    /** Access point under a finger that has not moved yet; holding it for [LONG_PRESS_MS] switches it to 5 GHz. */
    private var holdAp: Node? = null
    private var holdStart = 0f
    /** True once a hold fired, so lifting the finger does nothing more. */
    private var holdFired = false
    /** True from a touch-down on the reward dialog until the finger lifts, so that gesture never reaches the map. */
    private var gestureConsumed = false
    private var pressedCard: Int? = null
    private val rewardDialog = RewardDialog(context)
    /** The network's growth during the running game, replayed as a time-lapse on the game-over card (B3). */
    private val growth = GrowthRecorder()
    private val recap by lazy { GrowthRecap(density) { f -> context.getString(R.string.hud_date, f.year, f.week) } }
    /** [animTime] when the game-over card appeared; the time-lapse runs from there. */
    private var gameOverAt = 0f
    private val confetti by lazy { Confetti(density) }
    /** [animTime] when the last week change was celebrated with confetti, and that week; null for none. */
    private var celebrateAt: Float? = null
    private var celebratedWeek = -1

    /** True from a second finger touching down (or a double tap) until all fingers are up: only the camera moves. */
    private var cameraGesture = false
    private val pinch = TwoFingerGesture()
    /** True while a one-finger gesture that started on empty ground may pan the map. */
    private var panArmed = false
    /** One-finger pan: last pointer position, null until the finger leaves the tap slop. */
    private var panFrom: Vec2? = null
    /** Time and place of the last tap that hit nothing, to detect a double tap. */
    private var emptyTapTime: Long? = null
    private var emptyTapX = 0f
    private var emptyTapY = 0f
    /** The unlocked area the cameras were last framed for; a change means the map grew. */
    private var framedArea = world.unlocked

    /** Node and cable counts the cameras last checked; a change may put something new outside a portrait framing. */
    private var framedNodes = -1
    private var framedCables = -1
    private var growthHintPending = false

    private val density = resources.displayMetrics.density
    /** Text sizes that follow the system font size (docs/TOP100.md A7). */
    private val textScale = TextScale(resources.displayMetrics)
    private val hudText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF262B33.toInt(); typeface = Typeface.DEFAULT_BOLD; textSize = textScale.px(16f) }
    private val hudSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5B6674.toInt(); textSize = textScale.px(13f) }
    private val btnFill = fill(0xE6FFFFFF.toInt())
    private val btnActive = fill(0xFF262B33.toInt())
    private val holdRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val btnText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = textScale.px(14f) }
    private val barBg = fill(0x33262B33)
    private val swatch = fill(0)
    private val barFg = fill(0xFF262B33.toInt())
    private val bigText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; color = 0xFF262B33.toInt(); textSize = textScale.px(15f) }
    private val incidentText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = textScale.px(14f) }
    private val iconInk = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 3 * density }
    private val coinFill = fill(COIN_COLOR)
    private val coinText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = textScale.px(11f); color = 0xFF5A4300.toInt() }
    private val pinFill = fill(0)
    private val pinText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = textScale.px(12f); color = 0xFFFFFFFF.toInt() }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val selectionPath = android.graphics.Path()
    private val compassPaint = fill(0)

    private data class Button(val id: String, val rect: RectF)
    private val buttons = mutableListOf<Button>()
    /** The HUD's texts and buttons as accessibility elements, collected while drawing it. */
    private val hudNodes = ArrayList<UiNode>()
    private val accessibility = CanvasAccessibility(this, activate = { inputs.add(Input.Activate(it)) }, onFocus = { inputs.add(Input.Reveal(it)) })

    init {
        holder.addCallback(this)
        renderers.forEach { it.density = density }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun getAccessibilityNodeProvider(): android.view.accessibility.AccessibilityNodeProvider = accessibility

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = accessibility.onHover(event) || super.dispatchHoverEvent(event)

    /**
     * Hands the elements of the frame just drawn to accessibility services (docs/TOP100.md A7), only while one listens.
     * A menu card, the scenery picker and the reward choice are modal: only their elements (and the menu button during
     * the reward choice) are offered. In a game the map is one element that says what is on it; building on the map
     * needs direct touch.
     */
    private fun publishAccessibility() {
        if (!accessibility.active) return
        val nodes = ArrayList<UiNode>()
        val page = menuPage()
        when {
            page != null -> nodes += menuPanel.nodes
            screen == Screen.SCENERIES -> nodes += sceneryPicker.nodes
            screen == Screen.PLAYING && world.rewardOffer != null -> {
                nodes += rewardDialog.nodes
                nodes += hudNodes.filter { it.key == "hud:menu" }
            }
            screen == Screen.PLAYING -> {
                nodes += UiNode(
                    "hud:map", RectF(0f, 0f, surfaceWidth.toFloat(), surfaceHeight.toFloat()),
                    context.getString(R.string.a11y_map, world.nodes.size, world.cables.size), UiNode.Kind.TEXT,
                )
                tutorial?.let { nodes += tutorialOverlay.nodes }
                nodes += hudNodes
            }
        }
        accessibility.update(nodes)
    }

    /** Does what a tap on the accessibility element [key] would do, if it is still there. */
    private fun activate(key: String) {
        if (pendingAd != null || failFocusUntil != null) return
        val id = key.substringAfter(':')
        when (key.substringBefore(':')) {
            "menu" -> {
                val action = MenuAction.entries.firstOrNull { it.name == id } ?: return
                if (menuPage() == null || menuPanel.targetOf(action) == null) return
                click()
                onMenuAction(action)
            }
            "hud" -> {
                if (screen != Screen.PLAYING || buttons.none { it.id == id }) return
                if (world.rewardOffer != null && id != "menu") return
                onButton(id)
            }
            "scenery" -> if (screen == Screen.SCENERIES && sceneryPicker.targetOf(id) != null) {
                click()
                chooseScenery(id)
            }
            "reward" -> if (screen == Screen.PLAYING && world.rewardOffer != null) {
                if (id == "bonus") takeBonusRouter() else id.toIntOrNull()?.let { world.chooseReward(it) }
            }
            "tutorial" -> if (screen == Screen.PLAYING && tutorial != null && tutorialOverlay.targetOf(id) != null) {
                click()
                onTutorialButton(id)
            }
        }
    }

    /**
     * Reads the settings and looks for an autosave, once, before the first frame; on the game thread (or a test's thread
     * while the game thread is not running), so the UI thread never waits for the disk. The first start opens in the
     * tutorial.
     */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        applySettings(settingsStore.load())
        hasSave = saves.exists()
        if (!settingsStore.tutorialSeen && !hasSave) startTutorial()
    }

    // ---------------------------------------------------------------- lifecycle (UI thread)

    /** Starts the game thread; call from `Activity.onResume`. */
    fun resume() {
        if (loop != null) return
        // Coming back from a full-screen ad: its result normally arrives right away; the timeout counts from here.
        pendingAdSeconds = 0f
        sounds.open()
        running = true
        loop = thread(name = "GameLoop") { runLoop() }
    }

    /**
     * Stops the game thread, opens the pause menu and saves the game; call from `Activity.onPause`.
     *
     * Joining the game thread means that, in the rare case it is inside [ensureLoaded] or [continueGame] waiting on a
     * `GameIo.call`, this waits for that one read (docs/TOP100.md A3). Accepted: the thread must be stopped before the
     * snapshot is taken; the UI thread itself never touches the disk.
     */
    fun pause() {
        loop?.let { t ->
            running = false
            surfaceLock.withLock { surfaceAvailable.signalAll() }
            joinQuietly(t)
            loop = null
        }
        sounds.close()
        // Safe: the game thread has ended.
        if (failFocusUntil != null) showGameOverCard()
        // A gesture that started before the pause never gets its release; drop it with everything it armed.
        endDrag()
        tutorialGesture = false
        tutorialPressed = null
        pressedCard = null
        gestureConsumed = false
        cameraGesture = false
        stopPinch()
        // A full-screen ad pauses the activity; the game stays where it was (the world waits for the ad's result).
        if (screen == Screen.PLAYING && pendingAd == null) screen = Screen.PAUSED
        autosave()
    }

    /** What [restoreState] needs after the activity was recreated; call from `onSaveInstanceState`. */
    fun saveState(out: android.os.Bundle) {
        out.putBoolean(STATE_IN_GAME, gameInProgress && tutorial == null)
        out.putBoolean(STATE_IN_TUTORIAL, tutorial != null && screen != Screen.MAIN_MENU)
    }

    /**
     * After the activity was recreated (the process may have been gone): a game in progress continues from its
     * autosave in the pause menu, a running tutorial starts again. Call from `onCreate` before [resume].
     */
    fun restoreState(saved: android.os.Bundle) {
        check(loop == null) { "game loop is running" }
        // Loading the save is disk work: the game thread does it before its first frame.
        inputs.add(Input.Restore(saved.getBoolean(STATE_IN_GAME), saved.getBoolean(STATE_IN_TUTORIAL)))
    }

    private fun restore(r: Input.Restore) {
        when {
            r.inGame && hasSave -> {
                continueGame()
                if (gameInProgress) screen = Screen.PAUSED
            }
            r.inTutorial -> startTutorial()
        }
    }

    /**
     * The window runs edge to edge: the HUD keeps clear of a display cutout (notch, punch hole) on any side. System bars
     * are hidden while playing, so they take no room; when they peek in they overlay the game for a moment.
     */
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        inputs.add(Input.Safe(safeInsetsOf(insets)))
        return super.onApplyWindowInsets(insets)
    }

    /** Handles the back key; on the main menu it calls [onExit]. */
    fun back() {
        inputs.add(Input.Back)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        inputs.add(Input.Resize(width, height))
        surfaceLock.withLock {
            hasSurface = true
            surfaceAvailable.signalAll()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Blocks until the game thread has finished drawing, so it never touches a released surface.
        surfaceLock.withLock { hasSurface = false }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val lifting = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        val pointers = FloatArray(2 * (e.pointerCount - if (lifting >= 0) 1 else 0))
        var k = 0
        for (i in 0 until e.pointerCount) {
            if (i == lifting) continue
            pointers[k++] = e.getX(i)
            pointers[k++] = e.getY(i)
        }
        inputs.add(Input.Touch(e.actionMasked, e.x, e.y, pointers, e.eventTime))
        return true
    }

    private fun joinQuietly(t: Thread) {
        var interrupted = false
        while (t.isAlive) {
            try {
                t.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    // ---------------------------------------------------------------- game thread

    private fun runLoop() {
        var lastNanos = 0L
        clock.reset()
        while (running) {
            if (awaitSurface()) lastNanos = 0L
            if (!running) break
            val now = System.nanoTime()
            val frameSeconds = if (lastNanos == 0L) 0f else (now - lastNanos) / 1e9f
            lastNanos = now
            tick(frameSeconds)
            surfaceLock.withLock { if (hasSurface) render() }
            val spent = System.nanoTime() - now
            if (spent < MIN_FRAME_NANOS) Thread.sleep((MIN_FRAME_NANOS - spent) / 1_000_000L)
        }
    }

    /** Blocks until a surface exists or the loop is stopped. Returns true if it had to wait (frame timing restarts). */
    private fun awaitSurface(): Boolean = surfaceLock.withLock {
        var waited = false
        while (running && !hasSurface) {
            surfaceAvailable.awaitUninterruptibly()
            waited = true
        }
        if (waited) clock.reset()
        waited
    }

    /** One loop iteration: apply queued input, then advance the simulation in fixed steps. */
    private fun tick(frameSeconds: Float) {
        ensureLoaded()
        while (true) handle(inputs.poll() ?: break)
        publishBackHandling()
        val animStep = frameSeconds.coerceAtMost(MAX_ANIM_STEP)
        animTime += animStep
        checkHold()
        checkPendingAd(frameSeconds)
        if (screen == Screen.PLAYING) {
            if (userPaused) clock.reset() else clock.advance(frameSeconds) { world.update(it) }
            tutorial?.update()
            if (tutorial == null && gameInProgress) growth.sample(world)
            world.rewardOffer?.let {
                if (tutorial == null && it.week != celebratedWeek) {
                    celebratedWeek = it.week
                    celebrateAt = animTime
                }
            }
            val now = (animTime * 1000).toLong()
            for (cue in soundCues.poll(world)) sounds.play(cue, now)
            if (tutorial == null) {
                newsHints()
                busyServerHint()
            }
        } else {
            clock.reset()
        }
        checkGameOver()
        failFocusUntil?.let { if (animTime >= it) showGameOverCard() }
        followArea()
        if (selection != null && animTime >= selectionUntil) selection = null
        if (screen == Screen.PLAYING && animTime >= hintUntil && world.rewardOffer == null) hintQueue.removeFirstOrNull()?.let { showHint(it, LONG_HINT_SECONDS) }
        renderer.stepCamera(animStep, world)
    }

    /**
     * When the map grew: widen every style's zoom range, follow the new area, and say so once the reward is picked.
     * When nodes or cables came or went: keep them in a portrait framing ([Renderer.onContentChanged]).
     */
    private fun followArea() {
        if (world.unlocked != framedArea) {
            framedArea = world.unlocked
            renderers.forEach { it.onAreaChanged(world) }
            growthHintPending = true
        }
        if (world.nodes.size != framedNodes || world.cables.size != framedCables) {
            framedNodes = world.nodes.size
            framedCables = world.cables.size
            renderers.forEach { it.onContentChanged(world) }
        }
        if (growthHintPending && world.rewardOffer == null) {
            growthHintPending = false
            hintQueue.addFirst(context.getString(R.string.hint_map_grew))
        }
    }

    /**
     * A week that brought servers or radios queues what they need: the bandwidth and ping of a new service (so
     * "Streaming needs bandwidth 3" is said before ISDN fails it), and that radios only come as week rewards.
     */
    private fun newsHints() {
        val news = world.lastNews ?: return
        if (news === hintedNews) return
        hintedNews = news
        for (s in news.servers) {
            val ping = s.maxPingMs
            if (ping != null) hintQueue += context.getString(R.string.hint_service_needs_ping, texts.service(s), s.bandwidth, ping)
            else if (s.bandwidth > 1) hintQueue += context.getString(R.string.hint_service_needs, texts.service(s), s.bandwidth)
        }
        for (r in news.radios) if (world.radiosAvailable(r) == 0) hintQueue += context.getString(R.string.hint_radio_reward, texts.radio(r))
    }

    /** The first time in a game that requests queue at a server, say that tapping it upgrades it. */
    private fun busyServerHint() {
        if (busyHintShown || world.rewardOffer != null) return
        val busy = world.nodes.firstOrNull { it.kind == NodeKind.SERVER && world.waitingAt(it) >= BUSY_HINT_WAITING } ?: return
        busyHintShown = true
        hintQueue.addFirst(context.getString(R.string.hint_server_busy, texts.node(busy)))
    }

    /**
     * Gives up on a full-screen ad whose result never came (a missed SDK callback), so the game-over card or the week
     * screen do not stay locked: after [AD_TIMEOUT_SECONDS] of running time it counts as closed without a reward.
     */
    private fun checkPendingAd(frameSeconds: Float) {
        val ad = pendingAd
        if (ad == null) {
            pendingAdSeconds = 0f
            return
        }
        pendingAdSeconds += frameSeconds
        if (pendingAdSeconds >= AD_TIMEOUT_SECONDS) onAdResult(Input.AdResult(ad, earned = false))
    }

    private fun render() {
        var canvas: Canvas? = null
        if (useHardwareCanvas) {
            canvas = try {
                holder.lockHardwareCanvas()
            } catch (_: RuntimeException) {
                useHardwareCanvas = false
                null
            }
        }
        canvas = canvas ?: holder.lockCanvas() ?: return
        try {
            drawFrame(canvas)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawFrame(canvas: Canvas) {
        val playing = screen == Screen.PLAYING
        renderer.draw(canvas, world, if (playing) dragPreview() else null, animTime)
        if (playing) {
            drawSelection(canvas)
            drawIncidentPins(canvas)
            drawHoldProgress(canvas)
        }
        if (hudVisible) drawHud(canvas) else hudNodes.clear()
        if (playing) tutorial?.let {
            tutorialOverlay.place(safeInsets.left + 16 * density, tutorialTop(), tutorialBottom())
            tutorialOverlay.draw(canvas, it, tutorialFocus(it), renderer, world, ::hudTarget, surfaceWidth, animTime, tutorialPressed)
        }
        if (playing) world.rewardOffer?.let {
            val side = safeInsets.right + 16 * density + buttonHeight + 8 * density
            rewardDialog.draw(canvas, world, it, surfaceWidth, surfaceHeight, animTime, pressedCard, bonusLabel(), video = !monetization.adsRemoved, side = side)
            celebrateAt?.let { at -> confetti.draw(canvas, surfaceWidth, surfaceHeight, animTime - at, celebratedWeek) }
            // The menu stays reachable during the reward choice, so its button is drawn above the dimmed map.
            buttons.firstOrNull { b -> b.id == "menu" }?.let { b -> drawIconButton(canvas, b.rect, b.id, active = false) }
        }
        menuPage()?.let { menuPanel.draw(canvas, it, surfaceWidth, surfaceHeight, pressedAction, safeInsets) }
        if (screen == Screen.SCENERIES) {
            sceneryPicker.draw(
                canvas, context.getString(R.string.scenery_title), context.getString(R.string.menu_back), sceneryCards(),
                sceneryHint, surfaceWidth, surfaceHeight, pressedScenery, packLabel(), safeInsets,
            )
        }
        publishAccessibility()
    }

    private fun drawHudButton(canvas: Canvas, r: RectF, label: String, active: Boolean) {
        val h = r.height()
        canvas.drawRoundRect(r, h / 2, h / 2, if (active) btnActive else btnFill)
        btnText.color = if (active) 0xFFFFFFFF.toInt() else 0xFF262B33.toInt()
        canvas.drawText(label, r.centerX(), r.centerY() + btnText.textSize * 0.35f, btnText)
    }

    /** The HUD shows under the in-game menus, not under the main menu. */
    private val hudVisible
        get() = when (screen) {
            Screen.MAIN_MENU, Screen.SCENERIES -> false
            Screen.SETTINGS -> settingsReturn != Screen.MAIN_MENU
            else -> true
        }

    /**
     * Draws one frame of [snapshotWorld] at the given size into [canvas], for tests. [screen] is set first unless null;
     * a world given with [Screen.PLAYING] counts as a game in progress. Only valid while the game thread is not running.
     */
    internal fun drawSnapshot(
        canvas: Canvas,
        snapshotWorld: World,
        width: Int,
        height: Int,
        time: Float,
        style: String? = null,
        screen: Screen? = Screen.PLAYING,
    ) {
        check(loop == null) { "game loop is running" }
        ensureLoaded()
        val changed = world !== snapshotWorld || width != surfaceWidth || height != surfaceHeight
        if (world !== snapshotWorld) {
            world = snapshotWorld
            tutorial = null
            gameInProgress = screen == Screen.PLAYING
            hintedNews = world.lastNews
        }
        if (screen != null) this.screen = screen
        if (style != null) renderer = renderers.firstOrNull { it.name == style } ?: throw IllegalArgumentException("unknown style $style")
        animTime = time
        surfaceWidth = width
        surfaceHeight = height
        if (changed) layoutRenderers()
        checkGameOver()
        drawFrame(canvas)
    }

    /** Draws the current state as it is (no resize, no screen change), for tests. */
    internal fun drawCurrent(canvas: Canvas) {
        check(loop == null) { "game loop is running" }
        ensureLoaded()
        drawFrame(canvas)
    }

    /** Runs one loop iteration without the game thread, for tests. */
    internal fun advance(frameSeconds: Float) {
        check(loop == null) { "game loop is running" }
        tick(frameSeconds)
    }

    /** The active style, for tests. */
    internal val activeRenderer: Renderer get() = renderer

    /** The screen on top, for tests. */
    internal val currentScreen: Screen get() = run { ensureLoaded(); screen }

    /** The world shown right now, for tests. */
    internal val currentWorld: World get() = run { ensureLoaded(); world }

    /** The running tutorial, for tests. */
    internal val currentTutorial: Tutorial? get() = run { ensureLoaded(); tutorial }

    /** Screen rectangle of a tutorial bubble button ([TutorialOverlay.SKIP] ...) in the last drawn frame, for tests. */
    internal fun tutorialTarget(id: String): RectF? = if (tutorial != null) tutorialOverlay.targetOf(id) else null

    /** Title and text of the tutorial bubble right now, or null outside the tutorial, for tests. */
    internal fun tutorialText(): String? = tutorial?.let { tutorialOverlay.text(it, tutorialFocus(it)) }

    /** The recorded growth of the running game, for tests (a screenshot records a scripted game with it). */
    internal val growthRecorder: GrowthRecorder get() = growth

    /** True while week-change confetti is falling, for tests. */
    internal val celebrating: Boolean get() = celebrateAt?.let { confetti.running(animTime - it) } == true

    /** The cable technology picked in the HUD, for tests. */
    internal val pickedCable: CableType get() = cableType

    /** Screen rectangle of an enabled menu entry in the last drawn frame, for tests. */
    internal fun menuTarget(action: MenuAction): RectF? = menuPanel.targetOf(action)

    /** Screen rectangle of the extra-router pill on the week reward screen in the last drawn frame, for tests. */
    internal fun bonusTarget(): RectF? = if (world.rewardOffer != null) rewardDialog.bonusTarget() else null

    /** The full-screen ad whose result is awaited, for tests. */
    internal val awaitedAd: AdPlacement? get() = pendingAd

    /** Hands the result of a full-screen ad to the game thread, as the monetization callbacks do. */
    internal fun adClosed(placement: AdPlacement, earned: Boolean) {
        inputs.add(Input.AdResult(placement, earned))
    }

    /** Screen rectangle of a scenery card (or [SceneryPicker.BACK], [SceneryPicker.PACK]) in the last drawn picker, for tests. */
    internal fun sceneryTarget(id: String): RectF? = if (screen == Screen.SCENERIES) sceneryPicker.targetOf(id) else null

    /** Screen rectangle of the HUD button [id] ("menu", "pause", "router", "radio:…", "cable:…") in the last drawn frame, for tests. */
    internal fun hudTarget(id: String): RectF? = buttons.firstOrNull { it.id == id }?.rect

    /** The accessibility layer, for tests; set [CanvasAccessibility.forceActive] to collect elements without a service. */
    internal val accessibilityLayer: CanvasAccessibility get() = accessibility

    /** The last sounds played (only while sound is on) with their rate, newest last, for tests. */
    internal val playedSounds: List<Pair<Sound, Float>> get() = sounds.played

    /** The text lines of the menu card on top (game over, pause …), empty without one, for tests. */
    internal val menuLines: List<String> get() = menuPage()?.lines ?: emptyList()

    /** Why the last tapped scenery is locked (under the picker's cards), or null, for tests. */
    internal val shownSceneryHint: String? get() = sceneryHint

    /** The hint line above the bottom bar right now, or null, for tests. */
    internal val shownHint: String? get() = hint?.takeIf { animTime < hintUntil }

    /** True while the clock is stopped in place by the pause button, for tests. */
    internal val pausedInPlace: Boolean get() = userPaused

    /** Number of haptic pulses sent (only counted while haptics are on), for tests. */
    internal var hapticPulses = 0
        private set

    /**
     * Feeds one touch event straight to the input handling, for tests; [pointers] as in [Input.Touch].
     * Only valid while the game thread is not running.
     */
    internal fun injectTouch(action: Int, x: Float, y: Float, pointers: FloatArray = floatArrayOf(x, y), time: Long = 0L) {
        check(loop == null) { "game loop is running" }
        ensureLoaded()
        handle(Input.Touch(action, x, y, pointers, time))
    }

    private fun dragPreview(): DragPreview? {
        if (holdAp != null) return null
        val from = dragFrom ?: return null
        val end = dragEnd ?: return null
        val target = dragEndScreen?.let { pickNode(it.x, it.y, except = from) }
        val toCell = target?.cell ?: Cell(floor(end.x).toInt(), floor(end.y).toInt())
        val bend = dragBend(from.cell, toCell)
        val layout = world.planLayout(from.cell, toCell, bend)
        val error = target?.let { world.connectError(from, it, cableType, bend) }
        val label = when {
            target == null -> texts.cable(cableType)
            error != null -> texts.connectError(error, from, target, cableType)
            else -> context.getString(R.string.drag_cost, texts.cable(cableType), world.cableCost(layout, cableType))
        }
        // What the cable would mean for the device at one of its ends: too narrow for a service, or the ping it gets.
        val client = if (from.kind == NodeKind.CLIENT) from else target?.takeIf { it.kind == NodeKind.CLIENT }
        val other = if (client === from) target else from
        val check = if (error == null && client != null && other != null) world.checkCable(client, other, cableType) else null
        val detail = check?.let {
            if (it.problem == RouteProblem.TOO_NARROW) context.getString(R.string.drag_too_narrow, texts.service(it.service), it.service.bandwidth)
            else context.getString(R.string.drag_ping, texts.service(it.service), it.pingMs!!.roundToInt(), it.limitMs!!)
        }
        return DragPreview(
            from = from, end = end, target = target, type = cableType, layout = layout, blocked = error != null, label = label,
            detail = detail, detailWarning = check?.problem != null,
        )
    }

    private fun dragBend(from: Cell, to: Cell): Bend? = CableLayout.suggestBend(from, to, dragTrail)

    private fun trackDrag(sx: Float, sy: Float) {
        val p = renderer.toWorld(sx, sy)
        dragEnd = p
        dragEndScreen = Vec2(sx, sy)
        val target = dragFrom?.let { pickNode(sx, sy, except = it) }
        if (target !== snapTarget) {
            snapTarget = target
            if (target != null) haptic(HapticFeedbackConstants.CLOCK_TICK)
        }
        val last = dragTrail.lastOrNull()
        if ((last == null || hypot(p.x - last.x, p.y - last.y) >= TRAIL_SPACING) && dragTrail.size < MAX_TRAIL) dragTrail += p
    }

    /** Drops the gesture in progress: a half-drawn cable, a pan, and a hold on an access point. */
    private fun endDrag() {
        holdAp = null
        snapTarget = null
        dragFrom = null
        dragEnd = null
        dragEndScreen = null
        panArmed = false
        panFrom = null
        dragTrail.clear()
    }

    /** Node under a finger at ([sx], [sy]), with a touch target of at least 48 dp at any zoom. */
    private fun pickNode(sx: Float, sy: Float, except: Node? = null): Node? =
        renderer.nodeAtScreen(world, sx, sy, TouchTargets.nodeRadiusPx(renderer, density), except)

    // ---------------------------------------------------------------- HUD

    /** The day/night line under the date; only shown once a server with nightly demand exists. */
    internal fun clockLabel(): String? {
        if (world.availableServices.none { it.demand == Demand.NIGHTLY }) return null
        val now = texts.clock(world.hourOfDay)
        return if (world.isNight) context.getString(R.string.hud_clock_night, now, texts.clock(World.Tuning.BACKUP_HOUR))
        else context.getString(R.string.hud_clock_day, now)
    }

    /** Height of the HUD's round buttons and pills: 48 dp, more when large text needs it. */
    private val buttonHeight get() = maxOf(TouchTargets.MIN_DP * density, btnText.textSize + 24 * density)

    /** Room the HUD's top rows (date, packets, budget) take above the map, grown with the text size. */
    private val hudTopReserve: Float
        get() {
            val m = HudTop(surfaceWidth - safeInsets.left - safeInsets.right - 32 * density)
            return maxOf(56 * density * textScale.factor(16f), 16 * density + m.bottom)
        }

    /**
     * Top edge of the tutorial panel: below the date, its week bar and the clock line at the top left, and below the
     * counters too where the panel reaches under them.
     */
    private fun tutorialTop(): Float {
        val m = HudTop(surfaceWidth - safeInsets.left - safeInsets.right - 32 * density, compassShown)
        val panelRight = tutorialOverlay.reservedRight(surfaceWidth)
        val underCounters = m.stacked || panelRight > surfaceWidth - safeInsets.right - 16 * density - m.rightW - 8 * density
        val block = if (underCounters) m.bottom else m.leftBottom
        return safeInsets.top + maxOf(60 * density, 16 * density + block + 8 * density)
    }

    /**
     * The HUD's top rows, measured for a row [width] px wide: the date with its week bar (and clock) at the left, the
     * packets, budget and vouchers at the right. Offsets are from the top of the HUD area. When both blocks do not fit
     * side by side (a narrow window with large text), the counters move below the date. With [compass], the compass
     * button sits right-aligned below the counters.
     */
    private inner class HudTop(width: Float, compass: Boolean = false) {
        val date: String = context.getString(R.string.hud_date, world.year, world.week)
        val clock: String? = clockLabel()
        val delivered: String = resources.getQuantityString(R.plurals.hud_delivered, world.delivered, world.delivered)
        val stock: String = context.getString(R.string.hud_resources, world.budget, world.routersAvailable)
        val vouchers: String? =
            if (world.serverVouchers > 0) resources.getQuantityString(R.plurals.hud_vouchers, world.serverVouchers, world.serverVouchers) else null
        val barW = 110 * density
        val dateBaseline = hudText.textSize
        val barY = dateBaseline + 8 * density
        val clockBaseline = barY + 4 * density + 6 * density + hudSub.textSize
        val leftW = maxOf(hudText.measureText(date), barW, clock?.let { hudSub.measureText(it) } ?: 0f)
        val leftBottom = if (clock != null) clockBaseline + hudSub.descent() else barY + 4 * density
        val rightW = maxOf(hudText.measureText(delivered), hudSub.measureText(stock), vouchers?.let { hudSub.measureText(it) } ?: 0f)
        val stacked = leftW + rightW + 16 * density > width
        val rightTop = if (stacked) leftBottom + 8 * density else 0f
        val deliveredBaseline = rightTop + hudText.textSize
        val stockBaseline = deliveredBaseline + hudSub.textSize * 1.5f
        val voucherBaseline = stockBaseline + hudSub.textSize * 1.5f
        val countersBottom = (if (vouchers != null) voucherBaseline else stockBaseline) + hudSub.descent()
        val compassTop = countersBottom + 8 * density
        val rightBottom = if (compass) compassTop + buttonHeight else countersBottom
        val bottom = maxOf(leftBottom, rightBottom)
    }

    /** The compass shows while the map is turned away from north or still easing back (docs/TOP100.md B5). */
    private val compassShown get() = renderer.camera.angle != 0f

    /**
     * The compass button: a needle whose red tip points to where the top of the unturned map lies now; a tap turns the
     * map back to north.
     */
    private fun drawCompass(canvas: Canvas, r: RectF) {
        val h = r.height()
        canvas.drawRoundRect(r, h / 2, h / 2, btnFill)
        val cam = renderer.camera
        // The world direction that points up on the unturned map, as it points on screen now.
        val proj = cam.projection
        val up = cam.worldToMap(proj.unprojectX(0f, -1f), proj.unprojectY(0f, -1f))
        val len = hypot(up.x, up.y).coerceAtLeast(1e-6f)
        val dx = up.x / len; val dy = up.y / len
        val cx = r.centerX(); val cy = r.centerY(); val k = h * 0.3f; val w = h * 0.1f
        selectionPath.reset()
        selectionPath.moveTo(cx + dx * k, cy + dy * k)
        selectionPath.lineTo(cx - dy * w, cy + dx * w)
        selectionPath.lineTo(cx + dy * w, cy - dx * w)
        selectionPath.close()
        compassPaint.color = COMPASS_NORTH
        canvas.drawPath(selectionPath, compassPaint)
        selectionPath.reset()
        selectionPath.moveTo(cx - dx * k, cy - dy * k)
        selectionPath.lineTo(cx - dy * w, cy + dx * w)
        selectionPath.lineTo(cx + dy * w, cy - dx * w)
        selectionPath.close()
        compassPaint.color = 0xFF9AA3AD.toInt()
        canvas.drawPath(selectionPath, compassPaint)
        compassPaint.color = 0xFF262B33.toInt()
        canvas.drawCircle(cx, cy, h * 0.05f, compassPaint)
    }

    /** Lowest edge of the tutorial panel: above the cable buttons at the bottom left (and their slab). */
    private fun tutorialBottom() = surfaceHeight - safeInsets.bottom - 16 * density - buttonHeight - 12 * density

    /** Room the HUD's bottom row takes below the map. */
    private val hudBottomReserve get() = maxOf(68 * density, 16 * density + buttonHeight + 4 * density)

    private fun drawHud(canvas: Canvas) {
        // Everything stays inside the safe area (display cutout), with a margin of [pad] (docs/TOP100.md A5). Rows are
        // measured from the text sizes, which follow the system font size, so large text never overlaps (A7).
        hudNodes.clear()
        val pad = 16 * density
        val left = safeInsets.left + pad
        val top = safeInsets.top + pad
        val right = surfaceWidth - safeInsets.right - pad
        val bottom = surfaceHeight - safeInsets.bottom - pad
        buttons.clear()
        if (world.rewardOffer != null) {
            // During the week reward choice only the menu button stays (drawn above the dialog); the dialog repeats
            // the date and covers the rest.
            val r = RectF(right - buttonHeight, bottom - buttonHeight, right, bottom)
            buttons += Button("menu", r)
            hudNodes += UiNode("hud:menu", RectF(r), context.getString(R.string.a11y_menu), UiNode.Kind.BUTTON)
            return
        }
        val compass = compassShown
        val m = HudTop(right - left, compass)
        canvas.drawText(m.date, left, top + m.dateBaseline, hudText)
        val barY = top + m.barY
        canvas.drawRoundRect(left, barY, left + m.barW, barY + 4 * density, 2 * density, 2 * density, barBg)
        canvas.drawRoundRect(left, barY, left + m.barW * world.weekProgress, barY + 4 * density, 2 * density, 2 * density, barFg)
        m.clock?.let { canvas.drawText(it, left, top + m.clockBaseline, hudSub) }
        val leftW = m.leftW
        val leftBottom = top + m.leftBottom
        hudNodes += UiNode("hud:date", RectF(left, top, left + leftW, leftBottom), listOfNotNull(m.date, m.clock).joinToString(". "), UiNode.Kind.TEXT)

        hudText.textAlign = Paint.Align.RIGHT
        canvas.drawText(m.delivered, right, top + m.deliveredBaseline, hudText)
        hudText.textAlign = Paint.Align.LEFT
        hudSub.textAlign = Paint.Align.RIGHT
        canvas.drawText(m.stock, right, top + m.stockBaseline, hudSub)
        m.vouchers?.let { canvas.drawText(it, right, top + m.voucherBaseline, hudSub) }
        hudSub.textAlign = Paint.Align.LEFT
        // When both blocks do not fit side by side (a narrow window with large text), the counters sit below the date.
        val rightW = if (m.stacked) right - left else maxOf(m.rightW, if (compass) buttonHeight else 0f)
        val rightBottom = top + m.rightBottom
        hudNodes += UiNode(
            "hud:status", RectF(right - m.rightW, top + m.rightTop, right, top + m.countersBottom),
            listOfNotNull(m.delivered, m.stock, m.vouchers).joinToString(". "), UiNode.Kind.TEXT,
        )
        if (compass) {
            val r = RectF(right - buttonHeight, top + m.compassTop, right, top + m.compassTop + buttonHeight)
            drawCompass(canvas, r)
            buttons += Button("compass", r)
            hudNodes += UiNode("hud:compass", RectF(r), context.getString(R.string.a11y_compass), UiNode.Kind.BUTTON)
        }

        // Centered lines (week news, incidents, the paused pill) stack from the top; one that would run into the date
        // or the counters moves below them.
        val center = (left + right) / 2f
        val blocksBottom = maxOf(leftBottom, rightBottom) + 6 * density
        val freeHalf = minOf(center - (left + leftW) , (right - rightW) - center) - 12 * density
        var cursor = top
        fun place(w: Float, h: Float): Float {
            if (cursor < blocksBottom && w / 2f > freeHalf) cursor = blocksBottom
            val at = cursor
            cursor += h + 4 * density
            return at
        }
        world.lastNews?.let {
            if (world.rewardOffer == null && tutorial == null && world.time - world.lastNewsTime < 3.5f) {
                val text = texts.news(it, withYear = true)
                val line = fitText(text, right - left, bigText)
                val w = bigText.measureText(line)
                val at = place(w, bigText.textSize * 1.3f)
                canvas.drawText(line, center, at + bigText.textSize, bigText)
                hudNodes += UiNode("hud:news", RectF(center - w / 2f, at, center + w / 2f, at + bigText.textSize * 1.3f), text, UiNode.Kind.TEXT)
            }
        }
        if (world.rewardOffer == null) drawIncidentLine(canvas, center, right - left, ::place)
        if (userPaused && world.rewardOffer == null) drawPausedBanner(canvas, center, right - left, ::place)

        val bh = buttonHeight
        val gap = 10 * density
        var x = right
        val y = bottom - bh
        // Menu and pause as round icon buttons at the right edge, then the router stock.
        for (id in listOf("menu", "pause")) {
            val r = RectF(x - bh, y, x, y + bh)
            drawIconButton(canvas, r, id, active = id == "pause" && userPaused)
            buttons += Button(id, r)
            hudNodes += UiNode(
                "hud:$id", RectF(r),
                context.getString(if (id == "menu") R.string.a11y_menu else R.string.a11y_pause),
                if (id == "menu") UiNode.Kind.BUTTON else UiNode.Kind.TOGGLE, checked = id == "pause" && userPaused,
            )
            x -= bh + gap
        }
        val routerLabel = context.getString(R.string.button_router, world.routersAvailable)
        val rw = maxOf(bh, btnText.measureText(routerLabel) + 32 * density)
        val routerRect = RectF(x - rw, y, x, y + bh)
        drawHudButton(canvas, routerRect, routerLabel, active = placing == NodeKind.ROUTER)
        buttons += Button("router", routerRect)
        hudNodes += UiNode("hud:router", RectF(routerRect), context.getString(R.string.a11y_router, world.routersAvailable), UiNode.Kind.BUTTON, selected = placing == NodeKind.ROUTER)
        val rightEdge = routerRect.left
        // Cable technology picker, bottom left: invented technologies, each with its price per cell on a coin. In a
        // narrow (portrait) window it moves to its own row above; without room for every name even there, only the
        // selected technology keeps its name (the colour dots alone are hard to tell apart), and without room for
        // that either, none does.
        val coinR = maxOf(9 * density, coinText.textSize * 0.8f)
        val cables = world.unlockedCables
        fun widthOf(t: CableType, named: Boolean) =
            24 * density + (if (named) btnText.measureText(texts.cable(t)) + 8 * density else 0f) + 2 * coinR + 10 * density
        fun rowWidth(named: (CableType) -> Boolean) =
            cables.sumOf { widthOf(it, named(it)).toDouble() }.toFloat() + gap * (cables.size - 1)
        val all = { _: CableType -> true }
        val selectedOnly = { t: CableType -> t == cableType }
        // An extra row only where there is height to spare (not on a landscape phone with large text).
        val tall = bottom - top - m.bottom >= 6 * bh
        val fitsBeside = { named: (CableType) -> Boolean -> left + rowWidth(named) <= rightEdge - gap }
        val none = { _: CableType -> false }
        val (ownRow, named) = when {
            fitsBeside(all) -> false to all
            tall && left + rowWidth(all) <= right -> true to all
            fitsBeside(selectedOnly) -> false to selectedOnly
            tall && left + rowWidth(selectedOnly) <= right -> true to selectedOnly
            fitsBeside(none) -> false to none
            else -> true to none
        }
        val cableY = if (ownRow) y - bh - gap else y
        var cx = left
        for (t in cables) {
            val w = widthOf(t, named(t))
            val r = RectF(cx, cableY, cx + w, cableY + bh)
            val active = t == cableType
            canvas.drawRoundRect(r, bh / 2, bh / 2, if (active) btnActive else btnFill)
            swatch.color = CableStyles.of(t).color
            canvas.drawCircle(r.left + 14 * density, r.centerY(), 5 * density, swatch)
            if (named(t)) {
                btnText.color = if (active) 0xFFFFFFFF.toInt() else 0xFF262B33.toInt()
                btnText.textAlign = Paint.Align.LEFT
                canvas.drawText(texts.cable(t), r.left + 24 * density, r.centerY() + btnText.textSize * 0.35f, btnText)
                btnText.textAlign = Paint.Align.CENTER
            }
            val coinX = r.right - 10 * density - coinR
            canvas.drawCircle(coinX, r.centerY(), coinR, coinFill)
            canvas.drawText(t.costPerCell.toString(), coinX, r.centerY() + coinText.textSize * 0.36f, coinText)
            buttons += Button("cable:${t.name}", r)
            hudNodes += UiNode("hud:cable:${t.name}", RectF(r), context.getString(R.string.a11y_cable, texts.cable(t), t.costPerCell), UiNode.Kind.BUTTON, selected = active)
            cx += w + gap
        }
        // Radios in a row above the right buttons (above the cables too when those have their own row), only while
        // some are in stock (they come as week rewards).
        var rows = if (ownRow) 1 else 0
        x = right
        var radioRow = false
        for (type in RadioType.entries.reversed()) {
            val stock = world.radiosAvailable(type)
            if (stock == 0) continue
            radioRow = true
            val label = context.getString(if (type == RadioType.WLAN) R.string.button_access_point else R.string.button_cell_tower, stock)
            val w = maxOf(bh, btnText.measureText(label) + 32 * density)
            val top = y - (rows + 1) * (bh + gap)
            val r = RectF(x - w, top, x, top + bh)
            drawHudButton(canvas, r, label, active = placing == type.kind)
            buttons += Button("radio:${type.name}", r)
            hudNodes += UiNode(
                "hud:radio:${type.name}", RectF(r),
                context.getString(if (type == RadioType.WLAN) R.string.a11y_access_point else R.string.a11y_cell_tower, stock),
                UiNode.Kind.BUTTON, selected = placing == type.kind,
            )
            x -= w + gap
        }
        if (radioRow) rows++
        val hintY = y - 10 * density - rows * (bh + gap)
        // No hint under a menu card: the game-over card and the pause menu cover that spot.
        val hintText = when {
            screen != Screen.PLAYING -> null
            placing != null -> context.getString(R.string.hint_place_router)
            animTime < hintUntil -> hint
            else -> null
        }
        // A long hint (or large text) wraps into up to three lines that grow upwards from above the buttons.
        hintText?.let {
            val lineH = hudSub.textSize * 1.3f
            // As many lines as fit between the top rows (and the centered lines) and the buttons.
            val room = ((hintY - hudSub.textSize - maxOf(cursor, blocksBottom)) / lineH).toInt() + 1
            val lines = wrapText(it, right - left, hudSub, room.coerceIn(1, MAX_HINT_LINES))
            lines.forEachIndexed { i, line -> canvas.drawText(line, left, hintY - (lines.size - 1 - i) * lineH, hudSub) }
            val w = lines.maxOf { l -> hudSub.measureText(l) }
            hudNodes += UiNode("hud:hint", RectF(left, hintY - (lines.size - 1) * lineH - hudSub.textSize, left + w, hintY + hudSub.descent()), it, UiNode.Kind.TEXT)
        }
    }

    /** [s], shortened with an ellipsis if it is wider than [maxWidth] in [paint]. */
    private fun fitText(s: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 1 && paint.measureText(s, 0, end) + paint.measureText("…") > maxWidth) end--
        return s.substring(0, end).trimEnd() + "…"
    }

    /** [s] broken at spaces into at most [maxLines] lines of [maxWidth] in [paint]; the last one is shortened if needed. */
    private fun wrapText(s: String, maxWidth: Float, paint: Paint, maxLines: Int): List<String> {
        val lines = ArrayList<String>()
        var line = ""
        val words = s.split(' ')
        for ((i, word) in words.withIndex()) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= maxWidth || line.isEmpty()) {
                line = candidate
                continue
            }
            if (lines.size == maxLines - 1) {
                lines += fitText((listOf(line) + words.subList(i, words.size)).joinToString(" "), maxWidth, paint)
                return lines
            }
            lines += line
            line = word
        }
        if (line.isNotEmpty()) lines += fitText(line, maxWidth, paint)
        return lines
    }

    /** A round HUD button with a drawn icon: three lines for the menu, two bars (or a play triangle while paused) for pause. */
    private fun drawIconButton(canvas: Canvas, r: RectF, id: String, active: Boolean) {
        val h = r.height()
        canvas.drawRoundRect(r, h / 2, h / 2, if (active) btnActive else btnFill)
        iconInk.color = if (active) 0xFFFFFFFF.toInt() else 0xFF262B33.toInt()
        val cx = r.centerX(); val cy = r.centerY(); val u = h * 0.16f
        when {
            id == "menu" -> for (k in -1..1) canvas.drawLine(cx - u * 1.3f, cy + k * u, cx + u * 1.3f, cy + k * u, iconInk)
            active -> {
                selectionPath.reset()
                selectionPath.moveTo(cx - u * 0.8f, cy - u * 1.2f)
                selectionPath.lineTo(cx + u * 1.2f, cy)
                selectionPath.lineTo(cx - u * 0.8f, cy + u * 1.2f)
                selectionPath.close()
                iconInk.style = Paint.Style.FILL
                canvas.drawPath(selectionPath, iconInk)
                iconInk.style = Paint.Style.STROKE
            }
            else -> for (k in listOf(-1, 1)) canvas.drawLine(cx + k * u * 0.6f, cy - u * 1.1f, cx + k * u * 0.6f, cy + u * 1.1f, iconInk)
        }
    }

    /** "Paused, keep building" in a dark pill at the top centre while the clock is stopped in place. */
    private fun drawPausedBanner(canvas: Canvas, center: Float, maxWidth: Float, place: (Float, Float) -> Float) {
        val full = context.getString(R.string.hud_paused)
        val text = fitText(full, maxWidth - 28 * density, btnText)
        val w = btnText.measureText(text) + 28 * density
        val h = maxOf(30 * density, btnText.textSize * 2f)
        val top = place(w, h)
        val r = RectF(center - w / 2f, top, center + w / 2f, top + h)
        canvas.drawRoundRect(r, h / 2, h / 2, btnActive)
        btnText.color = 0xFFFFFFFF.toInt()
        canvas.drawText(text, r.centerX(), r.centerY() + btnText.textSize * 0.35f, btnText)
        hudNodes += UiNode("hud:paused", r, full, UiNode.Kind.TEXT)
    }

    /**
     * One centered line for the most urgent incident (a cut cable first, then the one due soonest), amber while
     * announced, red once struck, with "(+n)" for the others; each also gets a countdown pin on the map.
     */
    private fun drawIncidentLine(canvas: Canvas, center: Float, maxWidth: Float, place: (Float, Float) -> Float) {
        val incidents = world.incidents
        if (incidents.isEmpty()) return
        val first = incidents.minWith(compareBy({ !(it.struck && it.kind == IncidentKind.EXCAVATOR) }, { if (it.struck) it.remaining else it.warning }))
        incidentText.color = if (first.struck) IncidentStyles.CUT else IncidentStyles.WARNING.shade(-0.25f)
        val full = texts.incident(first).let { if (incidents.size > 1) context.getString(R.string.incident_more, it, incidents.size - 1) else it }
        val text = fitText(full, maxWidth, incidentText)
        val w = incidentText.measureText(text)
        val h = incidentText.textSize * 1.3f
        val top = place(w, h)
        canvas.drawText(text, center, top + incidentText.textSize, incidentText)
        hudNodes += UiNode("hud:incident", RectF(center - w / 2f, top, center + w / 2f, top + h), full, UiNode.Kind.TEXT)
    }

    /** A countdown pin over every incident's spot on the map, so the line at the top points at its cable or node. */
    private fun drawIncidentPins(canvas: Canvas) {
        if (world.rewardOffer != null) return
        for (i in world.incidents) {
            val at = renderer.toScreen(i.node?.center ?: i.spot)
            val seconds = ceil(if (i.struck) i.remaining else i.warning).toInt().coerceAtLeast(1)
            val text = context.getString(R.string.incident_countdown, seconds)
            val w = pinText.measureText(text) + 12 * density
            val h = 20 * density
            val bottom = at.y - maxOf(renderer.unitPx * 0.9f, 26 * density)
            pinFill.color = if (i.struck) IncidentStyles.CUT else IncidentStyles.WARNING.shade(-0.2f)
            canvas.drawRoundRect(at.x - w / 2f, bottom - h, at.x + w / 2f, bottom, h / 2f, h / 2f, pinFill)
            canvas.drawLine(at.x, bottom, at.x, bottom + 5 * density, pinFill.also { it.strokeWidth = 2 * density })
            canvas.drawText(text, at.x, bottom - h / 2f + pinText.textSize * 0.36f, pinText)
        }
    }

    /** A pulsing yellow glow on the selected cable or node, the one a second tap acts on. */
    private fun drawSelection(canvas: Canvas) {
        val sel = selection ?: return
        val pulse = 0.6f + 0.4f * kotlin.math.sin(animTime * 7f)
        selectionPaint.color = SELECTION_COLOR
        selectionPaint.alpha = (140 * pulse).toInt() + 60
        when (sel) {
            is Cable -> {
                selectionPath.reset()
                sel.layout.waypoints.forEachIndexed { k, p ->
                    val q = renderer.toScreen(p)
                    if (k == 0) selectionPath.moveTo(q.x, q.y) else selectionPath.lineTo(q.x, q.y)
                }
                selectionPaint.strokeWidth = maxOf(12 * density, renderer.unitPx * 0.3f)
                canvas.drawPath(selectionPath, selectionPaint)
            }
            is Node -> {
                val c = renderer.toScreen(sel.footprintCenter)
                selectionPaint.strokeWidth = 5 * density
                canvas.drawCircle(c.x, c.y, maxOf(26 * density, renderer.unitPx * (if (sel.isDataCenter) 1.3f else 0.8f)), selectionPaint)
            }
        }
    }

    /** True while back does something inside the game; false on the main menu, where it leaves the app. */
    internal val handlesBack: Boolean get() = screen != Screen.MAIN_MENU

    private fun publishBackHandling() {
        val now = handlesBack
        if (now == publishedBackHandling) return
        publishedBackHandling = now
        mainThread.post { onBackHandlingChanged?.invoke(now) }
    }

    // ---------------------------------------------------------------- input (game thread)

    private fun handle(input: Input) {
        when (input) {
            // The surface is recreated on every return from the background with the same size: keep the player's view.
            is Input.Resize -> if (input.width != surfaceWidth || input.height != surfaceHeight) {
                surfaceWidth = input.width
                surfaceHeight = input.height
                layoutRenderers()
                if (failFocusUntil != null) world.failedNode?.let { renderer.focusOn(it) }
            }
            is Input.Touch -> if (pendingAd == null) onTouch(input)
            Input.Back -> if (pendingAd == null) onBack()
            is Input.AdResult -> onAdResult(input)
            is Input.Restore -> restore(input)
            is Input.Safe -> if (input.insets != safeInsets) {
                safeInsets = input.insets
                layoutRenderers()
            }
            is Input.Activate -> activate(input.key)
            is Input.Reveal -> if (screen == Screen.SCENERIES && input.key.startsWith("scenery:")) sceneryPicker.reveal(input.key.removePrefix("scenery:"))
        }
    }

    private fun onTouch(e: Input.Touch) {
        if (failFocusUntil != null) {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> focusSkipArmed = true
                MotionEvent.ACTION_UP -> if (focusSkipArmed) showGameOverCard()
            }
            return
        }
        if (screen == Screen.SCENERIES) {
            onSceneryTouch(e)
            return
        }
        if (screen != Screen.PLAYING) {
            onMenuTouch(e)
            return
        }
        if (onTutorialTouch(e)) return
        if (!gestureConsumed && world.rewardOffer != null && e.action == MotionEvent.ACTION_DOWN) {
            buttons.firstOrNull { it.id == "menu" && it.rect.contains(e.x, e.y) }?.let { onButton(it.id); return }
        }
        if (gestureConsumed || (world.rewardOffer != null && e.action == MotionEvent.ACTION_DOWN)) {
            onRewardTouch(e)
            return
        }
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downTime = e.time
                cameraGesture = false
                buttons.firstOrNull { it.rect.contains(e.x, e.y) }?.let { onButton(it.id); return }
                dragTrail.clear()
                // While placing, the tap (not the start of a pan or pinch) decides where: see ACTION_UP.
                dragFrom = if (placing != null) null else pickNode(e.x, e.y)
                if (dragFrom == null && isDoubleTap(e)) {
                    renderer.fitArea(world, animate = true)
                    emptyTapTime = null
                    cameraGesture = true
                    return
                }
                panArmed = dragFrom == null
                holdAp = dragFrom?.takeIf { it.kind == NodeKind.ACCESS_POINT }
                holdStart = animTime
                holdFired = false
                trackDrag(e.x, e.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger turns any gesture into camera movement; a half-drawn cable is dropped.
                holdAp = null
                endDrag()
                cameraGesture = true
                renderer.camera.stopRotation()
                startPinch(e)
            }
            MotionEvent.ACTION_MOVE -> when {
                cameraGesture -> if (e.pointers.size >= 4) {
                    val before = renderer.camera.angle
                    pinch.move(e.pointers[0], e.pointers[1], e.pointers[2], e.pointers[3], renderer.camera)
                    if (renderer.camera.angle != before) renderer.updateLimits(world)
                }
                dragFrom != null -> {
                    if (hypot(e.x - downX, e.y - downY) >= TAP_SLOP_DP * density) holdAp = null
                    trackDrag(e.x, e.y)
                }
                panArmed -> panWithOneFinger(e)
            }
            MotionEvent.ACTION_POINTER_UP -> if (cameraGesture) startPinch(e)
            MotionEvent.ACTION_UP -> {
                holdAp = null
                if (holdFired) {
                    holdFired = false
                    endDrag()
                    return
                }
                if (cameraGesture || world.rewardOffer != null) {
                    cameraGesture = false
                    stopPinch()
                    endDrag()
                    return
                }
                val from = dragFrom
                val isTap = hypot(e.x - downX, e.y - downY) < TAP_SLOP_DP * density
                val kind = placing
                if (kind != null) {
                    if (isTap) renderer.toWorld(e.x, e.y).let { p -> place(kind, floor(p.x).toInt(), floor(p.y).toInt()) }
                } else if (from != null && isTap) {
                    when (from.kind) {
                        NodeKind.SERVER -> serverTap(from)
                        NodeKind.ACCESS_POINT -> if (e.time - downTime >= LONG_PRESS_MS) upgradeTo5Ghz(from) else cycleChannel(from)
                        NodeKind.ROUTER -> routerTap(from)
                        NodeKind.CLIENT -> explainClient(from)
                        NodeKind.CELL_TOWER -> Unit
                    }
                } else if (from != null) {
                    trackDrag(e.x, e.y)
                    pickNode(e.x, e.y, except = from)?.let {
                        if (world.connect(from, it, cableType, dragBend(from.cell, it.cell))) {
                            selection = null
                            haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                            sounds.play(Sound.CABLE)
                        }
                    }
                } else if (isTap && panArmed) {
                    val cable = renderer.cableAtScreen(world, e.x, e.y, TouchTargets.cableRadiusPx(renderer, density))
                    if (cable == null) {
                        selection = null
                        emptyTapTime = e.time; emptyTapX = e.x; emptyTapY = e.y
                    } else {
                        cableTap(cable)
                    }
                }
                endDrag()
            }
            MotionEvent.ACTION_CANCEL -> {
                holdAp = null
                holdFired = false
                cameraGesture = false
                stopPinch()
                endDrag()
            }
        }
    }

    /** (Re)starts the two-finger gesture from the fingers still down, or stops it if fewer than two remain. */
    private fun startPinch(e: Input.Touch) {
        val p = e.pointers
        if (p.size >= 4) pinch.start(p[0], p[1], p[2], p[3]) else stopPinch()
    }

    /**
     * Ends the two-finger gesture: unless "free rotation" is on, the map eases to the nearest multiple of 90° around
     * the point where the fingers were (docs/TOP100.md B5).
     */
    private fun stopPinch() {
        if (!pinch.isActive) return
        pinch.stop()
        renderer.camera.settleRotation(snap = !settings.freeRotation, pinch.lastMidX, pinch.lastMidY)
    }

    /** A drag that started on empty ground moves the map once it leaves the tap slop. */
    private fun panWithOneFinger(e: Input.Touch) {
        val last = panFrom
        if (last == null) {
            if (hypot(e.x - downX, e.y - downY) >= TAP_SLOP_DP * density) panFrom = Vec2(e.x, e.y).also {
                renderer.camera.panBy(e.x - downX, e.y - downY)
            }
            return
        }
        renderer.camera.panBy(e.x - last.x, e.y - last.y)
        panFrom = Vec2(e.x, e.y)
    }

    private fun isDoubleTap(e: Input.Touch): Boolean {
        val last = emptyTapTime ?: return false
        return e.time - last in 0..DOUBLE_TAP_MS && hypot(e.x - emptyTapX, e.y - emptyTapY) < DOUBLE_TAP_SLOP_DP * density
    }

    /**
     * Touches that start on the tutorial bubble stay there: a button is chosen when the finger goes down and up on it,
     * anything else on the bubble is swallowed. Returns true if the touch was the bubble's.
     */
    private fun onTutorialTouch(e: Input.Touch): Boolean {
        if (tutorial == null) return false
        if (e.action == MotionEvent.ACTION_DOWN) {
            val hit = tutorialOverlay.hit(e.x, e.y)
            if (hit == null) {
                // A new gesture elsewhere: whatever the bubble still waited for is over.
                tutorialGesture = false
                tutorialPressed = null
                return false
            }
            tutorialGesture = true
            tutorialPressed = hit
            endDrag()
            placing = null
            return true
        }
        if (!tutorialGesture) return false
        when (e.action) {
            MotionEvent.ACTION_UP -> {
                val id = tutorialPressed
                tutorialGesture = false
                tutorialPressed = null
                if (id != null && id != TutorialOverlay.BUBBLE && tutorialOverlay.hit(e.x, e.y) == id) {
                    click()
                    onTutorialButton(id)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                tutorialGesture = false
                tutorialPressed = null
            }
        }
        return true
    }

    private fun onTutorialButton(id: String) {
        when (id) {
            TutorialOverlay.SKIP -> {
                tutorial?.skip()
                leaveTutorial()
            }
            TutorialOverlay.PLAY -> {
                settingsStore.tutorialSeen = true
                newGame(Scenarios.RIVER_TOWN)
            }
            TutorialOverlay.MENU -> leaveTutorial()
        }
    }

    /** Ends the tutorial for good and returns to the main menu. */
    private fun leaveTutorial() {
        settingsStore.tutorialSeen = true
        screen = Screen.MAIN_MENU
        showWorld(DemoCity.build())
    }

    /** What the tutorial highlights; while a router is being placed, the phones it should go to instead of the button. */
    private fun tutorialFocus(t: Tutorial): TutorialFocus {
        val focus = t.focus(cableType)
        return if (focus == TutorialFocus.RouterButton && placing == NodeKind.ROUTER) TutorialFocus.Nodes(t.phones) else focus
    }

    /** While the reward choice is open, a card is picked when the finger goes down and up on the same card. */
    private fun onRewardTouch(e: Input.Touch) {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                gestureConsumed = true
                endDrag()
                pressedCard = rewardDialog.hit(e.x, e.y)
            }
            MotionEvent.ACTION_UP -> {
                val card = pressedCard
                if (card != null && world.rewardOffer != null && rewardDialog.hit(e.x, e.y) == card) {
                    if (card == RewardDialog.BONUS) takeBonusRouter() else world.chooseReward(card)
                }
                gestureConsumed = false
                pressedCard = null
            }
            MotionEvent.ACTION_CANCEL -> {
                gestureConsumed = false
                pressedCard = null
            }
        }
    }

    /** First tap on a server: its next tier and price; a second tap on the selected server upgrades it. */
    private fun serverTap(server: Node) {
        val error = world.serverUpgradeError(server)
        if (error != null) {
            showHint(
                when (error) {
                    ServerUpgradeError.NOT_A_SERVER -> return
                    ServerUpgradeError.MAX_LEVEL -> context.getString(R.string.server_error_max_level)
                    ServerUpgradeError.NO_SPACE -> context.getString(R.string.server_error_no_space)
                    ServerUpgradeError.NO_BUDGET ->
                        context.getString(R.string.server_error_no_budget, World.Tuning.SERVER_UPGRADE_COST[server.level - 1])
                },
            )
            return
        }
        val next = tierName(server.level + 1)
        if (selection !== server) {
            select(server)
            showHint(
                if (world.serverVouchers > 0) context.getString(R.string.hint_server_preview_voucher, texts.node(server), next)
                else context.getString(R.string.hint_server_preview, texts.node(server), next, World.Tuning.SERVER_UPGRADE_COST[server.level - 1]),
                SELECT_SECONDS,
            )
            return
        }
        if (!world.upgradeServer(server)) return
        selection = null
        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
        sounds.play(Sound.CABLE)
        showHint(context.getString(R.string.hint_server_upgraded, texts.node(server), next))
    }

    private fun tierName(level: Int) =
        if (level >= World.Tuning.DATA_CENTER_LEVEL) context.getString(R.string.server_tier_data_center) else context.getString(R.string.server_tier, level)

    /**
     * A tap on an intact cable upgrades it to a better picked technology. Otherwise the first tap selects it and says
     * what removing it gives back; a second tap on the selected cable removes it. A cut cable is repaired.
     */
    private fun cableTap(cable: Cable) {
        if (world.isCut(cable)) {
            selection = null
            repair(cable)
            return
        }
        if (cableType > cable.type) {
            selection = null
            val price = world.cableCost(cable.layout, cableType) - cable.cost
            when (world.upgradeError(cable, cableType)) {
                null -> if (world.upgrade(cable, cableType)) {
                    haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                    sounds.play(Sound.CABLE)
                    showHint(context.getString(R.string.hint_cable_upgraded, texts.cable(cableType), price))
                }
                CableUpgradeError.NO_BUDGET -> showHint(context.getString(R.string.cable_error_no_budget, price))
                CableUpgradeError.NOT_INVENTED -> showHint(context.getString(R.string.connect_error_not_invented, texts.cable(cableType)))
                CableUpgradeError.NOT_AN_UPGRADE -> Unit
            }
            return
        }
        val refund = world.refundOf(cable)
        if (selection !== cable) {
            select(cable)
            showHint(context.getString(R.string.hint_cable_remove, texts.cable(cable.type), refund), SELECT_SECONDS)
            return
        }
        selection = null
        world.removeCable(cable)
        haptic(HapticFeedbackConstants.CLOCK_TICK)
        showHint(context.getString(R.string.hint_cable_removed, refund))
    }

    /** A router without cables goes back into stock on a second tap; one with cables does nothing. */
    private fun routerTap(router: Node) {
        if (world.pickUpError(router) != null) return
        if (selection !== router) {
            select(router)
            showHint(context.getString(R.string.hint_router_pick_up), SELECT_SECONDS)
            return
        }
        selection = null
        if (world.pickUp(router)) {
            haptic(HapticFeedbackConstants.CLOCK_TICK)
            showHint(context.getString(R.string.hint_router_picked_up))
        }
    }

    /** Says why the device's waiting requests are stuck, or what it wants if none is. */
    private fun explainClient(client: Node) {
        val stuck = client.pending.firstOrNull { world.routeProblem(client, it) != null }
        showHint(
            if (stuck != null) {
                val problem = world.routeProblem(client, stuck)
                problemText(client, stuck, problem, if (problem == RouteProblem.PING_TOO_HIGH) world.bestRoute(client, stuck)?.pingMs else null)
            } else {
                val services = client.device!!.services.joinToString(context.getString(R.string.list_separator)) { texts.service(it) }
                context.getString(R.string.device_wants, texts.node(client), services)
            },
            LONG_HINT_SECONDS,
        )
    }

    /** "Konsole: Gaming – Ping 180 ms, erlaubt 140 ms" and the like, for a device, a service and its [RouteProblem]. */
    private fun problemText(n: Node, s: Service, problem: RouteProblem?, pingMs: Float?): String {
        val node = texts.node(n)
        val service = texts.service(s)
        return when (problem) {
            RouteProblem.NO_ROUTE -> context.getString(R.string.problem_no_route, node, service)
            RouteProblem.TOO_NARROW -> context.getString(R.string.problem_too_narrow, node, service, s.bandwidth)
            RouteProblem.PING_TOO_HIGH ->
                context.getString(R.string.problem_ping, node, service, (pingMs ?: 0f).roundToInt(), s.maxPingMs ?: 0)
            null -> context.getString(R.string.problem_jam, node, service)
        }
    }

    private fun select(target: Any) {
        selection = target
        selectionUntil = animTime + SELECT_SECONDS
    }

    private fun repair(cable: Cable) {
        if (world.repairError(cable) == RepairError.NO_BUDGET) {
            showHint(context.getString(R.string.repair_error_no_budget, Incidents.REPAIR_COST))
            return
        }
        if (!world.repair(cable)) return
        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
        sounds.play(Sound.CABLE)
        showHint(context.getString(R.string.hint_repaired))
    }

    /**
     * Places a router or radio from stock where the player tapped and disarms placing; a new access point explains its
     * controls. A cell that does not work says why and placing stays armed for another try.
     */
    private fun place(kind: NodeKind, cx: Int, cy: Int) {
        val radio = RadioType.of(kind)
        val error = world.placeError(kind, cx, cy)
        if (error != null) {
            val name = if (radio == null) context.getString(R.string.node_router) else texts.radio(radio)
            showHint(
                when (error) {
                    PlaceError.NO_STOCK -> context.getString(R.string.place_error_no_stock, name)
                    PlaceError.LOCKED -> context.getString(R.string.place_error_locked)
                    PlaceError.OCCUPIED -> context.getString(R.string.place_error_occupied)
                    PlaceError.TERRAIN -> context.getString(R.string.place_error_terrain)
                },
            )
            if (error == PlaceError.NO_STOCK) placing = null
            return
        }
        val placed = (if (radio == null) world.placeRouter(cx, cy) else world.placeRadio(radio, cx, cy)) ?: return
        placing = null
        haptic(HapticFeedbackConstants.CLOCK_TICK)
        if (placed.kind == NodeKind.ACCESS_POINT) showHint(context.getString(R.string.hint_access_point, Wifi.UPGRADE_5_GHZ_COST))
    }

    private fun cycleChannel(ap: Node) {
        if (!world.cycleChannel(ap)) return
        haptic(HapticFeedbackConstants.CLOCK_TICK)
        val name = texts.node(ap)
        val clashes = world.interferers(ap).size
        showHint(
            if (clashes == 0) context.getString(R.string.hint_channel, name, ap.channel)
            else resources.getQuantityString(R.plurals.hint_channel_interference, clashes, name, ap.channel, clashes),
        )
    }

    /** Fires a hold on an access point as soon as it lasted [LONG_PRESS_MS], not only when the finger lifts. */
    private fun checkHold() {
        val ap = holdAp ?: return
        if (screen != Screen.PLAYING || world.gameOver || world.rewardOffer != null || failFocusUntil != null || ap !in world.nodes) {
            holdAp = null
            return
        }
        if (animTime - holdStart < LONG_PRESS_MS / 1000f) return
        holdAp = null
        holdFired = true
        upgradeTo5Ghz(ap)
    }

    /** A ring around a held 2.4 GHz access point that fills up until the hold switches it to 5 GHz. */
    private fun drawHoldProgress(canvas: Canvas) {
        val ap = holdAp?.takeIf { !it.fiveGhz } ?: return
        val f = ((animTime - holdStart) * 1000f - HOLD_RING_DELAY_MS) / (LONG_PRESS_MS - HOLD_RING_DELAY_MS)
        if (f <= 0f) return
        val c = renderer.toScreen(ap.center)
        val r = HOLD_RING_DP * density
        holdRing.strokeWidth = 5 * density
        holdRing.color = 0x33262B33
        canvas.drawCircle(c.x, c.y, r, holdRing)
        holdRing.color = HOLD_RING_COLOR
        canvas.drawArc(c.x - r, c.y - r, c.x + r, c.y + r, -90f, 360f * f.coerceAtMost(1f), false, holdRing)
    }

    private fun upgradeTo5Ghz(ap: Node) {
        showHint(
            when (world.wifiUpgradeError(ap)) {
                null -> {
                    if (!world.upgradeTo5Ghz(ap)) return
                    haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                    context.getString(R.string.hint_5ghz, texts.node(ap))
                }
                WifiUpgradeError.NOT_AN_ACCESS_POINT -> return
                WifiUpgradeError.ALREADY_5_GHZ -> context.getString(R.string.wifi_error_already_5ghz)
                WifiUpgradeError.NO_BUDGET -> context.getString(R.string.wifi_error_no_budget, Wifi.UPGRADE_5_GHZ_COST)
            },
        )
    }

    private fun showHint(text: String, seconds: Float = HINT_SECONDS) {
        hint = text
        hintUntil = animTime + seconds
    }

    private fun onButton(id: String) {
        click()
        when {
            id == "menu" -> openPauseMenu()
            id == "pause" -> userPaused = !userPaused
            id == "compass" -> renderer.camera.rotateTo(0f)
            id == "router" -> togglePlacing(NodeKind.ROUTER, world.routersAvailable)
            id.startsWith("radio:") -> RadioType.valueOf(id.removePrefix("radio:")).let { togglePlacing(it.kind, world.radiosAvailable(it)) }
            id.startsWith("cable:") -> pickCable(CableType.valueOf(id.removePrefix("cable:")))
        }
    }

    /** Arms placing [kind] if [stock] allows it; pressing the same button again disarms it. An empty stock says so. */
    private fun togglePlacing(kind: NodeKind, stock: Int) {
        if (stock <= 0 && placing != kind) {
            val name = RadioType.of(kind)?.let(texts::radio) ?: context.getString(R.string.node_router)
            showHint(context.getString(R.string.place_error_no_stock, name))
        }
        placing = if (placing == kind || stock <= 0) null else kind
    }

    /** Picks a cable technology and names its bandwidth, speed and price, and a service it is too narrow for. */
    private fun pickCable(t: CableType) {
        cableType = t
        if (tutorial != null) return
        val ms = java.text.NumberFormat.getNumberInstance(resources.configuration.locales[0]).format(t.msPerCell.toDouble())
        val info = context.getString(R.string.hint_cable_info, texts.cable(t), t.capacity, ms, t.costPerCell)
        val narrow = world.availableServices.filter { it.bandwidth > t.capacity }.sortedBy { it.bandwidth }.firstOrNull()
        showHint(if (narrow == null) info else context.getString(R.string.hint_two_parts, info, context.getString(R.string.hint_cable_too_narrow, texts.service(narrow))))
    }

    // ---------------------------------------------------------------- menus (game thread)

    private fun menuPage(): MenuPage? = when (screen) {
        Screen.PLAYING, Screen.SCENERIES -> null
        Screen.MAIN_MENU -> MenuPage(
            title = context.getString(R.string.app_name),
            lines = listOf(context.getString(R.string.menu_tagline)),
            items = listOf(
                MenuItem.Button(MenuAction.PLAY, context.getString(R.string.menu_play), primary = true),
                MenuItem.Button(MenuAction.CONTINUE, context.getString(R.string.menu_continue), enabled = gameInProgress || hasSave),
                MenuItem.Button(MenuAction.SETTINGS, context.getString(R.string.menu_settings)),
            ) + listOfNotNull(removeAdsLabel()?.let { MenuItem.Button(MenuAction.REMOVE_ADS, it) }),
            footer = highscores.best(highscores.lastScenery).takeIf { it > 0 }?.let { context.getString(R.string.menu_best, it) },
            hero = true,
        )
        Screen.PAUSED -> MenuPage(
            title = context.getString(R.string.pause_title),
            lines = listOf(
                tutorial?.let { context.getString(R.string.tutorial_pause, it.number, Tutorial.STEPS) } ?: texts.scenario(world.scenario),
                context.getString(
                    R.string.pause_status,
                    context.getString(R.string.hud_date, world.year, world.week),
                    resources.getQuantityString(R.plurals.hud_delivered, world.delivered, world.delivered),
                ),
            ),
            items = listOf(
                MenuItem.Button(MenuAction.RESUME, context.getString(R.string.menu_resume), primary = true),
                MenuItem.Button(MenuAction.SETTINGS, context.getString(R.string.menu_settings)),
                MenuItem.Button(MenuAction.RESTART, context.getString(R.string.menu_restart)),
                MenuItem.Button(MenuAction.MAIN_MENU, context.getString(R.string.menu_main)),
            ),
        )
        Screen.SETTINGS -> MenuPage(
            title = context.getString(R.string.menu_settings),
            items = listOf(
                MenuItem.Toggle(MenuAction.TOGGLE_SOUND, context.getString(R.string.settings_sound), settings.sound),
                MenuItem.Toggle(MenuAction.TOGGLE_HAPTICS, context.getString(R.string.settings_haptics), settings.haptics),
                MenuItem.Toggle(MenuAction.TOGGLE_OVERVIEW, context.getString(R.string.settings_overview), settings.overviewMode),
                MenuItem.Toggle(MenuAction.TOGGLE_FREE_ROTATION, context.getString(R.string.settings_free_rotation), settings.freeRotation),
                MenuItem.Toggle(MenuAction.TOGGLE_COLORBLIND, context.getString(R.string.settings_colorblind), settings.colorblind),
                MenuItem.Button(MenuAction.TUTORIAL, context.getString(R.string.settings_tutorial)),
            ) + listOfNotNull(
                if (monetization.privacyOptionsRequired) MenuItem.Button(MenuAction.PRIVACY, context.getString(R.string.settings_privacy)) else null,
                MenuItem.Button(MenuAction.BACK, context.getString(R.string.menu_back)),
            ),
            footer = context.getString(R.string.settings_language),
        )
        Screen.GAME_OVER -> MenuPage(
            title = context.getString(R.string.game_over_title),
            highlight = if (newBest) context.getString(R.string.game_over_new_best) else null,
            lines = listOfNotNull(
                world.failure?.let { problemText(it.node, it.service, it.problem, it.pingMs) },
                resources.getQuantityString(R.plurals.game_over_stats, world.delivered, world.delivered, world.week),
                if (newBest) null else context.getString(R.string.game_over_best, highscores.best(world.scenario.id)),
            ),
            picture = recapPicture(),
            items = listOfNotNull(
                MenuItem.Button(MenuAction.PLAY_AGAIN, context.getString(R.string.game_over_again), primary = true),
                secondChanceLabel()?.let { MenuItem.Button(MenuAction.SECOND_CHANCE, it) },
                MenuItem.Button(MenuAction.MAIN_MENU, context.getString(R.string.menu_main)),
            ),
        )
    }

    /** The time-lapse of the network that just ended, once there is growth to show. */
    private fun recapPicture(): MenuPicture? {
        val frames = growth.frames
        if (frames.size < 2 || tutorial != null) return null
        val w = world
        return MenuPicture(context.getString(R.string.a11y_recap, frames.first().week, frames.last().week), recap.aspect(frames)) { c, r ->
            recap.draw(c, r, frames, animTime - gameOverAt) { x, y -> y in 0 until w.rows && x in 0 until w.cols && w.water[y][x] }
        }
    }

    /** A menu entry is chosen when the finger goes down and up on the same entry. */
    private fun onMenuTouch(e: Input.Touch) {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                endDrag()
                pressedAction = menuPanel.hit(e.x, e.y)
            }
            MotionEvent.ACTION_UP -> {
                val action = pressedAction
                pressedAction = null
                if (action != null && menuPanel.hit(e.x, e.y) == action) {
                    click()
                    onMenuAction(action)
                }
            }
            MotionEvent.ACTION_CANCEL -> pressedAction = null
        }
    }

    private fun onMenuAction(action: MenuAction) {
        if (screen == Screen.GAME_OVER && (action == MenuAction.PLAY_AGAIN || action == MenuAction.MAIN_MENU) && interstitialBetweenGames(action)) return
        runMenuAction(action)
    }

    private fun runMenuAction(action: MenuAction) {
        when (action) {
            MenuAction.PLAY -> {
                sceneryHint = null
                screen = Screen.SCENERIES
            }
            MenuAction.PLAY_AGAIN, MenuAction.RESTART -> if (tutorial != null) startTutorial() else newGame(world.scenario)
            MenuAction.TUTORIAL -> {
                autosave()
                startTutorial()
            }
            MenuAction.CONTINUE -> continueGame()
            MenuAction.RESUME -> screen = Screen.PLAYING
            MenuAction.SETTINGS -> {
                settingsReturn = screen
                screen = Screen.SETTINGS
            }
            MenuAction.BACK -> screen = settingsReturn
            MenuAction.MAIN_MENU -> {
                autosave()
                if (tutorial != null) settingsStore.tutorialSeen = true
                screen = Screen.MAIN_MENU
                if (!gameInProgress) showWorld(DemoCity.build())
            }
            MenuAction.TOGGLE_SOUND -> updateSettings(settings.copy(sound = !settings.sound))
            MenuAction.TOGGLE_HAPTICS -> updateSettings(settings.copy(haptics = !settings.haptics))
            MenuAction.TOGGLE_OVERVIEW -> updateSettings(settings.copy(overviewMode = !settings.overviewMode))
            MenuAction.TOGGLE_FREE_ROTATION -> updateSettings(settings.copy(freeRotation = !settings.freeRotation))
            MenuAction.TOGGLE_COLORBLIND -> updateSettings(settings.copy(colorblind = !settings.colorblind))
            MenuAction.SECOND_CHANCE -> askSecondChance()
            MenuAction.REMOVE_ADS -> monetization.purchase(Entitlements.REMOVE_ADS)
            MenuAction.PRIVACY -> monetization.showPrivacyOptions()
        }
    }

    // ---------------------------------------------------------------- monetization (game thread)

    /**
     * Counts the game that just ended and, if [AdPolicy][com.mininetworks.game.monetization.AdPolicy] allows it, shows
     * an interstitial; [action] runs once it closed. Returns false if no ad shows, so [action] runs right away.
     */
    private fun interstitialBetweenGames(action: MenuAction): Boolean {
        adPolicy.gameFinished()
        val show = adPolicy.interstitialDue(monetization.adsRemoved) &&
            monetization.showInterstitial { adClosed(AdPlacement.INTERSTITIAL, earned = false) }
        if (show) {
            adPolicy.interstitialShown()
            pendingAd = AdPlacement.INTERSTITIAL
            afterInterstitial = action
        }
        monetizationStore.savePolicy(adPolicy)
        return show
    }

    private fun onAdResult(r: Input.AdResult) {
        if (pendingAd != r.placement) return
        pendingAd = null
        when (r.placement) {
            AdPlacement.INTERSTITIAL -> afterInterstitial?.let {
                afterInterstitial = null
                runMenuAction(it)
            }
            AdPlacement.CONTINUE -> if (r.earned) secondChance()
            AdPlacement.BONUS_ROUTER -> if (r.earned) grantBonusRouter()
        }
    }

    /** "Continue" on the game-over card, while the lost game may still go on and a video is ready (or ads are removed). */
    private fun secondChanceLabel(): String? = when {
        !world.canContinue || tutorial != null -> null
        monetization.adsRemoved -> context.getString(R.string.game_over_continue)
        monetization.rewardedReady -> context.getString(R.string.game_over_continue_ad)
        else -> null
    }

    private fun askSecondChance() {
        if (!world.canContinue) return
        if (monetization.adsRemoved) {
            secondChance()
        } else if (monetization.showRewarded { adClosed(AdPlacement.CONTINUE, it) }) {
            pendingAd = AdPlacement.CONTINUE
        }
    }

    /** The lost game goes on with empty overload rings; it counts as a game in progress again and is saved. */
    private fun secondChance() {
        if (!world.continueAfterGameOver()) return
        screen = Screen.PLAYING
        gameInProgress = true
        newBest = false
        autosave()
        showHint(context.getString(R.string.hint_continued))
    }

    /** The extra-router pill on the week reward screen, once per week, while a video is ready (or ads are removed). */
    private fun bonusLabel(): String? {
        val offer = world.rewardOffer ?: return null
        return when {
            offer.bonusClaimed || tutorial != null -> null
            monetization.adsRemoved -> context.getString(R.string.reward_bonus_router)
            monetization.rewardedReady -> context.getString(R.string.reward_bonus_router_ad)
            else -> null
        }
    }

    private fun takeBonusRouter() {
        if (bonusLabel() == null) return
        click()
        if (monetization.adsRemoved) {
            grantBonusRouter()
        } else if (monetization.showRewarded { adClosed(AdPlacement.BONUS_ROUTER, it) }) {
            pendingAd = AdPlacement.BONUS_ROUTER
        }
    }

    private fun grantBonusRouter() {
        if (world.claimBonusRouter()) showHint(context.getString(R.string.hint_bonus_router))
    }

    /** "Remove ads" in the main menu while the store sells it. */
    private fun removeAdsLabel(): String? =
        if (monetization.adsRemoved) null else monetization.price(Entitlements.REMOVE_ADS)?.let { context.getString(R.string.menu_remove_ads, it) }

    /** The pack pill on the scenery picker while some purchasable scenery is still locked and the store sells the pack. */
    private fun packLabel(): String? {
        if (Scenarios.all.none { it.purchasable && !sceneryUnlocked(it) }) return null
        return monetization.price(Entitlements.SCENERY_PACK)?.let { context.getString(R.string.scenery_pack_buy, it) }
    }

    private fun onBack() {
        when (screen) {
            Screen.PLAYING -> when {
                failFocusUntil != null -> showGameOverCard()
                placing != null -> placing = null
                else -> openPauseMenu()
            }
            Screen.PAUSED -> screen = Screen.PLAYING
            Screen.SETTINGS -> screen = settingsReturn
            Screen.SCENERIES -> screen = Screen.MAIN_MENU
            Screen.GAME_OVER -> onMenuAction(MenuAction.MAIN_MENU)
            Screen.MAIN_MENU -> mainThread.post { onExit?.invoke() }
        }
    }

    // ---------------------------------------------------------------- scenery picker (game thread)

    private fun sceneryUnlocked(s: Scenario) = Scenarios.isUnlocked(s, highscores::best, monetization::ownsScenery)

    private fun sceneryCards(): List<SceneryCard> = Scenarios.all.map { s ->
        val unlocked = sceneryUnlocked(s)
        val unlock = s.unlock
        val best = highscores.best(s.id)
        val status = when {
            unlocked -> listOf(if (best > 0) context.getString(R.string.menu_best, best) else context.getString(R.string.scenery_not_played))
            unlock is Unlock.Score -> listOf(
                resources.getQuantityString(R.plurals.scenery_progress, unlock.packets, highscores.best(unlock.after).coerceAtMost(unlock.packets), unlock.packets),
                context.getString(R.string.scenery_progress_in, texts.scenario(Scenarios.byId(unlock.after)!!)),
            )
            else -> listOf(context.getString(R.string.scenery_shop), context.getString(R.string.scenery_shop_pack))
        }
        SceneryCard(
            scenario = s,
            name = texts.scenario(s),
            era = context.getString(R.string.scenery_from_year, s.startYear),
            description = texts.scenarioDescription(s),
            unlocked = unlocked,
            status = status,
            progress = if (!unlocked && unlock is Unlock.Score) highscores.best(unlock.after) / unlock.packets.toFloat() else null,
            lockedLabel = if (unlocked) null else context.getString(R.string.a11y_locked),
        )
    }

    /** A card is chosen when the finger goes down and up on it: an unlocked scenery starts, a locked one says how to get it. */
    private fun onSceneryTouch(e: Input.Touch) {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                endDrag()
                pressedScenery = sceneryPicker.hit(e.x, e.y)
                sceneryDownX = e.x
                sceneryLastX = e.x
                sceneryScrolling = false
            }
            // A row of cards wider than the screen (large text on a phone) scrolls sideways with a drag.
            MotionEvent.ACTION_MOVE -> if (sceneryPicker.scrollable) {
                if (!sceneryScrolling && kotlin.math.abs(e.x - sceneryDownX) >= TAP_SLOP_DP * density) {
                    sceneryScrolling = true
                    pressedScenery = null
                }
                if (sceneryScrolling) sceneryPicker.scrollBy(sceneryLastX - e.x)
                sceneryLastX = e.x
            }
            MotionEvent.ACTION_UP -> {
                val id = pressedScenery
                pressedScenery = null
                if (sceneryScrolling) {
                    sceneryScrolling = false
                    return
                }
                if (id == null || sceneryPicker.hit(e.x, e.y) != id) return
                click()
                chooseScenery(id)
            }
            MotionEvent.ACTION_CANCEL -> pressedScenery = null
        }
    }

    /** A picked card: an unlocked scenery starts, a locked one says how to get it; or the back or pack pill. */
    private fun chooseScenery(id: String) {
        if (id == SceneryPicker.BACK) {
            screen = Screen.MAIN_MENU
            return
        }
        if (id == SceneryPicker.PACK) {
            sceneryHint = if (monetization.purchase(Entitlements.SCENERY_PACK)) null else context.getString(R.string.scenery_hint_no_shop)
            return
        }
        val s = Scenarios.byId(id) ?: return
        if (sceneryUnlocked(s)) newGame(s) else sceneryHint = lockedHint(s)
    }

    /** Asks the store for a locked scenery; the hint says how to unlock it, or that buying is not possible yet. */
    private fun lockedHint(s: Scenario): String? {
        if (monetization.purchaseScenery(s.id)) return null
        val unlock = s.unlock
        return if (unlock is Unlock.Score) {
            resources.getQuantityString(R.plurals.scenery_hint_score, unlock.packets, unlock.packets, texts.scenario(Scenarios.byId(unlock.after)!!))
        } else {
            context.getString(R.string.scenery_hint_no_shop)
        }
    }

    private fun newGame(s: Scenario) {
        highscores.lastScenery = s.id
        startGame(World(s, seed = System.currentTimeMillis()))
    }

    private fun openPauseMenu() {
        endDrag()
        placing = null
        selection = null
        tutorialGesture = false
        tutorialPressed = null
        screen = Screen.PAUSED
        autosave()
    }

    /** "Continue": the game still in memory, otherwise the autosave. */
    private fun continueGame() {
        if (gameInProgress) {
            screen = Screen.PLAYING
            return
        }
        val saved = saves.load()
        if (saved == null || saved.gameOver) {
            saves.clearLater()
            hasSave = false
            return
        }
        startGame(saved)
    }

    private fun startGame(w: World) {
        screen = Screen.PLAYING
        showWorld(w)
        gameInProgress = true
        cableType = w.unlockedCables.first()
    }

    /** Starts the tutorial from its first step; it is not a game in progress, so it is neither saved nor scored. */
    private fun startTutorial() {
        val t = Tutorial.start()
        screen = Screen.PLAYING
        showWorld(t.world, t)
        cableType = t.world.unlockedCables.first()
    }

    private fun showWorld(w: World, withTutorial: Tutorial? = null) {
        world = w
        // A new map starts facing north.
        renderers.forEach { it.camera.resetRotation() }
        growth.clear()
        celebrateAt = null
        celebratedWeek = w.rewardOffer?.week ?: -1
        tutorial = withTutorial
        tutorialGesture = false
        tutorialPressed = null
        failFocusUntil = null
        gameInProgress = false
        selection = null
        userPaused = false
        busyHintShown = false
        hintQueue.clear()
        hint = null
        hintedNews = w.lastNews
        layoutRenderers()
        clock.reset()
        endDrag()
        placing = null
        pressedCard = null
        gestureConsumed = false
    }

    /**
     * Game over while playing: record the score and drop the save, then let the camera glide to the failed device for
     * [GAME_OVER_FOCUS_SECONDS] before the result card shows.
     */
    private fun checkGameOver() {
        if (screen != Screen.PLAYING || !world.gameOver || !gameInProgress) return
        growth.sample(world)
        gameInProgress = false
        newBest = highscores.submit(world.delivered, world.scenario.id)
        saves.clearLater()
        hasSave = false
        endDrag()
        placing = null
        selection = null
        userPaused = false
        // The game is over: pending hints (e.g. what the week's news need) would only peek out under the result card.
        hint = null
        hintQueue.clear()
        val failed = world.failedNode
        if (failed == null) {
            showGameOverCard()
            return
        }
        renderer.focusOn(failed)
        failFocusUntil = animTime + GAME_OVER_FOCUS_SECONDS
        focusSkipArmed = false
    }

    private fun showGameOverCard() {
        failFocusUntil = null
        gameOverAt = animTime
        screen = Screen.GAME_OVER
    }

    /**
     * Saves the running game, if there is one: a snapshot now, the writing on the I/O thread. A write that fails (disk
     * full) shows up as a save that does not load, which "Continue" then drops.
     */
    private fun autosave() {
        if (!gameInProgress || world.gameOver) return
        saves.saveLater(world)
        hasSave = true
    }

    private fun updateSettings(s: GameSettings) {
        settingsStore.save(s)
        applySettings(s)
    }

    private fun applySettings(s: GameSettings) {
        settings = s
        sounds.enabled = s.sound
        ServiceColors.colorblind = s.colorblind
        val next = if (s.overviewMode) flat else iso
        if (next !== renderer) {
            // The other style takes over the angle, so switching styles never turns the map.
            val turn = Camera.shortestTurn(next.camera.angle, renderer.camera.angle)
            next.rotateBy(turn, next.camera.centerX, next.camera.centerY, world)
            renderer = next
        }
        // Free rotation switched on keeps a turned map as it is; switched off, it snaps to the nearest right angle.
        if (!s.freeRotation) renderers.forEach { it.camera.settleRotation(snap = true) }
    }

    private fun haptic(kind: Int) {
        if (!settings.haptics) return
        hapticPulses++
        post { performHapticFeedback(kind) }
    }

    /** The system click sound for buttons, if sound is on. */
    private fun click() {
        if (settings.sound) post { playSoundEffect(SoundEffectConstants.CLICK) }
    }

    /**
     * Fits every style to the unlocked area, keeping the HUD rows (and the tutorial bubble) free; the demo town sits
     * beside the main menu card. Waits for the first surface size.
     */
    private fun layoutRenderers() {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        val insets = if ((screen == Screen.MAIN_MENU || screen == Screen.SCENERIES) && !gameInProgress) {
            ViewInsets(surfaceWidth * 0.55f, 24 * density, 16 * density, 24 * density)
        } else if (tutorial != null) {
            tutorialOverlay.place(safeInsets.left + 16 * density, tutorialTop(), tutorialBottom())
            ViewInsets(tutorialOverlay.reservedRight(surfaceWidth) - safeInsets.left + 8 * density, hudTopReserve, 8 * density, hudBottomReserve)
        } else {
            ViewInsets(8 * density, hudTopReserve, 8 * density, hudBottomReserve)
        }
        val safe = safeInsets
        val inside = ViewInsets(insets.left + safe.left, insets.top + safe.top, insets.right + safe.right, insets.bottom + safe.bottom)
        renderers.forEach { it.layout(surfaceWidth, surfaceHeight, world, inside) }
        framedArea = world.unlocked
        framedNodes = world.nodes.size
        framedCables = world.cables.size
        growthHintPending = false
    }

    internal companion object {
        /** The part of [insets] the HUD must keep clear of: the display cutout, in pixels. */
        fun safeInsetsOf(insets: WindowInsets): ViewInsets = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> insets.getInsets(WindowInsets.Type.displayCutout())
                .let { ViewInsets(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> insets.displayCutout?.let {
                ViewInsets(it.safeInsetLeft.toFloat(), it.safeInsetTop.toFloat(), it.safeInsetRight.toFloat(), it.safeInsetBottom.toFloat())
            } ?: ViewInsets.NONE
            else -> ViewInsets.NONE
        }

        /** Lower bound per loop iteration, in case posting a frame does not block on vsync. */
        const val MIN_FRAME_NANOS = 8_000_000L
        /** Longest animation step per frame, so animations do not jump after a stall. */
        const val MAX_ANIM_STEP = 0.05f
        /** Minimum distance between two drag trail samples, in cells. */
        const val TRAIL_SPACING = 0.2f
        const val MAX_TRAIL = 256
        const val HINT_SECONDS = 2.5f
        /** Lines a long hint (or one in large text) wraps into above the bottom buttons. */
        const val MAX_HINT_LINES = 3
        /** How long the camera shows the failed device before the game-over card. */
        const val GAME_OVER_FOCUS_SECONDS = 1.6f
        /** Longer hints: what a new service needs, why a device is stuck. */
        const val LONG_HINT_SECONDS = 4f
        /** How long a tapped cable, server or router stays selected for the confirming second tap. */
        const val SELECT_SECONDS = 3f
        /** Requests waiting at one server before the game points out that tapping upgrades it. */
        const val BUSY_HINT_WAITING = 2
        /** Running seconds after which a full-screen ad whose result never came counts as closed. */
        const val AD_TIMEOUT_SECONDS = 6f
        const val COIN_COLOR = 0xFFF5C542.toInt()
        const val SELECTION_COLOR = 0xFFFFC21A.toInt()
        const val COMPASS_NORTH = 0xFFD7263D.toInt()
        const val STATE_IN_GAME = "mininetworks.inGame"
        const val STATE_IN_TUTORIAL = "mininetworks.inTutorial"
        /** A finger that moves less than this is a tap. */
        const val TAP_SLOP_DP = 12f
        const val DOUBLE_TAP_MS = 300L
        const val DOUBLE_TAP_SLOP_DP = 40f
        /** Holding a tap on an access point this long switches it to 5 GHz. */
        const val LONG_PRESS_MS = 500L
        /** The hold ring appears only after this, so a quick tap does not flash it. */
        private const val HOLD_RING_DELAY_MS = 120f
        private const val HOLD_RING_DP = 40f
        private const val HOLD_RING_COLOR = 0xFF4F6BD8.toInt()
    }
}
