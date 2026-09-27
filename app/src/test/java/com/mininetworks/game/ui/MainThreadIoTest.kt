package com.mininetworks.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.StrictMode
import com.mininetworks.game.DebugChecks
import com.mininetworks.game.audio.SoundPlayer
import com.mininetworks.game.data.GameIo
import com.mininetworks.game.data.SaveStore
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.Scenarios
import com.mininetworks.game.game.World
import com.mininetworks.game.ui.menu.Screen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * No disk work on the UI thread (docs/TOP100.md A3). Each test blocks the [GameIo] thread with a gate, then calls what
 * the activity calls on the UI thread (create the view, restore, pause with a game to save, open the sounds). Those
 * calls must return right away although the disk thread is stuck, and the work is done once the gate opens: proof that
 * it runs on the I/O thread, not on the caller's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainThreadIoTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val bmp = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
    private var gate = CountDownLatch(0)

    @Before
    fun setUp() {
        SettingsStore(app).tutorialSeen = true
        GameIo.awaitIdle()
    }

    @After
    fun openGate() {
        gate.countDown()
        GameIo.awaitIdle()
    }

    /** Blocks the I/O thread until [openGate] (at most [GATE_SECONDS]). */
    private fun closeGate() {
        gate = CountDownLatch(1)
        val g = gate
        GameIo.execute { g.await(GATE_SECONDS, TimeUnit.SECONDS) }
    }

    /** Runs [block] and returns how long it took, in milliseconds. */
    private fun millis(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun pauseSavesWithoutWaitingForTheDisk() {
        val view = GameView(app)
        view.drawSnapshot(Canvas(bmp), World(Scenarios.RIVER_TOWN, seed = 3L), bmp.width, bmp.height, time = 0f)
        closeGate()
        val took = millis { view.pause() }
        assertTrue("pause() waited $took ms for the disk", took < QUICK_MS)
        assertFalse("not written while the I/O thread is blocked", SaveStore(app.filesDir).exists)
        openGate()
        assertTrue("written on the I/O thread", SaveStore(app.filesDir).exists)
    }

    @Test
    fun creatingAndRestoringTheViewDoNotTouchTheDisk() {
        SaveStore(app.filesDir).save(World(Scenarios.RIVER_TOWN, seed = 5L))
        val state = Bundle().apply { putBoolean("mininetworks.inGame", true) }
        closeGate()
        lateinit var view: GameView
        val took = millis {
            view = GameView(app)
            view.restoreState(state)
        }
        assertTrue("onCreate work waited $took ms for the disk", took < QUICK_MS)
        openGate()
        // The game thread's first frame reads the settings and the save.
        view.advance(0f)
        assertEquals(Screen.PAUSED, view.currentScreen)
        assertEquals(5L, view.currentWorld.seed)
    }

    @Test
    fun soundsLoadOnTheIoThread() {
        val sounds = SoundPlayer(app)
        closeGate()
        val took = millis { sounds.open() }
        assertTrue("open() waited $took ms", took < QUICK_MS)
        assertFalse(sounds.ready)
        openGate()
        assertTrue("loaded once the I/O thread ran", sounds.ready)
        sounds.close()
        assertFalse(sounds.ready)
    }

    @Test
    fun soundsClosedBeforeTheyLoadedStayClosed() {
        val sounds = SoundPlayer(app)
        closeGate()
        sounds.open()
        sounds.close()
        openGate()
        assertFalse("a pool that finished after close() is released", sounds.ready)
        sounds.open()
        GameIo.awaitIdle()
        assertTrue(sounds.ready)
        sounds.close()
    }

    @Test
    fun strictModeLogsEverythingInDebugBuilds() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
        DebugChecks.install()
        assertNotEquals(StrictMode.ThreadPolicy.LAX.toString(), StrictMode.getThreadPolicy().toString())
        assertNotEquals(StrictMode.VmPolicy.LAX.toString(), StrictMode.getVmPolicy().toString())
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
    }

    private companion object {
        const val GATE_SECONDS = 10L
        /** Far below the gate: anything that waits for the blocked I/O thread takes the full [GATE_SECONDS]. */
        const val QUICK_MS = 2_000L
    }
}
