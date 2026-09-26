package com.mininetworks.game.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.mininetworks.game.R
import com.mininetworks.game.data.GameSettings
import com.mininetworks.game.data.HighscoreStore
import com.mininetworks.game.data.SaveStore
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.Bend
import com.mininetworks.game.game.CableLayout
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.Cell
import com.mininetworks.game.game.FixedStep
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.RadioType
import com.mininetworks.game.game.ServerUpgradeError
import com.mininetworks.game.game.Vec2
import com.mininetworks.game.game.Wifi
import com.mininetworks.game.game.WifiUpgradeError
import com.mininetworks.game.game.World
import com.mininetworks.game.render.CableStyles
import com.mininetworks.game.render.DragPreview
import com.mininetworks.game.render.FlatRenderer
import com.mininetworks.game.render.IsoRenderer
import com.mininetworks.game.render.Renderer
import com.mininetworks.game.render.ServiceColors
import com.mininetworks.game.render.TouchTargets
import com.mininetworks.game.render.TwoFingerGesture
import com.mininetworks.game.render.ViewInsets
import com.mininetworks.game.render.fill
import com.mininetworks.game.ui.menu.DemoCity
import com.mininetworks.game.ui.menu.MenuAction
import com.mininetworks.game.ui.menu.MenuItem
import com.mininetworks.game.ui.menu.MenuPage
import com.mininetworks.game.ui.menu.MenuPanel
import com.mininetworks.game.ui.menu.Screen
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Hosts the game loop, the HUD, the menus and touch input on a [SurfaceView].
 *
 * A dedicated game thread owns all game state ([World], renderers, HUD and menu state). Each frame it drains the input
 * queue, advances the simulation in fixed 1/60 s steps ([FixedStep], at most 5 per frame) and draws to the surface.
 * The UI thread only enqueues [Input]s, so the world is never touched concurrently.
 *
 * Screens ([Screen]): the app opens on the main menu (play, continue the autosave, settings) over a demo town.
 * The simulation only runs while [Screen.PLAYING]. The game is saved ([SaveStore]) when the pause menu opens, when the
 * player leaves to the main menu and when the activity pauses; game over records the best score ([HighscoreStore])
 * and deletes the save.
 *
 * Controls:
 *  - drag from a node to another node: lay a cable along the grid (L-shaped; the drag path picks which way it bends)
 *  - drag on empty ground or with two fingers: pan; pinch: zoom; double tap on empty ground: fit the playable area
 *  - pick a cable technology in the bottom-left bar (ISDN, DSL, Kabel, Glasfaser)
 *  - tap a server: upgrade its hardware (more throughput, taller stack; tier 4 is a data center on 2×2 cells)
 *  - tap a cable: upgrade it to the picked technology, or remove it if it already is that type
 *  - "Router" button, then tap an empty cell: place a router; "WLAN" and "Mast" place won radios the same way
 *  - tap an access point: next WLAN channel; hold it: switch it to 5 GHz (costs budget)
 *  - "Pause" button or back: pause menu (resume, settings, restart, main menu)
 *  - settings: sound, haptics, overview mode (flat instead of isometric), colorblind palette
 *  - at each week change the world pauses and [RewardDialog] shows two reward cards; tap one to pick it
 *    ("Pause" stays tappable above the dialog; resuming returns to the choice)
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
    private val saveStore = SaveStore(context.filesDir)
    private val settingsStore = SettingsStore(context)
    private val highscores = HighscoreStore(context)
    private var settings = GameSettings()
    private var hasSave = saveStore.exists

    /** Called on the UI thread when back is pressed on the main menu. */
    var onExit: (() -> Unit)? = null
    private val mainThread = Handler(Looper.getMainLooper())

    private var screen = Screen.MAIN_MENU
    /** Where the settings screen returns to. */
    private var settingsReturn = Screen.MAIN_MENU
    /** True while [world] is a real game that is not over (not the demo town). */
    private var gameInProgress = false
    private var newBest = false
    private val menuPanel = MenuPanel(context)
    private var pressedAction: MenuAction? = null

    private var world = DemoCity.build()
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
    private var growthHintPending = false

    private val density = resources.displayMetrics.density
    private val hudText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF262B33.toInt(); typeface = Typeface.DEFAULT_BOLD; textSize = 16 * density }
    private val hudSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5B6674.toInt(); textSize = 13 * density }
    private val btnFill = fill(0xE6FFFFFF.toInt())
    private val btnActive = fill(0xFF262B33.toInt())
    private val holdRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val btnText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = 14 * density }
    private val barBg = fill(0x33262B33)
    private val swatch = fill(0)
    private val barFg = fill(0xFF262B33.toInt())
    private val bigText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; color = 0xFF262B33.toInt() }

    private data class Button(val id: String, val rect: RectF)
    private val buttons = mutableListOf<Button>()

    init {
        holder.addCallback(this)
        applySettings(settingsStore.load())
    }

    // ---------------------------------------------------------------- lifecycle (UI thread)

    /** Starts the game thread; call from `Activity.onResume`. */
    fun resume() {
        if (loop != null) return
        running = true
        loop = thread(name = "GameLoop") { runLoop() }
    }

    /** Stops the game thread, opens the pause menu and saves the game; call from `Activity.onPause`. */
    fun pause() {
        loop?.let { t ->
            running = false
            surfaceLock.withLock { surfaceAvailable.signalAll() }
            joinQuietly(t)
            loop = null
        }
        // Safe: the game thread has ended.
        if (screen == Screen.PLAYING) {
            endDrag()
            screen = Screen.PAUSED
        }
        autosave()
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
        while (true) handle(inputs.poll() ?: break)
        val animStep = frameSeconds.coerceAtMost(MAX_ANIM_STEP)
        animTime += animStep
        checkHold()
        if (screen == Screen.PLAYING) clock.advance(frameSeconds) { world.update(it) } else clock.reset()
        checkGameOver()
        followArea()
        renderer.camera.step(animStep)
    }

    /** When the map grew: widen every style's zoom range, follow the new area, and say so once the reward is picked. */
    private fun followArea() {
        if (world.unlocked != framedArea) {
            framedArea = world.unlocked
            renderers.forEach { it.onAreaChanged(world) }
            growthHintPending = true
        }
        if (growthHintPending && world.rewardOffer == null) {
            growthHintPending = false
            hint = context.getString(R.string.hint_map_grew)
            hintUntil = animTime + HINT_SECONDS
        }
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
        if (playing) drawHoldProgress(canvas)
        if (hudVisible) drawHud(canvas)
        if (playing) world.rewardOffer?.let {
            rewardDialog.draw(canvas, world, it, surfaceWidth, surfaceHeight, animTime, pressedCard)
            // Pause stays reachable during the reward choice, so it is drawn above the dimmed map.
            buttons.firstOrNull { b -> b.id == "pause" }?.let { b -> drawHudButton(canvas, b.rect, context.getString(R.string.button_pause), active = false) }
        }
        menuPage()?.let { menuPanel.draw(canvas, it, surfaceWidth, surfaceHeight, pressedAction) }
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
            Screen.MAIN_MENU -> false
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
        if (world !== snapshotWorld) {
            world = snapshotWorld
            gameInProgress = screen == Screen.PLAYING
        }
        if (screen != null) this.screen = screen
        if (style != null) renderer = renderers.firstOrNull { it.name == style } ?: throw IllegalArgumentException("unknown style $style")
        animTime = time
        handle(Input.Resize(width, height))
        checkGameOver()
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
    internal val currentScreen: Screen get() = screen

    /** The world shown right now, for tests. */
    internal val currentWorld: World get() = world

    /** Screen rectangle of an enabled menu entry in the last drawn frame, for tests. */
    internal fun menuTarget(action: MenuAction): RectF? = menuPanel.targetOf(action)

    /** Screen rectangle of the HUD button [id] ("pause", "router", "radio:…", "cable:…") in the last drawn frame, for tests. */
    internal fun hudTarget(id: String): RectF? = buttons.firstOrNull { it.id == id }?.rect

    /** Number of haptic pulses sent (only counted while haptics are on), for tests. */
    internal var hapticPulses = 0
        private set

    /**
     * Feeds one touch event straight to the input handling, for tests; [pointers] as in [Input.Touch].
     * Only valid while the game thread is not running.
     */
    internal fun injectTouch(action: Int, x: Float, y: Float, pointers: FloatArray = floatArrayOf(x, y), time: Long = 0L) {
        check(loop == null) { "game loop is running" }
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
        return DragPreview(from = from, end = end, target = target, type = cableType, layout = layout, blocked = error != null, label = label)
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

    private fun endDrag() {
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

    private fun drawHud(canvas: Canvas) {
        val pad = 16 * density
        canvas.drawText(context.getString(R.string.hud_date, world.year, world.week), pad, pad + hudText.textSize, hudText)
        val barY = pad + hudText.textSize + 8 * density
        val barW = 110 * density
        canvas.drawRoundRect(pad, barY, pad + barW, barY + 4 * density, 2 * density, 2 * density, barBg)
        canvas.drawRoundRect(pad, barY, pad + barW * world.weekProgress, barY + 4 * density, 2 * density, 2 * density, barFg)

        val right = surfaceWidth - pad
        hudText.textAlign = Paint.Align.RIGHT
        canvas.drawText(resources.getQuantityString(R.plurals.hud_delivered, world.delivered, world.delivered), right, pad + hudText.textSize, hudText)
        hudText.textAlign = Paint.Align.LEFT
        hudSub.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            context.getString(R.string.hud_resources, world.budget, world.routersAvailable),
            right, pad + hudText.textSize + 20 * density, hudSub,
        )
        if (world.serverVouchers > 0) {
            canvas.drawText(
                resources.getQuantityString(R.plurals.hud_vouchers, world.serverVouchers, world.serverVouchers),
                right, pad + hudText.textSize + 40 * density, hudSub,
            )
        }
        hudSub.textAlign = Paint.Align.LEFT

        world.lastNews?.let {
            if (world.rewardOffer == null && world.time - world.lastNewsTime < 3.5f) {
                bigText.textSize = 15 * density
                canvas.drawText(texts.news(it, withYear = true), surfaceWidth / 2f, pad + hudText.textSize, bigText)
            }
        }

        buttons.clear()
        val bh = 44 * density
        val gap = 10 * density
        var x = surfaceWidth - pad
        val y = surfaceHeight - pad - bh
        for ((id, label) in listOf(
            "pause" to context.getString(R.string.button_pause),
            "router" to context.getString(R.string.button_router, world.routersAvailable),
        )) {
            val w = btnText.measureText(label) + 32 * density
            val r = RectF(x - w, y, x, y + bh)
            drawHudButton(canvas, r, label, active = id == "router" && placing == NodeKind.ROUTER)
            buttons += Button(id, r)
            x -= w + gap
        }
        // Radios in a second row above, once invented (or won): the bottom row is full on a phone.
        x = surfaceWidth - pad
        for (type in RadioType.entries.reversed()) {
            val stock = world.radiosAvailable(type)
            if (world.week < type.unlockWeek && stock == 0) continue
            val label = context.getString(if (type == RadioType.WLAN) R.string.button_access_point else R.string.button_cell_tower, stock)
            val w = btnText.measureText(label) + 32 * density
            val r = RectF(x - w, y - bh - gap, x, y - gap)
            drawHudButton(canvas, r, label, active = placing == type.kind)
            buttons += Button("radio:${type.name}", r)
            x -= w + gap
        }
        // Cable technology picker, bottom left. Only invented technologies are shown.
        var cx = pad
        for (t in world.unlockedCables) {
            val label = context.getString(R.string.button_cable, texts.cable(t), t.costPerCell)
            val w = btnText.measureText(label) + 28 * density
            val r = RectF(cx, y, cx + w, y + bh)
            val active = t == cableType
            canvas.drawRoundRect(r, bh / 2, bh / 2, if (active) btnActive else btnFill)
            val st = CableStyles.of(t)
            swatch.color = st.color
            canvas.drawCircle(r.left + 14 * density, r.centerY(), 5 * density, swatch)
            btnText.color = if (active) 0xFFFFFFFF.toInt() else 0xFF262B33.toInt()
            canvas.drawText(label, r.centerX() + 6 * density, r.centerY() + btnText.textSize * 0.35f, btnText)
            buttons += Button("cable:${t.name}", r)
            cx += w + gap
        }
        if (placing != null) {
            canvas.drawText(context.getString(R.string.hint_place_router), pad, y - 10 * density, hudSub)
        } else if (animTime < hintUntil) {
            hint?.let { canvas.drawText(it, pad, y - 10 * density, hudSub) }
        }
    }

    // ---------------------------------------------------------------- input (game thread)

    private fun handle(input: Input) {
        when (input) {
            is Input.Resize -> {
                surfaceWidth = input.width
                surfaceHeight = input.height
                layoutRenderers()
            }
            is Input.Touch -> onTouch(input)
            Input.Back -> onBack()
        }
    }

    private fun onTouch(e: Input.Touch) {
        if (screen != Screen.PLAYING) {
            onMenuTouch(e)
            return
        }
        if (!gestureConsumed && world.rewardOffer != null && e.action == MotionEvent.ACTION_DOWN) {
            buttons.firstOrNull { it.id == "pause" && it.rect.contains(e.x, e.y) }?.let { onButton(it.id); return }
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
                val p = renderer.toWorld(e.x, e.y)
                placing?.let { kind ->
                    place(kind, floor(p.x).toInt(), floor(p.y).toInt())
                    placing = null
                    return
                }
                dragTrail.clear()
                dragFrom = pickNode(e.x, e.y)
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
                startPinch(e)
            }
            MotionEvent.ACTION_MOVE -> when {
                cameraGesture -> if (e.pointers.size >= 4) {
                    pinch.move(e.pointers[0], e.pointers[1], e.pointers[2], e.pointers[3], renderer.camera)
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
                    pinch.stop()
                    endDrag()
                    return
                }
                val from = dragFrom
                val isTap = hypot(e.x - downX, e.y - downY) < TAP_SLOP_DP * density
                if (from != null && isTap && from.kind == NodeKind.SERVER) {
                    upgradeServer(from)
                } else if (from != null && isTap && from.kind == NodeKind.ACCESS_POINT) {
                    if (e.time - downTime >= LONG_PRESS_MS) upgradeTo5Ghz(from) else cycleChannel(from)
                } else if (from != null && !isTap) {
                    trackDrag(e.x, e.y)
                    pickNode(e.x, e.y, except = from)?.let {
                        if (world.connect(from, it, cableType, dragBend(from.cell, it.cell))) haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                } else if (isTap && from == null && panArmed) {
                    val cable = renderer.cableAtScreen(world, e.x, e.y, TouchTargets.cableRadiusPx(renderer, density))
                    if (cable == null) {
                        emptyTapTime = e.time; emptyTapX = e.x; emptyTapY = e.y
                    } else if (cable.type == cableType) {
                        world.removeCable(cable)
                    } else {
                        world.upgrade(cable, cableType)
                    }
                }
                endDrag()
            }
            MotionEvent.ACTION_CANCEL -> {
                holdAp = null
                holdFired = false
                cameraGesture = false
                pinch.stop()
                endDrag()
            }
        }
    }

    /** (Re)starts the two-finger gesture from the fingers still down, or stops it if fewer than two remain. */
    private fun startPinch(e: Input.Touch) {
        val p = e.pointers
        if (p.size >= 4) pinch.start(p[0], p[1], p[2], p[3]) else pinch.stop()
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
                if (card != null && world.rewardOffer != null && rewardDialog.hit(e.x, e.y) == card) world.chooseReward(card)
                gestureConsumed = false
                pressedCard = null
            }
            MotionEvent.ACTION_CANCEL -> {
                gestureConsumed = false
                pressedCard = null
            }
        }
    }

    private fun upgradeServer(server: Node) {
        val error = world.serverUpgradeError(server)
        if (error == null) {
            world.upgradeServer(server)
            return
        }
        hint = when (error) {
            ServerUpgradeError.NOT_A_SERVER -> return
            ServerUpgradeError.MAX_LEVEL -> context.getString(R.string.server_error_max_level)
            ServerUpgradeError.NO_SPACE -> context.getString(R.string.server_error_no_space)
            ServerUpgradeError.NO_BUDGET ->
                context.getString(R.string.server_error_no_budget, World.Tuning.SERVER_UPGRADE_COST[server.level - 1])
        }
        hintUntil = animTime + HINT_SECONDS
    }

    /** Places a router or radio from stock; a new access point explains its controls. */
    private fun place(kind: NodeKind, cx: Int, cy: Int) {
        val radio = RadioType.of(kind)
        val placed = if (radio == null) world.placeRouter(cx, cy) else world.placeRadio(radio, cx, cy)
        if (placed?.kind == NodeKind.ACCESS_POINT) showHint(context.getString(R.string.hint_access_point, Wifi.UPGRADE_5_GHZ_COST))
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
        if (screen != Screen.PLAYING || world.rewardOffer != null || ap !in world.nodes) {
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
                    world.upgradeTo5Ghz(ap)
                    haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                    context.getString(R.string.hint_5ghz, texts.node(ap))
                }
                WifiUpgradeError.NOT_AN_ACCESS_POINT -> return
                WifiUpgradeError.ALREADY_5_GHZ -> context.getString(R.string.wifi_error_already_5ghz)
                WifiUpgradeError.NO_BUDGET -> context.getString(R.string.wifi_error_no_budget, Wifi.UPGRADE_5_GHZ_COST)
            },
        )
    }

    private fun showHint(text: String) {
        hint = text
        hintUntil = animTime + HINT_SECONDS
    }

    private fun onButton(id: String) {
        click()
        when {
            id == "pause" -> openPauseMenu()
            id == "router" -> togglePlacing(NodeKind.ROUTER, world.routersAvailable)
            id.startsWith("radio:") -> RadioType.valueOf(id.removePrefix("radio:")).let { togglePlacing(it.kind, world.radiosAvailable(it)) }
            id.startsWith("cable:") -> cableType = CableType.valueOf(id.removePrefix("cable:"))
        }
    }

    /** Arms placing [kind] if [stock] allows it; pressing the same button again disarms it. */
    private fun togglePlacing(kind: NodeKind, stock: Int) {
        placing = if (placing == kind || stock <= 0) null else kind
    }

    // ---------------------------------------------------------------- menus (game thread)

    private fun menuPage(): MenuPage? = when (screen) {
        Screen.PLAYING -> null
        Screen.MAIN_MENU -> MenuPage(
            title = context.getString(R.string.app_name),
            lines = listOf(context.getString(R.string.menu_tagline)),
            items = listOf(
                MenuItem.Button(MenuAction.PLAY, context.getString(R.string.menu_play), primary = true),
                MenuItem.Button(MenuAction.CONTINUE, context.getString(R.string.menu_continue), enabled = gameInProgress || hasSave),
                MenuItem.Button(MenuAction.SETTINGS, context.getString(R.string.menu_settings)),
            ),
            footer = highscores.best().takeIf { it > 0 }?.let { context.getString(R.string.menu_best, it) },
            hero = true,
        )
        Screen.PAUSED -> MenuPage(
            title = context.getString(R.string.pause_title),
            lines = listOf(
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
                MenuItem.Toggle(MenuAction.TOGGLE_COLORBLIND, context.getString(R.string.settings_colorblind), settings.colorblind),
                MenuItem.Button(MenuAction.BACK, context.getString(R.string.menu_back)),
            ),
            footer = context.getString(R.string.settings_language),
        )
        Screen.GAME_OVER -> MenuPage(
            title = context.getString(R.string.game_over_title),
            highlight = if (newBest) context.getString(R.string.game_over_new_best) else null,
            lines = listOfNotNull(
                resources.getQuantityString(R.plurals.game_over_stats, world.delivered, world.delivered, world.week),
                if (newBest) null else context.getString(R.string.game_over_best, highscores.best()),
            ),
            items = listOf(
                MenuItem.Button(MenuAction.PLAY_AGAIN, context.getString(R.string.game_over_again), primary = true),
                MenuItem.Button(MenuAction.MAIN_MENU, context.getString(R.string.menu_main)),
            ),
        )
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
        when (action) {
            MenuAction.PLAY, MenuAction.PLAY_AGAIN, MenuAction.RESTART -> startGame(World(seed = System.currentTimeMillis()))
            MenuAction.CONTINUE -> continueGame()
            MenuAction.RESUME -> screen = Screen.PLAYING
            MenuAction.SETTINGS -> {
                settingsReturn = screen
                screen = Screen.SETTINGS
            }
            MenuAction.BACK -> screen = settingsReturn
            MenuAction.MAIN_MENU -> {
                autosave()
                screen = Screen.MAIN_MENU
                if (!gameInProgress) showWorld(DemoCity.build())
            }
            MenuAction.TOGGLE_SOUND -> updateSettings(settings.copy(sound = !settings.sound))
            MenuAction.TOGGLE_HAPTICS -> updateSettings(settings.copy(haptics = !settings.haptics))
            MenuAction.TOGGLE_OVERVIEW -> updateSettings(settings.copy(overviewMode = !settings.overviewMode))
            MenuAction.TOGGLE_COLORBLIND -> updateSettings(settings.copy(colorblind = !settings.colorblind))
        }
    }

    private fun onBack() {
        when (screen) {
            Screen.PLAYING -> if (placing != null) placing = null else openPauseMenu()
            Screen.PAUSED -> screen = Screen.PLAYING
            Screen.SETTINGS -> screen = settingsReturn
            Screen.GAME_OVER -> onMenuAction(MenuAction.MAIN_MENU)
            Screen.MAIN_MENU -> mainThread.post { onExit?.invoke() }
        }
    }

    private fun openPauseMenu() {
        endDrag()
        placing = null
        screen = Screen.PAUSED
        autosave()
    }

    /** "Continue": the game still in memory, otherwise the autosave. */
    private fun continueGame() {
        if (gameInProgress) {
            screen = Screen.PLAYING
            return
        }
        val saved = saveStore.load()
        if (saved == null || saved.gameOver) {
            saveStore.clear()
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

    private fun showWorld(w: World) {
        world = w
        gameInProgress = false
        layoutRenderers()
        clock.reset()
        endDrag()
        placing = null
        pressedCard = null
        gestureConsumed = false
    }

    /** Game over while playing: record the score, drop the save and show the result. */
    private fun checkGameOver() {
        if (screen != Screen.PLAYING || !world.gameOver || !gameInProgress) return
        gameInProgress = false
        newBest = highscores.submit(world.delivered)
        saveStore.clear()
        hasSave = false
        endDrag()
        placing = null
        screen = Screen.GAME_OVER
    }

    /** Saves the running game, if there is one. */
    private fun autosave() {
        if (gameInProgress && !world.gameOver && saveStore.save(world)) hasSave = true
    }

    private fun updateSettings(s: GameSettings) {
        settingsStore.save(s)
        applySettings(s)
    }

    private fun applySettings(s: GameSettings) {
        settings = s
        ServiceColors.colorblind = s.colorblind
        renderer = if (s.overviewMode) flat else iso
    }

    private fun haptic(kind: Int) {
        if (!settings.haptics) return
        hapticPulses++
        post { performHapticFeedback(kind) }
    }

    /** The system click sound for buttons, if sound is on (game sounds come with P3.2). */
    private fun click() {
        if (settings.sound) post { playSoundEffect(SoundEffectConstants.CLICK) }
    }

    /** Fits every style to the unlocked area, keeping the HUD rows free; the demo town sits beside the main menu card. */
    private fun layoutRenderers() {
        val insets = if (screen == Screen.MAIN_MENU && !gameInProgress) {
            ViewInsets(surfaceWidth * 0.55f, 24 * density, 16 * density, 24 * density)
        } else {
            ViewInsets(8 * density, 56 * density, 8 * density, 68 * density)
        }
        renderers.forEach { it.layout(surfaceWidth, surfaceHeight, world, insets) }
        framedArea = world.unlocked
        growthHintPending = false
    }

    private companion object {
        /** Lower bound per loop iteration, in case posting a frame does not block on vsync. */
        const val MIN_FRAME_NANOS = 8_000_000L
        /** Longest animation step per frame, so animations do not jump after a stall. */
        const val MAX_ANIM_STEP = 0.05f
        /** Minimum distance between two drag trail samples, in cells. */
        const val TRAIL_SPACING = 0.2f
        const val MAX_TRAIL = 256
        const val HINT_SECONDS = 2.5f
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
