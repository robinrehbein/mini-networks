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

    private fun decode(s: WorldSnapshot) = Save.decode(kotlinx.serialization.json.Json.encodeToString(WorldSnapshot.serializer(), s))

    /** Replaces the first node matching [pick] in [s] by [change] of it. */
    private fun withNode(s: WorldSnapshot, pick: (NodeSnapshot) -> Boolean, change: (NodeSnapshot) -> NodeSnapshot) =
        s.copy(nodes = s.nodes.map { if (it === s.nodes.first(pick)) change(it) else it })

    @Test
    fun inconsistentNodesAreRejected() {
        val s = busyWorld().snapshot()
        assertNotNull("the untouched snapshot loads", decode(s))
        val client = { n: NodeSnapshot -> n.kind == NodeKind.CLIENT }
        val server = { n: NodeSnapshot -> n.kind == NodeKind.SERVER }
        assertNull("client without a device", decode(withNode(s, client) { it.copy(device = null) }))
        assertNull("server without a service", decode(withNode(s, server) { it.copy(service = null) }))
        assertNull("router with a device", decode(withNode(s, { it.kind == NodeKind.ROUTER }) { it.copy(device = Device.PC) }))
        assertNull("level 0", decode(withNode(s, server) { it.copy(level = 0) }))
        assertNull("level 5", decode(withNode(s, server) { it.copy(level = World.Tuning.MAX_SERVER_LEVEL + 1) }))
        assertNull("footprint off the grid", decode(withNode(s, client) { it.copy(footprint = listOf(Cell(it.cellX, it.cellY), Cell(s.cols, 0))) }))
        assertNull("cell off the grid", decode(withNode(s, client) { it.copy(cellX = -1, footprint = listOf(Cell(-1, it.cellY))) }))
        assertNull("footprint not at the cell", decode(withNode(s, client) { it.copy(footprint = listOf(Cell(0, 0))) }))
        assertNull("overload past full", decode(withNode(s, client) { it.copy(overload = 2f) }))
    }

    @Test
    fun brokenGridCalendarAndPacketsAreRejected() {
        val s = busyWorld().snapshot()
        assertNull("unlocked block off the grid", decode(s.copy(unlocked = CellRect(-1, 0, s.cols, s.rows))))
        assertNull("unlocked block too wide", decode(s.copy(unlocked = CellRect(0, 0, s.cols + 1, s.rows))))
        assertNull("week 0", decode(s.copy(week = 0)))
        assertNull("id reused by the next node", decode(s.copy(nextId = 0)))
        assertNull("endless replay", decode(s.copy(randomDraws = Long.MAX_VALUE)))
        assertNull("cable waypoint off the grid", decode(s.copy(cables = listOf(s.cables.first().let { it.copy(waypoints = it.waypoints + Cell(999, 0)) }) + s.cables.drop(1))))
        val p = s.packets.first()
        fun withPacket(q: PacketSnapshot) = s.copy(packets = listOf(q) + s.packets.drop(1))
        assertNull("packet already at its end", decode(withPacket(p.copy(hop = p.route.size - 1))))
        assertNull("packet progress past the link", decode(withPacket(p.copy(progress = 3f))))
        val linked = s.cables.filter { p.route.first() in listOf(it.a, it.b) }.flatMap { listOf(it.a, it.b) }
        val stranger = s.nodes.first { n -> n.kind == NodeKind.SERVER && n.id !in p.route && n.id !in linked }.id
        assertNull("route over nodes without a link", decode(withPacket(p.copy(route = listOf(p.route.first(), stranger)))))
        assertNull("origin not at the route's end", decode(withPacket(p.copy(origin = stranger))))
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
