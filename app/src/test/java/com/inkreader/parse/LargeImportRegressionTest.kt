package com.inkreader.parse

import com.inkreader.data.model.SplitOptions
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class LargeImportRegressionTest {
    @Test fun watermarkFastPathPreservesTitlesAndOrdinaryShortProse() {
        val keep = listOf("人物继续赶路，在天亮之前抵达城门。", "朋友说去 www.example.com 看看。",
            "第一章 求月票求收藏", "第一卷 求月票求收藏", "*** 这是普通装饰 ***", "手机阅读请访问的故事仍在继续")
        for (line in keep) assertEquals(line, ChapterSplitter.cleanBodyWatermarks(line))
        for (line in listOf("手机阅读请访问 https://example.com", "=== 小说网 www.example.com ===",
            "求月票 求收藏", "--- 最新章节 手机阅读 ---")) assertEquals("", ChapterSplitter.cleanBodyWatermarks(line))
        assertEquals("正文\n\n尾声", ChapterSplitter.cleanBodyWatermarks("正文\n\n求月票 求收藏\n\n尾声"))
    }

    @Test fun cachedDiskPlanMatchesUncachedPlanWithHardWrappedTextAndMixedHeadings() {
        val directory = Files.createTempDirectory("ink-short-lines").toFile()
        try {
            val text = buildString {
                for (n in 1..120) {
                    append("第${n}章 测试标题\n\n")
                    repeat(20) { append("这是一段普通正文，人物沿着道路继续向前走去。\n") }
                    append("\n第一章里提到的事情仍未结束。\n\n")
                }
            }
            val expected = ChapterSplitter.plan(text.trimEnd().split("\n"), text.trimEnd().length, SplitOptions())
            IndexedText.build(text.reader(), File(directory, "index.bin")).use { indexed ->
                val actual = ChapterSplitter.plan(indexed.lines, indexed.length, SplitOptions())
                assertEquals(expected, actual)
                assertEquals(120, actual.headings.size)
            }
        } finally { directory.deleteRecursively() }
    }
}
