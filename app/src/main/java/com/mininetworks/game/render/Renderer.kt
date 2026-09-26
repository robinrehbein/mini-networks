package com.mininetworks.game.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.mininetworks.game.game.Cable
import com.mininetworks.game.game.CableLayout
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Geometry
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.Packet
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.Shape
import com.mininetworks.game.game.Vec2
import com.mininetworks.game.game.World
import kotlin.math.sin

/**
 * A cable the player is currently dragging. `end` is the pointer in world space; [layout] is the grid path that will be
 * built (to [target], or to the hovered cell while no target is under the pointer).
 */
class DragPreview(
    val from: Node,
    val end: Vec2,
    val target: Node?,
    val type: CableType,
    val layout: CableLayout,
    val error: String?,
    val cost: Int?,
)

/**
 * A visual style. Renderers only read the [World]; they never change it.
 * Every style owns its own projection so input (touch -> world) always matches what is on screen.
 */
interface Renderer {
    val name: String

    fun layout(width: Int, height: Int, world: World)

    /** World units -> screen pixels, on the ground plane. */
    fun toScreen(p: Vec2): Vec2

    /** Screen pixels -> world units, on the ground plane. */
    fun toWorld(sx: Float, sy: Float): Vec2

    /** How a cable runs in world space; always the layout stored in the model, so every style agrees. */
    fun cablePath(c: Cable): List<Vec2> = c.layout.waypoints

    fun draw(canvas: Canvas, world: World, drag: DragPreview?, time: Float)

    /** Size of one world unit on screen, used by the HUD to scale touch targets. */
    val unitPx: Float

    fun packetPosition(world: World, p: Packet): Vec2 = world.packetPosition(p)

    fun cableNear(world: World, p: Vec2, radius: Float = 0.35f): Cable? =
        world.cables.minByOrNull { Geometry.distToPolyline(p, cablePath(it)) }
            ?.takeIf { Geometry.distToPolyline(p, cablePath(it)) <= radius }
}

/** Shape helpers shared by all styles. */
object Shapes {
    private val path = Path()

    fun path(shape: Shape, x: Float, y: Float, r: Float): Path {
        path.reset()
        when (shape) {
            Shape.CIRCLE -> path.addCircle(x, y, r, Path.Direction.CW)
            Shape.SQUARE -> { val q = r * 0.85f; path.addRect(x - q, y - q, x + q, y + q, Path.Direction.CW) }
            Shape.TRIANGLE -> {
                val h = r * 1.15f
                path.moveTo(x, y - h)
                path.lineTo(x + h * 0.95f, y + h * 0.7f)
                path.lineTo(x - h * 0.95f, y + h * 0.7f)
                path.close()
            }
            Shape.DIAMOND -> {
                val h = r * 1.2f
                path.moveTo(x, y - h); path.lineTo(x + h, y); path.lineTo(x, y + h); path.lineTo(x - h, y); path.close()
            }
        }
        return path
    }

    fun draw(c: Canvas, shape: Shape, x: Float, y: Float, r: Float, paint: Paint) = c.drawPath(path(shape, x, y, r), paint)
}

fun Int.shade(f: Float): Int {
    val r = (this shr 16) and 0xFF
    val g = (this shr 8) and 0xFF
    val b = this and 0xFF
    fun m(v: Int) = if (f < 0) (v * (1 + f)).toInt() else (v + (255 - v) * f).toInt()
    return (0xFF shl 24) or (m(r) shl 16) or (m(g) shl 8) or m(b)
}

fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

fun stroke(color: Int, width: Float = 1f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    style = Paint.Style.STROKE
    strokeWidth = width
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}

/** Fixed game palette per service. Kept in one place so a colorblind mode can swap it later. */
object ServiceColors {
    fun of(s: Service) = when (s) {
        Service.MAIL -> 0xFF2E86AB.toInt()
        Service.CALL -> 0xFF3BA55C.toInt()
        Service.GAMING -> 0xFFE9A92B.toInt()
        Service.STREAMING -> 0xFFE4572E.toInt()
    }
}

/** Look of each cable technology: outer color, width (in world units) and an optional inner core line. */
object CableStyles {
    class Style(val color: Int, val width: Float, val core: Int?, val coreWidth: Float)

    fun of(t: CableType) = when (t) {
        CableType.ISDN -> Style(0xFF9AA3AD.toInt(), 0.08f, null, 0f)
        CableType.DSL -> Style(0xFF39424E.toInt(), 0.13f, null, 0f)
        CableType.COAX -> Style(0xFF2F2A26.toInt(), 0.17f, 0xFF9C8B7A.toInt(), 0.045f)
        CableType.FIBER -> Style(0xFFF28C28.toInt(), 0.18f, 0xFFFFE2B8.toInt(), 0.05f)
    }
}

/**
 * Hand-drawn device icons in screen space, shared by all styles.
 * [s] is the half-size of the icon in pixels. Icons are ink outlines on white so they read at small sizes.
 */
class DeviceIcons {
    private val ink = 0xFF262B33.toInt()
    private val screen = 0xFFDCE6EF.toInt()
    private val body = fill(0xFFFFFFFF.toInt())
    private val line = stroke(ink)
    private val solid = fill(ink)
    private val rect = RectF()
    private val path = Path()

    private fun box(l: Float, t: Float, r: Float, b: Float, radius: Float, fillColor: Int = 0xFFFFFFFF.toInt(), s: Float) {
        rect.set(l, t, r, b)
        body.color = fillColor
        canvas.drawRoundRect(rect, radius, radius, body)
        line.strokeWidth = s * 0.16f
        canvas.drawRoundRect(rect, radius, radius, line)
    }

    private lateinit var canvas: Canvas

    fun device(c: Canvas, d: Device, x: Float, y: Float, s: Float) {
        canvas = c
        line.color = ink
        when (d) {
            Device.PC -> {
                box(x - s, y - s * 0.85f, x + s, y + s * 0.4f, s * 0.12f, screen, s)
                line.strokeWidth = s * 0.16f
                canvas.drawLine(x, y + s * 0.4f, x, y + s * 0.75f, line)
                canvas.drawLine(x - s * 0.45f, y + s * 0.8f, x + s * 0.45f, y + s * 0.8f, line)
            }
            Device.PHONE -> {
                // Desk phone: base with keypad, handset on top.
                path.reset()
                path.moveTo(x - s * 0.8f, y + s * 0.8f); path.lineTo(x - s * 0.55f, y - s * 0.1f)
                path.lineTo(x + s * 0.55f, y - s * 0.1f); path.lineTo(x + s * 0.8f, y + s * 0.8f); path.close()
                body.color = 0xFFFFFFFF.toInt(); canvas.drawPath(path, body)
                line.strokeWidth = s * 0.16f; canvas.drawPath(path, line)
                line.strokeWidth = s * 0.3f
                canvas.drawLine(x - s * 0.75f, y - s * 0.5f, x + s * 0.75f, y - s * 0.5f, line)
                for (i in 0 until 3) canvas.drawCircle(x - s * 0.3f + i * s * 0.3f, y + s * 0.4f, s * 0.08f, solid)
            }
            Device.LAPTOP -> {
                box(x - s * 0.8f, y - s * 0.8f, x + s * 0.8f, y + s * 0.3f, s * 0.1f, screen, s)
                box(x - s * 1.05f, y + s * 0.3f, x + s * 1.05f, y + s * 0.55f, s * 0.1f, 0xFFFFFFFF.toInt(), s)
            }
            Device.CONSOLE -> {
                box(x - s, y - s * 0.5f, x + s, y + s * 0.5f, s * 0.45f, 0xFFFFFFFF.toInt(), s)
                line.strokeWidth = s * 0.14f
                canvas.drawLine(x - s * 0.7f, y, x - s * 0.3f, y, line)
                canvas.drawLine(x - s * 0.5f, y - s * 0.2f, x - s * 0.5f, y + s * 0.2f, line)
                canvas.drawCircle(x + s * 0.35f, y + s * 0.1f, s * 0.1f, solid)
                canvas.drawCircle(x + s * 0.6f, y - s * 0.1f, s * 0.1f, solid)
            }
            Device.SMARTPHONE -> {
                box(x - s * 0.52f, y - s, x + s * 0.52f, y + s, s * 0.2f, 0xFFFFFFFF.toInt(), s)
                rect.set(x - s * 0.34f, y - s * 0.72f, x + s * 0.34f, y + s * 0.6f); body.color = screen; canvas.drawRect(rect, body)
                canvas.drawCircle(x, y + s * 0.8f, s * 0.07f, solid)
            }
            Device.TV -> {
                box(x - s * 1.1f, y - s * 0.75f, x + s * 1.1f, y + s * 0.5f, s * 0.08f, screen, s)
                line.strokeWidth = s * 0.16f
                canvas.drawLine(x - s * 0.6f, y + s * 0.55f, x - s * 0.75f, y + s * 0.8f, line)
                canvas.drawLine(x + s * 0.6f, y + s * 0.55f, x + s * 0.75f, y + s * 0.8f, line)
            }
            Device.TABLET -> {
                box(x - s, y - s * 0.72f, x + s, y + s * 0.72f, s * 0.18f, 0xFFFFFFFF.toInt(), s)
                rect.set(x - s * 0.72f, y - s * 0.5f, x + s * 0.62f, y + s * 0.5f); body.color = screen; canvas.drawRect(rect, body)
                canvas.drawCircle(x + s * 0.82f, y, s * 0.06f, solid)
            }
            Device.WATCH -> {
                box(x - s * 0.32f, y - s, x + s * 0.32f, y + s, s * 0.1f, 0xFFB9C2CC.toInt(), s)
                box(x - s * 0.58f, y - s * 0.58f, x + s * 0.58f, y + s * 0.58f, s * 0.2f, screen, s)
                line.strokeWidth = s * 0.12f
                canvas.drawLine(x, y, x, y - s * 0.3f, line)
                canvas.drawLine(x, y, x + s * 0.22f, y, line)
            }
        }
    }

    /** Rack server in the service color: one rack unit per [level], red LEDs while [busy], white service badge on top. */
    fun server(c: Canvas, service: Service, level: Int, busy: Boolean, x: Float, y: Float, s: Float, time: Float) {
        canvas = c
        val col = ServiceColors.of(service)
        val unitH = s * 0.62f
        val bottom = y + s * 0.8f
        val top = bottom - unitH * level - s * 0.2f
        box(x - s * 0.8f, top, x + s * 0.8f, bottom, s * 0.14f, col, s)
        for (i in 0 until level) {
            val sy = bottom - s * 0.1f - unitH * (i + 0.5f)
            rect.set(x - s * 0.55f, sy - s * 0.12f, x + s * 0.25f, sy + s * 0.12f)
            body.color = col.shade(-0.3f); canvas.drawRect(rect, body)
            body.color = when {
                busy -> 0xFFFF5A5A.toInt()
                sin(time * 4f + i * 2.1f + x * 0.01f) > 0f -> 0xFF7CF29A.toInt()
                else -> col.shade(-0.4f)
            }
            canvas.drawCircle(x + s * 0.5f, sy, s * 0.1f, body)
        }
        body.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(x + s * 0.8f, top, s * 0.42f, body)
        line.strokeWidth = s * 0.1f; canvas.drawCircle(x + s * 0.8f, top, s * 0.42f, line)
        body.color = col
        Shapes.draw(canvas, service.shape, x + s * 0.8f, top + if (service.shape == Shape.TRIANGLE) s * 0.04f else 0f, s * 0.22f, body)
    }

    fun router(c: Canvas, x: Float, y: Float, s: Float, time: Float) {
        canvas = c
        line.strokeWidth = s * 0.14f
        canvas.drawLine(x - s * 0.5f, y - s * 0.2f, x - s * 0.7f, y - s * 0.9f, line)
        canvas.drawLine(x + s * 0.5f, y - s * 0.2f, x + s * 0.7f, y - s * 0.9f, line)
        box(x - s * 0.9f, y - s * 0.25f, x + s * 0.9f, y + s * 0.45f, s * 0.15f, 0xFFFFFFFF.toInt(), s)
        for (i in 0 until 3) {
            body.color = if (sin(time * 6f + i * 1.3f) > 0f) 0xFF3BA55C.toInt() else 0xFFB9C2CC.toInt()
            canvas.drawCircle(x - s * 0.4f + i * s * 0.4f, y + s * 0.1f, s * 0.09f, body)
        }
    }
}
