package com.mininetworks.game.game

import java.util.Collections
import java.util.IdentityHashMap

/** Something worth a sound, derived from how a [World] changed since the last [SoundCues.poll]. Only for effects. */
sealed interface SoundCue {
    /** A response reached its client: [service] was delivered. */
    data class Delivered(val service: Service) : SoundCue

    /** A client just reached [World.Tuning.MAX_PENDING] waiting requests and its overload ring starts to fill. */
    data object OverloadStarted : SoundCue

    /** A new week began (the reward choice opens). */
    data object NewWeek : SoundCue
}

/**
 * Watches one [World] at a time and reports what changed as [SoundCue]s. Polling a different world (a new game, a loaded
 * save) only takes its state in silently. Deliveries are collapsed to one cue per service per poll, in [Service] order,
 * so a burst of packets does not stack up sounds. A client warns once per overload: it warns again only after its ring
 * has fully emptied, so a queue that hovers around the limit does not beep on every packet.
 */
class SoundCues {
    private var world: World? = null
    private var week = 0
    private val seenArrivals: MutableSet<Arrival> = Collections.newSetFromMap(IdentityHashMap())
    private val warned: MutableSet<Node> = Collections.newSetFromMap(IdentityHashMap())

    fun poll(w: World): List<SoundCue> {
        if (w !== world) {
            world = w
            week = w.week
            seenArrivals.clear()
            seenArrivals += w.arrivals
            warned.clear()
            w.nodes.filterTo(warned) { overloaded(it) || it.overload > 0f }
            return emptyList()
        }
        val cues = ArrayList<SoundCue>()
        if (w.week > week) cues += SoundCue.NewWeek
        week = w.week

        val delivered = HashSet<Service>()
        for (a in w.arrivals) if (a.isResponse && a !in seenArrivals) delivered += a.service
        seenArrivals.clear()
        seenArrivals += w.arrivals
        Service.entries.filter { it in delivered }.mapTo(cues) { SoundCue.Delivered(it) }

        warned.retainAll { it in w.nodes && (overloaded(it) || it.overload > 0f) }
        var overload = false
        for (n in w.nodes) if (overloaded(n) && warned.add(n)) overload = true
        if (overload) cues += SoundCue.OverloadStarted
        return cues
    }

    private fun overloaded(n: Node) = n.kind == NodeKind.CLIENT && n.pending.size >= World.Tuning.MAX_PENDING
}
