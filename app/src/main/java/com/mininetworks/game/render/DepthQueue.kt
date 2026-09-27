package com.mininetworks.game.render

/**
 * Draw order for the painter's algorithm without allocations per frame: every item is a [kind], a reference and an
 * optional point, filed under a depth. [sort] puts them back to front; items of equal depth keep the order they were
 * added in. The arrays grow when needed and are reused from frame to frame.
 */
class DepthQueue(capacity: Int = 64) {
    /** Sortable depth in the high 32 bits, insertion index in the low 32 bits: unique, so any sort is stable. */
    private var keys = LongArray(capacity)
    private var kinds = IntArray(capacity)
    private var refs = arrayOfNulls<Any>(capacity)
    private var xs = FloatArray(capacity)
    private var ys = FloatArray(capacity)

    var size = 0
        private set

    fun clear() {
        refs.fill(null, 0, size)
        size = 0
    }

    fun add(depth: Float, kind: Int, ref: Any?, x: Float = 0f, y: Float = 0f) {
        if (size == keys.size) grow()
        keys[size] = (sortable(depth).toLong() shl 32) or size.toLong()
        kinds[size] = kind
        refs[size] = ref
        xs[size] = x
        ys[size] = y
        size++
    }

    /** Orders the items back to front (heap sort: in place, no allocation). */
    fun sort() {
        val n = size
        for (i in n / 2 - 1 downTo 0) siftDown(i, n)
        for (end in n - 1 downTo 1) {
            val top = keys[0]; keys[0] = keys[end]; keys[end] = top
            siftDown(0, end)
        }
    }

    /** Kind, reference and point of the [i]-th item in the current order. */
    fun kind(i: Int) = kinds[slot(i)]
    fun ref(i: Int) = refs[slot(i)]
    fun x(i: Int) = xs[slot(i)]
    fun y(i: Int) = ys[slot(i)]

    private fun slot(i: Int) = (keys[i] and 0xFFFFFFFFL).toInt()

    private fun siftDown(start: Int, n: Int) {
        var root = start
        while (true) {
            var child = 2 * root + 1
            if (child >= n) return
            if (child + 1 < n && keys[child + 1] > keys[child]) child++
            if (keys[root] >= keys[child]) return
            val t = keys[root]; keys[root] = keys[child]; keys[child] = t
            root = child
        }
    }

    private fun grow() {
        val cap = keys.size * 2
        keys = keys.copyOf(cap)
        kinds = kinds.copyOf(cap)
        refs = refs.copyOf(cap)
        xs = xs.copyOf(cap)
        ys = ys.copyOf(cap)
    }

    private companion object {
        /** An int whose signed order is the order of [f] (for all non-NaN floats). */
        fun sortable(f: Float): Int {
            val bits = java.lang.Float.floatToRawIntBits(f)
            return if (bits < 0) bits xor 0x7FFFFFFF else bits
        }
    }
}
