package com.mininetworks.game.game

import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * What a grid cell is made of. Only [LAND] takes nodes. Cables cross everything, paying [cableExtra] per cell on top
 * of the technology's price. [blocksRadio] terrain lies between a radio and a client, that client gets no radio link.
 */
enum class Terrain(val cableExtra: Int, val blocksRadio: Boolean) {
    LAND(0, false),
    /** Rivers, lakes and sea: sea cable. */
    WATER(World.Tuning.WATER_EXTRA_PER_CELL, false),
    /** Rock: cables over a pass cost more, radio waves do not get through. */
    MOUNTAIN(World.Tuning.MOUNTAIN_EXTRA_PER_CELL, true),
    /** Downtown towers: digging up the inner city costs a little, the towers shadow radio. */
    HIGH_RISE(World.Tuning.HIGH_RISE_EXTRA_PER_CELL, true),
}

/**
 * One step of building a scenario's terrain, applied in order. Positions and radii are fractions of the grid, so a
 * feature scales with the map. Randomness comes from the world's generator: the same seed gives the same map.
 */
sealed interface TerrainFeature {
    /**
     * A river one cell wide (plus [extraWidth]) that meanders across the whole grid: top to bottom if [vertical],
     * otherwise left to right, around the line at fraction [at], swinging [amplitude] cells with [frequency] per cell.
     */
    data class River(val vertical: Boolean, val at: Float, val amplitude: Float, val frequency: Float, val extraWidth: Int = 0) : TerrainFeature

    /** Everything becomes water; [Island]s and later features put land back. */
    data object Sea : TerrainFeature

    /** Land inside the ellipse around ([cx], [cy]) with radii [rx], [ry]; its coast is ragged by [ragged] of the radius. */
    data class Island(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val ragged: Float = 0.12f) : TerrainFeature

    /** Water inside the ellipse: a lake or a harbour basin. */
    data class Lake(val cx: Float, val cy: Float, val rx: Float, val ry: Float) : TerrainFeature

    /** Mountains on dry cells inside the ellipse, dense in the middle ([density] at the center, thinning outward). */
    data class Mountains(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val density: Float) : TerrainFeature

    /** High-rise blocks on dry cells inside the ellipse, like [Mountains]. */
    data class HighRises(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val density: Float) : TerrainFeature
}

/** Rules that change the game in some scenarios on top of their terrain. */
enum class ScenarioRule {
    /** "6G": cell towers link every device, not only mobile ones. */
    SIX_G,

    /** "Rechenzentren im Orbit": every server starts one hardware tier higher. */
    ORBITAL_SERVERS,
}

/** How a scenario becomes playable (docs/PLAN.md 5.2). Every scenario that is not [Free] can also be bought. */
sealed interface Unlock {
    data object Free : Unlock

    /** Reaching [packets] delivered packets in one game of scenario [after]. */
    data class Score(val after: String, val packets: Int) : Unlock

    /** Only by buying it, alone or in the scenery pack. */
    data object Purchase : Unlock
}

/**
 * A playable map ("Szenerie", docs/PLAN.md 5.2): grid size, terrain, where the eras start and special rules.
 * The game starts in [startWeek] (with everything earlier invented and its servers built) and the calendar shows
 * [startYear] then; pacing, map growth and incidents count the weeks from the start, so a later era does not make
 * the first minutes harder. Names and descriptions come from the UI's string resources, keyed by [id].
 */
data class Scenario(
    val id: String,
    val cols: Int,
    val rows: Int,
    /** Size of the playable block at the start; it grows by a ring every [World.Tuning.GROWTH_WEEKS] weeks. */
    val startCols: Int,
    val startRows: Int,
    val startWeek: Int,
    val startYear: Int,
    val terrain: List<TerrainFeature>,
    val unlock: Unlock,
    val startBudget: Int = World.Tuning.START_BUDGET,
    val startRouters: Int = World.Tuning.START_ROUTERS,
    val startAccessPoints: Int = 0,
    val startCellTowers: Int = 0,
    val rules: Set<ScenarioRule> = emptySet(),
) {
    init {
        require(startCols in 3..cols && startRows in 3..rows) { "start block must fit the grid" }
        require(startWeek >= 1) { "start week must be at least 1" }
    }

    val purchasable get() = unlock != Unlock.Free

    /** Services whose servers stand on the map from the start: every one due up to [startWeek]. */
    val startServices get() = Service.entries.filter { it.serverWeek <= startWeek }
}

/** The five scenarios of docs/PLAN.md 5.2, in menu order. */
object Scenarios {
    /**
     * Delivered packets in [RIVER_TOWN] that unlock [METROPOLIS], and in [METROPOLIS] that unlock [ISLAND]: about
     * 1.2–1.5 times what the balancing bot delivers there in a median game (docs/BALANCING.md), so a good run gets there.
     */
    const val METROPOLIS_TARGET = 900
    const val ISLAND_TARGET = 650

    /** "Kleinstadt am Fluss": the tutorial map with one river, as in the prototype. */
    val RIVER_TOWN = Scenario(
        id = "river_town",
        cols = 32, rows = 20,
        startCols = World.Tuning.START_COLS, startRows = World.Tuning.START_ROWS,
        startWeek = 1, startYear = World.Tuning.FIRST_YEAR,
        terrain = listOf(TerrainFeature.River(vertical = true, at = 0.5f, amplitude = 1.3f, frequency = 0.6f)),
        unlock = Unlock.Free,
    )

    /** "Großstadt": two rivers and a dense downtown whose towers shadow radio. */
    val METROPOLIS = Scenario(
        id = "metropolis",
        cols = 36, rows = 22,
        startCols = 18, startRows = 11,
        startWeek = 2, startYear = 1998,
        terrain = listOf(
            TerrainFeature.River(vertical = true, at = 0.36f, amplitude = 1.4f, frequency = 0.45f),
            TerrainFeature.River(vertical = false, at = 0.68f, amplitude = 1.1f, frequency = 0.35f),
            TerrainFeature.HighRises(cx = 0.56f, cy = 0.42f, rx = 0.13f, ry = 0.2f, density = 0.55f),
        ),
        unlock = Unlock.Score(RIVER_TOWN.id, METROPOLIS_TARGET),
        startBudget = 56,
        startRouters = 4,
    )

    /** "Insel & Hafen": an island in the sea with a harbour basin and islets; sea cable and radio matter. */
    val ISLAND = Scenario(
        id = "island_harbor",
        cols = 32, rows = 20,
        startCols = 16, startRows = 10,
        startWeek = 4, startYear = 2004,
        terrain = listOf(
            TerrainFeature.Sea,
            TerrainFeature.Island(cx = 0.5f, cy = 0.48f, rx = 0.3f, ry = 0.32f),
            TerrainFeature.Island(cx = 0.1f, cy = 0.18f, rx = 0.07f, ry = 0.1f),
            TerrainFeature.Island(cx = 0.9f, cy = 0.8f, rx = 0.07f, ry = 0.1f),
            TerrainFeature.Island(cx = 0.84f, cy = 0.22f, rx = 0.05f, ry = 0.08f),
            TerrainFeature.Lake(cx = 0.56f, cy = 0.74f, rx = 0.06f, ry = 0.14f),
            TerrainFeature.Lake(cx = 0.24f, cy = 0.56f, rx = 0.05f, ry = 0.08f),
        ),
        unlock = Unlock.Score(METROPOLIS.id, ISLAND_TARGET),
        startBudget = 62,
        startRouters = 2,
        startCellTowers = 1,
    )

    /** "Bergdorf": a valley between mountains that block radio; cables over the passes are expensive. */
    val MOUNTAIN_VILLAGE = Scenario(
        id = "mountain_village",
        cols = 32, rows = 20,
        startCols = 16, startRows = 10,
        startWeek = 3, startYear = 2001,
        terrain = listOf(
            TerrainFeature.River(vertical = true, at = 0.3f, amplitude = 1f, frequency = 0.5f),
            TerrainFeature.Mountains(cx = 0.6f, cy = 0.3f, rx = 0.1f, ry = 0.2f, density = 0.9f),
            TerrainFeature.Mountains(cx = 0.46f, cy = 0.76f, rx = 0.08f, ry = 0.14f, density = 0.8f),
            TerrainFeature.Mountains(cx = 0.86f, cy = 0.55f, rx = 0.12f, ry = 0.3f, density = 0.9f),
            TerrainFeature.Mountains(cx = 0.1f, cy = 0.62f, rx = 0.1f, ry = 0.3f, density = 0.9f),
        ),
        unlock = Unlock.Purchase,
        startBudget = 54,
        startRouters = 2,
    )

    /** "Zukunft 2030": everything is invented, cell towers speak 6G to every device and servers start faster. */
    val FUTURE = Scenario(
        id = "future_2030",
        cols = 36, rows = 22,
        startCols = 20, startRows = 12,
        startWeek = 8, startYear = 2030,
        terrain = listOf(
            TerrainFeature.River(vertical = true, at = 0.64f, amplitude = 1.6f, frequency = 0.4f),
            TerrainFeature.Lake(cx = 0.24f, cy = 0.3f, rx = 0.07f, ry = 0.12f),
            TerrainFeature.HighRises(cx = 0.44f, cy = 0.52f, rx = 0.1f, ry = 0.16f, density = 0.5f),
        ),
        unlock = Unlock.Purchase,
        startBudget = 74,
        startRouters = 4,
        startAccessPoints = 1,
        startCellTowers = 1,
        rules = setOf(ScenarioRule.SIX_G, ScenarioRule.ORBITAL_SERVERS),
    )

    val all = listOf(RIVER_TOWN, METROPOLIS, ISLAND, MOUNTAIN_VILLAGE, FUTURE)

    fun byId(id: String): Scenario? = all.firstOrNull { it.id == id }

    /**
     * True if [s] can be played: it is free, owned ([owns], a purchase entitlement for its id), or its packet target
     * was reached in the scenario before it ([best]: best delivered packets per scenario id).
     */
    fun isUnlocked(s: Scenario, best: (String) -> Int, owns: (String) -> Boolean): Boolean = when (val u = s.unlock) {
        Unlock.Free -> true
        is Unlock.Score -> owns(s.id) || best(u.after) >= u.packets
        Unlock.Purchase -> owns(s.id)
    }

    /** Builds the terrain of [s] into [world]'s grids, drawing from [rng] feature by feature. */
    internal fun carve(s: Scenario, world: World, rng: Random) {
        for (f in s.terrain) when (f) {
            is TerrainFeature.River -> river(f, world, rng)
            TerrainFeature.Sea -> world.water.forEach { it.fill(true) }
            is TerrainFeature.Island -> ellipse(world, f.cx, f.cy, f.rx, f.ry) { x, y, d ->
                if (d <= 1f + (rng.nextFloat() - 0.5f) * 2f * f.ragged) world.setTerrain(x, y, Terrain.LAND)
            }
            is TerrainFeature.Lake -> ellipse(world, f.cx, f.cy, f.rx, f.ry) { x, y, d ->
                if (d <= 1f) world.setTerrain(x, y, Terrain.WATER)
            }
            is TerrainFeature.Mountains -> scatter(world, f.cx, f.cy, f.rx, f.ry, f.density, Terrain.MOUNTAIN, rng)
            is TerrainFeature.HighRises -> scatter(world, f.cx, f.cy, f.rx, f.ry, f.density, Terrain.HIGH_RISE, rng)
        }
    }

    private fun river(f: TerrainFeature.River, world: World, rng: Random) {
        val across = if (f.vertical) world.cols else world.rows
        val along = if (f.vertical) world.rows else world.cols
        val base = (across * f.at).toInt()
        val phase = rng.nextFloat() * 6f
        for (i in 0 until along) {
            val c = (base + sin(i * f.frequency + phase) * f.amplitude).roundToInt().coerceIn(1, across - 2)
            for (w in 0..f.extraWidth) {
                val k = (c + w).coerceAtMost(across - 1)
                if (f.vertical) world.setTerrain(k, i, Terrain.WATER) else world.setTerrain(i, k, Terrain.WATER)
            }
        }
    }

    /** Calls [cell] for every cell whose center lies inside the ellipse, with its normalized distance (1 = rim). */
    private inline fun ellipse(world: World, cx: Float, cy: Float, rx: Float, ry: Float, cell: (Int, Int, Float) -> Unit) {
        val ex = cx * world.cols; val ey = cy * world.rows
        val ax = rx * world.cols; val ay = ry * world.rows
        for (y in 0 until world.rows) for (x in 0 until world.cols) {
            val d = hypot((x + 0.5f - ex) / ax, (y + 0.5f - ey) / ay)
            if (d <= 1.5f) cell(x, y, d)
        }
    }

    private fun scatter(world: World, cx: Float, cy: Float, rx: Float, ry: Float, density: Float, t: Terrain, rng: Random) =
        ellipse(world, cx, cy, rx, ry) { x, y, d ->
            if (d <= 1f && world.terrainAt(x, y) == Terrain.LAND && rng.nextFloat() < density * (1f - 0.6f * d * d)) world.setTerrain(x, y, t)
        }
}
