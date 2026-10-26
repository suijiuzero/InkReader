package com.inkreader.data.repo

/** 重分章只删除/重新组织空白和标题；保留每个输出字符在原全文中的位置。 */
object TextRemap {
    fun align(source: String, outputs: List<String>): List<IntArray> {
        val compact = StringBuilder()
        val positions = IntArray(source.length)
        var count = 0
        source.forEachIndexed { i, c ->
            if (!c.isWhitespace() && c != '\uFEFF' && c != '\u200B') {
                positions[count++] = i
                compact.append(c)
            }
        }
        val search = compact.toString()
        var cursor = 0
        return outputs.map { text ->
            val mapping = IntArray(text.length)
            val whole = text.filter { !it.isWhitespace() }
            val exact = search.indexOf(whole, cursor)
            if (exact >= 0 && whole.isNotEmpty()) {
                cursor = exact
                var anchor = positions[cursor]
                text.forEachIndexed { i, c ->
                    if (!c.isWhitespace()) anchor = positions[cursor++]
                    mapping[i] = anchor
                }
            } else {
                // 清洗掉的整行可能打断连续匹配；按完整行定位，不能只搜批注的前几个字。
                var offset = 0
                var anchor = positions.getOrElse(cursor.coerceAtMost(count - 1).coerceAtLeast(0)) { source.length }
                text.split('\n').forEach { line ->
                    val token = line.filter { !it.isWhitespace() }
                    if (token.isNotEmpty()) {
                        val found = search.indexOf(token, cursor)
                        check(found >= 0) { "无法可靠映射重分章正文，原书已保留" }
                        cursor = found
                    }
                    line.forEach { c ->
                        if (!c.isWhitespace()) anchor = positions[cursor++]
                        mapping[offset++] = anchor
                    }
                    if (offset < mapping.size) mapping[offset++] = anchor
                }
            }
            mapping
        }
    }

    fun lowerBound(mapping: IntArray, sourceOffset: Int): Int {
        var lo = 0
        var hi = mapping.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (mapping[mid] < sourceOffset) lo = mid + 1 else hi = mid
        }
        return lo
    }

    data class Range(val chapter: Int, val start: Int, val end: Int)
    fun ranges(mappings: List<IntArray>, start: Int, end: Int): List<Range> {
        if (end <= start) return emptyList()
        return mappings.mapIndexedNotNull { chapter, map ->
            val a = lowerBound(map, start); val b = lowerBound(map, end)
            if (b > a) Range(chapter, a, b) else null
        }
    }

    fun locate(mappings: List<IntArray>, sourceOffset: Int): Pair<Int, Int> {
        for ((i, mapping) in mappings.withIndex()) {
            if (mapping.isNotEmpty() && sourceOffset <= mapping.last()) {
                return i to lowerBound(mapping, sourceOffset).coerceAtMost(mapping.size)
            }
        }
        return mappings.lastIndex to mappings.last().size
    }
}
