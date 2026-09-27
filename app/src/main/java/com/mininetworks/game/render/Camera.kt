package com.mininetworks.game.render

import com.mininetworks.game.game.Vec2
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/** Axis-aligned rectangle in a renderer's map space. */
data class MapRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
}

/** Screen margins in pixels that fitting keeps free, e.g. for the HUD. */
data class ViewInsets(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f) {
    companion object {
        val NONE = ViewInsets()
    }
}

/**
 * A style's flat projection of the (already rotated) ground plane into map units. It must be linear (no offset), so
 * that rotating the world and projecting it commute with the camera's pan and zoom.
 */
interface MapProjection {
    fun projectX(x: Float, y: Float): Float
    fun projectY(x: Float, y: Float): Float
    fun unprojectX(mx: Float, my: Float): Float
    fun unprojectY(mx: Float, my: Float): Float

    /** Map units are world units (the flat overview). */
    object Identity : MapProjection {
        override fun projectX(x: Float, y: Float) = x
        override fun projectY(x: Float, y: Float) = y
        override fun unprojectX(mx: Float, my: Float) = mx
        override fun unprojectY(mx: Float, my: Float) = my
    }
}

/**
 * Zoom, pan and rotation shared by every style.
 *
 * World units on the ground plane are first turned by [angle] (degrees, clockwise on screen for the flat view) around
 * the world origin, then flattened by the style's [projection] into map units (for the iso style this is the iso
 * squash, so the squash always comes after the turn and any angle works), and map units become screen pixels as
 * `screen = viewCenter + (map - focus) * scale`. [focus] is the map point shown at the centre of the inset viewport.
 * Rotating ([rotateBy]) keeps the world point under the pivot where it is, so turning around the camera focus or
 * around the midpoint of two fingers is the same operation with a different pivot. Pure Kotlin, no Android types.
 */
class Camera {
    /** The style's flat projection; set once by the renderer that owns this camera. */
    var projection: MapProjection = MapProjection.Identity

    /** Rotation of the world in degrees, 0 until 360; 0 is north up (the original view). */
    var angle = 0f; private set
    /** Cosine and sine of [angle], kept in step with it for the renderers' hot paths. */
    var cosA = 1f; private set
    var sinA = 0f; private set

    var scale = 1f; private set
    var focusX = 0f; private set
    var focusY = 0f; private set

    var viewWidth = 0; private set
    var viewHeight = 0; private set
    var insets = ViewInsets.NONE; private set

    /** Zoom range in pixels per map unit; [zoomBy] stays inside it. */
    var minScale = 0f; private set
    var maxScale = Float.MAX_VALUE; private set

    /** The screen centre never leaves this map area when panning; null means no limit. */
    var panBounds: MapRect? = null
        set(value) { field = value; clampFocus() }

    /** True while the view follows the fitted area (set by [fit]); any zoom or pan by the player clears it. */
    var followsArea = true; private set

    private var animating = false
    private var targetScale = 1f
    private var targetX = 0f
    private var targetY = 0f
    private var rate = ANIM_RATE

    val isAnimating get() = animating

    /** Running rotation animation: the angle it eases to and the screen point it turns around. */
    private var rotationTarget: Float? = null
    private var rotPivotX = 0f
    private var rotPivotY = 0f

    /** True while the view eases to a snapped angle or back to north. */
    val isRotating get() = rotationTarget != null

    /** Screen centre of the inset viewport, where [focus] is shown. */
    val centerX get() = insets.left + (viewWidth - insets.left - insets.right) / 2f
    val centerY get() = insets.top + (viewHeight - insets.top - insets.bottom) / 2f

    fun setViewport(width: Int, height: Int, insets: ViewInsets = ViewInsets.NONE) {
        viewWidth = width
        viewHeight = height
        this.insets = insets
    }

    fun setZoomRange(min: Float, max: Float) {
        minScale = min
        maxScale = maxOf(min, max)
        scale = scale.coerceIn(minScale, maxScale)
        if (animating) targetScale = targetScale.coerceIn(minScale, maxScale)
    }

    fun toScreenX(mx: Float) = centerX + (mx - focusX) * scale
    fun toScreenY(my: Float) = centerY + (my - focusY) * scale
    fun toScreen(m: Vec2) = Vec2(toScreenX(m.x), toScreenY(m.y))
    fun toMap(sx: Float, sy: Float) = Vec2(focusX + (sx - centerX) / scale, focusY + (sy - centerY) / scale)

    /** World x on the ground plane turned by [angle] around the origin (before the projection). */
    fun turnX(x: Float, y: Float) = cosA * x - sinA * y
    fun turnY(x: Float, y: Float) = sinA * x + cosA * y

    /** World units -> map units: turn by [angle], then project. */
    fun worldToMap(x: Float, y: Float): Vec2 {
        val rx = turnX(x, y); val ry = turnY(x, y)
        return Vec2(projection.projectX(rx, ry), projection.projectY(rx, ry))
    }

    /** Map units -> world units; the inverse of [worldToMap]. */
    fun mapToWorld(mx: Float, my: Float): Vec2 {
        val rx = projection.unprojectX(mx, my); val ry = projection.unprojectY(mx, my)
        return Vec2(cosA * rx + sinA * ry, -sinA * rx + cosA * ry)
    }

    /** World units -> screen pixels. */
    fun worldToScreen(p: Vec2): Vec2 = toScreen(worldToMap(p.x, p.y))

    /** Screen pixels -> world units; the inverse of [worldToScreen]. */
    fun screenToWorld(sx: Float, sy: Float): Vec2 = toMap(sx, sy).let { mapToWorld(it.x, it.y) }

    /**
     * Turns the world by [degrees] so that the world point under the screen point ([pivotX], [pivotY]) stays put.
     * The pan limit is not applied here: it depends on the angle, so the renderer renews it afterwards
     * ([Renderer.updateLimits]). A running fit or glide keeps aiming at the same world point.
     */
    fun rotateBy(degrees: Float, pivotX: Float, pivotY: Float) {
        if (degrees == 0f) return
        val pivot = screenToWorld(pivotX, pivotY)
        val target = if (animating) mapToWorld(targetX, targetY) else null
        setAngle(angle + degrees)
        val m = worldToMap(pivot.x, pivot.y)
        focusX = m.x - (pivotX - centerX) / scale
        focusY = m.y - (pivotY - centerY) / scale
        target?.let { t -> worldToMap(t.x, t.y).let { targetX = it.x; targetY = it.y } }
    }

    /** Sets [angle] (normalized to 0 until 360) and its cosine and sine. */
    private fun setAngle(degrees: Float) {
        var a = degrees % 360f
        if (a < 0f) a += 360f
        if (a >= 360f) a -= 360f
        angle = a
        val r = Math.toRadians(a.toDouble())
        cosA = cos(r).toFloat()
        sinA = sin(r).toFloat()
        // Exact values at right angles, so a snapped view is pixel-identical to an unrotated projection of it.
        if (a % 90f == 0f) {
            cosA = cosA.roundToInt().toFloat()
            sinA = sinA.roundToInt().toFloat()
        }
    }

    /**
     * After a two-finger turn ends: with [snap], eases to the nearest multiple of 90° around ([pivotX], [pivotY]);
     * without, the angle stays as it is (free rotation).
     */
    fun settleRotation(snap: Boolean, pivotX: Float = centerX, pivotY: Float = centerY) {
        if (!snap) return
        rotateTo(nearestRightAngle(angle), pivotX, pivotY)
    }

    /** Eases the angle to [degrees] (the shorter way round), turning around ([pivotX], [pivotY]). */
    fun rotateTo(degrees: Float, pivotX: Float = centerX, pivotY: Float = centerY) {
        rotationTarget = ((degrees % 360f) + 360f) % 360f
        rotPivotX = pivotX
        rotPivotY = pivotY
    }

    /** Back to north at once, without keeping any point in place; for a new map, which is framed afterwards. */
    fun resetRotation() {
        rotationTarget = null
        setAngle(0f)
    }

    /** Stops a running rotation animation where it is, e.g. when fingers touch the map again. */
    fun stopRotation() {
        rotationTarget = null
    }

    /** The scale at which [r] just fits into the inset viewport. */
    fun fitScale(r: MapRect): Float {
        val w = (viewWidth - insets.left - insets.right).coerceAtLeast(1f)
        val h = (viewHeight - insets.top - insets.bottom).coerceAtLeast(1f)
        return minOf(w / r.width, h / r.height)
    }

    /** True when the inset viewport is taller than wide (a portrait window). */
    val isTall get() = viewHeight - insets.top - insets.bottom > viewWidth - insets.left - insets.right

    /** Whether all of [r] is on screen inside the inset viewport right now. */
    fun shows(r: MapRect): Boolean =
        toScreenX(r.left) >= insets.left - EPS && toScreenX(r.right) <= viewWidth - insets.right + EPS &&
            toScreenY(r.top) >= insets.top - EPS && toScreenY(r.bottom) <= viewHeight - insets.bottom + EPS

    /**
     * Shows all of [r], centred, but zoomed in at least to [atLeast] (then [r] may overflow the view); with [animate],
     * glides there over the next [step]s. Sets [followsArea].
     */
    fun fit(r: MapRect, animate: Boolean = false, atLeast: Float = 0f) {
        followsArea = true
        val s = maxOf(fitScale(r), atLeast).coerceIn(minScale, maxScale)
        if (animate) {
            animating = true
            rate = ANIM_RATE
            targetScale = s
            targetX = r.centerX
            targetY = r.centerY
        } else {
            animating = false
            scale = s
            focusX = r.centerX
            focusY = r.centerY
            clampFocus()
        }
    }

    /**
     * Glides to show map point ([mx], [my]) in the centre at [targetScale] (clamped to the zoom range), slower than a
     * [fit] when [gentle]. Clears [followsArea], like a player move.
     */
    fun glideTo(mx: Float, my: Float, targetScale: Float, gentle: Boolean = false) {
        followsArea = false
        animating = true
        rate = if (gentle) GENTLE_RATE else ANIM_RATE
        this.targetScale = targetScale.coerceIn(minScale, maxScale)
        targetX = mx
        targetY = my
        panBounds?.let { b ->
            targetX = targetX.coerceIn(b.left, b.right)
            targetY = targetY.coerceIn(b.top, b.bottom)
        }
    }

    /** Scales by [factor] (clamped to the zoom range) so that the map point under ([pivotX], [pivotY]) stays put. */
    fun zoomBy(factor: Float, pivotX: Float, pivotY: Float) {
        userMoved()
        val m = toMap(pivotX, pivotY)
        scale = (scale * factor).coerceIn(minScale, maxScale)
        focusX = m.x - (pivotX - centerX) / scale
        focusY = m.y - (pivotY - centerY) / scale
        clampFocus()
    }

    /** Moves the picture by ([dx], [dy]) pixels, like dragging a sheet of paper. */
    fun panBy(dx: Float, dy: Float) {
        userMoved()
        focusX -= dx / scale
        focusY -= dy / scale
        clampFocus()
    }

    /** Advances a running [fit] animation and a running rotation ([rotateTo], [settleRotation]) by [dt] seconds. */
    fun step(dt: Float) {
        stepRotation(dt)
        if (!animating) return
        val k = 1f - exp(-rate * dt)
        scale += (targetScale - scale) * k
        focusX += (targetX - focusX) * k
        focusY += (targetY - focusY) * k
        if (hypot(targetX - focusX, targetY - focusY) * scale < 0.5f && abs(targetScale - scale) < targetScale * 1e-3f) {
            scale = targetScale
            focusX = targetX
            focusY = targetY
            animating = false
        }
        clampFocus()
    }

    private fun stepRotation(dt: Float) {
        val target = rotationTarget ?: return
        val left = shortestTurn(angle, target)
        val d = if (abs(left) < ROTATION_DONE_DEG) left else left * (1f - exp(-ROTATION_RATE * dt))
        rotateBy(d, rotPivotX, rotPivotY)
        if (abs(left) < ROTATION_DONE_DEG) {
            setAngle(target)
            rotationTarget = null
        }
    }

    private fun userMoved() {
        followsArea = false
        animating = false
    }

    private fun clampFocus() {
        val b = panBounds ?: return
        focusX = focusX.coerceIn(b.left, b.right)
        focusY = focusY.coerceIn(b.top, b.bottom)
    }

    companion object {
        /** The multiple of 90° closest to [degrees], normalized to 0 until 360. */
        fun nearestRightAngle(degrees: Float): Float = (((degrees / 90f).roundToInt() * 90) % 360 + 360) % 360f

        /** Signed turn in degrees (-180 until 180) that takes [from] to [to]. */
        fun shortestTurn(from: Float, to: Float): Float {
            var d = (to - from) % 360f
            if (d > 180f) d -= 360f
            if (d <= -180f) d += 360f
            return d
        }

        /** Exponential approach rate of the snapping and compass rotation per second. */
        private const val ROTATION_RATE = 11f
        /** A rotation animation closer than this to its target lands on it. */
        private const val ROTATION_DONE_DEG = 0.05f
        /** Exponential approach rate of [fit] animations per second. */
        private const val ANIM_RATE = 9f
        /** Rate of a gentle [glideTo], e.g. the game-over focus. */
        private const val GENTLE_RATE = 2.6f
        /** Tolerance of [shows] in pixels. */
        private const val EPS = 0.5f
    }
}

/**
 * Turns two-finger movement into camera pan (midpoint movement), zoom (finger distance ratio around the midpoint) and,
 * with [rotate], rotation (change of the angle of the line between the fingers, around the midpoint). All three apply
 * together in every move, so the map point between the fingers stays between them.
 */
class TwoFingerGesture {
    private var active = false
    private var midX = 0f
    private var midY = 0f
    private var span = 0f
    private var heading = 0f

    val isActive get() = active

    /** Where the fingers' midpoint was at the last call, the pivot a snap after release turns around. */
    val lastMidX get() = midX
    val lastMidY get() = midY

    /** Degrees the fingers turned the map since [start]. */
    var turned = 0f; private set

    /** Degrees the fingers twisted since [start] while the turn had not engaged yet. */
    private var twist = 0f

    /**
     * True once the fingers twisted by more than [ROTATE_THRESHOLD] degrees in this gesture. Before that, a pinch or a
     * two-finger pan (fingers never move perfectly parallel) only zooms and pans; the map does not wobble.
     */
    var turning = false; private set

    fun start(x0: Float, y0: Float, x1: Float, y1: Float) {
        active = true
        midX = (x0 + x1) / 2f
        midY = (y0 + y1) / 2f
        span = hypot(x1 - x0, y1 - y0)
        heading = headingOf(x0, y0, x1, y1)
        turned = 0f
        twist = 0f
        turning = false
    }

    /** Applies the movement since the last call to [camera]; the map point between the fingers stays between them. */
    fun move(x0: Float, y0: Float, x1: Float, y1: Float, camera: Camera, rotate: Boolean = true) {
        if (!active) return start(x0, y0, x1, y1)
        val mx = (x0 + x1) / 2f
        val my = (y0 + y1) / 2f
        val s = hypot(x1 - x0, y1 - y0)
        val h = headingOf(x0, y0, x1, y1)
        camera.panBy(mx - midX, my - midY)
        if (span > MIN_SPAN && s > MIN_SPAN) {
            camera.zoomBy(s / span, mx, my)
            if (rotate) {
                var d = Camera.shortestTurn(heading, h)
                if (!turning) {
                    twist += d
                    d = 0f
                    if (abs(twist) > ROTATE_THRESHOLD) {
                        // Engaged: from here on the map follows the fingers smoothly, without a jump by the threshold.
                        turning = true
                        d = twist - sign(twist) * ROTATE_THRESHOLD
                    }
                }
                if (d != 0f) {
                    camera.rotateBy(d, mx, my)
                    turned += d
                }
            }
        }
        midX = mx
        midY = my
        span = s
        heading = h
    }

    fun stop() {
        active = false
    }

    private fun headingOf(x0: Float, y0: Float, x1: Float, y1: Float) = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()

    companion object {
        /** Below this finger distance in pixels the ratio is too noisy to zoom by. */
        private const val MIN_SPAN = 8f

        /** Degrees the fingers must twist within one gesture before the map starts to turn (a dead zone for pinches). */
        const val ROTATE_THRESHOLD = 12f
    }
}
