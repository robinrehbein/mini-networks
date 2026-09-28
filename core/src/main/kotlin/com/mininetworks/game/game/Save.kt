package com.mininetworks.game.game

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Save games: a [World] as JSON text. [encode] writes [World.snapshot], [decode] restores it with [World.restore].
 * The file carries a [VERSION]; a save from another version or a damaged file decodes to null. Fields added later
 * have defaults, so older saves of the same version still load. Radio links are not stored; they follow from the nodes.
 */
object Save {
    const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(world: World): String = encode(world.snapshot())

    /** Encodes a [World.snapshot] taken earlier; the snapshot is plain immutable data, so any thread may do this. */
    fun encode(snapshot: WorldSnapshot): String = json.encodeToString(WorldSnapshot.serializer(), snapshot)

    fun decode(text: String): World? = try {
        json.decodeFromString(WorldSnapshot.serializer(), text).takeIf { it.version == VERSION }?.let(World::restore)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Plain-data copy of a whole [World]: everything the simulation needs to continue exactly where it was. */
@Serializable
data class WorldSnapshot(
    val version: Int = Save.VERSION,
    /** [Scenario.id]; saves from before the scenarios are all [Scenarios.RIVER_TOWN]. */
    val scenario: String = Scenarios.RIVER_TOWN.id,
    val cols: Int,
    val rows: Int,
    val seed: Long,
    /** How many values the world's random generator had produced; see [ReplayableRandom]. */
    val randomDraws: Long,
    val nextId: Int,
    /** One string per row, one [Terrain] per cell: `.` land, `~` water, `^` mountain, `#` high-rise. */
    val water: List<String>,
    val unlocked: CellRect,
    val time: Float,
    val week: Int,
    val delivered: Int,
    val budget: Int,
    val routersAvailable: Int,
    val accessPointsAvailable: Int = 0,
    val cellTowersAvailable: Int = 0,
    val gameOver: Boolean,
    val failedNodeId: Int?,
    val rewardOffer: RewardOfferSnapshot?,
    /** [World.continued]: the game already went on once after a game over. */
    val continued: Boolean = false,
    /** [World.bonusRoutersClaimed]; saves from before it count as none. */
    val bonusRoutersClaimed: Int = 0,
    val serverVouchers: Int,
    val lastNews: WeekNews?,
    val lastNewsTime: Float,
    val clientSpawnTimer: Float,
    val nodes: List<NodeSnapshot>,
    val cables: List<CableSnapshot>,
    val packets: List<PacketSnapshot>,
    val incidentsEnabled: Boolean = true,
    val incidents: List<IncidentSnapshot> = emptyList(),
    /** [World.mode]; saves from before the modes are normal games. */
    val mode: GameMode = GameMode.NORMAL,
    /** [DailyChallenge.day] of a daily challenge, null for any other game; the challenge follows from it. */
    val dailyDay: Long? = null,
)

@Serializable
data class RewardOfferSnapshot(val week: Int, val choices: List<Reward>, val bonusClaimed: Boolean = false)

@Serializable
data class NodeSnapshot(
    val id: Int,
    val kind: NodeKind,
    val device: Device?,
    val service: Service?,
    val cellX: Int,
    val cellY: Int,
    val footprint: List<Cell>,
    val pending: List<Service>,
    val overload: Float,
    val level: Int,
    val tokens: Float,
    val requestTimer: Float,
    val dispatchCooldown: Float,
    /** WLAN channel of an access point, 0 for other nodes. */
    val channel: Int = 0,
    val fiveGhz: Boolean = false,
    /** Generation of a cell tower; null for other nodes and in saves from before the generations ([CellGeneration.LEGACY]). */
    val cellGeneration: CellGeneration? = null,
)

/** A cable between the nodes with ids [a] and [b]; [waypoints] are the cells whose centers the layout runs through. */
@Serializable
data class CableSnapshot(
    val a: Int,
    val b: Int,
    val type: CableType,
    val cost: Int,
    val waypoints: List<Cell>,
    val waterCells: Int,
)

/** A packet; [origin] and [route] are node ids. */
@Serializable
data class PacketSnapshot(
    val service: Service,
    val origin: Int,
    val route: List<Int>,
    val isResponse: Boolean,
    val hop: Int,
    val progress: Float,
)

/** An incident; an excavator names its cable by the node ids [cableA] and [cableB], an outage its [node]. */
@Serializable
data class IncidentSnapshot(
    val kind: IncidentKind,
    val cableA: Int? = null,
    val cableB: Int? = null,
    val node: Int? = null,
    val cutAt: Float = 0f,
    val warning: Float,
    val remaining: Float,
)
