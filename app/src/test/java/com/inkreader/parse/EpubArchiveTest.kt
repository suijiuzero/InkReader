package com.inkreader.parse

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class EpubArchiveTest {
    private fun archive(vararg entries: Pair<String,String>): ByteArray {
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> entries.forEach { (name,text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
        return out.toByteArray()
    }
    @Test fun pathsPreserveLiteralPlusPercentEscapesAndCase() {
        assertEquals("OEBPS/Chapter+1.xhtml",EpubArchive.resolve("OEBPS","Chapter+1.xhtml"))
        assertEquals("OEBPS/Chapter+1.xhtml",EpubArchive.resolve("OEBPS","Chapter%2B1.xhtml#x"))
        val files=EpubArchive.read(archive("A.xhtml" to "upper", "a.xhtml" to "lower"))
        assertEquals(2,files.size)
        assertEquals("upper",String(files.getValue("A.xhtml")))
    }
    @Test fun oversizedTextFailsInsteadOfReturningEarlierChapter() {
        try {
            EpubArchive.read(archive("a.xhtml" to "valid", "b.xhtml" to "x".repeat(100)),maxEntry=20)
            fail("partial book must never be returned")
        } catch (_: IllegalStateException) { }
    }
    @Test fun aggregateTextLimitAlsoRejectsWholeImport() {
        try {
            EpubArchive.read(archive("a.xhtml" to "first", "b.xhtml" to "second"),maxTotal=8)
            fail("aggregate size must be bounded")
        } catch (_: IllegalStateException) { }
    }
    @Test fun skippedBinaryDataStillCountsAgainstInflationBudget() {
        try {
            EpubArchive.read(archive("a.xhtml" to "first", "cover.jpg" to "x".repeat(100)),maxInflated=20)
            fail("discarded data must also be bounded")
        } catch (_: IllegalStateException) { }
    }
    @Test fun corruptedStoredEntryIsNotIgnored() {
        val data="valid".toByteArray()
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val entry=ZipEntry("a.xhtml").apply { method=ZipEntry.STORED; size=data.size.toLong(); compressedSize=size; crc=java.util.zip.CRC32().apply { update(data) }.value }
            zip.putNextEntry(entry); zip.write(data); zip.closeEntry()
        }
        val bytes=out.toByteArray(); bytes[14]=(bytes[14].toInt() xor 1).toByte()
        try { EpubArchive.read(bytes); fail("CRC failure must abort") }
        catch (_: java.util.zip.ZipException) { }
    }
}
