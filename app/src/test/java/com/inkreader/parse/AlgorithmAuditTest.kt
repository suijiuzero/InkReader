package com.inkreader.parse

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class AlgorithmAuditTest {
    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
        return out.toByteArray()
    }

    @Test fun bomOnlyFilesDecodeToEmptyContent() {
        for (bytes in listOf(byteArrayOf(-17,-69,-65), byteArrayOf(-1,-2), byteArrayOf(-2,-1))) {
            assertEquals("", CharsetDetector.decode(bytes))
        }
    }

    @Test fun utf8RejectsSurrogateAndOutOfRangeCodePoints() {
        for (bytes in listOf(byteArrayOf(0xED.toByte(),0xA0.toByte(),0x80.toByte()),
            byteArrayOf(0xF4.toByte(),0x90.toByte(),0x80.toByte(),0x80.toByte()))) {
            assertNotEquals(Charsets.UTF_8, CharsetDetector.detect(bytes).charset)
        }
        assertEquals(Charsets.UTF_8, CharsetDetector.detect(byteArrayOf(0xF4.toByte(),0x8F.toByte(),0xBF.toByte(),0xBF.toByte())).charset)
        assertEquals("龙🐉", CharsetDetector.decode("龙🐉".toByteArray()))
    }

    @Test fun magicBytesAreComparedCompletely() {
        assertFalse(FormatDetector.isPdf("%PDFx".toByteArray()))
        assertTrue(FormatDetector.isPdf("%PDF-1.7".toByteArray()))
        assertFalse(FormatDetector.isOle2(byteArrayOf(0xD0.toByte(),0xCF.toByte(),0x11,0xE0.toByte(),0,0,0,0)))
        assertTrue(FormatDetector.isOle2(byteArrayOf(0xD0.toByte(),0xCF.toByte(),0x11,0xE0.toByte(),0xA1.toByte(),0xB1.toByte(),0x1A,0xE1.toByte())))
        assertFalse(FormatDetector.isZip(byteArrayOf(0x50,0x4B,0x03,0x11)))
        assertTrue(FormatDetector.isZip(byteArrayOf(0x50,0x4B,0x03,0x04)))
    }

    @Test fun archiveContentsOverrideMisleadingSuffixes() {
        assertEquals(DocumentFormat.DOCX, FormatDetector.detect(zip("word/document.xml" to "doc"), "renamed.epub"))
        assertEquals(DocumentFormat.EPUB, FormatDetector.detect(zip("META-INF/container.xml" to "epub"), "renamed.docx"))
        assertEquals(DocumentFormat.PPTX, FormatDetector.detect(zip("ppt/presentation.xml" to "ppt"), "book.bin"))
        assertEquals(DocumentFormat.XLSX, FormatDetector.detect(zip("xl/workbook.xml" to "xls"), "book.bin"))
        assertEquals(DocumentFormat.ZIP, FormatDetector.detect(zip("mimetype" to "image/something", "word/documentary.txt" to "other"), "book.bin"))
    }

    @Test fun formatProbeDoesNotInflateArbitrarilyLargeSkippedEntries() {
        val data = zip("large.bin" to "x".repeat(3 * 1024 * 1024), "word/document.xml" to "doc")
        assertEquals(DocumentFormat.ZIP, FormatDetector.detect(data, "unknown.bin"))
        assertEquals(DocumentFormat.DOCX, FormatDetector.detect(data, "known.docx"))
    }

    @Test fun windowsDirectoryNamesDoNotSupplyChapterNumbers() {
        assertEquals("第五章 风起", ChapterSplitter.titleFromFileName("C:\\Book12\\第五章 风起.txt"))
        assertNull(ChapterSplitter.chapterOrderFromName("C:\\Part12\\正文.txt"))
        assertEquals(5, ChapterSplitter.chapterOrderFromName("C:\\Part12\\第五章 风起.txt"))
    }

    @Test fun sectionSubtitlesCannotMasqueradeAsChapterNumbers() {
        for (title in listOf("第十节 实例2", "【第十节】实例2", "正文 第十节 实例2")) {
            assertNull(title, ChapterSplitter.chapterLevelOrderFromTitle(title))
            assertEquals(10, ChapterSplitter.chapterOrderFromTitle(title))
        }
    }

    @Test fun longRealChaptersAreNotDiscardedAsSequenceOutliers() {
        val titles = listOf("第一章 起点", "第一百章 中间", "第二章 新卷")
        val parts = ChapterSplitter.split(titles.joinToString("\n\n") { "$it\n\n${"正文".repeat(900)}" })
        assertEquals(titles, parts.map { it.title })
    }

    @Test fun explicitVolumeBoundaryPreventsSequenceRejection() {
        val text = "第一章 起点\n\n甲正文\n\n第一百章 卷尾\n\n乙正文\n\n第二卷\n第二章 新卷\n\n丙正文"
        val parts = ChapterSplitter.split(text)
        assertEquals(listOf("第一章 起点", "第一百章 卷尾", "第二章 新卷"), parts.map { it.title })
        assertEquals("第二卷", parts.last().volume)
    }

    @Test fun docxLimitsIncludeSkippedMediaAndAllRetainedXml() {
        val media = zip("word/document.xml" to "valid", "word/media/image.bin" to "x".repeat(50))
        assertTrue(runCatching { DocxArchive.read(media.inputStream(), maxInflated = 20) }.isFailure)
        val xml = zip("word/document.xml" to "x".repeat(30))
        assertTrue(runCatching { DocxArchive.read(xml.inputStream(), maxEntry = 20) }.isFailure)
        val aggregate = zip("word/document.xml" to "first", "word/styles.xml" to "second")
        assertTrue(runCatching { DocxArchive.read(aggregate.inputStream(), maxRetained = 8) }.isFailure)
        assertTrue(runCatching { DocxArchive.read(aggregate.inputStream(), maxEntries = 1) }.isFailure)
        assertEquals(setOf("word/document.xml"), DocxArchive.read(media.inputStream()).keys)
    }
}
