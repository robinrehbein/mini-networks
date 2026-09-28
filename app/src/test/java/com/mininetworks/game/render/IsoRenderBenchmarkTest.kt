package com.mininetworks.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.StressWorld
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Robolectric render benchmark for docs/TOP100.md section 4 ("Iso-Rendering, volle Szene, Software-Canvas"): the
 * full [StressWorld] scene after 5 s of play, drawn by the [IsoRenderer] on an xxhdpi phone bitmap with the software
 * canvas. After [WARMUP_FRAMES] frames of JIT warm-up it measures [BLOCKS] blocks of [FRAMES_PER_BLOCK] frames and
 * prints the mean per frame of every block and their median. This only measures and prints; the value is recorded
 * in the "Ist" column of docs/TOP100.md section 4 (the software canvas is not the phone's GPU).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "de")
@OptIn(DebugApi::class)
class IsoRenderBenchmarkTest {

    @Test
    fun isoFullSceneFrameTime() {
        val w = StressWorld.build()
        repeat(60 * 5) { w.update(1f / 60f) }
        val bmp = Bitmap.createBitmap(2400, 1080, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val r = IsoRenderer()
        r.density = 3f
        r.layout(bmp.width, bmp.height, w)
        repeat(WARMUP_FRAMES) { r.draw(canvas, w, drag = null, time = 1.3f) }
        val means = List(BLOCKS) {
            val start = System.nanoTime()
            repeat(FRAMES_PER_BLOCK) { r.draw(canvas, w, drag = null, time = 1.3f) }
            (System.nanoTime() - start) / 1e6 / FRAMES_PER_BLOCK
        }.sorted()
        val median = means[means.size / 2]
        println(
            "IsoRenderBenchmarkTest: iso frame of the full stress scene (${w.nodes.size} nodes, ${w.packets.size} packets): " +
                "median %.1f ms, blocks %s ms".format(Locale.ROOT, median, means.joinToString { "%.1f".format(Locale.ROOT, it) }),
        )
        assertTrue(median > 0.0)
    }

    private companion object {
        const val WARMUP_FRAMES = 200
        const val BLOCKS = 5
        const val FRAMES_PER_BLOCK = 50
    }
}
