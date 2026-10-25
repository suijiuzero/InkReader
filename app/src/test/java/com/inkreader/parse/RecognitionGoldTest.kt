package com.inkreader.parse

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RecognitionGoldTest {
    @Test fun independentlyAnnotatedCorpusMeasuresFalseSplitsAndMisses() {
        val relative = "tests/fixtures/recognition-gold"
        val root = File(relative).takeIf { it.isDirectory } ?: File("../$relative")
        require(root.isDirectory) { "Gold corpus not found: ${root.absolutePath}" }
        var truePositive = 0
        var falsePositive = 0
        var falseNegative = 0
        val failures = ArrayList<String>()
        val begin = System.nanoTime()
        val cases = File(root, "manifest.tsv").readLines().drop(1).filter { it.isNotBlank() }
        for (row in cases) {
            val id = row.substringBefore('\t')
            val gold = File(root, "$id.gold").readLines()
            val expected = gold.filter { it.startsWith("T\t") }.map { it.substring(2) }
            val markers = gold.filter { it.startsWith("P\t") }.map { it.substring(2) }
            val parsed = TxtParser.parse(File(root, "$id.txt").readBytes(), "$id.txt", diagnostics = true)
            val plain = TxtParser.parse(File(root, "$id.txt").readBytes(), "$id.txt")
            if (plain.parts != parsed.parts) failures += "$id diagnostic mode changed chapter content"
            val titles = if (parsed.analysis!!.decisions.isEmpty() && parsed.analysis.usedSizeFallback) emptyList()
                else parsed.parts.map { it.title }.filterNot { ChapterSplitter.isFallbackSectionTitle(it) }
            val remaining = expected.toMutableList()
            for (title in titles) {
                if (remaining.remove(title)) truePositive++ else falsePositive++
            }
            falseNegative += remaining.size
            if (expected != titles && expected.isNotEmpty()) failures += "$id titles: $titles expected $expected"
            if (expected.isEmpty() && parsed.parts.size != 1) failures += "$id should remain one unheaded body"
            val body = parsed.parts.joinToString("\n") { it.content }
            for (marker in markers) if (Regex(Regex.escape(marker)).findAll(body).count() != 1)
                failures += "$id body marker lost/duplicated: $marker"
            if ('\uFFFD' in body) failures += "$id replacement characters in decoded body"
        }
        val precision = truePositive.toDouble() / (truePositive + falsePositive).coerceAtLeast(1)
        val recall = truePositive.toDouble() / (truePositive + falseNegative).coerceAtLeast(1)
        val f1 = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
        println("GOLD cases=${cases.size} TP=$truePositive FP=$falsePositive FN=$falseNegative precision=$precision recall=$recall F1=$f1 ms=${(System.nanoTime() - begin) / 1_000_000}")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        // Unheaded fallback titles are not chapter boundary predictions.
        assertEquals(0, falseNegative)
        assertEquals(0, falsePositive)
    }
}
