package com.inkreader.parse

import com.inkreader.data.model.SplitOptions
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DenseTocTest {
    @Test fun denseTocIsRemovedBeforeAdjacentTitleSuppression() {
        val titles = listOf("第一章 初见", "第二章 重逢", "第三章 归来", "第四章 结束")
        val body = "这一段是真实正文，不能因目录去重丢失。".repeat(30)
        for (prefix in listOf("", "目录\n", "测试书名\n\n目录\n")) {
            val raw = prefix + titles.joinToString("\n") + "\n\n" +
                titles.joinToString("\n\n") { "$it\n\n$body" }
            val parts = ChapterSplitter.split(raw)
            assertEquals(prefix, titles, parts.map { it.title })
            assertTrue(parts.all { it.content.contains(body) })
        }
    }

    @Test fun fullWidthTocMatchesHalfWidthBodyWithoutChangingTitles() {
        val titles = (1..4).map { "第${it}章 正文" }
        val body = "保留全部正文和数字42。".repeat(30)
        val toc = (1..4).map { "第${'０' + it}章 正文" }
        val parts = ChapterSplitter.split("目录\n" + toc.joinToString("\n") + "\n\n" +
            titles.joinToString("\n\n") { "$it\n\n$body" })
        assertEquals(titles, parts.map { it.title })
        assertTrue(parts.all { it.content.contains(body) })
    }

    @Test fun standaloneContentsWithVolumeBannersKeepsAllEntriesAsText() {
        val raw = buildString {
            appendLine("目录")
            for (volume in 1..2) {
                appendLine("第${volume}卷 远方")
                for (number in 1..100) appendLine("第${number}章 标题 ……… ${number * 3}")
            }
        }.trimEnd()
        val options = SplitOptions(cleanWatermark = false, stripLeadingTitle = false)
        val parts = ChapterSplitter.split(raw, options = options)
        assertEquals(listOf(raw), parts.map { it.content })
        assertEquals(200, ChapterSplitter.analyze(raw, options = options).skippedTocEntries)
        val directory = Files.createTempDirectory("ink-contents-only").toFile()
        try {
            IndexedTxtStore(directory).withDocument("contents.txt", options, { raw.byteInputStream() }) { doc ->
                assertEquals(listOf(raw), doc.sections.map(doc::body))
            }
        } finally { directory.deleteRecursively() }
    }
}
