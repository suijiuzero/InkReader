package com.inkreader.parse

import com.inkreader.data.model.SplitMode
import com.inkreader.data.model.SplitOptions
import org.junit.Assert.*
import org.junit.Test

class ChapterGapRecoveryTest {
    private val body = "完整测试正文，保持内容和字符次序。".repeat(12)
    private fun source(vararg titles: String) = titles.joinToString("\n\n") { "$it\n\n$body" }
    private fun split(text: String, mode: SplitMode = SplitMode.SMART) =
        ChapterSplitter.split(text, options = SplitOptions(mode = mode, cleanWatermark = false))

    @Test fun recoversSpacedChapterOnlyInsideConfirmedGapAndPreservesOriginalText() {
        val titles = listOf("第十二章 离开", "第 十 三 章 重逢", "第十四章 归来")
        val parts = split(source(*titles.toTypedArray()))
        assertEquals(titles, parts.map { it.title })
        assertTrue(parts.all { it.content == body })
        assertEquals(13, ChapterSplitter.chapterOrderFromTitle(parts[1].title))
    }

    @Test fun normalizesArabicChineseFullWidthAndFinancialNumbers() {
        for (middle in listOf("第 １ ３ 章 重逢", "第 壹 拾 叁 章 重逢", "第 壹 拾 參 章 重逢")) {
            assertEquals(listOf("第１２章 离开", middle, "第十四章 归来"),
                split(source("第１２章 离开", middle, "第十四章 归来")).map { it.title })
            assertEquals(13, ChapterSplitter.chapterOrderFromTitle(middle))
        }
        assertEquals(126, ChapterSplitter.chapterOrderFromTitle("第壹佰貳拾陸章"))
        assertEquals(10003, ChapterSplitter.chapterOrderFromTitle("第壹萬零參章"))
    }

    @Test fun recoversLongHeadingWithoutBroadeningGlobalLengthLimit() {
        val middle = "第十三章 " + "这是很长的标题".repeat(7)
        assertTrue(middle.length in 45..80)
        assertEquals(listOf("第十二章 开始", middle, "第十四章 结束"),
            split(source("第十二章 开始", middle, "第十四章 结束")).map { it.title })
        val outside = split(source("第一章 开始", middle, "第二章 结束"))
        assertEquals(2, outside.size)
        assertTrue(outside[0].content.contains(middle))
    }

    @Test fun supportsWrappedDecoratedAndMarkdownStyleTxtHeadings() {
        for (middle in listOf("【第 十 三 章 重逢】", "★ 第 十 三 章 重逢 ★", "### 第十三章 重逢", "【第 十 三 章】重逢")) {
            assertEquals(middle, split(source("第十二章 开始", middle, "第十四章 结束"))[1].title)
        }
    }

    @Test fun usesChapterKindAndEnglishRomanNumbers() {
        for (mark in listOf("回", "话", "話")) {
            assertEquals(3, split(source("第十二$mark 开始", "第 十 三 $mark 重逢", "第十四$mark 结束")).size)
        }
        val longTitle = "Chapter XIII: " + "Unexpected return ".repeat(3).trimEnd()
        assertEquals(3, split(source("Chapter XII: Start", longTitle, "Chapter XIV: End")).size)
        val wrongKind = split(source("第十二章 开始", "第 十 三 回 重逢", "第十四章 结束"))
        assertEquals(2, wrongKind.size)
    }

    @Test fun neverSearchesBeforeOrAfterTheGap() {
        val outside = "第 十 三 章 不在缺口内"
        val parts = split(source("第十二章 开始", "第十四章 结束") + "\n\n$outside\n\n$body")
        assertEquals(2, parts.size)
        assertTrue(parts.last().content.contains(outside))
    }

    @Test fun missingSourceChapterDoesNotCreatePlaceholder() {
        val parts = split(source("第十二章 开始", "第十四章 结束"))
        assertEquals(listOf("第十二章 开始", "第十四章 结束"), parts.map { it.title })
        assertTrue(parts.all { it.content == body })
    }

    @Test fun partialRecoveryDoesNotInventOtherMissingChapters() {
        assertEquals(listOf("第十二章 开始", "第 十 四 章 归来", "第十五章 结束"),
            split(source("第十二章 开始", "第 十 四 章 归来", "第十五章 结束")).map { it.title })
    }

    @Test fun doesNotCrossExplicitVolumesOrNumberResets() {
        val middle = "第 十 三 章 重逢"
        for (volume in listOf("第二卷 风起", "下卷", "Book II Winter")) {
            val text = source("第十二章 开始") + "\n\n$volume\n\n$middle\n\n$body\n\n" + source("第十四章 结束")
            assertEquals(volume, 2, split(text).size)
        }
        assertEquals(2, split(source("第二十章 卷尾", middle, "第一章 新卷")).size)
    }

    @Test fun specialsNeverSupplyNumbersOrAllowSearchingTheirContent() {
        for (special in listOf("番外十三", "最终章（上）", "序章")) {
            val parts = split(source("第十二章 开始", special, "第 十 三 章 特殊章内的引用", "第十四章 结束"))
            assertEquals(listOf("第十二章 开始", special, "第十四章 结束"), parts.map { it.title })
        }
    }

    @Test fun equalNumberSegmentsDoNotFormGap() {
        val middle = "第 十 三 章 引用"
        assertEquals(2, split(source("第十二章（上）", middle, "第十二章（下）")).size)
    }

    @Test fun recoversUnambiguousUpperLowerSegmentsAsOneNumber() {
        val titles = arrayOf("第十二章 开始", "第 十 三 章 重逢（上）", "第 十 三 章 重逢（下）", "第十四章 结束")
        assertEquals(titles.toList(), split(source(*titles)).map { it.title })
    }

    @Test fun ambiguousDuplicateNumbersRemainBody() {
        for ((first, second) in listOf("第 十 三 章 一个标题" to "第 十 三 章 另一个标题",
            "第 十 三 章 重逢（下）" to "第 十 三 章 重逢（上）")) {
            val parts = split(source("第十二章 开始", first, second, "第十四章 结束"))
            assertEquals(2, parts.size)
            assertTrue(parts.first().content.contains(first) && parts.first().content.contains(second))
        }
    }

    @Test fun reversedCandidatesAreNotReorderedOrSplit() {
        assertEquals(2, split(source("第十二章 开始", "第 十 四 章 归来", "第 十 三 章 重逢", "第十五章 结束")).size)
    }

    @Test fun outOfGapNumbersCannotHideReversedOrResetCandidates() {
        for (middle in listOf(
            arrayOf("第 十 四 章 归来", "第 十 三 章 倒序"),
            arrayOf("第 十 三 章 重逢", "第 十 二 章 重置"),
            arrayOf("第 十 五 章 归来", "第 十 三 章 倒序")
        )) {
            val parts = split(source("第十二章 开始", *middle, "第十四章 结束"))
            assertEquals(middle.toList().toString(), 2, parts.size)
            assertTrue(middle.all { parts.first().content.contains(it) })
        }
    }

    @Test fun quotedReferencesSentencesAndWrongLevelsRemainBody() {
        for (middle in listOf("第 十 三 章 的内容", "第 十 三 章 里提到的事", "第 十 三 章 他来了，故事开始了。",
            "第 十 三 章：的内容", "Chapter XIII. is referenced here as a quotation in the body of the novel",
            "第 十 三 章 \"标题\"", "「第 十 三 章 引用」", "【第 十 三 章）", "第 十 三 节 小节")) {
            val parts = split(source("第十二章 开始", middle, "第十四章 结束"))
            assertEquals(middle, 2, parts.size)
            assertTrue(middle, parts.first().content.contains(middle))
        }
    }

    @Test fun denseInlineAndTocLikeCandidatesAreNotRecovered() {
        val inline = "第 十 三 章 重逢"
        assertEquals(2, split("第十二章 开始\n\n$body\n$inline\n$body\n\n第十四章 结束\n\n$body").size)
        assertEquals(2, split("第十二章 开始\n\n$inline\n\n第十四章 结束\n\n$body").size)
    }

    @Test fun fencedCodeIsNotUsedToFillGap() {
        for (fence in listOf("```", "~~~~")) {
            val text = "第十二章 开始\n\n$body\n\n$fence\n\n第 十 三 章 代码示例\n\n$body\n$fence\n\n第十四章 结束\n\n$body"
            assertEquals(2, split(text).size)
        }
    }

    @Test fun automaticModesRecoverButExplicitCustomPerFileAndFixedModesDoNot() {
        val text = source("第12章 开始", "第 1 3 章 重逢", "第14章 结束")
        for (mode in listOf(SplitMode.SMART, SplitMode.IN_FILE, SplitMode.STRICT_CHAP, SplitMode.NUMBERED))
            assertEquals(mode.name, 3, split(text, mode).size)
        val custom = ChapterSplitter.split(text, options = SplitOptions(mode = SplitMode.CUSTOM, customPattern = "^第(?:12|14)章.*$", cleanWatermark = false))
        assertEquals(2, custom.size)
        assertEquals(1, split(text, SplitMode.PER_FILE).size)
        assertEquals(1, split(text, SplitMode.FIXED_SIZE).size)
    }

    @Test fun largeNumberGapsAreLeftAlone() {
        assertEquals(2, split(source("第十二章 开始", "第 十 三 章 重逢", "第两千章 结束")).size)
        // 单独检查补查预算，避免超长正文的既有按字数兜底影响总章节数断言。
        val large = listOf("第十二章 开始", body, " ".repeat(2_000_001), "", "第 十 三 章 重逢", "", body, "第十四章 结束")
        val many = listOf("第十二章 开始", body) + List(50_001) { "" } + listOf("第 十 三 章 重逢", "", body, "第十四章 结束")
        for (lines in listOf(large, many)) {
            val chosen = listOf(0, lines.lastIndex)
            assertEquals(chosen, ChapterSplitter.recoverNumberGaps(lines, chosen))
        }
    }
}
