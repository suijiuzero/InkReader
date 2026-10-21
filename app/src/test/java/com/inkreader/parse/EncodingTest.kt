package com.inkreader.parse

import org.junit.Assert.*
import org.junit.Test

class EncodingTest {
    @Test fun utf16WithBomIsAcceptedAsText() {
        val text = "第一章 起点\n\n这是正文内容。"
        for (charset in listOf(Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val bom = if (charset == Charsets.UTF_16LE) byteArrayOf(-1, -2) else byteArrayOf(-2, -1)
            val bytes = bom + text.toByteArray(charset)
            assertEquals(DocumentFormat.TXT, FormatDetector.detect(bytes, "book.txt"))
            assertEquals(text, CharsetDetector.decode(bytes))
        }
    }

    @Test fun bomlessUtf16UsesCorrectEndian() {
        val text = "Hello reader. ".repeat(10)
        for (charset in listOf(Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val bytes = text.toByteArray(charset)
            assertEquals(charset, CharsetDetector.detect(bytes).charset)
            assertEquals(text, CharsetDetector.decode(bytes))
        }
    }

    @Test fun nulBinaryStillRejected() {
        assertEquals(DocumentFormat.UNKNOWN, FormatDetector.detect(byteArrayOf(1, 0, 2, 3, 4, 5), "data.bin"))
    }
}
