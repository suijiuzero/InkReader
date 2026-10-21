package com.inkreader.parse

import com.inkreader.data.model.SplitMode
import com.inkreader.data.model.SplitOptions
import org.junit.Assert.*
import org.junit.Test

class ParserRegressionTest {
    @Test fun basenameExtensionsSelectMarkdown() {
        assertEquals("md", FormatDetector.extensionOf("notes.md"))
        assertEquals("md", FormatDetector.extensionOf("C:\\books\\notes.MD"))
        assertEquals(DocumentFormat.MARKDOWN, FormatDetector.detect("**bold**".toByteArray(), "notes.md"))
    }
    @Test fun ordinaryReferenceLinksSurviveCleaning() {
        val text = "正文甲\n参考资料：https://example.com\n正文乙"
        assertEquals(text, ChapterSplitter.cleanBodyWatermarks(text))
        assertEquals("正文", ChapterSplitter.cleanBodyWatermarks("正文\n=== 小说网 www.ad.com 独家首发 ==="))
    }
    @Test fun fixedChunksBoundLongUnbrokenTextAndKeepEveryCharacter() {
        val text = "甲".repeat(150000)
        val result = ChapterSplitter.split(text, options = SplitOptions(mode = SplitMode.FIXED_SIZE, fixedChunkChars = 2000, cleanWatermark = false))
        assertTrue(result.all { it.content.length <= 2000 })
        assertEquals(text, result.joinToString("") { it.content })
    }
    @Test fun fixedChunksKeepUnicodePairsAndNewlines() {
        val text = "a".repeat(1999) + "🐉" + "\n\n" + "z".repeat(8000)
        val result = ChapterSplitter.split(text, options = SplitOptions(mode = SplitMode.FIXED_SIZE, fixedChunkChars = 2000, cleanWatermark = false))
        assertTrue(result.all { it.content.length <= 2000 && !Character.isHighSurrogate(it.content.last()) })
        assertEquals(text, result.joinToString("") { it.content })
    }
    @Test fun fallbackDoesNotGlueEnglishWords() {
        val text = "hello\nworld\n\nalpha\nbeta\n\ngamma\ndelta\n\nepsilon\nzeta"
        assertEquals(text, ChapterSplitter.split(text).single().content)
    }
    @Test fun singleBookHeadingDoesNotHideRepeatedChapterLevel() {
        val parts = ChapterAssembler.assemble(listOf(ChapterAssembler.Para(0, "全书标题"),
            ChapterAssembler.Para(1, "第一章"), ChapterAssembler.Para(-1, "甲"),
            ChapterAssembler.Para(1, "第二章"), ChapterAssembler.Para(-1, "乙")))
        assertEquals(listOf("全书标题", "第一章", "第二章"), parts.map { it.title })
    }
    @Test fun markdownBookTitleAndChapterHeadingsRemainSeparate() {
        val doc = MarkdownParser().parse("# 全书标题\n\n## 第一章\n\n甲\n\n## 第二章\n\n乙".toByteArray(), "book.md")
        assertEquals(listOf("全书标题", "第一章", "第二章"), doc.parts.map { it.title })
    }
    @Test fun gluedTitleIsAcceptedButReferencePhraseIsNot() {
        val text = "第一章 开始\n${"甲。".repeat(80)}\n第二章对不起\n${"乙。".repeat(80)}\n第三章 结束\n丙"
        assertEquals(3, ChapterSplitter.split(text).size)
        assertFalse(ChapterSplitter.split(text.replace("第二章对不起", "第二章里提到的约定")).any { it.title == "第二章里提到的约定" })
    }
    @Test fun longFenceCannotBeClosedByShortFenceOrInfoText() {
        val body = "````text\n```\n# 代码里的假标题\n```` trailing\n# 仍在代码中\n````\n# 真标题\n正文"
        val doc = MarkdownParser().parse(body.toByteArray(), "code.md")
        assertTrue(doc.parts.none { it.title.contains("假标题") || it.title == "仍在代码中" })
        assertTrue(doc.parts.any { it.content.contains("# 代码里的假标题") })
        assertTrue(doc.parts.any { it.title == "真标题" })
    }
    @Test fun numbersWithTenThousandAreSortable() {
        assertEquals(10000, ChapterSplitter.chapterOrderFromTitle("第一万章 测试"))
        assertEquals(10010, ChapterSplitter.chapterOrderFromTitle("第一万零一十章 测试"))
        assertEquals(23456, ChapterSplitter.chapterOrderFromTitle("第二万三千四百五十六章 测试"))
    }
    @Test fun htmlVoidEmbedDoesNotConsumeFollowingBody() {
        assertEquals(listOf("开头", "后续正文"), HtmlTextExtractor.extract("<p>开头</p><embed src='a.pdf'><p>后续正文</p>").paragraphs.map { it.text })
    }
    @Test fun pdfAggregationRetainsNewParagraphFirstLinesAndPageBoundaries() {
        val lines = listOf(PdfParagraphs.Line("首段",12f,10f,true), PdfParagraphs.Line("次段首行",12f,20f,false),
            PdfParagraphs.Line("次段末行",12f,30f,true), PdfParagraphs.Line("末段首行",12f,40f,false))
        val out = PdfParagraphs.collect(listOf(lines, listOf(PdfParagraphs.Line("新页",12f,10f,false))), {false}, {20f})
        assertEquals(listOf("首段", "次段首行次段末行", "末段首行", "新页"), out.map { it.text })
    }
    @Test fun pdfLargeGapKeepsTheCurrentLineAndLatinWordBoundary() {
        val lines = listOf(PdfParagraphs.Line("hello",12f,1f,false),PdfParagraphs.Line("world",12f,2f,false),PdfParagraphs.Line("next",12f,50f,false))
        assertEquals(listOf("hello world","next"), PdfParagraphs.collect(listOf(lines),{false},{10f}).map { it.text })
    }
}
