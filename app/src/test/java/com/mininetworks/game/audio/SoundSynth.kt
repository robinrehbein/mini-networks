package com.mininetworks.game.audio

import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Builds the game's sound effects from scratch as 16-bit mono WAV files, so no recorded or licensed audio is needed.
 * Deterministic: [StrictMath] and a seeded noise source give the same bytes on every JVM, which lets
 * [SoundAssetsTest] compare the generated files with the committed ones in `res/raw`.
 */
object SoundSynth {
    const val RATE = 22_050

    /** File name (without extension) in `res/raw` to its samples in -1..1. */
    val assets: Map<String, FloatArray> by lazy {
        mapOf(
            "sfx_pluck" to pluck(),
            "sfx_cable" to cableClick(),
            "sfx_warning" to warning(),
            "sfx_week" to weekChime(),
            "sfx_gameover" to gameOver(),
        )
    }

    /** Frequency the pluck is recorded at: C5. [SoundPlayer] pitches it per service from here. */
    const val PLUCK_HZ = 523.25

    /** A soft plucked string: a few harmonics that fade faster the higher they are, with a tiny breath of noise. */
    fun pluck(): FloatArray {
        val out = buffer(0.42)
        val noise = Random(1)
        for (i in out.indices) {
            val t = i.toDouble() / RATE
            var v = 0.0
            for (k in 1..6) v += StrictMath.sin(2 * Math.PI * PLUCK_HZ * k * t) * StrictMath.pow(k.toDouble(), -1.6) * StrictMath.exp(-t * (5.0 + 5.0 * k))
            v += (noise.nextDouble() * 2 - 1) * 0.15 * StrictMath.exp(-t * 400)
            out[i] = (v * attack(t, 0.004)).toFloat()
        }
        return normalize(fadeOut(out, 0.05), 0.55f)
    }

    /** A short wooden click with a low knock under it, for a cable snapping into place. */
    fun cableClick(): FloatArray {
        val out = buffer(0.07)
        val noise = Random(2)
        var low = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / RATE
            low += ((noise.nextDouble() * 2 - 1) - low) * 0.35
            val tick = StrictMath.sin(2 * Math.PI * 1_650 * t) * StrictMath.exp(-t * 160)
            val knock = StrictMath.sin(2 * Math.PI * 190 * t) * StrictMath.exp(-t * 55) * 0.8
            out[i] = ((tick * 0.6 + knock + low * StrictMath.exp(-t * 320) * 0.7) * attack(t, 0.0015)).toFloat()
        }
        return normalize(fadeOut(out, 0.02), 0.6f)
    }

    /** Two falling soft tones (E5, B4) with a slow wobble: something needs attention, without alarm. */
    fun warning(): FloatArray {
        val out = buffer(0.62)
        val notes = listOf(0.0 to 659.25, 0.2 to 493.88)
        for (i in out.indices) {
            val t = i.toDouble() / RATE
            var v = 0.0
            for ((start, hz) in notes) {
                val u = t - start
                if (u < 0) continue
                val env = attack(u, 0.012) * StrictMath.exp(-u * 5.5)
                val tone = StrictMath.sin(2 * Math.PI * hz * u) + 0.25 * StrictMath.sin(2 * Math.PI * hz * 3 * u) + 0.12 * StrictMath.sin(2 * Math.PI * hz * 2 * u)
                v += tone * env * (1 + 0.15 * StrictMath.sin(2 * Math.PI * 7 * u))
            }
            out[i] = v.toFloat()
        }
        return normalize(fadeOut(out, 0.08), 0.5f)
    }

    /** A rising bell arpeggio (C6, E6, G6) for a new week. */
    fun weekChime(): FloatArray {
        val out = buffer(1.35)
        val notes = listOf(0.0 to 1_046.5, 0.13 to 1_318.51, 0.26 to 1_567.98)
        val partials = listOf(Triple(1.0, 1.0, 3.2), Triple(2.76, 0.35, 7.0), Triple(5.4, 0.12, 12.0))
        for (i in out.indices) {
            val t = i.toDouble() / RATE
            var v = 0.0
            for ((start, hz) in notes) {
                val u = t - start
                if (u < 0) continue
                for ((ratio, amp, decay) in partials) v += StrictMath.sin(2 * Math.PI * hz * ratio * u) * amp * StrictMath.exp(-u * decay)
            }
            out[i] = (v * attack(t, 0.002)).toFloat()
        }
        return normalize(fadeOut(out, 0.2), 0.45f)
    }

    /**
     * A slow falling minor line (G4, E♭4, C4) on a soft square-ish tone with a low hum under it, the last note held:
     * the network went down.
     */
    fun gameOver(): FloatArray {
        val out = buffer(1.6)
        val notes = listOf(0.0 to 392.0, 0.28 to 311.13, 0.56 to 261.63)
        for (i in out.indices) {
            val t = i.toDouble() / RATE
            var v = 0.0
            for ((k, note) in notes.withIndex()) {
                val (start, hz) = note
                val u = t - start
                if (u < 0) continue
                val decay = if (k == notes.lastIndex) 2.2 else 6.0
                val env = attack(u, 0.015) * StrictMath.exp(-u * decay)
                val tone = StrictMath.sin(2 * Math.PI * hz * u) + 0.3 * StrictMath.sin(2 * Math.PI * hz * 3 * u) + 0.15 * StrictMath.sin(2 * Math.PI * hz * 5 * u)
                v += tone * env * (1 + 0.1 * StrictMath.sin(2 * Math.PI * 5 * u))
            }
            v += StrictMath.sin(2 * Math.PI * 65.41 * t) * 0.35 * attack(t, 0.05) * StrictMath.exp(-t * 1.8)
            out[i] = v.toFloat()
        }
        return normalize(fadeOut(out, 0.25), 0.55f)
    }

    /** Samples as a 16-bit PCM mono WAV file. */
    fun wav(samples: FloatArray): ByteArray {
        val data = samples.size * 2
        val out = ByteArrayOutputStream(44 + data)
        fun str(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun int(v: Int) = repeat(4) { out.write(v ushr (8 * it) and 0xFF) }
        fun short(v: Int) = repeat(2) { out.write(v ushr (8 * it) and 0xFF) }
        str("RIFF"); int(36 + data); str("WAVE")
        str("fmt "); int(16); short(1); short(1); int(RATE); int(RATE * 2); short(2); short(16)
        str("data"); int(data)
        for (s in samples) short(Math.round(s.coerceIn(-1f, 1f) * Short.MAX_VALUE))
        return out.toByteArray()
    }

    private fun buffer(seconds: Double) = FloatArray((seconds * RATE).toInt())

    private fun attack(t: Double, seconds: Double) = if (t >= seconds) 1.0 else t / seconds

    private fun fadeOut(s: FloatArray, seconds: Double): FloatArray {
        val n = (seconds * RATE).toInt().coerceAtMost(s.size)
        for (i in 0 until n) s[s.size - 1 - i] *= i.toFloat() / n
        return s
    }

    private fun normalize(s: FloatArray, peak: Float): FloatArray {
        val max = s.maxOf { kotlin.math.abs(it) }
        if (max > 0f) for (i in s.indices) s[i] = s[i] / max * peak
        return s
    }
}
