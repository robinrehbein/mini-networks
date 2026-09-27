package com.mininetworks.game.render

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** The painter's queue sorts back to front, keeps the insertion order of equal depths and reuses its storage. */
class DepthQueueTest {

    private fun order(q: DepthQueue) = (0 until q.size).map { q.ref(it) }

    @Test
    fun sortsByDepthAndKeepsTiesInInsertionOrder() {
        val q = DepthQueue(2)
        val depths = listOf(3f, -1.5f, 3f, 0f, -0f, 12.25f, -7f, 3f, 0.01f)
        depths.forEachIndexed { i, d -> q.add(d, kind = i % 3, ref = "item$i", x = i.toFloat(), y = -i.toFloat()) }
        q.sort()
        val expected = depths.withIndex().sortedBy { it.value }.map { "item${it.index}" }
        assertEquals(expected, order(q))
        for (k in 0 until q.size) {
            val i = (q.ref(k) as String).removePrefix("item").toInt()
            assertEquals(i % 3, q.kind(k))
            assertEquals(i.toFloat(), q.x(k))
            assertEquals(-i.toFloat(), q.y(k))
        }
    }

    @Test
    fun matchesAStableSortOnRandomDepths() {
        val q = DepthQueue()
        val rnd = Random(4)
        repeat(3) { round ->
            q.clear()
            val depths = List(300 + round * 50) { (rnd.nextInt(80) - 20) / 4f }
            depths.forEachIndexed { i, d -> q.add(d, 0, i) }
            q.sort()
            assertEquals(depths.indices.sortedBy { depths[it] }, order(q))
        }
    }
}
