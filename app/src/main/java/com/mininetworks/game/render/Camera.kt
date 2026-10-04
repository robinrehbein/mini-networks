package com.mininetworks.game.render

import com.mininetworks.game.game.Vec2
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
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
 * that rotating the world and projecting it commute with the camera's pan and zoom; the unproject functions are its
 * exact inverse. A style with height may depend on the camera's pitch ([tilted]): it stays linear at every pitch.
 */
interface MapProjection {
    fun projectX(x: Float, y: Float): Float
    fun projectY(x: Float, y: Float): Float
    fun unprojectX(mx: Float, my: Float): Float
    fun unprojectY(mx: Float, my: Float): Float

    /**
     * The same projection seen at a pitch with ground foreshortening [squash] ([Camera.squash]): how high a step
     * towards the viewer shows relative to a step across. Styles without height ignore it and return themselves.
     */
    fun tilted(squash: Float): MapProjection = this

    /** Map units are world units (the flat overview). */
    object Identity : MapProjection {
        override fun projectX(x: Float, y: Float) = x
        override fun projectY(x: Float, y: Float) = y
        override fun unprojectX(mx: Float, my: Float) = mx
        override fun unprojectY(mx: Float, my: Float) = my
    }
}

/**
 * Zoom, pan, rotation and tilt shared by every style.
 *
 * World units on the ground plane are first turned by [angle] (degrees, clockwise on screen for the flat view) around
 * the world origin, then flattened by the style's [projection] into map units (for the iso style this is the iso
 * squash, so the squash always comes after the turn and any angle works), and map units become screen pixels as
 * `screen = viewCenter + (map - focus) * scale`. [focus] is the map point shown at the centre of the inset viewport.
 * Rotating ([rotateBy]) keeps the world point under the pivot where it is, so turning around the camera focus or
 * around the midpoint of two fingers is the same operation with a different pivot. A released turn and the rotate
 * buttons land on multiples of [SNAP_DEG]: four corner views and four views straight onto a side.
 *
 * [tilt] is the pitch of the view above the ground, from [TILT_MIN] (low, buildings tower) to [TILT_MAX] (steep,
 * almost from above); [DEFAULT_TILT] is the classic iso look. A style with height reads it as [squash] (the ground's
 * foreshortening, the sine of the pitch, which its [projection] follows) and [lift] (how far heights rise, the
 * cosine), both exactly 1/2 at the default. Tilting ([tiltBy]) keeps the world point under the pivot in place, like
 * turning. Pure Kotlin, no Android types.
 */
class Camera {
    /** The style's flat projection; set once by the renderer that owns this camera, then kept at the current [tilt]. */
    var projection: MapProjection = MapProjection.Identity
        set(value) { field = value.tilted(squash) }

    /** Rotation of the world in degrees, 0 until 360; 0 is north up (the original view). */
    var angle = 0f; private set
    /** Cosine and sine of [angle], kept in step with it for the renderers' hot paths. */
    var cosA = 1f; private set
    var sinA = 0f; private set

    var scale = 1f; private set
    var focusX = 0f; private set
    var focusY = 0f; private set

    /** Pitch of the view in degrees above the ground, [TILT_MIN] until [TILT_MAX]; [DEFAULT_TILT] is the classic one. */
    var tilt = DEFAULT_TILT; private set
    /** Ground foreshortening at [tilt]: a step towards the viewer shows this high relative to a step across. */
    var squash = DEFAULT_SQUASH; private set
    /** Map units a height of one tile width rises at [tilt]; 0 would be straight from above. */
    var lift = DEFAULT_SQUASH; private set

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

    /** Running tilt animation ([tiltTo]): the pitch it eases to and the screen point it tilts around. */
    private var tiltTarget: Float? = null
    private var tiltPivotX = 0f
    private var tiltPivotY = 0f

    /** True while the view eases to a new pitch. */
    val isTilting get() = tiltTarget != null

    /** Screen point the last [tiltBy] pitched around, which [rescale] keeps in place while the renderer reframes. */
    var tiltedAroundX = 0f; private set
    var tiltedAroundY = 0f; private set

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

    /** Screen x of world point ([x], [y]), as [worldToScreen] but without allocating; for per-frame loops. */
    fun worldToScreenX(x: Float, y: Float) = toScreenX(projection.projectX(turnX(x, y), turnY(x, y)))

    /** Screen y of world point ([x], [y]), as [worldToScreen] but without allocating. */
    fun worldToScreenY(x: Float, y: Float) = toScreenY(projection.projectY(turnX(x, y), turnY(x, y)))

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
        // Exact values at right angles, so a snapped view is pixel-identical to an unrotated projection of it; at the
        // side-on views both are exactly as large, so walls seen edge-on drop out and depths along a row tie exactly.
        if (a % 90f == 0f) {
            cosA = cosA.roundToInt().toFloat()
            sinA = sinA.roundToInt().toFloat()
        } else if (a % SNAP_DEG == 0f) {
            cosA = sign(cosA) * HALF_SQRT2
            sinA = sign(sinA) * HALF_SQRT2
        }
    }

    /**
     * After a two-finger turn ends: with [snap], eases to the nearest multiple of [SNAP_DEG] (a corner or a side-on
     * view) around ([pivotX], [pivotY]); without, the angle stays as it is (free rotation).
     */
    fun settleRotation(snap: Boolean, pivotX: Float = centerX, pivotY: Float = centerY) {
        if (!snap) return
        rotateTo(nearestSnapAngle(angle), pivotX, pivotY)
    }

    /**
     * One tap on a rotate button: eases on to the next multiple of [SNAP_DEG] in [direction] (+1 clockwise, -1
     * counter-clockwise) around ([pivotX], [pivotY]). It steps on from where a running turn is heading, so quick taps
     * add up; from a free angle the first step lands on the next snapped view in that direction.
     */
    fun rotateStep(direction: Int, pivotX: Float = centerX, pivotY: Float = centerY) {
        val from = rotationTarget ?: angle
        rotateTo(nextStep(from / SNAP_DEG, direction) * SNAP_DEG, pivotX, pivotY)
    }

    /**
     * Tilts the view by [degrees] of pitch (clamped to [TILT_MIN] until [TILT_MAX]) so that the world point under the
     * screen point ([pivotX], [pivotY]) stays put. As with [rotateBy], the renderer renews the pan limit afterwards,
     * and a running fit or glide keeps aiming at the same world point.
     */
    fun tiltBy(degrees: Float, pivotX: Float = centerX, pivotY: Float = centerY) {
        val next = (tilt + degrees).coerceIn(TILT_MIN, TILT_MAX)
        if (next == tilt) return
        tiltedAroundX = pivotX
        tiltedAroundY = pivotY
        val pivot = screenToWorld(pivotX, pivotY)
        val target = if (animating) mapToWorld(targetX, targetY) else null
        setTilt(next)
        val m = worldToMap(pivot.x, pivot.y)
        focusX = m.x - (pivotX - centerX) / scale
        focusY = m.y - (pivotY - centerY) / scale
        target?.let { t -> worldToMap(t.x, t.y).let { targetX = it.x; targetY = it.y } }
    }

    /** Eases the pitch to [degrees] (clamped to the tilt range), tilting around ([pivotX], [pivotY]). */
    fun tiltTo(degrees: Float, pivotX: Float = centerX, pivotY: Float = centerY) {
        tiltTarget = degrees.coerceIn(TILT_MIN, TILT_MAX)
        tiltPivotX = pivotX
        tiltPivotY = pivotY
    }

    /**
     * One tap on a tilt button: eases [TILT_STEP] degrees steeper ([direction] +1) or flatter (-1) from where a running
     * tilt is heading, on the grid of steps through [DEFAULT_TILT], so tapping back always finds the classic view.
     */
    fun tiltStep(direction: Int, pivotX: Float = centerX, pivotY: Float = centerY) {
        val from = tiltTarget ?: tilt
        tiltTo(DEFAULT_TILT + nextStep((from - DEFAULT_TILT) / TILT_STEP, direction) * TILT_STEP, pivotX, pivotY)
    }

    /** True if the tilt buttons can go on in [direction] (+1 steeper, -1 flatter). */
    fun canTilt(direction: Int): Boolean = (tiltTarget ?: tilt).let { if (direction > 0) it < TILT_MAX else it > TILT_MIN }

    /** Back to the default pitch at once, keeping no point in place; for a new map, which is framed afterwards. */
    fun resetTilt() {
        tiltTarget = null
        setTilt(DEFAULT_TILT)
    }

    /** Stops a running tilt animation where it is, e.g. when fingers touch the map again. */
    fun stopTilt() {
        tiltTarget = null
    }

    /** Sets [tilt], its [squash] and [lift], and the projection seen at that pitch. */
    private fun setTilt(degrees: Float) {
        tilt = degrees
        squash = squashOf(degrees)
        lift = liftOf(degrees)
        projection = projection
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

    /**
     * Scales by [factor] (clamped to the zoom range) around ([pivotX], [pivotY]) for the renderer's own reframing, e.g.
     * while tilting ([Renderer.keepFramed]): unlike [zoomBy] it is no player move, so [followsArea] and a running fit or
     * glide stay (the glide's zoom scales along).
     */
    fun rescale(factor: Float, pivotX: Float, pivotY: Float) {
        val m = toMap(pivotX, pivotY)
        scale = (scale * factor).coerceIn(minScale, maxScale)
        if (animating) targetScale = (targetScale * factor).coerceIn(minScale, maxScale)
        focusX = m.x - (pivotX - centerX) / scale
        focusY = m.y - (pivotY - centerY) / scale
        clampFocus()
    }

    /**
     * Pans as little as possible so that [r] lies inside the inset viewport, along each axis on which it fits at the
     * current zoom (along the other it is left alone: the player pans there). Nothing moves during a running fit or
     * glide, which already aims somewhere. Like [rescale], no player move.
     */
    fun bringIntoView(r: MapRect) {
        if (animating) return
        focusX += shortfall(toScreenX(r.left), toScreenX(r.right), insets.left, viewWidth - insets.right) / scale
        focusY += shortfall(toScreenY(r.top), toScreenY(r.bottom), insets.top, viewHeight - insets.bottom) / scale
        clampFocus()
    }

    /** Moves the picture by ([dx], [dy]) pixels, like dragging a sheet of paper. */
    fun panBy(dx: Float, dy: Float) {
        userMoved()
        focusX -= dx / scale
        focusY -= dy / scale
        clampFocus()
    }

    /**
     * Advances a running [fit] animation, a running rotation ([rotateTo], [settleRotation]) and a running tilt
     * ([tiltTo]) by [dt] seconds; without [tilt] the pitch stays, for a caller that steps it itself ([stepTilt]).
     */
    fun step(dt: Float, tilt: Boolean = true) {
        stepRotation(dt)
        if (tilt) stepTilt(dt)
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

    /** Advances a running tilt animation ([tiltTo]) alone by [dt] seconds; [step] does it along with the rest. */
    fun stepTilt(dt: Float) {
        val target = tiltTarget ?: return
        val left = target - tilt
        if (abs(left) < TILT_DONE_DEG) {
            tiltBy(left, tiltPivotX, tiltPivotY)
            // Land exactly, so the default pitch gives the classic picture again.
            if (tilt != target) setTilt(target)
            tiltTarget = null
        } else {
            tiltBy(left * (1f - exp(-ROTATION_RATE * dt)), tiltPivotX, tiltPivotY)
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

        /** The multiple of [SNAP_DEG] closest to [degrees] (a corner or a side-on view), normalized to 0 until 360. */
        fun nearestSnapAngle(degrees: Float): Float = (((degrees / SNAP_DEG).roundToInt() * SNAP_DEG.toInt()) % 360 + 360) % 360f

        /** The whole step after [steps] in [direction]: the next one when on a step already, else the next that way. */
        private fun nextStep(steps: Float, direction: Int): Int {
            val near = steps.roundToInt()
            return when {
                abs(steps - near) < 1e-3f -> near + direction
                direction > 0 -> ceil(steps).toInt()
                else -> floor(steps).toInt()
            }
        }

        /** Degrees between the views a released turn snaps to and the rotate buttons step through. */
        const val SNAP_DEG = 45f

        /** Pitch range of [tilt] in degrees: low and from the side until steep and almost from above. */
        const val TILT_MIN = 15f
        const val TILT_MAX = 75f
        /** The classic iso pitch: tiles half as high as wide (sin 30° = 1/2). */
        const val DEFAULT_TILT = 30f
        /** Degrees of pitch one tap on a tilt button changes. */
        const val TILT_STEP = 15f

        /** [squash] and [lift] at the default pitch: exactly 1/2, so the default is the classic picture bit for bit. */
        private const val DEFAULT_SQUASH = 0.5f
        /** Cosine and sine of the side-on views. */
        private const val HALF_SQRT2 = 0.70710677f

        /** Ground foreshortening at [pitch] degrees: its sine, exactly 1/2 at [DEFAULT_TILT]. */
        fun squashOf(pitch: Float): Float =
            if (pitch == DEFAULT_TILT) DEFAULT_SQUASH else sin(Math.toRadians(pitch.toDouble())).toFloat()

        /**
         * Map units one tile width of height rises at [pitch] degrees: its cosine, scaled so that the classic pitch
         * keeps the classic 1/2; heights shrink towards the steep view and grow towards the low one.
         */
        fun liftOf(pitch: Float): Float =
            if (pitch == DEFAULT_TILT) DEFAULT_SQUASH
            else (DEFAULT_SQUASH * cos(Math.toRadians(pitch.toDouble())) / cos(Math.toRadians(DEFAULT_TILT.toDouble()))).toFloat()

        /**
         * Pixels the span [a]..[b] must move to lie inside [lo]..[hi], 0 if it does already or does not fit (then it
         * is not moved at all); positive moves the picture's content up or left.
         */
        private fun shortfall(a: Float, b: Float, lo: Float, hi: Float): Float = when {
            b - a > hi - lo + EPS -> 0f
            a < lo -> a - lo
            b > hi -> b - hi
            else -> 0f
        }

        /** Signed turn in degrees (-180 until 180) that takes [from] to [to]. */
        fun shortestTurn(from: Float, to: Float): Float {
            var d = (to - from) % 360f
            if (d > 180f) d -= 360f
            if (d <= -180f) d += 360f
            return d
        }

        /** A tilt animation closer than this (degrees) to its target lands on it. */
        private const val TILT_DONE_DEG = 0.05f
        /** Exponential approach rate of the snapping and compass rotation, and of the tilt buttons, per second. */
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
 * Turns two-finger movement into camera pan (midpoint movement), zoom (finger distance ratio around the midpoint),
 * with [rotate] rotation (change of the angle of the line between the fingers, around the midpoint) and with [tilt]
 * a change of pitch (both fingers dragged up or down side by side, as in map apps).
 *
 * Pan, zoom and rotation apply together in every move, so the map point between the fingers stays between them.
 * Turning and tilting each wait for a clear sign first, so a pinch or a pan (fingers never move perfectly straight or
 * parallel) does not wobble the map: a twist beyond [ROTATE_THRESHOLD] degrees starts turning; both fingers moving
 * the same way vertically by more than [TILT_THRESHOLD_DP] each, mostly vertically, while they lie side by side and
 * keep their distance, starts tilting. Whichever starts first holds for the rest of the gesture. While tilting, the
 * fingers only tilt (up is flatter, down is steeper): the small pan and zoom of the start are taken back and nothing
 * pans, zooms or turns any more, so the map point between the fingers stays put while the view pitches around it.
 */
class TwoFingerGesture {
    private var active = false
    private var midX = 0f
    private var midY = 0f
    private var span = 0f
    private var heading = 0f

    /** Where each finger and their midpoint went down, and their distance then: the tilt test measures from there. */
    private var startX0 = 0f
    private var startY0 = 0f
    private var startX1 = 0f
    private var startY1 = 0f
    private var startMidX = 0f
    private var startMidY = 0f
    private var startSpan = 0f
    /** The camera's zoom when the first move came, restored when a tilt engages. */
    private var startScale = Float.NaN

    /** Pixels per dp of the screen, for the tilt thresholds; 1 by default. */
    var density = 1f

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

    /** True once the fingers were dragged up or down side by side in this gesture: they then only tilt the view. */
    var tilting = false; private set

    /** Degrees of pitch the fingers changed since [start]. */
    var tilted = 0f; private set

    /** Screen point the view pitches around while [tilting]: the fingers' midpoint where they went down. */
    private var tiltPivotX = 0f
    private var tiltPivotY = 0f

    fun start(x0: Float, y0: Float, x1: Float, y1: Float) {
        active = true
        midX = (x0 + x1) / 2f
        midY = (y0 + y1) / 2f
        span = hypot(x1 - x0, y1 - y0)
        heading = headingOf(x0, y0, x1, y1)
        startX0 = x0; startY0 = y0; startX1 = x1; startY1 = y1
        startMidX = midX; startMidY = midY; startSpan = span; startScale = Float.NaN
        turned = 0f
        twist = 0f
        turning = false
        tilting = false
        tilted = 0f
    }

    /**
     * Applies the movement since the last call to [camera]; the map point between the fingers stays between them.
     * [rotate] and [tilt] allow turning and tilting (tilt only for a style with height).
     */
    fun move(x0: Float, y0: Float, x1: Float, y1: Float, camera: Camera, rotate: Boolean = true, tilt: Boolean = false) {
        if (!active) return start(x0, y0, x1, y1)
        val mx = (x0 + x1) / 2f
        val my = (y0 + y1) / 2f
        val s = hypot(x1 - x0, y1 - y0)
        val h = headingOf(x0, y0, x1, y1)
        if (startScale.isNaN()) startScale = camera.scale
        if (tilt && !tilting && !turning && isTiltDrag(x0, y0, x1, y1, s)) {
            // Engaged: take back the pan and zoom of the way here, then pitch by the drag beyond the threshold, so the
            // view neither jumps nor drifts.
            tilting = true
            camera.panBy(startMidX - midX, startMidY - midY)
            camera.zoomBy(startScale / camera.scale, startMidX, startMidY)
            tiltPivotX = startMidX
            tiltPivotY = startMidY
            val dy = my - startMidY
            applyTilt(dy - sign(dy) * TILT_THRESHOLD_DP * density, camera)
        } else if (tilting) {
            applyTilt(my - midY, camera)
        } else {
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
        }
        midX = mx
        midY = my
        span = s
        heading = h
    }

    /**
     * True if the fingers, now at ([x0], [y0]) and ([x1], [y1]) [s] px apart, moved like a tilt since [start]: both the
     * same way vertically by more than the threshold, each at most half as far sideways, side by side (their line
     * closer to horizontal than vertical) and at nearly the same distance.
     */
    private fun isTiltDrag(x0: Float, y0: Float, x1: Float, y1: Float, s: Float): Boolean {
        val dy0 = y0 - startY0; val dy1 = y1 - startY1
        val min = TILT_THRESHOLD_DP * density
        if (dy0 * dy1 <= 0f || abs(dy0) < min || abs(dy1) < min) return false
        if (abs(x0 - startX0) * 2f > abs(dy0) || abs(x1 - startX1) * 2f > abs(dy1)) return false
        if (abs(startX1 - startX0) < abs(startY1 - startY0)) return false
        return abs(s - startSpan) < startSpan * TILT_SPAN_TOLERANCE
    }

    /** Pitches [camera] for [dy] px of vertical drag around the tilt pivot: up (negative) is flatter. */
    private fun applyTilt(dy: Float, camera: Camera) {
        val before = camera.tilt
        camera.tiltBy(dy / density * TILT_DEG_PER_DP, tiltPivotX, tiltPivotY)
        tilted += camera.tilt - before
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

        /** Dp each finger must move up or down, side by side, before the view starts to tilt. */
        const val TILT_THRESHOLD_DP = 18f

        /** Degrees of pitch per dp of vertical two-finger drag: about the whole tilt range across a phone's height. */
        const val TILT_DEG_PER_DP = 0.2f

        /** Largest change of the finger distance, as a share of it, that still counts as a parallel drag. */
        private const val TILT_SPAN_TOLERANCE = 0.2f
    }
}
