package com.mininetworks.game.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(DebugApi::class)
class WirelessTest {

    private val step = 1f / 60f

    /** Empty dry world in week 7, where both WLAN and cell towers exist. */
    private fun world(seed: Long = 1L) = World(cols = 16, rows = 10, seed = seed, spawnInitialNodes = false).also { w ->
        for (row in w.water) row.fill(false)
        w.jumpToWeek(7)
        w.grant(200)
    }

    private fun linked(w: World, radio: Node) = w.radioLinks.filter { it.radio === radio }.map { it.device }.toSet()

    // ---------------------------------------------------------------- capacity formula

    @Test
    fun interferenceCostsThirtyPercentPerNeighbourButNeverBelowOne() {
        assertEquals(4, Wifi.reduced(4, 0))
        assertEquals(3, Wifi.reduced(4, 1))
        assertEquals(2, Wifi.reduced(4, 2))
        assertEquals(1, Wifi.reduced(4, 3))
        assertEquals(1, Wifi.reduced(4, 5))
        assertEquals(6, Wifi.reduced(8, 1))
        assertEquals(1, Wifi.reduced(1, 1))
    }

    @Test
    fun radioSpecsFollowThePlan() {
        assertEquals(1.5f, RadioType.WLAN.radius)
        assertEquals(4, RadioType.WLAN.capacity)
        assertEquals(4, RadioType.WLAN.maxDevices)
        assertEquals(5f, RadioType.WLAN.latencyMs)
        assertEquals(3f, RadioType.CELL.radius)
        assertEquals(8, RadioType.CELL.capacity)
        assertEquals(15f, RadioType.CELL.latencyMs)
        assertEquals(setOf(Device.SMARTPHONE, Device.TABLET, Device.WATCH), Device.entries.filter(RadioType.CELL::serves).toSet())
        assertTrue(Device.entries.all(RadioType.WLAN::serves))
    }

    // ---------------------------------------------------------------- coverage

    @Test
    fun accessPointLinksClientsWithinItsRadius() {
        val w = world()
        val ap = w.addRadio(RadioType.WLAN, 5, 5)
        val side = w.addClient(Device.PC, 6, 5)
        val diagonal = w.addClient(Device.LAPTOP, 6, 6)
        val twoAway = w.addClient(Device.TV, 7, 5)
        assertEquals(setOf(side, diagonal), linked(w, ap))
        assertNull(w.linkBetween(ap, twoAway))
        assertEquals(RadioType.WLAN.capacity, w.linkBetween(ap, side)!!.capacity)
    }

    @Test
    fun accessPointTakesTheFourNearestDevices() {
        val w = world()
        val ap = w.addRadio(RadioType.WLAN, 5, 5)
        val diagonals = listOf(4 to 4, 6 to 6).map { (x, y) -> w.addClient(Device.PC, x, y) }
        val sides = listOf(4 to 5, 6 to 5, 5 to 4, 5 to 6).map { (x, y) -> w.addClient(Device.PC, x, y) }
        assertEquals(sides.toSet(), linked(w, ap))
        assertTrue(diagonals.none { w.linkBetween(ap, it) != null })
    }

    @Test
    fun cellTowerServesOnlyMobileDevicesWithinThreeCells() {
        val w = world()
        val tower = w.addRadio(RadioType.CELL, 5, 5)
        val phone = w.addClient(Device.SMARTPHONE, 8, 5)
        val tablet = w.addClient(Device.TABLET, 7, 7)
        w.addClient(Device.WATCH, 5, 9)
        w.addClient(Device.PC, 6, 5)
        w.addClient(Device.LAPTOP, 5, 4)
        assertEquals(setOf(phone, tablet), linked(w, tower))
        assertNull("towers have no device limit", w.radioSlots(tower))
        assertEquals(8, w.radioCapacity(tower))
    }

    @Test
    fun cableBetweenRadioAndClientReplacesTheRadioLink() {
        val w = world()
        val ap = w.addRadio(RadioType.WLAN, 5, 5)
        val pc = w.addClient(Device.PC, 6, 5)
        assertTrue(w.linkBetween(ap, pc) is RadioLink)
        assertTrue(w.connect(ap, pc, CableType.DSL))
        assertSame(w.cableBetween(ap, pc), w.linkBetween(ap, pc))
        assertTrue(w.radioLinks.none { it.device === pc })
        w.removeCable(w.cableBetween(ap, pc)!!)
        assertTrue(w.linkBetween(ap, pc) is RadioLink)
    }

    @Test
    fun radiosHaveFewCablePorts() {
        assertEquals(2, NodeKind.ACCESS_POINT.maxPorts)
        assertEquals(4, NodeKind.CELL_TOWER.maxPorts)
    }

    // ---------------------------------------------------------------- routing

    @Test
    fun routeRunsOverTheRadioLinkAndTheAccessPointCable() {
        val w = world()
        val phone = w.addClient(Device.SMARTPHONE, 1, 1)
        val ap = w.addRadio(RadioType.WLAN, 2, 2)
        val server = w.addServer(Service.CALL, 6, 2)
        assertTrue(w.connect(ap, server, CableType.DSL))
        val route = w.routeFor(phone, Service.CALL)
        assertNotNull(route)
        assertEquals(listOf(phone, ap, server), route!!.nodes)
        val oneWay = RadioType.WLAN.latencyMs + World.Tuning.ROUTER_MS + 4 * CableType.DSL.msPerCell
        assertEquals(2 * oneWay, route.pingMs, 0.01f)
    }

    @Test
    fun cellTowerHopIsSlowerThanWlan() {
        val w = world()
        val phone = w.addClient(Device.SMARTPHONE, 1, 1)
        val tower = w.addRadio(RadioType.CELL, 3, 1)
        val server = w.addServer(Service.CALL, 6, 1)
        assertTrue(w.connect(tower, server, CableType.DSL))
        val route = w.routeFor(phone, Service.CALL)!!
        assertEquals(listOf(phone, tower, server), route.nodes)
        assertEquals(2 * (RadioType.CELL.latencyMs + World.Tuning.ROUTER_MS + 3 * CableType.DSL.msPerCell), route.pingMs, 0.01f)
    }

    @Test
    fun accessPointMustBeCabledItself() {
        val w = world()
        val a = w.addClient(Device.SMARTPHONE, 1, 1)
        val ap = w.addRadio(RadioType.WLAN, 2, 2)
        val b = w.addClient(Device.SMARTPHONE, 3, 1)
        val server = w.addServer(Service.CALL, 6, 1)
        assertTrue(w.connect(b, server, CableType.DSL))
        assertEquals(setOf(a, b), linked(w, ap))
        assertNull("no detour through another wireless client", w.routeFor(a, Service.CALL))
        assertTrue(w.connect(ap, server, CableType.DSL))
        assertEquals(listOf(a, ap, server), w.routeFor(a, Service.CALL)!!.nodes)
    }

    @Test
    fun cabledDeviceCannotRideOnAPhoneIntoTheCellTower() {
        val w = world()
        val pc = w.addClient(Device.PC, 1, 1)
        val phone = w.addClient(Device.SMARTPHONE, 2, 1)
        val tower = w.addRadio(RadioType.CELL, 4, 1)
        val server = w.addServer(Service.CALL, 7, 1)
        assertTrue(w.connect(tower, server, CableType.DSL))
        assertTrue(w.connect(pc, phone, CableType.DSL))
        assertEquals(setOf(phone), linked(w, tower))
        assertEquals(listOf(phone, tower, server), w.routeFor(phone, Service.CALL)!!.nodes)
        assertNull("towers only serve mobile devices", w.bestRoute(pc, Service.CALL))
    }

    @Test
    fun cabledDeviceCannotRideOnAWlanClient() {
        val w = world()
        val pc = w.addClient(Device.PC, 1, 1)
        val laptop = w.addClient(Device.LAPTOP, 2, 1)
        val ap = w.addRadio(RadioType.WLAN, 3, 1)
        val server = w.addServer(Service.CALL, 6, 1)
        assertTrue(w.connect(ap, server, CableType.DSL))
        assertTrue(w.connect(pc, laptop, CableType.DSL))
        assertEquals(setOf(laptop), linked(w, ap))
        assertNotNull(w.routeFor(laptop, Service.CALL))
        assertNull("the AP's device limit counts only devices on the air", w.bestRoute(pc, Service.CALL))
    }

    @Test
    fun requestAndResponseTravelOverTheAir() {
        val w = world()
        val phone = w.addClient(Device.SMARTPHONE, 1, 1)
        val ap = w.addRadio(RadioType.WLAN, 2, 2)
        val server = w.addServer(Service.CALL, 6, 2)
        assertTrue(w.connect(ap, server, CableType.DSL))
        phone.pending.addLast(Service.CALL)
        var sawRadioPacket = false
        var guard = 0
        while (w.delivered == 0) {
            w.update(step)
            for (p in w.packets) if (p.inTransit && p.progress >= 0f && w.linkBetween(p.from, p.to) is RadioLink) {
                sawRadioPacket = true
                val pos = w.packetPosition(p)
                assertTrue("on the straight line between phone and AP", pos.x in 1.5f..2.5f && pos.y in 1.5f..2.5f)
            }
            assertTrue("delivered within 20 s", ++guard < 60 * 20)
        }
        assertTrue(sawRadioPacket)
        assertEquals(0, w.ports(phone))
    }

    @Test
    fun radioLinksShareTheAccessPointCapacity() {
        val w = world()
        val ap = w.addRadio(RadioType.WLAN, 5, 5)
        val tablets = listOf(4 to 5, 6 to 5).map { (x, y) -> w.addClient(Device.TABLET, x, y) }
        val cdn = w.addServer(Service.STREAMING, 5, 1)
        assertTrue(w.connect(ap, cdn, CableType.FIBER))
        cdn.level = 3
        tablets.forEach { t -> repeat(2) { t.pending.addLast(Service.STREAMING) } }
        val link = w.linkBetween(ap, tablets[0])!!
        var guard = 0
        while (w.delivered < 4) {
            w.update(step)
            assertTrue("one 3-unit stream at a time on a 4-unit AP", w.linkLoad(link) <= RadioType.WLAN.capacity)
            assertTrue(++guard < 60 * 60)
        }
    }

    // ---------------------------------------------------------------- interference

    @Test
    fun overlappingAccessPointsOnTheSameChannelLoseCapacityAndSlots() {
        val w = world()
        val a = w.addRadio(RadioType.WLAN, 3, 3)
        val b = w.addRadio(RadioType.WLAN, 5, 3)
        assertEquals(listOf(b), w.interferers(a))
        assertEquals(3, w.radioCapacity(a))
        assertEquals(3, w.radioSlots(a))
        assertTrue(w.cycleChannel(b))
        assertEquals(6, b.channel)
        assertTrue(w.interferers(a).isEmpty())
        assertEquals(4, w.radioCapacity(a))
        assertEquals(4, w.radioSlots(b))
        assertTrue(w.cycleChannel(b))
        assertEquals(11, b.channel)
        assertTrue(w.cycleChannel(b))
        assertEquals(1, b.channel)
    }

    @Test
    fun accessPointsThreeCellsApartDoNotOverlap() {
        val w = world()
        val a = w.addRadio(RadioType.WLAN, 3, 3)
        w.addRadio(RadioType.WLAN, 6, 3)
        assertTrue(w.interferers(a).isEmpty())
        assertEquals(4, w.radioCapacity(a))
    }

    @Test
    fun crowdedChannelBlocksStreaming() {
        val w = world()
        val tablet = w.addClient(Device.TABLET, 4, 4)
        val aps = listOf(3 to 3, 5 to 3, 4 to 5).map { (x, y) -> w.addRadio(RadioType.WLAN, x, y) }
        val cdn = w.addServer(Service.STREAMING, 12, 3)
        aps.forEach { assertTrue(w.connect(it, cdn, CableType.FIBER)) }
        aps.forEach { assertEquals(2, w.interferers(it).size); assertEquals(2, w.radioCapacity(it)) }
        assertNull("a 3-unit stream does not fit a 2-unit AP", w.routeFor(tablet, Service.STREAMING))
        w.cycleChannel(aps[1])
        w.cycleChannel(aps[2])
        w.cycleChannel(aps[2])
        assertTrue(aps.all { w.interferers(it).isEmpty() })
        assertNotNull(w.routeFor(tablet, Service.STREAMING))
    }

    @Test
    fun lostSlotSendsPacketsBackToTheQueue() {
        val w = world()
        val ap = w.addRadio(RadioType.WLAN, 5, 5)
        listOf(4 to 5, 6 to 5, 5 to 4).forEach { (x, y) -> w.addClient(Device.PC, x, y) }
        val phone = w.addClient(Device.SMARTPHONE, 5, 6).apply { requestTimer = 1000f }
        val server = w.addServer(Service.CALL, 12, 5)
        assertTrue(w.connect(ap, server, CableType.DSL))
        phone.pending.addLast(Service.CALL)
        var guard = 0
        while (w.packets.none { it.origin === phone && it.progress >= 0f }) {
            w.update(step)
            assertTrue(++guard < 600)
        }
        assertTrue(phone.pending.isEmpty())
        // A neighbour on the same channel: 3 slots left, and the phone (same distance, highest id) loses its link.
        w.addRadio(RadioType.WLAN, 7, 7)
        assertEquals(3, w.radioSlots(ap))
        assertNull(w.linkBetween(ap, phone))
        assertTrue(w.packets.none { it.origin === phone })
        assertEquals(listOf(Service.CALL), phone.pending.toList())
    }

    @Test
    fun fiveGhzHasItsOwnChannelsAndASmallerRadius() {
        val w = world()
        val ap = w.addRadio(RadioType.WLAN, 5, 5)
        val diagonal = w.addClient(Device.LAPTOP, 6, 6)
        val side = w.addClient(Device.PC, 4, 5)
        val neighbour = w.addRadio(RadioType.WLAN, 7, 5)
        assertEquals(1, w.interferers(ap).size)
        val budget = w.budget
        assertNull(w.wifiUpgradeError(ap))
        assertTrue(w.upgradeTo5Ghz(ap))
        assertEquals(budget - Wifi.UPGRADE_5_GHZ_COST, w.budget)
        assertEquals(Wifi.CHANNELS_5_GHZ.first(), ap.channel)
        assertEquals(Wifi.RADIUS_5_GHZ, ap.radius)
        assertEquals(setOf(side), linked(w, ap))
        assertTrue(w.interferers(ap).isEmpty())
        assertTrue(w.interferers(neighbour).isEmpty())
        assertTrue(w.linkBetween(neighbour, diagonal) != null)
        assertTrue(w.cycleChannel(ap))
        assertEquals(40, ap.channel)
        assertEquals(WifiUpgradeError.ALREADY_5_GHZ, w.wifiUpgradeError(ap))
        assertFalse(w.upgradeTo5Ghz(ap))
        assertEquals(WifiUpgradeError.NOT_AN_ACCESS_POINT, w.wifiUpgradeError(side))
        w.grant(-w.budget)
        assertEquals(WifiUpgradeError.NO_BUDGET, w.wifiUpgradeError(neighbour))
    }

    @Test
    fun onlyAccessPointsHaveChannels() {
        val w = world()
        val tower = w.addRadio(RadioType.CELL, 5, 5)
        val router = w.addRouter(1, 1)
        assertFalse(w.cycleChannel(tower))
        assertFalse(w.cycleChannel(router))
        assertEquals(0, tower.channel)
        w.addRadio(RadioType.CELL, 6, 5)
        assertEquals("towers do not interfere", 8, w.radioCapacity(tower))
    }

    // ---------------------------------------------------------------- items and rewards

    @Test
    fun radioRewardsJoinThePoolWhenInvented() {
        val w = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false)
        w.jumpToWeek(5)
        assertFalse(Reward.ACCESS_POINT in w.eligibleRewards())
        w.jumpToWeek(6)
        assertTrue(Reward.ACCESS_POINT in w.eligibleRewards())
        assertFalse(Reward.CELL_TOWER in w.eligibleRewards())
        w.jumpToWeek(7)
        assertTrue(Reward.CELL_TOWER in w.eligibleRewards())
    }

    @Test
    fun weekSixAnnouncesWlanAndWeekSevenTheCellTower() {
        val w = World(cols = 16, rows = 10, seed = 1L, spawnInitialNodes = false)
        w.jumpToWeek(5)
        w.advanceToNextWeek()
        assertEquals(listOf(RadioType.WLAN), w.lastNews!!.radios)
        w.chooseReward(0)
        w.advanceToNextWeek()
        assertEquals(listOf(RadioType.CELL), w.lastNews!!.radios)
    }

    @Test
    fun wonRadiosArePlacedLikeRouters() {
        val seed = (0L until 200L).first { s ->
            World(cols = 16, rows = 10, seed = s, spawnInitialNodes = false).run {
                jumpToWeek(6)
                advanceToNextWeek()
                Reward.CELL_TOWER in rewardOffer!!.choices && Reward.ACCESS_POINT in rewardOffer!!.choices
            }
        }
        val w = World(cols = 16, rows = 10, seed = seed, spawnInitialNodes = false)
        for (row in w.water) row.fill(false)
        w.jumpToWeek(6)
        w.advanceToNextWeek()
        val offer = w.rewardOffer!!
        assertNull("nothing in stock yet", w.placeRadio(RadioType.CELL, 5, 5))
        assertTrue(w.chooseReward(offer.choices.indexOf(Reward.CELL_TOWER)))
        assertEquals(Rewards.CELL_TOWERS, w.cellTowersAvailable)
        assertEquals(0, w.accessPointsAvailable)
        val occupied = w.addRouter(4, 4)
        assertNull(w.placeRadio(RadioType.CELL, occupied.cellX, occupied.cellY))
        val tower = w.placeRadio(RadioType.CELL, 5, 5)
        assertNotNull(tower)
        assertEquals(NodeKind.CELL_TOWER, tower!!.kind)
        assertEquals(0, w.cellTowersAvailable)
        assertNull(w.placeRadio(RadioType.CELL, 6, 6))

        w.grant(0, extraAccessPoints = 1)
        val ap = w.placeRadio(RadioType.WLAN, 8, 5)!!
        assertEquals(Wifi.CHANNELS_2_4_GHZ.first(), ap.channel)
        assertEquals(0, w.radiosAvailable(RadioType.WLAN))
    }

    // ---------------------------------------------------------------- save

    @Test
    fun saveKeepsRadiosChannelsAndStockAndContinuesExactly() {
        val w = world(seed = 9L)
        val ap = w.addRadio(RadioType.WLAN, 5, 5)
        val other = w.addRadio(RadioType.WLAN, 7, 5)
        val tower = w.addRadio(RadioType.CELL, 10, 5)
        val server = w.addServer(Service.CALL, 8, 1)
        listOf(ap, other, tower).forEach { assertTrue(w.connect(it, server, CableType.FIBER)) }
        listOf(4 to 5, 5 to 6, 8 to 6, 11 to 7, 12 to 4).forEach { (x, y) ->
            w.addClient(Device.SMARTPHONE, x, y).pending.addLast(Service.CALL)
        }
        w.cycleChannel(other)
        w.upgradeTo5Ghz(ap)
        w.grant(0, extraAccessPoints = 2, extraCellTowers = 1)
        repeat(30) { w.update(step) }
        assertTrue("packets on the air", w.packets.any { w.linkBetween(it.from, it.to) is RadioLink })

        val loaded = Save.decode(Save.encode(w))!!
        assertEquals(w.snapshot(), loaded.snapshot())
        assertEquals(w.radioLinks.map { it.radio.id to it.device.id }, loaded.radioLinks.map { it.radio.id to it.device.id })
        assertEquals(2, loaded.accessPointsAvailable)
        assertEquals(1, loaded.cellTowersAvailable)
        repeat(60 * 30) {
            w.update(step)
            loaded.update(step)
        }
        assertEquals(w.snapshot(), loaded.snapshot())
    }

    @Test
    fun oldSavesWithoutRadioFieldsStillLoad() {
        val w = world()
        w.addClient(Device.PC, 3, 3)
        val json = Save.encode(w)
            .replace(Regex(""","(accessPointsAvailable|cellTowersAvailable|channel|fiveGhz)":[^,}]+"""), "")
        val loaded = Save.decode(json)
        assertNotNull(loaded)
        assertEquals(0, loaded!!.accessPointsAvailable)
        assertEquals(0, loaded.nodes.single().channel)
    }
}
