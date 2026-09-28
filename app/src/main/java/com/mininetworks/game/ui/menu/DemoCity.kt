package com.mininetworks.game.ui.menu

import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.NodeKind
import com.mininetworks.game.game.World
import kotlin.math.abs

/**
 * The wired town behind the main menu while no game is running, a few years in, so it shows every cable colour and
 * traffic on them instead of a handful of grey ISDN lines. Always the same (fixed seed).
 */
object DemoCity {
    @OptIn(DebugApi::class)
    fun build(): World {
        val w = World(seed = 4L)
        repeat(60 * 30) {
            w.nodes.forEach { it.pending.clear() }
            w.update(1f / 60f)
        }
        w.jumpToWeek(6)
        repeat(60 * 40) {
            w.nodes.forEach { it.pending.clear() }
            w.update(1f / 60f)
        }
        w.grant(600)
        val cables = w.unlockedCables
        for (client in w.nodes.filter { it.kind == NodeKind.CLIENT }) {
            val server = w.nodes
                .filter { it.kind == NodeKind.SERVER && it.service in client.device!!.services && w.ports(it) < it.maxPorts }
                .minByOrNull { abs(it.cellX - client.cellX) + abs(it.cellY - client.cellY) } ?: continue
            // Longer runs get the faster technologies, as a player would lay them.
            val d = abs(server.cellX - client.cellX) + abs(server.cellY - client.cellY)
            val type = when {
                d <= 3 -> CableType.DSL
                d <= 6 -> CableType.COAX
                else -> CableType.FIBER
            }.takeIf { it in cables } ?: cables.last()
            w.connect(client, server, type)
        }
        repeat(60 * 6) { w.update(1f / 60f) }
        return w
    }
}
