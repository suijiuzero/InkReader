package com.inkreader.parse

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Count every inflated entry, including media that is discarded; never return a partial document. */
internal object DocxArchive {
    private val selected = setOf("word/document.xml", "word/document2.xml", "word/styles.xml", "docProps/core.xml",
        "word/_rels/document.xml.rels", "word/_rels/document2.xml.rels")
    private val mediaExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    fun read(input: InputStream, maxEntry: Long = 64L * 1024 * 1024, maxRetained: Long = 128L * 1024 * 1024,
             maxInflated: Long = 256L * 1024 * 1024, maxEntries: Int = 10000): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var inflated = 0L
        var retained = 0L
        var entries = 0
        val buffer = ByteArray(32768)
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                ParseCancellation.checkpoint()
                val entry = zip.nextEntry ?: break
                check(++entries <= maxEntries) { "Word 条目过多" }
                val keep = !entry.isDirectory && (entry.name in selected ||
                    entry.name.startsWith("word/media/") && entry.name.substringAfterLast('.').lowercase(java.util.Locale.ROOT) in mediaExtensions)
                val data = if (keep) ByteArrayOutputStream() else null
                var size = 0L
                while (true) {
                    ParseCancellation.checkpoint()
                    val count = zip.read(buffer)
                    if (count < 0) break
                    inflated += count
                    check(inflated <= maxInflated) { "Word 解压数据超限" }
                    if (keep) {
                        size += count
                        retained += count
                        check(size <= maxEntry && retained <= maxRetained) { "Word 正文或样式超限" }
                        data!!.write(buffer, 0, count)
                    }
                }
                zip.closeEntry()
                if (data != null) {
                    check(entry.name !in out) { "重复的 Word 条目" }
                    out[entry.name] = data.toByteArray()
                }
            }
        }
        return out
    }
}
