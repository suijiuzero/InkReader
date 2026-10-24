package com.inkreader.parse

import com.inkreader.data.model.SplitMode
import com.inkreader.data.model.SplitOptions
import org.junit.Assert.*
import org.junit.Test

class HeadingFormsTest {
    private val body = "正文内容，保持原来的字形：ＡＢＣ１２３Ⅻ。".repeat(24)
    private fun split(titles: List<String>, mode: SplitMode = SplitMode.SMART) =
        ChapterSplitter.split(titles.joinToString("\n\n") { "$it\n\n$body" }, "正文", SplitOptions(mode = mode, cleanWatermark = false))

    @Test fun englishAbbreviationsAndNumberPrefixes() {
        val forms = listOf("Ch. 1: Dawn" to "Chap. 2: Return", "Chapter No. 1 Dawn" to "Chapter No. 2 Return",
            "Episode #1 Dawn" to "Ep. #2 Return", "ＣＨＡＰＴＥＲ １ Dawn" to "ＣＨＡＰＴＥＲ ２ Return")
        for ((first, second) in forms) {
            for (mode in listOf(SplitMode.SMART, SplitMode.STRICT_CHAP, SplitMode.NUMBERED)) {
                val parts = split(listOf(first, second), mode)
                assertEquals(first, listOf(first, second), parts.map { it.title })
                assertEquals(first, 1, ChapterSplitter.chapterOrderFromTitle(first))
                assertEquals(second, 2, ChapterSplitter.chapterOrderFromTitle(second))
                assertTrue(parts.all { it.content == body })
            }
        }
    }

    @Test fun unicodeRomanChapterNumbersKeepOriginalGlyphs() {
        val titles = listOf("Chapter ⅩⅠ: Dawn", "Chapter Ⅻ: Night", "Chapter ⅩⅢ: Home")
        assertEquals(titles, split(titles).map { it.title })
        assertEquals(listOf(11, 12, 13), titles.map { ChapterSplitter.chapterOrderFromTitle(it) })
    }

    @Test fun bareChapterNumbersHaveBoundariesAndOrdering() {
        for (titles in listOf(listOf("一章 初见", "二章 重逢"), listOf("01章：初见", "02章：重逢"),
            listOf("１話（上）", "２話（下）"))) {
            assertEquals(titles, split(titles).map { it.title })
            assertEquals(listOf(1, 2), titles.map { ChapterSplitter.chapterOrderFromTitle(it) })
        }
    }

    @Test fun wholeHeadingWrappersAndDecorations() {
        val forms = listOf(listOf("【第一章 初见】", "【第二章 重逢】"), listOf("[Chapter 1 Dawn]", "[Chapter 2 Home]"),
            listOf("★ 第一章 初见 ★", "◆ 第二章 重逢 ◆"), listOf("【第一章】初见", "【第二章】重逢"))
        for (titles in forms) {
            assertEquals(titles, split(titles).map { it.title })
            assertEquals(listOf(1, 2), titles.map { ChapterSplitter.chapterOrderFromTitle(it) })
        }
    }

    @Test fun labeledHeadingsAllowColonsAndFullWidthLatin() {
        val titles = listOf("正文：第一章 初见", "ＶＩＰ：第二章 重逢")
        assertEquals(titles, split(titles).map { it.title })
    }

    @Test fun volumeHeadersDoNotBecomeExtraChapters() {
        for ((volume1, volume2) in listOf("上卷 春" to "下卷 冬", "Book Ⅰ Spring" to "Book Ⅱ Winter")) {
            val raw = "$volume1\n第一章 初见\n\n$body\n\n第二章 重逢\n\n$body\n\n$volume2\n第三章 归来\n\n$body"
            val parts = ChapterSplitter.split(raw)
            assertEquals(listOf("第一章 初见", "第二章 重逢", "第三章 归来"), parts.map { it.title })
            assertEquals(listOf(volume1, volume1, volume2), parts.map { it.volume })
            assertTrue(parts.last().content.contains(volume2))
        }
    }

    @Test fun referencesAndMismatchedBracketsRemainBody() {
        for (line in listOf("Ch. 2 is referenced here", "Chap. 2 of the novel", "Chapter No. 2 is important",
            "三章小说还没读完", "2章里提到的事情", "【第十章）", "[第十章】")) {
            val raw = "第一章 初见\n\n$body\n\n$line\n\n$body\n\n第二章 重逢\n\n$body"
            val parts = ChapterSplitter.split(raw)
            assertEquals(line, listOf("第一章 初见", "第二章 重逢"), parts.map { it.title })
            assertTrue(line, parts.first().content.contains(line))
        }
    }

    @Test fun weakListsAndExoticPrefixesStillWork() {
        for (titles in listOf(listOf("（一）初见", "（二）重逢", "（三）归来"), listOf("◆ 01", "◆ 02", "◆ 03"),
            listOf("① 初见", "② 重逢", "③ 归来"))) {
            assertEquals(titles, split(titles).map { it.title })
        }
    }

    @Test fun tocUsesSameCanonicalHeadingAsTheBody() {
        val titles = listOf("Chapter 1: Dawn", "Chapter 2: Day", "Chapter 3: Night", "Chapter 4: Home")
        val toc = titles.map { it.replace('1', '１').replace('2', '２').replace('3', '３').replace('4', '４') }
        val raw = "目录\n\n" + toc.joinToString("\n\n") + "\n\n" + titles.joinToString("\n\n") { "$it\n\n$body" }
        val parts = ChapterSplitter.split(raw)
        assertEquals(titles, parts.map { it.title })
        assertTrue(parts.first().content.contains("目录"))
    }
}
