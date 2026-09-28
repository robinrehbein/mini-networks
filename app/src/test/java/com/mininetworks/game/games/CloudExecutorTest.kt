package com.mininetworks.game.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CloudExecutorTest {
    @Test
    fun tryExecuteRunsTaskWhileOpen() {
        val executor = Executors.newSingleThreadExecutor()
        val ran = AtomicInteger()
        assertTrue(executor.tryExecute { ran.incrementAndGet() })
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(1, ran.get())
    }

    @Test
    fun tryExecuteAfterShutdownReturnsFalseInsteadOfThrowing() {
        val executor = Executors.newSingleThreadExecutor()
        executor.shutdown()
        val ran = AtomicInteger()
        assertFalse(executor.tryExecute { ran.incrementAndGet() })
        assertEquals(0, ran.get())
    }
}
