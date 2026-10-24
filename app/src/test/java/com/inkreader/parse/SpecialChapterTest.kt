package com.inkreader.parse

import com.inkreader.data.model.SplitMode
import com.inkreader.data.model.SplitOptions
import org.junit.Assert.*
import org.junit.Test

class SpecialChapterTest {
    private val body = "正文内容保留。".repeat(30)
    private fun split(titles: List<String>, mode: SplitMode = SplitMode.SMART, clean: Boolean = false) =
        ChapterSplitter.split(titles.joinToString("\n\n") { "$it\n\n$body" }, "正文", SplitOptions(mode = mode, cleanWatermark = clean))

    @Test fun finalChapterSegmentsInSmartStrictAndNumberedModes() {
        val titles = listOf("第一章 出发", "第二章 归来", "最终章（上）", "最终章（中）", "最终章（下）")
        for (mode in listOf(SplitMode.SMART, SplitMode.STRICT_CHAP, SplitMode.NUMBERED)) {
            assertEquals(mode.name, titles, split(titles, mode).map { it.title })
        }
    }

    @Test fun expandedSpecialFormsKeepTitlesAndBodies() {
        val endings = listOf("最终章(上)", "最終章（下）", "最终话：再见", "最終回 終わり", "终幕", "大结局（下）",
            "大結局", "完结篇", "結局篇", "后日谈", "後日談", "番外一", "番外篇二 重逢", "番外第十二章 归途",
            "番外３", "终章上篇", "【最终章（上）】", "【大结局】归来", "「最終章」", "Epilogue (Part One)")
        for (ending in endings) {
            val parts = split(listOf("第一章 出发", ending))
            assertEquals(ending, listOf("第一章 出发", ending), parts.map { it.title })
            assertTrue(ending, parts.last().content.contains(body))
            assertFalse(ending, ChapterSplitter.showsOrdinalLabel(ending))
            assertEquals(ending, ChapterSplitter.ORDER_BACK_MATTER, ChapterSplitter.chapterOrderFromTitle(ending))
        }
    }

    @Test fun frontAndInterludeForms() {
        val titles = listOf("自序", "题记（上）", "第一章 出发", "间章：雨夜", "幕間（下）", "第二章 归来", "后序")
        assertEquals(titles, split(titles).map { it.title })
        assertEquals(ChapterSplitter.ORDER_FRONT_MATTER, ChapterSplitter.chapterOrderFromTitle("【题记（上）】"))
    }

    @Test fun specialNumbersDoNotSortAsOrdinaryChapters() {
        for (title in listOf("番外2", "番外第十二章", "后日谈（1）", "最终章 2")) {
            assertEquals(title, ChapterSplitter.ORDER_BACK_MATTER, ChapterSplitter.chapterOrderFromTitle(title))
            assertEquals(title, ChapterSplitter.ORDER_BACK_MATTER, ChapterSplitter.chapterOrderFromName("$title.txt"))
        }
        assertEquals(12, ChapterSplitter.chapterOrderFromTitle("第十二章 最终章（上）"))
    }

    @Test fun proseAndUnbalancedBracketsAreNotHeadings() {
        for (line in listOf("最终章是最精彩的部分", "最终章（上）已经读完了", "大结局之前还有很长的路", "后日谈起了这件事",
            "番外篇里的那个人", "【最终章）", "最终章（上", "最终章 （上", "「最终章」已经看完了", "前言不搭后语", "序列一")) {
            val parts = ChapterSplitter.split("第一章 出发\n\n$body\n\n$line\n\n$body\n\n第二章 归来\n\n$body", "正文")
            assertEquals(line, listOf("第一章 出发", "第二章 归来"), parts.map { it.title })
            assertTrue(line, parts.first().content.contains(line))
        }
    }

    @Test fun cleaningKeepsSegmentsButRemovesPromotion() {
        val parts = split(listOf("第一章 出发", "最终章（上）（求月票）", "最终章（下）"), clean = true)
        assertEquals(listOf("第一章 出发", "最终章（上）", "最终章（下）"), parts.map { it.title })
    }

    @Test fun noStyledHeadingsUsesSameRulesAcrossDocumentAssembler() {
        val titles = listOf("序章", "第一章 出发", "最终章（上）", "最终章（下）", "番外一")
        val paragraphs = titles.flatMap { listOf(ChapterAssembler.Para(-1, it), ChapterAssembler.Para(-1, body)) }
        assertEquals(titles, ChapterAssembler.assemble(paragraphs).map { it.title })
    }
}
