package com.mininetworks.game.render

import com.mininetworks.game.game.Cell
import com.mininetworks.game.game.Terrain
import com.mininetworks.game.game.World

/** Decorations on free land. Purely visual: they never block anything and give way to nodes and cables. */
enum class Decor { TREE, PINE, BUSH, HOUSE }

/**
 * Where the isometric style puts decorations and how it varies the ground, deterministic from the world's seed and
 * the cell, so the same map always looks the same and nothing flickers. Pure Kotlin, no Android types.
 */
object Scenery {
    /** Coarse grid of the forest noise: woods come in patches about this many cells wide. */
    private const val PATCH = 4f

    /**
     * The decoration planned for dry cell ([x], [y]) of a map with [seed], or null for bare land.
     * Woods cluster in patches, houses stand alone on open land.
     */
    fun planned(seed: Long, x: Int, y: Int): Decor? {
        val r = unit(seed, x, y, 1)
        val forest = forest(seed, x, y)
        return when {
            forest > 0.6f && r < 0.5f -> if (unit(seed, x, y, 2) < 0.45f) Decor.PINE else Decor.TREE
            r < 0.05f -> Decor.TREE
            r < 0.08f -> Decor.BUSH
            forest < 0.45f && r > 0.965f -> Decor.HOUSE
            else -> null
        }
    }

    /**
     * The decoration of cell ([x], [y]) in the outskirts around the board, [dist] cells beyond its edge: denser than
     * on the board (woods in patches, hedgerow trees, hamlets of a few cottages), so the land a portrait screen shows
     * above and below the board reads as countryside instead of empty haze (judge panel); thinning out far away.
     */
    fun outskirt(seed: Long, x: Int, y: Int, dist: Int): Decor? {
        val r = unit(seed, x, y, 1)
        val forest = forest(seed, x, y)
        val thin = (1f - (dist - 10).coerceAtLeast(0) / 20f).coerceIn(0.35f, 1f)
        val hamlet = forest(seed + 17, x, y)
        return when {
            forest > 0.55f && r < 0.72f * thin -> if (unit(seed, x, y, 2) < 0.5f) Decor.PINE else Decor.TREE
            hamlet > 0.7f && forest < 0.5f && r < 0.16f * thin -> Decor.HOUSE
            r < 0.12f * thin -> Decor.TREE
            r < 0.18f * thin -> Decor.BUSH
            forest < 0.45f && r > 1f - 0.03f * thin -> Decor.HOUSE
            else -> null
        }
    }

    /**
     * Visible decorations of [world], back to front: planned on plain land cells that no node footprint and no cable covers.
     * A node that spawns or a cable that is laid on a decorated cell replaces the decoration.
     */
    fun decorations(world: World, taken: Set<Cell> = occupied(world)): List<Pair<Cell, Decor>> {
        val out = ArrayList<Pair<Cell, Decor>>()
        for (y in 0 until world.rows) for (x in 0 until world.cols) {
            if (world.terrainAt(x, y) != Terrain.LAND) continue
            val cell = Cell(x, y)
            if (cell in taken) continue
            planned(world.seed, x, y)?.let { out += cell to it }
        }
        out.sortBy { it.first.x + it.first.y }
        return out
    }

    /** Cells covered by a node or a cable, plus [extra] cells the caller keeps free (such as an excavator's stand). */
    fun occupied(world: World, extra: Collection<Cell> = emptyList()): Set<Cell> {
        val taken = HashSet<Cell>(extra)
        for (n in world.nodes) taken += n.footprint
        for (c in world.cables) taken += c.layout.cells
        return taken
    }

    /** Subtle per-cell brightness offset of the ground tile in -1..1. */
    fun tileVariation(seed: Long, x: Int, y: Int) = unit(seed, x, y, 3) * 2f - 1f

    /** Deterministic value in 0..1 for cell ([x], [y]); [salt] gives independent streams. */
    fun unit(seed: Long, x: Int, y: Int, salt: Int): Float {
        var z = seed * -0x61c8864680b583ebL + x * 0x632BE59BD9B4E019L + y * -0x3d4d51c2d82b14b1L + salt * 0x2545F4914F6CDD1DL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return (z ushr 40).toFloat() / (1L shl 24).toFloat()
    }

    /** Smooth value noise in 0..1 on the coarse [PATCH] grid. */
    private fun forest(seed: Long, x: Int, y: Int): Float {
        val fx = x / PATCH
        val fy = y / PATCH
        val x0 = kotlin.math.floor(fx).toInt()
        val y0 = kotlin.math.floor(fy).toInt()
        val tx = smooth(fx - x0)
        val ty = smooth(fy - y0)
        fun v(i: Int, j: Int) = unit(seed, i, j, 7)
        val top = v(x0, y0) + (v(x0 + 1, y0) - v(x0, y0)) * tx
        val bottom = v(x0, y0 + 1) + (v(x0 + 1, y0 + 1) - v(x0, y0 + 1)) * tx
        return top + (bottom - top) * ty
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)
}
