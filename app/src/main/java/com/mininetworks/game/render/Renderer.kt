package com.mininetworks.game.render

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.mininetworks.game.game.Cable
import com.mininetworks.game.game.CableLayout
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.CellRect
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Geometry
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.Packet
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.Shape
import com.mininetworks.game.game.Vec2
import com.mininetworks.game.game.World
import kotlin.math.cos
import kotlin.math.hypot
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
    /** True if releasing here would not build the cable; the preview is drawn in the alarm color. */
    val blocked: Boolean,
    /** Text above the pointer: the cable and its price, or why it cannot be built. */
    val label: String?,
)

/**
 * A visual style. Renderers only read the [World]; they never change it.
 * Every style owns its own projection so input (touch -> world) always matches what is on screen:
 * world -> map units ([toMap], fixed per style) -> screen pixels ([camera], zoom and pan).
 */
interface Renderer {
    val name: String

    /** Zoom and pan of this style's view. */
    val camera: Camera

    /** World units on the ground plane -> this style's map units (before zoom and pan). */
    fun toMap(p: Vec2): Vec2

    /** Map units -> world units on the ground plane; inverse of [toMap]. */
    fun fromMap(mx: Float, my: Float): Vec2

    /** Map-space box that shows [area] with everything drawn on it (buildings, queues, board edge). */
    fun mapBounds(area: CellRect): MapRect

    /** World units -> screen pixels, on the ground plane. */
    fun toScreen(p: Vec2): Vec2 = camera.toScreen(toMap(p))

    /** Screen pixels -> world units, on the ground plane. */
    fun toWorld(sx: Float, sy: Float): Vec2 = camera.toMap(sx, sy).let { fromMap(it.x, it.y) }

    /** Sets the view size and fits the camera to the world's unlocked area. */
    fun layout(width: Int, height: Int, world: World, insets: ViewInsets = ViewInsets.NONE) {
        camera.setViewport(width, height, insets)
        fitArea(world, animate = false)
    }

    /** Zooms so the unlocked area fills the view, e.g. on a double tap. */
    fun fitArea(world: World, animate: Boolean) {
        updateLimits(world)
        camera.fit(mapBounds(world.unlocked), animate)
    }

    /** Call when the unlocked area grew: widens the limits and, unless the player moved the view, follows the area. */
    fun onAreaChanged(world: World) {
        updateLimits(world)
        if (camera.followsArea) camera.fit(mapBounds(world.unlocked), animate = true)
    }

    /**
     * Zoom range: out to the whole grid (and a bit more), in until about [ZOOM_IN_COLS] × [ZOOM_IN_ROWS] cells fill the view.
     * The screen centre stays over the grid.
     */
    fun updateLimits(world: World) {
        val whole = mapBounds(world.bounds)
        val out = minOf(camera.fitScale(whole), camera.fitScale(mapBounds(world.unlocked))) * ZOOM_OUT_SLACK
        camera.setZoomRange(out, camera.fitScale(mapBounds(CellRect(0, 0, ZOOM_IN_COLS, ZOOM_IN_ROWS))))
        camera.panBounds = whole
    }

    /**
     * Glides the camera gently to [n] and zooms in by [zoom] (within the zoom range), e.g. to show which device failed.
     */
    fun focusOn(n: Node, zoom: Float = FOCUS_ZOOM) {
        val m = toMap(n.footprintCenter)
        camera.glideTo(m.x, m.y, camera.scale * zoom, gentle = true)
    }

    /** How a cable runs in world space; always the layout stored in the model, so every style agrees. */
    fun cablePath(c: Cable): List<Vec2> = c.layout.waypoints

    fun draw(canvas: Canvas, world: World, drag: DragPreview?, time: Float)

    /** Size of one world unit on screen at the current zoom. */
    val unitPx: Float

    fun packetPosition(world: World, p: Packet): Vec2 = world.packetPosition(p)

    fun cableNear(world: World, p: Vec2, radius: Float = 0.35f): Cable? =
        world.cables.minByOrNull { Geometry.distToPolyline(p, cablePath(it)) }
            ?.takeIf { Geometry.distToPolyline(p, cablePath(it)) <= radius }

    /**
     * The node closest to screen point ([sx], [sy]) within [radiusPx], measured to its footprint cells on screen,
     * ignoring [except].
     */
    fun nodeAtScreen(world: World, sx: Float, sy: Float, radiusPx: Float, except: Node? = null): Node? {
        val p = Vec2(sx, sy)
        fun dist(n: Node) = n.footprint.minOf { distance(toScreen(it.center), p) }
        return world.nodes.filter { it !== except }.minByOrNull(::dist)?.takeIf { dist(it) <= radiusPx }
    }

    /** The cable closest to screen point ([sx], [sy]) within [radiusPx]; both projections keep straight lines straight. */
    fun cableAtScreen(world: World, sx: Float, sy: Float, radiusPx: Float): Cable? {
        val p = Vec2(sx, sy)
        fun dist(c: Cable) = Geometry.distToPolyline(p, cablePath(c).map(::toScreen))
        return world.cables.minByOrNull(::dist)?.takeIf { dist(it) <= radiusPx }
    }

    companion object {
        const val ZOOM_OUT_SLACK = 0.9f
        const val ZOOM_IN_COLS = 6
        const val ZOOM_IN_ROWS = 4
        /** Zoom factor of [focusOn]. */
        const val FOCUS_ZOOM = 1.6f
    }
}

/** Minimum touch target sizes; picking works in screen space so targets keep this size at every zoom. */
object TouchTargets {
    /** Android's recommended minimum touch target. */
    const val MIN_DP = 48f

    /** Pick radius around a node: half a 48 dp target, or more when zoomed in far. */
    fun nodeRadiusPx(renderer: Renderer, density: Float) = maxOf(MIN_DP / 2f * density, 0.7f * renderer.unitPx)

    /** Pick radius around a cable. */
    fun cableRadiusPx(renderer: Renderer, density: Float) = maxOf(MIN_DP / 2f * density, 0.35f * renderer.unitPx)
}

private fun distance(a: Vec2, b: Vec2) = hypot(a.x - b.x, a.y - b.y)

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
            Shape.PENTAGON -> polygon(x, y + r * 0.08f, r * 1.1f, 5, -90f)
            Shape.HEXAGON -> polygon(x, y, r * 1.05f, 6, 0f)
            Shape.PLUS -> {
                val a = r * 1.05f
                val t = r * 0.38f
                path.moveTo(x - t, y - a); path.lineTo(x + t, y - a); path.lineTo(x + t, y - t); path.lineTo(x + a, y - t)
                path.lineTo(x + a, y + t); path.lineTo(x + t, y + t); path.lineTo(x + t, y + a); path.lineTo(x - t, y + a)
                path.lineTo(x - t, y + t); path.lineTo(x - a, y + t); path.lineTo(x - a, y - t); path.lineTo(x - t, y - t)
                path.close()
            }
        }
        return path
    }

    /** Regular polygon with [corners] on a circle of radius [r], the first corner at [startDeg]. */
    private fun polygon(x: Float, y: Float, r: Float, corners: Int, startDeg: Float) {
        for (i in 0 until corners) {
            val a = Math.toRadians((startDeg + 360f * i / corners).toDouble())
            val px = x + r * cos(a).toFloat()
            val py = y + r * sin(a).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
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

/**
 * Service colors of the active palette. The default palette puts green, amber and red side by side, which blur
 * together with red-green color blindness; [colorblind] switches to hues based on the Okabe-Ito set, tuned so that all
 * seven stay apart (lightness carries what the hue cannot). No service may be as dark as the DSL/coax cables or the
 * icon ink, or its packets vanish on them.
 * Shapes carry the information either way. Set from the game thread (settings), read while drawing.
 */
object ServiceColors {
    @Volatile var colorblind = false

    fun of(s: Service) = if (colorblind) colorblindOf(s) else defaultOf(s)

    fun defaultOf(s: Service) = when (s) {
        Service.MAIL -> 0xFF2E86AB.toInt()
        Service.CALL -> 0xFF3BA55C.toInt()
        Service.GAMING -> 0xFFE9A92B.toInt()
        Service.STREAMING -> 0xFFE4572E.toInt()
        Service.VIDEO_CALL -> 0xFF8E5BC6.toInt()
        Service.CAMERA_UPLOAD -> 0xFFE0529C.toInt()
        Service.CLOUD_BACKUP -> 0xFF6CC4EC.toInt()
    }

    fun colorblindOf(s: Service) = when (s) {
        Service.MAIL -> 0xFF006AB1.toInt()
        Service.CALL -> 0xFF43B771.toInt()
        Service.GAMING -> 0xFFF0E442.toInt()
        Service.STREAMING -> 0xFFD55E00.toInt()
        Service.VIDEO_CALL -> 0xFFCA7BA5.toInt()
        Service.CAMERA_UPLOAD -> 0xFF3D2BD9.toInt()
        Service.CLOUD_BACKUP -> 0xFF95DAFF.toInt()
    }
}

/** Look of each cable technology: outer color, width (in world units) and an optional inner core line. */
object CableStyles {
    class Style(val color: Int, val width: Float, val core: Int?, val coreWidth: Float)

    private val ISDN = Style(0xFF9AA3AD.toInt(), 0.08f, null, 0f)
    private val DSL = Style(0xFF39424E.toInt(), 0.13f, null, 0f)
    private val COAX = Style(0xFF2F2A26.toInt(), 0.17f, 0xFF9C8B7A.toInt(), 0.045f)
    private val FIBER = Style(0xFFF28C28.toInt(), 0.18f, 0xFFFFE2B8.toInt(), 0.05f)

    fun of(t: CableType) = when (t) {
        CableType.ISDN -> ISDN
        CableType.DSL -> DSL
        CableType.COAX -> COAX
        CableType.FIBER -> FIBER
    }
}

/**
 * Dash effects reused across frames. [get] builds a new effect only when the dash length changes (zoom) or the phase
 * reaches a new one of [steps] positions per dash period, so an animated dash costs no allocation per frame.
 */
class DashCache(private val steps: Int = 24) {
    private var dash = Float.NaN
    private var gapRatio = Float.NaN
    private val effects = arrayOfNulls<DashPathEffect>(steps)

    /** Dashes [dash] long with gaps of [gapRatio] × [dash], shifted by [phase] pixels (rounded to a step). */
    fun get(dash: Float, gapRatio: Float, phase: Float): DashPathEffect {
        if (dash != this.dash || gapRatio != this.gapRatio) {
            effects.fill(null)
            this.dash = dash
            this.gapRatio = gapRatio
        }
        val period = dash * (1f + gapRatio)
        val p = ((phase % period) + period) % period
        val step = (p / period * steps).toInt().coerceIn(0, steps - 1)
        return effects[step] ?: DashPathEffect(floatArrayOf(dash, dash * gapRatio), step * period / steps).also { effects[step] = it }
    }
}

/**
 * Colors of radio coverage: one hue per WLAN channel, so equal colors mean "same channel"; cell towers are neutral.
 * Same-channel overlap is always drawn in [INTERFERENCE].
 */
object RadioStyles {
    const val INTERFERENCE = 0xFFD7263D.toInt()
    private const val TOWER = 0xFF6B7785.toInt()

    fun color(n: Node): Int = when (n.channel) {
        0 -> TOWER
        1 -> 0xFF2E86AB.toInt()
        6 -> 0xFF8E6CC0.toInt()
        11 -> 0xFF1FA39A.toInt()
        36 -> 0xFF4F6BD8.toInt()
        40 -> 0xFFB5569F.toInt()
        44 -> 0xFF3C9D5D.toInt()
        else -> 0xFFC98A2B.toInt()
    }
}

/** Colors of incidents ([com.mininetworks.game.game.Incident]), shared by all styles. */
object IncidentStyles {
    /** Announcement: pulse rings and the countdown until the incident strikes. */
    const val WARNING = 0xFFF2A516.toInt()
    /** A cut cable and the countdown until it repairs itself. */
    const val CUT = 0xFFD7263D.toInt()
    const val EXCAVATOR = 0xFFF2B705.toInt()
    const val EXCAVATOR_DARK = 0xFF3A3F47.toInt()
    const val DIRT = 0xFF8C6A48.toInt()
    /** Body of a router or access point without power. */
    const val DARK_BODY = 0xFF59616B.toInt()

    /** 0..1 phase of the announcement pulse: one ring per second. */
    fun pulse(time: Float) = time - kotlin.math.floor(time)

    /** A lightning bolt of half-height [r] around ([x], [y]), into a fresh path. */
    fun bolt(path: Path, x: Float, y: Float, r: Float): Path {
        path.reset()
        path.moveTo(x + r * 0.2f, y - r)
        path.lineTo(x - r * 0.55f, y + r * 0.12f)
        path.lineTo(x - r * 0.02f, y + r * 0.12f)
        path.lineTo(x - r * 0.25f, y + r)
        path.lineTo(x + r * 0.55f, y - r * 0.18f)
        path.lineTo(x + r * 0.04f, y - r * 0.18f)
        path.close()
        return path
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
            Device.CAMERA -> {
                // Security camera on a wall arm: tilted body, lens at the front, recording light on top.
                line.strokeWidth = s * 0.16f
                canvas.drawLine(x + s * 0.75f, y + s * 0.8f, x + s * 0.75f, y + s * 0.1f, line)
                canvas.drawLine(x + s * 0.75f, y + s * 0.1f, x + s * 0.35f, y - s * 0.05f, line)
                canvas.save()
                canvas.rotate(-15f, x, y - s * 0.3f)
                box(x - s * 0.95f, y - s * 0.62f, x + s * 0.55f, y + s * 0.02f, s * 0.14f, 0xFFFFFFFF.toInt(), s)
                box(x - s * 1.15f, y - s * 0.52f, x - s * 0.85f, y - s * 0.08f, s * 0.06f, screen, s * 0.8f)
                canvas.restore()
                body.color = 0xFFE4572E.toInt()
                canvas.drawCircle(x + s * 0.2f, y - s * 0.62f, s * 0.1f, body)
            }
            Device.SMART_HOME -> {
                // Hub shaped like a house with a status light in the door.
                path.reset()
                path.moveTo(x, y - s * 0.95f); path.lineTo(x + s * 0.95f, y - s * 0.1f); path.lineTo(x + s * 0.72f, y - s * 0.1f)
                path.lineTo(x + s * 0.72f, y + s * 0.85f); path.lineTo(x - s * 0.72f, y + s * 0.85f); path.lineTo(x - s * 0.72f, y - s * 0.1f)
                path.lineTo(x - s * 0.95f, y - s * 0.1f); path.close()
                body.color = 0xFFFFFFFF.toInt(); canvas.drawPath(path, body)
                line.strokeWidth = s * 0.16f; canvas.drawPath(path, line)
                line.strokeWidth = s * 0.12f
                for (i in 1..2) {
                    val r = s * 0.2f * i
                    rect.set(x - r, y + s * 0.15f - r, x + r, y + s * 0.15f + r)
                    canvas.drawArc(rect, -135f, 90f, false, line)
                }
                body.color = 0xFF3BA55C.toInt()
                canvas.drawCircle(x, y + s * 0.5f, s * 0.12f, body)
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

    /** Data center (server tier 4) spanning 2×2 cells around ([x], [y]): rows of racks with LEDs and a service badge. */
    fun dataCenter(c: Canvas, service: Service, busy: Boolean, x: Float, y: Float, s: Float, time: Float) {
        canvas = c
        val col = ServiceColors.of(service)
        box(x - s, y - s * 0.8f, x + s, y + s * 0.85f, s * 0.12f, col, s * 0.35f)
        for (row in 0 until 4) {
            val ry = y - s * 0.55f + row * s * 0.36f
            rect.set(x - s * 0.8f, ry - s * 0.1f, x + s * 0.55f, ry + s * 0.1f)
            body.color = col.shade(-0.3f); canvas.drawRect(rect, body)
            for (i in 0 until 4) {
                body.color = when {
                    busy -> 0xFFFF5A5A.toInt()
                    sin(time * 4f + i * 1.7f + row * 2.3f) > 0f -> 0xFF7CF29A.toInt()
                    else -> col.shade(-0.45f)
                }
                canvas.drawCircle(x - s * 0.62f + i * s * 0.3f, ry, s * 0.045f, body)
            }
        }
        body.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(x + s * 0.85f, y - s * 0.8f, s * 0.26f, body)
        line.strokeWidth = s * 0.05f; canvas.drawCircle(x + s * 0.85f, y - s * 0.8f, s * 0.26f, line)
        body.color = col
        Shapes.draw(canvas, service.shape, x + s * 0.85f, y - s * 0.8f + if (service.shape == Shape.TRIANGLE) s * 0.02f else 0f, s * 0.13f, body)
    }

    /**
     * WLAN access point: a flat puck with Wi-Fi arcs above it; the LED shows the channel [color].
     * A [dark] one (power outage) has no arcs and a grey body.
     */
    fun accessPoint(c: Canvas, x: Float, y: Float, s: Float, color: Int, time: Float, dark: Boolean = false) {
        canvas = c
        line.color = ink
        line.strokeWidth = s * 0.14f
        if (!dark) for (i in 1..3) {
            val r = s * (0.3f + 0.28f * i)
            rect.set(x - r, y - s * 0.15f - r, x + r, y - s * 0.15f + r)
            line.alpha = if (sin(time * 3f - i * 0.9f) > -0.3f) 255 else 90
            canvas.drawArc(rect, -135f, 90f, false, line)
        }
        line.alpha = 255
        box(x - s * 0.9f, y - s * 0.1f, x + s * 0.9f, y + s * 0.45f, s * 0.22f, if (dark) IncidentStyles.DARK_BODY else 0xFFFFFFFF.toInt(), s)
        body.color = if (dark) ink else color
        canvas.drawCircle(x, y + s * 0.17f, s * 0.11f, body)
    }

    /** Cell tower: a lattice mast with antenna panels and a blinking warning light on top. */
    fun cellTower(c: Canvas, x: Float, y: Float, s: Float, time: Float) {
        canvas = c
        line.color = ink
        line.strokeWidth = s * 0.12f
        val top = y - s * 1.1f
        val foot = y + s * 0.9f
        canvas.drawLine(x - s * 0.55f, foot, x, top, line)
        canvas.drawLine(x + s * 0.55f, foot, x, top, line)
        line.strokeWidth = s * 0.07f
        for (i in 1..3) {
            val f = i / 4f
            val yy = foot + (top - foot) * f
            val w = s * 0.55f * (1f - f)
            canvas.drawLine(x - w, yy, x + w, yy, line)
            val yn = foot + (top - foot) * (f - 0.25f)
            val wn = s * 0.55f * (1.25f - f)
            canvas.drawLine(x - wn, yn, x + w, yy, line)
        }
        for (side in listOf(-1f, 1f)) box(x + side * s * 0.32f - s * 0.12f, top + s * 0.2f, x + side * s * 0.32f + s * 0.12f, top + s * 0.62f, s * 0.05f, 0xFFFFFFFF.toInt(), s * 0.6f)
        body.color = if (sin(time * 2.5f) > 0f) 0xFFE4572E.toInt() else 0xFF8A3A2A.toInt()
        canvas.drawCircle(x, top, s * 0.13f, body)
    }

    /**
     * Router with blinking LEDs. While [warning] (a power outage is announced) the LEDs flicker amber; a [dark] router
     * (outage in effect) has a grey body and no light.
     */
    fun router(c: Canvas, x: Float, y: Float, s: Float, time: Float, warning: Boolean = false, dark: Boolean = false) {
        canvas = c
        line.color = ink
        line.strokeWidth = s * 0.14f
        canvas.drawLine(x - s * 0.5f, y - s * 0.2f, x - s * 0.7f, y - s * 0.9f, line)
        canvas.drawLine(x + s * 0.5f, y - s * 0.2f, x + s * 0.7f, y - s * 0.9f, line)
        box(x - s * 0.9f, y - s * 0.25f, x + s * 0.9f, y + s * 0.45f, s * 0.15f, if (dark) IncidentStyles.DARK_BODY else 0xFFFFFFFF.toInt(), s)
        for (i in 0 until 3) {
            body.color = when {
                dark -> ink
                warning -> if (sin(time * 17f + i * 2.1f) > 0f) IncidentStyles.WARNING else 0xFFB9C2CC.toInt()
                sin(time * 6f + i * 1.3f) > 0f -> 0xFF3BA55C.toInt()
                else -> 0xFFB9C2CC.toInt()
            }
            canvas.drawCircle(x - s * 0.4f + i * s * 0.4f, y + s * 0.1f, s * 0.09f, body)
        }
    }

    /**
     * Side view of a small excavator facing right, for the flat style: tracks, cab with window, boom and bucket.
     * [dig] 0..1 lowers the bucket from raised (0) to the ground (1).
     */
    fun excavator(c: Canvas, x: Float, y: Float, s: Float, dig: Float) {
        canvas = c
        line.color = ink
        // Tracks: a rounded belt with three wheels.
        box(x - s, y + s * 0.45f, x + s * 0.35f, y + s * 0.85f, s * 0.2f, IncidentStyles.EXCAVATOR_DARK, s * 0.7f)
        for (i in 0 until 3) {
            body.color = 0xFF9AA3AD.toInt()
            canvas.drawCircle(x - s * 0.78f + i * s * 0.45f, y + s * 0.65f, s * 0.11f, body)
        }
        // Cab and engine.
        box(x - s * 0.95f, y + s * 0.05f, x + s * 0.25f, y + s * 0.45f, s * 0.08f, IncidentStyles.EXCAVATOR, s * 0.7f)
        box(x - s * 0.55f, y - s * 0.6f, x + s * 0.2f, y + s * 0.1f, s * 0.1f, IncidentStyles.EXCAVATOR, s * 0.7f)
        rect.set(x - s * 0.38f, y - s * 0.45f, x + s * 0.08f, y - s * 0.08f)
        body.color = screen
        canvas.drawRect(rect, body)
        // Boom up to the elbow, stick down to the bucket.
        val ex = x + s * 0.75f; val ey = y - s * 0.75f
        val bx = x + s * 1.25f; val by = y - s * 0.2f + dig * s * 0.85f
        line.strokeWidth = s * 0.26f
        canvas.drawLine(x + s * 0.1f, y + s * 0.05f, ex, ey, line)
        canvas.drawLine(ex, ey, bx, by - s * 0.15f, line)
        line.color = IncidentStyles.EXCAVATOR
        line.strokeWidth = s * 0.14f
        canvas.drawLine(x + s * 0.1f, y + s * 0.05f, ex, ey, line)
        canvas.drawLine(ex, ey, bx, by - s * 0.15f, line)
        line.color = ink
        path.reset()
        path.moveTo(bx - s * 0.2f, by - s * 0.2f); path.lineTo(bx + s * 0.25f, by - s * 0.2f)
        path.lineTo(bx + s * 0.1f, by + s * 0.2f); path.lineTo(bx - s * 0.25f, by + s * 0.12f); path.close()
        body.color = IncidentStyles.EXCAVATOR_DARK
        canvas.drawPath(path, body)
    }
}
