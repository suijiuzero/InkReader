package com.inkreader.data.repo

/** 有界 Myers 差异：分别迁移多处修改；大幅重写的区域收拢到新边界。 */
class TextEditMap(val before: String, val after: String) {
    private data class Op(val kind: Int, val size: Int)
    private data class Change(val start: Int, val end: Int, val newStart: Int, val newEnd: Int)
    private val changes: List<Change>
    private var work = 0

    init {
        val ops = ArrayList<Op>()
        diff(0, before.length, 0, after.length, ops)
        val out = ArrayList<Change>()
        var old = 0; var fresh = 0
        var pendingOld = -1; var pendingNew = -1
        fun flush() {
            if (pendingOld >= 0) out += Change(pendingOld, old, pendingNew, fresh)
            pendingOld = -1
        }
        for (op in ops) {
            if (op.kind == 0) { flush(); old += op.size; fresh += op.size }
            else {
                if (pendingOld < 0) { pendingOld = old; pendingNew = fresh }
                if (op.kind < 0) old += op.size else fresh += op.size
            }
        }
        flush()
        changes = out
    }

    fun offset(value: Int, end: Boolean = false): Int {
        val p = value.coerceIn(0, before.length)
        var delta = 0
        for (c in changes) {
            if (p < c.start) break
            if (p <= c.end) {
                return when {
                    c.start == c.end -> if (end) c.newStart else c.newEnd
                    p == c.start -> c.newStart
                    p == c.end -> c.newEnd
                    end -> c.newEnd
                    else -> c.newStart
                }.coerceIn(0, after.length)
            }
            delta = c.newEnd - c.end
        }
        return (p + delta).coerceIn(0, after.length)
    }

    fun range(start: Int, end: Int): Pair<Int, Int> {
        if (start == end) return offset(start).let { it to it }
        val a = offset(start); val b = offset(end, true)
        return minOf(a, b) to maxOf(a, b)
    }

    private fun diff(a0: Int, a1: Int, b0: Int, b1: Int, out: MutableList<Op>, depth: Int = 0) {
        var a = a0; var b = b0
        while (a < a1 && b < b1 && before[a] == after[b]) { a++; b++ }
        if (a > a0) out += Op(0, a - a0)
        var ae = a1; var be = b1
        while (ae > a && be > b && before[ae - 1] == after[be - 1]) { ae--; be-- }
        when {
            a == ae -> if (b < be) out += Op(1, be - b)
            b == be -> out += Op(-1, ae - a)
            depth >= 32 || work >= 2_000_000 -> { out += Op(-1, ae - a); out += Op(1, be - b) }
            else -> {
                val split = bisect(a, ae, b, be)
                if (split == null || (split.first == a && split.second == b) || (split.first == ae && split.second == be)) {
                    out += Op(-1, ae - a); out += Op(1, be - b)
                } else {
                    diff(a, split.first, b, split.second, out, depth + 1)
                    diff(split.first, ae, split.second, be, out, depth + 1)
                }
            }
        }
        if (ae < a1) out += Op(0, a1 - ae)
    }

    private fun bisect(a: Int, ae: Int, b: Int, be: Int): Pair<Int, Int>? {
        val n = ae - a; val m = be - b
        if (n.toLong() + m > 1_000_000) return null
        val max = (n + m + 1) / 2
        val offset = max + 1
        val forward = IntArray(2 * max + 3) { -1 }
        val reverse = IntArray(forward.size) { -1 }
        forward[offset + 1] = 0; reverse[offset + 1] = 0
        val delta = n - m
        val odd = delta % 2 != 0
        for (d in 0..minOf(max, 512)) {
            for (k in -d..d step 2) {
                if (++work >= 2_000_000) return null
                val slot = offset + k
                var x = if (k == -d || (k != d && forward[slot - 1] < forward[slot + 1])) forward[slot + 1] else forward[slot - 1] + 1
                var y = x - k
                if (x < 0 || y < 0) continue
                while (x < n && y < m && before[a + x] == after[b + y]) { x++; y++; if (++work >= 2_000_000) return null }
                forward[slot] = x
                val backSlot = offset + delta - k
                if (odd && backSlot in reverse.indices && reverse[backSlot] >= 0 && x >= n - reverse[backSlot])
                    return (a + x.coerceAtMost(n)) to (b + y.coerceAtMost(m))
            }
            for (k in -d..d step 2) {
                if (++work >= 2_000_000) return null
                val slot = offset + k
                var x = if (k == -d || (k != d && reverse[slot - 1] < reverse[slot + 1])) reverse[slot + 1] else reverse[slot - 1] + 1
                var y = x - k
                if (x < 0 || y < 0) continue
                while (x < n && y < m && before[ae - x - 1] == after[be - y - 1]) { x++; y++; if (++work >= 2_000_000) return null }
                reverse[slot] = x
                val frontSlot = offset + delta - k
                if (!odd && frontSlot in forward.indices && forward[frontSlot] >= 0 && forward[frontSlot] >= n - x) {
                    val fx = forward[frontSlot].coerceAtMost(n)
                    val fy = fx - (delta - k)
                    return (a + fx) to (b + fy.coerceIn(0, m))
                }
            }
        }
        return null
    }
}
