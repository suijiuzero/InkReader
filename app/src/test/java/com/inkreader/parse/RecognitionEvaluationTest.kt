package com.inkreader.parse

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

/** Expectations are authored and frozen independently of parser output. */
class RecognitionEvaluationTest {
    data class Counts(var tp: Int = 0, var fp: Int = 0, var fn: Int = 0, var bodyErrors: Int = 0, var cases: Int = 0)
    @Test fun partitionedBoundaryAndParagraphEvaluation() {
        val path = "tests/fixtures/recognition-eval"
        val root = File(path).takeIf { it.isDirectory } ?: File("../$path")
        val selected = System.getenv("INK_EVAL_PARTITION") ?: "development"
        val frozen = Regex("\"([^\"]+)\":\\s*\"([0-9a-f]{64})\"").findAll(File(root, "frozen-sha256.json").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
        val manifestHash = MessageDigest.getInstance("SHA-256").digest(File(root, "manifest.tsv").readBytes())
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
        assertEquals("Frozen partition manifest changed", frozen["manifest.tsv"], manifestHash)
        val rows = File(root, "manifest.tsv").readLines().drop(1).filter { it.isNotBlank() }.map { it.split('\t') }
        val groups = rows.groupBy { it[3] }
        assertTrue("Source groups overlap partitions", groups.values.all { values -> values.map { it[1] }.distinct().size == 1 })
        val metrics = linkedMapOf<String, Counts>()
        val failures = ArrayList<String>()
        for (row in rows.filter { selected == "all" || it[1] == selected }) {
            val (id, partition, language) = row
            for (name in listOf("$id.txt", "$id.gold")) {
                val hash = MessageDigest.getInstance("SHA-256").digest(File(root, name).readBytes()).joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
                assertEquals("Frozen annotation/input changed: $name", frozen[name], hash)
            }
            val gold = File(root, "$id.gold").readLines()
            val expected = gold.filter { it.startsWith("L\t") }.map { it.substring(2).toInt() }.toSet()
            val titles = gold.filter { it.startsWith("T\t") }.map { it.substring(2) }
            // Import normalization removes trailing whitespace on every line; annotations retain the raw paragraph.
            val paragraphs = gold.filter { it.startsWith("P64\t") }.map {
                String(Base64.getDecoder().decode(it.substring(4)), Charsets.UTF_8).lines().joinToString("\n") { line -> line.trimEnd() }
            }
            val text = ChapterSplitter.normalize(File(root, "$id.txt").readText())
            val result = ChapterSplitter.analyze(text)
            val decisions = result.decisions.filter { it.accepted }
            val actual = decisions.map { it.line }.toSet()
            val counts = metrics.getOrPut("$partition/$language") { Counts() }
            counts.cases++; counts.tp += (expected intersect actual).size
            counts.fp += (actual - expected).size; counts.fn += (expected - actual).size
            if (expected != actual) failures += "$id boundaries expected=$expected actual=$actual"
            if (titles != decisions.map { it.title }) failures += "$id raw titles changed or misrecognized"
            val body = result.parts.joinToString("\n\n") { it.content }
            for (paragraph in paragraphs) if (Regex(Regex.escape(paragraph)).findAll(body).count() != 1) {
                counts.bodyErrors++; failures += "$id paragraph missing/duplicated"
            }
        }
        assertTrue("No evaluation cases selected", metrics.isNotEmpty())
        for ((cohort, c) in metrics) {
            val precision = if (c.tp + c.fp == 0) 1.0 else c.tp.toDouble() / (c.tp + c.fp)
            val recall = if (c.tp + c.fn == 0) 1.0 else c.tp.toDouble() / (c.tp + c.fn)
            println("EVAL cohort=$cohort cases=${c.cases} TP=${c.tp} FP=${c.fp} FN=${c.fn} precision=$precision recall=$recall bodyErrors=${c.bodyErrors}")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
