package com.inkreader.parse

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** 严格读取 EPUB 文本；任一必需条目超限或 CRC 错误向上报告，不返回半本书。 */
object EpubArchive {
    private val TEXT_EXT = setOf("xhtml", "html", "htm", "xml", "opf", "ncx", "txt")
    private fun normalize(path: String): String {
        var p = path.trim()
        while (p.startsWith("./")) p = p.substring(2)
        return p.trimStart('/')
    }

    fun read(bytes: ByteArray, maxEntry: Long = 8L * 1024 * 1024,
             maxTotal: Long = 96L * 1024 * 1024,
             maxInflated: Long = 256L * 1024 * 1024): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var retained = 0L
        var inflated = 0L
        var count = 0
        val buffer = ByteArray(32 * 1024)
        ZipInputStream(bytes.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                check(++count <= 10000) { "EPUB 条目过多" }
                val name = normalize(entry.name)
                val keep = !entry.isDirectory && name.substringAfterLast('.', "").lowercase() in TEXT_EXT
                val data = if (keep) ByteArrayOutputStream() else null
                var size = 0L
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    size += n
                    inflated += n
                    check(inflated <= maxInflated) { "EPUB 解压数据超限" }
                    if (keep) {
                        retained += n
                        check(size <= maxEntry && retained <= maxTotal) { "EPUB 正文超限，未导入不完整书籍" }
                        data!!.write(buffer, 0, n)
                    }
                }
                zip.closeEntry()
                if (data != null) {
                    check(!out.containsKey(name)) { "重复的 EPUB 条目 $name" }
                    out[name] = data.toByteArray()
                }
            }
        }
        return out
    }

    fun resolve(baseDir: String, href: String): String {
        val clean = href.substringBefore('#').substringBefore('?')
        val decoded = runCatching { java.net.URLDecoder.decode(clean.replace("+", "%2B"), "UTF-8") }.getOrDefault(clean)
        val raw = if (decoded.startsWith("/")) decoded.trimStart('/') else "$baseDir/$decoded"
        val out = ArrayList<String>()
        for (seg in raw.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out += seg
            }
        }
        return out.joinToString("/")
    }

}
