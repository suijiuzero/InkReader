package com.inkreader.parse

import com.inkreader.data.model.SplitMode
import com.inkreader.data.model.SplitOptions
import java.io.Closeable
import java.io.Reader

/** Chapter bodies are ranges, not retained Strings. Consumers materialize at most one bounded chapter. */
class PreparedTxt(val text: IndexedText, val encoding: CharsetDetector.Result, val fingerprint: String,
    val options: SplitOptions, existing: List<Section>? = null, previousPlan: ChapterPlan? = null) : Closeable {
    data class Piece(val start: Int, val end: Int, val literal: String? = null) {
        val length get() = literal?.length ?: end - start
    }
    data class Section(val title: String, val pieces: List<Piece>, val volume: String = "", val chunked: Boolean = false,
        val children: List<Section>? = null, val includeHeadings: Boolean = false) {
        val length: Int get() = children?.let { entries -> entries.sumOf { it.length + if (includeHeadings && !ChapterSplitter.isFallbackSectionTitle(it.title)) it.title.length + 2 else 0 } +
            ((entries.size - 1).coerceAtLeast(0)) * 2 } ?: pieces.sumOf { it.length }
    }
    val plan: ChapterPlan = previousPlan ?: if (options.mode in listOf(SplitMode.PER_FILE, SplitMode.FIXED_SIZE))
        ChapterPlan(emptyList(), emptyList(), emptyList(), 0, false, false)
        else ChapterSplitter.plan(text.lines, text.length, options)
    val title: String? = TxtParser.detectTitle(text.lines.asSequence())
    val sections: List<Section> by lazy {
        val all = existing ?: assemble()
        val first = all.firstOrNull()
        if (!options.stripLeadingTitle || existing != null || first == null || title == null || first.length - title.length >= 60) all
        else {
            val content = readPieces(first.pieces, 0, first.length).trim()
            if (!content.startsWith(title)) all
            else {
                val rest = content.removePrefix(title).trim()
                if (rest.isEmpty()) all.drop(1).ifEmpty { all }
                else listOf(first.copy(pieces = listOf(literal(rest)))) + all.drop(1)
            }
        }
    }
    val analysis get() = ChapterAnalysis(emptyList(), plan.decisions, plan.gaps, plan.skippedTocEntries,
        plan.headings.isEmpty() && options.mode != SplitMode.PER_FILE || sections.any { ChapterSplitter.isFallbackSectionTitle(it.title) }, plan.truncated)

    fun reader(section: Section): Reader {
        if (section.children != null) {
            val sources = buildList<() -> Reader> {
                section.children.forEachIndexed { i, child ->
                    if (i > 0) add { java.io.StringReader("\n\n") }
                    if (section.includeHeadings && child.title.isNotBlank() && !ChapterSplitter.isFallbackSectionTitle(child.title))
                        add { java.io.StringReader(child.title + "\n\n") }
                    add { reader(child) }
                }
            }.iterator()
            return object : Reader() {
                var active: Reader? = null
                override fun read(buffer: CharArray, offset: Int, count: Int): Int {
                    if (count == 0) return 0
                    while (true) {
                        ParseCancellation.checkpoint()
                        if (active == null) { if (!sources.hasNext()) return -1; active = sources.next()() }
                        val n = active!!.read(buffer, offset, count); if (n >= 0) return n
                        active!!.close(); active = null
                    }
                }
                override fun close() { active?.close(); active = null }
            }
        }
        if (!options.cleanWatermark) return rawReader(section)
        if (section.length <= 120000) {
            val raw = rawReader(section).use { it.readText() }
            val clean = ChapterSplitter.cleanBodyWatermarks(raw)
            // Size splitting preserves every boundary newline. With no removed watermark,
            // cleaning an individual chunk must not trim a paragraph shared with the next chunk.
            if (section.chunked && clean == raw.trim()) return java.io.StringReader(raw)
            return java.io.StringReader(clean.ifEmpty { if (plan.headings.isNotEmpty()) section.title else "" })
        }
        val kept = ArrayList<Piece>(); var cursor = 0
        for (i in text.starts.indices) {
            val length = text.ends[i] - text.starts[i]
            if (length in 1..90 && ChapterSplitter.cleanBodyWatermarks(text.lines[i]).isEmpty()) {
                if (text.starts[i] > cursor) kept += Piece(cursor, text.starts[i])
                cursor = text.ends[i]
            }
        }
        if (cursor < text.length) kept += Piece(cursor, text.length)
        return object : Reader() {
            val source = rawReader(section.copy(pieces = kept)).buffered()
            var started = false; var pending = 0; var held = -1
            override fun read(buffer: CharArray, offset: Int, count: Int): Int {
                var written = 0
                while (written < count) {
                    if (held >= 0) {
                        if (pending > 0) { buffer[offset + written++] = '\n'; pending--; continue }
                        buffer[offset + written++] = held.toChar(); held = -1; continue
                    }
                    val value = source.read(); if (value < 0) return if (written == 0) -1 else written
                    val c = value.toChar()
                    if (!started && c.isWhitespace()) continue
                    if (c == '\n') { pending = minOf(2, pending + 1); continue }
                    started = true; held = value
                }
                return written
            }
            override fun close() = source.close()
        }
    }
    private fun rawReader(section: Section): Reader = object : Reader() {
        var pieceIndex = 0; var offset = 0
        override fun read(buffer: CharArray, start: Int, count: Int): Int {
            if (count == 0) return 0
            while (pieceIndex < section.pieces.size) {
                val piece = section.pieces[pieceIndex]
                if (offset == piece.length) { pieceIndex++; offset = 0; continue }
                val n = minOf(count, piece.length - offset)
                val value = piece.literal?.substring(offset, offset + n) ?: text.read(piece.start + offset, piece.start + offset + n)
                value.toCharArray(buffer, start, 0, n); offset += n
                return n
            }
            return -1
        }
        override fun close() = Unit
    }
    fun body(section: Section): String = reader(section).use { it.readText() }
    fun readRange(section: Section, start: Int, end: Int): String {
        require(start >= 0 && end >= start && end <= section.length && end - start <= 120000)
        return readPieces(section.pieces, start, end)
    }
    /** 合并/追加仍按原语义每个文件判断分章；正文用读取器拼接，不拼整本字符串。 */
    fun units(fileName: String): List<Section> {
        val fileTitle = ChapterSplitter.titleFromFileName(fileName)
        if (options.mode == SplitMode.PER_FILE) return sections.map { it.copy(title = if (options.cleanWatermark) ChapterSplitter.cleanChapterTitle(fileTitle) else fileTitle) }
        if (options.mode != SplitMode.SMART || sections.size >= 2 && sections.mapNotNull { ChapterSplitter.chapterLevelOrderFromTitle(it.title) }.distinct().size >= 2)
            return sections
        val only = sections.firstOrNull() ?: return emptyList()
        val title = if (ChapterSplitter.chapterOrderFromTitle(fileTitle) != null) fileTitle else only.title.ifBlank { fileTitle }
        return listOf(Section(if (options.cleanWatermark) ChapterSplitter.cleanChapterTitle(title) else title,
            if (sections.size == 1) only.pieces else emptyList(), only.volume,
            children = if (sections.size > 1) sections else null, includeHeadings = sections.size > 1))
    }
    fun normalizedReader(section: Section): Reader {
        if (section.length <= 120000) return java.io.StringReader(ChapterSplitter.normalize(body(section)))
        val file = java.io.File.createTempFile("body-", ".index", text.file.parentFile)
        val index = try { reader(section).use { IndexedText.build(it, file) } } catch (t: Throwable) { file.delete(); throw t }
        return object : Reader() {
            var position = 0
            override fun read(buffer: CharArray, offset: Int, count: Int): Int {
                if (count == 0) return 0
                val n = minOf(count, index.length - position); if (n <= 0) return -1
                index.read(position, position + n).toCharArray(buffer, offset, 0, n); position += n; return n
            }
            override fun close() { index.close(); file.delete() }
        }
    }
    private fun literal(value: String) = Piece(0, 0, value)
    private fun range(first: Int, end: Int): Piece? = text.trimmedLines(first, end)?.let { Piece(it.first, it.last + 1) }
    private fun joined(parts: List<Piece>, separator: String = "\n\n"): List<Piece> = buildList {
        parts.filter { it.length > 0 }.forEach { if (isNotEmpty()) add(literal(separator)); add(it) }
    }
    private fun assemble(): List<Section> {
        if (text.length == 0) return emptyList()
        if (options.mode == SplitMode.PER_FILE) return listOf(Section("正文", listOf(Piece(0, text.length))))
        if (options.mode == SplitMode.FIXED_SIZE || plan.headings.isEmpty())
            return chunks(Section("正文", listOf(Piece(0, text.length))), if (options.mode == SplitMode.FIXED_SIZE) options.fixedChunkChars.coerceIn(2000, 50000) else 18000, false)
        val result = ArrayList<Section>()
        val head = range(0, plan.headings.first())
        var volume = ""
        for (i in 0 until plan.headings.first()) ChapterSplitter.extractVolumeTitle(text.lines[i])?.let { volume = it }
        if (head != null && head.length >= 200 && !plan.mergeLeadingBody) {
            val first = text.lines.firstOrNull { it.isNotBlank() }.orEmpty().trim()
            result += Section(first.takeIf { it.length <= 20 } ?: "前言", listOf(head), volume)
        }
        var carry = emptyList<Piece>()
        for ((position, start) in plan.headings.withIndex()) {
            ParseCancellation.checkpoint()
            val end = plan.headings.getOrNull(position + 1) ?: text.lines.size
            var stop = end
            val peeled = ArrayList<Int>()
            if (position < plan.headings.lastIndex) {
                while (stop > start + 1) {
                    val value = text.lines[stop - 1].trim()
                    if (value.isBlank()) { stop--; continue }
                    if (ChapterSplitter.extractVolumeTitle(value) == null) break
                    peeled.add(0, --stop)
                }
                if (peeled.isEmpty() || stop == start + 1) { stop = end; peeled.clear() }
            }
            val rawTitle = text.lines[start].trim()
            ChapterSplitter.extractVolumeTitle(rawTitle)?.let { volume = it }
            carry.forEach { ChapterSplitter.extractVolumeTitle(text.read(it.start, it.end))?.let { value -> volume = value } }
            val pieces = ArrayList<Piece>()
            if (position == 0 && head != null && (head.length < 200 || plan.mergeLeadingBody)) pieces += head
            if (carry.isNotEmpty()) {
                if (pieces.isNotEmpty()) pieces += literal("\n\n")
                pieces += joined(carry, "\n")
            }
            val prefix = if (carry.isNotEmpty()) pieces.toList() else joined(pieces)
            pieces.clear()
            pieces += prefix
            range(start + 1, stop)?.let { if (pieces.isNotEmpty()) pieces += literal("\n\n"); pieces += it }
            if (pieces.isEmpty()) pieces += literal(rawTitle)
            val section = Section(if (options.cleanWatermark) ChapterSplitter.cleanChapterTitle(rawTitle) else rawTitle, pieces, volume)
            if (section.length > 120000) result += chunks(section, 18000, true) else result += section
            carry = peeled.map { Piece(text.starts[it], text.ends[it]) }
        }
        return result
    }
    private fun chunks(section: Section, size: Int, named: Boolean): List<Section> {
        if (section.length <= size) return listOf(section)
        val result = ArrayList<Section>(); var position = 0
        while (position < section.length) {
            ParseCancellation.checkpoint()
            val hard = minOf(position + size, section.length)
            val window = readPieces(section.pieces, position, minOf(hard + 1, section.length))
            var count = hard - position
            if (hard < section.length) {
                val paragraph = window.lastIndexOf("\n\n", count - 1)
                val line = window.lastIndexOf('\n', count - 1)
                count = when { paragraph >= size / 2 -> minOf(paragraph + 2, count); line >= size / 2 -> line + 1; else -> count }
                if (count > 0 && Character.isHighSurrogate(window[count - 1]) && Character.isLowSurrogate(window[count])) count--
            }
            val title = if (named) if (result.isEmpty()) section.title else "${section.title}（${result.size + 1}）" else "第 ${result.size + 1} 节"
            result += Section(title, slicePieces(section.pieces, position, position + count), section.volume, chunked = true)
            position += count
        }
        return result
    }
    private fun slicePieces(pieces: List<Piece>, start: Int, end: Int): List<Piece> {
        var cursor = 0
        return pieces.mapNotNull { piece ->
            val a = maxOf(start - cursor, 0); val b = minOf(end - cursor, piece.length); cursor += piece.length
            if (b <= a) null else if (piece.literal != null) literal(piece.literal.substring(a, b)) else Piece(piece.start + a, piece.start + b)
        }
    }
    private fun readPieces(pieces: List<Piece>, start: Int, end: Int) = slicePieces(pieces, start, end).joinToString("") { it.literal ?: text.read(it.start, it.end) }
    override fun close() = text.close()
}
