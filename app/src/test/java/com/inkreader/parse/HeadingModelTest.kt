package com.inkreader.parse

import com.inkreader.data.model.SplitOptions
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class HeadingModelTest {
    @Test fun frozenCorpusSuggestionsNeverSilentlyChangeSplits() {
        assertTrue(HeadingModel.available)
        val root = File("tests/fixtures").takeIf { it.isDirectory } ?: File("../tests/fixtures")
        val totals = linkedMapOf<String, IntArray>()
        for (dataset in listOf("real-source-v2", "real-source-v3")) {
            for (row in root.resolve("$dataset/manifest.tsv").readLines().drop(1).filter { it.isNotBlank() }) {
                val cells = row.split('\t'); val id = cells[0]
                val source = root.resolve("$dataset/$id.txt").readText()
                val gold = SourceGold.read(root.resolve("$dataset/$id.gold").readText(), source)
                val text = SourceGold.normalizeContract(source)
                val options = SplitOptions(cleanWatermark = false)
                val baseline = ChapterSplitter.plan(text.lines(), text.length, options)
                val assisted = ChapterSplitter.plan(text.lines(), text.length, options.copy(modelSuggestions = true))
                assertEquals(id, baseline.headings, assisted.headings)
                assertTrue(assisted.modelLines.size <= 200)
                val missing = gold.lines.map { it - 1 }.toSet() - baseline.headings.toSet()
                val added = assisted.modelLines
                val counts = totals.getOrPut(cells[1]) { IntArray(3) }
                counts[0] += (added intersect missing).size
                counts[1] += (added - gold.lines.map { it - 1 }.toSet()).size
                counts[2] += (missing - added).size
                if (added.isNotEmpty()) {
                    val line = added.first() + 1
                    val confirmed = ChapterSplitter.plan(text.lines(), text.length, options.copy(modelSuggestions = true, forcedHeadingLines = setOf(line)))
                    assertTrue(line - 1 in confirmed.headings)
                    val ignored = ChapterSplitter.plan(text.lines(), text.length, options.copy(modelSuggestions = true, ignoredHeadingLines = setOf(line)))
                    assertFalse(line - 1 in ignored.headings)
                }
            }
        }
        totals.forEach { (split, v) -> println("MODEL_ADDITIONAL $split TP=${v[0]} FP=${v[1]} FN=${v[2]}") }
    }
    @Test fun malformedNumbersFailClosedAndInferenceMatchesTrainedWeights() {
        assertNull(HeadingModel.score(doubleArrayOf(Double.NaN), doubleArrayOf(0.0, 1.0)))
        assertNull(HeadingModel.score(doubleArrayOf(0.0), doubleArrayOf(0.0)))
        assertNull(HeadingModel.score(doubleArrayOf(1.0), doubleArrayOf(0.0, Double.POSITIVE_INFINITY)))
        assertEquals(0.5, HeadingModel.score(doubleArrayOf(0.0), doubleArrayOf(0.0, 0.0))!!, 0.0)
        val features = HeadingFeatures(listOf("夜行", "", "完整的正文。".repeat(60)))
        assertTrue(HeadingModel.score(features.at(0))!! in 0.0..1.0)
    }
    @Test fun suggestionsRespectCodeFencesAndExplicitModes() {
        val text = "```\n夜行\n\n" + "完整的正文。".repeat(80) + "\n```"
        assertTrue(ChapterSplitter.plan(text.lines(), text.length, SplitOptions(modelSuggestions = true)).modelLines.isEmpty())
        for (mode in listOf(com.inkreader.data.model.SplitMode.STRICT_CHAP, com.inkreader.data.model.SplitMode.NUMBERED)) {
            assertTrue(ChapterSplitter.plan(listOf("夜行", "", "完整的正文。".repeat(80)), 600,
                SplitOptions(mode = mode, modelSuggestions = true)).modelLines.isEmpty())
        }
    }
    @Test fun confirmationIsAvailableThroughPagedReviewAndCacheSwitching() {
        val root = File("tests/fixtures/real-source-v3").takeIf { it.isDirectory } ?: File("../tests/fixtures/real-source-v3")
        val dir = java.nio.file.Files.createTempDirectory("ink-model-review").toFile()
        try {
            val bytes = root.resolve("C19A.txt").readBytes()
            val store = IndexedTxtStore(dir)
            val options = SplitOptions(cleanWatermark = false, modelSuggestions = true)
            var count = 0
            store.withDocument("collection.txt", options, { bytes.inputStream() }) { doc ->
                val page = RecognitionBrowser.page(doc, "", 0, true)
                count = page.rows.count { it.modelSuggestion }
                assertTrue(page.rows.filter { it.modelSuggestion }.all { !it.selected && it.editable })
            }
            assertTrue(count > 0)
            store.withDocument("collection.txt", options.copy(modelSuggestions = false), { bytes.inputStream() }) {
                assertTrue(it.plan.modelLines.isEmpty())
            }
        } finally { dir.deleteRecursively() }
    }
    @Test(timeout = 60000) fun largeCandidateQueueIsBoundedAndCancellationStillWorks() {
        val lines = (0 until 12000).flatMap { listOf("候选标题$it", "", "完整的正文。".repeat(60), "") }
        val suggestions = HeadingModel.suggest(lines, BooleanArray(lines.size), emptySet())
        assertTrue(suggestions.size <= 200)
        assertEquals(suggestions.map { it.index }.sorted(), suggestions.map { it.index })
        Thread.currentThread().interrupt()
        try {
            var cancelled = false
            try { HeadingModel.suggest(lines, BooleanArray(lines.size), emptySet()) }
            catch (_: java.util.concurrent.CancellationException) { cancelled = true }
            assertTrue(cancelled)
        } finally { Thread.interrupted() }
    }
}
