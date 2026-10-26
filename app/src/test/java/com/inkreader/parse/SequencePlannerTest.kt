package com.inkreader.parse

import org.junit.Assert.*
import org.junit.Test

class SequencePlannerTest {
    private val body = "正文完整保留。".repeat(40)
    @Test fun completeAlternativePrefersIsolatedHeadingToAnInlineSameNumberReference() {
        val text = "第十二章 起点\n\n$body\n\n第十三章 正式标题\n\n$body\n第十三章 旧书的片段\n$body\n\n第十四章 归来\n\n$body"
        val result = ChapterSplitter.analyze(text)
        assertEquals(listOf("第十二章 起点", "第十三章 正式标题", "第十四章 归来"), result.parts.map { it.title })
        assertTrue(result.parts.joinToString { it.content }.contains("第十三章 旧书的片段"))
        assertTrue(result.decisions.any { HeadingReason.SEQUENCE_ALTERNATIVE in it.reasons })
    }
    @Test fun equallySupportedAlternativesRemainReviewableAndUpperLowerPartsAreKept() {
        val text = "第十二章 起点\n\n$body\n\n第十三章 甲\n\n$body\n\n第十三章 乙\n\n$body\n\n第十四章 终点\n\n$body"
        val plan = ChapterSplitter.plan(text.lines(), text.length, com.inkreader.data.model.SplitOptions())
        assertEquals(4, plan.headings.size); assertTrue(plan.doubtLines.isNotEmpty())
        val segmented = text.replace("第十三章 甲", "第十三章（上）").replace("第十三章 乙", "第十三章（下）")
        assertEquals(4, ChapterSplitter.split(segmented).size)
    }
}
