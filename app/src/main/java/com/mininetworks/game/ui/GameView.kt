package com.mininetworks.game.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.mininetworks.game.R
import com.mininetworks.game.game.Bend
import com.mininetworks.game.game.CableLayout
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.Cell
import com.mininetworks.game.game.FixedStep
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.ServerUpgradeError
import com.mininetworks.game.game.Vec2
import com.mininetworks.game.game.World
import com.mininetworks.game.render.CableStyles
import com.mininetworks.game.render.DragPreview
import com.mininetworks.game.render.FlatRenderer
import com.mininetworks.game.render.IsoRenderer
import com.mininetworks.game.render.Renderer
import com.mininetworks.game.render.TouchTargets
import com.mininetworks.game.render.TwoFingerGesture
import com.mininetworks.game.render.ViewInsets
import com.mininetworks.game.render.fill
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Hosts the game loop, the HUD and touch input on a [SurfaceView].
 *
 * A dedicated game thread owns all game state ([World], renderers, HUD state). Each frame it drains the input queue,
 * advances the simulation in fixed 1/60 s steps ([FixedStep], at most 5 per frame) and draws to the surface.
 * The UI thread only enqueues [Input]s, so the world is never touched concurrently.
 *
 * Controls:
 *  - drag from a node to another node: lay a cable along the grid (L-shaped; the drag path picks which way it bends)
 *  - drag on empty ground or with two fingers: pan; pinch: zoom; double tap on empty ground: fit the playable area
 *  - pick a cable technology in the bottom-left bar (ISDN, DSL, Kabel, Glasfaser)
 *  - tap a server: upgrade its hardware (more throughput, taller stack; tier 4 is a data center on 2×2 cells)
 *  - tap a cable: upgrade it to the picked technology, or remove it if it already is that type
 *  - "Router" button, then tap an empty cell: place a router
 *  - "Stil" button: switch between flat and isometric rendering
 *  - at each week change the world pauses and [RewardDialog] shows two reward cards; tap one to pick it
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
    }

    // ---------------------------------------------------------------- shared between UI and game thread

    private val inputs = ConcurrentLinkedQueue<Input>()
    private val surfaceLock = ReentrantLock()
    private val surfaceAvailable = surfaceLock.newCondition()
    private var hasSurface = false // guarded by surfaceLock
    @Volatile private var running = false
    private var loop: Thread? = null // UI thread only

    // ---------------------------------------------------------------- game thread only (or UI thread while stopped)

    private var world = World(seed = System.currentTimeMillis())
    private val renderers: List<Renderer> = listOf(FlatRenderer(), IsoRenderer())
    private var rendererIndex = 0
    private val renderer get() = renderers[rendererIndex]
    private val clock = FixedStep()
    private var useHardwareCanvas = true
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private var paused = false
    private var routerMode = false
    private var cableType = CableType.ISDN
    private var animTime = 0f

    /** Short feedback above the bottom bar, e.g. why a server tap did not upgrade; shown until [hintUntil]. */
    private var hint: String? = null
    private var hintUntil = 0f

    private var dragFrom: Node? = null
    private var dragEnd: Vec2? = null
    /** Pointer of the current drag in screen pixels, used to pick the target node in screen space. */
    private var dragEndScreen: Vec2? = null
    /** Pointer samples of the current drag in world space; they decide which way the cable bends. */
    private val dragTrail = ArrayList<Vec2>()
    private var downX = 0f
    private var downY = 0f
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
    private val btnText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = 14 * density }
    private val barBg = fill(0x33262B33)
    private val swatch = fill(0)
    private val barFg = fill(0xFF262B33.toInt())
    private val overlay = fill(0xCCF3F1EC.toInt())
    private val bigText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; color = 0xFF262B33.toInt() }

    private data class Button(val id: String, val rect: RectF)
    private val buttons = mutableListOf<Button>()

    init {
        holder.addCallback(this)
    }

    // ---------------------------------------------------------------- lifecycle (UI thread)

    /** Starts the game thread; call from `Activity.onResume`. */
    fun resume() {
        if (loop != null) return
        running = true
        loop = thread(name = "GameLoop") { runLoop() }
    }

    /** Stops the game thread and pauses the game; call from `Activity.onPause`. */
    fun pause() {
        val t = loop ?: return
        running = false
        surfaceLock.withLock { surfaceAvailable.signalAll() }
        joinQuietly(t)
        loop = null
        paused = true // safe: the game thread has ended
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
        if (paused) clock.reset() else clock.advance(frameSeconds) { world.update(it) }
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
        renderer.draw(canvas, world, dragPreview(), animTime)
        drawHud(canvas)
        world.rewardOffer?.let { rewardDialog.draw(canvas, world, it, surfaceWidth, surfaceHeight, animTime, pressedCard) }
        if (world.gameOver) drawGameOver(canvas)
    }

    /**
     * Draws one frame of [snapshotWorld] at the given size into [canvas], for screenshot tests.
     * Only valid while the game thread is not running.
     */
    internal fun drawSnapshot(canvas: Canvas, snapshotWorld: World, width: Int, height: Int, time: Float, style: String? = null) {
        check(loop == null) { "game loop is running" }
        world = snapshotWorld
        if (style != null) rendererIndex = renderers.indexOfFirst { it.name == style }.also { require(it >= 0) { "unknown style $style" } }
        animTime = time
        handle(Input.Resize(width, height))
        drawFrame(canvas)
    }

    /** The active style, for tests. */
    internal val activeRenderer: Renderer get() = renderer

    /**
     * Feeds one touch event straight to the input handling, for tests; [pointers] as in [Input.Touch].
     * Only valid while the game thread is not running.
     */
    internal fun injectTouch(action: Int, x: Float, y: Float, pointers: FloatArray = floatArrayOf(x, y), time: Long = 0L) {
        check(loop == null) { "game loop is running" }
        handle(Input.Touch(action, x, y, pointers, time))
    }

    private fun dragPreview(): DragPreview? {
        val from = dragFrom ?: return null
        val end = dragEnd ?: return null
        val target = dragEndScreen?.let { pickNode(it.x, it.y, except = from) }
        val toCell = target?.cell ?: Cell(floor(end.x).toInt(), floor(end.y).toInt())
        val bend = dragBend(from.cell, toCell)
        val layout = world.planLayout(from.cell, toCell, bend)
        return DragPreview(
            from = from,
            end = end,
            target = target,
            type = cableType,
            layout = layout,
            error = target?.let { world.connectError(from, it, cableType, bend) },
            cost = target?.let { world.cableCost(layout, cableType) },
        )
    }

    private fun dragBend(from: Cell, to: Cell): Bend? = CableLayout.suggestBend(from, to, dragTrail)

    private fun trackDrag(sx: Float, sy: Float) {
        val p = renderer.toWorld(sx, sy)
        dragEnd = p
        dragEndScreen = Vec2(sx, sy)
        val last = dragTrail.lastOrNull()
        if ((last == null || hypot(p.x - last.x, p.y - last.y) >= TRAIL_SPACING) && dragTrail.size < MAX_TRAIL) dragTrail += p
    }

    private fun endDrag() {
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

        world.lastEvent?.let {
            if (world.rewardOffer == null && world.time - world.lastEventTime < 3.5f) {
                bigText.textSize = 15 * density
                canvas.drawText(it, surfaceWidth / 2f, pad + hudText.textSize, bigText)
            }
        }

        buttons.clear()
        val bh = 44 * density
        val gap = 10 * density
        var x = surfaceWidth - pad
        val y = surfaceHeight - pad - bh
        for ((id, label) in listOf(
            "pause" to context.getString(if (paused) R.string.button_resume else R.string.button_pause),
            "style" to context.getString(R.string.button_style, renderer.name),
            "router" to context.getString(R.string.button_router, world.routersAvailable),
        )) {
            val w = btnText.measureText(label) + 32 * density
            val r = RectF(x - w, y, x, y + bh)
            val active = id == "router" && routerMode
            canvas.drawRoundRect(r, bh / 2, bh / 2, if (active) btnActive else btnFill)
            btnText.color = if (active) 0xFFFFFFFF.toInt() else 0xFF262B33.toInt()
            canvas.drawText(label, r.centerX(), r.centerY() + btnText.textSize * 0.35f, btnText)
            buttons += Button(id, r)
            x -= w + gap
        }
        // Cable technology picker, bottom left. Only invented technologies are shown.
        var cx = pad
        for (t in world.unlockedCables) {
            val label = context.getString(R.string.button_cable, t.label, t.costPerCell)
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
        if (routerMode) {
            canvas.drawText(context.getString(R.string.hint_place_router), pad, y - 10 * density, hudSub)
        } else if (animTime < hintUntil) {
            hint?.let { canvas.drawText(it, pad, y - 10 * density, hudSub) }
        }
    }

    private fun drawGameOver(canvas: Canvas) {
        val cx = surfaceWidth / 2f
        val cy = surfaceHeight / 2f
        canvas.drawRect(0f, 0f, surfaceWidth.toFloat(), surfaceHeight.toFloat(), overlay)
        bigText.textSize = 34 * density
        canvas.drawText(context.getString(R.string.game_over_title), cx, cy - 12 * density, bigText)
        bigText.textSize = 18 * density
        canvas.drawText(resources.getQuantityString(R.plurals.game_over_stats, world.delivered, world.delivered, world.week), cx, cy + 22 * density, bigText)
        bigText.textSize = 14 * density
        canvas.drawText(context.getString(R.string.game_over_restart), cx, cy + 52 * density, bigText)
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
        }
    }

    private fun onTouch(e: Input.Touch) {
        if (gestureConsumed || (world.rewardOffer != null && e.action == MotionEvent.ACTION_DOWN)) {
            onRewardTouch(e)
            return
        }
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                cameraGesture = false
                if (world.gameOver) { restart(); return }
                buttons.firstOrNull { it.rect.contains(e.x, e.y) }?.let { onButton(it.id); return }
                val p = renderer.toWorld(e.x, e.y)
                if (routerMode) {
                    world.placeRouter(floor(p.x).toInt(), floor(p.y).toInt())
                    routerMode = false
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
                trackDrag(e.x, e.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger turns any gesture into camera movement; a half-drawn cable is dropped.
                endDrag()
                cameraGesture = true
                startPinch(e)
            }
            MotionEvent.ACTION_MOVE -> when {
                cameraGesture -> if (e.pointers.size >= 4) {
                    pinch.move(e.pointers[0], e.pointers[1], e.pointers[2], e.pointers[3], renderer.camera)
                }
                dragFrom != null -> trackDrag(e.x, e.y)
                panArmed -> panWithOneFinger(e)
            }
            MotionEvent.ACTION_POINTER_UP -> if (cameraGesture) startPinch(e)
            MotionEvent.ACTION_UP -> {
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
                } else if (from != null && !isTap) {
                    trackDrag(e.x, e.y)
                    pickNode(e.x, e.y, except = from)?.let { world.connect(from, it, cableType, dragBend(from.cell, it.cell)) }
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

    private fun onButton(id: String) {
        when (id) {
            "pause" -> paused = !paused
            "style" -> rendererIndex = (rendererIndex + 1) % renderers.size
            "router" -> routerMode = !routerMode && world.routersAvailable > 0
            else -> if (id.startsWith("cable:")) cableType = CableType.valueOf(id.removePrefix("cable:"))
        }
    }

    private fun restart() {
        world = World(seed = System.currentTimeMillis())
        layoutRenderers()
        clock.reset()
        paused = false
        routerMode = false
        cableType = CableType.ISDN
    }

    /** Fits every style to the unlocked area, keeping the HUD rows free. */
    private fun layoutRenderers() {
        val insets = ViewInsets(8 * density, 56 * density, 8 * density, 68 * density)
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
    }
}
