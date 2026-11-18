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

    @Test fun englishContentsLabelsHaveConsistentCaseHandlingInBothPaths() {
        val entries = listOf("Chapter 1 Arrival", "Chapter 2 Crossing", "Chapter 3 Return", "Chapter 4 Departure")
        val options = SplitOptions(cleanWatermark = false, stripLeadingTitle = false)
        val directory = Files.createTempDirectory("ink-toc-labels").toFile()
        try {
            for (label in listOf("Contents", "contents", "CoNtEnTs", "TABLE OF CONTENTS", "Table of Contents")) {
                val raw = "$label\n\n" + entries.joinToString("\n")
                val parts = ChapterSplitter.split(raw, options = options)
                assertEquals(label, listOf(raw), parts.map { it.content })
                assertEquals(label, 4, ChapterSplitter.analyze(raw, options = options).skippedTocEntries)
                IndexedTxtStore(directory).withDocument("contents.txt", options, { raw.byteInputStream() }) { doc ->
                    assertEquals(label, listOf(raw), doc.sections.map(doc::body))
                }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun englishContentsLabelDoesNotSuppressRealBodyOrConfirmedHeadings() {
        val entries = listOf("Chapter 1 Arrival", "Chapter 2 Crossing", "Chapter 3 Return", "Chapter 4 Departure")
        val paragraph = "They walked through the gate and watched the river below. ".repeat(15).trim()
        val options = SplitOptions(cleanWatermark = false, stripLeadingTitle = false)
        val directory = Files.createTempDirectory("ink-toc-body").toFile()
        try {
            for (label in listOf("contents", "Table of Contents")) {
                val raw = "$label\n\n" + entries.joinToString("\n") + "\n\n" +
                    entries.joinToString("\n\n") { "$it\n\n$paragraph" }
                val parts = ChapterSplitter.split(raw, options = options)
                assertEquals(label, entries, parts.map { it.title })
                assertTrue(parts.all { it.content.contains(paragraph) })
                IndexedTxtStore(directory).withDocument("story.txt", options, { raw.byteInputStream() }) { doc ->
                    assertEquals(label, parts, doc.sections.map { ChapterSplitter.Part(it.title, doc.body(it), it.volume) })
                }
                val onlyContents = "$label\n\n" + entries.joinToString("\n")
                val chosen = options.copy(forcedHeadingLines = setOf(3, 4, 5, 6))
                assertEquals(label, entries, ChapterSplitter.split(onlyContents, options = chosen).map { it.title })
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun aLaterContentsListDoesNotHideChaptersThatAlreadyHaveBodyText() {
        val options = SplitOptions(cleanWatermark = false, stripLeadingTitle = false)
        val directory = Files.createTempDirectory("ink-late-contents").toFile()
        try {
            val fixtures = listOf(
                Triple("Contents", "Chapter 1 Arrival", "Chapter 2 Crossing") to
                    listOf("Chapter 3 Archive", "Chapter 4 Garden", "Chapter 5 Mill", "Chapter 6 Station"),
                Triple("目录", "第一章 抵达", "第二章 渡河") to
                    listOf("第三章 档案", "第四章 花园", "第五章 磨坊", "第六章 车站")
            )
            for ((headings, entries) in fixtures) {
                val (label, first, second) = headings
                val body = if (label == "Contents") {
                    "The group followed the river well past the old bridge. ".repeat(5).trim()
                } else {
                    List(8) { "他们沿河而行，在天亮前到达了石桥。" }.joinToString("\n")
                }
                val raw = "$first\n\n$body\n\n$second\n\n$body\n\n$label\n\n" + entries.joinToString("\n")
                val parts = ChapterSplitter.split(raw, options = options)
                assertEquals(listOf(first, second), parts.map { it.title })
                assertEquals(body, parts.first().content)
                assertEquals("$body\n\n$label\n\n" + entries.joinToString("\n"), parts.last().content)
                assertEquals(4, ChapterSplitter.analyze(raw, options = options).skippedTocEntries)
                IndexedTxtStore(directory).withDocument("later-contents.txt", options, { raw.byteInputStream() }) { doc ->
                    assertEquals(parts, doc.sections.map { ChapterSplitter.Part(it.title, doc.body(it), it.volume) })
                }
            }
        } finally { directory.deleteRecursively() }
    }
}
