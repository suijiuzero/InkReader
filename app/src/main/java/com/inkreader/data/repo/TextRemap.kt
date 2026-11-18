package com.inkreader.data.repo

import com.inkreader.parse.ParseCancellation

/** 重分章只删除/重新组织空白和标题；保留每个输出字符在原全文中的位置。 */
object TextRemap {
    private fun retained(c: Char): Boolean = !c.isWhitespace() && c != '\uFEFF' && c != '\u200B'

    /** Searches compact text, then advances through original offsets in the same order. */
    private class SourceCursor(private val source: String) {
        val search = buildString(source.length) {
            source.forEachIndexed { index, c ->
                if (index and 16383 == 0) ParseCancellation.checkpoint()
                if (retained(c)) append(c)
            }
        }
        var index = 0
            private set
        private var nextOffset = nextRetained(0)
        private var previousOffset = 0

        private fun nextRetained(from: Int): Int {
            var offset = from
            while (offset < source.length && !retained(source[offset])) {
                if (offset and 16383 == 0) ParseCancellation.checkpoint()
                offset++
            }
            return offset
        }

        fun take(): Int {
            previousOffset = nextOffset
            nextOffset = nextRetained(nextOffset + 1)
            index++
            return previousOffset
        }

        fun seek(target: Int) {
            while (index < target) {
                if (index and 16383 == 0) ParseCancellation.checkpoint()
                take()
            }
        }

        fun anchor(): Int = if (index < search.length) nextOffset else previousOffset
    }

    fun align(source: String, outputs: List<String>): List<IntArray> =
        buildList(outputs.size) { visitAligned(source, outputs) { _, mapping -> add(mapping) } }

    /** Keep only the reading position for plain books without annotations. Validate every part. */
    internal fun locateAligned(source: String, outputs: List<String>, sourceOffset: Int): Pair<Int, Int> {
        var result: Pair<Int, Int>? = null
        visitAligned(source, outputs, needsOffsets = { result == null }) { chapter, mapping ->
            if (result == null && mapping.isNotEmpty() && sourceOffset <= mapping.last()) {
                result = chapter to lowerBound(mapping, sourceOffset)
            }
        }
        return result ?: (outputs.lastIndex to outputs.last().length)
    }

    private inline fun visitAligned(
        source: String, outputs: List<String>, needsOffsets: () -> Boolean = { true },
        visit: (Int, IntArray) -> Unit
    ) {
        val cursor = SourceCursor(source)
        var searchIndex = 0
        for ((chapter, text) in outputs.withIndex()) {
            ParseCancellation.checkpoint()
            val mapping = if (needsOffsets()) IntArray(text.length) else null
            val whole = text.filter { !it.isWhitespace() }
            val exact = cursor.search.indexOf(whole, searchIndex)
            if (exact >= 0 && whole.isNotEmpty()) {
                searchIndex = exact + whole.length
                if (mapping != null) {
                    cursor.seek(exact)
                    var anchor = cursor.anchor()
                    text.forEachIndexed { i, c ->
                        if (!c.isWhitespace()) anchor = cursor.take()
                        mapping[i] = anchor
                    }
                }
            } else {
                // 清洗掉的整行可能打断连续匹配；按完整行定位，不能只搜批注的前几个字。
                var offset = 0
                var anchor = 0
                if (mapping != null) {
                    cursor.seek(searchIndex)
                    anchor = cursor.anchor()
                }
                text.splitToSequence('\n').forEach { line ->
                    val token = line.filter { !it.isWhitespace() }
                    if (token.isNotEmpty()) {
                        val found = cursor.search.indexOf(token, searchIndex)
                        check(found >= 0) { "无法可靠映射重分章正文，原书已保留" }
                        searchIndex = found + token.length
                        if (mapping != null) cursor.seek(found)
                    }
                    if (mapping != null) {
                        line.forEach { c ->
                            if (!c.isWhitespace()) anchor = cursor.take()
                            mapping[offset++] = anchor
                        }
                        if (offset < mapping.size) mapping[offset++] = anchor
                    }
                }
            }
            if (mapping != null) visit(chapter, mapping)
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
