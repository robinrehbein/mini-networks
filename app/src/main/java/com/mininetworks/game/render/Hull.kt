package com.mininetworks.game.render

/**
 * Convex hull of a few points (Andrew's monotone chain) without allocating, for soft shadows swept by the light.
 * Pure Kotlin; one instance per renderer, as it reuses its scratch arrays.
 */
class Hull(private val maxPoints: Int = 16) {
    private val order = IntArray(maxPoints)
    private val chain = IntArray(2 * maxPoints + 1)

    /**
     * Writes the hull of the first [n] points of [pts] (x, y pairs) into [out] as x, y pairs in order around it and
     * returns how many corners it has. [out] needs room for 2 × [n] floats.
     */
    fun convex(pts: FloatArray, n: Int, out: FloatArray): Int {
        require(n in 1..maxPoints) { "1..$maxPoints points" }
        for (i in 0 until n) {
            var j = i
            while (j > 0 && less(pts, i, order[j - 1])) {
                order[j] = order[j - 1]
                j--
            }
            order[j] = i
        }
        if (n < 3) {
            for (k in 0 until n) { out[2 * k] = pts[2 * order[k]]; out[2 * k + 1] = pts[2 * order[k] + 1] }
            return n
        }
        var m = 0
        for (k in 0 until n) {
            while (m >= 2 && cross(pts, chain[m - 2], chain[m - 1], order[k]) <= 0f) m--
            chain[m++] = order[k]
        }
        val lower = m + 1
        for (k in n - 2 downTo 0) {
            while (m >= lower && cross(pts, chain[m - 2], chain[m - 1], order[k]) <= 0f) m--
            chain[m++] = order[k]
        }
        val count = m - 1
        for (k in 0 until count) { out[2 * k] = pts[2 * chain[k]]; out[2 * k + 1] = pts[2 * chain[k] + 1] }
        return count
    }

    private fun less(p: FloatArray, a: Int, b: Int) = p[2 * a] < p[2 * b] || (p[2 * a] == p[2 * b] && p[2 * a + 1] < p[2 * b + 1])

    private fun cross(p: FloatArray, o: Int, a: Int, b: Int): Float =
        (p[2 * a] - p[2 * o]) * (p[2 * b + 1] - p[2 * o + 1]) - (p[2 * a + 1] - p[2 * o + 1]) * (p[2 * b] - p[2 * o])
}
