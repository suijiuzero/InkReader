package com.inkreader.tts

/**
 * 把正文切成朗读单元（句子），返回每句在原文中的字符区间。
 * 朗读时按区间逐句提交给 TTS，UI 用同一区间做高亮跟随与翻页。
 */
object SentenceSplitter {

    private val TERMINATORS = charArrayOf('。', '！', '？', '；', '…', '!', '?', ';', '\n')
    private const val MAX_LEN = 220
    private const val MIN_LEN = 2

    fun split(text: String): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        val n = text.length
        var start = 0
        while (start < n) {
            // 跳过空白
            while (start < n && text[start].isWhitespace()) start++
            if (start >= n) break
            var end = start
            var lastBreak = -1
            while (end < n) {
                val c = text[end]
                end++
                if (c in TERMINATORS) {
                    lastBreak = end
                    if (end - start >= MIN_LEN) break
                } else if (end - start >= MAX_LEN) {
                    // 超长句：在最近的逗号/顿号处断开，否则硬断
                    val soft = findSoftBreak(text, start, end)
                    lastBreak = if (soft in start until end) soft + 1 else end
                    break
                }
            }
            var stop = if (lastBreak > start) lastBreak else minOf(end, n)
            if (stop < n && stop > start && Character.isHighSurrogate(text[stop - 1]) &&
                Character.isLowSurrogate(text[stop])) stop--
            val piece = text.substring(start, stop)
            if (piece.isNotBlank()) out += start until stop
            start = stop
        }
        return if (out.isEmpty() && text.isNotBlank()) listOf(0 until n) else out
    }

    private val COMMAS = charArrayOf('，', '、', '：', ',', ':')

    /** 在 [start, end) 内从后往前找最近的软断点（逗号/顿号/冒号），找不到返回 -1 */
    private fun findSoftBreak(text: String, start: Int, end: Int): Int {
        var i = minOf(end, text.length) - 1
        while (i > start) {
            if (text[i] in COMMAS) return i
            i--
        }
        return -1
    }
}
