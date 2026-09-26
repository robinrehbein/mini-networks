package com.mininetworks.game.audio

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WAV files in `res/raw` are exactly what [SoundSynth] generates, and stay small.
 * Regenerate them after changing the synth with `REGENERATE_SOUNDS=1 ./gradlew :app:testDebugUnitTest --tests '*SoundAssetsTest*'`.
 */
class SoundAssetsTest {

    private val dir = File(System.getProperty("sounds.dir") ?: "src/main/res/raw")
    private val regenerate = System.getProperty("sounds.regenerate") == "1"

    @Test
    fun committedFilesMatchTheSynth() {
        if (regenerate) dir.mkdirs()
        for ((name, samples) in SoundSynth.assets) {
            val bytes = SoundSynth.wav(samples)
            val file = File(dir, "$name.wav")
            if (regenerate) file.writeBytes(bytes)
            assertTrue("$file is missing; run with REGENERATE_SOUNDS=1", file.exists())
            assertArrayEquals("$file differs from the synth; run with REGENERATE_SOUNDS=1", bytes, file.readBytes())
        }
    }

    @Test
    fun allSoundsTogetherStayUnder300Kb() {
        val total = dir.listFiles().orEmpty().sumOf { it.length() }
        assertTrue("audio assets take $total bytes", total in 1 until 300 * 1024)
        assertEquals("only the synth's files live in res/raw", SoundSynth.assets.keys.map { "$it.wav" }.sorted(), dir.list().orEmpty().sorted())
    }

    @Test
    fun soundsAreAudibleAndNeverClip() {
        for ((name, samples) in SoundSynth.assets) {
            val peak = samples.maxOf { kotlin.math.abs(it) }
            assertTrue("$name peak $peak", peak in 0.3f..0.8f)
            assertTrue("$name starts silent", kotlin.math.abs(samples.first()) < 0.01f)
            assertTrue("$name ends silent", kotlin.math.abs(samples.last()) < 0.01f)
        }
    }
}
