package com.inkreader.parse

import com.inkreader.data.model.SplitOptions
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DuplicateChapterHeadingsTest {
    private val body = "完整正文保留，不因为标题去重而删掉任何叙述。".repeat(20)
    @Test fun bareAndDescriptiveCopiesCollapseWithoutDuplicatingTheChapterInItsBody() {
        val raw = "样本书名\n\n第一章\n\n样本书名\n\n第1章 相遇\n\n$body\n\n第二章 归来\n\n第二章 归来\n\n$body"
        val parts = ChapterSplitter.split(raw)
        assertEquals(listOf("第1章 相遇", "第二章 归来"), parts.map { it.title })
        assertFalse(parts.first().content.contains("第一章"))
        assertFalse(parts.first().content.contains("第1章 相遇"))
        assertEquals(body, parts.last().content)
        val dir = Files.createTempDirectory("ink-duplicate-headers").toFile()
        try {
            val options = SplitOptions(cleanWatermark = false)
            IndexedTxtStore(dir).withDocument("sample.txt", options, { raw.byteInputStream() }) { doc ->
                assertEquals(parts, doc.sections.map { ChapterSplitter.Part(it.title, doc.body(it), it.volume) })
                assertEquals(2, doc.plan.omittedHeadingLines.size)
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun repeatedProseSplitPartsAndVolumeResetsArePreserved() {
        val inputs = listOf(
            "第一章 相遇\n\n相同的正文句子。\n\n相同的正文句子。\n\n第一章 相遇\n\n$body",
            "第一章（上）\n\n$body\n\n第一章（下）\n\n$body",
            "第一章 相遇\n\n第一章里提到的事情还没有结束。\n\n第一章 相遇\n\n$body",
            "第一章 相遇\n\n第二卷\n\n第一章 相遇\n\n$body")
        for (text in inputs) {
            val plan = ChapterSplitter.plan(text.split("\n"), text.length, SplitOptions())
            assertTrue(plan.omittedHeadingLines.isEmpty())
        }
    }
    @Test fun exactSubpartCopiesAndPunctuatedTitlesCollapseButDifferentSubpartsRemain() {
        val lines = listOf("第一章（上）", "", "第一章（上）", "", body)
        val result = DuplicateChapterHeadings.resolve(lines, listOf(0, 2), SplitOptions())
        assertEquals(listOf(0), result.headings)
        assertEquals(setOf(2), result.omitted)
        assertTrue(DuplicateChapterHeadings.resolve(listOf("第一章", "", "第一章（上）", "", body), listOf(0, 2), SplitOptions()).omitted.isEmpty())
        val quoted = listOf("第一章 奇怪的“朋友”", "", "第一章 奇怪的“朋友”", "", body)
        assertEquals(setOf(2), DuplicateChapterHeadings.resolve(quoted, listOf(0, 2), SplitOptions()).omitted)
    }
    @Test fun explicitContentMarkerBetweenIdenticalTitlesDoesNotCreateAnotherChapter() {
        val raw = "第五十八章 出发\n\n$body\n\n第59章 到达\n\n正文\n\n第59章 到达\n\n$body\n\n第60章 归来\n\n$body"
        val options = SplitOptions(cleanWatermark = false)
        val parts = ChapterSplitter.split(raw, options = options)
        assertEquals(listOf(58, 59, 60), parts.map { ChapterSplitter.chapterOrderFromTitle(it.title) })
        assertEquals("正文\n\n\n$body", parts[1].content)
        val dir = Files.createTempDirectory("ink-content-marker").toFile()
        try {
            IndexedTxtStore(dir).withDocument("sample.txt", options, { raw.byteInputStream() }) { doc ->
                assertEquals(parts, doc.sections.map { ChapterSplitter.Part(it.title, doc.body(it), it.volume) })
                assertEquals(1, doc.plan.omittedHeadingLines.size)
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun emptyChapterDoesNotInventACopyOfItsTitleAsBody() {
        val raw = "第14章 空章\n\n第15章 继续\n\n$body"
        for (clean in listOf(false, true)) {
            val options = SplitOptions(cleanWatermark = clean)
            val parts = ChapterSplitter.split(raw, options = options)
            assertEquals(2, parts.size)
            assertEquals("", parts.first().content)
            assertEquals(body, parts.last().content)
            val dir = Files.createTempDirectory("ink-empty-chapter").toFile()
            try {
                IndexedTxtStore(dir).withDocument("sample.txt", options, { raw.byteInputStream() }) { doc ->
                    assertEquals(parts, doc.sections.map { ChapterSplitter.Part(it.title, doc.body(it), it.volume) })
                }
            } finally { dir.deleteRecursively() }
        }
    }
    @Test fun unselectedBodyReferencesCannotBePromotedByTheDuplicatePass() {
        val lines = listOf("第一章", "", "第一章里提到的事", "", body)
        val result = DuplicateChapterHeadings.resolve(lines, listOf(0), SplitOptions(), candidates = setOf(0))
        assertEquals(listOf(0), result.headings)
        assertTrue(result.omitted.isEmpty())
    }
    @Test fun explicitUserDecisionsProtectHeaderCopiesAndUnknownShortTextIsNotDiscarded() {
        val lines = listOf("第一章 相遇", "", "第一章 相遇", "", body)
        val forced = DuplicateChapterHeadings.resolve(lines, listOf(0, 2), SplitOptions(forcedHeadingLines = setOf(1, 3)))
        assertEquals(listOf(0, 2), forced.headings)
        assertTrue(forced.omitted.isEmpty())
        val bodyLines = listOf("第一章 相遇", "", "站在门口", "", "第一章 相遇", "", body)
        assertTrue(DuplicateChapterHeadings.resolve(bodyLines, listOf(0), SplitOptions()).omitted.isEmpty())
    }
}
