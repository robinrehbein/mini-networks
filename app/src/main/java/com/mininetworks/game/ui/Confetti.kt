package com.mininetworks.game.ui

import android.graphics.Canvas
import com.mininetworks.game.render.fill
import kotlin.math.cos
import kotlin.math.sin

/**
 * The small celebration at a week change (docs/TOP100.md B3): a burst of confetti in the service colours that
 * rains over the reward cards for [SECONDS] and fades out. Deterministic per [seed] (the week), so a screenshot is
 * repeatable.
 */
class Confetti(private val density: Float) {
    private val paint = fill(0)

    /** True while [t] seconds after the start still show confetti. */
    fun running(t: Float) = t in 0f..SECONDS

    /** Draws the confetti [t] seconds after the week changed over a [width] × [height] view. */
    fun draw(canvas: Canvas, width: Int, height: Int, t: Float, seed: Int) {
        if (!running(t)) return
        val alpha = if (t < SECONDS - FADE) 255 else ((SECONDS - t) / FADE * 255f).toInt().coerceIn(0, 255)
        for (i in 0 until PIECES) {
            val u = unit(seed, i, 0)
            val start = unit(seed, i, 1) * 0.35f
            val local = t - start
            if (local < 0f) continue
            // Launched upwards from the top edge, then falling with a sideways sway.
            val x0 = width * (0.05f + 0.9f * u)
            val vy = -(120f + 260f * unit(seed, i, 2)) * density
            val y = -20f * density + vy * local + 0.5f * GRAVITY * density * local * local
            val x = x0 + sin(local * (2f + 3f * unit(seed, i, 3)) + i) * 26f * density
            if (y > height + 20 * density) continue
            val size = (4f + 4f * unit(seed, i, 4)) * density
            val spin = local * (4f + 8f * unit(seed, i, 5)) + i
            paint.color = COLORS[i % COLORS.size] and 0xFFFFFF or (alpha shl 24)
            canvas.save()
            canvas.rotate(Math.toDegrees(spin.toDouble()).toFloat(), x, y)
            // Squashed by the spin, like a flat piece of paper turning.
            val squash = 0.35f + 0.65f * kotlin.math.abs(cos(spin * 1.3f))
            canvas.drawRect(x - size, y - size * 0.45f * squash, x + size, y + size * 0.45f * squash, paint)
            canvas.restore()
        }
    }

    private fun unit(seed: Int, i: Int, salt: Int): Float {
        var h = seed * 0x9E3779B1.toInt() xor (i * 0x85EBCA6B.toInt()) xor (salt * 0xC2B2AE35.toInt())
        h = h xor (h ushr 16); h *= 0x7FEB352D; h = h xor (h ushr 15); h *= 0x846CA68B.toInt(); h = h xor (h ushr 16)
        return (h ushr 8) / 16777216f
    }

    companion object {
        const val SECONDS = 2.4f
        private const val FADE = 0.6f
        private const val PIECES = 70
        /** Downward pull in dp per second squared. */
        private const val GRAVITY = 900f
        private val COLORS = intArrayOf(
            0xFF2E86AB.toInt(), 0xFF3BA55C.toInt(), 0xFFE9A92B.toInt(), 0xFFE4572E.toInt(), 0xFF8E5BC6.toInt(), 0xFFE0529C.toInt(),
        )
    }
}
