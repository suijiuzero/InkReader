package com.inkreader.data.repo

import android.util.AtomicFile
import com.inkreader.data.file.FileStore
import com.inkreader.data.model.Chapter
import com.inkreader.parse.ChapterSplitter
import com.inkreader.parse.ParseErrorCode
import com.inkreader.parse.ParseException
import java.io.Closeable
import java.io.File
import java.io.Reader
import java.security.DigestOutputStream
import java.security.MessageDigest

/** 用与旧重分章相同的标题拼接规则逐段重建源文件，并记录原章节的源坐标。 */
class BookSource(val file: File, val starts: IntArray, val lengths: IntArray, val fingerprint: String) : Closeable {
    override fun close() { file.delete() }
    companion object {
        private fun checkpoint() { if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException() }
        private fun bodyStartsWith(file: File, title: String): Boolean = AtomicFile(file).openRead().bufferedReader().use { reader ->
            var c = reader.read(); var scanned = 0
            while (c >= 0 && c.toChar().isWhitespace()) { if (scanned++ and 16383 == 0) checkpoint(); c = reader.read() }
            for (i in title.indices) { if (c < 0 || c.toChar() != title[i]) return@use false; c = reader.read() }
            true
        }
        fun create(root: File, bookId: Long, chapters: List<Chapter>, files: FileStore): BookSource {
            check(root.isDirectory || root.mkdirs())
            val file = File.createTempFile("book-$bookId-", ".txt", root)
            val starts = IntArray(chapters.size); val lengths = IntArray(chapters.size)
            val atomic = AtomicFile(file); val output = atomic.startWrite()
            val digest = MessageDigest.getInstance("SHA-256")
            val writer = DigestOutputStream(output, digest).writer(Charsets.UTF_8)
            var position = 0
            fun append(text: String) { writer.write(text); position += text.length }
            try {
                for ((i, chapter) in chapters.withIndex()) {
                    checkpoint()
                    if (position > 0) append("\n\n")
                    val title = chapter.title.trim()
                    if (title.isNotEmpty() && !ChapterSplitter.isFallbackSectionTitle(title) && !bodyStartsWith(files.file(chapter.fileName), title)) append(title + "\n\n")
                    starts[i] = position
                    AtomicFile(files.file(chapter.fileName)).openRead().bufferedReader().use { reader ->
                        val buffer = CharArray(16384)
                        while (true) { checkpoint(); val n = reader.read(buffer); if (n < 0) break
                            writer.write(buffer, 0, n); position += n
                            if (position > 200000000) throw ParseException(ParseErrorCode.TOO_LARGE)
                        }
                    }
                    lengths[i] = position - starts[i]
                }
                writer.flush(); atomic.finishWrite(output)
                return BookSource(file, starts, lengths, digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) })
            } catch (t: Throwable) { atomic.failWrite(output); file.delete(); throw t }
        }
    }
}
