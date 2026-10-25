package com.inkreader.parse

import com.inkreader.data.model.SplitMode
import com.inkreader.data.model.SplitOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

/** A source is always hashed again; stale provider timestamps cannot reuse the wrong chapter plan. */
class IndexedTxtStore(root: File) {
    // Each live repository owns a session. A later process/session removes abandoned files,
    // while concurrent repositories never delete each other's open indexes.
    private val directory: File = synchronized(liveSessions) {
        check(root.isDirectory || root.mkdirs())
        val active = liveSessions.values.toSet()
        root.listFiles()?.filter { it.isDirectory && it.name.matches(sessionName) && it !in active }
            ?.forEach { it.deleteRecursively() }
        File(root, "session-${UUID.randomUUID()}").also { check(it.mkdirs()); liveSessions[this] = it }
    }
    companion object {
        private val sessionName = Regex("session-[0-9a-f-]{36}")
        private val liveSessions = java.util.WeakHashMap<IndexedTxtStore, File>()
    }
    private data class Entry(val key: String, val index: IndexedText, val encoding: CharsetDetector.Result,
        val fingerprint: String, val options: SplitOptions, val sections: List<PreparedTxt.Section>, val plan: ChapterPlan)
    private val gate = ReentrantLock()
    private var cached: Entry? = null
    var cacheHits: Int = 0; private set

    fun <T> withDocument(name: String, options: SplitOptions, open: () -> InputStream, block: (PreparedTxt) -> T): T? {
        if (!name.endsWith(".txt", true) || options.mode == SplitMode.CUSTOM) return null
        gate.lockInterruptibly()
        try {
            check(directory.isDirectory || directory.mkdirs())
            val raw = File(directory, "raw-${UUID.randomUUID()}")
            var normalized: File? = null
            try {
                val sample = ByteArrayOutputStream(65536)
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                open().use { input -> raw.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        ParseCancellation.checkpoint()
                        val count = input.read(buffer); if (count < 0) break
                        size += count
                        if (size > 200L * 1024 * 1024) throw ParseException(ParseErrorCode.TOO_LARGE)
                        if (sample.size() < 65536) sample.write(buffer, 0, minOf(count, 65536 - sample.size()))
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                } }
                if (size == 0L) throw ParseException(ParseErrorCode.EMPTY)
                val head = sample.toByteArray()
                if (FormatDetector.detect(head.copyOf(minOf(4096, head.size)), name) != DocumentFormat.TXT) return null
                val fingerprint = digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
                val key = fingerprint + "|" + name + "|" + options.copy(rememberCorrections = false, useSavedCorrections = false)
                cached?.takeIf { it.key == key && it.index.file.isFile }?.let { entry ->
                    cacheHits++
                    return PreparedTxt(entry.index.reopen(), entry.encoding, fingerprint, options, entry.sections, entry.plan).use(block)
                }
                val validUtf8 = try {
                    java.io.InputStreamReader(raw.inputStream(), Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)).use { reader ->
                        val chars = CharArray(16384)
                        while (reader.read(chars) >= 0) ParseCancellation.checkpoint()
                    }
                    true
                } catch (_: CharacterCodingException) { false }
                val encoding = CharsetDetector.inspect(head, options.textEncoding, validUtf8)
                normalized = File(directory, "text-${UUID.randomUUID()}")
                val index = raw.inputStream().use { input ->
                    var skip = encoding.bomLength
                    while (skip-- > 0) check(input.read() >= 0)
                    input.reader(encoding.charset).use { IndexedText.build(it, normalized!!) }
                }
                if (index.length == 0) { index.close(); throw ParseException(ParseErrorCode.NO_CONTENT) }
                val document = PreparedTxt(index, encoding, fingerprint, options)
                val keep = normalized!!.length() <= 128L * 1024 * 1024
                try {
                    val result = block(document)
                    if (keep) {
                        cached?.index?.file?.delete()
                        cached = Entry(key, index, encoding, fingerprint, options, document.sections, document.plan)
                        normalized = null
                    }
                    return result
                } finally { document.close() }
            } finally { raw.delete(); normalized?.delete() }
        } finally { gate.unlock() }
    }
}
