package com.mininetworks.game.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.Vec2
import com.mininetworks.game.game.World
import com.mininetworks.game.render.DragPreview
import com.mininetworks.game.render.FlatRenderer
import com.mininetworks.game.render.IsoRenderer
import com.mininetworks.game.render.Renderer
import com.mininetworks.game.render.fill
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Hosts the game loop (Choreographer, one update + draw per vsync), the HUD and touch input.
 *
 * Controls:
 *  - drag from a node to another node: lay a cable
 *  - tap a cable: remove it (refunds its cost)
 *  - "Router" button, then tap an empty cell: place a router
 *  - "Stil" button: switch between flat and isometric rendering
 */
class GameView(context: Context) : View(context), Choreographer.FrameCallback {

    private var world = World(seed = System.currentTimeMillis())
    private val renderers: List<Renderer> = listOf(FlatRenderer(), IsoRenderer())
    private var rendererIndex = 0
    private val renderer get() = renderers[rendererIndex]

    private var paused = false
    private var routerMode = false
    private var lastFrameNanos = 0L
    private var animTime = 0f

    private var dragFrom: Node? = null
    private var dragEnd: Vec2? = null
    private var downX = 0f
    private var downY = 0f

    private val density = resources.displayMetrics.density
    private val hudText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF262B33.toInt(); typeface = Typeface.DEFAULT_BOLD; textSize = 16 * density }
    private val hudSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5B6674.toInt(); textSize = 13 * density }
    private val btnFill = fill(0xE6FFFFFF.toInt())
    private val btnActive = fill(0xFF262B33.toInt())
    private val btnText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = 14 * density }
    private val barBg = fill(0x33262B33)
    private val barFg = fill(0xFF262B33.toInt())
    private val overlay = fill(0xCCF3F1EC.toInt())
    private val bigText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; color = 0xFF262B33.toInt() }

    private data class Button(val id: String, val rect: RectF)
    private val buttons = mutableListOf<Button>()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    fun pause() { paused = true }

    override fun doFrame(frameTimeNanos: Long) {
        val dt = if (lastFrameNanos == 0L) 0f else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceAtMost(0.05f)
        lastFrameNanos = frameTimeNanos
        animTime += dt
        if (!paused) world.update(dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        renderers.forEach { it.layout(w, h, world) }
    }

    override fun onDraw(canvas: Canvas) {
        renderer.draw(canvas, world, dragPreview(), animTime)
        drawHud(canvas)
        if (world.gameOver) drawGameOver(canvas)
    }

    private fun dragPreview(): DragPreview? {
        val from = dragFrom ?: return null
        val end = dragEnd ?: return null
        val target = world.nodeNear(end)?.takeIf { it !== from }
        return DragPreview(
            from = from,
            end = end,
            target = target,
            error = target?.let { world.connectError(from, it) },
            cost = target?.let { world.cableCost(from, it) },
        )
    }

    // ---------------------------------------------------------------- HUD

    private fun drawHud(canvas: Canvas) {
        val pad = 16 * density
        canvas.drawText("Woche ${world.week}", pad, pad + hudText.textSize, hudText)
        val barY = pad + hudText.textSize + 8 * density
        val barW = 110 * density
        canvas.drawRoundRect(pad, barY, pad + barW, barY + 4 * density, 2 * density, 2 * density, barBg)
        canvas.drawRoundRect(pad, barY, pad + barW * world.weekProgress, barY + 4 * density, 2 * density, 2 * density, barFg)

        val right = width - pad
        hudText.textAlign = Paint.Align.RIGHT
        canvas.drawText("${world.delivered} Pakete", right, pad + hudText.textSize, hudText)
        hudText.textAlign = Paint.Align.LEFT
        hudSub.textAlign = Paint.Align.RIGHT
        canvas.drawText("Kabel ${world.cableBudget}  ·  Router ${world.routersAvailable}", right, pad + hudText.textSize + 20 * density, hudSub)
        hudSub.textAlign = Paint.Align.LEFT

        world.lastEvent?.let {
            if (world.time - world.lastEventTime < 3.5f) {
                bigText.textSize = 15 * density
                canvas.drawText(it, width / 2f, pad + hudText.textSize, bigText)
            }
        }

        buttons.clear()
        val bh = 44 * density
        val gap = 10 * density
        var x = width - pad
        val y = height - pad - bh
        for ((id, label) in listOf(
            "pause" to if (paused) "Weiter" else "Pause",
            "style" to "Stil: ${renderer.name}",
            "router" to "Router (${world.routersAvailable})",
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
        if (routerMode) canvas.drawText("Tippe auf ein freies Feld", pad, height - pad - bh / 2 + hudSub.textSize * 0.35f, hudSub)
    }

    private fun drawGameOver(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlay)
        bigText.textSize = 34 * density
        canvas.drawText("Netz überlastet", width / 2f, height / 2f - 12 * density, bigText)
        bigText.textSize = 18 * density
        canvas.drawText("${world.delivered} Pakete zugestellt · Woche ${world.week}", width / 2f, height / 2f + 22 * density, bigText)
        bigText.textSize = 14 * density
        canvas.drawText("Tippen für ein neues Spiel", width / 2f, height / 2f + 52 * density, bigText)
    }

    // ---------------------------------------------------------------- input

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                if (world.gameOver) { restart(); return true }
                buttons.firstOrNull { it.rect.contains(e.x, e.y) }?.let { onButton(it.id); return true }
                val p = renderer.toWorld(e.x, e.y)
                if (routerMode) {
                    world.placeRouter(floor(p.x).toInt(), floor(p.y).toInt())
                    routerMode = false
                    return true
                }
                val radius = maxOf(0.7f, 28 * density / renderer.unitPx)
                dragFrom = world.nodeNear(p, radius)
                dragEnd = p
            }
            MotionEvent.ACTION_MOVE -> if (dragFrom != null) dragEnd = renderer.toWorld(e.x, e.y)
            MotionEvent.ACTION_UP -> {
                val from = dragFrom
                val p = renderer.toWorld(e.x, e.y)
                val isTap = hypot(e.x - downX, e.y - downY) < 12 * density
                if (from != null && !isTap) {
                    world.nodeNear(p)?.let { if (it !== from) world.connect(from, it) }
                } else if (isTap && from == null) {
                    renderer.cableNear(world, p)?.let { world.removeCable(it) }
                }
                dragFrom = null; dragEnd = null
            }
            MotionEvent.ACTION_CANCEL -> { dragFrom = null; dragEnd = null }
        }
        return true
    }

    private fun onButton(id: String) {
        when (id) {
            "pause" -> paused = !paused
            "style" -> {
                rendererIndex = (rendererIndex + 1) % renderers.size
                renderer.layout(width, height, world)
            }
            "router" -> routerMode = !routerMode && world.routersAvailable > 0
        }
    }

    private fun restart() {
        world = World(seed = System.currentTimeMillis())
        renderers.forEach { it.layout(width, height, world) }
        paused = false
        routerMode = false
    }
}
