package com.inkreader.parse

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class NormalizationAnchorsTest {
    @Test fun requestedWordOffsetsSurviveWhitespaceUnicodeAndWatermarkNormalization() {
        val dir = Files.createTempDirectory("ink-anchor-test").toFile()
        try {
            for (source in listOf("  甲\r\n\r\n\r\n  保留🐉乙  \r\n", "正文甲\n\n=== 小说网 www.example.com ===\n\n 保留乙\u200B丙\n",
                "x".repeat(32765) + "\n=== 小说网 www.example.com ===\n保留🐉尾声  ")) {
                val positions = intArrayOf(source.indexOf("保留"), source.indexOf("🐉").takeIf { it >= 0 } ?: source.indexOf('丙'), source.length)
                for (clean in listOf(false, true)) {
                    val anchors = NormalizationAnchors(positions)
                    IndexedText.build(source.reader(), dir.resolve("data.bin"), anchors, clean).use { index ->
                        val normalized = ChapterSplitter.normalize(source)
                        val expected = if (clean) ChapterSplitter.cleanBodyWatermarks(normalized) else normalized
                        assertEquals(expected, index.read(0, index.length))
                        assertEquals(expected.indexOf("保留"), anchors.map(positions[0]))
                        val token = if (source.contains("🐉")) "🐉" else "丙"
                        assertEquals(expected.indexOf(token), anchors.map(positions[1]))
                        assertEquals(index.length, anchors.map(source.length))
                    }
                }
            }
        } finally { dir.deleteRecursively() }
    }
}
