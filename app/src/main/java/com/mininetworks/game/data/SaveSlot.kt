package com.mininetworks.game.data

import com.mininetworks.game.game.World
import java.io.File

/**
 * The autosave as the game uses it: every access to the file runs on the [GameIo] thread, in the order it was asked
 * for (docs/TOP100.md A3). [saveLater] takes the [World.snapshot] right away on the calling thread (cheap, and the
 * world may change right after) and leaves encoding and writing to the I/O thread, so the UI thread can save in
 * `onPause` without touching the disk. [load] and [exists] wait for the answer and are meant for the game thread.
 * The [SaveStore] itself is only created on the I/O thread, since resolving [dir] may create the directory.
 */
class SaveSlot(private val dir: () -> File) {
    private var store: SaveStore? = null // I/O thread only

    private fun store() = store ?: SaveStore(dir()).also { store = it }

    fun saveLater(world: World) {
        val snapshot = world.snapshot()
        GameIo.execute { store().save(snapshot) }
    }

    fun clearLater() = GameIo.execute { store().clear() }

    /** The saved game (see [SaveStore.load]); blocks until the I/O thread has read it. Not for the UI thread. */
    fun load(): World? = GameIo.call { store().load() }

    /** True if a save file exists once everything queued before is done. Not for the UI thread. */
    fun exists(): Boolean = GameIo.call { store().exists }
}
