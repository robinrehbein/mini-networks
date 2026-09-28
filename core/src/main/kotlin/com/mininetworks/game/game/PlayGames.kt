package com.mininetworks.game.game

/**
 * A score for one of the Play Games leaderboards (docs/TOP100.md C3). [board] is a logical key from [Leaderboards];
 * the app maps it to the console id in games-ids.xml. [tag] travels with the score (Play shows it to nobody, it tells
 * scores of different days apart).
 */
data class LeaderboardScore(val board: String, val score: Long, val tag: String? = null)

/** The leaderboards: one per scenery (normal mode) and one for the daily challenge. */
object Leaderboards {
    const val DAILY = "daily"

    /** Board key of scenery [id]. */
    fun scenery(id: String) = "scenery_$id"

    /** Every board key; games-ids.xml needs an id for each (checked by a test). */
    val all: List<String> = Scenarios.all.map { scenery(it.id) } + DAILY

    /** Score tag of the daily challenge of UTC [day]. */
    fun dayTag(day: Long) = "day$day"

    /**
     * What the game over of [world] submits at [nowMillis]: a normal game to its scenery's board, a daily challenge to
     * the daily board as long as its UTC day lasts (the same rule as its local best, C1). Endless games have no game
     * over, creative games build for free, the tutorial and a game without a single delivery submit nothing. Neither
     * does an [assisted][World.assisted] run (continued after a game over or with a bonus router): the boards compare
     * only games played by the same rules, whatever a player watched or bought.
     */
    fun forGameOver(world: World, nowMillis: Long): LeaderboardScore? {
        if (world.guided || !world.gameOver || world.delivered <= 0 || world.assisted) return null
        val daily = world.daily
        return when {
            daily != null -> if (daily.isToday(nowMillis)) LeaderboardScore(DAILY, world.delivered.toLong(), dayTag(daily.day)) else null
            world.mode == GameMode.NORMAL -> LeaderboardScore(scenery(world.scenario.id), world.delivered.toLong())
            else -> null
        }
    }
}

/**
 * Which achievements Play Games still has to hear of (docs/TOP100.md C2). The local [PlayerStats] are the truth: every
 * achievement they reach is unlocked on Play Games, also those reached offline or before the player signed in. The app
 * starts each sign-in with an empty synced set (so a reinstall or another account gets everything once; Play ignores
 * repeats) and adds the ids of each unlock call (the Play Games SDK queues calls made offline).
 */
object AchievementSync {
    /** Ids reached in [stats] that are not in [synced], in list order. */
    fun pending(stats: PlayerStats, synced: Set<String>): List<String> =
        Achievements.all.filter { it.reached(stats) && it.id !in synced }.map { it.id }
}
