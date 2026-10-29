package com.inkreader.parse

import com.inkreader.data.model.SplitOptions
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class SourceEvaluationV2Test {
    @Test fun frozenRealWorksMeasureMissesFalseCutsAndParagraphConservation() {
        evaluate("tests/fixtures/real-source-v2", "INK_SOURCE_V2_PARTITION")
    }
    @Test fun chineseFocusedExpansionPreservesFrozenSourceBlocks() {
        evaluate("tests/fixtures/real-source-v3", "INK_SOURCE_V3_PARTITION")
    }
    private fun evaluate(relative: String, variable: String) {
        val root = File(relative).takeIf { it.isDirectory } ?: File("../$relative")
        val frozen = Regex("\"([^\"]+)\":\\s*\"([0-9a-f]{64})\"").findAll(root.resolve("frozen-sha256.json").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
        for ((name, hash) in frozen) assertEquals("Frozen file changed: $name", hash,
            MessageDigest.getInstance("SHA-256").digest(root.resolve(name).readBytes()).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) })
        val rows = root.resolve("manifest.tsv").readLines().drop(1).filter { it.isNotBlank() }.map { it.split('\t') }
        assertTrue(rows.groupBy { it[3] }.values.all { group -> group.map { it[1] }.distinct().size == 1 })
        val partition = System.getenv(variable) ?: "development"
        val selected = rows.filter { partition == "all" || it[1] == partition }
        assertTrue("No source evaluation cases selected", selected.isNotEmpty())
        val failures = arrayListOf<String>()
        val metrics = linkedMapOf<String, IntArray>()
        for (row in selected) {
            val id = row[0]; val part = row[1]; val language = row[2]; val kind = row[4]; val contract = row[5]
            val source = root.resolve("$id.txt").readText()
            val gold = SourceGold.read(root.resolve("$id.gold").readText(), source)
            assertEquals("$id normalization contract", SourceGold.normalizeContract(source), ChapterSplitter.normalize(source))
            val result = ChapterSplitter.analyze(ChapterSplitter.normalize(source), options = SplitOptions(cleanWatermark = false))
            val score = SourceGold.score(result, gold, relative.endsWith("-v3"))
            val c = metrics.getOrPut("$part/$language/$contract") { IntArray(5) }
            c[0]++; c[1] += score.tp; c[2] += score.fp; c[3] += score.fn; c[4] += score.bodyErrors
            println("SOURCE_EVAL corpus=$relative case=$id partition=$part language=$language kind=$kind contract=$contract TP=${score.tp} FP=${score.fp} FN=${score.fn} bodyErrors=${score.bodyErrors} titlesMatch=${score.titlesMatch}")
            if (score.bodyErrors != 0 || (contract == "required" && !score.exact)) {
                failures += "$id expected=${gold.titles} actual=${result.decisions.filter { it.accepted }.map { it.title }} score=$score"
            }
        }
        for ((cohort, c) in metrics) println("SOURCE_V2_TOTAL cohort=$cohort cases=${c[0]} TP=${c[1]} FP=${c[2]} FN=${c[3]} bodyErrors=${c[4]}")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
