package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

@OptIn(DebugApi::class)
class SaveTest {

    private val dt = 1f / 60f

    /** Wires every client without a cable to the nearest server it can use that still has a free port. */
    private fun wireClients(w: World) {
        for (client in w.nodes.filter { it.kind == NodeKind.CLIENT && w.ports(it) == 0 }) {
            val server = w.nodes
                .filter { it.kind == NodeKind.SERVER && it.service in client.device!!.services && w.ports(it) < it.maxPorts }
                .minByOrNull { abs(it.cellX - client.cellX) + abs(it.cellY - client.cellY) } ?: continue
            w.connect(client, server, w.unlockedCables.last())
        }
    }

    /**
     * Plays [seconds] with a simple fixed strategy: always the first reward, new clients wired right away. Requests
     * that pile up are dropped so the game keeps running.
     */
    private fun play(w: World, seconds: Int) {
        repeat(seconds * 60) { step ->
            if (w.rewardOffer != null) w.chooseReward(0)
            if (step % 60 == 0) wireClients(w)
            for (n in w.nodes) while (n.pending.size > 3) n.pending.removeFirst()
            w.update(dt)
        }
    }

    /** A game in week 4 with cables of several types, packets both ways, a data center and a router. */
    private fun busyWorld(): World {
        val w = World(seed = 5L)
        w.grant(400, 2)
        play(w, 150)
        val server = w.nodes.first { it.kind == NodeKind.SERVER }
        while (w.upgradeServer(server)) Unit
        val cell = (w.unlocked.left until w.unlocked.right).firstNotNullOf { x ->
            (w.unlocked.top until w.unlocked.bottom).firstOrNull { y -> w.isFree(x, y) }?.let { x to it }
        }
        assertNotNull(w.placeRouter(cell.first, cell.second))
        play(w, 5)
        assertTrue(w.week >= 4)
        assertTrue("packets in flight", w.packets.any { it.isResponse } && w.packets.any { !it.isResponse })
        assertTrue(server.isDataCenter)
        return w
    }

    @Test
    fun roundTripKeepsEveryField() {
        val w = busyWorld()
        val restored = Save.decode(Save.encode(w))
        assertNotNull(restored)
        assertEquals(w.snapshot(), restored!!.snapshot())
        assertEquals(w.cables.map { it.layout.waypoints }, restored.cables.map { it.layout.waypoints })
        assertEquals(w.nodes.map { it.footprint }, restored.nodes.map { it.footprint })
    }

    @Test
    fun restoredWorldContinuesExactlyLikeTheOriginal() {
        val original = busyWorld()
        val restored = Save.decode(Save.encode(original))!!
        play(original, 120)
        play(restored, 120)
        assertEquals(original.snapshot(), restored.snapshot())
        assertTrue("the continuation spawned new nodes", restored.nodes.size > busyWorld().nodes.size)
    }

    @Test
    fun savingDoesNotChangeTheGame() {
        val saved = busyWorld()
        Save.encode(saved)
        play(saved, 60)
        val untouched = busyWorld()
        play(untouched, 60)
        assertEquals(untouched.snapshot(), saved.snapshot())
    }

    @Test
    fun openRewardOfferAndWeekNewsSurvive() {
        val w = World(seed = 9L)
        w.jumpToWeek(4)
        w.advanceToNextWeek()
        val offer = w.rewardOffer!!
        val restored = Save.decode(Save.encode(w))!!
        assertEquals(offer.week, restored.rewardOffer!!.week)
        assertEquals(offer.choices, restored.rewardOffer!!.choices)
        assertEquals(w.lastNews, restored.lastNews)
        assertEquals(w.unlocked, restored.unlocked)
        restored.update(dt)
        assertEquals("still paused for the choice", w.time, restored.time)
        assertTrue(restored.chooseReward(1))
    }

    @Test
    fun gameOverSurvivesWithTheFailedNode() {
        val w = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false)
        w.addServer(Service.MAIL, 1, 1)
        val pc = w.addClient(Device.PC, 3, 3)
        while (!w.gameOver) {
            pc.pending.addLast(Service.MAIL)
            w.update(0.5f)
        }
        val restored = Save.decode(Save.encode(w))!!
        assertTrue(restored.gameOver)
        assertSame(restored.nodes.first { it.id == pc.id }, restored.failedNode)
    }

    @Test
    fun customWaterIsKept() {
        val w = World(cols = 12, rows = 8, seed = 3L, spawnInitialNodes = false)
        for (row in w.water) row.fill(false)
        w.water[2][4] = true
        val restored = Save.decode(Save.encode(w))!!
        for (y in 0 until w.rows) for (x in 0 until w.cols) assertEquals(w.water[y][x], restored.water[y][x])
    }

    @Test
    fun damagedOrForeignSavesAreRejected() {
        val json = Save.encode(busyWorld())
        assertNull(Save.decode(""))
        assertNull(Save.decode("{\"hello\": 1}"))
        assertNull(Save.decode(json.dropLast(20)))
        assertNull("other version", Save.decode(json.replaceFirst("\"version\":1", "\"version\":99")))
        val snapshot = busyWorld().snapshot()
        val broken = snapshot.copy(cables = snapshot.cables + snapshot.cables.first().copy(a = 999))
        assertNull("unknown node id", Save.decode(kotlinx.serialization.json.Json.encodeToString(WorldSnapshot.serializer(), broken)))
    }

    @Test
    fun replayableRandomMatchesPlainRandomAndResumes() {
        val plain = Random(42L)
        val replayable = ReplayableRandom(42L)
        repeat(50) {
            assertEquals(plain.nextInt(17), replayable.nextInt(17))
            assertEquals(plain.nextFloat(), replayable.nextFloat())
        }
        val resumed = ReplayableRandom.restore(42L, replayable.draws)
        assertEquals(replayable.draws, resumed.draws)
        repeat(50) { assertEquals(replayable.nextInt(), resumed.nextInt()) }
        assertEquals(listOf(1, 2, 3, 4, 5).shuffled(Random(8L)), listOf(1, 2, 3, 4, 5).shuffled(ReplayableRandom(8L)))
    }
}
