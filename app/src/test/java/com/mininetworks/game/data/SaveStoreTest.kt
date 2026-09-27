package com.mininetworks.game.data

import com.mininetworks.game.game.World
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveStoreTest {
    @get:Rule val dir = TemporaryFolder()

    @Test
    fun savesAndLoads() {
        val store = SaveStore(dir.root)
        assertTrue(store.save(World(seed = 3L)))
        assertTrue(store.exists)
        assertNotNull(store.load())
        assertFalse("no temp file left", File(dir.root, "savegame.json.tmp").exists())
    }

    @Test
    fun damagedSaveIsDroppedInsteadOfCrashing() {
        val store = SaveStore(dir.root)
        assertTrue(store.save(World(seed = 3L)))
        val file = File(dir.root, "savegame.json")
        // A client that lost its device: restore() must refuse it rather than crash later.
        file.writeText(file.readText().replaceFirst("\"device\":\"", "\"device\":null,\"x\":\""))
        assertNull(store.load())
        assertFalse("the bad save is gone, so Continue cannot crash again", store.exists)
    }
}
