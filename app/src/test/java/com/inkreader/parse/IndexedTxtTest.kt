package com.inkreader.parse

import com.inkreader.data.model.SplitOptions
import com.inkreader.data.model.SplitMode
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.nio.file.Files

class IndexedTxtTest {
    @Test fun incrementalNormalizerMatchesStringNormalizerAndPreservesPairs() {
        val dir = Files.createTempDirectory("ink-index-test").toFile()
        try {
            val samples = listOf("", "  \r\n\t", "  开始 \r\n\r\n\r\n  后续  \r\n", "a\u200B\uFEFFb\u00A0c\r尾声",
                " \t".repeat(20000) + "🐉\n\n" + "x".repeat(100000) + "  ", "a\n\n\n\n  b\n")
            for ((i, source) in samples.withIndex()) IndexedText.build(StringReader(source), File(dir, "$i.bin")).use {
                assertEquals(ChapterSplitter.normalize(source), it.read(0, it.length))
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun diskBackedPlanAndBodiesMatchAllAuthoredGoldCases() {
        val root = File("tests/fixtures/recognition-gold").takeIf { it.isDirectory } ?: File("../tests/fixtures/recognition-gold")
        val dir = Files.createTempDirectory("ink-plan-test").toFile()
        try {
            for (line in File(root, "manifest.tsv").readLines().drop(1)) {
                val id = line.substringBefore('\t'); val bytes = File(root, "$id.txt").readBytes()
                val raw = CharsetDetector.decode(bytes)
                val expected = TxtParser.parse(bytes, "$id.txt").parts
                val index = IndexedText.build(StringReader(raw), File(dir, "$id.bin"))
                PreparedTxt(index, CharsetDetector.inspect(bytes), "test", SplitOptions(cleanWatermark = false)).use { doc ->
                    val actual = doc.sections.map { ChapterSplitter.Part(it.title, doc.body(it), it.volume) }
                    assertEquals(id, expected, actual)
                }
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun cacheValidatesBytesAndDoesNotReuseAChangedFile() {
        val dir = Files.createTempDirectory("ink-cache-test").toFile()
        try {
            val store = IndexedTxtStore(dir)
            var source = "第一章\n\n正文。".toByteArray()
            val options = SplitOptions(cleanWatermark = false)
            repeat(2) { store.withDocument("book.txt", options, { source.inputStream() }) { check(it.sections.size == 1) } }
            assertEquals(1, store.cacheHits)
            source = "第一章\n\n正文已变更。\n\n第二章\n\n新内容。".toByteArray()
            store.withDocument("book.txt", options, { source.inputStream() }) { assertEquals(2, it.sections.size) }
            assertEquals(1, store.cacheHits)
        } finally { dir.deleteRecursively() }
    }

    @Test fun correctionAnchorsFollowTextAndRejectChangedOrAmbiguousContext() {
        val body = "完整正文。".repeat(40)
        val source = "第一章\n\n$body\n\n第二章\n\n$body\n\n第三章\n\n$body"
        val initial = ChapterSplitter.analyze(source)
        val edited = ChapterSplitter.analyze(source, options = SplitOptions(cleanWatermark = false,
            ignoredHeadingLines = setOf(initial.decisions[1].line)))
        val anchors = ChapterCorrections.capture(source.lines(), edited.decisions)
        val shifted = "书籍说明\n\n$source"
        val resolution = ChapterCorrections.resolve(shifted.lines(), anchors)
        assertEquals(setOf(initial.decisions[1].line + 2), resolution.ignored)
        val changed = shifted.replace("第二章\n\n$body", "第二章\n\n改写后的正文。")
        assertTrue(ChapterCorrections.resolve(changed.lines(), anchors).ignored.isEmpty())
        assertTrue(ChapterCorrections.resolve((source + "\n\n" + source).lines(), anchors).ignored.isEmpty())
    }

    @Test fun sessionCleanupPreservesLiveRepositoriesAndRemovesAbandonedSessions() {
        val dir = Files.createTempDirectory("ink-session-test").toFile()
        try {
            val stale = File(dir, "session-00000000-0000-0000-0000-000000000000").apply { mkdirs(); resolve("old").writeText("old") }
            val first = IndexedTxtStore(dir)
            first.withDocument("one.txt", SplitOptions(), { "第一章\n\n原文".byteInputStream() }) { }
            assertFalse(stale.exists())
            val liveFiles = dir.walkTopDown().filter { it.isFile }.toList()
            IndexedTxtStore(dir).withDocument("two.txt", SplitOptions(), { "第二章\n\n后文".byteInputStream() }) { }
            assertTrue(liveFiles.isNotEmpty() && liveFiles.all { it.exists() })
            first.withDocument("one.txt", SplitOptions(), { "第一章\n\n原文".byteInputStream() }) { }
            assertEquals(1, first.cacheHits)
        } finally { dir.deleteRecursively() }
    }

    @Test fun longCodeFenceAndUserConfirmedDecisionMatchDiskAndStringPlans() {
        val dir = Files.createTempDirectory("ink-fence-test").toFile()
        try {
            val source = "`".repeat(900) + "\n\n第一章 引用\n\n" + "`".repeat(400) +
                "\n\n第二章 仍在代码内\n\n" + "`".repeat(1000) + "\n\n第一章 正文\n\n正文保留。"
            val options = SplitOptions(cleanWatermark = false)
            val expected = ChapterSplitter.analyze(source, options = options)
            IndexedText.build(StringReader(source), File(dir, "fence.bin")).use { text ->
                assertEquals(expected.decisions, ChapterSplitter.plan(text.lines, text.length, options).decisions)
            }
            val forced = ChapterSplitter.analyze("无编号起点\n\n正文内容。", options = options.copy(forcedHeadingLines = setOf(1)))
            assertTrue(forced.decisions.single().reasons.contains(HeadingReason.USER_CONFIRMED))
            assertTrue(forced.decisions.single().accepted)
        } finally { dir.deleteRecursively() }
    }

    @Test fun allAutomaticModesAndWatermarkChoicesMatchTheExistingStringPath() {
        val dir = Files.createTempDirectory("ink-modes-test").toFile()
        val root = File("tests/fixtures/recognition-gold").takeIf { it.isDirectory } ?: File("../tests/fixtures/recognition-gold")
        try {
            val samples = File(root, "manifest.tsv").readLines().drop(1).filter { it.isNotBlank() }.map {
                CharsetDetector.decode(File(root, it.substringBefore('\t') + ".txt").readBytes())
            } + listOf("www.example.com\n\n我的书名\n\n第一章\n\n正文内容。", "第一章\n\n" + "完整正文🐉。".repeat(22000))
            for ((n, raw) in samples.withIndex()) for (mode in SplitMode.entries.filter { it != SplitMode.CUSTOM }) for (clean in listOf(false, true)) {
                val options = SplitOptions(mode = mode, cleanWatermark = clean, fixedChunkChars = 4000)
                val expected = TxtParser.parse(raw.toByteArray(), "sample.txt", options)
                PreparedTxt(IndexedText.build(raw.reader(), File(dir, "mode.bin")), CharsetDetector.inspect(raw.toByteArray()), "test", options).use { doc ->
                    assertEquals("title $n/$mode/$clean", expected.title, doc.title)
                    assertEquals("parts $n/$mode/$clean", expected.parts, doc.sections.map { ChapterSplitter.Part(it.title, doc.body(it), it.volume) })
                }
            }
        } finally { dir.deleteRecursively() }
    }
}
