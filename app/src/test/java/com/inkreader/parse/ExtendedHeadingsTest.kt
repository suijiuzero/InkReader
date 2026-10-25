package com.inkreader.parse

import org.junit.Assert.*
import org.junit.Test

class ExtendedHeadingsTest {
    private val body = "原文与所有段落都必须完整保留。".repeat(25)
    private fun split(titles: List<String>) = ChapterSplitter.analyze(titles.joinToString("\n\n") { "$it\n\n$body" })

    @Test fun nativeLabelsAndUnicodeDecimalDigitsPreserveSource() {
        for (titles in listOf(
            listOf("Capítulo 1 Amanecer", "Capítulo 2 Regreso", "Capítulo 3 Hogar"),
            listOf("Глава 1 Утро", "Глава 2 Вечер", "Глава 3 Дом"),
            listOf("제1장 시작", "제2장 재회", "제3장 귀환"),
            listOf("الفصل ١ البداية", "الفصل ٢ العودة", "الفصل ٣ المنزل"),
            listOf("अध्याय १ आरंभ", "अध्याय २ वापसी", "अध्याय ३ घर"),
            listOf("บทที่ ๑ เริ่มต้น", "บทที่ ๒ กลับมา", "บทที่ ๓ บ้าน"),
            listOf("Chương 1 Khởi đầu", "Chương 2 Trở lại", "Chương 3 Quê nhà"))) {
            assertEquals(titles, split(titles).parts.map { it.title })
        }
        assertEquals(123, ChapterSplitter.chapterOrderFromTitle("Chapter ١٢٣ Dawn"))
    }

    @Test fun bareNumbersAndMidBookStyleChangesRequireSeries() {
        for (titles in listOf(listOf("1", "2", "3"), listOf("一", "二", "三"), listOf("壹", "贰", "叁"),
            listOf("第一章 起点", "第二章 途中", "3. 山", "4. 海", "5. 家")))
            assertEquals(titles, split(titles).parts.map { it.title })
        val prose = "$body\n\n1\n\n$body\n\n2\n\n$body\n\n3\n\n$body"
        assertTrue(ChapterSplitter.analyze(prose).decisions.isEmpty())
    }

    @Test fun rangeChaptersDoNotCreateInternalMissingNumbers() {
        val result = split(listOf("第 1-2 章 合章", "第 3-4 章 合章", "第 5-6 章 合章"))
        assertEquals(3, result.parts.size)
        assertTrue(result.gaps.isEmpty())
        assertEquals(listOf(5), split(listOf("第1-4章 开篇", "第6章 归来")).gaps.single().missing)
        val wrapped = split(listOf("〔第1–2章〕初见", "〔第3–4章〕归来", "〔第5–6章〕远行"))
        assertEquals(3, wrapped.parts.size)
        assertTrue(wrapped.gaps.isEmpty())
    }

    @Test fun numberedEnglishReferenceAfterPeriodRemainsBody() {
        val source = "第十二章 开始\n\n$body\n\nChapter XIII. is an example quoted from another book\n\n$body\n\n第十四章 结束\n\n$body"
        assertEquals(listOf("第十二章 开始", "第十四章 结束"), ChapterSplitter.split(source).map { it.title })
    }
}
