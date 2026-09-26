package com.mininetworks.game.ui.menu

import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.World
import kotlin.math.abs

/** The small wired town behind the main menu while no game is running. Always the same (fixed seed). */
object DemoCity {
    @OptIn(DebugApi::class)
    fun build(): World {
        val w = World(seed = 4L)
        repeat(60 * 30) {
            w.nodes.forEach { it.pending.clear() }
            w.update(1f / 60f)
        }
        w.grant(200)
        for (client in w.nodes.filter { it.kind == NodeKind.CLIENT }) {
            val server = w.nodes
                .filter { it.kind == NodeKind.SERVER && it.service in client.device!!.services && w.ports(it) < it.maxPorts }
                .minByOrNull { abs(it.cellX - client.cellX) + abs(it.cellY - client.cellY) } ?: continue
            w.connect(client, server, CableType.ISDN)
        }
        repeat(60 * 6) { w.update(1f / 60f) }
        return w
    }
}
