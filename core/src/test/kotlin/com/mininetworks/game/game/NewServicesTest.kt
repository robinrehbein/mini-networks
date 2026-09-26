package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(DebugApi::class)
class NewServicesTest {

    private val dt = 1f / 60f

    private fun dryWorld(): World {
        val w = World(cols = 24, rows = 12, seed = 1L, spawnInitialNodes = false)
        for (row in w.water) row.fill(false)
        w.grant(500)
        return w
    }

    private fun run(w: World, seconds: Float) = repeat((seconds / dt).toInt()) { w.update(dt) }

    /** Game time of the first nightly backup: 02:00 on the first day, the clock starts at 06:00. */
    private val firstBackup = (24f - World.Tuning.DAWN_HOUR + World.Tuning.BACKUP_HOUR) / 24f * World.Tuning.DAY_SECONDS

    // ---------------------------------------------------------------- values

    @Test
    fun newServicesMatchThePlan() {
        with(Service.VIDEO_CALL) {
            assertEquals(2, bandwidth)
            assertEquals("120 ms each way", 240, maxPingMs)
            assertEquals(Demand.RANDOM, demand)
            assertFalse(upload)
        }
        with(Service.CAMERA_UPLOAD) {
            assertEquals(2, bandwidth)
            assertNull(maxPingMs)
            assertEquals(Demand.STREAM, demand)
            assertTrue(upload)
        }
        with(Service.CLOUD_BACKUP) {
            assertEquals(4, bandwidth)
            assertNull(maxPingMs)
            assertEquals(Demand.NIGHTLY, demand)
            assertTrue(upload)
        }
        assertEquals(Shape.PENTAGON, Service.VIDEO_CALL.shape)
        assertEquals(Shape.HEXAGON, Service.CAMERA_UPLOAD.shape)
        assertEquals(Shape.PLUS, Service.CLOUD_BACKUP.shape)
        assertEquals("every service has its own shape", Service.entries.size, Service.entries.map { it.shape }.toSet().size)
        assertTrue(Service.GAMING.maxPingMs!! < Service.VIDEO_CALL.maxPingMs!!)
        assertTrue(Service.VIDEO_CALL.maxPingMs!! < Service.CALL.maxPingMs!!)
    }

    @Test
    fun cameraAndSmartHomeArriveInWeekSeven() {
        assertEquals(7, Device.CAMERA.unlockWeek)
        assertEquals(7, Device.SMART_HOME.unlockWeek)
        assertEquals(listOf(Service.CAMERA_UPLOAD), Device.CAMERA.services)
        assertEquals(Service.CAMERA_UPLOAD, Device.CAMERA.stream)
        assertTrue(Device.entries.filter { it != Device.CAMERA }.all { it.stream == null })
        assertTrue(Service.CLOUD_BACKUP in Device.SMART_HOME.services)
        assertTrue("WLAN reaches the camera", RadioType.WLAN.serves(Device.CAMERA))
        assertFalse("the camera is not mobile", RadioType.CELL.serves(Device.CAMERA))
    }

    @Test
    fun serversOfNewServicesFollowTheSchedule() {
        val w = dryWorld()
        w.jumpToWeek(5)
        fun next(): WeekNews? {
            w.rewardOffer?.let { w.chooseReward(0) }
            val before = w.lastNews
            w.advanceToNextWeek()
            return w.lastNews.takeIf { it !== before }
        }
        fun servers(s: Service) = w.nodes.count { it.kind == NodeKind.SERVER && it.service == s }

        assertEquals(listOf(Service.VIDEO_CALL), next()!!.servers)
        assertEquals(1, servers(Service.VIDEO_CALL))
        val week7 = next()!!
        assertEquals(listOf(Service.CAMERA_UPLOAD), week7.servers)
        assertEquals(listOf(Device.CAMERA, Device.SMART_HOME), week7.devices)
        assertEquals(1, servers(Service.CAMERA_UPLOAD))
        assertEquals(listOf(Service.CLOUD_BACKUP), next()!!.servers)
        assertEquals(1, servers(Service.CLOUD_BACKUP))
        val count = w.nodes.size
        assertNull("week 9 brings no server", next())
        assertEquals(count, w.nodes.size)
        assertEquals("week 10 brings a random one", 1, next()!!.servers.size)
        assertEquals(count + 1, w.nodes.size)
    }

    // ---------------------------------------------------------------- video call

    @Test
    fun videoCallNeedsPingAndBandwidth() {
        val w = dryWorld()
        w.jumpToWeek(CableType.FIBER.unlockWeek)
        val near = w.addClient(Device.LAPTOP, 1, 4)
        val nearServer = w.addServer(Service.VIDEO_CALL, 4, 4)
        assertTrue(w.connect(near, nearServer, CableType.DSL))
        assertNotNull("3 cells DSL are fine", w.routeFor(near, Service.VIDEO_CALL))

        val far = w.addClient(Device.LAPTOP, 1, 1)
        val farServer = w.addServer(Service.VIDEO_CALL, 20, 1)
        assertTrue(w.connect(far, farServer, CableType.DSL))
        assertNotNull(w.bestRoute(far, Service.VIDEO_CALL))
        assertNull("19 cells DSL are too slow both ways", w.routeFor(far, Service.VIDEO_CALL))
        assertTrue(w.upgrade(w.cableBetween(far, farServer)!!, CableType.FIBER))
        assertNotNull("fiber makes it", w.routeFor(far, Service.VIDEO_CALL))
    }

    @Test
    fun videoCallFillsAnIsdnLine() {
        val w = dryWorld()
        val laptop = w.addClient(Device.LAPTOP, 2, 2)
        val server = w.addServer(Service.VIDEO_CALL, 5, 2)
        assertTrue(w.connect(laptop, server, CableType.ISDN))
        val cable = w.cableBetween(laptop, server)!!
        repeat(3) { laptop.pending.addLast(Service.VIDEO_CALL) }
        var sawCall = false
        repeat(60 * 6) {
            w.update(dt)
            assertTrue(w.cableLoad(cable) <= CableType.ISDN.capacity)
            if (w.cableLoad(cable) == 2 && w.packets.any { !it.isResponse }) sawCall = true
        }
        assertTrue("one call takes the whole ISDN line", sawCall)
        assertTrue(w.delivered >= 1)
    }

    // ---------------------------------------------------------------- camera upload

    /** Seconds between the camera's queued requests over [seconds] of play. */
    private fun cameraIntervals(week: Int, seconds: Float): List<Float> {
        val w = dryWorld()
        w.jumpToWeek(week)
        w.addServer(Service.CAMERA_UPLOAD, 10, 5)
        val camera = w.addClient(Device.CAMERA, 2, 2)
        val times = ArrayList<Float>()
        var last = 0
        repeat((seconds / dt).toInt()) {
            w.update(dt)
            if (camera.pending.size > last) times += w.time
            last = camera.pending.size
        }
        assertTrue(camera.pending.all { it == Service.CAMERA_UPLOAD })
        return times.zipWithNext { a, b -> b - a }
    }

    @Test
    fun cameraStreamsInAFixedRhythm() {
        val early = cameraIntervals(week = 7, seconds = 18f)
        assertTrue(early.size >= 4)
        for (gap in early) assertEquals(World.Tuning.STREAM_SECONDS, gap, 2 * dt)
        val late = cameraIntervals(week = 14, seconds = 18f)
        assertEquals("the stream does not speed up like other devices", early.size, late.size)
        for (gap in late) assertEquals(World.Tuning.STREAM_SECONDS, gap, 2 * dt)
    }

    @Test
    fun cameraWithoutUploadServerStaysQuiet() {
        val w = dryWorld()
        w.jumpToWeek(7)
        w.addServer(Service.MAIL, 10, 5)
        val camera = w.addClient(Device.CAMERA, 2, 2)
        run(w, 12f)
        assertTrue(camera.pending.isEmpty())
    }

    @Test
    fun uploadsGetSmallAcknowledgements() {
        val w = dryWorld()
        w.jumpToWeek(CableType.DSL.unlockWeek)
        val camera = w.addClient(Device.CAMERA, 2, 2)
        val server = w.addServer(Service.CAMERA_UPLOAD, 6, 2)
        assertTrue(w.connect(camera, server, CableType.DSL))
        var request: Packet? = null
        var response: Packet? = null
        repeat(60 * 15) {
            w.update(dt)
            w.packets.forEach { if (it.isResponse) response = it else request = it }
        }
        assertEquals(2, request!!.size)
        assertEquals(Service.ACK_SIZE, response!!.size)
        assertTrue("delivered ${w.delivered}", w.delivered >= 2)

        val route = listOf(camera, server)
        assertEquals(1, Packet(Service.CLOUD_BACKUP, camera, route, isResponse = true).size)
        assertEquals(4, Packet(Service.CLOUD_BACKUP, camera, route).size)
        assertEquals("downloads answer in full", 3, Packet(Service.STREAMING, camera, route, isResponse = true).size)
        assertEquals(2, Packet(Service.VIDEO_CALL, camera, route, isResponse = true).size)
    }

    // ---------------------------------------------------------------- cloud backup

    @Test
    fun clockRunsThreeDaysPerWeek() {
        assertEquals(3f, World.Tuning.WEEK_SECONDS / World.Tuning.DAY_SECONDS, 1e-6f)
        assertEquals(6f, World.hourAt(0f), 1e-4f)
        assertEquals(18f, World.hourAt(World.Tuning.DAY_SECONDS / 2f), 1e-4f)
        assertEquals(6f, World.hourAt(World.Tuning.DAY_SECONDS), 1e-3f)
        assertEquals(2f, World.hourAt(firstBackup), 1e-3f)
        val w = dryWorld()
        assertFalse(w.isNight)
        run(w, World.Tuning.DAY_SECONDS * 0.5f)
        assertFalse("18:00", w.isNight)
        run(w, World.Tuning.DAY_SECONDS * 0.25f)
        assertTrue("00:00", w.isNight)
    }

    @Test
    fun backupsBurstEverywhereAtTwoAtNight() {
        val w = dryWorld()
        w.jumpToWeek(8)
        w.addServer(Service.CLOUD_BACKUP, 12, 6)
        val users = listOf(w.addClient(Device.PC, 2, 2), w.addClient(Device.LAPTOP, 5, 2), w.addClient(Device.SMART_HOME, 8, 2))
        val others = listOf(w.addClient(Device.CAMERA, 2, 8), w.addClient(Device.CONSOLE, 5, 8), w.addClient(Device.TABLET, 8, 8))
        val start = w.time
        run(w, firstBackup - 0.1f)
        assertTrue("no backups by day", w.nodes.all { it.pending.isEmpty() })
        run(w, 0.2f)
        for (n in users) assertEquals(List(World.Tuning.BACKUP_BURST) { Service.CLOUD_BACKUP }, n.pending.toList())
        for (n in others) assertTrue(n.pending.isEmpty())
        assertEquals(2f, w.hourOfDay, 0.5f)

        users[0].pending.clear()
        run(w, start + firstBackup + World.Tuning.DAY_SECONDS + 0.1f - w.time)
        assertEquals("the next night, a fresh burst", World.Tuning.BACKUP_BURST, users[0].pending.size)
        for (n in users.drop(1)) assertEquals("a backup still waiting blocks the next", World.Tuning.BACKUP_BURST, n.pending.size)
    }

    @Test
    fun regularRequestsNeverPickBackups() {
        val w = dryWorld()
        w.jumpToWeek(8)
        w.addServer(Service.CLOUD_BACKUP, 12, 6)
        w.addServer(Service.MAIL, 12, 2)
        val pc = w.addClient(Device.PC, 2, 2)
        run(w, firstBackup - 0.1f)
        assertTrue(pc.pending.isNotEmpty())
        assertTrue(pc.pending.all { it == Service.MAIL })
    }

    @Test
    fun backupsNeedAThickCableAndGetDelivered() {
        val w = dryWorld()
        w.jumpToWeek(8)
        w.incidentsEnabled = false
        val server = w.addServer(Service.CLOUD_BACKUP, 6, 2)
        val pc = w.addClient(Device.PC, 2, 2)
        assertTrue(w.connect(pc, server, CableType.ISDN))
        assertNull("ISDN cannot carry a backup", w.routeFor(pc, Service.CLOUD_BACKUP))
        assertTrue(w.upgrade(w.cableBetween(pc, server)!!, CableType.DSL))
        assertNotNull(w.routeFor(pc, Service.CLOUD_BACKUP))
        run(w, firstBackup + 10f)
        assertEquals(World.Tuning.BACKUP_BURST, w.delivered)
        assertTrue(pc.pending.isEmpty())
    }

    @Test
    fun noBackupsWithoutServer() {
        val w = dryWorld()
        w.jumpToWeek(8)
        val pc = w.addClient(Device.PC, 2, 2)
        run(w, firstBackup + 1f)
        assertTrue(pc.pending.isEmpty())
    }

    // ---------------------------------------------------------------- save

    @Test
    fun savedGameKeepsStreamAndNightRhythm() {
        val w = dryWorld()
        w.jumpToWeek(8)
        w.addServer(Service.CLOUD_BACKUP, 12, 6)
        val upload = w.addServer(Service.CAMERA_UPLOAD, 12, 2)
        val camera = w.addClient(Device.CAMERA, 2, 2)
        assertTrue(w.connect(camera, upload, CableType.DSL))
        w.addClient(Device.SMART_HOME, 5, 5)
        run(w, 7f)
        val restored = Save.decode(Save.encode(w))!!
        run(w, 20f)
        run(restored, 20f)
        assertEquals(w.snapshot(), restored.snapshot())
        assertTrue(w.nodes.any { Service.CLOUD_BACKUP in it.pending })
    }
}
