package com.inkreader.parse

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
}
