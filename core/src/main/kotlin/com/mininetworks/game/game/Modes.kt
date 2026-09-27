package com.mininetworks.game.game

/**
 * How a game is played (docs/TOP100.md C4). Names and descriptions come from the UI's string resources.
 */
enum class GameMode {
    /** The real game: a device whose overload ring closes ends it. */
    NORMAL,

    /**
     * No game over: an overloaded device stays at a full ring and slows the area around it instead ([World.isSlowed]);
     * its queue holds no more than [World.Tuning.MAX_PENDING] requests, further ones are lost.
     */
    ENDLESS,

    /**
     * Free building: unlimited budget and stock of routers and radios, every technology and device invented from the
     * start, a server of every service on the map, no incidents and no week rewards. Overload works as in [ENDLESS].
     */
    CREATIVE,
    ;

    /** True if an overloaded device ends the game. */
    val endsOnOverload get() = this == NORMAL
}

/**
 * The special rule of a daily challenge ([DailyChallenge.rule]); one per day from this rotating pool. Every rule is a
 * small twist on the normal game, applied by [World]. New entries go at the end: the rotation counts on the order.
 */
enum class DailyRule {
    /** "Sparkurs": the start budget is [World.Tuning.TIGHT_START_SHARE] of the scenery's, every week pays [World.Tuning.TIGHT_WEEK_BUDGET]. */
    TIGHT_BUDGET,

    /** "Stoßzeit": devices ask [World.Tuning.RUSH_REQUEST_FACTOR] times as long between two requests, so more often. */
    RUSH_HOUR,

    /** "Glasfaser-Tag": every cable technology is invented from the start. */
    FIBER_DAY,

    /** "Knappe Router": only [World.Tuning.FEW_ROUTERS] router in stock at the start. */
    FEW_ROUTERS,

    /** "Unwetter": incidents come [World.Tuning.STORM_WEEKS_AHEAD] weeks earlier than usual, and more of them. */
    STORM,

    /** "Weites Land": the playable block starts [World.Tuning.WIDE_RINGS] rings larger. */
    WIDE_LAND,

    /** "Andrang": new devices come more often ([World.Tuning.CROWD_SPAWN_FACTOR]), but overload rings fill slower. */
    CROWD,
}

/**
 * The challenge of one day (docs/TOP100.md C1): the same for every player, because everything follows from the day
 * number alone ([day], days since 1970-01-01 in UTC): the world [seed], the [scenario] and the [rule]. Same seed means
 * the same map and the same start (servers, first devices); after that the game follows the player's moves.
 */
data class DailyChallenge(val day: Long, val seed: Long, val scenario: Scenario, val rule: DailyRule) {
    companion object {
        /** Packets to deliver in one run of the day's challenge before the day counts for the streak ([DailyStreak]). */
        const val STREAK_PACKETS = 30

        /** Sceneries the challenge rotates through: the ones everyone can reach without buying. */
        val SCENARIOS = listOf(Scenarios.RIVER_TOWN, Scenarios.METROPOLIS, Scenarios.ISLAND)

        private const val MILLIS_PER_DAY = 86_400_000L

        /** Day number in UTC of the moment [epochMillis]: the same everywhere on Earth at the same moment. */
        fun dayOf(epochMillis: Long): Long = Math.floorDiv(epochMillis, MILLIS_PER_DAY)

        /** The challenge of the UTC day that contains [epochMillis]. */
        fun at(epochMillis: Long) = of(dayOf(epochMillis))

        /**
         * The challenge of [day]. The rule rotates through [DailyRule] in order, one per day, so a week shows seven
         * different rules; the scenery rotates through [SCENARIOS] on its own cycle, so rule and map pair up
         * differently from week to week. The seed is a hash of the day.
         */
        fun of(day: Long): DailyChallenge {
            val rules = DailyRule.entries
            val rule = rules[Math.floorMod(day, rules.size.toLong()).toInt()]
            val scenario = SCENARIOS[Math.floorMod(day, SCENARIOS.size.toLong()).toInt()]
            return DailyChallenge(day, seedOf(day), scenario, rule)
        }

        /** SplitMix64 of the day: neighbouring days get unrelated seeds. */
        fun seedOf(day: Long): Long {
            var z = day * -0x61c8864680b583ebL + 0x6D696E696E6574L
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
    }
}

/**
 * The local streak of daily challenges: [current] days in a row with a counted challenge, the [best] such run, and the
 * [lastDay] that counted (null before the first). Plain data, so the app can store it however it likes.
 */
data class DailyStreak(val lastDay: Long? = null, val current: Int = 0, val best: Int = 0) {
    /**
     * The streak after the challenge of [day] counted: the next day in a row adds one, a gap starts over at one. A day
     * that already counted (or an older one, e.g. a run of yesterday's challenge finished after midnight) changes nothing.
     */
    fun record(day: Long): DailyStreak {
        val last = lastDay
        if (last != null && day <= last) return this
        val next = if (last != null && day == last + 1) current + 1 else 1
        return DailyStreak(day, next, maxOf(best, next))
    }

    /** The streak as it stands on [today]: it is broken (0) once a whole day passed without a counted challenge. */
    fun currentOn(today: Long): Int = if (lastDay != null && today - lastDay <= 1) current else 0

    /** True if the challenge of [day] already counted. */
    fun counted(day: Long) = lastDay != null && lastDay >= day
}
