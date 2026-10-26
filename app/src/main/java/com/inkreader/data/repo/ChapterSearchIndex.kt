package com.inkreader.data.repo

import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32

/** 磁盘Bloom只排除确定不匹配的章节，最终仍逐字校验，保留忽略大小写的子串语义。 */
class ChapterSearchIndex(private val root: File) {
    data class Hit(val offset: Int, val context: String)
    var skippedChapters = 0; private set
    private fun fold(c: Char) = c.uppercaseChar().lowercaseChar().code
    private fun hash(a: Char, b: Char, c: Char): Int = ((fold(a) * 1000003 + fold(b)) * 1000003 + fold(c)) and 65535
    private fun add(bits: ByteArray, code: Int) { val i = code ushr 3; bits[i] = (bits[i].toInt() or (1 shl (code and 7))).toByte() }
    private fun contains(bits: ByteArray, keyword: String): Boolean {
        // regionMatches 在部分平台按完整码点折叠补充平面字母；UTF-16三元组不能据此排除。
        for (i in 0 until keyword.lastIndex) {
            if (Character.isHighSurrogate(keyword[i]) && Character.isLowSurrogate(keyword[i + 1])) {
                val point = Character.toCodePoint(keyword[i], keyword[i + 1])
                if (Character.toUpperCase(point) != point || Character.toLowerCase(point) != point) return true
            }
        }
        for (i in 2 until keyword.length) { val code = hash(keyword[i - 2], keyword[i - 1], keyword[i])
            if (bits[code ushr 3].toInt() and (1 shl (code and 7)) == 0) return false
        }
        return true
    }
    private fun key(file: File) = "search-v1|${file.name}|${file.length()}|${file.lastModified()}"
    private fun cache(key: String): File = File(root, MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) } + ".idx")
    @Synchronized private fun load(file: File): ByteArray? = runCatching {
        if (file.length() != 8200L) return null
        file.inputStream().buffered().use { source ->
            val input = java.io.DataInputStream(source); val crc = input.readLong(); val bytes = ByteArray(8192); input.readFully(bytes)
            require(CRC32().also { it.update(bytes) }.value == crc); bytes
        }
    }.getOrNull()
    @Synchronized private fun save(file: File, bits: ByteArray) {
        runCatching {
            check(root.isDirectory || root.mkdirs())
            val temp = File.createTempFile("search-", ".tmp", root)
            try {
                java.io.DataOutputStream(temp.outputStream().buffered()).use { it.writeLong(CRC32().also { crc -> crc.update(bits) }.value); it.write(bits) }
                if (!temp.renameTo(file)) { file.delete(); check(temp.renameTo(file)) }
            } finally { temp.delete() }
            val entries = root.listFiles().orEmpty()
            if (entries.size > 1024) entries.sortedByDescending { it.lastModified() }.drop(768).forEach { it.delete() }
        }
    }
    fun search(file: File, keyword: String, maxHits: Int, open: () -> java.io.Reader = { file.reader(Charsets.UTF_8) }, checkpoint: () -> Unit = {}): List<Hit> {
        require(keyword.length <= 1000000)
        if (keyword.isBlank() || maxHits <= 0) return emptyList()
        val signature = key(file); val cached = cache(signature); val bits = load(cached)
        if (bits != null && !contains(bits, keyword)) { skippedChapters++; return emptyList() }
        val building = if (bits == null) ByteArray(8192) else null
        val result = ArrayList<Hit>(); var base = 0; var from = 0; var carry = ""
        var previous = '\u0000'; var beforePrevious = '\u0000'; var seen = 0
        var complete = false
        open().buffered().use { reader ->
            val buffer = CharArray(16384)
            while (true) {
                checkpoint()
                val count = reader.read(buffer); val eof = count < 0
                val incoming = if (eof) "" else String(buffer, 0, count)
                if (building != null) for (c in incoming) {
                    if (seen >= 2) add(building, hash(beforePrevious, previous, c))
                    beforePrevious = previous; previous = c; seen = minOf(2, seen + 1)
                }
                val window = carry + incoming
                val limit = if (eof) window.length else window.length - keyword.length - 18
                var next = maxOf(0, from - base)
                while (result.size < maxHits) {
                    checkpoint()
                    val index = window.indexOf(keyword, next, ignoreCase = true)
                    if (index < 0 || index > limit) break
                    val start = maxOf(0, index - 18); val end = minOf(window.length, index + keyword.length + 18)
                    val context = window.substring(start, end).replace('\n', ' ').trim()
                    result += Hit(base + index, (if (base + start > 0) "…" else "") + context + (if (!eof || end < window.length) "…" else ""))
                    from = base + index + keyword.length; next = from - base
                }
                if (result.size >= maxHits) break
                if (eof) { complete = true; break }
                val drop = maxOf(0, limit - 18 + 1)
                carry = window.substring(drop); base += drop
            }
        }
        if (complete && building != null && signature == key(file)) save(cached, building)
        return result
    }
}
