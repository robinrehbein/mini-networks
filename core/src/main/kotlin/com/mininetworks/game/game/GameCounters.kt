package com.mininetworks.game.game

/**
 * What the player did in one game, counted by [World] as it happens, for achievements and missions (docs/TOP100.md
 * C2). Only counts up; the app's [AchievementTracker] takes the differences. Not saved: a game continued from a save
 * counts from there.
 */
class GameCounters {
    var cablesLaid = 0; internal set
    /** Fiber cables laid, and cables upgraded to fiber. */
    var fiberLaid = 0; internal set
    var cableUpgrades = 0; internal set
    var routersPlaced = 0; internal set
    var accessPoints = 0; internal set
    var cellTowers = 0; internal set
    var serverUpgrades = 0; internal set
    /** Servers that reached the data center tier. */
    var dataCenters = 0; internal set
    /** Cut cables repaired by the player (not the ones that repaired themselves). */
    var repairs = 0; internal set
    /** Delivered responses per [Service.ordinal]. */
    internal val deliveredBy = IntArray(Service.entries.size)

    /** Responses of service [s] delivered in this game. */
    fun delivered(s: Service) = deliveredBy[s.ordinal]
}
