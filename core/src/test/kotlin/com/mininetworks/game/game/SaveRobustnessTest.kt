package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Damaged and old saves (docs/TOP100.md A2): [Save.decode] never throws, whatever the file holds. It either returns
 * null (the app then drops the file and starts clean, see the app's SaveStoreTest) or a world that runs, draws its
 * packets and saves again without an exception.
 */
@OptIn(DebugApi::class)
class SaveRobustnessTest {

    /** A rich save: a later-week game of the future scenery with radios, incidents and packets in flight. */
    private val base: String by lazy { SoakRun.play(4, 240f).finalSave }

    /** Decodes [text]; a world that loads must also run for [steps] steps and encode again. */
    private fun decodeAndRun(text: String, label: String, steps: Int = 120): World? {
        val w = try {
            Save.decode(text)
        } catch (e: Throwable) {
            throw AssertionError("$label: decode threw", e)
        }
        if (w == null) return null
        try {
            repeat(steps) {
                if (w.rewardOffer != null) w.chooseReward(0)
                w.update(1f / 60f)
                for (p in w.packets) w.packetPosition(p)
            }
            w.failure
            Save.encode(w)
        } catch (e: Throwable) {
            throw AssertionError("$label: the loaded world crashed", e)
        }
        return w
    }

    @Test
    fun theBaseSaveIsValid() {
        val w = requireNotNull(decodeAndRun(base, "base"))
        assertTrue("packets in flight", w.packets.isNotEmpty())
        assertTrue("several kinds of nodes", w.nodes.map { it.kind }.toSet().size >= 3)
    }

    @Test
    fun garbageNeverThrows() {
        val samples = listOf(
            "", " ", "null", "{}", "[]", "0", "\"text\"", "{\"version\":1}", "{\"version\":\"one\"}",
            "\u0000\u0001\u0002", "{".repeat(5_000), "[".repeat(100_000), "{\"a\":".repeat(10_000),
            base.substring(0, base.length / 2), base + "}}}", base.replace(":", "="), base.reversed(),
        )
        for ((i, s) in samples.withIndex()) assertNull("sample $i", decodeAndRun(s, "sample $i"))
        val rnd = Random(11L)
        repeat(200) { i ->
            val bytes = ByteArray(rnd.nextInt(1, 400)).also(rnd::nextBytes)
            assertNull(decodeAndRun(String(bytes, Charsets.ISO_8859_1), "random bytes $i"))
        }
    }

    @Test
    fun savesOfOtherVersionsAreRefused() {
        for (v in listOf(0, 2, -1, Int.MAX_VALUE)) {
            assertNull("version $v", decodeAndRun(base.replaceFirst("\"version\":1", "\"version\":$v"), "version $v"))
        }
    }

    /** A save written before scenarios, radios and incidents existed has none of their fields and still loads. */
    @Test
    fun oldSaveWithoutLaterFieldsLoads() {
        val w = World(seed = 4L)
        repeat(600) { if (w.rewardOffer != null) w.chooseReward(0); w.update(1f / 60f) }
        var old = Save.encode(w)
        for (key in listOf("scenario", "accessPointsAvailable", "cellTowersAvailable", "continued", "incidentsEnabled")) {
            old = old.replace(Regex(",?\"$key\":[^,}\\]]*"), "")
        }
        old = old.replace(Regex(",?\"incidents\":\\[[^\\]]*\\]"), "").replace(Regex(",?\"(channel|fiveGhz)\":[^,}]*"), "")
        assertTrue(!old.contains("\"scenario\"") && !old.contains("\"incidents\""))
        val loaded = requireNotNull(decodeAndRun(old, "old save"))
        assertEquals(Scenarios.RIVER_TOWN, loaded.scenario)
        assertEquals(w.nodes.size, loaded.nodes.size)
    }

    /**
     * Regression (found by the soak test): a request on its first cable whose next cable was removed meanwhile is a
     * normal state of a running game; the save must load and the request goes back to its client when it gets there.
     */
    @Test
    fun packetWhoseLaterCableWasRemovedStillLoads() {
        val w = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false)
        w.grant(1000)
        val pc = w.addClient(Device.PC, 1, 1)
        val router = w.addRouter(3, 1)
        val mail = w.addServer(Service.MAIL, 5, 1)
        assertTrue(w.connect(pc, router, CableType.ISDN))
        assertTrue(w.connect(router, mail, CableType.ISDN))
        var steps = 0
        while (w.packets.none { !it.isResponse && it.hop == 0 && it.progress in 0f..0.5f }) {
            w.update(1f / 60f)
            check(++steps < 60 * 60) { "no request set off" }
        }
        w.removeCable(w.cableBetween(router, mail)!!)
        assertTrue("the request is still on its first cable", w.packets.any { !it.isResponse && it.hop == 0 })
        val text = Save.encode(w)
        val loaded = requireNotNull(Save.decode(text)) { "the save does not load" }
        assertEquals(text, Save.encode(loaded))
        repeat(600) { loaded.update(1f / 60f); w.update(1f / 60f) }
        assertEquals("the loaded game goes on exactly like the original", Save.encode(w), Save.encode(loaded))
    }

    /** Thousands of random edits of a real save: truncations, flipped characters, cut or doubled ranges, odd numbers. */
    @Test
    fun randomlyDamagedSavesNeverCrash() {
        val rnd = Random(2024L)
        val numbers = Regex("-?\\d+(\\.\\d+)?(E-?\\d+)?")
        val odd = listOf("-1", "0", "1e30", "-1e30", "NaN", "null", "true", "\"x\"", "2147483648", "99999", "0.5", "[]", "{}")
        var loaded = 0
        repeat(ROUNDS) { round ->
            var text = base
            repeat(1 + rnd.nextInt(3)) {
                val i = rnd.nextInt(text.length)
                text = when (rnd.nextInt(6)) {
                    0 -> text.substring(0, i)
                    1 -> text.substring(0, i) + (32 + rnd.nextInt(95)).toChar() + text.substring(i + 1)
                    2 -> text.removeRange(i, minOf(text.length, i + 1 + rnd.nextInt(40)))
                    3 -> text.substring(0, i) + text.substring(i, minOf(text.length, i + 1 + rnd.nextInt(80))) + text.substring(i)
                    else -> {
                        val all = numbers.findAll(text).toList()
                        val m = all[rnd.nextInt(all.size)]
                        text.replaceRange(m.range, odd[rnd.nextInt(odd.size)])
                    }
                }
            }
            if (decodeAndRun(text, "round $round", steps = 30) != null) loaded++
        }
        println("SaveRobustnessTest: $loaded of $ROUNDS damaged saves still loaded and ran, the rest were refused")
    }

    private companion object {
        const val ROUNDS = 3000
    }
}
