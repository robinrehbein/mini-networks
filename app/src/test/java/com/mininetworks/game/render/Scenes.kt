package com.mininetworks.game.render

import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.Device
import com.mininetworks.game.game.Service
import com.mininetworks.game.game.World

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
}
