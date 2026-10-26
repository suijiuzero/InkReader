package com.inkreader.parse

/** 只为源范围记录输出位置，避免大书逐字符IntArray占用。 */
class OutputOrigins(sections: List<PreparedTxt.Section>) {
    data class Span(val sourceStart: Int, val sourceEnd: Int, val chapter: Int, val outputStart: Int)
    data class Range(val chapter: Int, val start: Int, val end: Int)
    val lengths = sections.map { it.length }
    val spans: List<Span> = buildList {
        sections.forEachIndexed { chapter, section ->
            var offset = 0
            for (piece in section.pieces) {
                if (piece.literal == null && piece.end > piece.start) add(Span(piece.start, piece.end, chapter, offset))
                offset += piece.length
            }
        }
    }.sortedBy { it.sourceStart }
    fun locate(position: Int, endBias: Boolean = false): Pair<Int, Int> {
        if (spans.isEmpty()) return 0 to 0
        var low = 0; var high = spans.size
        while (low < high) { val middle = (low + high) ushr 1; if (spans[middle].sourceStart <= position) low = middle + 1 else high = middle }
        val previous = spans.getOrNull(low - 1)
        if (previous != null && (position < previous.sourceEnd || endBias && position == previous.sourceEnd))
            return previous.chapter to (previous.outputStart + position - previous.sourceStart).coerceIn(0, lengths[previous.chapter])
        val next = spans.getOrNull(low)
        return if (next != null) next.chapter to next.outputStart else spans.last().let { it.chapter to lengths[it.chapter] }
    }
    fun intersections(start: Int, end: Int): List<Range> {
        if (end <= start) return emptyList()
        var low = 0; var high = spans.size
        while (low < high) { val middle = (low + high) ushr 1; if (spans[middle].sourceEnd <= start) low = middle + 1 else high = middle }
        val result = ArrayList<Range>()
        for (i in low until spans.size) {
            val span = spans[i]; if (span.sourceStart >= end) break
            val a = maxOf(start, span.sourceStart); val b = minOf(end, span.sourceEnd)
            if (b > a) {
                val range = Range(span.chapter, span.outputStart + a - span.sourceStart, span.outputStart + b - span.sourceStart)
                val last = result.lastOrNull()
                if (last != null && last.chapter == range.chapter && last.end <= range.start) result[result.lastIndex] = last.copy(end = range.end)
                else result += range
            }
        }
        return result
    }
}
