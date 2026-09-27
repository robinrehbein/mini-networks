package com.mininetworks.game.data

import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * The one background thread for disk and other slow setup work (docs/TOP100.md A3): the autosave ([SaveSlot]), loading
 * the sound effects and starting billing. Nothing of it may run on the UI thread, where a slow flash chip turns into
 * jank or an ANR. Tasks run one after the other in the order they were queued, so a save queued before a delete is
 * written before it is deleted, and a read waits for every write queued before it.
 */
object GameIo {
    private const val TAG = "GameIo"

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GameIo").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    /** Runs [task] later on the I/O thread. An exception is logged; it must not take the whole app down. */
    fun execute(task: () -> Unit) {
        executor.execute {
            try {
                task()
            } catch (e: RuntimeException) {
                Log.w(TAG, "background task failed", e)
            }
        }
    }

    /**
     * Runs [task] on the I/O thread after everything queued before it and waits for its result. Only for the game
     * thread and tests, never for the UI thread; never from a task on the I/O thread itself (it would wait for itself).
     */
    fun <T> call(task: () -> T): T {
        check(Thread.currentThread().name != "GameIo") { "GameIo.call from the I/O thread" }
        try {
            return executor.submit(Callable(task)).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** Waits until every task queued so far has run; for tests. */
    fun awaitIdle() = call { }
}
