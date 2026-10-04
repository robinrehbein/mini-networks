package com.mininetworks.game.game

import java.util.Collections
import java.util.IdentityHashMap

/** Something worth a sound, derived from how a [World] changed since the last [SoundCues.poll]. Only for effects. */
sealed interface SoundCue {
    /** A response reached its client: [service] was delivered. */
    data class Delivered(val service: Service) : SoundCue

    /** A client just reached [World.Tuning.MAX_PENDING] waiting requests and its overload ring starts to fill. */
    data object OverloadStarted : SoundCue

    /** The fullest overload ring just passed [SoundCues.HALF_RING]: half of the time to game over is gone. */
    data object OverloadHalf : SoundCue

    /** The fullest overload ring just passed [SoundCues.CRITICAL_RING]: game over is seconds away. */
    data object OverloadCritical : SoundCue

    /** The game was just lost. Nothing else sounds in the same poll. */
    data object GameOver : SoundCue

    /** A new week began (the reward choice opens). */
    data object NewWeek : SoundCue
}

/**
 * Watches one [World] at a time and reports what changed as [SoundCue]s. Polling a different world (a new game, a loaded
 * save) only takes its state in silently. Deliveries are collapsed to one cue per service per poll, in [Service] order,
 * so a burst of packets does not stack up sounds. A client warns once per overload: it warns again only after its ring
 * has fully emptied (or the game was revived), so a queue that hovers around the limit does not beep on every packet.
 * The rising alarm sounds only where a full ring ends the game ([GameMode.endsOnOverload], not guided) and follows the
 * single worst danger on the map: a ring passing [HALF_RING] or [CRITICAL_RING] sounds only when no client already holds
 * that stage or a higher one, and a client's stage is cleared only once its ring has fully emptied. At most one cue of
 * the warning family sounds per poll, the most urgent one, so warnings from several clients never clash.
 */
class SoundCues {
    private var world: World? = null
    private var week = 0
    private val seenArrivals: MutableSet<Arrival> = Collections.newSetFromMap(IdentityHashMap())
    private val warned: MutableSet<Node> = Collections.newSetFromMap(IdentityHashMap())
    /** Alarm stage (1 = [HALF_RING], 2 = [CRITICAL_RING]) each client has reached since its ring was last empty. */
    private val stages = IdentityHashMap<Node, Int>()
    private var over = false

    fun poll(w: World): List<SoundCue> {
        if (w !== world) {
            world = w
            week = w.week
            seenArrivals.clear()
            seenArrivals += w.arrivals
            warned.clear()
            w.nodes.filterTo(warned) { overloaded(it) || it.overload > 0f }
            stages.clear()
            for (n in w.nodes) stage(w, n).takeIf { it > 0 }?.let { stages[n] = it }
            over = w.gameOver
            return emptyList()
        }
        val cues = ArrayList<SoundCue>()
        val lost = w.gameOver && !over
        // A revive empties every ring: the next overload warns and rises from the start again.
        if (over && !w.gameOver) {
            warned.clear()
            stages.clear()
        }
        over = w.gameOver
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

        stages.keys.retainAll { it in w.nodes && it.overload > 0f }
        var held = 0
        for (v in stages.values) held = maxOf(held, v)
        var rise = 0
        for (n in w.nodes) {
            val s = stage(w, n)
            if (s > (stages[n] ?: 0)) {
                stages[n] = s
                rise = maxOf(rise, s)
            }
        }
        if (lost) return listOf(SoundCue.GameOver)
        val alarm = when {
            rise <= held -> null
            rise == 2 -> SoundCue.OverloadCritical
            else -> SoundCue.OverloadHalf
        }
        if (alarm != null) {
            cues.remove(SoundCue.OverloadStarted)
            cues += alarm
        }
        return cues
    }

    private fun stage(w: World, n: Node) = when {
        n.kind != NodeKind.CLIENT || !w.mode.endsOnOverload || w.guided -> 0
        n.overload >= CRITICAL_RING -> 2
        n.overload >= HALF_RING -> 1
        else -> 0
    }

    private fun overloaded(n: Node) = n.kind == NodeKind.CLIENT && n.pending.size >= World.Tuning.MAX_PENDING

    companion object {
        /** Ring fill at which the alarm first rises ([SoundCue.OverloadHalf]). */
        const val HALF_RING = 0.5f
        /** Ring fill at which the alarm turns urgent ([SoundCue.OverloadCritical]). */
        const val CRITICAL_RING = 0.8f
    }
}
