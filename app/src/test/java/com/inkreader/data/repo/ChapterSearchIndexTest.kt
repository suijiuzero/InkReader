package com.inkreader.data.repo

import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files

class ChapterSearchIndexTest {
    @Test fun exactSubstringContextsAcrossChunksCacheCorruptionAndAtomicReplacement() {
        val root = Files.createTempDirectory("search-test").toFile()
        try {
            val file = java.io.File(root, "chapter.txt")
            val text = "A".repeat(16378) + "中A𠮷bßİıſΣς🐉文\n\n" + "tail NEEDLE ".repeat(4000) + "LAST_MATCH"
            file.writeText(text)
            val cache = java.io.File(root, "cache"); val index = ChapterSearchIndex(cache)
            for (query in listOf("中A𠮷", "𠮷B", "İ", "I", "ſ", "σ", "🐉", "needle", "LAST_MATCH", "no-match", "文\n\n", "A".repeat(17000))) {
                val expected = ArrayList<ChapterSearchIndex.Hit>(); var from = 0
                while (expected.size < 10000) {
                    val offset = text.indexOf(query, from, true); if (offset < 0) break
                    val a = maxOf(0, offset - 18); val b = minOf(text.length, offset + query.length + 18)
                    expected += ChapterSearchIndex.Hit(offset, (if (a > 0) "…" else "") + text.substring(a, b).replace('\n', ' ').trim() + (if (b < text.length) "…" else ""))
                    from = offset + query.length
                }
                assertEquals(query, expected, index.search(file, query, 10000))
                assertEquals(query, expected, index.search(file, query, 10000))
            }
            index.search(file, "never-in-book", 10000)
            assertTrue(index.skippedChapters > 0)
            cache.listFiles()!!.forEach { it.writeBytes(ByteArray(8200)) }
            assertEquals(4000, index.search(file, "needle", 10000).size)
            file.writeText("NEW CONTENT")
            assertEquals(1, index.search(file, "content", 10000).size)
        } finally { root.deleteRecursively() }
    }
    @Test fun supplementaryCasePairsAreNeverExcludedByUtf16CandidateFilter() {
        val root = Files.createTempDirectory("search-supplementary").toFile()
        try {
            val file = java.io.File(root, "case.txt").apply { writeText("𐐀A 𐐐B tail") }
            val index = ChapterSearchIndex(java.io.File(root, "cache"))
            for (query in listOf("𐐨a", "𐐸b")) {
                val expected = file.readText().indexOf(query, ignoreCase = true)
                assertTrue(expected >= 0)
                assertEquals(expected, index.search(file, query, 10).single().offset)
                assertEquals(expected, index.search(file, query, 10).single().offset)
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun cancellationIsPromptAndPartialScansDoNotCreateNegativeIndex() {
        val root = Files.createTempDirectory("search-cancel").toFile()
        try {
            val file = java.io.File(root, "text.txt").apply { writeText("prefix " + "body ".repeat(30000) + "TAIL_TOKEN") }
            val index = ChapterSearchIndex(java.io.File(root, "cache"))
            assertEquals(1, index.search(file, "prefix", 1).size)
            assertEquals(1, index.search(file, "TAIL_TOKEN", 1).size)
            var checks = 0
            try { index.search(file, "nothing", 10) { if (++checks == 2) throw java.util.concurrent.CancellationException() }; fail() }
            catch (_: java.util.concurrent.CancellationException) { }
        } finally { root.deleteRecursively() }
    }
}
