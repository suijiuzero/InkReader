package com.inkreader.parse

/** 仅记录调用方需要的源字符锚点；不为整本正文保留逐字映射。 */
class NormalizationAnchors(points: IntArray) {
    private val source = points.copyOf().apply { sort() }.let { sorted ->
        var count = 0
        for (value in sorted) { require(value >= 0); if (count == 0 || sorted[count - 1] != value) sorted[count++] = value }
        sorted.copyOf(count)
    }
    private val target = IntArray(source.size)
    private var cursor = 0
    private var lineStart = 0
    internal fun record(position: Int, output: Int) {
        while (cursor < source.size && source[cursor] <= position) target[cursor++] = output
    }
    internal fun finishLine(first: Int, last: Int) {
        for (i in lineStart until cursor) target[i] = target[i].coerceIn(first, last)
        lineStart = cursor
    }
    internal fun finish(output: Int) { while (cursor < source.size) target[cursor++] = output }
    fun map(position: Int): Int {
        val index = source.binarySearch(position)
        require(index >= 0) { "Unregistered text anchor" }
        return target[index]
    }
}
