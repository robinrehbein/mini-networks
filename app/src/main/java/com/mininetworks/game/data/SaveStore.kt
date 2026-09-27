package com.mininetworks.game.data

import com.mininetworks.game.game.Save
import com.mininetworks.game.game.World
import com.mininetworks.game.game.WorldSnapshot
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The autosave: one JSON file ([Save]) in [dir], normally `Context.filesDir`. Plain blocking file access: the game
 * reaches it only through [SaveSlot], which runs it on the [GameIo] thread.
 */
class SaveStore(dir: File) {
    private val file = File(dir, FILE_NAME)
    private val temp = File(dir, "$FILE_NAME.tmp")

    val exists get() = file.isFile

    /**
     * Writes [world] to a temporary file and moves it over the save in one step, so the old save stays intact until
     * the new one is complete. Returns false on an I/O error.
     */
    fun save(world: World): Boolean = save(world.snapshot())

    /** Like [save] for a [World.snapshot] taken earlier, e.g. on another thread. */
    fun save(snapshot: WorldSnapshot): Boolean = try {
        temp.writeText(Save.encode(snapshot))
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        true
    } catch (_: IOException) {
        false
    }

    /**
     * The saved game, or null if there is none or it cannot be read (damaged, cut short, from another version, far too
     * big). A save that does not load is deleted, so a damaged file never crashes the app again and again and the
     * player simply starts a new game (docs/TOP100.md A2).
     */
    fun load(): World? {
        if (!exists) return null
        val world = try {
            if (file.length() > MAX_BYTES) null else Save.decode(file.readText())
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
        if (world == null) clear()
        return world
    }

    fun clear() {
        file.delete()
        temp.delete()
    }

    private companion object {
        const val FILE_NAME = "savegame.json"
        /** Far above any real save (a late game is a few hundred KB); anything bigger is not read into memory. */
        const val MAX_BYTES = 8L * 1024 * 1024
    }
}
