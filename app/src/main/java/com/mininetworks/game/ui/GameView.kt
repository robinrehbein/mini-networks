package com.mininetworks.game.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.util.Log
import android.widget.EditText
import android.widget.Toast
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
import com.mininetworks.game.data.ProgressStore
import com.mininetworks.game.data.SaveSlot
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.Achievement
import com.mininetworks.game.game.AchievementTracker
import com.mininetworks.game.game.Achievements
import com.mininetworks.game.game.Bend
import com.mininetworks.game.game.Cable
import com.mininetworks.game.game.CableLayout
import com.mininetworks.game.game.CableSkin
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.CableUpgradeError
import com.mininetworks.game.game.ConnectError
import com.mininetworks.game.game.Cell
import com.mininetworks.game.game.CellRect
import com.mininetworks.game.game.ColorTheme
import com.mininetworks.game.game.Cosmetics
import com.mininetworks.game.game.DailyChallenge
import com.mininetworks.game.game.DailyStreak
import com.mininetworks.game.game.Demand
import com.mininetworks.game.game.Failure
import com.mininetworks.game.game.IncidentKind
import com.mininetworks.game.game.Incidents
import com.mininetworks.game.game.FixedStep
import com.mininetworks.game.game.GameMode
import com.mininetworks.game.game.PlayerStats
import com.mininetworks.game.game.GrowthRecorder
import com.mininetworks.game.game.LossTip
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.PlaceError
import com.mininetworks.game.game.RadioType
import com.mininetworks.game.game.RepairError
import com.mininetworks.game.game.RerouteError
import com.mininetworks.game.game.RouteProblem
import com.mininetworks.game.game.Scenario
import com.mininetworks.game.game.Scenarios
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.ServerUpgradeError
import com.mininetworks.game.game.SoundCue
import com.mininetworks.game.game.SoundCues
import com.mininetworks.game.game.Tutorial
import com.mininetworks.game.game.TutorialFocus
import com.mininetworks.game.game.Vec2
import com.mininetworks.game.game.WeekNews
import com.mininetworks.game.game.Wifi
import com.mininetworks.game.game.CellUpgradeError
import com.mininetworks.game.game.WifiUpgradeError
import com.mininetworks.game.game.Unlock
import com.mininetworks.game.game.World
import com.mininetworks.game.monetization.AdPlacement
import com.mininetworks.game.monetization.Entitlements
import com.mininetworks.game.monetization.Monetization
import com.mininetworks.game.monetization.MonetizationStore
import com.mininetworks.game.monetization.NoOpMonetization
import com.mininetworks.game.monetization.PlayMonetization
import com.mininetworks.game.render.CableStyles
import com.mininetworks.game.render.Camera
import com.mininetworks.game.render.Cosmetic
import com.mininetworks.game.render.DragJuice
import com.mininetworks.game.render.DragPreview
import com.mininetworks.game.render.FlatRenderer
import com.mininetworks.game.render.IncidentStyles
import com.mininetworks.game.render.IsoRenderer
import com.mininetworks.game.render.ProblemBadges
import com.mininetworks.game.render.QueueGauge
import com.mininetworks.game.render.Renderer
import com.mininetworks.game.render.ServerFocus
import com.mininetworks.game.render.ServerLabels
import com.mininetworks.game.render.ServiceColors
import com.mininetworks.game.render.TouchTargets
import com.mininetworks.game.render.TwoFingerGesture
import com.mininetworks.game.render.ViewInsets
import com.mininetworks.game.render.fill
import com.mininetworks.game.render.shade
import com.mininetworks.game.render.stroke
import com.mininetworks.game.ui.menu.AchievementTile
import com.mininetworks.game.ui.menu.AchievementsPanel
import com.mininetworks.game.ui.menu.DailyPreview
import com.mininetworks.game.ui.menu.DemoCity
import com.mininetworks.game.ui.menu.LegendEntry
import com.mininetworks.game.ui.menu.LegendIcon
import com.mininetworks.game.ui.menu.LegendPanel
import com.mininetworks.game.ui.menu.LegendSection
import com.mininetworks.game.ui.menu.MenuAction
import com.mininetworks.game.ui.menu.MenuItem
import com.mininetworks.game.ui.menu.MenuPage
import com.mininetworks.game.ui.menu.MenuPanel
import com.mininetworks.game.ui.menu.MenuPicture
import com.mininetworks.game.ui.menu.SceneryCard
import com.mininetworks.game.ui.menu.SceneryPicker
import com.mininetworks.game.ui.menu.Screen
import com.mininetworks.game.data.GameIo
import com.mininetworks.game.data.ReviewStore
import com.mininetworks.game.game.AchievementSync
import com.mininetworks.game.game.CloudProgress
import com.mininetworks.game.game.FinishedGame
import com.mininetworks.game.game.Leaderboards
import com.mininetworks.game.game.ReviewPolicy
import com.mininetworks.game.games.GameServices
import com.mininetworks.game.games.NoOpGameServices
import com.mininetworks.game.review.NoOpReviewPrompt
import com.mininetworks.game.review.ReviewPrompt
import com.mininetworks.game.share.ShareCard
import com.mininetworks.game.share.ShareSheet
import java.io.File
import java.io.IOException
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
 *    the label shows the price and, for a device, the ping it would get or that the cable is too narrow. Every node has
 *    a row of port dots ([com.mininetworks.game.render.PortDots]: filled in use, hollow free; a PC 2, a server 4, a
 *    router 6); while a cable is dragged, nodes without a free port are ringed red. A cable that fails on full ports
 *    says so ("PC hat nur 2 Anschlüsse – setz einen Router dazwischen"), the router tile pulses, and the first time
 *    in a game a tip explains the dots. While the finger is on a device, the servers of the services it asks for light
 *    up and the others dim ([ServerFocus]; from a router or radio: the servers the devices behind it wait for)
 *  - drag on empty ground or with two fingers: pan; pinch: zoom; turn two fingers: rotate the map around their midpoint
 *    (all at once); on release it eases to the nearest multiple of 45° (four corner and four side-on views) unless
 *    "free rotation" is on in the settings; two fingers side by side dragged up or down: tilt the iso view flatter or
 *    steeper ([Camera.tilt]; the flat overview has no tilt), which locks out pan, zoom and turn for that gesture;
 *    double tap on empty ground: fit the playable area
 *  - view controls in a column at the right edge, above the menu button: turn left, compass (points to north; a tap
 *    turns the map back), turn right (each tap eases 45° around the centre of the view), and in the iso view flatter
 *    and steeper (a second column beside the first where one is too tall); they keep clear of the counters, the
 *    toolbar and the hint, and step aside where no room is left; a few seconds after the map last moved they fade out
 *    (only the compass stays while the map is turned) and any pan, pinch or turn brings them back
 *  - the bottom toolbar ([layoutToolbar]) has two captioned groups: "Kabel" picks a cable technology (ISDN, DSL,
 *    Koax, Glasfaser; the coin is the price per cell; picking one names its bandwidth, speed and price), "Netzwerk"
 *    holds the devices that join several others (router, and WLAN and mast while some are in stock), each tile with
 *    its icon, one dot per port and its stock; pause and menu sit at the right
 *  - tap a server: preview its next hardware tier and price; tap again to upgrade (tier 4 is a data center on 2×2 cells)
 *  - tap a cable: upgrade it to a better picked technology; otherwise the first tap selects it and a second tap removes
 *    it for a refund; a cable cut by an excavator is repaired instead (small fee)
 *  - a selected cable shows a grab handle at each end: drag one onto another node to re-route that end, drag the middle
 *    of a bent cable to flip its L ([World.reroute]; the label shows the price difference, "+12", "−4" or "±0", and
 *    releasing anywhere else cancels); a long press on any cable selects it and grabs its nearer end at once
 *  - tap a device: which server its requests need and why they are stuck (no way, too narrow, ping too high, jam), what
 *    its oldest request wants, or which servers it needs, each with the service's token ([InlineGlyphs]); its servers
 *    light up for [FOCUS_TAP_SECONDS]
 *  - every server carries a name plate ([ServerLabels]): its service's token and its type ("Telefonzentrale"); a data
 *    center is only a server's top tier ("Mail-Server · Rechenzentrum"). Plates keep clear of devices, bubbles and the
 *    HUD and fade out when zoomed far out
 *  - drag a network tile onto the map: the device floats over the cell under the finger (red where it cannot go) and
 *    is placed where it is let go, not over the toolbar; or tap the tile, then tap an empty cell (a tap, not the start
 *    of a pan or pinch). A cell that does not work says why; tap a router without cables twice to put it back
 *  - tap an access point: next WLAN channel; hold it: switch it to 5 GHz (costs budget)
 *  - pause button: stops the clock in place, building goes on (like Mini Metro); menu button or back: pause menu
 *    (resume, settings, restart, main menu)
 *  - settings: sound, haptics, overview mode (flat instead of isometric), colorblind palette
 *  - at each week change the world pauses and [RewardDialog] shows two reward cards; tap one to pick it
 *    (the menu button stays tappable above the dialog; resuming returns to the choice)
 *
 * Sound ([SoundPlayer]): a pluck per delivery pitched by service, a click when a cable locks in (or is re-routed), a
 * soft warning when a device starts to overload, rising higher and louder as the fullest ring on the map passes 50 % and
 * 80 % (once until that ring has emptied), a falling line when the game is lost and a chime at each new week ([SoundCues] reads them from the world while playing).
 * Haptics: a tick when a dragged cable (or a re-routed end) snaps onto a target node and a pulse when it is laid or
 * re-routed; a long press that grabs a cable pulses too; a heavy click when the fullest ring passes 80 % and a long buzz
 * on game over.
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
        /** Play Games signed the player in (docs/TOP100.md C2, C6). */
        data object SignedIn : Input
        /** The Play Games cloud save arrived, or null if there is none (C6). */
        class CloudLoaded(val progress: CloudProgress?) : Input
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
    private val highscoresLazy = lazy { HighscoreStore(context) }
    private val highscores by highscoresLazy
    private val progressStore by lazy { ProgressStore(context) }
    /** Achievement stats, read on the game thread on first use (docs/TOP100.md C2). */
    private val trackerLazy = lazy { AchievementTracker(progressStore.loadStats()) }
    private val tracker by trackerLazy
    /** The daily streak (docs/TOP100.md C1), read on first use and kept in step with the store. */
    private var streakCache: DailyStreak? = null
    private val streak: DailyStreak get() = streakCache ?: progressStore.streak.also { streakCache = it }

    /** The wall clock the daily challenge follows (UTC days); tests set a fixed one. */
    internal var wallClock: () -> Long = System::currentTimeMillis
    /** True once the running daily challenge said that its day is over. */
    private var dailyExpiredHinted = false
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
    /**
     * Play Games (docs/TOP100.md C2, C3, C6); [NoOpGameServices] unless the activity sets the Play implementation. Read
     * on the game thread; its sign-in reaches the game thread as [Input.SignedIn].
     */
    @Volatile var gameServices: GameServices = NoOpGameServices
        set(value) {
            field = value
            value.onSignedIn = { inputs.add(Input.SignedIn) }
            if (value.signedIn) inputs.add(Input.SignedIn)
        }
    /** Achievement ids handed to Play Games since the last sign-in; game thread only. */
    private val syncedAchievements = HashSet<String>()

    /** The In-App Review dialog (docs/TOP100.md D1); [ReviewPolicy] decides when. */
    @Volatile var reviewPrompt: ReviewPrompt = NoOpReviewPrompt
    private val reviewStore by lazy { ReviewStore(context) }
    /** Set at a game over that earned a rating request; it is made once the game-over card shows. */
    private var reviewDue = false

    /**
     * Called on the UI thread with the written share card and its text (docs/TOP100.md D2); the activity opens the
     * share sheet ([ShareSheet.chooser]).
     */
    var onShare: ((File, String) -> Unit)? = null
    private val shareCard by lazy { ShareCard(context) }

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
    /** "Telefon überlastet": the callout under the device that lost the game, while the camera glides to it. */
    private var failCallout: String? = null
    private val calloutBox = RectF()
    private var calloutShown = false
    /** The callout's side of the device, fixed for the whole glide; null until the first frame decides it. */
    private var calloutBelow: Boolean? = null
    /** [failCallout] shortened to [calloutFitWidth] ([fitText]), refitted only when the room changes. */
    private var calloutFitted = ""
    private var calloutFitWidth = -1f

    /** The failed device's callout as last drawn, or null when none showed; for tests. */
    internal val failCalloutBox: RectF? get() = if (calloutShown) calloutBox else null

    /** The failed device's callout text, for tests. */
    internal val failCalloutText: String? get() = failCallout
    private val calloutPath = Path()
    private val calloutFill = Paint(Paint.ANTI_ALIAS_FLAG)
    /** A finger went down during the game-over focus; only its release skips the focus, not a gesture from before. */
    private var focusSkipArmed = false
    private var newBest = false
    private val menuPanel = MenuPanel(context)
    private var pressedAction: MenuAction? = null
    private val sceneryPicker = SceneryPicker(context)
    /** The mode the scenery picker starts games in (docs/TOP100.md C4); its pill switches it. */
    private var pickerMode = GameMode.NORMAL
    private val achievementsPanel = AchievementsPanel(context)
    private val legendPanel = LegendPanel(context)
    private var pressedLegend: String? = null
    private var legendDownY = 0f
    private var legendLastY = 0f
    private var legendScrolling = false
    /** [AchievementsPanel.BACK] under the finger on the achievements screen. */
    private var pressedAchievement: String? = null
    /** Vertical drag on the achievements grid. */
    private var achievementDownY = 0f
    private var achievementLastY = 0f
    private var achievementScrolling = false
    /** Tiles of the achievements screen for [tilesFor]; rebuilt when the stats change. */
    private var tiles: List<AchievementTile> = emptyList()
    private var tilesFor: PlayerStats? = null
    /** Set once this game said that an overload slows its area (endless and creative mode). */
    private var jamHintShown = false
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
    /**
     * The network tile ("router", "radio:…") a finger went down on: lifted in place it arms [placing] like a tap,
     * moved past the tap slop it carries the device onto the map ([toolDrag]).
     */
    private var toolPress: String? = null
    /** The device dragged from the toolbar and the finger's screen position; null while none is. */
    private var toolDrag: NodeKind? = null
    private var toolDragAt: Vec2? = null
    /** Top edge of the toolbar's tray in the last drawn frame (NaN before the first): a drop below it is no drop. */
    private var toolbarTop = Float.NaN
    /** The tray's raised part behind a network row above the controls in the last drawn frame, or null. */
    private var toolbarRaised: RectF? = null
    /** [animTime] when a cable last failed on full ports; the router tile pulses from then on for a moment. */
    private var routerPulseAt = Float.NEGATIVE_INFINITY
    /** When an action last failed for lack of budget: the HUD's budget line flashes red ([BUDGET_FLASH_SECONDS]). */
    private var budgetFlashAt = Float.NEGATIVE_INFINITY
    /** Set once this game explained the port dots, after the first cable that failed on full ports. */
    private var portsTipShown = false
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
    /** Keys of the one-time coaching tips shown on this install ([Coaching]); read from the settings on first use. */
    private val coachingSeen by lazy { settingsStore.coachingSeen.toMutableSet() }
    /** [animTime] of the last coaching tip: the next one waits [Coaching.GAP_SECONDS]. */
    private var coachedAt = Float.NEGATIVE_INFINITY
    /**
     * A coaching tip due for the hint line: it goes before the queued explanations. Its text is built when it shows
     * ([showCoach]), from the situation then; a tip whose situation is over by then waits for the next time.
     */
    private var coachWaiting: Coaching? = null
    /** [animTime] from which [coach] looks for a due tip again: a few times a second is enough. */
    private var nextCoachCheck = 0f
    /**
     * The coaching tip the hint line shows now. It counts as seen on this install once it was on the map for most of its
     * time ([COACH_READ_SHARE] of [coachSeconds], counted in [coachShownFor]; its timer stands while anything covers
     * it); a hint that cuts it short earlier sends it back to [coachWaiting].
     */
    private var hintCoach: Coaching? = null
    private var coachSeconds = 0f
    private var coachShownFor = 0f
    /** Where the legend's back pill leads: the pause menu it was opened from, or the game for the HUD's "?" button. */
    private var legendReturn = Screen.PAUSED
    /** Hints shown one after the other once the screen is free, e.g. what a new week's services need. */
    private val hintQueue = ArrayDeque<String>()
    /** The week news already turned into hints. */
    private var hintedNews: WeekNews? = null

    /** Short feedback above the bottom bar, e.g. why a server tap did not upgrade; shown until [hintUntil]. */
    private var hint: String? = null
    private var hintUntil = 0f
    /** The shown [hint] is an explanation to read ([LONG_HINT_SECONDS]): it may take one line more than others. */
    private var hintLong = false
    /** The shown [hint] says why an action was refused: a reddish plate with a "!" mark, for [ERROR_HINT_SECONDS]. */
    private var hintError = false
    /** What the shown [hint] is about (a tapped server, cable, a tool kind); a new hint about it replaces even an error. */
    private var hintSubject: Any? = null
    /**
     * The info hint that came while an error was showing, shown once the error had its time: only the newest (info
     * replaces info). Errors never wait: the newest one shows at once, with its buzz.
     */
    private val pendingHints = ArrayDeque<PendingHint>()
    private class PendingHint(val text: String, val seconds: Float, val subject: Any?)
    /** The spoken text of the hint line ([hintSpokenFor] as an error or not), kept while the same hint shows. */
    private var hintSpoken = ""
    private var hintSpokenFor: String? = null
    private var hintSpokenError = false
    /** Lines the coaching tip [coachWantFor] wraps into at [coachWantWidth] and [coachWantSize], kept per tip. */
    private var coachWant = 1
    private var coachWantFor: String? = null
    private var coachWantWidth = 0f
    private var coachWantSize = 0f
    /** The same at the full hint width ([coachWantWidth] is the capped card's), for windows too low for the card. */
    private var coachWantFull = 1
    private var coachWantFullWidth = 0f

    /** Lines coaching tip [tip] wraps into at text width [capped] (the card) and [full] (the whole row), cached per tip. */
    private fun coachLines(tip: String, capped: Float, full: Float, wide: Boolean): Int {
        if (coachWantFor !== tip || coachWantWidth != capped || coachWantFullWidth != full || coachWantSize != hudSub.textSize) {
            val layout = InlineGlyphs.layout(tip)
            coachWant = wrapText(layout, capped, hudSub, COACH_HINT_LINES).size
            coachWantFull = if (full == capped) coachWant else wrapText(layout, full, hudSub, COACH_HINT_LINES).size
            coachWantFor = tip
            coachWantWidth = capped
            coachWantFullWidth = full
            coachWantSize = hudSub.textSize
        }
        return if (wide) coachWantFull else coachWant
    }
    /** The drop hint ([dropHint]) of this frame names why the cell under the finger does not work. */
    private var dropHintError = false

    private var dragFrom: Node? = null
    private var dragEnd: Vec2? = null
    /**
     * Services whose servers light up ([ServerFocus]) while a drag from a device (or a router) is under way and, after
     * a tap on a device, until [focusUntil]; [focusSince] is when the highlight began, for its fade-in.
     */
    private var focusServices: Set<Service> = emptySet()
    private var focusSince = 0f
    private var focusUntil = 0f
    private val focus = ServerFocus(emptySet(), 0f)
    /** The HUD's bottom tray as last drawn, which the server plates keep clear of. */
    private val trayBox = RectF()
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

    /**
     * A finger on the selected cable ([selection]): [moving] is the end that follows it while the other one stays,
     * or null for the middle, which flips the L's bend. It re-routes the cable ([World.reroute]) once [grabbing].
     */
    private class Grab(val cable: Cable, val moving: Node?) {
        /** The end that stays; for a bend change, the cable's start. */
        val fixed: Node get() = if (moving === cable.a) cable.b else cable.a
    }
    private var grab: Grab? = null
    /** True once the grabbing finger left the tap slop (or a long press grabbed): the drag now previews the re-route. */
    private var grabbing = false
    /** Cable under a finger that has not moved yet; holding it for [LONG_PRESS_MS] selects it and grabs its nearest end. */
    private var holdCable: Cable? = null
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
    private val dailyPreview by lazy { DailyPreview(density) }
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
    /** [toolbarKey] the cameras' bottom inset was last measured for; the toolbar can grow without the map growing. */
    private var framedToolbar = -1
    private var growthHintPending = false

    /** Text sizes that follow the system font size (docs/TOP100.md A7) and grow on tablets. */
    private val textScale = TextScale.of(context)
    /** Pixels per UI dp, larger on tablets ([TextScale.uiScale]). */
    private val density = textScale.density
    /** Unlock toasts of achievements and the daily streak (docs/TOP100.md C1, C2). */
    private val toast = AchievementToast(textScale)
    private val hudText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF262B33.toInt(); typeface = Typeface.DEFAULT_BOLD; textSize = textScale.px(16f) }
    private val hudSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A4350.toInt(); textSize = textScale.px(13f) }
    /** The failed device's callout text ([drawFailCallout]): bold white on alarm red. */
    private val calloutText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = Typeface.DEFAULT_BOLD; textSize = textScale.px(16f) }
    /** The budget line where bold 16 sp does not fit the row (a narrow phone with large text): bold, a step smaller. */
    private val hudStockSmall = Paint(hudText).apply { textSize = textScale.px(13f) }
    private val hudPlate = fill(0xB8FFFFFF.toInt())
    /** The plate under the hint line: the tray's frosted look, a little denser so text stays readable over buildings. */
    private val hintPlate = fill(0xD2F4F6F1.toInt())
    /** An error hint's plate: the same frosted look tinted red, with a thin red rim, its text a dark red. */
    private val hintErrorPlate = fill(0xFAFBE4E2.toInt())
    /** A coaching tip's card: opaque white with an accent stripe, so it reads as advice and not as map noise. */
    private val coachPlate = fill(0xFFFFFFFF.toInt())
    /** Behind the budget while it is short of a cable ([budgetLow]): a pale amber pill under the dark amber text. */
    private val budgetLowPill = fill(0x4DF5A623)
    private val coachStripe = fill(0xFF1F8A70.toInt())
    private val hintErrorRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x80D7263D.toInt() }
    private val hintErrorText = Paint(hudSub).apply { color = 0xFF6B1420.toInt(); typeface = android.graphics.Typeface.DEFAULT_BOLD }
    /** The error hint's "!" mark: a white "!" in a red disc, so the kind does not rest on colour alone. */
    private val hintMarkFill = fill(PORTS_FULL_RED)
    private val hintMarkText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; color = 0xFFFFFFFF.toInt() }
    private val btnFill = fill(0xE6FFFFFF.toInt())
    private val btnActive = fill(0xFF262B33.toInt())
    private val holdRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val btnText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = textScale.px(14f) }
    /** Cable chip names when even the short ones do not fit at a large font size: capped at 130 % of 14 sp. */
    private val chipLabelSmall = Paint(btnText).apply { textSize = minOf(btnText.textSize, 14f * 1.3f * density) }
    /** Cable chip names a step smaller, so the full names fit a portrait row instead of being cut short. */
    private val chipLabelCompact = Paint(btnText).apply { textSize = btnText.textSize * 0.82f }
    private val barBg = fill(0x4D262B33)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val barFg = fill(0xFF262B33.toInt())
    private val bigText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; color = 0xFF262B33.toInt(); textSize = textScale.px(15f) }
    private val incidentText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = textScale.px(14f) }
    private val iconInk = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 3 * density }
    /** The "?" on the HUD's legend button. */
    private val helpGlyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val coinFill = fill(COIN_COLOR)
    private val chipBadgeRim = fill(0xFFFFFFFF.toInt())
    /** The frosted tray under the bottom toolbar: the map stops visibly behind the buttons instead of running under them. */
    private val trayFill = fill(0xA6F4F6F1.toInt())
    private val coinText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = textScale.px(11f); color = 0xFF5A4300.toInt() }
    /** Group captions of the toolbar ("KABEL", "NETZWERK"): small, spaced capitals that label without shouting. */
    private val captionText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5B6674.toInt(); typeface = Typeface.DEFAULT_BOLD; textSize = textScale.px(11f); letterSpacing = 0.08f
    }
    private val dividerFill = fill(0x331C2A30)
    private val trayPath = android.graphics.Path()
    private val trayLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x1F1C2A30 }
    private val trayLineFill = fill(0x1F1C2A30)
    private val tileWell = fill(TILE_WELL)
    private val tileDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val stockFill = fill(0)
    private val stockText = Paint(coinText).apply { color = 0xFFFFFFFF.toInt() }
    private val tilePulse = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = PORTS_FULL_RED }
    private val ghostFill = fill(0)
    private val ghostLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    /** Device icons of the network tiles and of a tile dragged onto the map. */
    private val toolIcons = com.mininetworks.game.render.DeviceIcons()
    private val pinFill = fill(0)
    private val pinText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = textScale.px(12f); color = 0xFFFFFFFF.toInt() }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val selectionPath = android.graphics.Path()
    private val offscreenFill = fill(0)
    private val offscreenRing = stroke(0).apply { strokeCap = Paint.Cap.ROUND }
    private val offscreenArrow = android.graphics.Path()
    /** HUD elements and off-screen chips already laid out in this frame, which a further chip keeps clear of. */
    private val offscreenTaken = ArrayList<RectF>()
    private val offscreenTroubled = ArrayList<Node>()
    private val offscreenArea = RectF()
    private val offscreenInner = RectF()
    private val offscreenProbe = RectF()
    /** Before the queue reaches the limit the chip's ring is dashed, so it reads apart from the red one without colour. */
    private var offscreenDash: android.graphics.DashPathEffect? = null
    private var offscreenDashRing = 0f
    /**
     * Most urgent first: a running ring before a nearly full queue, but a ring left full (a jam in a mode without game
     * over) last. Compares primitives, as it sorts every frame.
     */
    private val offscreenOrder = Comparator<Node> { a, b ->
        val leftFull = (if (a.overload >= 1f) 1 else 0) - (if (b.overload >= 1f) 1 else 0)
        when {
            leftFull != 0 -> leftFull
            a.overload != b.overload -> java.lang.Float.compare(b.overload, a.overload)
            else -> java.lang.Float.compare(b.pressure, a.pressure)
        }
    }
    /** Chip boxes and their arrows' hit boxes of this frame, reused as the chips' [Button] rects. */
    private val offscreenBoxes = Array(MAX_OFFSCREEN_CHIPS) { RectF() }
    private val offscreenArrowHits = Array(MAX_OFFSCREEN_CHIPS) { RectF() }
    private val offscreenArrowIds = arrayOfNulls<String>(MAX_OFFSCREEN_CHIPS)

    /** Per device with a chip: its ids and TalkBack label, rebuilt only when what the label says changes. */
    private class OffscreenChip(n: Node) {
        val id = "offscreen:${n.id}"
        val key = "hud:$id"
        var label = ""
        var full = false
        var side = -1
        var waiting = -1
        /** The frame ([offscreenFrame]) the device last counted as in trouble. */
        var seen = 0
    }
    private val offscreenChips = java.util.IdentityHashMap<Node, OffscreenChip>()
    private var offscreenWorld: World? = null
    private var offscreenFrame = 0
    private val compassPaint = fill(0)
    /** Hairline between the segments of a view-control pill. */
    private val pillDivider = fill(0x26262B33)

    private data class Button(val id: String, val rect: RectF)
    private val buttons = mutableListOf<Button>()
    /** The HUD's texts and buttons as accessibility elements, collected while drawing it. */
    private val hudNodes = ArrayList<UiNode>()
    private val accessibility = CanvasAccessibility(this, activate = { inputs.add(Input.Activate(it)) }, onFocus = { inputs.add(Input.Reveal(it)) })

    init {
        holder.addCallback(this)
        renderers.forEach { it.density = density }
        val plateNames = ServerLabels.Names(Service.entries.associateWith { texts.server(it) }, texts.dataCenter())
        renderers.forEach {
            it.serverLabels.names = plateNames
            it.serverLabels.textPx = textScale.px(ServerLabels.DEFAULT_SP)
        }
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
            screen == Screen.ACHIEVEMENTS -> nodes += achievementsPanel.nodes
            screen == Screen.LEGEND -> nodes += legendPanel.nodes
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
        // The unlock toast is a short announcement over everything; screen readers get it once it has slid in.
        toast.showing?.takeIf { toast.settled }?.let { m ->
            toast.bounds?.let { nodes += UiNode("toast", RectF(it), listOfNotNull(m.title, m.detail).joinToString(". "), UiNode.Kind.TEXT) }
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
            "achievement" -> if (screen == Screen.ACHIEVEMENTS && id == AchievementsPanel.BACK) {
                click()
                screen = Screen.MAIN_MENU
            }
            "legend" -> if (screen == Screen.LEGEND && id == LegendPanel.BACK) {
                click()
                screen = legendReturn
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
        if (failFocusUntil != null) showGameOverCard(askReview = false)
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
            if (tutorial == null && gameInProgress) {
                growth.sample(world)
                track()
            }
            world.rewardOffer?.let {
                if (tutorial == null && it.week != celebratedWeek) {
                    celebratedWeek = it.week
                    celebrateAt = animTime
                }
            }
            val now = (animTime * 1000).toLong()
            for (cue in soundCues.poll(world)) {
                sounds.play(cue, now)
                val pulse = when (cue) {
                    SoundCue.OverloadCritical -> HapticFeedbackConstants.LONG_PRESS
                    SoundCue.GameOver -> Haptics.ALARM
                    else -> continue
                }
                haptic(pulse, alarm = true)
            }
            if (tutorial == null) {
                coach()
                newsHints()
                busyServerHint()
                jamHint()
            }
        } else {
            clock.reset()
        }
        checkGameOver()
        failFocusUntil?.let { if (animTime >= it) showGameOverCard() }
        askReviewIfDue()
        followArea()
        if (selection != null && animTime >= selectionUntil && grab == null) selection = null
        if (hintCoach != null) {
            // A coaching tip that anything covers (a menu, the week reward, the placing instruction, a tool drag, the
            // hidden HUD) keeps its time for the map; one that had it all counts as read.
            if (!coachOnScreen()) hintUntil += animStep
            else if (animTime >= hintUntil) retireCoach()
            else coachShownFor += animStep
        }
        // Hints that waited behind an error or a tip come first, then a coaching tip, then the queued explanations (none
        // over a reward offer).
        if (screen == Screen.PLAYING && animTime >= hintUntil && world.rewardOffer == null) {
            val waiting = pendingHints.removeFirstOrNull()
            val tip = coachWaiting
            if (waiting != null) displayHint(waiting.text, waiting.seconds, false, waiting.subject)
            else if (tip != null) showCoach(tip)
            else hintQueue.removeFirstOrNull()?.let { showHint(it, LONG_HINT_SECONDS) }
        }
        toast.update(animTime)
        renderer.stepCamera(animStep, world)
    }

    // ---------------------------------------------------------------- achievements and daily streak (game thread)

    /** Counts the running game for achievements and the daily streak, and toasts what that unlocked (docs/TOP100.md C1, C2). */
    private fun track() {
        unlocked(tracker.observe(world))
        val daily = world.daily ?: return
        val now = wallClock()
        // Past UTC midnight the run goes on as a plain game; say so once (docs/TOP100.md C1).
        if (!daily.isToday(now) && !dailyExpiredHinted) {
            dailyExpiredHinted = true
            hintQueue.addFirst(context.getString(R.string.hint_daily_expired))
        }
        if (!daily.countsFor(streak, world.delivered, now, world.assisted)) return
        val next = streak.record(daily.day)
        streakCache = next
        progressStore.streak = next
        toast.add(AchievementToast.Message(resources.getQuantityString(R.plurals.toast_daily_counted, next.current, next.current), null))
        unlocked(tracker.dailyCounted(next))
    }

    /** A toast for each achievement in [list] (naming the cosmetic it unlocks), and the stats are stored. */
    private fun unlocked(list: List<Achievement>) {
        for (a in list) {
            val detail = Cosmetics.skinFor(a.id)?.let { context.getString(R.string.toast_new_skin, texts.skin(it)) }
                ?: Cosmetics.themeFor(a.id)?.let { context.getString(R.string.toast_new_theme, texts.theme(it)) }
            toast.add(AchievementToast.Message(context.getString(R.string.toast_achievement, texts.achievementTitle(a)), detail))
        }
        if (list.isNotEmpty()) {
            saveStats()
            syncAchievements()
        }
    }

    /** Stores the stats if they changed; never the first read (so [pause] on the UI thread never loads them). */
    private fun saveStats() {
        if (!trackerLazy.isInitialized() || !tracker.dirty) return
        progressStore.saveStats(tracker.stats)
        tracker.saved()
    }

    /** In endless and creative mode the first full overload ring says that it slows its area instead of ending the game. */
    private fun jamHint() {
        if (jamHintShown || world.mode.endsOnOverload || world.rewardOffer != null) return
        val jammed = world.nodes.firstOrNull { it.kind == NodeKind.CLIENT && it.overload >= 1f } ?: return
        jamHintShown = true
        hintQueue.addFirst(context.getString(R.string.hint_endless_jam, texts.node(jammed)))
    }

    /** The tiles of the achievements screen, rebuilt only when the stats changed. */
    private fun achievementTiles(): List<AchievementTile> {
        val stats = tracker.stats
        if (stats === tilesFor) return tiles
        tilesFor = stats
        tiles = Achievements.all.map { a ->
            val reached = a.reached(stats)
            val reward = Cosmetics.skinFor(a.id)?.let { context.getString(R.string.ach_reward_skin, texts.skin(it)) }
                ?: Cosmetics.themeFor(a.id)?.let { context.getString(R.string.ach_reward_theme, texts.theme(it)) }
            AchievementTile(
                id = a.id,
                title = texts.achievementTitle(a),
                description = texts.achievementDescription(a),
                progressText = if (reached) context.getString(R.string.ach_reached) else context.getString(R.string.ach_progress, a.progress(stats), a.target),
                progress = a.progress(stats).toFloat() / a.target,
                reached = reached,
                reward = reward,
                stateLabel = context.getString(if (reached) R.string.ach_reached else R.string.ach_open),
            )
        }
        return tiles
    }

    /** Stats of the achievements, for tests and screenshots. */
    internal val achievementStats: PlayerStats get() = run { ensureLoaded(); tracker.stats }

    /** Replaces the achievement stats (and stores them), for tests and screenshots. */
    internal fun setAchievementStats(stats: PlayerStats) {
        ensureLoaded()
        progressStore.saveStats(stats)
        tracker.replace(stats)
        applySettings(settings)
    }

    /** The toast on screen, for tests. */
    internal val shownToast: AchievementToast.Message? get() = toast.showing

    /** The daily streak, for tests. */
    internal val dailyStreak: DailyStreak get() = run { ensureLoaded(); streak }

    /** Screen rectangle of the back pill on the achievements screen, or null, for tests. */
    internal fun achievementTarget(id: String): RectF? = if (screen == Screen.ACHIEVEMENTS) achievementsPanel.targetOf(id) else null

    /** Screen rectangle of the tile of achievement [id] in the last drawn frame, for tests. */
    internal fun achievementTile(id: String): RectF? = if (screen == Screen.ACHIEVEMENTS) achievementsPanel.tileOf(id) else null

    /** Where the legend's back pill ([LegendPanel.BACK]) was drawn in the last frame, for tests. */
    internal fun legendTarget(id: String): RectF? = if (screen == Screen.LEGEND) legendPanel.targetOf(id) else null

    /** Where the legend's tile [id] was drawn in the last frame (maybe scrolled out of view), for tests. */
    internal fun legendTile(id: String): RectF? = if (screen == Screen.LEGEND) legendPanel.tileOf(id) else null

    /** The mode the picker starts games in, for tests. */
    internal val sceneryMode: GameMode get() = pickerMode

    /**
     * When the map grew: widen every style's zoom range, follow the new area, and say so once the reward is picked.
     * When nodes or cables came or went: keep them in a portrait framing ([Renderer.onContentChanged]).
     */
    private fun followArea() {
        if (world.unlocked != framedArea) {
            framedArea = world.unlocked
            framedToolbar = toolbarKey()
            refreshToolbarInset()
            renderers.forEach { it.onAreaChanged(world) }
            growthHintPending = true
        } else if (screen == Screen.PLAYING && toolbarKey() != framedToolbar) {
            // A new cable chip or network tile (a week reward) may wrap the toolbar into a taller tray or a raised row.
            framedToolbar = toolbarKey()
            if (refreshToolbarInset()) renderers.forEach { it.onAreaChanged(world) }
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

    /**
     * The next one-time coaching tip ([Coaching]) that is due, at most one per [Coaching.GAP_SECONDS] and one at a
     * time; it shows before the queued explanations ([showCoach]) and counts as shown on this install once it was on
     * the map for its full time ([retireCoach]). None in a daily challenge, none while the week reward is open; the
     * pause tip not while the clock already stands.
     */
    private fun coach() {
        if (world.daily != null || world.gameOver || world.rewardOffer != null) return
        if (coachWaiting != null || hintCoach != null) return
        // The tips need no frame-exact timing: a few checks a second keep their cost off most frames.
        if (animTime < nextCoachCheck || coachingSeen.size == Coaching.entries.size) return
        nextCoachCheck = animTime + COACH_CHECK_SECONDS
        // Within the gap only a tip that cannot wait (the excavator's warning lasts seconds).
        val urgentOnly = animTime - coachedAt < Coaching.GAP_SECONDS
        val (tip, _) = Coaching.next(world, coachingSeen, paused = userPaused, urgentOnly = urgentOnly) ?: return
        // The news line that introduces a new service comes before the tip about old devices wanting it.
        if (tip == Coaching.NEW_SERVICE && hintQueue.isNotEmpty()) return
        coachWaiting = tip
    }

    /**
     * Puts the waiting coaching [tip] on the hint line, for longer than other explanations: it says more. Its situation
     * is looked at again first: a tip that no longer applies (the excavator gone, the jam cleared, the clock already
     * stopped) is dropped unread and comes back the next time it is due; one that still does names today's subject.
     */
    private fun showCoach(tip: Coaching) {
        coachWaiting = null
        val subject = if (tip.appliesTo(world) && !(tip == Coaching.PAUSE && userPaused)) tip.due(world) else null
        if (subject == null) return
        val text = coachingText(tip, subject)
        coachSeconds = maxOf(COACH_HINT_SECONDS, text.length * COACH_SECONDS_PER_CHAR)
        displayHint(text, coachSeconds, false, null)
        hintCoach = tip
        coachShownFor = 0f
        coachedAt = animTime
        // The tip says more than the short per-game reminder about the same server.
        if (tip == Coaching.SERVER_QUEUE) busyHintShown = true
    }

    /** True while the hint line really shows the coaching tip: no menu, reward dialog, placing or tool drag over it. */
    private fun coachOnScreen(): Boolean =
        screen == Screen.PLAYING && world.rewardOffer == null && placing == null && toolDrag == null && !hudHidden

    /**
     * The shown coaching tip leaves the hint line: read once it was on the map for most of its time (stored as seen),
     * else it waits to show again once the line is free.
     */
    private fun retireCoach() {
        val tip = hintCoach ?: return
        hintCoach = null
        if (animTime >= hintUntil || coachShownFor >= coachSeconds * COACH_READ_SHARE) learned(tip)
        else coachWaiting = tip
    }

    /**
     * The player read [tip] or just did what it teaches (upgraded a server, repaired a cable, placed a radio, stopped
     * the clock): it is stored as seen on this install and never comes again, not even a waiting copy.
     */
    private fun learned(tip: Coaching) {
        if (coachWaiting == tip) coachWaiting = null
        if (hintCoach == tip) hintCoach = null
        if (tip.key in coachingSeen) return
        coachingSeen += tip.key
        settingsStore.markCoachingSeen(tip.key)
    }

    private fun coachingText(tip: Coaching, subject: Any): String = when (tip) {
        Coaching.INCIDENT -> context.getString(R.string.coach_incident)
        Coaching.PAUSE -> context.getString(R.string.coach_pause)
        Coaching.SERVER_QUEUE -> context.getString(R.string.coach_server_queue, texts.node(subject as Node))
        Coaching.JAM -> context.getString(R.string.coach_jam, texts.node(subject as Node))
        Coaching.CABLE_UPGRADE -> context.getString(R.string.coach_cable_upgrade)
        Coaching.NEW_SERVICE -> Coaching.newService(subject).let { (n, s) -> context.getString(R.string.coach_new_service, texts.node(n), texts.service(s)) }
        Coaching.RADIO_STOCK -> context.getString(R.string.coach_radio_stock, texts.radio(subject as RadioType))
    }

    /** The coaching tips shown on this install, for tests. */
    internal val coachingShown: Set<String> get() = coachingSeen

    /** The explanations waiting to be shown, for tests. */
    internal val queuedHints: List<String> get() = hintQueue.toList()

    /** The first time in a game that requests queue at a server, say that tapping it upgrades it. */
    private fun busyServerHint() {
        if (busyHintShown || world.rewardOffer != null) return
        // The coaching tip about it, still to come, says the same at more length.
        if (world.daily == null && Coaching.SERVER_QUEUE.key !in coachingSeen && Coaching.SERVER_QUEUE.appliesTo(world)) return
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
        pinCount = 0
        renderer.serverLabels.focus = if (playing) serverFocus() else null
        renderer.draw(canvas, world, if (playing) dragPreview() else null, animTime)
        if (playing) {
            drawSelection(canvas)
            drawIncidentPins(canvas)
            drawHoldProgress(canvas)
        }
        if (hudVisible && !hudHidden) drawHud(canvas) else hudNodes.clear()
        if (playing) drawToolGhost(canvas)
        val callout = playing && drawFailCallout(canvas)
        calloutShown = callout
        // Read out too: during the glide it names the device that failed before the card comes.
        if (callout) failCallout?.let { hudNodes += UiNode("hud:callout", RectF(calloutBox), it, UiNode.Kind.TEXT) }
        // The server plates of the next frame keep clear of the HUD just drawn (its bottom tray included).
        renderer.serverLabels.clearReserved()
        for (n in hudNodes) renderer.serverLabels.reserve(n.bounds)
        if (hudNodes.isNotEmpty() && !trayBox.isEmpty) renderer.serverLabels.reserve(trayBox)
        if (callout) renderer.serverLabels.reserve(calloutBox)
        // ... and of the incidents' countdown pins.
        overlayPins(renderer.serverLabels)
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
        menuPage()?.let { page ->
            // A centred card moves below an achievement toast rather than under it (the main menu's card sits beside).
            val toastRoom = if (page.hero) 0f else toast.reservedTop()
            val safe = if (toastRoom > 0f) ViewInsets(safeInsets.left, maxOf(safeInsets.top, toastRoom), safeInsets.right, safeInsets.bottom) else safeInsets
            menuPanel.draw(canvas, page, surfaceWidth, surfaceHeight, pressedAction, safe)
        }
        if (screen == Screen.SCENERIES) {
            sceneryPicker.draw(
                canvas, context.getString(R.string.scenery_title), context.getString(R.string.menu_back), sceneryCards(),
                sceneryHint ?: texts.modeDescription(pickerMode), surfaceWidth, surfaceHeight, pressedScenery, packLabel(), safeInsets,
                mode = context.getString(R.string.mode_pill, texts.mode(pickerMode)),
            )
        }
        if (screen == Screen.ACHIEVEMENTS) {
            val all = Achievements.all.size
            achievementsPanel.draw(
                canvas, context.getString(R.string.achievements_title),
                context.getString(R.string.achievements_count, Achievements.all.count { it.reached(tracker.stats) }, all),
                context.getString(R.string.menu_back), achievementTiles(), surfaceWidth, surfaceHeight, pressedAchievement, safeInsets,
            )
        }
        if (screen == Screen.LEGEND) {
            legendPanel.draw(
                canvas, context.getString(R.string.menu_legend), context.getString(R.string.menu_back), legendSections(),
                surfaceWidth, surfaceHeight, pressedLegend, animTime, safeInsets,
            )
        }
        toast.draw(canvas, surfaceWidth, animTime, safeInsets)
        publishAccessibility()
    }

    private fun drawHudButton(canvas: Canvas, r: RectF, label: String, active: Boolean) {
        val h = r.height()
        canvas.drawRoundRect(r, h / 2, h / 2, if (active) btnActive else btnFill)
        btnText.color = if (active) 0xFFFFFFFF.toInt() else 0xFF262B33.toInt()
        canvas.drawText(label, r.centerX(), r.centerY() + btnText.textSize * 0.35f, btnText)
    }

    /**
     * Hides the HUD and frees its rows for the map: for clean captures such as the store's marketing pictures, never
     * set by the game itself. Set it before the first frame at a size, so the map is framed without the HUD rows.
     */
    internal var hudHidden = false

    /**
     * The HUD shows under the in-game menus, not under the main menu, and not under the game-over card: the round is
     * over, and its pills peeking out at the card's edges read as clutter (judge panel).
     */
    private val hudVisible
        get() = when (screen) {
            Screen.MAIN_MENU, Screen.SCENERIES, Screen.DAILY, Screen.ACHIEVEMENTS, Screen.GAME_OVER, Screen.LEGEND -> false
            Screen.SETTINGS, Screen.APPEARANCE -> settingsReturn != Screen.MAIN_MENU
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
            // Another game: an error of the one before neither holds back nor waits for its hints.
            clearHints()
        }
        if (screen != null) this.screen = screen
        val restyled = style != null && style != renderer.name
        if (style != null) renderer = renderers.firstOrNull { it.name == style } ?: throw IllegalArgumentException("unknown style $style")
        animTime = time
        surfaceWidth = width
        surfaceHeight = height
        if (changed) layoutRenderers()
        checkGameOver()
        // A fresh layout has no HUD boxes and pins yet that the server plates keep clear of (they are taken from the
        // frame before): a first pass lays them out, as the frame before would on a device.
        if (changed || restyled) drawFrame(canvas)
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

    /** Top edge of the toolbar's frosted tray as last drawn (NaN before the first frame), for tests. */
    internal val toolbarTopEdge: Float get() = toolbarTop

    /** The toolbar's raised network row as last drawn, or null, for tests. */
    internal val toolbarRaisedBox: RectF? get() = toolbarRaised?.let { RectF(it) }

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

    /**
     * Screen rectangle of the HUD button [id] ("menu", "pause", "cable:…", and the network tiles "router", "radio:…") in
     * the last drawn frame, for tests.
     */
    internal fun hudTarget(id: String): RectF? = buttons.firstOrNull { it.id == id }?.rect

    /** Bounds of the HUD element [key] ("hud:status" …) in the frame just drawn, for tests. */
    internal fun hudBounds(key: String): RectF? = hudNodes.firstOrNull { it.key == key }?.bounds

    /** The accessibility layer, for tests; set [CanvasAccessibility.forceActive] to collect elements without a service. */
    internal val accessibilityLayer: CanvasAccessibility get() = accessibility

    /** The last sounds played (only while sound is on) with their rate, newest last, for tests. */
    internal val playedSounds: List<Pair<Sound, Float>> get() = sounds.played

    /** The volume of each of [playedSounds], for tests. */
    internal val playedVolumes: List<Float> get() = sounds.volumes

    /** The accent line of the menu card on top (a new best, the daily streak), or null, for tests. */
    internal val menuHighlight: String? get() = menuPage()?.highlight

    /** The text lines of the menu card on top (game over, pause …), empty without one, for tests. */
    internal val menuLines: List<String> get() = menuPage()?.lines ?: emptyList()

    /** Why the last tapped scenery is locked (under the picker's cards), or null, for tests. */
    internal val shownSceneryHint: String? get() = sceneryHint

    /** The hint line above the bottom bar right now, or null, for tests. */
    internal val shownHint: String? get() = hint?.takeIf { animTime < hintUntil }?.let(InlineGlyphs::plain)

    /** Whether [shownHint] is an error (a refused action), drawn on the reddish plate with a "!" mark; for tests. */
    internal val shownHintIsError: Boolean get() = shownHint != null && hintError

    /** Hints waiting behind a showing error, in order; for tests. */
    internal val pendingHintTexts: List<String> get() = pendingHints.map { InlineGlyphs.plain(it.text) }

    /** True while the clock is stopped in place by the pause button, for tests. */
    internal val pausedInPlace: Boolean get() = userPaused

    /** The cable, server or router a second tap acts on, or null, for tests. */
    internal val selected: Any? get() = selection

    /** Screen points of the selected cable's grab handles (start end first), empty without them, for tests. */
    internal fun handleTargets(): List<Vec2> =
        (selection as? Cable)?.takeIf(::canReroute)?.let { c -> handlePoints(c.layout).map(renderer::toScreen) } ?: emptyList()

    /** True while a tap has armed placing a router or radio, for tests. */
    internal val placingArmed: Boolean get() = placing != null

    /** True while the router tile pulses after a cable failed on full ports, for tests. */
    internal val routerPulsing: Boolean get() = animTime - routerPulseAt in 0f..ROUTER_PULSE_SECONDS

    /**
     * The budget line's layout in the HUD as last laid out, for tests: its text size, whether budget and routers take a
     * line each, and whether the counters sit below the date.
     */
    internal val hudStockLayout: Triple<Float, Boolean, Boolean>
        get() = HudTop(surfaceWidth - safeInsets.left - safeInsets.right - 32 * density)
            .let { Triple(it.stockPaint.textSize, it.stockTwoLines, it.stacked) }

    /** Text size of the date and the packets, for tests. */
    internal val hudTextSize: Float get() = hudText.textSize

    /** True while the HUD's budget line flashes after an action failed for lack of budget, for tests. */
    internal val budgetFlashing: Boolean get() = !world.unlimited && animTime - budgetFlashAt in 0f..BUDGET_FLASH_SECONDS

    /** Number of haptic pulses sent (only counted while haptics are on), for tests. */
    internal var hapticPulses = 0
        private set

    /** Number of error pulses ([Haptics.ERROR]) among [hapticPulses], for tests. */
    internal var errorPulses = 0
        private set

    /** Haptic pulses of the overload alarm (a ring past 80 %, a lost game) among [hapticPulses], for tests. */
    internal var alarmPulses = 0
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
        if (grabbing) return reroutePreview()
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
            world.unlimited -> texts.cable(cableType)
            else -> context.getString(R.string.drag_cost, texts.cable(cableType), world.cableCost(layout, cableType))
        }
        // What the cable would mean for the device at one of its ends: too narrow for a service, or the ping it gets.
        val client = if (from.kind == NodeKind.CLIENT) from else target?.takeIf { it.kind == NodeKind.CLIENT }
        val other = if (client === from) target else from
        val check = if (error == null && client != null && other != null) world.checkCable(client, other, cableType) else null
        val portsFull = error == ConnectError.FROM_PORTS_FULL || error == ConnectError.TO_PORTS_FULL
        val detail = if (portsFull) context.getString(R.string.ports_add_router) else check?.let {
            if (it.problem == RouteProblem.TOO_NARROW) context.getString(R.string.drag_too_narrow, texts.service(it.service), it.service.bandwidth)
            else context.getString(R.string.drag_ping, texts.service(it.service), it.pingMs!!.roundToInt(), it.limitMs!!)
        }
        return DragPreview(
            from = from, end = end, target = target, type = cableType, layout = layout, blocked = error != null, label = label,
            detail = detail, detailWarning = check?.problem != null || portsFull, trail = dragTrail.toList(),
        )
    }

    private fun dragBend(from: Cell, to: Cell): Bend? = CableLayout.suggestBend(from, to, dragTrail)

    /** Where the [grab] would re-route its cable: the end that stays, the node under the finger (if any) and the bend. */
    private class ReroutePlan(val fixed: Node, val target: Node?, val toCell: Cell, val bend: Bend?)

    private fun reroutePlan(): ReroutePlan? {
        val g = grab ?: return null
        val end = dragEnd ?: return null
        val fixed = g.fixed
        if (g.moving == null) {
            // The middle: the bend whose corner is nearer the finger (in world space, so any camera turn or tilt agrees).
            val other = g.cable.other(fixed)
            val h = Cell(other.cell.x, fixed.cell.y).center
            val v = Cell(fixed.cell.x, other.cell.y).center
            val bend = if (hypot(end.x - v.x, end.y - v.y) < hypot(end.x - h.x, end.y - h.y)) Bend.VERTICAL_FIRST else Bend.HORIZONTAL_FIRST
            return ReroutePlan(fixed, other, other.cell, bend)
        }
        val target = dragEndScreen?.let { pickNode(it.x, it.y, except = fixed) }
        val toCell = target?.cell ?: Cell(floor(end.x).toInt(), floor(end.y).toInt())
        return ReroutePlan(fixed, target, toCell, dragBend(fixed.cell, toCell))
    }

    /**
     * The re-route under the finger: the new way from the end that stays, the old cable as a dashed ghost, a handle on
     * the end that stays, and a label with the price difference ("+12", "−4", "±0") or why it does not work.
     */
    private fun reroutePreview(): DragPreview? {
        val g = grab ?: return null
        val plan = reroutePlan() ?: return null
        val end = dragEnd ?: return null
        val c = g.cable
        val target = plan.target
        val layout = world.planLayout(plan.fixed.cell, plan.toCell, plan.bend)
        val error = target?.let { world.rerouteError(c, plan.fixed, it, plan.bend) }
        val blocked = error != null && error != RerouteError.UNCHANGED
        val label = when {
            target == null || !blocked && world.unlimited -> texts.cable(c.type)
            error != null -> texts.rerouteError(error, target) ?: texts.cable(c.type)
            else -> context.getString(R.string.hint_two_parts, texts.cable(c.type), signed(world.rerouteCost(c, plan.fixed, target, plan.bend)))
        }
        val client = if (plan.fixed.kind == NodeKind.CLIENT) plan.fixed else target?.takeIf { it.kind == NodeKind.CLIENT }
        val other = if (client === plan.fixed) target else plan.fixed
        val check = if (!blocked && client != null && other != null) world.checkCable(client, other, c.type, plan.bend) else null
        val detail = check?.let {
            if (it.problem == RouteProblem.TOO_NARROW) context.getString(R.string.drag_too_narrow, texts.service(it.service), it.service.bandwidth)
            else context.getString(R.string.drag_ping, texts.service(it.service), it.pingMs!!.roundToInt(), it.limitMs!!)
        }
        val handles = handlePoints(layout)
        return DragPreview(
            from = plan.fixed, end = end, target = target, type = c.type, layout = layout, blocked = blocked, label = label,
            detail = detail, detailWarning = check?.problem != null, trail = dragTrail.toList(), replaces = c,
            // Moving an end, the finger is the other handle; changing the bend, both ends stay and keep theirs.
            handles = if (g.moving == null) handles else listOf(handles[0]),
            labelAt = if (g.moving == null) end else null,
        )
    }

    /** A price difference as the label shows it: "+12", "−4" or "±0". */
    private fun signed(diff: Int) = when {
        diff > 0 -> "+$diff"
        diff < 0 -> "−${-diff}"
        else -> "±0"
    }

    /**
     * World points of the two grab handles on [layout], start end first: on the cable just outside its end nodes, at
     * least [HANDLE_GAP_DP] from the node's center on screen, never past 40 % of the cable, so they never meet.
     */
    private fun handlePoints(layout: CableLayout): List<Vec2> {
        val length = layout.length
        if (length <= 0f) return listOf(layout.start, layout.end)
        val gap = maxOf(HANDLE_GAP_CELLS, HANDLE_GAP_DP * density / renderer.unitPx).coerceAtMost(length * 0.4f)
        return listOf(layout.pointAt(gap / length), layout.pointAt(1f - gap / length))
    }

    /**
     * The grab a finger going down at ([sx], [sy]) starts on the selected cable, if any: a handle within
     * [HANDLE_TOUCH_DP] (unless a node is nearer, so a node next to a handle still starts its own cable), or the middle
     * of a bent cable. A cable an excavator is at has no handles: it cannot be re-routed.
     */
    private fun grabAt(sx: Float, sy: Float): Grab? {
        val c = (selection as? Cable)?.takeIf { canReroute(it) } ?: return null
        val p = Vec2(sx, sy)
        val (ha, hb) = handlePoints(c.layout).map(renderer::toScreen)
        val da = hypot(ha.x - p.x, ha.y - p.y)
        val db = hypot(hb.x - p.x, hb.y - p.y)
        val node = pickNode(sx, sy)
        val dn = node?.footprint?.minOf { renderer.toScreen(it.center).let { q -> hypot(q.x - p.x, q.y - p.y) } }
        if (minOf(da, db) <= HANDLE_TOUCH_DP * density && (dn == null || minOf(da, db) < dn)) return Grab(c, if (da <= db) c.a else c.b)
        val bent = c.layout.waypoints.size > 2
        if (node == null && bent && renderer.cableAtScreen(world, sx, sy, TouchTargets.cableRadiusPx(renderer, density)) === c) return Grab(c, null)
        return null
    }

    private fun canReroute(c: Cable) = c in world.cables && world.incidents.none { it.cable === c }

    /** A long press on [cable] fired: it is selected and its end nearer the finger follows the finger from now on. */
    private fun grabByHold(cable: Cable) {
        holdCable = null
        panArmed = false
        select(cable)
        if (!canReroute(cable)) {
            holdFired = true
            refuse(context.getString(R.string.reroute_error_incident), subject = cable)
            return
        }
        val p = Vec2(dragEndScreen?.x ?: downX, dragEndScreen?.y ?: downY)
        val a = renderer.toScreen(cable.a.center)
        val b = renderer.toScreen(cable.b.center)
        grab = Grab(cable, if (hypot(a.x - p.x, a.y - p.y) <= hypot(b.x - p.x, b.y - p.y)) cable.a else cable.b)
        grabbing = true
        dragTrail.clear()
        haptic(HapticFeedbackConstants.LONG_PRESS)
        trackDrag(p.x, p.y)
    }

    /**
     * Releasing a grab that moved: re-routes the cable if the finger is on a node that works; on a node that does not,
     * says why; anywhere else it cancels.
     */
    private fun finishReroute(sx: Float, sy: Float) {
        trackDrag(sx, sy)
        val g = grab ?: return
        val plan = reroutePlan() ?: return
        val target = plan.target ?: return
        val c = g.cable
        val error = world.rerouteError(c, plan.fixed, target, plan.bend)
        if (error != null) {
            // Dropped where it cannot go: say why (the drag's label is gone now), except for an unchanged layout.
            if (error == RerouteError.PORTS_FULL) portsFull(target)
            else texts.rerouteError(error, target)?.let { refuse(it, noBudget = error == RerouteError.NO_BUDGET, subject = c) }
            return
        }
        val diff = world.rerouteCost(c, plan.fixed, target, plan.bend)
        if (!world.reroute(c, plan.fixed, target, plan.bend)) return
        world.cableBetween(plan.fixed, target)?.let(::select)
        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
        sounds.play(Sound.CABLE)
        showHint(
            when {
                world.unlimited || diff == 0 -> context.getString(R.string.hint_cable_rerouted)
                diff > 0 -> context.getString(R.string.hint_cable_rerouted_paid, diff)
                else -> context.getString(R.string.hint_cable_rerouted_refund, -diff)
            },
            subject = c,
        )
    }

    /**
     * The servers to highlight this frame: while a finger that went down on a device (or a router) is on the map, and
     * for [FOCUS_TAP_SECONDS] after a tap on a device; fading in over [FOCUS_FADE_SECONDS] and out at the end.
     */
    private fun serverFocus(): ServerFocus? {
        if (focusServices.isEmpty() || placing != null) return null
        val holding = dragFrom != null && holdAp == null
        if (!holding && animTime >= focusUntil) return null
        val fadeIn = ((animTime - focusSince) / FOCUS_FADE_SECONDS).coerceIn(0f, 1f)
        val fadeOut = if (holding) 1f else ((focusUntil - animTime) / FOCUS_FADE_SECONDS).coerceIn(0f, 1f)
        focus.services = focusServices
        focus.strength = minOf(fadeIn, fadeOut)
        return focus
    }

    /**
     * What a drag from [n] is looking for: every service a device asks for; for a router or radio, what the devices
     * already behind it wait for (found over cables and radio links, not through servers). Empty for anything else.
     */
    private fun servicesSought(n: Node): Set<Service> = when (n.kind) {
        NodeKind.CLIENT -> n.device!!.services.toSet()
        NodeKind.ROUTER, NodeKind.ACCESS_POINT, NodeKind.CELL_TOWER -> {
            val seen = HashSet<Node>().apply { add(n) }
            val queue = ArrayDeque<Node>().apply { add(n) }
            val out = LinkedHashSet<Service>()
            while (queue.isNotEmpty()) {
                val at = queue.removeFirst()
                if (at.kind == NodeKind.CLIENT) out += at.pending
                for (c in world.cables) if (c.connects(at)) c.other(at).let { if (it.kind != NodeKind.SERVER && seen.add(it)) queue += it }
                for (l in world.radioLinks) if (l.connects(at)) l.other(at).let { if (it.kind != NodeKind.SERVER && seen.add(it)) queue += it }
            }
            out
        }
        NodeKind.SERVER -> emptySet()
    }

    private fun trackDrag(sx: Float, sy: Float) {
        val p = renderer.toWorld(sx, sy)
        dragEnd = p
        dragEndScreen = Vec2(sx, sy)
        val from = dragFrom ?: grab?.takeIf { grabbing && it.moving != null }?.fixed
        val target = from?.let { pickNode(sx, sy, except = it) }
        if (target !== snapTarget) {
            snapTarget = target
            if (target != null) haptic(HapticFeedbackConstants.CLOCK_TICK)
        }
        val last = dragTrail.lastOrNull()
        if ((last == null || hypot(p.x - last.x, p.y - last.y) >= TRAIL_SPACING) && dragTrail.size < MAX_TRAIL) dragTrail += p
    }

    /** Drops the gesture in progress: a half-drawn cable, a re-route, a pan, and a hold on an access point or cable. */
    private fun endDrag() {
        endToolDrag()
        // The servers' highlight fades out instead of vanishing with the finger (a tap keeps it longer, explainClient).
        if (dragFrom != null && focusServices.isNotEmpty()) focusUntil = maxOf(focusUntil, animTime + FOCUS_FADE_SECONDS)
        holdAp = null
        holdCable = null
        grab = null
        grabbing = false
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
        val m = HudTop(surfaceWidth - safeInsets.left - safeInsets.right - 32 * density)
        val panelRight = tutorialOverlay.reservedRight(surfaceWidth)
        val underCounters = m.stacked || panelRight > surfaceWidth - safeInsets.right - 16 * density - m.rightW - 8 * density
        val block = if (underCounters) m.bottom else m.leftBottom
        return safeInsets.top + maxOf(60 * density, 16 * density + block + 8 * density)
    }

    /**
     * The HUD's top rows, measured for a row [width] px wide: the date with its week bar (and clock) at the left, the
     * packets, budget and vouchers at the right. Offsets are from the top of the HUD area. When both blocks do not fit
     * side by side (a narrow window with large text), the counters move below the date.
     */
    private inner class HudTop(width: Float) {
        val date: String = context.getString(R.string.hud_date, world.year, world.week)
        val clock: String? = clockLabel()
        val delivered: String = resources.getQuantityString(R.plurals.hud_delivered, world.delivered, world.delivered)
        /**
         * The budget and router line with its coin and router markers ([InlineGlyphs]); [stock] is it as read aloud.
         * The budget part is [stockLayout] up to [stockBudgetEnd], the router part starts at [stockRouterStart].
         */
        val stockText: String = stockLine()
        val stockLayout: String = stockLayoutCache
        val stock: String = stockPlainCache
        val stockBudgetEnd = stockBudgetEndCache
        val stockRouterStart = stockRouterStartCache
        val vouchers: String? =
            if (world.serverVouchers > 0) resources.getQuantityString(R.plurals.hud_vouchers, world.serverVouchers, world.serverVouchers) else null
        val barW = 110 * density
        val dateBaseline = hudText.textSize
        val barY = dateBaseline + 8 * density
        val clockBaseline = barY + WEEK_BAR_DP * density + 6 * density + hudSub.textSize
        val leftW = maxOf(hudText.measureText(date), barW, clock?.let { hudSub.measureText(it) } ?: 0f)
        val leftBottom = if (clock != null) clockBaseline + hudSub.descent() else barY + WEEK_BAR_DP * density
        /**
         * The line as prominent as the date and the packets (bold 16 sp) where it fits beside the date; else a step
         * smaller, then budget and routers on a line each, and only then the counters move below the date (with the
         * same steps over the whole row). The tutorial keeps its HUD calm where its panel reaches under the counters (a
         * narrow window), so the panel stays low and clear of the toolbar: bold a step smaller there.
         */
        val stockPaint: Paint
        val stockTwoLines: Boolean
        /** Width of the line with budget and routers on a line each. */
        private fun twoLineW(p: Paint) = maxOf(p.measureText(stockLayout, 0, stockBudgetEnd), p.measureText(stockLayout, stockRouterStart, stockLayout.length))
        init {
            val oneW = hudText.measureText(stockLayout)
            val othersW = maxOf(hudText.measureText(delivered), vouchers?.let { hudSub.measureText(it) } ?: 0f)
            val calm = tutorial != null &&
                tutorialOverlay.reservedRight(surfaceWidth) > surfaceWidth - safeInsets.right - 16 * density - maxOf(othersW, oneW) - 8 * density
            val first = if (calm) hudStockSmall else hudText
            // A plain choice without a local closure: the HUD is laid out every frame.
            val beside = width - leftW - 16 * density
            var pick = if (othersW <= beside) stockFit(first, beside) else STOCK_NONE
            if (pick == STOCK_NONE) pick = stockFit(first, width)
            stockPaint = if (pick == STOCK_FIRST_ONE || pick == STOCK_FIRST_TWO) first else hudStockSmall
            stockTwoLines = pick != STOCK_FIRST_ONE && pick != STOCK_SMALL_ONE
        }
        /** Which step of the stock line fits in [room] with [first] as the large paint: one of the STOCK_ codes. */
        private fun stockFit(first: Paint, room: Float): Int = when {
            first.measureText(stockLayout) <= room -> STOCK_FIRST_ONE
            hudStockSmall.measureText(stockLayout) <= room -> STOCK_SMALL_ONE
            twoLineW(first) <= room -> STOCK_FIRST_TWO
            twoLineW(hudStockSmall) <= room -> STOCK_SMALL_TWO
            else -> STOCK_NONE
        }
        val stockW: Float = if (stockTwoLines) twoLineW(stockPaint) else stockPaint.measureText(stockLayout)
        val rightW = maxOf(hudText.measureText(delivered), stockW, vouchers?.let { hudSub.measureText(it) } ?: 0f)
        val stacked = leftW + rightW + 16 * density > width
        val rightTop = if (stacked) leftBottom + 8 * density else 0f
        val deliveredBaseline = rightTop + hudText.textSize
        val stockBaseline = deliveredBaseline + stockPaint.textSize * 1.35f
        /** The router line's baseline where budget and routers take a line each, else [stockBaseline]. */
        val routerBaseline = if (stockTwoLines) stockBaseline + stockPaint.textSize * 1.25f else stockBaseline
        val voucherBaseline = routerBaseline + stockPaint.descent() + hudSub.textSize * 1.2f
        val countersBottom = if (vouchers != null) voucherBaseline + hudSub.descent() else routerBaseline + stockPaint.descent()
        val bottom = maxOf(leftBottom, countersBottom)
    }

    /** The compass shows while the map is turned away from north or still easing back (docs/TOP100.md B5). */
    private val compassShown get() = renderer.camera.angle != 0f

    /** [animTime] of the last camera move (pan, pinch, turn, tilt, a view control); never at game start, so they start hidden. */
    private var viewTouchedAt = 0f

    /**
     * How visible the view controls are: hidden until the map is moved or zoomed, then fully for
     * [VIEW_CONTROLS_SECONDS] after it last moved, then fading out,
     * so they leave the map to the game (only the compass stays while the map is turned). A screen reader keeps them.
     */
    private val viewControlsAlpha: Float
        get() = if (accessibility.active) 1f
        else ((VIEW_CONTROLS_SECONDS + VIEW_CONTROLS_FADE_SECONDS - (animTime - viewTouchedAt)) / VIEW_CONTROLS_FADE_SECONDS).coerceIn(0f, 1f)

    /**
     * The view controls at the right edge, as pills of segment ids: turn left, the compass and turn right (every 45°
     * a corner or a side-on view), and in a style with height ([Renderer.tilts]) flatter and steeper. The tutorial
     * keeps its HUD calm, and so does a map left alone for a while ([viewControlsAlpha]): only the compass, and only
     * while the map is turned.
     */
    private fun viewControls(): List<List<String>> = when {
        tutorial != null || viewControlsAlpha == 0f -> if (compassShown) listOf(listOf("compass")) else emptyList()
        renderer.tilts -> listOf(listOf("rotate:left", "compass", "rotate:right"), listOf("tilt:low", "tilt:high"))
        else -> listOf(listOf("rotate:left", "compass", "rotate:right"))
    }

    /**
     * The view control [pills] in columns for a column [room] px tall, the first column rightmost: one column where all
     * fit above each other, else a column per pill; in a low window (a raised network row under a wide phone's counters)
     * a pill too tall is split, the compass going to a column of its own beside the turn buttons. Null where not even
     * one button fits.
     */
    private fun viewColumns(pills: List<List<String>>, room: Float): List<List<List<String>>>? {
        val bh = buttonHeight
        val gap = CONTROL_GAP_DP * density
        val fit = floor(room / bh).toInt()
        return when {
            pills.sumOf { it.size } * bh + (pills.size - 1) * gap <= room -> listOf(pills)
            pills.all { it.size <= fit } -> pills.map { listOf(it) }
            fit < 1 -> null
            else -> pills.flatMap { pill ->
                when {
                    pill.size <= fit -> listOf(pill)
                    "compass" in pill -> (pill - "compass").chunked(fit) + listOf(listOf("compass"))
                    else -> pill.chunked(fit)
                }
            }.map { listOf(it) }
        }
    }

    /**
     * One upright pill of view controls over [r]: a round button per segment of [ids], top down, with hairlines between
     * them, each a 48 dp touch target of its own. A tilt button at the end of the range is dimmed and does nothing.
     */
    private fun drawViewPill(canvas: Canvas, r: RectF, ids: List<String>) {
        val w = r.width()
        canvas.drawRoundRect(r, w / 2, w / 2, btnFill)
        for ((i, id) in ids.withIndex()) {
            val seg = RectF(r.left, r.top + i * w, r.right, r.top + (i + 1) * w)
            if (i > 0) canvas.drawRect(seg.left + w * 0.26f, seg.top - 0.5f * density, seg.right - w * 0.26f, seg.top + 0.5f * density, pillDivider)
            val enabled = when (id) {
                "tilt:low" -> renderer.camera.canTilt(-1)
                "tilt:high" -> renderer.camera.canTilt(1)
                else -> true
            }
            iconInk.color = if (enabled) 0xFF262B33.toInt() else 0x59262B33
            when (id) {
                "compass" -> drawCompass(canvas, seg)
                "rotate:left" -> drawTurnIcon(canvas, seg, clockwise = false)
                "rotate:right" -> drawTurnIcon(canvas, seg, clockwise = true)
                else -> drawTiltIcon(canvas, seg, steep = id == "tilt:high")
            }
            if (enabled) buttons += Button(id, seg)
            val label = when (id) {
                "compass" -> R.string.a11y_compass
                "rotate:left" -> R.string.a11y_rotate_left
                "rotate:right" -> R.string.a11y_rotate_right
                "tilt:low" -> R.string.a11y_tilt_low
                else -> R.string.a11y_tilt_high
            }
            hudNodes += UiNode("hud:$id", seg, context.getString(label), UiNode.Kind.BUTTON, enabled = enabled)
        }
    }

    /** A circular arrow in [r] that runs [clockwise] or against it, its head at the top where the circle opens. */
    private fun drawTurnIcon(canvas: Canvas, r: RectF, clockwise: Boolean) {
        val cx = r.centerX(); val cy = r.centerY(); val rad = r.height() * 0.19f
        val style = iconInk.style
        val width = iconInk.strokeWidth
        iconInk.style = Paint.Style.STROKE
        iconInk.strokeWidth = 2.5f * density
        // The circle is open at the top between -125° and -55°; it runs from one side of the gap round to the other.
        val start = if (clockwise) -55f else -125f
        val sweep = if (clockwise) 290f else -290f
        canvas.drawArc(cx - rad, cy - rad, cx + rad, cy + rad, start, sweep, false, iconInk)
        iconInk.style = Paint.Style.FILL
        val end = Math.toRadians((start + sweep).toDouble())
        val px = cx + rad * kotlin.math.cos(end).toFloat(); val py = cy + rad * kotlin.math.sin(end).toFloat()
        // Unit tangent in the direction of travel and the normal to it.
        val k = if (clockwise) 1f else -1f
        val tx = -kotlin.math.sin(end).toFloat() * k; val ty = kotlin.math.cos(end).toFloat() * k
        val a = rad * 0.62f
        selectionPath.reset()
        selectionPath.moveTo(px + tx * a, py + ty * a)
        selectionPath.lineTo(px - tx * a * 0.35f - ty * a * 0.85f, py - ty * a * 0.35f + tx * a * 0.85f)
        selectionPath.lineTo(px - tx * a * 0.35f + ty * a * 0.85f, py - ty * a * 0.35f - tx * a * 0.85f)
        selectionPath.close()
        canvas.drawPath(selectionPath, iconInk)
        iconInk.style = style
        iconInk.strokeWidth = width
    }

    /**
     * A block in [r] as the tilt buttons show the view they lead to: seen from low down ([steep] false: a flat roof
     * and tall walls) or from high up (a deep roof and short walls). The roof is tinted, the edges are drawn in ink.
     */
    private fun drawTiltIcon(canvas: Canvas, r: RectF, steep: Boolean) {
        val s = r.height() * 0.2f
        val k = if (steep) 0.8f else 0.34f
        val wall = if (steep) 0.34f * s else 1.05f * s
        val cx = r.centerX()
        val top = r.centerY() - (2 * k * s + wall) / 2f
        val midY = top + k * s
        val style = iconInk.style
        val width = iconInk.strokeWidth
        val ink = iconInk.color
        selectionPath.reset()
        selectionPath.moveTo(cx, top); selectionPath.lineTo(cx + s, midY); selectionPath.lineTo(cx, midY + k * s); selectionPath.lineTo(cx - s, midY); selectionPath.close()
        iconInk.style = Paint.Style.FILL
        iconInk.color = ink and 0x00FFFFFF or ((ink ushr 24) * 0x40 / 0xFF shl 24)
        canvas.drawPath(selectionPath, iconInk)
        iconInk.color = ink
        iconInk.style = Paint.Style.STROKE
        iconInk.strokeWidth = 2f * density
        iconInk.strokeJoin = Paint.Join.ROUND
        // The outline, then the roof's front edges and the front corner.
        selectionPath.reset()
        selectionPath.moveTo(cx, top); selectionPath.lineTo(cx + s, midY); selectionPath.lineTo(cx + s, midY + wall)
        selectionPath.lineTo(cx, midY + k * s + wall); selectionPath.lineTo(cx - s, midY + wall); selectionPath.lineTo(cx - s, midY); selectionPath.close()
        selectionPath.moveTo(cx - s, midY); selectionPath.lineTo(cx, midY + k * s); selectionPath.lineTo(cx + s, midY)
        selectionPath.moveTo(cx, midY + k * s); selectionPath.lineTo(cx, midY + k * s + wall)
        canvas.drawPath(selectionPath, iconInk)
        iconInk.style = style
        iconInk.strokeWidth = width
    }

    /**
     * The compass in [r]: a needle whose red tip points to where the top of the unturned map lies now; a tap turns the
     * map back to north.
     */
    private fun drawCompass(canvas: Canvas, r: RectF) {
        val h = r.height()
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

    /** Lowest edge of the tutorial panel: above the toolbar's tray (as last drawn; before that, one captioned row). */
    private fun tutorialBottom() =
        if (toolbarTop.isNaN()) surfaceHeight - safeInsets.bottom - 16 * density - buttonHeight - captionHeight - 12 * density
        else toolbarTop - 8 * density

    /** Room the HUD's bottom row and its group captions take below the map, the tray's top padding included. */
    private val hudBottomReserve get() = maxOf(68 * density, 16 * density + buttonHeight + captionHeight + (TRAY_PAD_DP + 4) * density)

    /**
     * Room the framing keeps free above the bottom edge of the safe area for the toolbar as it will be laid out now:
     * its frosted tray (two rows of chips in a tall window) and a raised network row, plus a little air, so no node
     * sits under the toolbar at rest; at least [hudBottomReserve]. The raised row only covers the right part of a
     * landscape window, but the inset spans the whole width (a camera has one bottom inset): the map gives up one
     * button row of height there, accepted so no node ever sits under the network tiles.
     */
    private fun toolbarReserve(): Float {
        val pad = 16 * density
        val left = safeInsets.left + pad
        val right = surfaceWidth - safeInsets.right - pad
        val top = safeInsets.top + pad
        val bottom = surfaceHeight - safeInsets.bottom - pad
        if (right <= left || bottom <= top) return hudBottomReserve
        val m = HudTop(right - left)
        val bh = buttonHeight
        val bar = layoutToolbar(left, right, bottom - bh, tall = bottom - top - maxOf(m.leftBottom, m.countersBottom) >= 6 * bh)
        val trayTop = minOf(bar.trayTop, bar.raised?.top ?: Float.MAX_VALUE)
        return maxOf(hudBottomReserve, surfaceHeight - safeInsets.bottom - trayTop + 4 * density)
    }

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
        val m = HudTop(right - left)
        // Soft plates under the date and the counters keep them readable over a busy map at phone size.
        val plateX = 10 * density
        val plateY = 7 * density
        // Side by side in a narrow window, the plates' inner margins shrink so a gap stays between them.
        val between = (right - m.rightW) - (left + m.leftW)
        val innerX = if (m.stacked) plateX else ((between - HUD_PLATE_GAP_DP * density) / 2f).coerceIn(2 * density, plateX)
        canvas.drawRoundRect(left - plateX, top - plateY, left + m.leftW + innerX, top + m.leftBottom + plateY, 14 * density, 14 * density, hudPlate)
        canvas.drawRoundRect(
            right - m.rightW - innerX, top + m.rightTop - plateY, right + plateX, top + m.countersBottom + plateY,
            14 * density, 14 * density, hudPlate,
        )
        canvas.drawText(m.date, left, top + m.dateBaseline, hudText)
        val barY = top + m.barY
        // The week bar: a 6 dp track with a hairline frame, so it reads over a busy map and on the white plate alike.
        val barH = WEEK_BAR_DP * density
        canvas.drawRoundRect(left, barY, left + m.barW, barY + barH, barH / 2f, barH / 2f, barBg)
        if (world.weekProgress > 0f) {
            canvas.drawRoundRect(left, barY, left + maxOf(barH, m.barW * world.weekProgress), barY + barH, barH / 2f, barH / 2f, barFg)
        }
        m.clock?.let { canvas.drawText(it, left, top + m.clockBaseline, hudSub) }
        val leftW = m.leftW
        val leftBottom = top + m.leftBottom
        hudNodes += UiNode("hud:date", RectF(left, top, left + leftW, leftBottom), listOfNotNull(m.date, m.clock).joinToString(". "), UiNode.Kind.TEXT)

        hudText.textAlign = Paint.Align.RIGHT
        canvas.drawText(m.delivered, right, top + m.deliveredBaseline, hudText)
        hudText.textAlign = Paint.Align.LEFT
        // Too little budget for an action: the line turns red and shakes briefly, so it also reads without the colour.
        // Too little for a short cable of the picked type ([budgetLow]): the line stays in the warning tint.
        val flashing = budgetFlashing
        val flash = if (flashing) 1f - (animTime - budgetFlashAt) / BUDGET_FLASH_SECONDS else 0f
        val shake = if (flashing) kotlin.math.sin((animTime - budgetFlashAt) * 40f) * 3f * density * flash else 0f
        val sp = m.stockPaint
        val stockColor = sp.color
        val layout = m.stockLayout
        val n = layout.length
        val budgetEnd = m.stockBudgetEnd
        // Only the budget part takes the colour; on one line the router part follows it, else it gets a line of its own.
        val budgetX = right + shake - (if (m.stockTwoLines) sp.measureText(layout, 0, budgetEnd) else sp.measureText(layout))
        val routerStart = if (m.stockTwoLines) m.stockRouterStart else budgetEnd
        val routerX = if (m.stockTwoLines) right - sp.measureText(layout, routerStart, n) else budgetX + sp.measureText(layout, 0, budgetEnd)
        sp.color = when {
            flashing -> PORTS_FULL_RED
            budgetLow -> BUDGET_LOW_TINT
            else -> stockColor
        }
        if (budgetLow && !flashing) {
            // Not by the tint alone (colourblind players): a pale amber pill behind the budget marks it as short too.
            val px = BUDGET_LOW_PILL_PAD_DP * density
            val pt = top + m.stockBaseline + sp.ascent() - px / 2f
            val pb = top + m.stockBaseline + sp.descent() + px / 2f
            canvas.drawRoundRect(budgetX - px, pt, budgetX + sp.measureText(layout, 0, budgetEnd) + px, pb, (pb - pt) / 2f, (pb - pt) / 2f, budgetLowPill)
        }
        canvas.drawText(layout, 0, budgetEnd, budgetX, top + m.stockBaseline, sp)
        sp.color = stockColor
        canvas.drawText(layout, routerStart, n, routerX, top + m.routerBaseline, sp)
        // The coin and the router sign in front of their numbers; the coin stays gold (money, not an alarm light).
        val coin = COIN_COLOR
        InlineGlyphs.drawLine(canvas, m.stockText, layout, 0, budgetEnd, budgetX, top + m.stockBaseline, sp, coin)
        InlineGlyphs.drawLine(canvas, m.stockText, layout, routerStart, n, routerX, top + m.routerBaseline, sp, coin)
        hudSub.textAlign = Paint.Align.RIGHT
        m.vouchers?.let { canvas.drawText(it, right, top + m.voucherBaseline, hudSub) }
        hudSub.textAlign = Paint.Align.LEFT
        // When both blocks do not fit side by side (a narrow window with large text), the counters sit below the date.
        val rightW = if (m.stacked) right - left else m.rightW
        hudNodes += UiNode(
            "hud:status", RectF(right - m.rightW, top + m.rightTop, right, top + m.countersBottom),
            listOfNotNull(m.delivered, m.stock, if (budgetLow) context.getString(R.string.hud_budget_low) else null, m.vouchers).joinToString(". "),
            UiNode.Kind.TEXT,
        )
        // Centered lines (week news, incidents, the paused pill) stack from the top; one that would run into the date
        // or the counters moves below them.
        val center = (left + right) / 2f
        val headBottom = maxOf(leftBottom, top + m.countersBottom) + 6 * density
        val blocksBottom = maxOf(leftBottom, top + m.countersBottom) + 6 * density
        val freeHalf = minOf(center - (left + leftW) , (right - rightW) - center) - 12 * density
        var cursor = top
        fun place(w: Float, h: Float): Float {
            if (cursor < headBottom && w / 2f > freeHalf) cursor = headBottom
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
        val cursorBeforeIncident = cursor
        var incidentShown = world.rewardOffer == null && placeIncidentLine(right - left, center, ::place)
        // The paused pill is drawn after the hint: where both do not fit (large text, long words), the hint wins.
        val cursorWithoutBanner = cursor
        // An achievement toast takes the top centre while it shows: the pill goes below it, never half under it.
        fun placeBanner(): PausedBanner? {
            if (toast.showing != null) cursor = maxOf(cursor, toast.reservedTop())
            return placePausedBanner(center, right - left, ::place)
        }
        var banner = if (userPaused && world.rewardOffer == null) placeBanner() else null

        val bh = buttonHeight
        val bar = layoutToolbar(left, right, bottom - bh, tall = bottom - top - maxOf(m.leftBottom, m.countersBottom) >= 6 * bh)
        toolbarTop = bar.trayTop
        toolbarRaised = bar.raised
        chipBadges = bar.chip.price == ChipPrice.BADGE
        drawTray(canvas, bar)
        drawToolbar(canvas, bar)
        // Above the whole toolbar, the raised network row too: a long word must never run into it.
        val hintPadX = HINT_PAD_X_DP * density
        var hintPadY = HINT_PAD_Y_DP * density
        val hintBase = (bar.raised?.top ?: bar.trayTop) - hudSub.descent() - 4 * density
        var hintY = hintBase - hintPadY
        // No hint under a menu card: the game-over card and the pause menu cover that spot.
        val hintText = when {
            screen != Screen.PLAYING -> null
            toolDrag != null -> dropHint()
            // Why a placement was refused shows over the standing "tap a free cell" while placing stays armed.
            placing != null && !(hintError && animTime < hintUntil && hintSubject == placing) -> context.getString(R.string.hint_place_router)
            animTime < hintUntil -> hint
            else -> null
        }
        val hintIsError = when {
            toolDrag != null -> dropHintError
            hintText == null || hintText !== hint -> false
            else -> hintError
        }
        var hintBox: RectF? = null
        // A long hint (or large text) wraps into up to three lines (four for an explanation) that grow upwards from above
        // the buttons.
        hintText?.let {
            val lineH = hudSub.textSize * 1.3f
            // As many lines as fit between the top rows (and the centered lines) and the buttons (floor: a line that
            // would reach into the rows above does not count).
            var room = floor((hintY - hudSub.textSize - hintPadY - maxOf(cursor, blocksBottom)) / lineH).toInt() + 1
            // A coaching tip wants all its lines: its second sentence is the advice.
            val coach = it === hint && hintCoach != null
            // A coaching tip is a card of its own: capped in width (it wraps rather than spanning the map), with an
            // accent stripe before the text.
            // Measured once per tip and width, not every frame. In a window too low for the card's lines the tip
            // spans the row again (without the stripe) rather than losing its advice.
            val stripe = COACH_STRIPE_DP * density
            val cappedText = minOf(right - left, COACH_MAX_W_DP * density) - 2 * hintPadX - stripe
            val fullText = right - left - 2 * hintPadX
            val wide = coach && room < coachLines(it, cappedText, fullText, wide = false)
            val hintW = if (coach && !wide) cappedText + 2 * hintPadX + stripe else right - left
            val want = if (coach) coachLines(it, cappedText, fullText, wide) else 1
            if (room < want) {
                // Tight (a low window with large text): a thinner plate first, before anything else steps aside.
                hintPadY = HINT_PAD_TIGHT_DP * density
                hintY = hintBase - hintPadY
                room = floor((hintY - hudSub.textSize - hintPadY - maxOf(cursor, blocksBottom)) / lineH).toInt() + 1
            }
            if (room < want && banner != null) {
                // Not even one line below the paused pill: the pause button shows the stopped clock anyway.
                banner = null
                room = floor((hintY - hudSub.textSize - hintPadY - maxOf(cursorWithoutBanner, blocksBottom)) / lineH).toInt() + 1
            }
            if (room < want && incidentShown && hintCoach == Coaching.INCIDENT) {
                // Still too low for the tip about the excavator: it says what the incident pill says, and the pin on
                // the map keeps the countdown.
                incidentShown = false
                room = floor((hintY - hudSub.textSize - hintPadY - maxOf(cursorBeforeIncident, blocksBottom)) / lineH).toInt() + 1
            }
            // Service pictograms in the text ([InlineGlyphs]) are laid out as gaps and painted as tokens into them.
            val glyphs = InlineGlyphs.services(it)
            // An error leads with its "!" mark; the text wraps in the room beside it.
            val markD = if (hintIsError) hudSub.textSize * HINT_MARK_SCALE else 0f
            val markW = if (hintIsError) markD + HINT_MARK_GAP_DP * density else if (coach && !wide) COACH_STRIPE_DP * density else 0f
            val textPaint = if (hintIsError) hintErrorText else hudSub
            val lines = wrapText(InlineGlyphs.layout(it), hintW - 2 * hintPadX - markW, textPaint, room.coerceIn(1, hintLines(it)))
            val w = lines.maxOf { l -> textPaint.measureText(l) }
            // A calm frosted plate (the tray's look) keeps the text readable over buildings; it hugs the wrapped lines.
            val plate = RectF(left, hintY - (lines.size - 1) * lineH - hudSub.textSize - hintPadY, left + w + 2 * hintPadX + markW, hintY + hudSub.descent() + hintPadY)
            val radius = HINT_RADIUS_DP * density
            hintBox = plate
            canvas.drawRoundRect(plate, radius, radius, if (hintIsError) hintErrorPlate else if (coach) coachPlate else hintPlate)
            val textX = left + hintPadX + markW
            if (coach && !wide) {
                val sw = COACH_STRIPE_DP * density / 2f
                canvas.drawRoundRect(left + hintPadX, plate.top + hintPadY, left + hintPadX + sw, plate.bottom - hintPadY, sw / 2f, sw / 2f, coachStripe)
            }
            if (hintIsError) {
                hintErrorRim.strokeWidth = density
                canvas.drawRoundRect(plate, radius, radius, hintErrorRim)
                // Beside the first line: in a tall plate the mark leads the text instead of floating in the middle.
                val r = minOf(markD / 2f, plate.height() / 2f - density)
                val cx = left + hintPadX + markD / 2f
                val cy = if (lines.size == 1) plate.centerY() else hintY - (lines.size - 1) * lineH - hudSub.textSize * 0.35f
                canvas.drawCircle(cx, cy, r, hintMarkFill)
                hintMarkText.textSize = r * 1.5f
                canvas.drawText("!", cx, cy - (hintMarkText.ascent() + hintMarkText.descent()) / 2f, hintMarkText)
            }
            var glyph = 0
            lines.forEachIndexed { i, line ->
                val ly = hintY - (lines.size - 1 - i) * lineH
                canvas.drawText(line, textX, ly, textPaint)
                if (glyphs.isNotEmpty()) glyph = InlineGlyphs.drawTokens(canvas, line, textX, ly, textPaint, glyphs, glyph)
            }
            // Read out as an error too: the red plate and the mark are visual only.
            if (hintSpokenFor !== it || hintSpokenError != hintIsError) {
                // Built once per hint, not every frame it shows.
                hintSpoken = InlineGlyphs.plain(it).let { t -> if (hintIsError) context.getString(R.string.hint_error_prefix, t) else t }
                hintSpokenFor = it
                hintSpokenError = hintIsError
            }
            hudNodes += UiNode("hud:hint", plate, hintSpoken, UiNode.Kind.TEXT, shortened = lines.last().endsWith(TextWrap.ELLIPSIS))
        }
        if (incidentShown) drawIncidentLine(canvas)
        banner?.let { drawPausedBanner(canvas, it) }
        // The view controls (turn, compass, tilt: docs/TOP100.md B5) stand upright at the right edge, in line with the
        // menu button below them and centred between the counters (and any centred line) and the toolbar, so they take
        // no room from the top rows. Drawn last: they keep clear of the hint and step aside where no room is left.
        val pills = viewControls()
        if (pills.isNotEmpty()) {
            val gap = CONTROL_GAP_DP * density
            val air = CONTROL_AIR_DP * density
            val colTop = maxOf(top + m.countersBottom + plateY, cursor) + air
            var colBottom = minOf(bar.trayTop, bar.raised?.top ?: Float.MAX_VALUE) - air
            var columns = viewColumns(pills, colBottom - colTop)
            val hintPlate = hintBox
            if (columns != null && hintPlate != null && hintPlate.right + gap > right - columns.size * (bh + gap) + gap) {
                colBottom = minOf(colBottom, hintPlate.top - air)
                columns = viewColumns(pills, colBottom - colTop)
            }
            if (columns != null) {
                // Fading out after the map was left alone (the compass alone, once they are gone, stays solid).
                val alpha = if (pills.singleOrNull()?.size == 1) 1f else viewControlsAlpha
                val layer = if (alpha < 1f) canvas.saveLayerAlpha(null, (alpha * 255).toInt()) else null
                var colRight = right
                for (column in columns) {
                    val h = column.sumOf { it.size } * bh + (column.size - 1) * gap
                    var y = (colTop + colBottom - h) / 2f
                    for (pill in column) {
                        drawViewPill(canvas, RectF(colRight - bh, y, colRight, y + pill.size * bh), pill)
                        y += pill.size * bh + gap
                    }
                    colRight -= bh + gap
                }
                layer?.let { canvas.restoreToCount(it) }
            }
        }
        if (screen == Screen.PLAYING && tutorial == null && !world.gameOver && failFocusUntil == null) drawOffscreenChips(canvas)
    }

    /** How the cable chips name and price themselves, from full names with a coin down to no name at all. */
    private class ChipStyle(val label: (CableType) -> String?, val paint: Paint, val price: ChipPrice)

    /** The price on a cable chip: a coin at its end, a coin badge over its corner (own row only), or none. */
    private enum class ChipPrice { COIN, BADGE, NONE }

    /** One network tile: the [kind] it places, its button [id], its [stock] (null: unlimited) and short [name]. */
    private class NetTool(val kind: NodeKind, val id: String, val stock: Int?, val name: String)

    /** A group caption ("KABEL", "NETZWERK") with its left end and baseline. */
    private class Caption(val key: String, val text: String, val x: Float, val baseline: Float)

    /**
     * The bottom toolbar as laid out for one frame by [layoutToolbar]: the cable [chips] in [chip] style, the network
     * [tiles] with their names in [tilePaint] (null: icon, dots and stock only), pause and menu ([controls]), the group
     * [captions], the [divider] between the groups when they share a row, and the top of the tray behind it all, with
     * a [raised] part (rounded at its top left) behind a network row that sits above the controls.
     */
    private class Toolbar(
        val chips: List<Pair<CableType, RectF>>,
        val chip: ChipStyle,
        val tiles: List<Pair<NetTool, RectF>>,
        val tilePaint: Paint?,
        val controls: List<Pair<String, RectF>>,
        val captions: List<Caption>,
        val divider: RectF?,
        val trayTop: Float,
        val raised: RectF? = null,
    )

    /**
     * Cable chip styles, best first. Names shorten ("Faser" for "Glasfaser") before they disappear, and a slightly
     * smaller type comes first so a portrait window says "Glasfaser" as landscape does (judge panel: the terms must stay
     * the same everywhere); colour alone is not enough to tell the technologies apart (docs/TOP100.md A7, B4). Prices
     * move onto a corner badge before they drop out, and drop out before the names: the drag bubble shows them anyway.
     * Creative mode (docs/TOP100.md C4): nothing costs anything, so no coins.
     */
    private fun chipStyles(): List<ChipStyle> {
        val full = { t: CableType -> texts.cable(t) }
        val short = { t: CableType -> texts.cableShort(t) }
        val selectedShort = { t: CableType -> if (t == cableType) texts.cableShort(t) else null }
        val none = { _: CableType -> null as String? }
        val priced = !world.unlimited
        val coin = if (priced) ChipPrice.COIN else ChipPrice.NONE
        return listOfNotNull(
            ChipStyle(full, btnText, coin),
            ChipStyle(full, chipLabelCompact, coin),
            if (priced) ChipStyle(full, chipLabelCompact, ChipPrice.BADGE) else null,
            ChipStyle(short, btnText, coin),
            ChipStyle(short, chipLabelCompact, coin),
            if (priced) ChipStyle(short, chipLabelCompact, ChipPrice.BADGE) else null,
            if (priced) ChipStyle(short, btnText, ChipPrice.NONE) else null,
            ChipStyle(short, chipLabelSmall, ChipPrice.NONE),
            ChipStyle(selectedShort, btnText, coin),
            ChipStyle(none, btnText, coin),
        )
    }

    /** Radius of the price coins and stock badges. */
    private val coinR get() = maxOf(9 * density, coinText.textSize * 0.8f)

    /** Radius of a cable chip's price badge (without its rim), for tests. */
    internal val chipBadgeRadius get() = coinR * 0.9f

    /** True if the toolbar last drawn shows the cable prices on badges over the chips' corners, for tests. */
    internal var chipBadges = false
        private set

    private fun chipWidth(t: CableType, style: ChipStyle): Float {
        val label = style.label(t)
        return 26 * density + (if (label != null) style.paint.measureText(label) + 8 * density else 0f) +
            when (style.price) {
                ChipPrice.COIN -> 2 * coinR + 10 * density
                ChipPrice.BADGE -> 12 * density
                ChipPrice.NONE -> 4 * density
            }
    }

    private fun chipsWidth(style: ChipStyle): Float {
        val cables = world.unlockedCables
        return cables.sumOf { chipWidth(it, style).toDouble() }.toFloat() + TOOL_GAP_DP * density * (cables.size - 1)
    }

    /** The router always, WLAN and mast while some are in stock (they come as week rewards) or in creative mode. */
    private fun networkTools(): List<NetTool> {
        val unlimited = world.unlimited
        val router = NetTool(NodeKind.ROUTER, "router", if (unlimited) null else world.routersAvailable, context.getString(R.string.toolbar_router))
        return listOf(router) + RadioType.entries.filter { unlimited || world.radiosAvailable(it) > 0 }.map {
            val name = context.getString(if (it == RadioType.WLAN) R.string.toolbar_access_point else R.string.toolbar_cell_tower)
            NetTool(it.kind, "radio:${it.name}", if (unlimited) null else world.radiosAvailable(it), name)
        }
    }

    /** Width of a row of [n] port dots in a tile. */
    private fun tileDotsWidth(n: Int) = (n - 1) * TILE_DOT_STEP_DP * density + 2 * TILE_DOT_DP * density

    /**
     * A network tile: the device in a round well, its name with a row of one hollow dot per port under it, and the
     * stock on a dark badge at the end (as the price coin on a cable chip). Without a name ([paint] null) the dots
     * move under the well and the stock onto a small badge over the well's corner, so three tiles fit beside pause and
     * menu in a portrait phone.
     */
    private fun tileWidth(tool: NetTool, paint: Paint?): Float {
        val dots = tileDotsWidth(tool.kind.maxPorts)
        val well = 2 * TILE_WELL_DP * density
        val badge = 2 * coinR + 10 * density
        return if (paint == null) maxOf(buttonHeight, 2 * 14 * density + maxOf(well, dots))
        else 12 * density + well + 8 * density + maxOf(paint.measureText(tool.name), dots) + 8 * density + badge
    }

    private fun tilesWidth(tools: List<NetTool>, paint: Paint?) =
        tools.sumOf { tileWidth(it, paint).toDouble() }.toFloat() + TOOL_GAP_DP * density * (tools.size - 1)

    /** Height a group caption takes above its row. */
    private val captionHeight get() = captionText.textSize + captionText.descent() + CAPTION_GAP_DP * density

    /** Baseline of a caption over a row whose top is [rowTop]. */
    private fun captionBaseline(rowTop: Float) = rowTop - CAPTION_GAP_DP * density - captionText.descent()

    private fun caption(key: String, res: Int, x: Float, rowTop: Float) =
        Caption(key, context.getString(res).uppercase(resources.configuration.locales[0]), x, captionBaseline(rowTop))

    /**
     * Lays out the bottom toolbar above [y] (the top of the lowest row) between [left] and [right]: two captioned
     * groups, "Kabel" (the cable technologies) and "Netzwerk" (router, WLAN, mast: the devices that join several
     * others), then pause and menu at the right edge. The best cable [chipStyles] wins that fits one of, in this order:
     *  - one row: cables | divider | network, and the controls at the right (landscape, tablets);
     *  - network on a row above the controls, cables beside the controls (a landscape window with radios in stock or
     *    large text): the tray stays one row high on the left, where the map is, instead of the names shrinking away;
     *  - cables on their own row above, network and controls below, only where there is height to spare ([tall]:
     *    portrait windows, where the cables do not fit beside the controls).
     * The network tiles keep their names in the chips' type if they fit, a step smaller otherwise, and only show icon,
     * dots and stock as a last resort. Every tile and chip is at least [buttonHeight] tall and wide (48 dp).
     */
    private fun layoutToolbar(left: Float, right: Float, y: Float, tall: Boolean): Toolbar {
        val bh = buttonHeight
        val gap = TOOL_GAP_DP * density
        val menu = RectF(right - bh, y, right, y + bh)
        val pause = RectF(menu.left - gap - bh, y, menu.left - gap, y + bh)
        val help = RectF(pause.left - gap - bh, y, pause.left - gap, y + bh)
        val controls = listOf("menu" to menu, "pause" to pause, "help" to help)
        val controlsEdge = help.left - gap
        val tools = networkTools()
        val groupGap = GROUP_GAP_DP * density
        val cables = world.unlockedCables
        fun chipsAt(style: ChipStyle, top: Float): List<Pair<CableType, RectF>> {
            var x = left
            return cables.map { t -> val w = chipWidth(t, style); (t to RectF(x, top, x + w, top + bh)).also { x += w + gap } }
        }
        fun tilesAt(paint: Paint?, from: Float, top: Float): List<Pair<NetTool, RectF>> {
            var x = from
            return tools.map { tool -> val w = tileWidth(tool, paint); (tool to RectF(x, top, x + w, top + bh)).also { x += w + gap } }
        }
        fun trayTop(captions: List<Caption>, rowTops: List<Float>) =
            minOf(captions.minOfOrNull { it.baseline - captionText.textSize } ?: Float.MAX_VALUE, rowTops.min()) - TRAY_PAD_DP * density
        /**
         * With the cables on a row of their own above: the network tiles' paint (null: icon, dots and stock only) and
         * the controls. Where the tiles do not fit beside all three controls (a narrow portrait phone with radios in
         * stock), the "?" moves up to the right end of the cable row if that has room, else onto a row of its own
         * above that end (the tray grows to hold it), so nothing overlaps.
         */
        fun stackedControls(
            paints: List<Paint>, chips: List<Pair<CableType, RectF>>, cableTop: Float, style: ChipStyle,
        ): Pair<Paint?, List<Pair<String, RectF>>> {
            val fitsAll = paints.firstOrNull { left + tilesWidth(tools, it) <= controlsEdge }
            if (fitsAll != null || left + tilesWidth(tools, null) <= controlsEdge) return fitsAll to controls
            // A price badge sticks out over its chip's top right corner ([drawToolbar]): the "?" keeps clear of it.
            val badge = style.price == ChipPrice.BADGE
            val br = coinR * 0.9f
            val pastRight = if (badge) br * 0.3f + 2 * density else 0f
            val overTop = if (badge) br * 0.65f + 2 * density else 0f
            val rowFree = (chips.lastOrNull()?.second?.right ?: left) + pastRight + gap <= right - bh
            val upTop = if (rowFree) cableTop else cableTop - maxOf(gap, overTop + BADGE_CLEAR_DP * density) - bh
            val up = RectF(right - bh, upTop, right, upTop + bh)
            val twoEdge = pause.left - gap
            val paint = paints.firstOrNull { left + tilesWidth(tools, it) <= twoEdge }
            return paint to listOf("menu" to menu, "pause" to pause, "help" to up)
        }
        /** The top of the "?" where [stackedControls] lifted it above the cable row, for the tray. */
        fun helpTop(ctrls: List<Pair<String, RectF>>) = ctrls.first { it.first == "help" }.second.top
        val styles = chipStyles()
        for (style in styles) {
            val cw = chipsWidth(style)
            if (style.price != ChipPrice.BADGE) {
                val tilePaint = listOf(style.paint, chipLabelCompact).distinct().firstOrNull { left + cw + 2 * groupGap + tilesWidth(tools, it) <= controlsEdge }
                if (tilePaint != null) {
                    val netLeft = left + cw + 2 * groupGap
                    val captions = listOf(
                        caption("cables", R.string.toolbar_cables, left + CAPTION_INSET_DP * density, y),
                        caption("network", R.string.toolbar_network, netLeft + CAPTION_INSET_DP * density, y),
                    )
                    val mid = left + cw + groupGap
                    val divider = RectF(mid - 0.5f * density, y + bh * 0.2f, mid + 0.5f * density, y + bh * 0.8f)
                    return Toolbar(chipsAt(style, y), style, tilesAt(tilePaint, netLeft, y), tilePaint, controls, captions, divider, trayTop(captions, listOf(y)))
                }
            }
            if (style.price != ChipPrice.BADGE && left + cw <= controlsEdge) {
                val netTop = y - bh - gap
                val cablesCaption = caption("cables", R.string.toolbar_cables, left + CAPTION_INSET_DP * density, y)
                val besideCaption = cablesCaption.x + captionText.measureText(cablesCaption.text) + 2 * gap
                val tilePaint = listOf(style.paint, chipLabelCompact).distinct().firstOrNull { right - tilesWidth(tools, it) >= besideCaption }
                val netLeft = right - tilesWidth(tools, tilePaint)
                val netCaption = caption("network", R.string.toolbar_network, netLeft + CAPTION_INSET_DP * density, netTop).let {
                    // A caption wider than a lone narrow tile ends at the right edge instead of running past it.
                    val w = captionText.measureText(it.text)
                    if (it.x + w > right) Caption(it.key, it.text, right - w, it.baseline) else it
                }
                // Where the network row reaches over the cables' caption (a very narrow window), that caption goes.
                val captions = listOfNotNull(cablesCaption.takeIf { netLeft >= besideCaption - gap }, netCaption)
                val raisedTop = minOf(netCaption.baseline - captionText.textSize, netTop) - TRAY_PAD_DP * density
                val raised = RectF(minOf(netLeft, netCaption.x) - GROUP_GAP_DP * density, raisedTop, surfaceWidth.toFloat(), y)
                val low = trayTop(captions - netCaption, listOf(y))
                return Toolbar(chipsAt(style, y), style, tilesAt(tilePaint, netLeft, netTop), tilePaint, controls, captions, null, low, raised)
            }
            if (tall && left + cw <= right) {
                val cableTop = y - bh - gap - captionHeight
                val chips = chipsAt(style, cableTop)
                val (tilePaint, ctrls) = stackedControls(listOf(style.paint, chipLabelCompact).distinct(), chips, cableTop, style)
                val captions = listOf(
                    caption("cables", R.string.toolbar_cables, left + CAPTION_INSET_DP * density, cableTop),
                    caption("network", R.string.toolbar_network, left + CAPTION_INSET_DP * density, y),
                )
                return Toolbar(chips, style, tilesAt(tilePaint, left, y), tilePaint, ctrls, captions, null, trayTop(captions, listOf(cableTop, helpTop(ctrls))))
            }
        }
        val last = styles.last()
        val cableTop = y - bh - gap - captionHeight
        val chips = chipsAt(last, cableTop)
        val ctrls = stackedControls(emptyList(), chips, cableTop, last).second
        val captions = listOf(caption("network", R.string.toolbar_network, left + CAPTION_INSET_DP * density, y))
        return Toolbar(chips, last, tilesAt(null, left, y), null, ctrls, captions, null, trayTop(captions, listOf(cableTop, helpTop(ctrls))))
    }

    /**
     * The frosted tray under the toolbar with a hairline along its top edge, and its raised part (rounded at the top
     * left) on top of it, meeting it edge to edge so the translucent fill never doubles.
     */
    private fun drawTray(canvas: Canvas, bar: Toolbar) {
        val w = surfaceWidth.toFloat()
        val line = 1 * density
        canvas.drawRect(0f, bar.trayTop, w, surfaceHeight.toFloat(), trayFill)
        trayBox.set(0f, bar.trayTop, w, surfaceHeight.toFloat())
        val raised = bar.raised
        canvas.drawRect(0f, bar.trayTop - line, raised?.left ?: w, bar.trayTop, trayLineFill)
        if (raised == null) return
        val r = TRAY_CORNER_DP * density
        // Its outline: up the left side, round the top left corner, along the top to the screen edge.
        trayPath.reset()
        trayPath.moveTo(raised.left, bar.trayTop)
        trayPath.lineTo(raised.left, raised.top + r)
        trayPath.quadTo(raised.left, raised.top, raised.left + r, raised.top)
        trayPath.lineTo(w, raised.top)
        trayLine.strokeWidth = 2 * line
        canvas.drawPath(trayPath, trayLine)
        trayPath.lineTo(w, bar.trayTop)
        trayPath.close()
        canvas.drawPath(trayPath, trayFill)
    }

    /** Draws [bar] and registers its buttons for touches and TalkBack. */
    private fun drawToolbar(canvas: Canvas, bar: Toolbar) {
        for ((id, r) in bar.controls) {
            drawIconButton(canvas, r, id, active = id == "pause" && userPaused)
            buttons += Button(id, r)
            hudNodes += UiNode(
                "hud:$id", RectF(r),
                context.getString(
                    when (id) {
                        "menu" -> R.string.a11y_menu
                        "help" -> R.string.menu_legend
                        else -> R.string.a11y_pause
                    },
                ),
                if (id == "pause") UiNode.Kind.TOGGLE else UiNode.Kind.BUTTON, checked = id == "pause" && userPaused,
            )
        }
        for (c in bar.captions) {
            canvas.drawText(c.text, c.x, c.baseline, captionText)
            val w = captionText.measureText(c.text)
            hudNodes += UiNode("hud:group:${c.key}", RectF(c.x, c.baseline - captionText.textSize, c.x + w, c.baseline + captionText.descent()), c.text, UiNode.Kind.HEADING)
        }
        bar.divider?.let { canvas.drawRect(it, dividerFill) }
        val style = bar.chip
        val chipText = style.paint
        val priced = !world.unlimited
        for ((t, r) in bar.chips) {
            val bh = r.height()
            val label = style.label(t)
            val active = t == cableType
            canvas.drawRoundRect(r, bh / 2, bh / 2, if (active) btnActive else btnFill)
            drawCableGlyph(canvas, t, r.left + 12 * density, r.left + 20 * density, r.centerY())
            if (label != null) {
                chipText.color = if (active) 0xFFFFFFFF.toInt() else 0xFF262B33.toInt()
                chipText.textAlign = Paint.Align.LEFT
                canvas.drawText(label, r.left + 27 * density, r.centerY() + chipText.textSize * 0.35f, chipText)
                chipText.textAlign = Paint.Align.CENTER
            }
            when (style.price) {
                // The price on a coin badge over the chip's top right corner, with a white rim to lift it off the chip.
                ChipPrice.BADGE -> {
                    val br = coinR * 0.9f
                    val bx = r.right - br * 0.7f
                    val by = r.top + br * 0.35f
                    canvas.drawCircle(bx, by, br + 2 * density, chipBadgeRim)
                    canvas.drawCircle(bx, by, br, coinFill)
                    canvas.drawText(t.costPerCell.toString(), bx, by + coinText.textSize * 0.36f, coinText)
                }
                ChipPrice.COIN -> {
                    val coinX = r.right - 10 * density - coinR
                    canvas.drawCircle(coinX, r.centerY(), coinR, coinFill)
                    canvas.drawText(t.costPerCell.toString(), coinX, r.centerY() + coinText.textSize * 0.36f, coinText)
                }
                ChipPrice.NONE -> Unit
            }
            buttons += Button("cable:${t.name}", r)
            val a11y = if (priced) context.getString(R.string.a11y_cable, texts.cable(t), t.costPerCell) else context.getString(R.string.a11y_cable_free, texts.cable(t))
            hudNodes += UiNode("hud:cable:${t.name}", RectF(r), a11y, UiNode.Kind.BUTTON, selected = active)
        }
        for ((tool, r) in bar.tiles) {
            drawTile(canvas, tool, r, bar.tilePaint)
            buttons += Button(tool.id, r)
            val stock = tool.stock
            val base = when {
                stock == null -> "${tool.name}, ${context.getString(R.string.a11y_unlimited)}"
                tool.kind == NodeKind.ROUTER -> context.getString(R.string.a11y_router, stock)
                tool.kind == NodeKind.ACCESS_POINT -> context.getString(R.string.a11y_access_point, stock)
                else -> context.getString(R.string.a11y_cell_tower, stock)
            }
            hudNodes += UiNode(
                "hud:${tool.id}", RectF(r), context.getString(R.string.hint_two_parts, base, texts.ports(tool.kind.maxPorts)),
                UiNode.Kind.BUTTON, selected = placing == tool.kind,
            )
        }
    }

    /** One network tile (see [tileWidth]); the router's pulses after a cable failed on full ports. */
    private fun drawTile(canvas: Canvas, tool: NetTool, r: RectF, paint: Paint?) {
        val h = r.height()
        val active = placing == tool.kind || toolDrag == tool.kind
        val pulse = animTime - routerPulseAt
        if (tool.kind == NodeKind.ROUTER && pulse in 0f..ROUTER_PULSE_SECONDS) {
            // Two rings that grow out of the tile and fade: "this is what you need".
            for (k in 0 until 2) {
                val f = (pulse / ROUTER_PULSE_SECONDS * 2f - k * 0.5f).let { it - floor(it) }
                val grow = (3f + 9f * f) * density
                tilePulse.alpha = ((1f - f) * 230).toInt()
                tilePulse.strokeWidth = 3 * density
                canvas.drawRoundRect(r.left - grow, r.top - grow, r.right + grow, r.bottom + grow, h / 2 + grow, h / 2 + grow, tilePulse)
            }
        }
        canvas.drawRoundRect(r, h / 2, h / 2, if (active) btnActive else btnFill)
        val well = TILE_WELL_DP * density
        val named = paint != null
        val wx = if (named) r.left + 12 * density + well else r.centerX()
        val wy = if (named) r.centerY() else r.centerY() - 5 * density
        tileWell.color = if (active) 0xFFFFFFFF.toInt() else TILE_WELL
        canvas.drawCircle(wx, wy, if (named) well else well * 0.85f, tileWell)
        drawToolIcon(canvas, tool.kind, wx, wy, (if (named) 9.5f else 8f) * density)
        // One hollow dot per port: a fresh device has them all free.
        val dotR = TILE_DOT_DP * density
        tileDot.color = if (active) 0xFFFFFFFF.toInt() else 0xFF5B6674.toInt()
        tileDot.strokeWidth = 1.3f * density
        val dotsY: Float
        var dx: Float
        if (paint != null) {
            val cap = paint.textSize * 0.72f
            val block = cap + 6 * density + 2 * dotR
            val baseline = r.centerY() - block / 2f + cap
            paint.color = if (active) 0xFFFFFFFF.toInt() else 0xFF262B33.toInt()
            paint.textAlign = Paint.Align.LEFT
            val tx = wx + well + 8 * density
            canvas.drawText(tool.name, tx, baseline, paint)
            paint.textAlign = Paint.Align.CENTER
            dotsY = baseline + 6 * density + dotR
            dx = tx + dotR
        } else {
            dotsY = r.bottom - 9 * density
            dx = wx - tileDotsWidth(tool.kind.maxPorts) / 2f + dotR
        }
        repeat(tool.kind.maxPorts) {
            canvas.drawCircle(dx, dotsY, dotR - tileDot.strokeWidth / 2f, tileDot)
            dx += TILE_DOT_STEP_DP * density
        }
        // The stock on a dark badge where a cable chip has its price coin (on the well's corner without a name); grey
        // once none is left.
        val badgeR = if (named) coinR else coinR * 0.85f
        val bx = if (named) r.right - 10 * density - coinR else wx + well * 0.85f
        val by = if (named) r.centerY() else wy - well * 0.55f
        val stock = tool.stock
        stockFill.color = when {
            stock == 0 -> 0xFFB4BAC2.toInt()
            active -> 0xFFFFFFFF.toInt()
            else -> 0xFF262B33.toInt()
        }
        if (!named) canvas.drawCircle(bx, by, badgeR + 2 * density, if (active) btnActive else chipBadgeRim)
        canvas.drawCircle(bx, by, badgeR, stockFill)
        stockText.color = if (active && stock != 0) 0xFF262B33.toInt() else 0xFFFFFFFF.toInt()
        canvas.drawText(stock?.toString() ?: "∞", bx, by + stockText.textSize * 0.36f, stockText)
    }

    /** The device [kind] places, as on the legend: a router block, a WLAN puck with its arcs, a lattice mast. */
    private fun drawToolIcon(canvas: Canvas, kind: NodeKind, x: Float, y: Float, s: Float) {
        when (kind) {
            NodeKind.ROUTER -> toolIcons.router(canvas, x - s * 0.08f, y + s * 0.22f, s, animTime)
            NodeKind.ACCESS_POINT -> toolIcons.accessPoint(canvas, x, y + s * 0.02f, s, ACCESS_POINT_LED, animTime)
            NodeKind.CELL_TOWER -> toolIcons.cellTower(canvas, x, y + s * 0.05f, s * 0.95f, animTime)
            NodeKind.CLIENT, NodeKind.SERVER -> Unit
        }
    }

    /**
     * While a network tile is dragged over the map: the cell under the finger (its four corners projected, so it
     * follows any rotation or tilt of the camera) in white where the device can go and red where it cannot, and the
     * device itself floating on it; over the toolbar it just follows the finger.
     */
    private fun drawToolGhost(canvas: Canvas) {
        val kind = toolDrag ?: return
        val at = toolDragAt ?: return
        val s = maxOf(renderer.unitPx * 0.45f, 14 * density)
        var gx = at.x
        var gy = at.y - s
        var alpha = 230
        if (!overToolbar(at.x, at.y)) {
            val cell = cellAt(at.x, at.y)
            val ok = world.placeError(kind, cell.x, cell.y) == null
            selectionPath.reset()
            for (k in 0 until 4) {
                val q = renderer.toScreen(Vec2(cell.x + (if (k == 1 || k == 2) 1f else 0f), cell.y + (if (k >= 2) 1f else 0f)))
                if (k == 0) selectionPath.moveTo(q.x, q.y) else selectionPath.lineTo(q.x, q.y)
            }
            selectionPath.close()
            ghostFill.color = if (ok) 0x66FFFFFF else 0x55D7263D
            canvas.drawPath(selectionPath, ghostFill)
            ghostLine.color = if (ok) 0xF2FFFFFF.toInt() else PORTS_FULL_RED
            ghostLine.strokeWidth = 2.5f * density
            canvas.drawPath(selectionPath, ghostLine)
            val c = renderer.toScreen(cell.center)
            gx = c.x
            gy = c.y - s * 0.35f
            if (!ok) alpha = 140
        }
        canvas.saveLayerAlpha(gx - 3 * s, gy - 3 * s, gx + 3 * s, gy + 3 * s, alpha)
        drawToolIcon(canvas, kind, gx, gy, s)
        canvas.restore()
    }

    /** True if ([x], [y]) lies on the toolbar's tray (as last drawn), where a dragged tile is not dropped. */
    private fun overToolbar(x: Float, y: Float) = y >= toolbarTop || toolbarRaised?.contains(x, y) == true

    /** The cell under the screen point ([sx], [sy]), through the renderer's own picking (any rotation or tilt). */
    private fun cellAt(sx: Float, sy: Float): Cell = renderer.toWorld(sx, sy).let { Cell(floor(it.x).toInt(), floor(it.y).toInt()) }

    /** The hint line while a tile is dragged: where to let go, or why the cell under the finger does not work. */
    private fun dropHint(): String {
        dropHintError = false
        val kind = toolDrag ?: return context.getString(R.string.hint_drop_tool)
        val at = toolDragAt ?: return context.getString(R.string.hint_drop_tool)
        if (overToolbar(at.x, at.y)) return context.getString(R.string.hint_drop_tool)
        val cell = cellAt(at.x, at.y)
        return world.placeError(kind, cell.x, cell.y)?.let { dropHintError = true; placeErrorText(kind, it) } ?: context.getString(R.string.hint_drop_tool)
    }

    private var stockKey = Long.MIN_VALUE
    private var stockCache = ""
    private var stockLayoutCache = ""
    private var stockPlainCache = ""
    private var stockBudgetEndCache = 0
    private var stockRouterStartCache = 0

    /**
     * The HUD's budget and router line, "Budget ● 42  ·  Router ● 3" with the coin and the router sign as [InlineGlyphs]
     * markers (a bare ∞ in a game without limits). Built again only when a number changes, so the HUD does not allocate
     * it every frame; [stockLayoutCache] and [stockPlainCache] follow it.
     */
    private fun stockLine(): String {
        val key = if (world.unlimited) Long.MAX_VALUE else (world.budget.toLong() shl 32) or (world.routersAvailable.toLong() and 0xFFFFFFFFL)
        if (key != stockKey) {
            stockKey = key
            val budget = if (world.unlimited) UNLIMITED else world.budget.toString()
            val routers = if (world.unlimited) UNLIMITED else world.routersAvailable.toString()
            // A game without limits has no prices: no coin and no router sign before the ∞ (docs/TOP100.md).
            stockCache =
                if (world.unlimited) context.getString(R.string.hud_resources, budget, routers)
                else context.getString(R.string.hud_resources, InlineGlyphs.coin() + budget, InlineGlyphs.router() + routers)
            stockLayoutCache = InlineGlyphs.layout(stockCache)
            stockPlainCache = InlineGlyphs.plain(stockCache)
            // Every language writes "Budget …  ·  Router …": the budget part ends before the dot, the router part
            // starts after it and its spaces (without a dot, the whole line is the budget part).
            val dot = stockCache.indexOf('·')
            if (dot < 0) {
                stockBudgetEndCache = stockCache.length
                stockRouterStartCache = stockCache.length
            } else {
                var end = dot
                while (end > 0 && stockCache[end - 1] == ' ') end--
                var start = dot + 1
                while (start < stockCache.length && stockCache[start] == ' ') start++
                stockBudgetEndCache = end
                stockRouterStartCache = start
            }
        }
        return stockCache
    }

    /**
     * True while the budget would not pay for a short cable ([SHORT_CABLE_CELLS] cells) of the picked type: the HUD
     * shows the budget line in a warning tint, before a drag is refused for lack of budget. The yardstick is the bare
     * price per cell, without the surcharge for crossing water or hills ([World.cableCost]), so a cable over rough
     * ground can still be refused while the line is calm; it holds whichever tool is armed.
     */
    internal val budgetLow: Boolean get() = !world.unlimited && world.budget < SHORT_CABLE_CELLS * cableType.costPerCell

    /**
     * A short piece of cable [t] from [x0] to [x1] at [y], as on the map: its colour, its thickness (which grows with
     * the capacity) and its core line, on a white casing so it stays visible on the dark selected chip.
     */
    private fun drawCableGlyph(canvas: Canvas, t: CableType, x0: Float, x1: Float, y: Float) {
        val st = CableStyles.of(t)
        val w = (2.5f + st.width * 26f) * density
        glyphPaint.color = 0xFFFFFFFF.toInt(); glyphPaint.strokeWidth = w + 2.5f * density
        canvas.drawLine(x0, y, x1, y, glyphPaint)
        glyphPaint.color = st.color; glyphPaint.strokeWidth = w
        canvas.drawLine(x0, y, x1, y, glyphPaint)
        st.core?.let { glyphPaint.color = it; glyphPaint.strokeWidth = maxOf(1.2f * density, st.coreWidth * 26f * density); canvas.drawLine(x0, y, x1, y, glyphPaint) }
    }

    /** [s], shortened with an ellipsis if it is wider than [maxWidth] in [paint]. */
    private fun fitText(s: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 1 && paint.measureText(s, 0, end) + paint.measureText("…") > maxWidth) end--
        return s.substring(0, end).trimEnd() + "…"
    }

    /** [s] broken into at most [maxLines] lines of [maxWidth] in [paint] (at spaces, and between CJK characters); the last one is shortened if needed. */
    /** Lines the hint line may wrap [text] into: more for a coaching tip and an explanation than for other hints. */
    private fun hintLines(text: String) = when {
        text !== hint -> MAX_HINT_LINES
        hintCoach != null -> COACH_HINT_LINES
        hintLong -> MAX_HINT_LINES + 1
        else -> MAX_HINT_LINES
    }

    private fun wrapText(s: String, maxWidth: Float, paint: Paint, maxLines: Int): List<String> =
        TextWrap.wrap(s, maxWidth, maxLines) { paint.measureText(it) }

    /** A round HUD button with a drawn icon: three lines for the menu, two bars (or a play triangle while paused) for pause. */
    private fun drawIconButton(canvas: Canvas, r: RectF, id: String, active: Boolean) {
        val h = r.height()
        canvas.drawRoundRect(r, h / 2, h / 2, if (active) btnActive else btnFill)
        iconInk.color = if (active) 0xFFFFFFFF.toInt() else 0xFF262B33.toInt()
        val cx = r.centerX(); val cy = r.centerY(); val u = h * 0.16f
        when {
            id == "menu" -> for (k in -1..1) canvas.drawLine(cx - u * 1.3f, cy + k * u, cx + u * 1.3f, cy + k * u, iconInk)
            // The legend: a bold "?" in the ink of the other icons, sized to the button and not to the font scale.
            id == "help" -> {
                helpGlyph.color = iconInk.color
                helpGlyph.textSize = h * 0.5f
                canvas.drawText("?", cx, cy - (helpGlyph.ascent() + helpGlyph.descent()) / 2f, helpGlyph)
            }
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

    /** The paused pill: where it goes ([rect]), the drawn [text] and the [full] text for TalkBack. */
    private class PausedBanner(val rect: RectF, val text: String, val full: String, val textSize: Float, val align: Paint.Align, val typeface: Typeface?)

    /** Places "Paused, keep building" at the top centre (see [drawPausedBanner]) while the clock is stopped in place. */
    private fun placePausedBanner(center: Float, maxWidth: Float, place: (Float, Float) -> Float): PausedBanner {
        val full = context.getString(R.string.hud_paused)
        val text = fitText(full, maxWidth - 28 * density, btnText)
        val w = btnText.measureText(text) + 28 * density
        val h = maxOf(30 * density, btnText.textSize * 2f)
        val top = place(w, h)
        return PausedBanner(RectF(center - w / 2f, top, center + w / 2f, top + h), text, full, btnText.textSize, btnText.textAlign, btnText.typeface)
    }

    /** [b] in a dark pill. */
    private fun drawPausedBanner(canvas: Canvas, b: PausedBanner) {
        val r = b.rect
        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, btnActive)
        // The buttons drawn since it was placed may have changed the paint: draw with the placed style, then restore.
        val size = btnText.textSize; val align = btnText.textAlign; val face = btnText.typeface
        btnText.textSize = b.textSize; btnText.textAlign = b.align; btnText.typeface = b.typeface
        btnText.color = 0xFFFFFFFF.toInt()
        canvas.drawText(b.text, r.centerX(), r.centerY() + btnText.textSize * 0.35f, btnText)
        btnText.textSize = size; btnText.textAlign = align; btnText.typeface = face
        hudNodes += UiNode("hud:paused", r, b.full, UiNode.Kind.TEXT)
    }

    /**
     * One centered line for the most urgent incident (a cut cable first, then the one due soonest), amber while
     * announced, red once struck, with "(+n)" for the others; each also gets a countdown pin on the map.
     */
    /** The incident pill's box, colour and (fitted and full) text, as [placeIncidentLine] placed it for this frame. */
    private val incidentPill = RectF()
    private var incidentPillColor = 0
    private var incidentPillText = ""
    private var incidentPillFull = ""

    /**
     * Places the incident line (the most urgent incident, and how many more) at the top of the HUD; false if there is
     * none. [drawIncidentLine] draws it once the hint below had its say.
     */
    private fun placeIncidentLine(maxWidth: Float, center: Float, place: (Float, Float) -> Float): Boolean {
        val incidents = world.incidents
        if (incidents.isEmpty()) return false
        val first = incidents.minWith(compareBy({ !(it.struck && it.kind == IncidentKind.EXCAVATOR) }, { if (it.struck) it.remaining else it.warning }))
        // A pill in the incident's colour with a warning sign, like the other HUD plates (judge panel: plain amber
        // text on the pale map had too little contrast).
        incidentPillColor = if (first.struck) IncidentStyles.CUT else IncidentStyles.WARNING.shade(-0.3f)
        val full = texts.incident(first).let { if (incidents.size > 1) context.getString(R.string.incident_more, it, incidents.size - 1) else it }
        val h = incidentText.textSize * 2f
        val icon = h * 0.62f
        val pad = h * 0.45f
        val text = fitText(full, maxWidth - icon - pad * 2.4f, incidentText)
        val w = incidentText.measureText(text) + icon + pad * 2.4f
        val top = place(w, h)
        incidentPill.set(center - w / 2f, top, center + w / 2f, top + h)
        incidentPillText = text
        incidentPillFull = full
        return true
    }

    private fun drawIncidentLine(canvas: Canvas) {
        val r = incidentPill
        val h = r.height()
        val icon = h * 0.62f
        val pad = h * 0.45f
        val pillColor = incidentPillColor
        val text = incidentPillText
        val full = incidentPillFull
        incidentText.color = 0xFFFFFFFF.toInt()
        pinFill.color = 0x33000000
        canvas.drawRoundRect(r.left, r.top + 2 * density, r.right, r.bottom + 2 * density, h / 2f, h / 2f, pinFill)
        pinFill.color = pillColor
        canvas.drawRoundRect(r, h / 2f, h / 2f, pinFill)
        // Warning sign: a white triangle with the pill's colour showing through as the "!".
        val ix = r.left + pad + icon / 2f
        val iy = r.centerY()
        incidentIcon.reset()
        incidentIcon.moveTo(ix, iy - icon * 0.48f); incidentIcon.lineTo(ix + icon * 0.52f, iy + icon * 0.42f); incidentIcon.lineTo(ix - icon * 0.52f, iy + icon * 0.42f); incidentIcon.close()
        pinFill.color = 0xFFFFFFFF.toInt(); canvas.drawPath(incidentIcon, pinFill)
        pinFill.color = pillColor
        canvas.drawRect(ix - icon * 0.05f, iy - icon * 0.2f, ix + icon * 0.05f, iy + icon * 0.14f, pinFill)
        canvas.drawCircle(ix, iy + icon * 0.26f, icon * 0.06f, pinFill)
        canvas.drawText(text, r.left + pad + icon + pad * 0.6f + incidentText.measureText(text) / 2f, r.centerY() + incidentText.textSize * 0.36f, incidentText)
        hudNodes += UiNode("hud:incident", RectF(r), full, UiNode.Kind.TEXT)
    }

    private val incidentIcon = android.graphics.Path()

    /** Screen boxes of the countdown pins drawn this frame, reused, so the next frame's server plates keep clear. */
    private val pinBoxes = ArrayList<RectF>()
    private var pinCount = 0

    /** The countdown pins' boxes of the last frame, for tests. */
    internal fun incidentPinBoxes(): List<RectF> = pinBoxes.subList(0, pinCount).map { RectF(it) }

    private val pinPill = RectF()

    /** Height of the pins' pills in the last frame; below it a pin's box holds only its thin stem. */
    private var pinH = 0f

    /** Registers the last frame's pins with the server plates: each pill and its thin stem, not the space beside it. */
    private fun overlayPins(labels: ServerLabels) {
        labels.clearOverlays()
        val half = 2 * density
        for (j in 0 until pinCount) pinBoxes[j].let {
            labels.overlay(it.left, it.top, it.right, it.top + pinH)
            labels.overlay(it.centerX() - half, it.top + pinH, it.centerX() + half, it.bottom)
        }
    }

    /** The boxes [overlayPins] registered in the last frame, for tests. */
    internal fun incidentPinOverlays(): List<RectF> = pinBoxes.subList(0, pinCount).flatMap {
        listOf(RectF(it.left, it.top, it.right, it.top + pinH), RectF(it.centerX() - 2 * density, it.top + pinH, it.centerX() + 2 * density, it.bottom))
    }

    /**
     * A countdown pin over every incident's spot on the map, so the line at the top points at its cable or node. Pins
     * that would cover each other stack upwards (the stem grows to its spot); the server plates treat each pin as an
     * obstacle ([ServerLabels.overlay]) where they can, and a pin moves up past a plate that could not, so a pin never
     * hides a server's name (only its thin stem may cross one).
     */
    private fun drawIncidentPins(canvas: Canvas) {
        if (world.rewardOffer != null) return
        // The countdown grows with the zoom (up to 1.8×), so close in it reads as large as the cut it belongs to.
        val base = textScale.px(12f)
        val k = (renderer.unitPx / (PIN_UNIT_DP * density)).coerceIn(1f, PIN_MAX_SCALE)
        pinText.textSize = base * k
        val gap = 3 * density * k
        for (i in world.incidents) {
            val at = renderer.toScreen(i.node?.center ?: i.spot)
            val seconds = ceil(if (i.struck) i.remaining else i.warning).toInt().coerceAtLeast(1)
            val text = context.getString(R.string.incident_countdown, seconds)
            val w = pinText.measureText(text) + 12 * density * k
            val h = 20 * density * k
            val stem = at.y - maxOf(renderer.unitPx * 0.9f, 26 * density) + 5 * density * k
            var bottom = stem - 5 * density * k
            // Up past every earlier pin it would touch and every server plate its pill would cover (a plate that found
            // no spot clear of the pins took one anyway), until it is clear of all of them.
            val labels = renderer.serverLabels
            var moved = true
            while (moved) {
                moved = false
                for (j in 0 until pinCount + labels.placedPlates) {
                    val o = if (j < pinCount) pinBoxes[j] else labels.placedPlate(j - pinCount)
                    if (at.x + w / 2f + gap <= o.left || at.x - w / 2f - gap >= o.right || bottom + gap <= o.top || bottom - h - gap >= o.bottom) continue
                    bottom = o.top - gap
                    moved = true
                }
            }
            // ... but never off the screen or under a HUD box (those of the last frame, as for the plates): there it
            // stays below the top HUD band instead (a spot below that band keeps its pin below it).
            pinPill.set(at.x - w / 2f, bottom - h, at.x + w / 2f, bottom)
            val hud = labels.reservedHit(pinPill, gap)
            if (bottom - h < safeInsets.top + gap || hud != null) {
                // Below the HUD box it ran into (the incident line over the map too), else above it.
                val ceiling = maxOf(renderer.camera.insets.top, hud?.bottom ?: 0f) + gap
                if (stem > ceiling + h) bottom = maxOf(bottom, ceiling + h)
                else if (hud != null && hud.top - gap - h >= safeInsets.top + gap) bottom = hud.top - gap
            }
            pinH = h
            if (pinCount == pinBoxes.size) pinBoxes += RectF()
            pinBoxes[pinCount++].set(at.x - w / 2f, bottom - h, at.x + w / 2f, stem)
            pinFill.color = if (i.struck) IncidentStyles.CUT else IncidentStyles.WARNING.shade(-0.2f)
            canvas.drawRoundRect(at.x - w / 2f, bottom - h, at.x + w / 2f, bottom, h / 2f, h / 2f, pinFill)
            canvas.drawLine(at.x, bottom, at.x, stem, pinFill.also { it.strokeWidth = 2 * density * k })
            canvas.drawText(text, at.x, bottom - h / 2f + pinText.textSize * 0.36f, pinText)
        }
        pinText.textSize = base
    }

    /** True if client [n] is overloading (its ring runs) or a request or two short of it. */
    private fun inTrouble(n: Node) = n.kind == NodeKind.CLIENT && QueueGauge.warns(n)

    /**
     * True if device [n] gets an off-screen chip: it is [inTrouble], or it had a chip last frame ([had]) and its queue is
     * at most one request under the pre-warning. The chip so outlasts a queue that swings around the limit, and the
     * other chips keep their places.
     */
    private fun wantsChip(n: Node, had: Boolean) =
        inTrouble(n) || (had && n.kind == NodeKind.CLIENT && n.pending.size >= World.Tuning.PREWARN_PENDING - 1)

    /**
     * A chip at the edge of the map's view for each device in trouble ([inTrouble], held a little longer by [wantsChip])
     * outside it, so a jam never builds up unseen: the device's icon in a white disc with an arrow towards it, the red
     * overload ring running round it (an dashed orange ring while the queue only nears the limit). Chips sit inside the
     * camera's view (clear of the HUD rows and the toolbar), so they hold in every style, zoom and turn; a device counts
     * as seen while it is on the surface and not under a HUD element. The most urgent come first; one that would cover
     * another chip or a HUD element slides along its edge, and one with no room left is dropped. A tap on the disc or its
     * arrow (or TalkBack, which also hears the side and the queue) glides the camera there.
     */
    private fun drawOffscreenChips(canvas: Canvas) {
        if (world.rewardOffer != null) return
        if (offscreenWorld !== world) {
            offscreenChips.clear()
            offscreenWorld = world
        }
        val frame = ++offscreenFrame
        val nodes = world.nodes
        val troubled = offscreenTroubled
        troubled.clear()
        for (i in nodes.indices) {
            val n = nodes[i]
            val chip = offscreenChips[n]
            if (!wantsChip(n, chip != null)) continue
            (chip ?: OffscreenChip(n).also { offscreenChips[n] = it }).seen = frame
            troubled += n
        }
        if (offscreenChips.size > troubled.size) offscreenChips.values.removeIf { it.seen != frame }
        if (troubled.isEmpty()) return
        val cam = renderer.camera
        val area = offscreenArea
        area.set(cam.insets.left, cam.insets.top, surfaceWidth - cam.insets.right, surfaceHeight - cam.insets.bottom)
        // Chips for devices under the toolbar sit above its tray, never in it (where none would find room).
        if (!toolbarTop.isNaN()) area.bottom = minOf(area.bottom, toolbarTop)
        val r = OFFSCREEN_CHIP_DP / 2f * density
        val reach = r * 1.5f + 4 * density
        val inner = offscreenInner
        inner.set(area.left + reach, area.top + reach, area.right - reach, area.bottom - reach)
        if (inner.width() <= 0f || inner.height() <= 0f) return
        troubled.sortWith(offscreenOrder)
        offscreenTaken.clear()
        for (n in hudNodes) offscreenTaken += n.bounds
        if (!trayBox.isEmpty) offscreenTaken += trayBox
        toolbarRaised?.let { offscreenTaken += it }
        val fixed = offscreenTaken.size
        val cx = inner.centerX(); val cy = inner.centerY()
        val step = 2 * r + 8 * density
        val pad = 4 * density
        val edge = 12 * density
        var placed = 0
        for (n in troubled) {
            if (placed >= MAX_OFFSCREEN_CHIPS) break
            val at = n.footprintCenter
            val px = cam.worldToScreenX(at.x, at.y); val py = cam.worldToScreenY(at.x, at.y)
            if (px >= edge && px <= surfaceWidth - edge && py >= edge && py <= surfaceHeight - edge) {
                var covered = false
                for (i in 0 until fixed) if (offscreenTaken[i].contains(px, py)) { covered = true; break }
                if (!covered) continue
            }
            // Where the line from the view's middle to the device leaves the chips' room.
            val dx = px - cx; val dy = py - cy
            val tx = if (dx > 0f) (inner.right - cx) / dx else if (dx < 0f) (inner.left - cx) / dx else Float.MAX_VALUE
            val ty = if (dy > 0f) (inner.bottom - cy) / dy else if (dy < 0f) (inner.top - cy) / dy else Float.MAX_VALUE
            val t = minOf(tx, ty)
            val ex = cx + dx * t; val ey = cy + dy * t
            val alongY = tx < ty
            var found = false
            val probe = offscreenProbe
            for (k in 0..OFFSCREEN_SLIDES) {
                val off = step * ((k + 1) / 2) * (if (k % 2 == 0) 1 else -1)
                val x = if (alongY) ex else (ex + off).coerceIn(inner.left, inner.right)
                val y = if (alongY) (ey + off).coerceIn(inner.top, inner.bottom) else ey
                probe.set(x - r - pad, y - r - pad, x + r + pad, y + r + pad)
                var free = true
                for (i in offscreenTaken.indices) if (RectF.intersects(offscreenTaken[i], probe)) { free = false; break }
                if (free) {
                    found = true
                    break
                }
            }
            if (!found) continue
            val box = offscreenBoxes[placed]
            box.set(probe.left + pad, probe.top + pad, probe.right - pad, probe.bottom - pad)
            offscreenTaken += box
            val angle = kotlin.math.atan2(py - box.centerY(), px - box.centerX())
            drawOffscreenChip(canvas, n, box.centerX(), box.centerY(), r, angle)
            val chip = offscreenChips.getValue(n)
            val side = if (alongY) (if (dx > 0f) SIDE_RIGHT else SIDE_LEFT) else (if (dy > 0f) SIDE_BOTTOM else SIDE_TOP)
            val full = QueueGauge.full(n)
            if (chip.full != full || chip.side != side || chip.waiting != n.pending.size) {
                chip.full = full; chip.side = side; chip.waiting = n.pending.size
                chip.label = texts.offscreen(n, full, side, n.pending.size)
            }
            buttons += Button(chip.id, box)
            // The arrow reaches past the disc; a tap on it pans too, but a disc always wins over another chip's arrow.
            val c = kotlin.math.cos(angle); val sn = kotlin.math.sin(angle)
            val tip = r * 1.5f
            val hit = offscreenArrowHits[placed]
            hit.set(box.centerX() + c * tip, box.centerY() + sn * tip, box.centerX() + c * tip, box.centerY() + sn * tip)
            hit.inset(-r * 0.55f, -r * 0.55f)
            offscreenArrowIds[placed] = chip.id
            hudNodes += UiNode(chip.key, RectF(box), chip.label, UiNode.Kind.BUTTON)
            placed++
        }
        for (i in 0 until placed) buttons += Button(offscreenArrowIds[i]!!, offscreenArrowHits[i])
    }

    /** One off-screen chip of radius [r] around ([x], [y]) for device [n], its arrow pointing along [angle] (radians). */
    private fun drawOffscreenChip(canvas: Canvas, n: Node, x: Float, y: Float, r: Float, angle: Float) {
        val overloading = QueueGauge.full(n)
        val color = QueueGauge.color(n)
        val beat = if (overloading) 0.5f + 0.5f * kotlin.math.sin(animTime * (5f + 6f * n.overload)) else 0f
        val c = kotlin.math.cos(angle); val s = kotlin.math.sin(angle)
        // The arrow first, so the disc's shadow and casing sit over its base.
        val tip = r * (1.42f + 0.06f * beat); val base = r * 0.82f; val half = r * 0.5f
        offscreenArrow.reset()
        offscreenArrow.moveTo(x + c * tip, y + s * tip)
        offscreenArrow.lineTo(x + c * base - s * half, y + s * base + c * half)
        offscreenArrow.lineTo(x + c * base + s * half, y + s * base - c * half)
        offscreenArrow.close()
        offscreenRing.color = 0xFFFFFFFF.toInt(); offscreenRing.strokeWidth = r * 0.18f
        offscreenRing.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(offscreenArrow, offscreenRing)
        offscreenFill.color = color
        canvas.drawPath(offscreenArrow, offscreenFill)
        offscreenFill.color = 0x40000000
        canvas.drawCircle(x, y + r * 0.12f, r, offscreenFill)
        offscreenFill.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(x, y, r, offscreenFill)
        // The timer ring as on the map: a dark track and the red arc that runs out; a full orange ring before it starts.
        val ring = r * 0.8f
        offscreenRing.strokeWidth = r * 0.2f
        if (overloading) {
            offscreenRing.color = 0x402A1418
            canvas.drawCircle(x, y, ring, offscreenRing)
            offscreenRing.color = color
            canvas.drawArc(x - ring, y - ring, x + ring, y + ring, -90f, 360f * n.overload, false, offscreenRing)
        } else {
            offscreenRing.color = color
            if (offscreenDashRing != ring) {
                val seg = (2 * Math.PI * ring / 12).toFloat()
                offscreenDash = android.graphics.DashPathEffect(floatArrayOf(seg * 0.6f, seg * 0.4f), 0f)
                offscreenDashRing = ring
            }
            offscreenRing.pathEffect = offscreenDash
            canvas.drawCircle(x, y, ring, offscreenRing)
            offscreenRing.pathEffect = null
        }
        n.device?.let { toolIcons.device(canvas, it, x, y, r * 0.4f) }
    }

    /**
     * A pulsing yellow glow on the selected cable or node, the one a second tap acts on; a cable that can be re-routed
     * also shows a grab handle at each end. While a handle is dragged, the drag preview shows the cable instead.
     */
    private fun drawSelection(canvas: Canvas) {
        val sel = selection ?: return
        if (grabbing) return
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
                if (canReroute(sel)) for (h in handlePoints(sel.layout)) {
                    val q = renderer.toScreen(h)
                    DragJuice.handle(canvas, q.x, q.y, CableStyles.of(sel.type).color, density)
                }
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
            is Input.Reveal -> when {
                screen == Screen.SCENERIES && input.key.startsWith("scenery:") -> sceneryPicker.reveal(input.key.removePrefix("scenery:"))
                screen == Screen.ACHIEVEMENTS && input.key.startsWith("achievement:") -> achievementsPanel.reveal(input.key.removePrefix("achievement:"))
                screen == Screen.LEGEND && input.key.startsWith("legend:") -> legendPanel.reveal(input.key.removePrefix("legend:"))
            }
            Input.SignedIn -> onSignedIn()
            is Input.CloudLoaded -> applyCloud(input.progress)
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
        if (screen == Screen.ACHIEVEMENTS) {
            onAchievementsTouch(e)
            return
        }
        if (screen == Screen.LEGEND) {
            onLegendTouch(e)
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
        if (toolPress != null && onToolTouch(e)) return
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downTime = e.time
                cameraGesture = false
                endToolDrag()
                buttons.firstOrNull { it.rect.contains(e.x, e.y) }?.let {
                    // A network tile waits for the finger: lifted, it arms placing; dragged, it carries the device.
                    if (it.id == "router" || it.id.startsWith("radio:")) toolPress = it.id else onButton(it.id)
                    return
                }
                dragTrail.clear()
                // While placing, the tap (not the start of a pan or pinch) decides where: see ACTION_UP.
                grab = if (placing != null) null else grabAt(e.x, e.y)
                dragFrom = if (placing != null || grab != null) null else pickNode(e.x, e.y)
                // A finger on a device lights up the servers it needs; anywhere else ends a tap's highlight.
                val sought = dragFrom?.let(::servicesSought).orEmpty()
                if (sought.isNotEmpty()) {
                    if (sought != focusServices || animTime >= focusUntil) focusSince = animTime
                    focusServices = sought
                } else {
                    focusUntil = 0f
                    focusServices = emptySet()
                }
                if (dragFrom == null && grab == null && isDoubleTap(e)) {
                    renderer.fitArea(world, animate = true)
                    emptyTapTime = null
                    cameraGesture = true
                    return
                }
                panArmed = dragFrom == null && grab == null
                holdAp = dragFrom?.takeIf { it.kind == NodeKind.ACCESS_POINT }
                holdCable = if (panArmed && placing == null) renderer.cableAtScreen(world, e.x, e.y, TouchTargets.cableRadiusPx(renderer, density)) else null
                holdStart = animTime
                holdFired = false
                trackDrag(e.x, e.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger turns any gesture into camera movement; a half-drawn cable is dropped.
                holdAp = null
                endDrag()
                cameraGesture = true
                viewTouchedAt = animTime
                renderer.camera.stopRotation()
                renderer.camera.stopTilt()
                startPinch(e)
            }
            MotionEvent.ACTION_MOVE -> when {
                cameraGesture -> if (e.pointers.size >= 4) {
                    viewTouchedAt = animTime
                    val before = renderer.camera.angle
                    val move = { pinch.move(e.pointers[0], e.pointers[1], e.pointers[2], e.pointers[3], renderer.camera, tilt = renderer.tilts) }
                    // A move that may tilt keeps the playable area framed at the new pitch (and renews the limits).
                    if (renderer.tilts && !pinch.turning) renderer.keepFramed(world, move) else move()
                    if (renderer.camera.angle != before) renderer.updateLimits(world)
                }
                grab != null -> {
                    if (!grabbing && hypot(e.x - downX, e.y - downY) >= TAP_SLOP_DP * density) {
                        grabbing = true
                        dragTrail.clear()
                    }
                    if (grabbing) trackDrag(e.x, e.y)
                }
                dragFrom != null -> {
                    if (hypot(e.x - downX, e.y - downY) >= TAP_SLOP_DP * density) holdAp = null
                    trackDrag(e.x, e.y)
                }
                panArmed -> {
                    val held = holdCable
                    if (held != null) {
                        val still = hypot(e.x - downX, e.y - downY) < TAP_SLOP_DP * density
                        if (!still) holdCable = null
                        else if (e.time - downTime >= LONG_PRESS_MS) {
                            grabByHold(held)
                            return
                        }
                    }
                    panWithOneFinger(e)
                }
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
                        NodeKind.CELL_TOWER -> cellTap(from)
                    }
                } else if (from != null) {
                    trackDrag(e.x, e.y)
                    pickNode(e.x, e.y, except = from)?.let {
                        val bend = dragBend(from.cell, it.cell)
                        if (world.connect(from, it, cableType, bend)) {
                            selection = null
                            haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                            sounds.play(Sound.CABLE)
                        } else {
                            when (val error = world.connectError(from, it, cableType, bend)) {
                                ConnectError.FROM_PORTS_FULL -> portsFull(from)
                                ConnectError.TO_PORTS_FULL -> portsFull(it)
                                null, ConnectError.SAME_NODE -> Unit
                                else -> refuse(
                                    texts.connectFailure(
                                        error, from, it, cableType, world.cableCost(from, it, cableType, bend),
                                        world.yearOfWeek(cableType.unlockWeek),
                                    ),
                                    noBudget = error == ConnectError.NO_BUDGET,
                                )
                            }
                        }
                    }
                } else if (grab != null) {
                    val g = grab!!
                    when {
                        grabbing -> finishReroute(e.x, e.y)
                        // A tap on a handle only keeps the cable selected; the second tap that removes it goes elsewhere.
                        isTap && g.moving != null && onHandle(g.cable, e.x, e.y) -> select(g.cable)
                        isTap -> tapGround(e)
                    }
                } else if (isTap && panArmed) {
                    tapGround(e)
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

    /** A tap that started on no node: on a cable, see [cableTap]; on empty ground it deselects and may start a double tap. */
    private fun tapGround(e: Input.Touch) {
        val cable = renderer.cableAtScreen(world, e.x, e.y, TouchTargets.cableRadiusPx(renderer, density))
        if (cable == null) {
            selection = null
            emptyTapTime = e.time; emptyTapX = e.x; emptyTapY = e.y
        } else {
            cableTap(cable)
        }
    }

    /** True if ([sx], [sy]) is on one of [c]'s drawn handles (with a finger's margin), not just near it. */
    private fun onHandle(c: Cable, sx: Float, sy: Float) = handlePoints(c.layout).any {
        val q = renderer.toScreen(it)
        hypot(q.x - sx, q.y - sy) <= (DragJuice.HANDLE_DP + HANDLE_TAP_MARGIN_DP) * density
    }

    /**
     * The rest of a gesture that went down on a network tile ([toolPress]): past the tap slop the device follows the
     * finger ([drawToolGhost]) and is placed where it is let go, on the map, not over the toolbar; lifted in place it is
     * a tap. A second finger drops it for a pinch. Returns true if the touch was handled here.
     */
    private fun onToolTouch(e: Input.Touch): Boolean {
        val id = toolPress ?: return false
        when (e.action) {
            MotionEvent.ACTION_MOVE -> {
                if (toolDrag == null && hypot(e.x - downX, e.y - downY) >= TAP_SLOP_DP * density) {
                    val kind = if (id == "router") NodeKind.ROUTER else RadioType.valueOf(id.removePrefix("radio:")).kind
                    val stock = if (world.unlimited) Int.MAX_VALUE else RadioType.of(kind)?.let(world::radiosAvailable) ?: world.routersAvailable
                    if (stock <= 0) {
                        refuse(placeErrorText(kind, PlaceError.NO_STOCK), subject = kind)
                        endToolDrag()
                        return true
                    }
                    toolDrag = kind
                    placing = null
                    selection = null
                    haptic(HapticFeedbackConstants.CLOCK_TICK)
                }
                if (toolDrag != null) toolDragAt = Vec2(e.x, e.y)
            }
            MotionEvent.ACTION_UP -> {
                val kind = toolDrag
                when {
                    kind == null -> onButton(id)
                    !overToolbar(e.x, e.y) -> cellAt(e.x, e.y).let { place(kind, it.x, it.y) }
                }
                endToolDrag()
            }
            MotionEvent.ACTION_CANCEL -> endToolDrag()
            // A second finger: the normal handling starts the pinch (and ends this gesture with [endDrag]).
            else -> return false
        }
        return true
    }

    private fun endToolDrag() {
        toolPress = null
        toolDrag = null
        toolDragAt = null
    }

    /**
     * A cable failed because [n] has no free port: say how many it has and that a router goes in between, let the router
     * tile pulse, and the first time in a game explain the port dots right after.
     */
    private fun portsFull(n: Node) {
        routerPulseAt = animTime
        refuse(texts.portsFull(n, R.plurals.ports_full), LONG_HINT_SECONDS)
        if (!portsTipShown) {
            portsTipShown = true
            val ports = NodeKind.ROUTER.maxPorts
            hintQueue.addFirst(resources.getQuantityString(R.plurals.tip_ports, ports, ports))
        }
    }

    /** (Re)starts the two-finger gesture from the fingers still down, or stops it if fewer than two remain. */
    private fun startPinch(e: Input.Touch) {
        val p = e.pointers
        pinch.density = density
        if (p.size >= 4) pinch.start(p[0], p[1], p[2], p[3]) else stopPinch()
    }

    /**
     * Ends the two-finger gesture: unless "free rotation" is on, the map eases to the nearest multiple of 45° (a corner
     * or a side-on view) around the point where the fingers were (docs/TOP100.md B5). A tilt stays where it was left.
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
                viewTouchedAt = animTime
            }
            return
        }
        renderer.camera.panBy(e.x - last.x, e.y - last.y)
        panFrom = Vec2(e.x, e.y)
        viewTouchedAt = animTime
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
                newGame(Scenarios.RIVER_TOWN, GameMode.NORMAL)
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

    /**
     * What the tutorial highlights; while a router is being placed (armed or dragged), the devices it should go next to
     * instead of the tile.
     */
    private fun tutorialFocus(t: Tutorial): TutorialFocus {
        val focus = t.focus(cableType)
        val carrying = placing == NodeKind.ROUTER || toolDrag == NodeKind.ROUTER
        return if (focus == TutorialFocus.RouterButton && carrying) TutorialFocus.Nodes(t.placeNear()) else focus
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
            val text = when (error) {
                ServerUpgradeError.NOT_A_SERVER -> return
                ServerUpgradeError.MAX_LEVEL -> context.getString(R.string.server_error_max_level)
                ServerUpgradeError.NO_SPACE -> context.getString(R.string.server_error_no_space)
                ServerUpgradeError.NO_BUDGET ->
                    context.getString(R.string.server_error_no_budget, World.Tuning.SERVER_UPGRADE_COST[server.level - 1])
            }
            // The top tier is a fact, not a refused action: no buzz for it.
            if (error == ServerUpgradeError.MAX_LEVEL) showHint(text, subject = server)
            else refuse(text, noBudget = error == ServerUpgradeError.NO_BUDGET, subject = server)
            return
        }
        val next = tierName(server.level + 1)
        if (selection !== server) {
            select(server)
            showHint(
                if (world.serverVouchers > 0) context.getString(R.string.hint_server_preview_voucher, texts.node(server), next)
                else context.getString(R.string.hint_server_preview, texts.node(server), next, World.Tuning.SERVER_UPGRADE_COST[server.level - 1]),
                SELECT_SECONDS,
                subject = server,
                direct = true,
            )
            return
        }
        if (!world.upgradeServer(server)) return
        selection = null
        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
        sounds.play(Sound.CABLE)
        learned(Coaching.SERVER_QUEUE)
        showHint(context.getString(R.string.hint_server_upgraded, texts.node(server), next), subject = server)
    }

    /**
     * A tap on a cell tower previews its next mobile generation (like a server tier); a second tap upgrades it.
     * Otherwise it says why not: already the newest, not invented yet or too little budget.
     */
    private fun cellTap(tower: Node) {
        val next = tower.cellGeneration?.next
        val error = world.cellUpgradeError(tower)
        if (error != null) {
            val text = when (error) {
                CellUpgradeError.NOT_A_CELL_TOWER -> return
                CellUpgradeError.NEWEST -> context.getString(R.string.cell_error_newest)
                CellUpgradeError.NOT_INVENTED ->
                    context.getString(R.string.cell_error_not_invented, next!!.longLabel, world.yearOfWeek(next.unlockWeek))
                CellUpgradeError.NO_BUDGET -> context.getString(R.string.server_error_no_budget, next!!.upgradeCost)
            }
            if (error == CellUpgradeError.NEWEST) showHint(text, subject = tower)
            else refuse(text, noBudget = error == CellUpgradeError.NO_BUDGET, subject = tower)
            return
        }
        next!!
        if (selection !== tower) {
            select(tower)
            showHint(context.getString(R.string.hint_server_preview, texts.node(tower), next.longLabel, next.upgradeCost), SELECT_SECONDS, tower, direct = true)
            return
        }
        if (!world.upgradeCell(tower)) return
        selection = null
        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
        sounds.play(Sound.CABLE)
        showHint(context.getString(R.string.hint_server_upgraded, texts.node(tower), next.longLabel), subject = tower)
    }

    private fun tierName(level: Int) =
        if (level >= World.Tuning.DATA_CENTER_LEVEL) context.getString(R.string.server_tier_data_center) else context.getString(R.string.server_tier, level)

    /**
     * A tap on an intact cable upgrades it to a better picked technology. Otherwise the first tap selects it (with grab
     * handles to re-route it, see [grabAt]) and says what removing it gives back; a second tap on the selected cable
     * removes it. A cut cable is repaired.
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
                    learned(Coaching.CABLE_UPGRADE)
                    showHint(
                        if (world.unlimited) context.getString(R.string.hint_cable_upgraded_free, texts.cable(cableType))
                        else context.getString(R.string.hint_cable_upgraded, texts.cable(cableType), price),
                        subject = cable,
                    )
                }
                CableUpgradeError.NO_BUDGET -> refuse(context.getString(R.string.cable_error_no_budget, price), noBudget = true, subject = cable)
                CableUpgradeError.NOT_INVENTED -> refuse(context.getString(R.string.connect_error_not_invented, texts.cable(cableType)), subject = cable)
                CableUpgradeError.NOT_AN_UPGRADE -> Unit
            }
            return
        }
        val refund = world.refundOf(cable)
        if (selection !== cable) {
            select(cable)
            val movable = canReroute(cable)
            showHint(
                when {
                    world.unlimited && movable -> context.getString(R.string.hint_cable_select_free, texts.cable(cable.type))
                    world.unlimited -> context.getString(R.string.hint_cable_remove_free, texts.cable(cable.type))
                    movable -> context.getString(R.string.hint_cable_select, texts.cable(cable.type), refund)
                    else -> context.getString(R.string.hint_cable_remove, texts.cable(cable.type), refund)
                },
                SELECT_SECONDS,
                cable,
                direct = true,
            )
            return
        }
        selection = null
        world.removeCable(cable)
        haptic(HapticFeedbackConstants.CLOCK_TICK)
        showHint(if (world.unlimited) context.getString(R.string.hint_cable_removed_free) else context.getString(R.string.hint_cable_removed, refund), subject = cable)
    }

    /**
     * A router without cables goes back into stock on a second tap. One with cables (or an incident) is only selected
     * by the first tap, which may be a look and so leaves other hints alone; the second tap is refused with the reason.
     */
    private fun routerTap(router: Node) {
        world.pickUpError(router)?.let { error ->
            val text = texts.pickUpError(error) ?: return
            if (selection !== router) {
                select(router)
            } else {
                selection = null
                refuse(text, subject = router)
            }
            return
        }
        if (selection !== router) {
            select(router)
            showHint(context.getString(R.string.hint_router_pick_up), SELECT_SECONDS, router, direct = true)
            return
        }
        selection = null
        if (world.pickUp(router)) {
            haptic(HapticFeedbackConstants.CLOCK_TICK)
            showHint(context.getString(R.string.hint_router_picked_up), subject = router)
        }
    }

    /**
     * Says which server the device's requests are for and why they are stuck ("Konsole → Game-Server: Ping 180 ms,
     * erlaubt 140 ms"), what its oldest request wants ("PC will Mail → Mail-Server"), or, with none waiting, which
     * servers it needs; the servers light up meanwhile ([serverFocus]).
     */
    private fun explainClient(client: Node) {
        // In the order of the device's badge (ProblemBadges.drawFor): a blocked route, a missing server, a jam.
        val blocked = client.pending.firstOrNull { ProblemBadges.shows(world.routeProblem(client, it)) }
        val missing = world.firstUnreachableService(client)
        val stuck = client.pending.firstOrNull { world.routeProblem(client, it) != null }
        val jammed = world.isJammed(client)
        val jam = if (jammed) world.jamLink(client) else null
        val waiting = client.pending.firstOrNull()
        showHint(
            when {
                blocked != null -> blockedText(client, blocked)
                missing != null -> texts.noServer(client, missing)
                stuck != null -> blockedText(client, stuck)
                jammed && (jam != null || world.jamServer(client) != null) ->
                    texts.jam(client, client.pending.first { world.routeFor(client, it) != null }, jam)
                waiting != null -> context.getString(R.string.device_wants_server, texts.node(client), texts.serviceWithGlyph(waiting), texts.server(waiting))
                else -> context.getString(
                    R.string.device_needs_servers, texts.node(client),
                    client.device!!.services.joinToString(SERVER_LIST_SEPARATOR) { texts.serverWithGlyph(it) },
                )
            },
            LONG_HINT_SECONDS,
            client,
        )
        focusUntil = animTime + FOCUS_TAP_SECONDS
    }

    /** [problemText] for [client]'s requests for [s], with the ping of its best route when that is the problem. */
    private fun blockedText(client: Node, s: Service): String {
        val problem = world.routeProblem(client, s)
        return problemText(client, s, problem, if (problem == RouteProblem.PING_TOO_HIGH) world.bestRoute(client, s)?.pingMs else null)
    }

    /** "Konsole → Game-Server: Ping 180 ms, erlaubt 140 ms" and the like, for a device, a service and its [RouteProblem]. */
    private fun problemText(n: Node, s: Service, problem: RouteProblem?, pingMs: Float?): String {
        val node = texts.node(n)
        val service = texts.serverWithGlyph(s)
        return when (problem) {
            RouteProblem.NO_ROUTE -> context.getString(R.string.problem_no_route, node, service)
            RouteProblem.TOO_NARROW -> context.getString(R.string.problem_too_narrow, node, service, s.bandwidth)
            RouteProblem.PING_TOO_HIGH ->
                context.getString(R.string.problem_ping, node, service, (pingMs ?: 0f).roundToInt(), s.maxPingMs ?: 0)
            null -> context.getString(R.string.problem_jam, node, service)
        }
    }

    /** Why the game ended as one short sentence for the game-over card ("Phone couldn’t reach a Phone exchange"). */
    private fun gameOverReason(n: Node, s: Service, problem: RouteProblem?, pingMs: Float?): String {
        val node = texts.node(n)
        val service = texts.service(s)
        return when (problem) {
            // The server by the name the tip under it uses ("Telefonzentrale"), not the service's.
            RouteProblem.NO_ROUTE -> context.getString(R.string.game_over_no_route, node, texts.server(s))
            RouteProblem.TOO_NARROW -> context.getString(R.string.game_over_too_narrow, node, service)
            RouteProblem.PING_TOO_HIGH ->
                context.getString(R.string.game_over_ping, node, service, (pingMs ?: 0f).roundToInt(), s.maxPingMs ?: 0)
            null -> context.getString(R.string.game_over_jam, node, service)
        }
    }

    /** The world and its clock [lossReason] last worked out the card's reason and tip for. */
    private var lossFor: World? = null
    private var lossAt = -1f
    private var lossReasonLine: String? = null
    private var lossTipLine: String? = null

    /**
     * Why the game was lost, for the card ([gameOverReason]), with the tip against it in [lossTipLine]: worked out once
     * per lost game, not on every frame the card is drawn. Null if the world names no failed device.
     */
    private fun lossReason(): String? {
        if (lossFor !== world || lossAt != world.time) {
            lossFor = world
            lossAt = world.time
            val f = world.failure
            lossReasonLine = f?.let { gameOverReason(it.node, it.service, it.problem, it.pingMs) }
            lossTipLine = f?.let(::lossTipText)
        }
        return lossReasonLine
    }

    /** The one thing to do better next time against [f], from its cause ([World.lossTip]), for the game-over card. */
    internal fun lossTipText(f: Failure, tip: LossTip = world.lossTip(f)): String {
        val node = texts.node(f.node)
        return when (tip) {
            LossTip.CONNECT -> context.getString(R.string.loss_tip_connect, node, texts.server(f.service))
            // The device's ports, or else every server's of the service: the one to put a router in front of.
            LossTip.ROUTER -> context.getString(
                R.string.loss_tip_router, if (world.ports(f.node) >= f.node.maxPorts) node else texts.server(f.service),
            )
            LossTip.NEEDS_SERVER -> context.getString(R.string.loss_tip_needs_server, node, texts.server(f.service))
            LossTip.REPAIR -> context.getString(R.string.loss_tip_repair)
            LossTip.OUTAGE -> context.getString(R.string.loss_tip_outage)
            LossTip.WIDER_CABLE -> {
                // The technologies wide enough for the service, the invented ones if any (streaming: DSL / Coax / Fiber).
                val wide = CableType.entries.filter { it.capacity >= f.service.bandwidth }
                val shown = wide.filter { it in world.unlockedCables }.ifEmpty { wide }
                context.getString(R.string.loss_tip_wider_cable, texts.service(f.service), shown.joinToString(CABLE_CHOICE_SEPARATOR) { texts.cable(it) })
            }
            LossTip.FASTER_CABLE -> context.getString(R.string.loss_tip_faster_cable, texts.service(f.service))
            LossTip.SECOND_CABLE -> context.getString(R.string.loss_tip_second_cable)
            LossTip.UPGRADE_SERVER -> context.getString(R.string.loss_tip_upgrade_server, texts.server(f.service))
            LossTip.SECOND_SERVER -> context.getString(R.string.loss_tip_second_server, texts.server(f.service))
        }
    }

    private fun select(target: Any) {
        selection = target
        selectionUntil = animTime + SELECT_SECONDS
    }

    private fun repair(cable: Cable) {
        if (world.repairError(cable) == RepairError.NO_BUDGET) {
            refuse(context.getString(R.string.repair_error_no_budget, Incidents.REPAIR_COST), noBudget = true, subject = cable)
            return
        }
        if (!world.repair(cable)) return
        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
        sounds.play(Sound.CABLE)
        learned(Coaching.INCIDENT)
        showHint(context.getString(R.string.hint_repaired), subject = cable)
    }

    /**
     * Places a router or radio from stock where the player tapped and disarms placing; a new access point explains its
     * controls. A cell that does not work says why and placing stays armed for another try.
     */
    private fun place(kind: NodeKind, cx: Int, cy: Int) {
        val radio = RadioType.of(kind)
        val error = world.placeError(kind, cx, cy)
        if (error != null) {
            refuse(placeErrorText(kind, error), subject = kind)
            if (error == PlaceError.NO_STOCK) placing = null
            return
        }
        val placed = (if (radio == null) world.placeRouter(cx, cy) else world.placeRadio(radio, cx, cy)) ?: return
        placing = null
        haptic(HapticFeedbackConstants.CLOCK_TICK)
        if (radio != null) learned(Coaching.RADIO_STOCK)
        if (placed.kind == NodeKind.ACCESS_POINT) showHint(context.getString(R.string.hint_access_point, Wifi.UPGRADE_5_GHZ_COST), subject = kind)
        placed.cellGeneration?.let { showHint(context.getString(R.string.hint_cell_tower, it.longLabel), subject = kind) }
    }

    /** Why [kind] cannot go where the player tapped or let go. */
    private fun placeErrorText(kind: NodeKind, error: PlaceError): String = when (error) {
        PlaceError.NO_STOCK -> context.getString(R.string.place_error_no_stock, RadioType.of(kind)?.let(texts::radio) ?: context.getString(R.string.node_router))
        PlaceError.LOCKED -> context.getString(R.string.place_error_locked)
        PlaceError.OCCUPIED -> context.getString(R.string.place_error_occupied)
        PlaceError.TERRAIN -> context.getString(R.string.place_error_terrain)
    }

    private fun cycleChannel(ap: Node) {
        if (!world.cycleChannel(ap)) return
        haptic(HapticFeedbackConstants.CLOCK_TICK)
        val name = texts.node(ap)
        val clashes = world.interferers(ap).size
        showHint(
            if (clashes == 0) context.getString(R.string.hint_channel, name, ap.channel)
            else resources.getQuantityString(R.plurals.hint_channel_interference, clashes, name, ap.channel, clashes),
            subject = ap,
        )
    }

    /** Fires a hold on an access point or a cable as soon as it lasted [LONG_PRESS_MS], not only when the finger lifts. */
    private fun checkHold() {
        holdCable?.let { c ->
            if (screen != Screen.PLAYING || world.gameOver || world.rewardOffer != null || failFocusUntil != null || c !in world.cables) holdCable = null
            else if (animTime - holdStart >= LONG_PRESS_MS / 1000f) grabByHold(c)
        }
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

    /**
     * While the camera glides to the device that overflowed ([failFocusUntil]): a callout under its red ring with the
     * device's icon and "Telefon überlastet", pointing up at it, so the player sees which device lost the game even
     * where its request bubble hides the icon on the map. It fades in, keeps clear of the bottom tray and the screen
     * edges, and its box goes into [calloutBox]. Returns true if it was drawn.
     */
    private fun drawFailCallout(canvas: Canvas): Boolean {
        val until = failFocusUntil ?: return false
        val n = world.failedNode ?: return false
        val label = failCallout ?: return false
        val cam = renderer.camera
        val cx = cam.worldToScreenX(n.footprintCenter.x, n.footprintCenter.y)
        val cy = cam.worldToScreenY(n.footprintCenter.x, n.footprintCenter.y)
        val fade = ((animTime - (until - GAME_OVER_FOCUS_SECONDS)) / CALLOUT_FADE_SECONDS).coerceIn(0f, 1f)
        val alpha = (fade * 255).toInt()
        val h = maxOf(CALLOUT_MIN_DP * density, calloutText.textSize * 2.1f)
        val icon = h * 0.3f
        val pad = 12f * density
        val margin = 16f * density
        // Long names in large text ("Telecamera di sorveglianza") would run off a phone: the text shrinks to the room.
        val chrome = pad + 2 * icon + 8f * density + pad
        val room = surfaceWidth - safeInsets.left - safeInsets.right - 2 * margin - chrome
        if (room != calloutFitWidth) {
            calloutFitWidth = room
            calloutFitted = fitText(label, maxOf(room, 0f), calloutText)
        }
        val text = calloutFitted
        val w = chrome + calloutText.measureText(text)
        val gap = maxOf(renderer.unitPx * 0.55f, 28f * density)
        val bottom = if (trayBox.isEmpty) surfaceHeight - safeInsets.bottom - margin else trayBox.top - 8f * density
        val topLimit = safeInsets.top + margin
        // Under the ring, or above it (over the request bubble) where the tray leaves no room there once the camera
        // has arrived (the device then sits at the view's center); decided once so the pill does not jump mid-glide.
        val below = calloutBelow ?: (cam.centerY + gap + h <= bottom).also { calloutBelow = it }
        val wanted = if (below) cy + gap else cy - gap - h - renderer.unitPx * 0.6f
        val top = wanted.coerceIn(topLimit, maxOf(topLimit, bottom - h))
        val left = (cx - w / 2f).coerceIn(safeInsets.left + margin, maxOf(safeInsets.left + margin, surfaceWidth - safeInsets.right - margin - w))
        calloutBox.set(left, top, left + w, top + h)
        // The pointer towards the device.
        val px = cx.coerceIn(left + h * 0.5f, left + w - h * 0.5f)
        val tip = h * 0.32f
        calloutPath.reset()
        if (below) {
            calloutPath.moveTo(px - tip, top + 1f); calloutPath.lineTo(px, top - tip); calloutPath.lineTo(px + tip, top + 1f)
        } else {
            calloutPath.moveTo(px - tip, top + h - 1f); calloutPath.lineTo(px, top + h + tip); calloutPath.lineTo(px + tip, top + h - 1f)
        }
        calloutPath.close()
        calloutFill.color = CALLOUT_SHADOW
        calloutFill.alpha = (alpha * 0.25f).toInt()
        canvas.drawRoundRect(left, top + 3f * density, left + w, top + h + 3f * density, h / 2f, h / 2f, calloutFill)
        calloutFill.color = PORTS_FULL_RED
        calloutFill.alpha = alpha
        canvas.drawRoundRect(calloutBox, h / 2f, h / 2f, calloutFill)
        canvas.drawPath(calloutPath, calloutFill)
        // The device's icon on a white disc, then its name in white.
        val ix = left + pad + icon
        val iy = top + h / 2f
        calloutFill.color = 0xFFFFFFFF.toInt()
        calloutFill.alpha = alpha
        canvas.drawCircle(ix, iy, icon * 1.25f, calloutFill)
        n.device?.let {
            // A layer only while fading in: the icon draws with paints of its own, the layer fades them all at once.
            val fading = alpha < 255
            if (fading) canvas.saveLayerAlpha(ix - icon * 1.3f, iy - icon * 1.3f, ix + icon * 1.3f, iy + icon * 1.3f, alpha)
            toolIcons.device(canvas, it, ix, iy, icon * 0.8f)
            if (fading) canvas.restore()
        }
        calloutText.alpha = alpha
        canvas.drawText(text, ix + icon * 1.25f + 8f * density, iy + calloutText.textSize * 0.36f, calloutText)
        return true
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
        when (world.wifiUpgradeError(ap)) {
            null -> {
                if (!world.upgradeTo5Ghz(ap)) return
                haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                showHint(context.getString(R.string.hint_5ghz, texts.node(ap)), subject = ap)
            }
            WifiUpgradeError.NOT_AN_ACCESS_POINT -> Unit
            // Already done is a fact, not a refused action: no buzz, as for a server at the top tier.
            WifiUpgradeError.ALREADY_5_GHZ -> showHint(context.getString(R.string.wifi_error_already_5ghz), subject = ap)
            WifiUpgradeError.NO_BUDGET -> refuse(context.getString(R.string.wifi_error_no_budget, Wifi.UPGRADE_5_GHZ_COST), noBudget = true, subject = ap)
        }
    }

    /**
     * An info hint for [seconds]; [subject] is what it is about (see [postHint]). A [direct] hint answers the latest tap
     * and asks for a second one (a price or refund preview, a picked cable): it shows at once, even over an error.
     */
    private fun showHint(text: String, seconds: Float = HINT_SECONDS, subject: Any? = null, direct: Boolean = false) =
        postHint(text, seconds, false, subject, direct)

    /**
     * Shows [text] now, or lets an info hint wait behind a showing error: an error is not cut short by an unrelated
     * hint. The newest error shows at once (its buzz and its reason belong together), as does a [direct] hint and a hint
     * about the error's own [subject] (the retried server, cable or tool), as the error's news is stale then; the same
     * error again only renews its time. Info hints replace info hints, waiting ones too.
     */
    private fun postHint(text: String, seconds: Float, error: Boolean, subject: Any?, direct: Boolean = false) {
        val errorShown = hint != null && hintError && animTime < hintUntil
        if (errorShown && text == hint) {
            if (error) hintUntil = maxOf(hintUntil, animTime + maxOf(seconds, ERROR_HINT_SECONDS))
            return
        }
        // A coaching tip on screen holds back only the queued news (subject-less); an answer to a tap cuts it short, as
        // does an error.
        val heldBack = errorShown || (hintCoach != null && subject == null && hint != null && animTime < hintUntil)
        if (!heldBack || error || direct || (subject != null && subject === hintSubject)) {
            if (!error) pendingHints.clear()
            displayHint(text, seconds, error, subject)
            return
        }
        pendingHints.clear()
        pendingHints.addLast(PendingHint(text, seconds, subject))
    }

    private fun displayHint(text: String, seconds: Float, error: Boolean, subject: Any?) {
        retireCoach()
        hint = text
        hintError = error
        hintSubject = subject
        hintUntil = animTime + if (error) maxOf(seconds, ERROR_HINT_SECONDS) else seconds
        hintLong = seconds >= LONG_HINT_SECONDS
    }

    private fun clearHints() {
        // A tip not yet read comes back the next time it is due.
        hintCoach = null
        coachWaiting = null
        hint = null
        hintError = false
        hintSubject = null
        pendingHints.clear()
    }

    /**
     * An action the game refused: says why ([text]) with the error buzz, and for lack of budget ([noBudget]) flashes
     * the HUD's budget line. No refusal stays silent.
     */
    private fun refuse(text: String, seconds: Float = HINT_SECONDS, noBudget: Boolean = false, subject: Any? = null) {
        postHint(text, seconds, true, subject)
        if (noBudget) budgetFlashAt = animTime
        haptic(Haptics.ERROR)
    }

    private fun onButton(id: String) {
        click()
        when {
            id == "menu" -> openPauseMenu()
            id == "help" -> openLegend(from = Screen.PLAYING)
            id == "pause" -> {
                userPaused = !userPaused
                // Found the pause: the tip about it has nothing more to say (where tips run at all).
                if (userPaused && tutorial == null && world.daily == null && Coaching.PAUSE.appliesTo(world)) learned(Coaching.PAUSE)
            }
            id == "compass" -> renderer.camera.rotateTo(0f).also { viewTouchedAt = animTime }
            // Rotate and tilt buttons: animated steps around the centre of the view (stepping the camera renews the
            // limits and, while tilting, keeps the area framed).
            id == "rotate:left" -> renderer.camera.rotateStep(-1).also { viewTouchedAt = animTime }
            id == "rotate:right" -> renderer.camera.rotateStep(1).also { viewTouchedAt = animTime }
            id == "tilt:low" -> renderer.camera.tiltStep(-1).also { viewTouchedAt = animTime }
            id == "tilt:high" -> renderer.camera.tiltStep(1).also { viewTouchedAt = animTime }
            id == "router" -> togglePlacing(NodeKind.ROUTER, if (world.unlimited) Int.MAX_VALUE else world.routersAvailable)
            id.startsWith("radio:") -> RadioType.valueOf(id.removePrefix("radio:")).let {
                togglePlacing(it.kind, if (world.unlimited) Int.MAX_VALUE else world.radiosAvailable(it))
            }
            id.startsWith("cable:") -> pickCable(CableType.valueOf(id.removePrefix("cable:")))
            id.startsWith("offscreen:") -> world.nodes.firstOrNull { it.id.toString() == id.removePrefix("offscreen:") }?.let {
                renderer.focusOn(it, zoom = 1f)
                viewTouchedAt = animTime
            }
        }
    }

    /** Arms placing [kind] if [stock] allows it; pressing the same button again disarms it. An empty stock says so. */
    private fun togglePlacing(kind: NodeKind, stock: Int) {
        if (stock <= 0 && placing != kind) refuse(placeErrorText(kind, PlaceError.NO_STOCK), subject = kind)
        placing = if (placing == kind || stock <= 0) null else kind
    }

    /** Picks a cable technology and names its bandwidth, speed and price, and a service it is too narrow for. */
    private fun pickCable(t: CableType) {
        cableType = t
        if (tutorial != null) return
        val ms = java.text.NumberFormat.getNumberInstance(resources.configuration.locales[0]).format(t.msPerCell.toDouble())
        val info = if (world.unlimited) context.getString(R.string.hint_cable_info_free, texts.cable(t), t.capacity, ms)
        else context.getString(R.string.hint_cable_info, texts.cable(t), t.capacity, ms, t.costPerCell)
        val narrow = world.availableServices.filter { it.bandwidth > t.capacity }.sortedBy { it.bandwidth }.firstOrNull()
        showHint(
            if (narrow == null) info else context.getString(R.string.hint_two_parts, info, context.getString(R.string.hint_cable_too_narrow, texts.service(narrow))),
            direct = true,
        )
    }

    // ---------------------------------------------------------------- menus (game thread)

    private fun menuPage(): MenuPage? = when (screen) {
        Screen.PLAYING, Screen.SCENERIES -> null
        Screen.MAIN_MENU -> MenuPage(
            title = context.getString(R.string.app_name),
            lines = listOf(context.getString(R.string.menu_tagline)),
            items = listOf(
                MenuItem.Button(MenuAction.PLAY, context.getString(R.string.menu_play), primary = true),
            ) + listOfNotNull(
                // Only offered when there is something to continue: a greyed-out pill looked broken (judge panel).
                if (gameInProgress || hasSave) MenuItem.Button(MenuAction.CONTINUE, context.getString(R.string.menu_continue)) else null,
                MenuItem.Button(MenuAction.DAILY, context.getString(R.string.menu_daily)),
                MenuItem.Button(MenuAction.ACHIEVEMENTS, context.getString(R.string.menu_achievements)),
                if (gameServices.available) MenuItem.Button(MenuAction.LEADERBOARDS, context.getString(R.string.menu_leaderboards)) else null,
                MenuItem.Button(MenuAction.SETTINGS, context.getString(R.string.menu_settings)),
                // A small text link under the buttons, not a third row of pills that crowds the card (judge panel).
                removeAdsLabel()?.let { MenuItem.Button(MenuAction.REMOVE_ADS, it, link = true) },
            ),
            footer = highscores.best(highscores.lastScenery).takeIf { it > 0 }?.let { context.getString(R.string.menu_best, it) },
            hero = true,
        )
        Screen.PAUSED -> MenuPage(
            title = context.getString(R.string.pause_title),
            lines = listOf(
                tutorial?.let { context.getString(R.string.tutorial_pause, it.number, Tutorial.STEPS) } ?: gameLabel(),
                context.getString(
                    R.string.pause_status,
                    context.getString(R.string.hud_date, world.year, world.week),
                    resources.getQuantityString(R.plurals.hud_delivered, world.delivered, world.delivered),
                ),
            ),
            items = listOf(
                MenuItem.Button(MenuAction.RESUME, context.getString(R.string.menu_resume), primary = true),
                MenuItem.Button(MenuAction.LEGEND, context.getString(R.string.menu_legend)),
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
                MenuItem.Button(MenuAction.APPEARANCE, context.getString(R.string.settings_appearance)),
                MenuItem.Button(MenuAction.TUTORIAL, context.getString(R.string.settings_tutorial)),
            ) + listOfNotNull(
                if (monetization.privacyOptionsRequired) MenuItem.Button(MenuAction.PRIVACY, context.getString(R.string.settings_privacy)) else null,
                MenuItem.Button(MenuAction.PRIVACY_POLICY, context.getString(R.string.settings_privacy_policy), link = true),
                if ((monetization as? PlayMonetization)?.reviewerAccessAvailable == true)
                    MenuItem.Button(MenuAction.REVIEW_ACCESS, context.getString(R.string.reviewer_access), link = true) else null,
                MenuItem.Button(MenuAction.BACK, context.getString(R.string.menu_back)),
            ),
            footer = context.getString(R.string.settings_language),
        )
        // The view options and the cosmetics (docs/TOP100.md C5) on a page of their own: with everything on one page
        // the card needed three columns on a 16:9 phone at 200 % text and cut most labels (A7).
        Screen.APPEARANCE -> MenuPage(
            title = context.getString(R.string.settings_appearance),
            items = listOf(
                MenuItem.Toggle(MenuAction.TOGGLE_OVERVIEW, context.getString(R.string.settings_overview), settings.overviewMode),
                MenuItem.Toggle(MenuAction.TOGGLE_FREE_ROTATION, context.getString(R.string.settings_free_rotation), settings.freeRotation),
                MenuItem.Toggle(MenuAction.TOGGLE_COLORBLIND, context.getString(R.string.settings_colorblind), settings.colorblind),
                MenuItem.Button(MenuAction.CABLE_SKIN, skinLabel()),
                MenuItem.Button(MenuAction.COLOR_THEME, themeLabel()),
                MenuItem.Button(MenuAction.BACK, context.getString(R.string.menu_back)),
            ),
            footer = context.getString(
                R.string.settings_cosmetics_unlocked,
                Cosmetics.skins(tracker.unlocked).size, CableSkin.entries.size,
                Cosmetics.themes(tracker.unlocked).size, ColorTheme.entries.size,
            ),
        )
        Screen.GAME_OVER -> MenuPage(
            title = context.getString(R.string.game_over_title),
            highlight = if (newBest) context.getString(R.string.game_over_new_best) else null,
            lines = listOfNotNull(
                lossReason(),
                when {
                    newBest -> null
                    world.assisted && world.daily == null -> context.getString(R.string.game_over_assisted, highscores.best(world.scenario.id))
                    world.daily != null -> context.getString(R.string.game_over_daily_best, progressStore.dailyBest(world.daily!!.day))
                    else -> context.getString(R.string.game_over_best, highscores.best(world.scenario.id))
                },
            ),
            picture = recapPicture(),
            alertFirstLine = lossReason() != null,
            tip = lossTipLine,
            // The packets delivered are the score: a hero number under the title, like on the share card.
            score = resources.getQuantityString(R.plurals.hud_delivered, world.delivered, world.delivered),
            items = listOfNotNull(
                MenuItem.Button(MenuAction.PLAY_AGAIN, context.getString(R.string.game_over_again), primary = true),
                secondChanceLabel()?.let { MenuItem.Button(MenuAction.SECOND_CHANCE, it) },
                if (tutorial == null) MenuItem.Button(MenuAction.SHARE, context.getString(R.string.game_over_share)) else null,
                MenuItem.Button(MenuAction.MAIN_MENU, context.getString(R.string.menu_main)),
            ),
        )
        Screen.DAILY -> dailyPage()
        Screen.ACHIEVEMENTS, Screen.LEGEND -> null
    }

    /** What the pause card calls the running game: its scenery, with the mode or the daily rule. */
    private fun gameLabel(): String {
        world.daily?.let { return context.getString(R.string.daily_pause, texts.rule(it.rule)) }
        return if (world.mode == GameMode.NORMAL) texts.scenario(world.scenario)
        else context.getString(R.string.pause_mode, texts.scenario(world.scenario), texts.mode(world.mode))
    }

    /**
     * The daily challenge's card (docs/TOP100.md C1): the streak, today's date (UTC) and scenery, the rule of the day,
     * the goal or today's best, and when the next challenge comes.
     */
    private fun dailyPage(): MenuPage {
        val now = wallClock()
        val c = DailyChallenge.at(now)
        val s = streak
        val current = s.currentOn(c.day)
        val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, resources.configuration.locales[0])
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(c.day * MILLIS_PER_DAY))
        val hours = ceil(((c.day + 1) * MILLIS_PER_DAY - now) / 3_600_000.0).toInt().coerceIn(1, 24)
        return MenuPage(
            title = context.getString(R.string.daily_title),
            highlight = if (s.best > 0) resources.getQuantityString(R.plurals.daily_streak, current, current, s.best)
            else context.getString(R.string.daily_no_streak),
            lines = listOf(
                context.getString(R.string.daily_scenery, date, texts.scenario(c.scenario)),
                context.getString(R.string.daily_rule, texts.rule(c.rule), texts.ruleDescription(c.rule)),
                if (s.counted(c.day)) context.getString(R.string.daily_done, progressStore.dailyBest(c.day))
                else resources.getQuantityString(R.plurals.daily_goal, DailyChallenge.STREAK_PACKETS, DailyChallenge.STREAK_PACKETS),
            ),
            // Today's map with the streak flame: the card shows what the player will play.
            picture = MenuPicture(context.getString(R.string.daily_scenery, date, texts.scenario(c.scenario)), dailyPreview.aspect) { cv, r ->
                dailyPreview.draw(cv, r, c, current)
            },
            items = listOf(
                MenuItem.Button(MenuAction.DAILY_START, context.getString(R.string.daily_start), primary = true),
                MenuItem.Button(MenuAction.BACK, context.getString(R.string.menu_back)),
            ),
            footer = resources.getQuantityString(R.plurals.daily_footer, hours, hours),
        )
    }

    /** "Kabel-Skin: Neon (2/5)": the active skin and how many of all are unlocked. */
    /** "Kabel: Kupfer": short, so the active skin stays readable at 200 % text; the footer counts the unlocked ones. */
    private fun skinLabel(): String = context.getString(R.string.settings_cable_skin, texts.skin(Cosmetic.skin))

    private fun themeLabel(): String = context.getString(R.string.settings_color_theme, texts.theme(Cosmetic.theme))

    /** The time-lapse of the network that just ended, once there is growth to show. */
    private fun recapPicture(): MenuPicture? {
        val frames = growth.frames
        if (frames.size < 2 || tutorial != null) return null
        val w = world
        return MenuPicture(context.getString(R.string.a11y_recap, frames.first().week, frames.last().week), recap.aspect(frames)) { c, r ->
            recap.draw(
                c, r, frames, animTime - gameOverAt, { x, y -> y in 0 until w.rows && x in 0 until w.cols && w.water[y][x] }, Cosmetic.paletteFor(w.scenario.id),
                seed = w.seed, failed = w.failedNode?.footprint?.get(0),
            )
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
            MenuAction.PLAY_AGAIN, MenuAction.RESTART -> when {
                tutorial != null -> startTutorial()
                // After UTC midnight "again" means the new day's challenge, never yesterday's once more.
                world.daily != null -> startDaily(world.daily!!.again(wallClock()))
                else -> newGame(world.scenario, world.mode)
            }
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
            MenuAction.APPEARANCE -> screen = Screen.APPEARANCE
            MenuAction.BACK -> screen = when (screen) {
                Screen.SETTINGS -> settingsReturn
                Screen.APPEARANCE -> Screen.SETTINGS
                else -> Screen.MAIN_MENU
            }
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
            MenuAction.PRIVACY_POLICY -> openPrivacyPolicy()
            MenuAction.REVIEW_ACCESS -> showReviewerAccessDialog()
            MenuAction.DAILY -> screen = Screen.DAILY
            MenuAction.DAILY_START -> startDaily(DailyChallenge.at(wallClock()))
            MenuAction.ACHIEVEMENTS -> {
                achievementsPanel.resetScroll()
                screen = Screen.ACHIEVEMENTS
            }
            MenuAction.LEGEND -> openLegend(from = Screen.PAUSED)
            MenuAction.CABLE_SKIN -> updateSettings(settings.copy(cableSkin = Cosmetics.next(Cosmetic.skin, Cosmetics.skins(tracker.unlocked))))
            MenuAction.COLOR_THEME -> updateSettings(settings.copy(colorTheme = Cosmetics.next(Cosmetic.theme, Cosmetics.themes(tracker.unlocked))))
            MenuAction.LEADERBOARDS -> gameServices.showLeaderboards()
            MenuAction.SHARE -> shareNetwork()
        }
    }

    /**
     * Opens the privacy policy in a browser. On the main thread, as every activity start; without a browser (none
     * installed, disabled, blocked by a work profile) nothing happens instead of the game thread crashing the app.
     */
    private fun openPrivacyPolicy() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        mainThread.post {
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Log.w("GameView", "no app to open the privacy policy", e)
            }
        }
    }

    private fun showReviewerAccessDialog() {
        val play = monetization as? PlayMonetization ?: return
        post {
            val input = EditText(context).apply { hint = context.getString(R.string.reviewer_access_hint); isSingleLine = true }
            AlertDialog.Builder(context)
                .setTitle(R.string.reviewer_access)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.reviewer_access_unlock) { _, _ ->
                    val unlocked = play.unlockReviewerAccess(input.text.toString())
                    Toast.makeText(context, if (unlocked) R.string.reviewer_access_enabled else R.string.reviewer_access_invalid, Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    // ---------------------------------------------------------------- Play Games, rating, sharing (game thread)

    /** Signed in to Play Games: every reached achievement once more, then the cloud save (docs/TOP100.md C2, C6). */
    private fun onSignedIn() {
        syncedAchievements.clear()
        syncAchievements()
        gameServices.loadProgress { inputs.add(Input.CloudLoaded(it)) }
    }

    /** Hands the achievements reached but not yet sent since the sign-in to Play Games (docs/TOP100.md C2). */
    private fun syncAchievements() {
        if (!gameServices.signedIn) return
        val pending = AchievementSync.pending(tracker.stats, syncedAchievements)
        if (pending.isNotEmpty()) syncedAchievements += gameServices.unlock(pending)
    }

    /** The progress on this device as a cloud save (docs/TOP100.md C6). */
    private fun localProgress(): CloudProgress = CloudProgress.of(
        stats = tracker.stats,
        best = highscores.all(),
        streak = streak,
        dailyDay = progressStore.dailyDay,
        dailyBest = progressStore.dailyDay?.let(progressStore::dailyBest) ?: 0,
        savedAt = wallClock(),
    )

    /**
     * The cloud save arrived (docs/TOP100.md C6): both sides are merged ([CloudProgress.merge], most progress per value,
     * the newer day for the streak) and the result goes into the local stores; if the cloud lacked something of this
     * device, the merged save is written back. A save of a newer app version is merged in but never overwritten.
     */
    private fun applyCloud(remote: CloudProgress?) {
        val local = localProgress()
        if (remote == null) {
            gameServices.saveProgress(local)
            return
        }
        val merged = CloudProgress.merge(local, remote)
        if (merged.stats != tracker.stats) {
            tracker.replace(merged.stats)
            progressStore.saveStats(merged.stats)
            // Cosmetics the merged stats unlock (docs/TOP100.md C5) take effect now, not after the next settings change.
            applySettings(settings)
            tiles = emptyList()
            tilesFor = null
        }
        highscores.restore(merged.best)
        if (merged.streak != streak) {
            streakCache = merged.streak
            progressStore.streak = merged.streak
        }
        progressStore.restoreDaily(merged.dailyDay, merged.dailyBest)
        syncAchievements()
        if (!remote.newerFormat && merged.copy(savedAt = 0) != remote.copy(savedAt = 0)) gameServices.saveProgress(merged.copy(savedAt = wallClock()))
    }

    /**
     * After a game over of a normal game or a daily challenge: its leaderboard score (C3), the cloud save (C6) and the
     * rating request (D1). [previousBest] is the best of the same board before this game.
     */
    private fun afterGameOver(previousBest: Int) {
        Leaderboards.forGameOver(world, wallClock())?.let(gameServices::submit)
        if (gameServices.signedIn) gameServices.saveProgress(localProgress())
        if (world.mode != GameMode.NORMAL) return
        val game = FinishedGame(score = world.delivered, previousBest = previousBest, weeks = world.weeksPlayed, continued = world.continued)
        val decision = ReviewPolicy.onGameOver(reviewStore.state, game, wallClock())
        reviewStore.state = decision.state
        if (decision.ask) reviewDue = true
    }

    /**
     * Draws the share card of the finished game here, writes it on GameIo and hands it to [onShare] on the UI thread
     * (docs/TOP100.md D2).
     */
    private fun shareNetwork() {
        val card = shareCard.render(world, animTime)
        val text = shareCard.message(world)
        val cache = context.cacheDir
        GameIo.execute {
            // A full disk or an unwritable cache only means no share sheet this time, never a crash.
            val file = try {
                ShareSheet.write(cache, card)
            } catch (e: IOException) {
                Log.w("GameView", "share card not written", e)
                null
            } finally {
                card.recycle()
            }
            if (file != null) mainThread.post { onShare?.invoke(file, text) }
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
        unlocked(tracker.secondChance())
        autosave()
        showHint(context.getString(R.string.hint_continued))
    }

    /** The extra-router pill on the week reward screen, once per week, while a video is ready (or ads are removed). */
    private fun bonusLabel(): String? {
        val offer = world.rewardOffer ?: return null
        return when {
            offer.bonusClaimed || tutorial != null || !world.extrasAllowed -> null
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
            Screen.APPEARANCE -> screen = Screen.SETTINGS
            Screen.SCENERIES, Screen.DAILY, Screen.ACHIEVEMENTS -> screen = Screen.MAIN_MENU
            Screen.LEGEND -> screen = legendReturn
            Screen.GAME_OVER -> onMenuAction(MenuAction.MAIN_MENU)
            Screen.MAIN_MENU -> mainThread.post { onExit?.invoke() }
        }
    }

    // ---------------------------------------------------------------- scenery picker (game thread)

    private fun sceneryUnlocked(s: Scenario) = Scenarios.isUnlocked(s, highscores::best, monetization::ownsScenery)

    // In the order of their years, so the picker reads as a timeline (judge panel: 2004 stood before 2001).
    private fun sceneryCards(): List<SceneryCard> = Scenarios.all.sortedBy { it.startYear }.map { s ->
        val unlocked = sceneryUnlocked(s)
        val unlock = s.unlock
        val endless = pickerMode == GameMode.ENDLESS
        val best = highscores.best(if (endless) endlessKey(s.id) else s.id)
        val status = when {
            unlocked -> listOf(
                when {
                    best <= 0 -> context.getString(R.string.scenery_not_played)
                    endless -> context.getString(R.string.menu_best_endless, best)
                    else -> context.getString(R.string.menu_best, best)
                },
            )
            unlock is Unlock.Score -> listOf(
                resources.getQuantityString(R.plurals.scenery_progress, unlock.packets, highscores.best(unlock.after).coerceAtMost(unlock.packets), unlock.packets),
                context.getString(R.string.scenery_progress_in, texts.scenario(Scenarios.byId(unlock.after)!!)),
            )
            // A price when the store knows one, so the card says what it costs instead of a vague hint.
            else -> listOf(
                context.getString(R.string.scenery_shop),
                monetization.price(Entitlements.sceneryProduct(s.id)) ?: context.getString(R.string.scenery_shop_pack),
            )
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
            buy = !unlocked && unlock !is Unlock.Score,
            tint = com.mininetworks.game.render.Cosmetic.paletteFor(s.id).boardShade,
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
        if (id == SceneryPicker.NEXT || id == SceneryPicker.PREV) {
            sceneryPicker.page(id)
            return
        }
        if (id == SceneryPicker.MODE) {
            pickerMode = GameMode.entries[(pickerMode.ordinal + 1) % GameMode.entries.size]
            sceneryHint = null
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

    private fun newGame(s: Scenario, mode: GameMode = pickerMode) {
        highscores.lastScenery = s.id
        startGame(World(s, seed = System.currentTimeMillis(), mode = mode), fresh = true)
    }

    /** Starts the daily challenge [c]: the same map for everyone that day, with the rule of the day (docs/TOP100.md C1). */
    private fun startDaily(c: DailyChallenge) {
        startGame(World(c.scenario, seed = c.seed, daily = c), fresh = true)
    }

    /** Best-score key of scenery [id] in endless mode, next to the normal ones in [HighscoreStore]. */
    private fun endlessKey(id: String) = "endless_$id"

    /** An endless game has no game over: its best score is kept whenever it is saved or replaced. */
    private fun recordEndlessBest() {
        // Never the first read of the store: [pause] runs on the UI thread (docs/TOP100.md A3). The picker or the main
        // menu has read it before any endless game starts; a game restored after the process died records on its next save.
        if (world.mode == GameMode.ENDLESS && gameInProgress && tutorial == null && highscoresLazy.isInitialized()) {
            highscores.submit(world.delivered, endlessKey(world.scenario.id))
        }
    }

    /** Achievements screen: the back pill on a tap; a vertical drag scrolls the grid. */
    private fun onAchievementsTouch(e: Input.Touch) {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                endDrag()
                pressedAchievement = achievementsPanel.hit(e.x, e.y)
                achievementDownY = e.y
                achievementLastY = e.y
                achievementScrolling = false
            }
            MotionEvent.ACTION_MOVE -> if (achievementsPanel.scrollable) {
                if (!achievementScrolling && kotlin.math.abs(e.y - achievementDownY) >= TAP_SLOP_DP * density) {
                    achievementScrolling = true
                    pressedAchievement = null
                }
                if (achievementScrolling) achievementsPanel.scrollBy(achievementLastY - e.y)
                achievementLastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                val id = pressedAchievement
                pressedAchievement = null
                if (achievementScrolling) {
                    achievementScrolling = false
                    return
                }
                if (id == AchievementsPanel.BACK && achievementsPanel.hit(e.x, e.y) == id) {
                    click()
                    screen = Screen.MAIN_MENU
                }
            }
            MotionEvent.ACTION_CANCEL -> pressedAchievement = null
        }
    }

    /** Legend screen: the back pill on a tap; a vertical drag scrolls the tiles. */
    private fun onLegendTouch(e: Input.Touch) {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                endDrag()
                pressedLegend = legendPanel.hit(e.x, e.y)
                legendDownY = e.y
                legendLastY = e.y
                legendScrolling = false
            }
            MotionEvent.ACTION_MOVE -> if (legendPanel.scrollable) {
                if (!legendScrolling && kotlin.math.abs(e.y - legendDownY) >= TAP_SLOP_DP * density) {
                    legendScrolling = true
                    pressedLegend = null
                }
                if (legendScrolling) legendPanel.scrollBy(legendLastY - e.y)
                legendLastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                val id = pressedLegend
                pressedLegend = null
                if (legendScrolling) {
                    legendScrolling = false
                    return
                }
                if (id == LegendPanel.BACK && legendPanel.hit(e.x, e.y) == id) {
                    click()
                    screen = legendReturn
                }
            }
            MotionEvent.ACTION_CANCEL -> pressedLegend = null
        }
    }

    /**
     * The legend's content: first which device needs which server (the requests it sends and the servers that answer
     * them), then every server type with its service's bandwidth and ping and the data center as the top tier of any
     * of them, the signs on the map and the network's parts. Everything is drawn as on the map.
     */
    private fun legendSections(): List<LegendSection> {
        val numbers = java.text.NumberFormat.getNumberInstance(resources.configuration.locales[0])
        val locale = resources.configuration.locales[0]
        val devices = Device.entries.map { d ->
            LegendEntry(
                "device:${d.name}", LegendIcon.OfDevice(d), texts.device(d), "",
                services = d.services, labels = d.services.map { texts.server(it) },
                servicesLabel = context.getString(
                    R.string.legend_device_servers, d.services.joinToString(context.getString(R.string.list_separator)) { texts.server(it) },
                ),
            )
        }
        val servers = Service.entries.map { s ->
            LegendEntry(
                "service:${s.name}", LegendIcon.Server(s), texts.server(s),
                s.maxPingMs?.let { context.getString(R.string.legend_service_ping, s.bandwidth, it) }
                    ?: context.getString(R.string.legend_service_any, s.bandwidth),
                services = listOf(s), labels = listOf(texts.service(s)), servicesLabel = texts.service(s),
            )
        } + LegendEntry(
            "data_center", LegendIcon.DataCenter(Service.MAIL), texts.dataCenter().replaceFirstChar { it.titlecase(locale) },
            context.getString(R.string.legend_data_center_desc),
        )
        val network = listOf(
            LegendEntry("server", LegendIcon.Server(Service.MAIL), context.getString(R.string.legend_server_title), context.getString(R.string.legend_server_desc)),
            LegendEntry("router", LegendIcon.Router, context.getString(R.string.node_router), context.getString(R.string.legend_router_desc)),
            LegendEntry("ports", LegendIcon.Ports, context.getString(R.string.legend_ports_title), context.getString(R.string.legend_ports_desc)),
        ) + CableType.entries.map { t ->
            LegendEntry(
                "cable:${t.name}", LegendIcon.Cable(t), texts.cable(t),
                context.getString(R.string.legend_cable_desc, t.capacity, numbers.format(t.msPerCell.toDouble()), t.costPerCell),
            )
        } + listOf(
            LegendEntry("access_point", LegendIcon.AccessPoint, context.getString(R.string.node_access_point), context.getString(R.string.reward_access_point_desc)),
            LegendEntry("cell_tower", LegendIcon.CellTower, context.getString(R.string.node_cell_tower), context.getString(R.string.legend_cell_tower_desc)),
            LegendEntry("cable_edit", LegendIcon.Cable(CableType.FIBER), context.getString(R.string.legend_cable_edit_title), context.getString(R.string.legend_cable_edit_desc)),
        )
        val signs = listOf(
            LegendEntry("request", LegendIcon.Request(Service.MAIL), context.getString(R.string.legend_request_title), context.getString(R.string.legend_request_desc)),
            LegendEntry("response", LegendIcon.Response(Service.MAIL), context.getString(R.string.legend_response_title), context.getString(R.string.legend_response_desc)),
            LegendEntry("overload", LegendIcon.Overload, context.getString(R.string.legend_overload_title), context.getString(R.string.legend_overload_desc)),
            LegendEntry("too_narrow", LegendIcon.Problem(RouteProblem.TOO_NARROW), context.getString(R.string.legend_too_narrow_title), context.getString(R.string.legend_too_narrow_desc)),
            LegendEntry("ping", LegendIcon.Problem(RouteProblem.PING_TOO_HIGH), context.getString(R.string.legend_ping_title), context.getString(R.string.legend_ping_desc)),
            LegendEntry("gauge", LegendIcon.Gauge, context.getString(R.string.legend_gauge_title), context.getString(R.string.legend_gauge_desc)),
            LegendEntry("jam", LegendIcon.Jam, context.getString(R.string.legend_jam_title), context.getString(R.string.legend_jam_desc)),
            LegendEntry("missing_server", LegendIcon.MissingServer(Service.MAIL), context.getString(R.string.legend_missing_title), context.getString(R.string.legend_missing_desc)),
            LegendEntry("incident", LegendIcon.Incident, context.getString(R.string.legend_incident_title), context.getString(R.string.legend_incident_desc)),
        )
        return listOf(
            LegendSection("devices", context.getString(R.string.legend_section_devices), devices),
            LegendSection("servers", context.getString(R.string.legend_section_services), servers),
            LegendSection("signs", context.getString(R.string.legend_section_signs), signs),
            LegendSection("network", context.getString(R.string.legend_section_network), network),
        )
    }

    /**
     * The legend "What's what?", from the pause menu or straight from the HUD's "?" button; there it stops the game
     * like the pause menu does (the clock only runs on the map), and its back pill leads back to the map.
     */
    private fun openLegend(from: Screen) {
        if (from == Screen.PLAYING) openPauseMenu()
        legendReturn = from
        legendPanel.resetScroll()
        screen = Screen.LEGEND
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
            // The view controls stay hidden until the map is moved or zoomed.
            viewTouchedAt = Float.NEGATIVE_INFINITY
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

    /**
     * Shows [w] as the running game. A [fresh] game (not a save) counts for achievements from its start and says what
     * its mode or daily rule changes.
     */
    private fun startGame(w: World, fresh: Boolean = false) {
        screen = Screen.PLAYING
        showWorld(w)
        gameInProgress = true
        viewTouchedAt = Float.NEGATIVE_INFINITY
        cableType = w.unlockedCables.first()
        dailyExpiredHinted = false
        if (fresh) {
            unlocked(tracker.begin(w, fresh = true))
            w.daily?.let { hintQueue += context.getString(R.string.daily_rule, texts.rule(it.rule), texts.ruleDescription(it.rule)) }
            texts.modeDescription(w.mode)?.let { hintQueue += it }
        } else {
            tracker.begin(w, fresh = false)
        }
    }

    /** Starts the tutorial from its first step; it is not a game in progress, so it is neither saved nor scored. */
    private fun startTutorial() {
        val t = Tutorial.start()
        screen = Screen.PLAYING
        showWorld(t.world, t)
        cableType = t.world.unlockedCables.first()
    }

    private fun showWorld(w: World, withTutorial: Tutorial? = null) {
        recordEndlessBest()
        saveStats()
        world = w
        jamHintShown = false
        // A new map starts facing north, at the classic pitch.
        renderers.forEach { it.camera.resetRotation(); it.camera.resetTilt() }
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
        portsTipShown = false
        routerPulseAt = Float.NEGATIVE_INFINITY
        budgetFlashAt = Float.NEGATIVE_INFINITY
        hintQueue.clear()
        clearHints()
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
        // A daily challenge keeps its own best of the day; it never counts towards unlocking sceneries. An assisted run
        // (continued, bonus router) sets no best: bests unlock sceneries and must be reached by the same rules for all.
        val daily = world.daily
        val previousBest = when {
            daily == null -> highscores.best(world.scenario.id)
            else -> progressStore.dailyBest(daily.day)
        }
        newBest = when {
            world.assisted -> false
            daily == null -> highscores.submit(world.delivered, world.scenario.id)
            // A run finished after its UTC day no longer counts for that day's best (docs/TOP100.md C1).
            daily.isToday(wallClock()) -> progressStore.submitDaily(daily.day, world.delivered)
            else -> false
        }
        unlocked(tracker.gameOver(world))
        afterGameOver(previousBest)
        saves.clearLater()
        hasSave = false
        endDrag()
        placing = null
        selection = null
        userPaused = false
        // The game is over: pending hints (e.g. what the week's news need) would only peek out under the result card.
        clearHints()
        hintQueue.clear()
        val failed = world.failedNode
        if (failed == null) {
            showGameOverCard()
            return
        }
        renderer.focusOn(failed)
        failCallout = context.getString(R.string.game_over_callout, texts.node(failed))
        calloutBelow = null
        calloutFitWidth = -1f
        failFocusUntil = animTime + GAME_OVER_FOCUS_SECONDS
        focusSkipArmed = false
    }

    /**
     * Switches from the camera's glide to the result card. The rating request comes with the card, not over the glide
     * (D1), and only in the foreground: from [pause] ([askReview] false) the request stays due until the card is drawn
     * again after the return ([askReviewIfDue] in [update]), since Play cannot show its dialog to an app going away and
     * the policy has already counted the request.
     */
    private fun showGameOverCard(askReview: Boolean = true) {
        failFocusUntil = null
        gameOverAt = animTime
        screen = Screen.GAME_OVER
        if (askReview) askReviewIfDue()
    }

    private fun askReviewIfDue() {
        if (reviewDue && screen == Screen.GAME_OVER) {
            reviewDue = false
            reviewPrompt.request()
        }
    }

    /**
     * Saves the running game, if there is one: a snapshot now, the writing on the I/O thread. A write that fails (disk
     * full) shows up as a save that does not load, which "Continue" then drops.
     */
    private fun autosave() {
        saveStats()
        recordEndlessBest()
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
        // Cosmetics (docs/TOP100.md C5): only what an achievement unlocked; anything else falls back to the default.
        val unlocked = tracker.unlocked
        Cosmetic.skin = s.cableSkin.takeIf { it in Cosmetics.skins(unlocked) } ?: CableSkin.CLASSIC
        Cosmetic.theme = s.colorTheme.takeIf { it in Cosmetics.themes(unlocked) } ?: ColorTheme.MEADOW
        val next = if (s.overviewMode) flat else iso
        if (next !== renderer) {
            // The other style takes over the angle, so switching styles never turns the map.
            val turn = Camera.shortestTurn(next.camera.angle, renderer.camera.angle)
            next.rotateBy(turn, next.camera.centerX, next.camera.centerY, world)
            renderer = next
        }
        // Free rotation switched on keeps a turned map as it is; switched off, it snaps to the nearest multiple of 45°.
        if (!s.freeRotation) renderers.forEach { it.camera.settleRotation(snap = true) }
    }

    private val vibrator by lazy { Haptics(context) }

    /** One haptic pulse of [kind] if haptics are on; [alarm] marks a pulse of the overload alarm. */
    private fun haptic(kind: Int, alarm: Boolean = false) {
        if (!settings.haptics) return
        hapticPulses++
        if (kind == Haptics.ERROR) errorPulses++
        if (alarm) alarmPulses++
        post { vibrator.pulse(kind) }
    }

    /** The system click sound for buttons, if sound is on. */
    private fun click() {
        if (settings.sound) post { playSoundEffect(SoundEffectConstants.CLICK) }
    }

    /**
     * The toolbar may have grown since the last [layoutRenderers] (a new cable type, a wider row that wraps): a style
     * that follows the area keeps its framing clear of the toolbar as it is now; a view the player moved stays put.
     */
    private fun refreshToolbarInset(): Boolean {
        if (surfaceWidth <= 0 || surfaceHeight <= 0 || tutorial != null || hudHidden || screen != Screen.PLAYING) return false
        val bottom = toolbarReserve() + safeInsets.bottom
        var changed = false
        for (r in renderers) {
            val c = r.camera
            if (!c.followsArea || kotlin.math.abs(c.insets.bottom - bottom) < 0.5f) continue
            // The picture stays where it is; the refit that follows glides to the new framing.
            c.setInsetsKeepingView(c.insets.copy(bottom = bottom))
            changed = true
        }
        return changed
    }

    /** What decides the toolbar's layout height: the cable chips and the network tiles it shows. */
    private fun toolbarKey(): Int {
        // Counted on the cached entries, without the list [World.unlockedCables] builds: this runs every tick.
        val types = CableType.entries
        var cables = 0
        for (i in types.indices) if (world.invented(types[i])) cables++
        val kinds = RadioType.entries
        var radios = 0
        for (i in kinds.indices) if (world.unlimited || world.radiosAvailable(kinds[i]) > 0) radios++
        return cables * 16 + radios
    }

    /**
     * Fits every style to the unlocked area, keeping the HUD rows (and the tutorial bubble) free; the demo town sits
     * beside the main menu card. Waits for the first surface size.
     */
    private fun layoutRenderers() {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        val toolbar = if (screen in MENU_SCREENS && !gameInProgress) 0f else toolbarReserve()
        val insets = if (screen in MENU_SCREENS && !gameInProgress) {
            ViewInsets(surfaceWidth * 0.55f, 24 * density, 16 * density, 24 * density)
        } else if (tutorial != null) {
            tutorialOverlay.place(safeInsets.left + 16 * density, tutorialTop(), tutorialBottom())
            ViewInsets(tutorialOverlay.reservedRight(surfaceWidth) - safeInsets.left + 8 * density, hudTopReserve, 8 * density, toolbar)
        } else if (hudHidden) {
            ViewInsets(8 * density, 8 * density, 8 * density, 8 * density)
        } else if (surfaceWidth > surfaceHeight) {
            // Landscape: the top HUD holds only two corner pills over the iso board's empty corners, so the map may
            // reach up between them (judge panel: the board filled only half of a phone's screen).
            ViewInsets(8 * density, hudTopReserve * LANDSCAPE_TOP_SHARE, 8 * density, toolbar)
        } else {
            ViewInsets(8 * density, hudTopReserve, 8 * density, toolbar)
        }
        val safe = safeInsets
        val inside = ViewInsets(insets.left + safe.left, insets.top + safe.top, insets.right + safe.right, insets.bottom + safe.bottom)
        renderers.forEach { it.layout(surfaceWidth, surfaceHeight, world, inside) }
        // The tutorial frames the few devices it talks about, a few cells around them, instead of the whole start
        // area: the first drag is large and central (judge panel: a tiny pair in the corner of an empty board).
        if (tutorial != null && world.nodes.isNotEmpty()) {
            // Centred on the devices (not clipped to the area, which would push them to its edge).
            val around = CellRect(
                world.nodes.minOf { it.cellX } - TUTORIAL_MARGIN, world.nodes.minOf { it.cellY } - TUTORIAL_MARGIN,
                world.nodes.maxOf { it.cellX } + 1 + TUTORIAL_MARGIN, world.nodes.maxOf { it.cellY } + 1 + TUTORIAL_MARGIN,
            )
            renderers.forEach { it.camera.fit(it.mapBounds(around), atLeast = it.readableScale) }
        }
        framedArea = world.unlocked
        framedNodes = world.nodes.size
        framedCables = world.cables.size
        framedToolbar = toolbarKey()
        growthHintPending = false
    }

    internal companion object {
        /** Share of the top HUD rows a landscape map keeps free (the pills sit in the corners). */
        const val LANDSCAPE_TOP_SHARE = 0.4f
        /** World-unit size (dp) above which an incident's countdown pin grows with the zoom, and its largest growth. */
        const val PIN_UNIT_DP = 36f
        const val PIN_MAX_SCALE = 1.8f
        /** Diameter of an off-screen chip, a full touch target; at most this many show, each trying this many slides. */
        const val OFFSCREEN_CHIP_DP = 48f
        const val MAX_OFFSCREEN_CHIPS = 4
        const val OFFSCREEN_SLIDES = 8
        /** The view's edge an off-screen chip sits on, for its TalkBack label ([Texts.offscreen]). */
        const val SIDE_LEFT = 0
        const val SIDE_TOP = 1
        const val SIDE_RIGHT = 2
        const val SIDE_BOTTOM = 3
        /** Cells of ground the tutorial's framing keeps around its devices. */
        const val TUTORIAL_MARGIN = 1
        /** The part of [insets] the HUD must keep clear of: the display cutout, in pixels. */
        fun safeInsetsOf(insets: WindowInsets): ViewInsets = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> insets.getInsets(WindowInsets.Type.displayCutout())
                .let { ViewInsets(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> insets.displayCutout?.let {
                ViewInsets(it.safeInsetLeft.toFloat(), it.safeInsetTop.toFloat(), it.safeInsetRight.toFloat(), it.safeInsetBottom.toFloat())
            } ?: ViewInsets.NONE
            else -> ViewInsets.NONE
        }

        /** Screens of the main menu, which show the demo town beside them. */
        val MENU_SCREENS = setOf(Screen.MAIN_MENU, Screen.SCENERIES, Screen.DAILY, Screen.ACHIEVEMENTS)
        const val MILLIS_PER_DAY = 86_400_000L
        const val PRIVACY_POLICY_URL = "https://robinrehbein.github.io/mini-networks/privacy/"
        /** Lower bound per loop iteration, in case posting a frame does not block on vsync. */
        const val MIN_FRAME_NANOS = 8_000_000L
        /** Longest animation step per frame, so animations do not jump after a stall. */
        const val MAX_ANIM_STEP = 0.05f
        /** Minimum distance between two drag trail samples, in cells. */
        const val TRAIL_SPACING = 0.2f
        const val MAX_TRAIL = 256
        const val HINT_SECONDS = 2.5f
        /** An error hint stays at least this long: why an action was refused is worth reading. */
        const val ERROR_HINT_SECONDS = 4.5f
        /**
         * Lines a long hint (or one in large text) wraps into above the bottom buttons; one more for an explanation
         * shown for [LONG_HINT_SECONDS], so its advice is not cut at 200 % text.
         */
        const val MAX_HINT_LINES = 3
        /** Padding (dp) around the hint line's text inside its backdrop plate, and the plate's corner radius. */
        const val HINT_PAD_X_DP = 10f
        const val HINT_PAD_Y_DP = 5f
        /** The hint plate's top and bottom padding where a full one would push the paused pill away. */
        const val HINT_PAD_TIGHT_DP = 1f
        const val HINT_RADIUS_DP = 12f
        /** The error hint's "!" disc, as a share of the hint's text size, and its gap to the text (dp). */
        const val HINT_MARK_SCALE = 1.15f
        const val HINT_MARK_GAP_DP = 6f
        /** Seconds the view controls stay after the map last moved, and how long they then take to fade out. */
        const val VIEW_CONTROLS_SECONDS = 4f
        const val VIEW_CONTROLS_FADE_SECONDS = 0.6f
        /** How long the camera shows the failed device before the game-over card. */
        const val GAME_OVER_FOCUS_SECONDS = 1.6f
        /** How long the failed device's callout takes to fade in at the start of the camera's glide. */
        const val CALLOUT_FADE_SECONDS = 0.35f
        const val CALLOUT_SHADOW = 0xFF000000.toInt()
        /** Least height of the callout; it is no touch target (any tap skips the glide). */
        const val CALLOUT_MIN_DP = 36f
        /** Longer hints: what a new service needs, why a device is stuck. */
        const val LONG_HINT_SECONDS = 4f
        /**
         * A coaching tip ([Coaching]) stays at least this long, and longer for a long text (seconds per character):
         * it explains a mechanic in two sentences, and its second one is the advice.
         */
        const val COACH_HINT_SECONDS = 8f
        const val COACH_SECONDS_PER_CHAR = 0.07f
        /** Lines a coaching tip may wrap into, so its advice is not cut at 200 % text. */
        const val COACH_HINT_LINES = MAX_HINT_LINES + 3
        /** A coaching tip's widest card, and its accent stripe with the gap after it. */
        const val COACH_MAX_W_DP = 440f
        /** The least gap between the date's plate and the counters' plate side by side. */
        const val HUD_PLATE_GAP_DP = 8f
        const val COACH_STRIPE_DP = 12f
        /** A coaching tip counts as read once it was on screen for this share of its time, even if a hint cut it short. */
        const val COACH_READ_SHARE = 0.6f
        /** The steps of the HUD's stock line ([HudTop.stockFit]): paint and lines, or none that fits. */
        private const val STOCK_NONE = 0
        private const val STOCK_FIRST_ONE = 1
        private const val STOCK_SMALL_ONE = 2
        private const val STOCK_FIRST_TWO = 3
        private const val STOCK_SMALL_TWO = 4
        /** Seconds between two looks for a due coaching tip. */
        const val COACH_CHECK_SECONDS = 0.25f
        /** How long the servers a tapped device needs stay lit, and how long the highlight takes to fade in and out. */
        const val FOCUS_TAP_SECONDS = 2.8f
        const val FOCUS_FADE_SECONDS = 0.25f
        /** Between the servers a device needs in a hint ("PC braucht: Mail-Server · Game-Server"), in every language. */
        const val SERVER_LIST_SEPARATOR = " · "
        /** Between the cable technologies a loss tip offers ("DSL / Koax / Glasfaser"). */
        const val CABLE_CHOICE_SEPARATOR = " / "
        /** How long a tapped cable, server or router stays selected for the confirming second tap. */
        const val SELECT_SECONDS = 3f
        /** Requests waiting at one server before the game points out that tapping upgrades it. */
        const val BUSY_HINT_WAITING = 2
        /** Running seconds after which a full-screen ad whose result never came counts as closed. */
        const val AD_TIMEOUT_SECONDS = 6f
        const val COIN_COLOR = 0xFFF5C542.toInt()
        /** Gap between the toolbar's chips and tiles, and between its rows. */
        const val TOOL_GAP_DP = 10f
        /** Least room between a cable chip's price badge and the "?" lifted above the cable row. */
        const val BADGE_CLEAR_DP = 8f
        /** Room on each side of the divider between the cable and the network group. */
        const val GROUP_GAP_DP = 12f
        /** Between a group caption's descent and its row; the caption starts [CAPTION_INSET_DP] in from the row. */
        const val CAPTION_GAP_DP = 5f
        const val CAPTION_INSET_DP = 12f
        /** The tray reaches this far above the highest caption or row. */
        const val TRAY_PAD_DP = 8f
        const val TRAY_CORNER_DP = 16f
        /** Radius of the round well behind a tile's device icon, and a tile's port dots and their spacing. */
        const val TILE_WELL_DP = 14f
        const val TILE_DOT_DP = 2.4f
        const val TILE_DOT_STEP_DP = 6.4f
        const val TILE_WELL = 0xFFEDF0EB.toInt()
        const val ACCESS_POINT_LED = 0xFF3BA55C.toInt()
        /** Full ports: the router tile's pulse and a red ghost cell; the map's alarm red. */
        const val PORTS_FULL_RED = 0xFFD7263D.toInt()
        /**
         * The budget line while it would not pay for a short cable ([budgetLow]): a dark amber, readable on the white
         * plate and apart from [PORTS_FULL_RED] of the refusal flash for red-green colour blindness too.
         */
        const val BUDGET_LOW_TINT = 0xFFB25A00.toInt()
        /** Thickness of the bar under a low budget ([budgetLow]), its cue beside the tint. */
        const val BUDGET_LOW_PILL_PAD_DP = 4f
        /** Cells of a short cable, the yardstick of [budgetLow]. */
        const val SHORT_CABLE_CELLS = 5
        /** Height of the week progress bar under the date. */
        const val WEEK_BAR_DP = 6f
        /** The budget and router count in a game without limits. */
        private const val UNLIMITED = "∞"
        const val ROUTER_PULSE_SECONDS = 1.6f
        /** How long the HUD's budget line flashes red after an action failed for lack of budget. */
        const val BUDGET_FLASH_SECONDS = 1.2f
        const val SELECTION_COLOR = 0xFFFFC21A.toInt()
        const val COMPASS_NORTH = 0xFFD7263D.toInt()
        /** Gap between the pills of the view controls, above each other or side by side, in dp. */
        const val CONTROL_GAP_DP = 8f
        /** Air between the column of view controls and the counters above it or the toolbar below it, in dp. */
        const val CONTROL_AIR_DP = 12f
        const val STATE_IN_GAME = "mininetworks.inGame"
        const val STATE_IN_TUTORIAL = "mininetworks.inTutorial"
        /** A finger that moves less than this is a tap. */
        const val TAP_SLOP_DP = 12f
        const val DOUBLE_TAP_MS = 300L
        const val DOUBLE_TAP_SLOP_DP = 40f
        /** Holding a tap on an access point this long switches it to 5 GHz; on a cable, it grabs the cable's nearer end. */
        const val LONG_PRESS_MS = 500L
        /** Touch radius of a selected cable's grab handles: a 64 dp target, easy to hit beside the node it belongs to. */
        const val HANDLE_TOUCH_DP = 32f
        /** A tap this far outside a handle's drawn disc still counts as on the handle (and does not remove the cable). */
        private const val HANDLE_TAP_MARGIN_DP = 8f
        /** Handles sit this far from their node's center on screen (at least [HANDLE_GAP_CELLS]), clear of its icon. */
        private const val HANDLE_GAP_DP = 30f
        private const val HANDLE_GAP_CELLS = 0.7f
        /** The hold ring appears only after this, so a quick tap does not flash it. */
        private const val HOLD_RING_DELAY_MS = 120f
        private const val HOLD_RING_DP = 40f
        private const val HOLD_RING_COLOR = 0xFF4F6BD8.toInt()
    }
}
