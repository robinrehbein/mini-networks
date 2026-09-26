package com.mininetworks.game.data

import com.mininetworks.game.game.Save
import com.mininetworks.game.game.World
import java.io.File
import java.io.IOException

/** The autosave: one JSON file ([Save]) in [dir], normally `Context.filesDir`. */
class SaveStore(dir: File) {
    private val file = File(dir, FILE_NAME)
    private val temp = File(dir, "$FILE_NAME.tmp")

    val exists get() = file.isFile

    /** Writes [world]; the old save stays intact until the new one is complete. Returns false on an I/O error. */
    fun save(world: World): Boolean = try {
        temp.writeText(Save.encode(world))
        temp.renameTo(file) || run { temp.copyTo(file, overwrite = true); temp.delete() }
    } catch (_: IOException) {
        false
    }

    /** The saved game, or null if there is none or it cannot be read. */
    fun load(): World? = try {
        if (exists) Save.decode(file.readText()) else null
    } catch (_: IOException) {
        null
    }

    fun clear() {
        file.delete()
        temp.delete()
    }

    private companion object {
        const val FILE_NAME = "savegame.json"
    }
}
