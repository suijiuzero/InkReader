package com.inkreader.parse

/** 比较前后编号锚点之间的完整候选方案；证据接近时保留疑点，不强选。 */
object SequencePlanner {
    data class Choice(val rejected: Set<Int>, val review: Set<Int>)
    private val segment = Regex("[（(\\[][上中下一二三四五六七八九十0-9]+[）)\\]]\\s*$")
    fun resolve(lines: List<String>, decisions: List<HeadingDecision>): Choice {
        val nodes = decisions.filter { it.accepted }.sortedBy { it.line }
        if (nodes.size < 4) return Choice(emptySet(), emptySet())
        val prefix = LongArray(lines.size + 1)
        for (i in lines.indices) {
            if (i and 511 == 0) ParseCancellation.checkpoint()
            prefix[i + 1] = prefix[i] + ((lines as? SizedLines)?.characters(i, true) ?: lines[i].trim().length)
        }
        val orders = nodes.map { ChapterSplitter.chapterOrderFromTitle(it.title)?.takeIf { n -> n in 1..10000000 } }
        val rejected = HashSet<Int>(); val review = HashSet<Int>()
        var start = 1
        while (start < nodes.lastIndex) {
            val order = orders[start]
            var end = start + 1
            while (order != null && end < nodes.size && orders[end] == order) end++
            if (end - start >= 2 && end < nodes.size && orders[start - 1] == order!! - 1 && orders[end] == order + 1) {
                val firstLine = nodes[start - 1].line - 1; val lastLine = nodes[end].line - 1
                val regionBreak = (firstLine + 1 until lastLine).any { ChapterSplitter.extractVolumeTitle(lines[it]) != null || ChapterSplitter.isBookendTitle(lines[it]) }
                val candidates = nodes.subList(start, end)
                if (!regionBreak && candidates.none { segment.containsMatchIn(it.title) }) {
                    review += candidates.map { it.line - 1 }
                    fun isolated(node: HeadingDecision): Boolean {
                        val i = node.line - 1
                        return (i == 0 || lines[i - 1].isBlank()) && (i == lines.lastIndex || lines[i + 1].isBlank())
                    }
                    val ranked = candidates.sortedByDescending { it.score * 2 + if (isolated(it)) 32 else 0 }
                    val best = ranked.first(); val second = ranked[1]
                    val qualityGap = (best.score - second.score) * 2 + (if (isolated(best)) 32 else 0) - (if (isolated(second)) 32 else 0)
                    val i = best.line - 1
                    val bodyOnBothSides = prefix[i] - prefix[firstLine + 1] >= 160 && prefix[lastLine] - prefix[i + 1] >= 160
                    if (qualityGap >= 24 && isolated(best) && bodyOnBothSides && ranked.drop(1).all { node ->
                        val line = node.line - 1
                        line > 0 && line < lines.lastIndex && lines[line - 1].isNotBlank() && lines[line + 1].isNotBlank()
                    }) rejected += ranked.drop(1).map { it.line - 1 }
                }
            }
            start = maxOf(start + 1, end)
        }
        return Choice(rejected, review)
    }
}
