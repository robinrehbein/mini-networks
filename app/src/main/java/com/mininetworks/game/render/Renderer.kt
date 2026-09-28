package com.mininetworks.game.render

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.mininetworks.game.game.Cable
import com.mininetworks.game.game.CableLayout
import com.mininetworks.game.game.CableSkin
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.CellRect
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Geometry
import com.mininetworks.game.game.Node
import com.mininetworks.game.game.Packet
import com.mininetworks.game.game.RouteProblem
import com.mininetworks.game.game.Service
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
    /** A second line under [label]: what the cable means for the device's services (ping, bandwidth), if anything. */
    val detail: String? = null,
    /** True if [detail] is a warning (too narrow, ping too high); drawn in the alarm color. */
    val detailWarning: Boolean = false,
    /** The finger's path in world space, oldest first, drawn as a fading touch trail behind the pointer. */
    val trail: List<Vec2> = emptyList(),
    /** The laid cable this drag re-routes, drawn as a dashed ghost under the preview; null for a new cable. */
    val replaces: Cable? = null,
    /** World points where grab handles ([DragJuice.handle]) are drawn on the preview, e.g. the end that stays. */
    val handles: List<Vec2> = emptyList(),
    /** Where the bubble's tail points; the end of [layout] if null. */
    val labelAt: Vec2? = null,
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

    /** Pixels per dp of the screen, for sizes that must stay readable at any zoom; set by the view, 1 by default. */
    var density: Float

    /**
     * Smallest zoom the automatic framing ([layout], [fitArea], [onAreaChanged]) goes to, so devices and requests stay
     * readable on a phone; a bigger area than fits is then centred and the player pans. Pinching zooms out further.
     */
    val readableScale: Float

    /** World units on the ground plane -> this style's map units (turned by the camera's angle, before zoom and pan). */
    fun toMap(p: Vec2): Vec2 = camera.worldToMap(p.x, p.y)

    /** Map units -> world units on the ground plane; inverse of [toMap]. */
    fun fromMap(mx: Float, my: Float): Vec2 = camera.mapToWorld(mx, my)

    /**
     * Map-space box that shows [area] with everything drawn on it (buildings, queues, board edge) when the world is
     * turned by [angle] degrees: the box around the turned area plus room for what stands up from it.
     */
    fun mapBounds(area: CellRect, angle: Float = camera.angle): MapRect

    /** World units -> screen pixels, on the ground plane. */
    fun toScreen(p: Vec2): Vec2 = camera.worldToScreen(p)

    /** Screen pixels -> world units, on the ground plane. The inverse of [toScreen] at any zoom, pan and angle. */
    fun toWorld(sx: Float, sy: Float): Vec2 = camera.screenToWorld(sx, sy)

    /**
     * Turns the view by [degrees] around the screen point ([pivotX], [pivotY]) and renews the angle-dependent pan
     * limit. Tapping and dragging keep hitting the same cells, as both go through [toScreen] and [toWorld].
     */
    fun rotateBy(degrees: Float, pivotX: Float, pivotY: Float, world: World) {
        camera.rotateBy(degrees, pivotX, pivotY)
        updateLimits(world)
    }

    /** Advances the camera's animations by [dt] seconds, renewing the pan limit while the angle changes. */
    fun stepCamera(dt: Float, world: World) {
        val before = camera.angle
        camera.step(dt)
        if (camera.angle != before) updateLimits(world)
    }

    /** Sets the view size and fits the camera to the world's unlocked area. */
    fun layout(width: Int, height: Int, world: World, insets: ViewInsets = ViewInsets.NONE) {
        camera.setViewport(width, height, insets)
        fitArea(world, animate = false)
    }

    /**
     * Zooms so the unlocked area fills the view (not below [readableScale]), e.g. on a double tap. In a portrait view
     * the iso map (twice as wide as high) would only fill a band in the middle; there the framing ([frame]) zooms in
     * as far as the height allows while every device, server and cable stays on screen, so only empty ground is cut.
     */
    fun fitArea(world: World, animate: Boolean) {
        updateLimits(world)
        camera.fit(frame(world), animate, atLeast = framingMinScale(world))
    }

    /** Call when the unlocked area grew: widens the limits and, unless the player moved the view, follows the area. */
    fun onAreaChanged(world: World) {
        updateLimits(world)
        if (camera.followsArea) camera.fit(frame(world), animate = true, atLeast = framingMinScale(world))
    }

    /**
     * Call when nodes or cables changed: in a portrait view that [frame]s on the built content, glides out (unless the
     * player moved the view) when something new lies outside the view, e.g. a device on an empty corner of the map.
     */
    fun onContentChanged(world: World) {
        if (!camera.followsArea) return
        val content = contentBounds(world) ?: return
        if (!camera.shows(content)) camera.fit(frame(world), animate = true, atLeast = framingMinScale(world))
    }

    /**
     * The zoom the automatic framing does not go below: [readableScale]; but in a portrait view, whose width is the
     * scarce direction, never so far in that a built node would be pushed off the side of the screen.
     */
    fun framingMinScale(world: World): Float {
        if (!camera.isTall) return readableScale
        val content = contentBounds(world) ?: return readableScale
        return minOf(readableScale, camera.fitScale(content))
    }

    /**
     * What the automatic framing shows: the unlocked area while nothing is built; in a portrait view its full height,
     * but across only the width of what is built on it ([contentBounds]), so the view zooms in towards the height
     * without cutting off any node; in landscape every node plus the middle [CORE_FRACTION] of the area. Map corners
     * without nodes may lie outside; the player pans there.
     */
    fun frame(world: World): MapRect {
        val area = mapBounds(world.unlocked)
        val c = contentBounds(world) ?: return area
        // Portrait: as tall as the area, but centred on the built network, so it sits in the middle of the screen
        // instead of wherever the middle of the area happens to be.
        if (camera.isTall) return MapRect(c.left, c.centerY - area.height / 2f, c.right, c.centerY + area.height / 2f)
        // Landscape: the built network plus the middle of the area. The iso area is a diamond, so fitting its whole box
        // left half the screen as empty corners and locked ground; its empty tips may now lie outside (docs/TOP100.md B4).
        val hx = area.width * CORE_FRACTION / 2f
        val hy = area.height * CORE_FRACTION / 2f
        return MapRect(
            minOf(c.left, area.centerX - hx), minOf(c.top, area.centerY - hy),
            maxOf(c.right, area.centerX + hx), maxOf(c.bottom, area.centerY + hy),
        )
    }

    /**
     * Map-space box around every node (with its building, badges and queue, as [mapBounds] draws them for its cells)
     * and every cable bend on the unlocked area, plus [CONTENT_MARGIN], within the area's [mapBounds]; null when nothing is there.
     */
    fun contentBounds(world: World): MapRect? {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (n in world.nodes) for (cell in n.footprint) {
            if (cell !in world.unlocked) continue
            val m = mapBounds(CellRect(cell.x, cell.y, cell.x + 1, cell.y + 1))
            l = minOf(l, m.left); t = minOf(t, m.top); r = maxOf(r, m.right); b = maxOf(b, m.bottom)
        }
        for (c in world.cables) for (p in c.layout.waypoints) {
            val m = toMap(p)
            l = minOf(l, m.x); t = minOf(t, m.y); r = maxOf(r, m.x); b = maxOf(b, m.y)
        }
        if (l > r) return null
        val pad = CONTENT_MARGIN * (mapBounds(CellRect(0, 0, 1, 1)).width)
        val a = mapBounds(world.unlocked)
        return MapRect(maxOf(a.left, l - pad), maxOf(a.top, t - pad), minOf(a.right, r + pad), minOf(a.bottom, b + pad))
    }

    /**
     * Zoom range: out to the whole grid (and a bit more) at any angle, in until about [ZOOM_IN_COLS] × [ZOOM_IN_ROWS]
     * cells fill the view. The range does not depend on the current angle, so turning the map never zooms it. The
     * screen centre stays over the grid as it is turned now.
     */
    fun updateLimits(world: World) {
        var out = Float.MAX_VALUE
        for (a in LIMIT_ANGLES) out = minOf(out, camera.fitScale(mapBounds(world.bounds, a)), camera.fitScale(mapBounds(world.unlocked, a)))
        camera.setZoomRange(out * ZOOM_OUT_SLACK, camera.fitScale(mapBounds(CellRect(0, 0, ZOOM_IN_COLS, ZOOM_IN_ROWS), 0f)))
        camera.panBounds = mapBounds(world.bounds)
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
        /** Angles the zoom-out limit is worked out for; the widest one counts. */
        val LIMIT_ANGLES = floatArrayOf(0f, 30f, 45f, 60f, 90f, 120f, 135f, 150f)
        const val ZOOM_OUT_SLACK = 0.9f
        const val ZOOM_IN_COLS = 4
        const val ZOOM_IN_ROWS = 3
        /** Zoom factor of [focusOn]. */
        const val FOCUS_ZOOM = 2f
        /** Room around [contentBounds], in widths of one drawn cell. */
        const val CONTENT_MARGIN = 0.15f
        /** Share of the area's width and height a landscape [frame] always shows around its middle. */
        const val CORE_FRACTION = 0.45f
    }
}

/**
 * Marks for requests that cannot leave ([World.routeProblem]): a red badge with a speedometer when the route is too slow for
 * the service's ping limit, and with two wedges squeezing together when no link on the way is wide enough.
 * [NO_ROUTE][RouteProblem.NO_ROUTE] gets none: an unconnected device already shows that. Shared by all styles.
 */
object ProblemBadges {
    const val ALARM = 0xFFD7263D.toInt()
    private val bodyP = fill(ALARM)
    private val inkP = stroke(0xFFFFFFFF.toInt())
    private val inkFill = fill(0xFFFFFFFF.toInt())
    private val shadowP = fill(0x40000000)
    private val path = Path()

    /** True for the problems that get a badge. */
    fun shows(p: RouteProblem?) = p == RouteProblem.PING_TOO_HIGH || p == RouteProblem.TOO_NARROW

    /** The first problem with a badge among the first [max] waiting requests of client [n], or null. */
    fun of(world: World, n: Node, max: Int = 8): RouteProblem? {
        for (i in 0 until minOf(n.pending.size, max)) world.routeProblem(n, n.pending[i]).let { if (shows(it)) return it }
        return null
    }

    /** A badge of radius [r] pixels around ([x], [y]). */
    fun draw(canvas: Canvas, problem: RouteProblem, x: Float, y: Float, r: Float) {
        // A white plate and a soft shadow lift the badge off the busy map, so it reads at phone size.
        canvas.drawCircle(x, y + r * 0.18f, r * 1.28f, shadowP)
        canvas.drawCircle(x, y, r * 1.28f, inkFill)
        canvas.drawCircle(x, y, r, bodyP)
        inkP.strokeWidth = r * 0.16f
        canvas.drawCircle(x, y, r, inkP)
        when (problem) {
            // A speedometer, not a clock: a clock read as the overload timer (playtest).
            RouteProblem.PING_TOO_HIGH -> {
                val k = r * 1.45f / 24f
                canvas.save()
                canvas.translate(x - 12f * k, y - 12.5f * k)
                canvas.scale(k, k)
                canvas.drawPath(gauge, inkFill)
                canvas.restore()
            }
            RouteProblem.TOO_NARROW -> for (side in SIDES) {
                path.reset()
                path.moveTo(x + side * r * 0.62f, y - r * 0.45f)
                path.lineTo(x + side * r * 0.1f, y)
                path.lineTo(x + side * r * 0.62f, y + r * 0.45f)
                path.close()
                canvas.drawPath(path, inkFill)
            }
            RouteProblem.NO_ROUTE -> Unit
        }
    }

    private val SIDES = floatArrayOf(-1f, 1f)

    /** Material Icons "speed" (Apache License 2.0, docs/licenses-material-icons.txt), in its 24-unit box. */
    private val gauge: Path = androidx.core.graphics.PathParser.createPathFromPathData(
        "M19.46 10a1 1 0 0 0-.07 1 7.55 7.55 0 0 1 .52 1.81 8 8 0 0 1-.69 4.73 1 1 0 0 1-.89.53H5.68a1 1 0 0 1-.89-.54A8 8 0 0 1 13 6.06a7.69 7.69 0 0 1 2.11.56 1 1 0 0 0 1-.07 1 1 0 0 0-.17-1.76A10 10 0 0 0 3.35 19a2 2 0 0 0 1.72 1h13.85a2 2 0 0 0 1.74-1 10 10 0 0 0 .55-8.89 1 1 0 0 0-1.75-.11z" +
            "M10.59 12.59a2 2 0 0 0 2.83 2.83l5.66-8.49z",
    )
}

/**
 * The box in map units around [area] turned by [angle] degrees and flattened by [projection], plus [left], [top],
 * [right] and [bottom] map units of room for what is drawn on it. Shared by the styles' [Renderer.mapBounds].
 */
fun turnedBounds(area: CellRect, angle: Float, projection: MapProjection, left: Float, top: Float, right: Float, bottom: Float): MapRect {
    val r = Math.toRadians(angle.toDouble())
    var c = cos(r).toFloat(); var s = sin(r).toFloat()
    if (angle % 90f == 0f) { c = kotlin.math.round(c); s = kotlin.math.round(s) }
    var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var rr = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
    for (k in 0 until 4) {
        val x = (if (k == 1 || k == 2) area.right else area.left).toFloat()
        val y = (if (k >= 2) area.bottom else area.top).toFloat()
        val tx = c * x - s * y; val ty = s * x + c * y
        val mx = projection.projectX(tx, ty); val my = projection.projectY(tx, ty)
        l = minOf(l, mx); t = minOf(t, my); rr = maxOf(rr, mx); b = maxOf(b, my)
    }
    return MapRect(l - left, t - top, rr + right, b + bottom)
}

/**
 * Timing of the small effects shared by all styles (docs/TOP100.md B3): a cable grows along its path when laid and
 * clicks in with a ring at both ends, a glint runs along a cable that was upgraded, rings and sparkles rise from an
 * upgraded server or access point, and a delivered response pops above its device with a little burst.
 */
object Juice {
    /** Seconds a freshly laid cable of [length] cells takes to grow. */
    fun layDuration(length: Float) = (0.15f + length * 0.06f).coerceAtMost(0.6f)

    /** 0..1: how much of a cable of [length] laid at [builtAt] is drawn at [time], easing out. */
    fun growth(time: Float, builtAt: Float, length: Float): Float {
        val t = ((time - builtAt) / layDuration(length)).coerceIn(0f, 1f)
        return 1f - (1f - t) * (1f - t)
    }

    /** 0..1 progress of the click-in ring once the cable has grown, or a value outside while none shows. */
    fun landing(time: Float, builtAt: Float, length: Float) = (time - builtAt - layDuration(length)) / LANDING_SECONDS

    /** 0..1 progress of an upgrade effect that started at [at], or a value outside while none shows. */
    fun upgrade(time: Float, at: Float, seconds: Float = UPGRADE_SECONDS) = (time - at) / seconds

    const val LANDING_SECONDS = 0.45f
    const val UPGRADE_SECONDS = 0.9f
    const val CABLE_GLINT_SECONDS = 0.7f

    /** A four-pointed sparkle of radius [r] around ([x], [y]). */
    fun sparkle(canvas: Canvas, path: Path, x: Float, y: Float, r: Float, paint: Paint) {
        val k = r * 0.28f
        path.reset()
        path.moveTo(x, y - r); path.lineTo(x + k, y - k); path.lineTo(x + r, y); path.lineTo(x + k, y + k)
        path.lineTo(x, y + r); path.lineTo(x - k, y + k); path.lineTo(x - r, y); path.lineTo(x - k, y - k); path.close()
        canvas.drawPath(path, paint)
    }

    /** Alpha 0..255 that fades out over progress [t] (0..1), quicker at the end. */
    fun fade(t: Float) = ((1f - t * t).coerceIn(0f, 1f) * 255f).toInt()

    /** Sparkles rising around ([x], [y]) at progress [t]: [n] of them on a ring of [spread] px, [rise] px up at the end. */
    fun sparkles(canvas: Canvas, path: Path, x: Float, y: Float, t: Float, spread: Float, rise: Float, size: Float, color: Int, paint: Paint, n: Int = 6) {
        if (t !in 0f..1f) return
        paint.color = color and 0xFFFFFF or (fade(t) shl 24)
        for (i in 0 until n) {
            val a = (i * 2.0 * Math.PI / n + 0.4).toFloat()
            val d = spread * (0.5f + 0.5f * t)
            sparkle(canvas, path, x + cos(a) * d, y + sin(a) * d * 0.5f - rise * t, size * (1f - 0.5f * t), paint)
        }
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

/**
 * Look of each cable technology: outer color, width (in world units) and an optional inner core line. The width grows
 * with the capacity and stays the same in every cable skin ([Cosmetic.skin], docs/TOP100.md C5); a skin only changes
 * colors, so the technologies stay apart by width and brightness and nothing about the rules changes.
 */
object CableStyles {
    class Style(val color: Int, val width: Float, val core: Int?, val coreWidth: Float)

    private const val W_ISDN = 0.08f
    private const val W_DSL = 0.13f
    private const val W_COAX = 0.17f
    private const val W_FIBER = 0.18f
    private const val CORE_COAX = 0.045f
    private const val CORE_FIBER = 0.05f

    /** ISDN, DSL, coax and fiber of one skin, in [CableType] order. */
    private fun skin(isdn: Int, dsl: Int, dslCore: Int?, coax: Int, coaxCore: Int, fiber: Int, fiberCore: Int) = listOf(
        Style(isdn, W_ISDN, null, 0f),
        Style(dsl, W_DSL, dslCore, if (dslCore != null) 0.035f else 0f),
        Style(coax, W_COAX, coaxCore, CORE_COAX),
        Style(fiber, W_FIBER, fiberCore, CORE_FIBER),
    )

    /**
     * The default skin gives every technology its own hue, not just another grey (slate-grey ISDN, dark enough to hold on pale ground, teal DSL,
     * wine-red coax, orange fiber), so the HUD chips and the map read apart at a glance. DSL and coax stay dark so
     * packets show on them; a dark outline and a white halo ([IsoRenderer]) lift them off the green grass.
     */
    private val CLASSIC = skin(0xFF8F9CAE.toInt(), 0xFF134E48.toInt(), null, 0xFF6A1F3F.toInt(), 0xFFF2B8CE.toInt(), 0xFFF28C28.toInt(), 0xFFFFE2B8.toInt())
    private val COPPER = skin(0xFFC4A07E.toInt(), 0xFF5E3620.toInt(), null, 0xFF3A2519.toInt(), 0xFFD08A52.toInt(), 0xFFD9A441.toInt(), 0xFFFFF0C2.toInt())
    private val NEON = skin(0xFF7ED3E6.toInt(), 0xFF262A50.toInt(), 0xFF8F6BFF.toInt(), 0xFF16181F.toInt(), 0xFFFF4FA3.toInt(), 0xFF3EE68A.toInt(), 0xFFE8FFF1.toInt())
    private val PASTEL = skin(0xFFB9C3D3.toInt(), 0xFF4A4C48.toInt(), 0xFFC9B8E8.toInt(), 0xFF1C191E.toInt(), 0xFFE9C6D6.toInt(), 0xFFF3A6B8.toInt(), 0xFFFFE6EE.toInt())
    private val GOLD = skin(0xFFC9BC92.toInt(), 0xFF564B36.toInt(), null, 0xFF24211B.toInt(), 0xFFD4AF37.toInt(), 0xFFE8B923.toInt(), 0xFFFFF3C4.toInt())

    fun of(t: CableType) = of(Cosmetic.skin, t)

    fun of(skin: CableSkin, t: CableType): Style = when (skin) {
        CableSkin.CLASSIC -> CLASSIC
        CableSkin.COPPER -> COPPER
        CableSkin.NEON -> NEON
        CableSkin.PASTEL -> PASTEL
        CableSkin.GOLD -> GOLD
    }[t.ordinal]
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
    /** Cell towers in a deep teal, apart from the Wi-Fi channel hues (judge panel: grey coverage read as a smudge). */
    private const val TOWER = 0xFF0E8C7F.toInt()

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
    /** The network's slate blue, shared with the iso router block. */
    private val ROUTER_BODY = 0xFF5C7FA8.toInt()
    /** A lit screen in sky blue with a glare, so devices read as glowing gadgets rather than line icons (judge panel). */
    private val screen = 0xFF4FA8E8.toInt()
    private val glare = 0x66FFFFFF
    /** The side of a device, offset down-right under its face: a little volume to match the extruded buildings. */
    private val side = 0xFF8E9AA8.toInt()
    /**
     * Where a device's side shows, as a share of its half size: down right on a flat map; the iso map turns its
     * devices to the viewer's left front wall and sets this back and up, so the side reads as the device's thickness.
     */
    var depthX = 0.12f
    var depthY = 0.16f
    private val body = fill(0xFFFFFFFF.toInt())
    private val line = stroke(ink)
    private val solid = fill(ink)
    private val rect = RectF()
    private val path = Path()
    private val shine = Path()

    private fun box(l: Float, t: Float, r: Float, b: Float, radius: Float, fillColor: Int = 0xFFFFFFFF.toInt(), s: Float, depth: Boolean = true) {
        if (depth) {
            rect.set(l + s * depthX, t + s * depthY, r + s * depthX, b + s * depthY)
            body.color = side
            canvas.drawRoundRect(rect, radius, radius, body)
        }
        rect.set(l, t, r, b)
        body.color = fillColor
        canvas.drawRoundRect(rect, radius, radius, body)
        if (fillColor == screen) glareOn(l, t, r, b)
        line.strokeWidth = s * 0.16f
        canvas.drawRoundRect(rect, radius, radius, line)
    }

    /** A diagonal glare over the upper left of the screen ([l], [t], [r], [b]). */
    private fun glareOn(l: Float, t: Float, r: Float, b: Float) {
        val w = r - l; val h = b - t
        shine.reset()
        shine.moveTo(l + w * 0.18f, t); shine.lineTo(l + w * 0.5f, t)
        shine.lineTo(l + w * 0.08f, t + h); shine.lineTo(l - w * 0.24f, t + h); shine.close()
        canvas.save()
        canvas.clipRect(l, t, r, b)
        body.color = glare
        canvas.drawPath(shine, body)
        canvas.restore()
    }

    /** [p] offset down-right in the side colour, under a shape drawn from a path. */
    private fun pathDepth(p: Path, s: Float) {
        canvas.save()
        canvas.translate(s * depthX, s * depthY)
        body.color = side
        canvas.drawPath(p, body)
        canvas.restore()
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
                // Desk phone like the ☎ sign: a wide base with a 3×2 keypad and the handset resting on two cradle
                // horns (the old slanted base with a bar on top read as a flat iron).
                box(x - s * 0.85f, y, x + s * 0.85f, y + s * 0.85f, s * 0.22f, 0xFFFFFFFF.toInt(), s)
                for (row in 0 until 2) for (col in 0 until 3) {
                    canvas.drawCircle(x - s * 0.34f + col * s * 0.34f, y + s * 0.3f + row * s * 0.28f, s * 0.085f, solid)
                }
                // Handset: a curved grip with a round ear and mouth piece, lifted clear of the base.
                line.strokeWidth = s * 0.26f
                rect.set(x - s * 0.72f, y - s * 0.78f, x + s * 0.72f, y + s * 0.1f)
                canvas.drawArc(rect, 200f, 140f, false, line)
                canvas.drawCircle(x - s * 0.7f, y - s * 0.3f, s * 0.2f, solid)
                canvas.drawCircle(x + s * 0.7f, y - s * 0.3f, s * 0.2f, solid)
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
                glareOn(x - s * 0.34f, y - s * 0.72f, x + s * 0.34f, y + s * 0.6f)
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
                glareOn(x - s * 0.72f, y - s * 0.5f, x + s * 0.62f, y + s * 0.5f)
                canvas.drawCircle(x + s * 0.82f, y, s * 0.06f, solid)
            }
            Device.WATCH -> {
                box(x - s * 0.32f, y - s, x + s * 0.32f, y + s, s * 0.1f, 0xFFB9C2CC.toInt(), s)
                box(x - s * 0.58f, y - s * 0.58f, x + s * 0.58f, y + s * 0.58f, s * 0.2f, screen, s, depth = false)
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
                box(x - s * 1.15f, y - s * 0.52f, x - s * 0.85f, y - s * 0.08f, s * 0.06f, screen, s * 0.8f, depth = false)
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
                pathDepth(path, s)
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
        ServiceGlyphs.sign(canvas, service, x + s * 0.8f, top, s * 0.46f, ink)
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
        ServiceGlyphs.sign(canvas, service, x + s * 0.85f, y - s * 0.8f, s * 0.3f, ink)
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
        for (side in listOf(-1f, 1f)) box(x + side * s * 0.32f - s * 0.12f, top + s * 0.2f, x + side * s * 0.32f + s * 0.12f, top + s * 0.62f, s * 0.05f, 0xFFFFFFFF.toInt(), s * 0.6f, depth = false)
        body.color = if (sin(time * 2.5f) > 0f) 0xFFE4572E.toInt() else 0xFF8A3A2A.toInt()
        canvas.drawCircle(x, top, s * 0.13f, body)
    }

    /**
     * Router with blinking LEDs. While [warning] (a power outage is announced) the LEDs flicker amber; a [dark] router
     * (outage in effect) has a grey body and no light.
     */
    fun router(c: Canvas, x: Float, y: Float, s: Float, time: Float, warning: Boolean = false, dark: Boolean = false) {
        // Network gear like the rack server: a slate-blue unit with a dark port strip, two antennas and the network
        // sign, instead of the white gadget it once was (playtest: it read as an end device).
        canvas = c
        line.color = ink
        line.strokeWidth = s * 0.12f
        canvas.drawLine(x - s * 0.55f, y - s * 0.3f, x - s * 0.55f, y - s * 0.95f, line)
        canvas.drawLine(x + s * 0.2f, y - s * 0.3f, x + s * 0.2f, y - s * 0.95f, line)
        box(x - s * 0.9f, y - s * 0.3f, x + s * 0.9f, y + s * 0.55f, s * 0.14f, if (dark) IncidentStyles.DARK_BODY else ROUTER_BODY, s)
        rect.set(x - s * 0.7f, y - s * 0.05f, x + s * 0.7f, y + s * 0.3f)
        body.color = 0xFF3A4452.toInt(); canvas.drawRect(rect, body)
        for (i in 0 until 4) {
            body.color = when {
                dark -> 0xFF56606C.toInt()
                warning -> if (sin(time * 17f + i * 2.1f) > 0f) IncidentStyles.WARNING else 0xFF56606C.toInt()
                sin(time * 6f + i * 1.3f) > -0.1f -> 0xFF7CF29A.toInt()
                else -> 0xFF56606C.toInt()
            }
            canvas.drawCircle(x - s * 0.48f + i * s * 0.32f, y + s * 0.125f, s * 0.08f, body)
        }
        ServiceGlyphs.networkSign(canvas, x + s * 0.8f, y - s * 0.3f, s * 0.42f, ROUTER_BODY, ink)
    }

    /**
     * Side view of a small excavator facing right, for the flat style: tracks, cab with window, boom and bucket.
     * [dig] 0..1 lowers the bucket from raised (0) to the ground (1).
     */
    fun excavator(c: Canvas, x: Float, y: Float, s: Float, dig: Float) {
        canvas = c
        line.color = ink
        // Tracks: a rounded belt with three wheels.
        box(x - s, y + s * 0.45f, x + s * 0.35f, y + s * 0.85f, s * 0.2f, IncidentStyles.EXCAVATOR_DARK, s * 0.7f, depth = false)
        for (i in 0 until 3) {
            body.color = 0xFF9AA3AD.toInt()
            canvas.drawCircle(x - s * 0.78f + i * s * 0.45f, y + s * 0.65f, s * 0.11f, body)
        }
        // Cab and engine.
        box(x - s * 0.95f, y + s * 0.05f, x + s * 0.25f, y + s * 0.45f, s * 0.08f, IncidentStyles.EXCAVATOR, s * 0.7f, depth = false)
        box(x - s * 0.55f, y - s * 0.6f, x + s * 0.2f, y + s * 0.1f, s * 0.1f, IncidentStyles.EXCAVATOR, s * 0.7f, depth = false)
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

/**
 * How a cable being dragged looks in every style: a soft glow in the cable's color under the preview line, a fading
 * touch trail along the finger's path with a contact ring at the pointer, and the price (or the reason it cannot be
 * built) in a speech bubble held well above the finger, so it never sits on a building's roof or under the thumb.
 */
object DragJuice {
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dotP = fill(0)
    private val ringP = stroke(0)
    private val bubbleP = fill(0xF7FFFFFF.toInt())
    private val shadowP = fill(0x2E000000)
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val tail = Path()
    private val rect = RectF()

    /** The glow under a preview line [path] of stroke width [width] pixels in [color]. */
    fun glow(canvas: Canvas, path: Path, color: Int, width: Float) {
        glowP.color = color and 0xFFFFFF or 0x38000000
        glowP.strokeWidth = width * 3.2f
        canvas.drawPath(path, glowP)
        glowP.color = color and 0xFFFFFF or 0x5C000000
        glowP.strokeWidth = width * 1.9f
        canvas.drawPath(path, glowP)
    }

    /**
     * A grab handle at screen point ([x], [y]): a white disc with a dark rim and a dot in the cable's [color], on a soft
     * shadow; the same at any zoom, so it reads as a control rather than part of the map.
     */
    fun handle(canvas: Canvas, x: Float, y: Float, color: Int, density: Float) {
        val r = HANDLE_DP * density
        dotP.color = 0x38000000
        canvas.drawCircle(x, y + 1.5f * density, r + 1f * density, dotP)
        dotP.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(x, y, r, dotP)
        ringP.color = HANDLE_RIM; ringP.strokeWidth = 2.5f * density
        canvas.drawCircle(x, y, r - 1.25f * density, ringP)
        dotP.color = color or 0xFF000000.toInt()
        canvas.drawCircle(x, y, r * 0.42f, dotP)
    }

    /** The cable a drag re-routes, along [path] (stroke [width] pixels): dashed white over it, so it reads as "moving away". */
    fun ghost(canvas: Canvas, path: Path, width: Float, density: Float) {
        ghostP.pathEffect = ghostDash.get(maxOf(width * 1.2f, 6f * density), 0.6f, 0f)
        ghostP.strokeWidth = width
        canvas.drawPath(path, ghostP)
    }

    /** Visible radius of a [handle]; its touch target is larger (see GameView). */
    const val HANDLE_DP = 11f
    private const val HANDLE_RIM = 0xFF2F3A34.toInt()
    private val ghostP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; color = 0xD9FFFFFF.toInt() }
    private val ghostDash = DashCache()

    /** The finger's trail through the screen points [xs]/[ys] (oldest first) and the contact ring at the last one. */
    fun trail(canvas: Canvas, xs: FloatArray, ys: FloatArray, color: Int, density: Float) {
        val n = xs.size
        if (n == 0) return
        for (i in 0 until n) {
            val t = (i + 1f) / n
            dotP.color = 0xFFFFFF or ((t * t * 150f).toInt() shl 24)
            canvas.drawCircle(xs[i], ys[i], (2.5f + 5.5f * t) * density, dotP)
        }
        val x = xs[n - 1]; val y = ys[n - 1]
        dotP.color = 0x8CFFFFFF.toInt()
        canvas.drawCircle(x, y, 17f * density, dotP)
        ringP.color = color; ringP.strokeWidth = 3f * density
        canvas.drawCircle(x, y, 17f * density, ringP)
        ringP.color = color and 0xFFFFFF or 0x55000000; ringP.strokeWidth = 2f * density
        canvas.drawCircle(x, y, 26f * density, ringP)
    }

    /**
     * The bubble with [label] (and a smaller [detail] line) whose tail points at ([x], [y]), its bottom [lift] pixels
     * above that point; [size] is the label's text size.
     */
    fun bubble(
        canvas: Canvas, label: String, detail: String?, x: Float, y: Float, lift: Float, size: Float, density: Float,
        labelColor: Int, detailColor: Int, accent: Int,
    ) {
        textP.textSize = size
        val w1 = textP.measureText(label)
        val detailSize = size * 0.78f
        textP.textSize = detailSize
        val w2 = detail?.let { textP.measureText(it) } ?: 0f
        val padX = size * 0.75f
        val padY = size * 0.5f
        val w = maxOf(w1, w2) + 2 * padX
        val h = size * 1.15f + (if (detail != null) detailSize * 1.25f else 0f) + 2 * padY
        val bottom = y - lift
        val cx = x.coerceIn(w / 2f + 8 * density, canvas.width - w / 2f - 8 * density)
        rect.set(cx - w / 2f, bottom - h, cx + w / 2f, bottom)
        val r = minOf(h / 2f, size * 0.9f)
        rect.offset(0f, 3f * density)
        canvas.drawRoundRect(rect, r, r, shadowP)
        rect.offset(0f, -3f * density)
        tail.reset()
        val tx = x.coerceIn(rect.left + r, rect.right - r)
        tail.moveTo(tx - size * 0.45f, bottom - 1f)
        tail.lineTo(tx, bottom + size * 0.5f)
        tail.lineTo(tx + size * 0.45f, bottom - 1f)
        tail.close()
        canvas.drawPath(tail, bubbleP)
        canvas.drawRoundRect(rect, r, r, bubbleP)
        ringP.color = accent; ringP.strokeWidth = 2.5f * density
        canvas.drawRoundRect(rect, r, r, ringP)
        textP.textSize = size; textP.color = labelColor
        val base = rect.top + padY + size * 0.9f
        canvas.drawText(label, cx, base, textP)
        detail?.let {
            textP.textSize = detailSize; textP.color = detailColor
            canvas.drawText(it, cx, base + detailSize * 1.25f, textP)
        }
    }
}
