package com.mininetworks.game.game

import kotlin.math.min
import kotlin.random.Random

/** Kinds of disturbance (docs/PLAN.md 3.5, item 4). */
enum class IncidentKind {
    /** An excavator digs next to a cable and cuts it: the cable is unusable until repaired. */
    EXCAVATOR,

    /** A power outage switches a router or access point off for a while. */
    POWER_OUTAGE,
}

/** Why a cable cannot be repaired right now. */
enum class RepairError { NOT_CUT, NO_BUDGET }

/**
 * A disturbance, announced [Incidents.WARNING_SECONDS] before it strikes. An [IncidentKind.EXCAVATOR] targets [cable]
 * and digs at [cutAt] (fraction of the cable's length); a [IncidentKind.POWER_OUTAGE] targets [node].
 * Once struck it lasts [remaining] seconds; a cut cable also ends when the player repairs it ([World.repair]).
 */
class Incident internal constructor(
    val kind: IncidentKind,
    val cable: Cable?,
    val node: Node?,
    val cutAt: Float,
    warning: Float = Incidents.WARNING_SECONDS,
    remaining: Float = Incidents.durationOf(kind),
) {
    init {
        require(if (kind == IncidentKind.EXCAVATOR) cable != null && node == null else node != null && cable == null) { "target does not match $kind" }
    }

    /** Seconds until the incident strikes; the announcement runs while this is above 0. */
    var warning = warning
        internal set

    /** Seconds the effect still lasts once struck. */
    var remaining = remaining
        internal set

    val struck get() = warning <= 0f

    /** Where it happens in world space: the cut on the cable, or the node's center. */
    val spot: Vec2 get() = cable?.pointAt(cutAt) ?: node!!.center

    /** 0..1: how far the effect has run, for a countdown ring. */
    val effectProgress get() = if (!struck) 0f else 1f - remaining / Incidents.durationOf(kind)
}

/** One incident in the fixed plan of a week: when it is announced and which kind it prefers. */
data class PlannedIncident(
    /** Seconds after the start of the week. */
    val at: Float,
    val kind: IncidentKind,
    /** Seed for picking the target when the incident starts. */
    val pick: Long,
)

/**
 * Timing and rules of incidents. The plan of every week is derived from seed and week only ([plan]), so it does not
 * depend on how the game was played; the target is picked from the network when the announcement starts.
 * None in the first [FIRST_WEEK] - 1 weeks, then more week by week, up to [MAX_PER_WEEK].
 */
object Incidents {
    const val FIRST_WEEK = 4
    const val MAX_PER_WEEK = 2
    /** One more incident per week every this many weeks. */
    const val WEEKS_PER_STEP = 6
    const val WARNING_SECONDS = 8f
    /** A cut cable repairs itself after this long. */
    const val CUT_SECONDS = 20f
    const val OUTAGE_SECONDS = 10f
    /** Budget a tap on a cut cable costs to repair it at once. */
    const val REPAIR_COST = 3
    /** No announcement in the first and the last seconds of a week. */
    const val START_MARGIN = 6f
    const val END_MARGIN = 8f

    fun durationOf(kind: IncidentKind) = when (kind) {
        IncidentKind.EXCAVATOR -> CUT_SECONDS
        IncidentKind.POWER_OUTAGE -> OUTAGE_SECONDS
    }

    /** How many incidents [week] brings: 0 before [FIRST_WEEK], then one more every [WEEKS_PER_STEP] weeks. */
    fun countIn(week: Int) = if (week < FIRST_WEEK) 0 else min(MAX_PER_WEEK, 1 + (week - FIRST_WEEK) / WEEKS_PER_STEP)

    /**
     * The incidents of [week] in a game with [seed], in time order: the usable part of the week is cut into one slot per
     * incident, and each is announced early enough in its slot to strike before the next one is announced.
     */
    fun plan(seed: Long, week: Int, weekSeconds: Float = World.Tuning.WEEK_SECONDS): List<PlannedIncident> {
        val n = countIn(week)
        if (n == 0) return emptyList()
        val r = Random(seed * 1_000_033L + week * 104_729L + SALT)
        val slot = (weekSeconds - START_MARGIN - END_MARGIN) / n
        return List(n) { i ->
            val at = START_MARGIN + slot * i + r.nextFloat() * (slot - WARNING_SECONDS).coerceAtLeast(0f)
            val kind = if (r.nextBoolean()) IncidentKind.EXCAVATOR else IncidentKind.POWER_OUTAGE
            PlannedIncident(at, kind, r.nextLong())
        }
    }

    private const val SALT = 0x5EED_1DL
}
