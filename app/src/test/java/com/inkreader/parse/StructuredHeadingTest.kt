package com.inkreader.parse

import com.inkreader.data.model.SplitMode
import com.inkreader.data.model.SplitOptions
import org.junit.Assert.*
import org.junit.Test

class StructuredHeadingTest {
    private fun p(level: Int, text: String) = ChapterAssembler.Para(level, text)
    private fun markdown(src: String) = MarkdownParser().parse(src.toByteArray(), "test.md").parts

    @Test fun volumeParentsSelectRepeatedChapterLevelAndMoveVolumeIntroductions() {
        val parts = ChapterAssembler.assemble(listOf(p(0,"第一卷 春"), p(-1,"春卷引言"), p(1,"第一章 初见"), p(-1,"正文甲"),
            p(1,"第二章 重逢"), p(-1,"正文乙"), p(0,"第二卷 冬"), p(-1,"冬卷引言"), p(1,"第三章 归来"), p(-1,"正文丙")))
        assertEquals(listOf("第一章 初见", "第二章 重逢", "第三章 归来"), parts.map { it.title })
        assertEquals(listOf("第一卷 春", "第一卷 春", "第二卷 冬"), parts.map { it.volume })
        assertTrue(parts.first().content.contains("春卷引言"))
        assertFalse(parts[1].content.contains("冬卷引言"))
        assertTrue(parts.last().content.startsWith("第二卷 冬\n\n冬卷引言"))
        for (text in listOf("正文甲", "正文乙", "正文丙", "春卷引言", "冬卷引言")) {
            assertEquals(text, 1, parts.count { text in it.content })
        }
    }

    @Test fun sameLevelVolumeLabelsDoNotCreateExtraChapters() {
        val parts = markdown("# Book I\n\nFirst intro\n\n# Chapter 1 Dawn\n\nA\n\n# Book II\n\nSecond intro\n\n# Chapter 2 Home\n\nB")
        assertEquals(listOf("Chapter 1 Dawn", "Chapter 2 Home"), parts.map { it.title })
        assertEquals(listOf("Book I", "Book II"), parts.map { it.volume })
        assertTrue("Second intro" in parts.last().content)
        assertFalse("Second intro" in parts.first().content)
    }

    @Test fun markdownBookVolumeAndChaptersPreserveMarkupAndBookends() {
        val parts = markdown("# 全书\n\n## 第一卷 春\n\n卷首 **说明**\n\n### 第一章 初见\n\n甲 **强调**\n\n#### 场景一\n\n细节\n\n### 第二章 重逢\n\n乙\n\n## 最终章（上）\n\n结局")
        assertEquals(listOf("全书", "第一章 初见", "第二章 重逢", "最终章（上）"), parts.map { it.title })
        assertTrue(parts[1].content.contains("卷首 **说明**"))
        assertTrue(parts[1].content.contains("甲 **强调**"))
        assertTrue(parts[1].content.contains("#### 场景一"))
        assertEquals("第一卷 春", parts.last().volume)
    }

    @Test fun unrelatedHeadingParentsRetainTheirHierarchy() {
        val parts = markdown("# 研究一\n\n## 第一章 引用标题\n\n甲\n\n# 研究二\n\n## 第二章 引用标题\n\n乙")
        assertEquals(listOf("研究一", "研究二"), parts.map { it.title })
        assertTrue(parts.first().content.contains("## 第一章 引用标题"))
    }

    @Test fun ordinarySubheadingsDoNotReplaceNumberedParents() {
        val parts = markdown("# 第一章 初见\n\n甲\n\n## 第十章 示例\n\n细节\n\n## 第十一章 示例\n\n细节\n\n# 第二章 重逢\n\n乙")
        assertEquals(listOf("第一章 初见", "第二章 重逢"), parts.map { it.title })
    }

    @Test fun singleDeclaredChapterIsNotDemotedByMoreNumerousSubheadings() {
        val parts = markdown("# 第一章 初见\n\n甲\n\n## 第十章 示例\n\n乙\n\n## 第十一章 示例\n\n丙")
        assertEquals(listOf("第一章 初见"), parts.map { it.title })
        assertTrue(parts.single().content.contains("## 第十章 示例"))
        assertTrue(parts.single().content.contains("## 第十一章 示例"))
    }

    @Test fun htmlStyledVolumesUseTheSharedPlan() {
        val html = "<h1>第一卷 春</h1><p>卷首说明</p><h2>第一章 初见</h2><p>正文甲</p>" +
            "<h2>第二章 重逢</h2><p>正文乙</p><h1>第二卷 冬</h1><p>冬卷说明</p><h2>第三章 归来</h2><p>正文丙</p>"
        val parts = HtmlDocumentParser().parse(html.toByteArray(), "test.html").parts
        assertEquals(listOf("第一章 初见", "第二章 重逢", "第三章 归来"), parts.map { it.title })
        assertEquals(listOf("第一卷 春", "第一卷 春", "第二卷 冬"), parts.map { it.volume })
        assertTrue(parts.last().content.contains("冬卷说明"))
        assertTrue(parts.last().content.contains("正文丙"))
    }

    @Test fun indentedAndFencedCodeCannotSupplyHeadings() {
        val parts = markdown("# 第一章\n\n    # 代码示例\n    ## 代码里的第二章\n\n```md\n# 假标题\n    ```\n# 仍在代码中\n```\n\n# 第二章\n\n正文")
        assertEquals(listOf("第一章", "第二章"), parts.map { it.title })
        assertTrue(parts.first().content.contains("    # 代码示例"))
        assertTrue(parts.first().content.contains("# 仍在代码中"))
    }

    @Test fun setextVolumeAndChapterHeadersUseTheSamePlan() {
        val parts = markdown("第一卷\n===\n\n第一章 初见\n---\n\n甲\n\n第二章 重逢\n---\n\n乙\n\n第二卷\n===\n\n第三章 归来\n---\n\n丙")
        assertEquals(listOf("第一章 初见", "第二章 重逢", "第三章 归来"), parts.map { it.title })
        assertEquals("第二卷", parts.last().volume)
        assertTrue(parts.last().content.contains("第二卷\n==="))
    }

    @Test fun explicitCustomRulesMatchOriginalWidthAndDecorations() {
        for ((pattern, titles) in listOf("★" to listOf("★ 第一章 初见", "★ 第二章 重逢"),
            "^第[０-９]+章.*$" to listOf("第１章 初见", "第２章 重逢"))) {
            val parts = ChapterSplitter.split(titles.joinToString("\n\n") { "$it\n\n正文" }, "正文",
                SplitOptions(mode = SplitMode.CUSTOM, customPattern = pattern))
            assertEquals(titles, parts.map { it.title })
        }
    }

    @Test fun structuredLongChaptersRemainBoundedWithoutReinterpretingTheirContents() {
        val body = "甲".repeat(65000) + "\n\n第二章 正文中的引用\n\n" + "乙".repeat(65000)
        val parts = ChapterAssembler.assemble(listOf(p(0,"第一章"), p(-1,body)))
        assertTrue(parts.all { it.content.length <= 50000 })
        assertEquals(body, parts.joinToString("") { it.content })
    }
}
