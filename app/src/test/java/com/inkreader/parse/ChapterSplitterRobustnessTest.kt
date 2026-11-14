package com.inkreader.parse

import com.inkreader.data.model.SplitMode
import com.inkreader.data.model.SplitOptions
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 真实网文里踩过的两类坑：正文装饰性 `~~~` 被当成代码围栏、标题被写两遍。 */
class ChapterSplitterRobustnessTest {

    private val body = "　　他抬头看了看天色，把刀收回鞘里，转身朝城门走去。"

    @Test
    fun danglingTildeDecorationDoesNotSwallowRestOfBook() {
        // 网文常用 ~~~ 表示呻吟/情绪，若被当成 Markdown 围栏且没有闭合，
        // 后面的标题会被整段跳过（实测《诡秘之主》从第 111 章起全部丢失）。
        val sb = StringBuilder()
        for (i in 1..3) {
            sb.appendLine("第${i}章 标题$i")
            sb.appendLine(body)
            sb.appendLine(body)
        }
        sb.appendLine("“不要！~~~好舒服！~~~~不可以！~~~”")
        sb.appendLine(body)
        for (i in 4..8) {
            sb.appendLine("第${i}章 标题$i")
            sb.appendLine(body)
            sb.appendLine(body)
        }
        val parts = ChapterSplitter.split(sb.toString(), options = SplitOptions(mode = SplitMode.SMART))
        val numbers = parts.mapNotNull { ChapterSplitter.chapterOrderFromTitle(it.title) }
        assertEquals("装饰性 ~~~ 不能吞掉后面的章节", listOf(1, 2, 3, 4, 5, 6, 7, 8), numbers)
    }

    @Test
    fun gluedDuplicateTitleDoesNotCreateEmptyChapter() {
        // 常见排版：标题独立一行，紧接着又一次「第X章 标题」直接粘着正文。
        val sb = StringBuilder()
        for (i in 1..5) {
            sb.appendLine("第${i}章 标题$i")
            sb.appendLine()
            sb.appendLine("第${i}章 标题$i$body")
            sb.appendLine(body)
            sb.appendLine(body)
            sb.appendLine()
        }
        val parts = ChapterSplitter.split(sb.toString(), options = SplitOptions(mode = SplitMode.SMART))
        assertEquals("每章只应出现一次", 5, parts.size)
        assertTrue("不应产生空章", parts.all { it.content.isNotBlank() })
        val numbers = parts.mapNotNull { ChapterSplitter.chapterOrderFromTitle(it.title) }
        assertEquals(listOf(1, 2, 3, 4, 5), numbers)
    }

    @Test
    fun misnumberedDuplicateWithSameSubtitleCollapses() {
        // 源文件把同一章写了两遍、编号却打错：第82章 切磋 / 第80章 切磋
        val sb = StringBuilder()
        sb.appendLine("第81章 前情")
        sb.appendLine(body)
        sb.appendLine(body)
        sb.appendLine("第82章 切磋")
        sb.appendLine()
        sb.appendLine("第80章 切磋")
        sb.appendLine(body)
        sb.appendLine(body)
        sb.appendLine("第83章 后事")
        sb.appendLine(body)
        val parts = ChapterSplitter.split(sb.toString(), options = SplitOptions(mode = SplitMode.SMART))
        assertTrue("同样的章名不该切出空章", parts.all { it.content.isNotBlank() })
        assertTrue("章数不应多于实际章节", parts.size <= 4)
    }

    @Test fun oversizedChapterKeepsPrefaceAndIgnoresVolumeLookingCodeAndIgnoredBoundaries() {
        val prefix = "这是短前言。"
        val chapter = "第一章 归来"
        val ignoredTitle = "第二部 样本"
        val raw = listOf(prefix, chapter, body.repeat(2600), "```", "第一部 示例", "```", ignoredTitle, body.repeat(2600))
            .joinToString("\n\n")
        val options = SplitOptions(cleanWatermark = false, stripLeadingTitle = false, ignoredHeadingLines = setOf(13))
        val text = ChapterSplitter.normalize(raw)
        val parts = ChapterSplitter.split(text, options = options)
        val joined = parts.joinToString("") { it.content }
        assertTrue(parts.size > 1)
        assertEquals(setOf(chapter), parts.map { it.title.substringBefore("（") }.toSet())
        assertTrue(joined.startsWith(prefix))
        assertTrue(joined.contains("第一部 示例"))
        assertTrue(joined.contains(ignoredTitle))
        val directory = Files.createTempDirectory("ink-oversized-chapter").toFile()
        try {
            IndexedTxtStore(directory).withDocument("oversized.txt", options, { text.byteInputStream() }) { doc ->
                assertEquals(parts, doc.sections.map { ChapterSplitter.Part(it.title, doc.body(it), it.volume) })
            }
        } finally { directory.deleteRecursively() }
    }
}
