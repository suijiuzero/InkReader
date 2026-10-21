package com.inkreader.data.repo

import com.inkreader.data.model.Mark
import com.inkreader.data.model.MarkType
import org.junit.Assert.*
import org.junit.Test

class DataSafetyTest {
    @Test fun deleteKeepsNextChapterMarks() {
        val marks = (0..2).map { Mark(id = it.toLong() + 1, bookId = 1, chapterIndex = it, startOffset = 0, endOffset = 0) }
        val result = MarkShifts.forDelete(marks, 0)
        assertEquals(listOf(2L, 3L), result.map { it.id })
        assertEquals(listOf(0, 1), result.map { it.chapterIndex })
    }

    @Test fun trimmedBookmarkKeepsValidRange() {
        val mark = Mark(bookId = 1, chapterIndex = 0, startOffset = 8, endOffset = 8, type = MarkType.BOOKMARK)
        val moved = MarkShifts.forSplitAt(listOf(mark), 0, 10, 12, 6, 10).single()
        assertEquals(6, moved.startOffset)
        assertEquals(6, moved.endOffset)
    }

    @Test fun repeatedTextMapsToItsOriginalOccurrence() {
        val source = "第一章\n\n重复对白\n\n第二章\n\n重复对白"
        val mapping = TextRemap.align(source, listOf("重复对白", "重复对白"))
        val second = source.lastIndexOf("重复对白")
        assertEquals(1 to 0, TextRemap.locate(mapping, second))
        assertEquals(second, mapping[1][0])
    }

    @Test fun removedTitlesWatermarksAndWhitespaceKeepSourceAnchors() {
        val source = "第一章\n\n  第一段\r\n水印网址\n第二段\n\n第二章\n\n末段"
        val mapping = TextRemap.align(source, listOf("第一段\n第二段", "末段"))
        assertEquals(0 to 4, TextRemap.locate(mapping, source.indexOf("第二段")))
        assertEquals(1 to 1, TextRemap.locate(mapping, source.indexOf("末段") + 1))
        mapping.forEach { arr -> assertTrue(arr.asList().zipWithNext().all { (a, b) -> a <= b }) }
    }

    @Test fun invalidMappingFailsBeforeReplacingBook() {
        try {
            TextRemap.align("原正文", listOf("不存在的新正文"))
            fail("unmappable text must be rejected")
        } catch (_: IllegalStateException) { }
    }
}
