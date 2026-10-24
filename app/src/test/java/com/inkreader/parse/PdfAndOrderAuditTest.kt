package com.inkreader.parse

import org.junit.Assert.*
import org.junit.Test

class PdfAndOrderAuditTest {
    private fun line(text: String, y: Float) = PdfParagraphs.Line(text, 12f, y, true)
    @Test fun repeatedChapterPatternsAtPageTopsAreKept() {
        val pages = (1..5).map { listOf(line("第${it}章", 10f), line("正文。", 80f), line("$it", 700f)) }
        val filter = PdfArtifacts(pages)
        pages.forEach { page ->
            assertFalse(filter.drop(page.first(), 0, page.size))
            assertTrue(filter.drop(page.last(), page.lastIndex, page.size))
        }
    }

    @Test fun runningHeadersCannotRemoveSameTextOrNumbersInMiddleOfPage() {
        val pages = (1..4).map { listOf(line("书名", 10f), line("正文甲。",100f), line("书名",200f),
            line("42",300f), line("正文乙。",400f), line("$it",700f)) }
        val filter = PdfArtifacts(pages)
        val collected = PdfParagraphs.collect(pages, {false}, {30f}, filter::drop).map { it.text }
        assertEquals(4, collected.count { it == "书名" })
        assertEquals(4, collected.count { it == "42" })
        assertEquals(4, collected.count { it == "正文甲。" })
        assertEquals(4, collected.count { it == "正文乙。" })
    }

    @Test fun repeatedSentencesAreNotDeletedAsRunningHeaders() {
        val pages = (1..4).map { listOf(line("同一段正文。",10f), line("中间。",100f), line("$it",700f)) }
        val filter = PdfArtifacts(pages)
        pages.forEach { assertFalse(filter.drop(it.first(),0,it.size)) }
    }

    @Test fun multipartFilesSortUpperMiddleLowerWithoutMixingTitles() {
        fun sorted(names: List<String>) = names.sortedWith(compareBy(
            {ChapterSplitter.fileOrderKey(it).order}, {ChapterSplitter.fileOrderKey(it).family}, {ChapterSplitter.fileOrderKey(it).segment}))
        assertEquals(listOf("最终章（上）.txt","最终章（中）.txt","最终章（下）.txt"),
            sorted(listOf("最终章（下）.txt","最终章（上）.txt","最终章（中）.txt")))
        assertEquals(listOf("第9章.txt","第10章（上）.txt","第10章（中）.txt","第10章（下）.txt","第11章.txt"),
            sorted(listOf("第10章（下）.txt","第11章.txt","第10章（中）.txt","第9章.txt","第10章（上）.txt")))
        assertEquals(-1, ChapterSplitter.fileOrderKey("山上.txt").segment)
    }
}
