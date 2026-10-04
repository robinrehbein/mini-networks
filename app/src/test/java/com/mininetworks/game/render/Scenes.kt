package com.mininetworks.game.render

import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.RadioType
import com.mininetworks.game.game.World
import kotlin.math.abs

/** Fixed worlds shared by the screenshot and layout tests. */
@OptIn(DebugApi::class)
object Scenes {
    /** Week 6 of a 16 × 10 town: four servers (one a data center), two routers, eight devices, every cable type. */
    fun hud(): World {
        val w = World(cols = 16, rows = 10, seed = 3L, spawnInitialNodes = false)
        w.incidentsEnabled = false
        w.jumpToWeek(6)
        w.grant(200)
        val mail = w.addServer(Service.MAIL, 2, 1)
        val call = w.addServer(Service.CALL, 13, 7)
        val game = w.addServer(Service.GAMING, 13, 1)
        val cdn = w.addServer(Service.STREAMING, 2, 8)
        val r1 = w.addRouter(4, 4)
        val r2 = w.addRouter(11, 4)
        val pc = w.addClient(Device.PC, 5, 1)
        val phone = w.addClient(Device.PHONE, 1, 5)
        val laptop = w.addClient(Device.LAPTOP, 6, 7)
        val console = w.addClient(Device.CONSOLE, 10, 2)
        val smartphone = w.addClient(Device.SMARTPHONE, 14, 4)
        val tv = w.addClient(Device.TV, 4, 8)
        val tablet = w.addClient(Device.TABLET, 10, 8)
        val watch = w.addClient(Device.WATCH, 6, 3)
        w.connect(mail, pc, CableType.ISDN)
        w.connect(pc, r1, CableType.DSL)
        w.connect(phone, r1, CableType.ISDN)
        w.connect(r1, laptop, CableType.DSL)
        w.connect(r1, r2, CableType.FIBER)
        w.connect(r2, console, CableType.FIBER)
        w.connect(console, game, CableType.FIBER)
        w.connect(r2, smartphone, CableType.COAX)
        w.connect(r2, call, CableType.DSL)
        w.connect(cdn, tv, CableType.COAX)
        w.connect(tv, laptop, CableType.COAX)
        w.connect(r2, tablet, CableType.COAX)
        w.connect(watch, r1, CableType.DSL)
        w.connect(watch, pc, CableType.DSL)
        w.upgradeServer(game)
        w.upgradeServer(cdn)
        w.upgradeServer(cdn)
        repeat(3) { check(w.upgradeServer(mail)) { "mail server becomes a data center" } }
        repeat(60 * 25) { w.update(1f / 60f) }
        return w
    }

    /**
     * Wireless in week 7: two access points on channel 1 overlap (red lens, reduced slots), one moved to channel 6,
     * one on 5 GHz with its smaller circle, and a cell tower that links only the mobile devices around it.
     */
    fun wireless(): World {
        val w = World(cols = 16, rows = 10, seed = 3L, spawnInitialNodes = false)
        for (row in w.water) row.fill(false)
        w.incidentsEnabled = false
        w.jumpToWeek(7)
        w.grant(400, extraAccessPoints = 1, extraCellTowers = 1)
        val call = w.addServer(Service.CALL, 13, 1)
        val cdn = w.addServer(Service.STREAMING, 2, 1)
        val mail = w.addServer(Service.MAIL, 8, 1)
        val west = w.addRouter(5, 3)
        val east = w.addRouter(10, 3)
        check(w.connect(west, east, CableType.FIBER))
        listOf(cdn, mail).forEach { check(w.connect(west, it, CableType.FIBER)) }
        check(w.connect(east, call, CableType.FIBER))
        repeat(2) { listOf(call, cdn, mail).forEach { s -> w.upgradeServer(s) } }
        val ap1 = w.addRadio(RadioType.WLAN, 3, 6)
        val ap2 = w.addRadio(RadioType.WLAN, 5, 6)
        val ap3 = w.addRadio(RadioType.WLAN, 9, 6)
        val ap4 = w.addRadio(RadioType.WLAN, 7, 8)
        val tower = w.addRadio(RadioType.CELL, 12, 6)
        w.cycleChannel(ap3)
        w.upgradeTo5Ghz(ap4)
        listOf(ap1, ap2).forEach { check(w.connect(it, west, CableType.FIBER)) }
        listOf(ap3, ap4, tower).forEach { check(w.connect(it, east, CableType.FIBER)) }
        for ((device, x, y) in listOf(
            Triple(Device.LAPTOP, 2, 5), Triple(Device.TABLET, 2, 7), Triple(Device.SMARTPHONE, 4, 7), Triple(Device.TV, 4, 5),
            Triple(Device.PC, 6, 5), Triple(Device.TABLET, 6, 7), Triple(Device.LAPTOP, 10, 7), Triple(Device.SMARTPHONE, 9, 5),
            Triple(Device.TV, 7, 9), Triple(Device.SMARTPHONE, 14, 7), Triple(Device.WATCH, 12, 8), Triple(Device.TABLET, 13, 4),
            Triple(Device.PC, 13, 6),
        )) w.addClient(device, x, y)
        repeat(60 * 6) { w.update(1f / 60f) }
        check(w.interferers(ap1) == listOf(ap2)) { "channel 1 overlap" }
        return w
    }

    /**
     * Incidents in week 9: an excavator has cut the fiber between the routers and digs in the hole (red countdown until
     * it repairs itself), a second one is announced at the mail server's fiber (amber pulse and countdown), the east router is dark
     * from a power outage, and an outage is announced for the access point.
     */
    fun incidents(single: Boolean = false): World {
        val w = World(cols = 16, rows = 10, seed = 3L, spawnInitialNodes = false)
        for (row in w.water) row.fill(false)
        w.incidentsEnabled = false
        w.jumpToWeek(9)
        w.grant(400)
        val mail = w.addServer(Service.MAIL, 2, 1)
        val call = w.addServer(Service.CALL, 13, 1)
        val cdn = w.addServer(Service.STREAMING, 13, 8)
        val west = w.addRouter(4, 4)
        val east = w.addRouter(11, 4)
        val ap = w.addRadio(RadioType.WLAN, 4, 7)
        check(w.connect(west, east, CableType.FIBER))
        check(w.connect(west, mail, CableType.FIBER))
        check(w.connect(east, call, CableType.DSL))
        check(w.connect(east, cdn, CableType.COAX))
        check(w.connect(ap, west, CableType.DSL))
        listOf(mail, call, cdn).forEach { repeat(2) { _ -> w.upgradeServer(it) } }
        for ((device, x, y) in listOf(
            Triple(Device.PC, 1, 4), Triple(Device.LAPTOP, 7, 2), Triple(Device.PHONE, 9, 7), Triple(Device.TV, 14, 5),
            Triple(Device.SMARTPHONE, 5, 8), Triple(Device.TABLET, 3, 8), Triple(Device.CONSOLE, 12, 7),
        )) {
            val c = w.addClient(device, x, y)
            if (device != Device.SMARTPHONE && device != Device.TABLET) {
                check(w.connect(c, if (x < 8) west else east, CableType.DSL))
            }
        }
        repeat(60 * 4) { w.update(1f / 60f) }
        w.announceExcavator(w.cableBetween(west, east)!!)
        if (!single) w.announcePowerOutage(east)
        repeat(60 * 9) { w.update(1f / 60f) }
        // [single]: just the one excavator on the fiber trunk, the store's close-up of one clear threat.
        if (single) return w
        // On the vertical leg of the L to the mail server: (4,4) → (2,4) → (2,1), digging at (2,3).
        w.announceExcavator(w.cableBetween(west, mail)!!, cutAt = 0.6f)
        w.announcePowerOutage(ap)
        repeat(60 * 3) { w.update(1f / 60f) }
        check(w.incidents.count { it.struck } == 2 && w.incidents.count { !it.struck } == 2) { "two struck, two announced" }
        return w
    }

    /**
     * A 32×20 city in week 5: two rings have grown around the 16×10 start block. Every client is wired to the
     * nearest server it can use so the network carries traffic.
     */
    fun grownCity(): World {
        val w = World(seed = 4L)
        w.incidentsEnabled = false
        repeat(60 * 45 * 4 + 60 * 5) {
            if (w.rewardOffer != null) w.chooseReward(0)
            w.nodes.forEach { it.pending.clear() }
            w.update(1f / 60f)
        }
        w.grant(600)
        for (client in w.nodes.filter { it.kind == NodeKind.CLIENT }) {
            val server = w.nodes
                .filter { it.kind == NodeKind.SERVER && it.service in client.device!!.services && w.ports(it) < it.maxPorts }
                .minByOrNull { abs(it.cellX - client.cellX) + abs(it.cellY - client.cellY) } ?: continue
            w.connect(client, server, CableType.DSL)
        }
        repeat(60 * 3) { w.update(1f / 60f) }
        return w
    }
    /**
     * Week 8 of a busy town: every client wired to the nearest servers it can use in all four technologies, so many
     * cables run over the same cells and the lanes ([com.mininetworks.game.game.CableLanes]) show.
     */
    fun crowdedTown(): World {
        val w = World(seed = 7L)
        w.incidentsEnabled = false
        repeat(60 * 45 * 7) {
            if (w.rewardOffer != null) w.chooseReward(0)
            w.nodes.forEach { it.pending.clear() }
            w.update(1f / 60f)
        }
        w.grant(2000)
        val types = CableType.entries.filter { w.invented(it) }
        for ((i, client) in w.nodes.filter { it.kind == NodeKind.CLIENT }.withIndex()) {
            val servers = w.nodes
                .filter { it.kind == NodeKind.SERVER && it.service in client.device!!.services && w.ports(it) < it.maxPorts }
                .sortedBy { abs(it.cellX - client.cellX) + abs(it.cellY - client.cellY) }
            for (server in servers.take(2)) w.connect(client, server, types[(i + server.id) % types.size])
        }
        repeat(60 * 3) {
            if (w.rewardOffer != null) w.chooseReward(0)
            w.update(1f / 60f)
        }
        if (w.rewardOffer != null) w.chooseReward(0)
        return w
    }
}
