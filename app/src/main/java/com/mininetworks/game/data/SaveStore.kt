package com.mininetworks.game.data

import com.mininetworks.game.game.Save
import com.mininetworks.game.game.World
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The autosave: one JSON file ([Save]) in [dir], normally `Context.filesDir`. */
class SaveStore(dir: File) {
    private val file = File(dir, FILE_NAME)
    private val temp = File(dir, "$FILE_NAME.tmp")

    val exists get() = file.isFile

    /**
     * Writes [world] to a temporary file and moves it over the save in one step, so the old save stays intact until
     * the new one is complete. Returns false on an I/O error.
     */
    fun save(world: World): Boolean = try {
        temp.writeText(Save.encode(world))
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
     * The saved game, or null if there is none or it cannot be read. A save that does not load is deleted, so a
     * damaged file never crashes the app again and again.
     */
    fun load(): World? {
        if (!exists) return null
        val world = try {
            Save.decode(file.readText())
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
    }
}
