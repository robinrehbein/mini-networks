package com.mininetworks.game.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** The I/O thread survives any failing task, checked exceptions included (a full disk must not end the app). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameIoTest {

    @Test
    fun aCheckedExceptionOfATaskIsLoggedAndNeverReachesTheUncaughtHandler() {
        val uncaught = AtomicReference<Throwable?>(null)
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.set(e) }
        try {
            val thread = GameIo.call { Thread.currentThread() }
            GameIo.execute { throw IOException("disk full") }
            GameIo.execute { throw IllegalStateException("broken") }
            var ranAfter = false
            GameIo.execute { ranAfter = true }
            GameIo.awaitIdle()
            assertEquals("no exception reached the uncaught-exception handler", null, uncaught.get())
            assertTrue("later tasks still run", ranAfter)
            assertTrue("still the same I/O thread", thread === GameIo.call { Thread.currentThread() })
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }
}
