package com.mininetworks.game.game

/**
 * Records how the player's network grows during a game, for the time-lapse on the game-over card
 * (docs/TOP100.md B3). Pure Kotlin.
 *
 * [sample] is called every frame; it keeps a [Frame] whenever the network changed (a node or cable came or went, a
 * cable or server was upgraded, the map grew) and at least [MIN_GAP] seconds of game time passed since the last one.
 * A change less than [MIN_GAP] after the last frame replaces that frame (never the first one), so bursts of building
 * do not flood the recording and the last frame always shows the network as it ended. At most [MAX_FRAMES] frames are kept: when full, every second frame of the older half is dropped, so a long
 * game still plays from its start to its end.
 */
class GrowthRecorder {
    /** One node as the time-lapse draws it. */
    data class NodeMark(val x: Int, val y: Int, val kind: NodeKind, val service: Service?, val level: Int, val size: Int)

    /** One cable: its technology and the cell centres it runs through. */
    data class CableMark(val type: CableType, val points: List<Vec2>)

    /** The network at game time [time] in [week] of [year], with [delivered] packets so far. */
    data class Frame(
        val time: Float,
        val year: Int,
        val week: Int,
        val delivered: Int,
        val unlocked: CellRect,
        val nodes: List<NodeMark>,
        val cables: List<CableMark>,
    )

    private val list = ArrayList<Frame>()
    private var world: World? = null
    private var signature = 0L

    /** The recorded frames, oldest first. */
    val frames: List<Frame> get() = list

    /** Forgets everything; the next [sample] starts a new recording (also done when the world changes). */
    fun clear() {
        list.clear()
        world = null
        signature = 0L
    }

    /** Looks at [w] and keeps a frame if its network changed; cheap when nothing changed. */
    fun sample(w: World) {
        if (w !== world) {
            clear()
            world = w
        }
        val sig = signatureOf(w)
        if (list.isNotEmpty() && sig == signature) return
        signature = sig
        val frame = snapshot(w)
        if (list.size >= 2 && w.time - list.last().time < MIN_GAP) list[list.size - 1] = frame
        else list += frame
        if (list.size > MAX_FRAMES) thin()
    }

    private fun thin() {
        val half = list.size / 2
        val keep = ArrayList<Frame>(MAX_FRAMES)
        for (i in list.indices) if (i >= half || i % 2 == 0) keep += list[i]
        list.clear()
        list.addAll(keep)
    }

    private fun snapshot(w: World) = Frame(
        time = w.time,
        year = w.year,
        week = w.week,
        delivered = w.delivered,
        unlocked = w.unlocked,
        nodes = w.nodes.map { NodeMark(it.footprint[0].x, it.footprint[0].y, it.kind, it.service, it.level, if (it.isDataCenter) 2 else 1) },
        cables = w.cables.map { CableMark(it.type, it.layout.waypoints) },
    )

    private fun signatureOf(w: World): Long {
        var h = 1469598103934665603L
        fun mix(v: Int) { h = (h xor v.toLong()) * 1099511628211L }
        mix(w.nodes.size)
        for (n in w.nodes) { mix(n.id); mix(n.level) }
        mix(w.cables.size)
        for (c in w.cables) { mix(c.a.id); mix(c.b.id); mix(c.type.ordinal) }
        mix(w.unlocked.left); mix(w.unlocked.top); mix(w.unlocked.right); mix(w.unlocked.bottom)
        return h
    }

    companion object {
        /** Frames closer together than this (game seconds) merge into the newer one. */
        const val MIN_GAP = 2f
        const val MAX_FRAMES = 90
    }
}
