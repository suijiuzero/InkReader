package com.inkreader.parse

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class RealSourceTest {
    @Test fun attributedWorkExcerptsMatchFrozenIndependentChapterAndParagraphLabels() {
        val root = File("tests/fixtures/real-source").takeIf { it.isDirectory } ?: File("../tests/fixtures/real-source")
        val hashes = Regex("\"([^\"]+)\":\\s*\"([0-9a-f]{64})\"").findAll(root.resolve("frozen-sha256.json").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
        hashes.forEach { (name, value) -> assertEquals(name, value, MessageDigest.getInstance("SHA-256").digest(root.resolve(name).readBytes())
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }) }
        val partition = System.getenv("INK_EVAL_PARTITION") ?: "development"
        for (row in root.resolve("manifest.tsv").readLines().drop(1).filter { it.isNotBlank() }) {
            val (id, group) = row.split('\t')
            if (partition != "all" && partition != group) continue
            val gold = root.resolve("$id.gold").readLines()
            val titles = gold.filter { it.startsWith("T\t") }.map { it.substring(2) }
            val boundaries = gold.filter { it.startsWith("L\t") }.map { it.substring(2).toInt() }
            val result = ChapterSplitter.analyze(ChapterSplitter.normalize(root.resolve("$id.txt").readText()),
                options = com.inkreader.data.model.SplitOptions(cleanWatermark = false))
            val accepted = result.decisions.filter { it.accepted }
            assertEquals("$id titles", titles, accepted.map { it.title })
            assertEquals("$id source boundaries", boundaries, accepted.map { it.line })
            val body = result.parts.joinToString("\n\n") { it.content }
            for (paragraph in gold.filter { it.startsWith("P64\t") }) {
                // 原导入契约会移除网页零宽/BOM字符；冻结答案仍保留原始摘段。
                val text = String(Base64.getDecoder().decode(paragraph.substring(4)), Charsets.UTF_8)
                    .replace("\u200B", "").replace("\uFEFF", "").replace('\u00A0', ' ')
                assertEquals("$id full paragraph", 1, Regex(Regex.escape(text)).findAll(body).count())
            }
            println("REAL_SOURCE id=$id partition=$group headings=${titles.size} FP=0 FN=0 bodyErrors=0")
        }
    }
}
