package com.inkreader.parse

import com.inkreader.data.model.SplitOptions
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RecognitionBrowserTest {
    @Test fun browserReachesHeadingsBeyondTheInlineLimitAndCanInspectMissedLines() {
        val dir = Files.createTempDirectory("ink-browser").toFile()
        try {
            val raw = (1..650).joinToString("\n\n") { "第${it}章 标题\n\n" + "完整正文。".repeat(35) }
            val store = IndexedTxtStore(dir)
            var fingerprint = ""
            store.withDocument("all.txt", SplitOptions(cleanWatermark = false), { raw.byteInputStream() }) { doc ->
                val page = RecognitionBrowser.page(doc, "650章", 0, false)
                assertEquals(1, page.total); assertEquals("第650章 标题", page.rows.single().title)
                assertEquals(650, page.chapters); fingerprint = page.fingerprint
                val source = RecognitionBrowser.page(doc, "#${page.rows.single().line}", 0, false)
                assertTrue(source.sourceView && source.rows.any { it.title == "第650章 标题" })
                assertEquals(page.rows.single().line, source.rows.first().line)
            }
            store.withSnapshot(fingerprint, SplitOptions(ignoredHeadingLines = setOf(1))) { doc ->
                assertEquals(649, doc.sections.size)
            } ?: fail("Snapshot was not available")
            try {
                store.withDocument("all.txt", SplitOptions(sourceFingerprint = fingerprint), { "changed".byteInputStream() }) { }
                fail("Changed source accepted stale edits")
            } catch (_: ParseException) { }
        } finally { dir.deleteRecursively() }
    }
    @Test fun explicitCorrectionsBeyondFortyRemainBookScopedAnchors() {
        val lines = (1..100).flatMap { listOf("第${it}章", "正文编号${it}。", "") }
        val anchors = ChapterCorrections.captureChoices(lines, setOf(148), setOf(298))
        val result = ChapterCorrections.resolve(lines, anchors)
        assertEquals(setOf(148), result.ignored); assertEquals(setOf(298), result.confirmed)
        assertEquals(2, anchors.size)
    }
}
