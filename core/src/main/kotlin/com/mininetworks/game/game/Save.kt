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

    fun encode(world: World): String = json.encodeToString(WorldSnapshot.serializer(), world.snapshot())

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
    val cols: Int,
    val rows: Int,
    val seed: Long,
    /** How many values the world's random generator had produced; see [ReplayableRandom]. */
    val randomDraws: Long,
    val nextId: Int,
    /** One string per row, `~` for water and `.` for land. */
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
    val serverVouchers: Int,
    val lastNews: WeekNews?,
    val lastNewsTime: Float,
    val clientSpawnTimer: Float,
    val nodes: List<NodeSnapshot>,
    val cables: List<CableSnapshot>,
    val packets: List<PacketSnapshot>,
)

@Serializable
data class RewardOfferSnapshot(val week: Int, val choices: List<Reward>)

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
