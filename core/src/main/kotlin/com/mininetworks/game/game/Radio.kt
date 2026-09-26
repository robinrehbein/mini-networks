package com.mininetworks.game.game

import kotlin.math.hypot

/**
 * Wireless technologies (docs/PLAN.md 3.3). A radio node is cabled to the network like a router and links every client
 * it [serves] within its radius automatically, so those clients need no cable. All links of one radio share its
 * capacity; [maxDevices] caps how many clients it links at once (null: no cap).
 */
enum class RadioType(
    val kind: NodeKind,
    val radius: Float,
    val capacity: Int,
    val maxDevices: Int?,
    /** Latency of one radio hop, independent of the distance. */
    val latencyMs: Float,
    /** Visual packet speed in cells per second. */
    val speed: Float,
    val unlockWeek: Int,
    private val mobileOnly: Boolean,
) {
    WLAN(NodeKind.ACCESS_POINT, 1.5f, 4, 4, 5f, 2.6f, 6, mobileOnly = false),
    CELL(NodeKind.CELL_TOWER, 3f, 8, null, 15f, 3.2f, 7, mobileOnly = true),
    ;

    fun serves(d: Device) = !mobileOnly || d.mobile

    companion object {
        fun of(kind: NodeKind): RadioType? = entries.firstOrNull { it.kind == kind }
    }
}

/**
 * WLAN channels and interference. Access points whose radio circles overlap on the same channel disturb each other:
 * each such neighbour costs [PENALTY_PERCENT] of capacity and device slots, never below 1 (see [reduced]).
 */
object Wifi {
    val CHANNELS_2_4_GHZ = listOf(1, 6, 11)
    val CHANNELS_5_GHZ = listOf(36, 40, 44, 48)
    const val RADIUS_5_GHZ = 1f
    const val UPGRADE_5_GHZ_COST = 6
    const val PENALTY_PERCENT = 30

    /** [base] minus [PENALTY_PERCENT] per interfering neighbour, rounded to the nearest whole unit, at least 1. */
    fun reduced(base: Int, neighbours: Int): Int {
        val percent = (100 - PENALTY_PERCENT * neighbours).coerceAtLeast(0)
        return ((base * percent + 50) / 100).coerceAtLeast(1)
    }

    /** True if two radio circles around [a] and [b] with radii [ra] and [rb] overlap (touching does not count). */
    fun overlaps(a: Vec2, ra: Float, b: Vec2, rb: Float) = hypot(a.x - b.x, a.y - b.y) < ra + rb - EPSILON

    internal const val EPSILON = 1e-4f
}

/** An automatic wireless link from [radio] to a client [device] in its radius. [capacity] is the radio's shared capacity. */
class RadioLink(val radio: Node, val device: Node, override val capacity: Int) : Link {
    override val a get() = radio
    override val b get() = device
    val type = requireNotNull(radio.radio) { "not a radio node" }
    override val length = hypot(device.center.x - radio.center.x, device.center.y - radio.center.y)
    override val latencyMs get() = type.latencyMs
    override val speed get() = type.speed
    override val medium get() = radio

    override fun pointFrom(from: Node, f: Float, out: FloatArray) {
        val t = (if (from === radio) f else 1f - f).coerceIn(0f, 1f)
        out[0] = radio.center.x + (device.center.x - radio.center.x) * t
        out[1] = radio.center.y + (device.center.y - radio.center.y) * t
    }
}
