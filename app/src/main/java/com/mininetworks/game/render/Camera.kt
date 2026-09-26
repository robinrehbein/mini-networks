package com.mininetworks.game.render

import com.mininetworks.game.game.Vec2
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

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
 * Zoom and pan shared by every style: map units -> screen pixels as `screen = viewCenter + (map - focus) * scale`.
 * Map units are a renderer's flat projection of the world (see [Renderer.toMap]), so one camera works for any style.
 * [focus] is the map point shown at the centre of the inset viewport. Pure Kotlin, no Android types.
 */
class Camera {
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

    val isAnimating get() = animating

    private val centerX get() = insets.left + (viewWidth - insets.left - insets.right) / 2f
    private val centerY get() = insets.top + (viewHeight - insets.top - insets.bottom) / 2f

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

    /** The scale at which [r] just fits into the inset viewport. */
    fun fitScale(r: MapRect): Float {
        val w = (viewWidth - insets.left - insets.right).coerceAtLeast(1f)
        val h = (viewHeight - insets.top - insets.bottom).coerceAtLeast(1f)
        return minOf(w / r.width, h / r.height)
    }

    /** Shows all of [r], centred; with [animate], glides there over the next [step]s. Sets [followsArea]. */
    fun fit(r: MapRect, animate: Boolean = false) {
        followsArea = true
        val s = fitScale(r).coerceIn(minScale, maxScale)
        if (animate) {
            animating = true
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

    /** Advances a running [fit] animation by [dt] seconds. */
    fun step(dt: Float) {
        if (!animating) return
        val k = 1f - exp(-ANIM_RATE * dt)
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

    private fun userMoved() {
        followsArea = false
        animating = false
    }

    private fun clampFocus() {
        val b = panBounds ?: return
        focusX = focusX.coerceIn(b.left, b.right)
        focusY = focusY.coerceIn(b.top, b.bottom)
    }

    private companion object {
        /** Exponential approach rate of [fit] animations per second. */
        const val ANIM_RATE = 9f
    }
}

/** Turns two-finger movement into camera pan (midpoint movement) and zoom (finger distance ratio around the midpoint). */
class TwoFingerGesture {
    private var active = false
    private var midX = 0f
    private var midY = 0f
    private var span = 0f

    val isActive get() = active

    fun start(x0: Float, y0: Float, x1: Float, y1: Float) {
        active = true
        midX = (x0 + x1) / 2f
        midY = (y0 + y1) / 2f
        span = hypot(x1 - x0, y1 - y0)
    }

    /** Applies the movement since the last call to [camera]; the map point between the fingers stays between them. */
    fun move(x0: Float, y0: Float, x1: Float, y1: Float, camera: Camera) {
        if (!active) return start(x0, y0, x1, y1)
        val mx = (x0 + x1) / 2f
        val my = (y0 + y1) / 2f
        val s = hypot(x1 - x0, y1 - y0)
        camera.panBy(mx - midX, my - midY)
        if (span > MIN_SPAN && s > MIN_SPAN) camera.zoomBy(s / span, mx, my)
        midX = mx
        midY = my
        span = s
    }

    fun stop() {
        active = false
    }

    private companion object {
        /** Below this finger distance in pixels the ratio is too noisy to zoom by. */
        const val MIN_SPAN = 8f
    }
}
