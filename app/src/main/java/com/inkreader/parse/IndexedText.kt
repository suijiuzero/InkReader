package com.inkreader.parse

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.io.Reader
import java.util.LinkedHashMap

/** Normalized UTF-16 on disk, with primitive line indexes and a small page cache. */
class IndexedText private constructor(
    val file: File, val starts: IntArray, val ends: IntArray, val indents: IntArray, val length: Int
) : Closeable {
    private val input = RandomAccessFile(file, "r")
    private val pages = object : LinkedHashMap<Int, CharArray>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CharArray>?) = size > 16
    }
    fun read(start: Int, end: Int): String {
        require(start in 0..end && end <= length)
        val result = CharArray(end - start)
        var position = start
        while (position < end) {
            val page = position / 8192
            val chars = pages.getOrPut(page) {
                val count = minOf(8192, length - page * 8192)
                val bytes = ByteArray(count * 2)
                input.seek(page * 16384L); input.readFully(bytes)
                CharArray(count) { i -> ((bytes[i * 2].toInt() and 255) or ((bytes[i * 2 + 1].toInt() and 255) shl 8)).toChar() }
            }
            val offset = position % 8192
            val count = minOf(end - position, chars.size - offset)
            chars.copyInto(result, position - start, offset, offset + count)
            position += count
        }
        return String(result)
    }
    val lines: List<String> = object : AbstractList<String>(), SizedLines {
        override val size get() = starts.size
        override fun get(index: Int): String {
            val end = minOf(ends[index], starts[index] + 256)
            val head = read(starts[index], end)
            return if (end < ends[index] && head.isBlank()) head + "\uFFFC" else head
        }
        override fun characters(index: Int, trim: Boolean) = ends[index] - starts[index] - if (trim) indents[index] else 0
        override fun fence(index: Int): FenceInfo? {
            val start = starts[index] + indents[index]
            if (start == ends[index]) return null
            val marker = read(start, minOf(start + 1, ends[index])).first()
            if (marker != '`' && marker != '~') return null
            var position = start
            while (position < ends[index]) {
                val chunk = read(position, minOf(position + 256, ends[index]))
                val length = chunk.takeWhile { it == marker }.length
                position += length
                if (length < chunk.length) return FenceInfo(marker, position - start, false)
            }
            return FenceInfo(marker, position - start, true)
        }
    }
    fun trimmedLines(start: Int, end: Int): IntRange? {
        var a = start; var b = end
        while (a < b && starts[a] == ends[a]) a++
        while (b > a && starts[b - 1] == ends[b - 1]) b--
        return if (a == b) null else (starts[a] + indents[a]) until ends[b - 1]
    }
    fun reopen() = IndexedText(file, starts, ends, indents, length)
    fun reader(start: Int = 0, end: Int = length): Reader {
        require(start in 0..end && end <= length)
        return object : Reader() {
            var position = start
            override fun read(buffer: CharArray, offset: Int, count: Int): Int {
                if (count == 0) return 0
                ParseCancellation.checkpoint()
                val n = minOf(count, end - position); if (n <= 0) return -1
                this@IndexedText.read(position, position + n).toCharArray(buffer, offset, 0, n); position += n; return n
            }
            override fun close() = Unit
        }
    }
    fun trimmedRange(start: Int, end: Int): IntRange? {
        var a = start; var b = end
        while (a < b) {
            ParseCancellation.checkpoint()
            val head = read(a, minOf(a + 1024, b)); val skip = head.takeWhile { it.isWhitespace() }.length
            a += skip; if (skip < head.length) break
        }
        while (b > a) {
            val tail = read(maxOf(a, b - 1024), b); val skip = tail.takeLastWhile { it.isWhitespace() }.length
            b -= skip; if (skip < tail.length) break
        }
        return if (a == b) null else a until b
    }
    override fun close() { pages.clear(); input.close() }

    companion object {
        fun build(reader: Reader, file: File, anchors: NormalizationAnchors? = null, removeWatermarks: Boolean = false): IndexedText {
            var starts = IntArray(1024); var ends = IntArray(1024); var indents = IntArray(1024); var count = 0
            fun line(start: Int, end: Int, indent: Int) {
                // Primitive indexes are bounded too: pathological one-character lines must not exhaust the heap.
                if (count >= 500000) throw ParseException(ParseErrorCode.TOO_LARGE)
                if (count == starts.size) { starts = starts.copyOf(count * 2); ends = ends.copyOf(count * 2); indents = indents.copyOf(count * 2) }
                starts[count] = start; ends[count] = end; indents[count++] = indent
            }
            val writer = BufferedChars(file)
            var seen = false; var pendingBlank = false; var active = false
            var before = 0; var contentStart = 0; var first = -1; var last = 0
            fun begin() {
                if (active) return
                before = writer.position
                if (seen) { writer.append('\n'); if (pendingBlank) writer.append('\n') }
                contentStart = writer.position; first = -1; last = contentStart; active = true
            }
            fun finish() {
                if (!active) begin()
                val watermark = removeWatermarks && first >= 0 && last - first <= 90 &&
                    ChapterSplitter.cleanBodyWatermarks(writer.slice(first, last)).isEmpty()
                if (first < 0 || watermark) {
                    writer.truncate(before)
                    if (!watermark) pendingBlank = seen
                    anchors?.finishLine(before, before)
                }
                else {
                    writer.truncate(last)
                    anchors?.finishLine(contentStart, last)
                    if (seen && pendingBlank) line(before + 1, before + 1, 0)
                    line(contentStart, last, first - contentStart)
                    seen = true; pendingBlank = false
                }
                active = false
            }
            try {
                val buffer = CharArray(16384); var previousCr = false; var sourcePosition = 0
                while (true) {
                    ParseCancellation.checkpoint()
                    val n = reader.read(buffer); if (n < 0) break
                    for (i in 0 until n) {
                        anchors?.record(sourcePosition, writer.position); sourcePosition++
                        var c = buffer[i]
                        if (c == '\n' && previousCr) { previousCr = false; continue }
                        if (c == '\r' || c == '\n') { finish(); previousCr = c == '\r'; continue }
                        previousCr = false
                        if (c == '\uFEFF' || c == '\u200B') continue
                        if (c == '\u00A0') c = ' '
                        begin()
                        if (!seen && first < 0 && c.isWhitespace()) continue
                        writer.append(c)
                        if (!c.isWhitespace()) { if (first < 0) first = writer.position - 1; last = writer.position }
                    }
                }
                finish()
                val length = writer.position
                anchors?.record(sourcePosition, length); anchors?.finish(length)
                writer.close()
                return IndexedText(file, starts.copyOf(count), ends.copyOf(count), indents.copyOf(count), length)
            } catch (t: Throwable) { writer.close(); file.delete(); throw t }
        }
    }
    private class BufferedChars(file: File) : Closeable {
        private val output = RandomAccessFile(file, "rw").apply { setLength(0) }
        private val buffer = ByteArray(65536)
        private var used = 0
        private var flushed = 0
        private var closed = false
        val position get() = flushed + used / 2
        fun append(c: Char) {
            if (used == buffer.size) flush()
            buffer[used++] = c.code.toByte(); buffer[used++] = (c.code ushr 8).toByte()
        }
        fun truncate(position: Int) {
            if (position >= flushed) used = (position - flushed) * 2
            else { used = 0; output.setLength(position * 2L); output.seek(position * 2L); flushed = position }
        }
        private fun flush() { output.write(buffer, 0, used); flushed += used / 2; used = 0 }
        fun slice(start: Int, end: Int): String {
            require(end - start <= 90)
            val chars = CharArray(end - start)
            var position = start
            if (position < flushed) {
                val n = minOf(end, flushed) - position; val bytes = ByteArray(n * 2)
                val previous = output.filePointer; output.seek(position * 2L); output.readFully(bytes); output.seek(previous)
                for (i in 0 until n) chars[i] = ((bytes[i * 2].toInt() and 255) or ((bytes[i * 2 + 1].toInt() and 255) shl 8)).toChar()
                position += n
            }
            while (position < end) {
                val offset = (position - flushed) * 2
                chars[position - start] = ((buffer[offset].toInt() and 255) or ((buffer[offset + 1].toInt() and 255) shl 8)).toChar(); position++
            }
            return String(chars)
        }
        override fun close() { if (!closed) { closed = true; try { flush() } finally { output.close() } } }
    }
}

data class FenceInfo(val marker: Char, val length: Int, val onlyMarker: Boolean)
interface SizedLines {
    fun characters(index: Int, trim: Boolean = false): Int
    fun fence(index: Int): FenceInfo? = null
}
