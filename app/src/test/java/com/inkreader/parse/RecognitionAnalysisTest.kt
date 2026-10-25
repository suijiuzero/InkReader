package com.inkreader.parse

import com.inkreader.data.model.SplitMode
import com.inkreader.data.model.SplitOptions
import org.junit.Assert.*
import org.junit.Test

class RecognitionAnalysisTest {
    private val body = "这是完整的正文，章节处理应当保留全部内容。".repeat(22)
    private fun section(title: String) = "$title\n\n$body"
    private fun analyze(text: String, options: SplitOptions = SplitOptions(cleanWatermark = false)) =
        ChapterSplitter.analyze(text, options = options)

    @Test fun volumeCanSwitchFromChaptersToNumberedEssays() {
        val text = "第一卷 春\n\n" + listOf("第一章 出发", "第二章 归来").joinToString("\n\n", transform = ::section) +
            "\n\n第二卷 夏\n\n" + listOf("一、山", "二、水", "三、风").joinToString("\n\n", transform = ::section)
        val result = analyze(text)
        assertEquals(listOf("第一章 出发", "第二章 归来", "一、山", "二、水", "三、风"), result.parts.map { it.title })
        assertEquals(listOf("第一卷 春", "第一卷 春", "第二卷 夏", "第二卷 夏", "第二卷 夏"), result.parts.map { it.volume })
        assertTrue(result.decisions.any { HeadingReason.LOCAL_STYLE in it.reasons })
    }

    @Test fun subsectionsWithinAChapterAreStillNotChapters() {
        val text = "第一卷\n\n" + section("第一章") + "\n\n" + section("第一节") + "\n\n" + section("第二节") +
            "\n\n" + section("第二章")
        assertEquals(listOf("第一章", "第二章"), analyze(text).parts.map { it.title })
    }

    @Test fun tocMatchesNumberIdentityDespitePunctuationAndPageLeaders() {
        val toc = "目录\n\n第一章：春 …… 1\n第二章：夏 …… 12\n第三章：秋 …… 25\n第四章：冬 …… 39"
        val titles = listOf("第一章 春", "第二章 夏", "第三章 秋", "第四章 冬")
        val result = analyze(toc + "\n\n" + titles.joinToString("\n\n", transform = ::section))
        assertEquals(titles, result.parts.map { it.title })
        assertEquals(4, result.skippedTocEntries)
        assertEquals(4, result.parts.sumOf { part -> Regex(Regex.escape(body)).findAll(part.content).count() })
    }

    @Test fun denseShortChaptersWithoutRepeatedContentsRemainChapters() {
        val titles = listOf("第一章 春", "第二章 夏", "第三章 秋", "第四章 冬")
        assertEquals(titles, analyze(titles.joinToString("\n\n") { "$it\n\n短篇。" }).parts.map { it.title })
    }

    @Test fun ignoringABoundaryKeepsItsHeadingAndEveryParagraph() {
        val titles = listOf("第十二章", "第 十 三 章 重逢", "第十四章")
        val text = titles.joinToString("\n\n", transform = ::section)
        val result = analyze(text)
        val middle = result.decisions.single { it.title == titles[1] }
        assertTrue(HeadingReason.NUMBER_RECOVERY in middle.reasons)
        val changed = analyze(text, SplitOptions(cleanWatermark = false, ignoredHeadingLines = setOf(middle.line)))
        assertEquals(listOf(titles[0], titles[2]), changed.parts.map { it.title })
        assertTrue(changed.parts.first().content.contains(titles[1]))
        assertEquals(3, changed.parts.sumOf { Regex(Regex.escape(body)).findAll(it.content).count() })
        assertTrue(changed.decisions.any { !it.accepted && HeadingReason.USER_IGNORED in it.reasons })
    }

    @Test fun missingNumbersAreDiagnosticsNotInventedChapters() {
        val result = analyze(section("第十二章") + "\n\n" + section("第十四章"))
        assertEquals(2, result.parts.size)
        assertEquals(listOf(13), result.gaps.single().missing)
        val across = analyze(section("第十二章") + "\n\n第二卷\n\n" + section("第十四章"))
        assertTrue(across.gaps.isEmpty())
    }

    @Test fun primaryRulesCannotSplitCodeFences() {
        val text = section("第一章") + "\n\n````text\n第三章 假标题\n```\n第四章 仍在代码\n````\n\n" + section("第二章")
        val result = analyze(text)
        assertEquals(listOf("第一章", "第二章"), result.parts.map { it.title })
        assertTrue(result.parts.first().content.contains("第三章 假标题"))
    }

    @Test fun explicitModesDoNotInheritAutomaticRegionalRules() {
        val result = analyze(section("第一章") + "\n\n第二卷\n\n" + section("一、山") + "\n\n" + section("二、水"),
            SplitOptions(mode = SplitMode.STRICT_CHAP, cleanWatermark = false))
        assertEquals(listOf("第一章"), result.parts.map { it.title })
    }

    @Test fun ignoringFirstBoundaryMergesLeadingBodyInsteadOfMakingANewPreface() {
        val text = section("第一章") + "\n\n" + section("第二章")
        val result = analyze(text, SplitOptions(cleanWatermark = false, ignoredHeadingLines = setOf(1)))
        assertEquals(listOf("第二章"), result.parts.map { it.title })
        assertTrue(result.parts.single().content.startsWith("第一章"))
        assertEquals(2, Regex(Regex.escape(body)).findAll(result.parts.single().content).count())
    }

    @Test fun chapterBodyCannotBeMistakenForBookMetadata() {
        val parsed = TxtParser.parse("第一章\n\n没有标点的短正文".toByteArray(), "book.txt")
        assertNull(parsed.title)
        assertEquals("没有标点的短正文", parsed.parts.single().content)
    }

    @Test fun fastProseRejectionRetainsSpecialAndDecoratedVolumeHeadings() {
        for (special in listOf("自序", "序説", "まえがき", "プロローグ", "Interlude", "あとがき")) {
            val text = section(special) + "\n\n" + section("第一章") + "\n\n" + section("第二章")
            assertEquals(special, listOf(special, "第一章", "第二章"), analyze(text).parts.map { it.title })
        }
        assertEquals("★ 第一卷 春 ★", ChapterSplitter.extractVolumeTitle("★ 第一卷 春 ★"))
    }
}
