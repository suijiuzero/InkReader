package com.inkreader.parse

import java.io.File
import org.junit.Test
import org.junit.Assert.*

class HeadingTrainingExportTest {
    @Test fun exportIndependentLabelsWithTheActualAndroidFeatureExtractor() {
        val target = System.getenv("INK_MODEL_EXPORT") ?: return
        val root = File("tests/fixtures").takeIf { it.isDirectory } ?: File("../tests/fixtures")
        File(target).apply { parentFile?.mkdirs() }.bufferedWriter().use { out ->
            out.appendLine("dataset\tcase\tpartition\tgroup\tline\tlabel\t" + HeadingFeatures.names.joinToString("\t"))
            for (dataset in listOf("real-source-v2", "real-source-v3", "heading-training-extra")) {
                if (!root.resolve("$dataset/manifest.tsv").isFile) continue
                for (row in root.resolve("$dataset/manifest.tsv").readLines().drop(1).filter { it.isNotBlank() }) {
                    val cells = row.split('\t'); val id = cells[0]
                    val source = root.resolve("$dataset/$id.txt").readText()
                    val gold = SourceGold.read(root.resolve("$dataset/$id.gold").readText(), source)
                    val lines = SourceGold.normalizeContract(source).lines()
                    val features = HeadingFeatures(lines)
                    // Metadata row keeps all gold positives in the recall denominator,
                    // including titles beyond the bounded inference candidate budget.
                    out.appendLine(listOf(dataset, id, cells[1], cells[3], "0", gold.lines.size.toString()).joinToString("\t") +
                        "\t" + HeadingFeatures.names.joinToString("\t") { "0.0" })
                    for (i in features.candidates) out.appendLine(listOf(dataset, id, cells[1], cells[3], (i+1).toString(),
                        if (i+1 in gold.lines) "1" else "0").joinToString("\t") + "\t" + features.at(i).joinToString("\t"))
                }
            }
        }
    }
    @Test fun featureVectorIsFiniteBoundedAndHasStableOrder() {
        val source = listOf("", "第一章", "", "正文。".repeat(200), "", "A supplementary 🐉 title", "")
        val f = HeadingFeatures(source)
        for (i in f.candidates) {
            val vector = f.at(i)
            assertEquals(HeadingFeatures.names.size, vector.size)
            assertTrue(vector.all { it.isFinite() && it in 0.0..1.0 })
        }
    }
}
