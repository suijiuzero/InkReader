package com.inkreader.data.repo

import android.content.Context
import com.inkreader.data.db.InkDb
import com.inkreader.data.file.FileStore
import com.inkreader.data.model.*
import com.inkreader.parse.*
import java.io.File

/** 大书只存行索引与实际批注/样式端点；正文和归一化均在磁盘上处理。 */
class StreamingResplit(private val context: Context, private val db: InkDb,
    private val files: FileStore, private val corrections: ChapterCorrectionStore) {
    private fun checkpoint() = ParseCancellation.checkpoint()
    private class Points {
        var values = IntArray(256); var size = 0
        fun add(value: Int) {
            require(size < 1000000) { "Too many annotation endpoints" }
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }
        fun array() = values.copyOf(size)
    }
    fun run(book: Book, chapters: List<Chapter>, options: SplitOptions, progress: (Float) -> Unit): Int {
        val root = File(context.cacheDir, "resplit-source").apply { mkdirs() }
        val temporary = ArrayList<File>()
        try { BookSource.create(root, book.id, chapters, files).use { source ->
            require(options.sourceFingerprint.isEmpty() || options.sourceFingerprint == source.fingerprint) { "Source changed; reopen recognition center" }
            progress(.08f)
            val oldMarks = db.marksOf(book.id)
            val rich = chapters.mapIndexed { i, chapter ->
                val file = files.file(files.richNameOf(chapter.fileName))
                require(file.length() <= 16L * 1024 * 1024) { "Rich text metadata too large" }
                RichChapter.decode(files.readRich(chapter.fileName))?.takeIf { it.len == source.lengths[i] }
            }
            fun raw(chapter: Int, offset: Int): Int {
                val i = chapter.coerceIn(0, chapters.lastIndex)
                return source.starts[i] + offset.coerceIn(0, source.lengths[i])
            }
            val points = Points()
            oldMarks.forEach { points.add(raw(it.chapterIndex, it.startOffset)); points.add(raw(it.chapterIndex, it.endOffset)) }
            points.add(raw(book.lastChapterIndex, book.lastCharOffset))
            rich.forEachIndexed { i, r ->
                r?.blocks?.forEach { points.add(raw(i, it.start)); points.add(raw(i, it.end)) }
                r?.spans?.forEach { points.add(raw(i, it.start)); points.add(raw(i, it.end)) }
                r?.links?.forEach { points.add(raw(i, it.start)); points.add(raw(i, it.end)) }
            }
            val originalPoints = points.array()
            val firstMap = NormalizationAnchors(originalPoints)
            fun temp() = File.createTempFile("resplit-", ".index", root).also { temporary += it }
            source.file.reader(Charsets.UTF_8).use { IndexedText.build(it, temp(), firstMap) }.use { original ->
                val memory = if (options.useSavedCorrections) ChapterCorrections.resolve(original.lines, corrections.load(book.id)) else null
                val effective = options.copy(stripLeadingTitle = false,
                    ignoredHeadingLines = (memory?.ignored.orEmpty() - options.forcedHeadingLines) + options.ignoredHeadingLines,
                    forcedHeadingLines = (memory?.confirmed.orEmpty() - options.ignoredHeadingLines) + options.forcedHeadingLines)
                val document = PreparedTxt(original, CharsetDetector.Result(Charsets.UTF_8, 0), source.fingerprint, effective)
                val rawSections = document.sections
                if (rawSections.isEmpty()) return chapters.size
                progress(.3f)
                val cleanedPoints = Points()
                originalPoints.forEach { cleanedPoints.add(firstMap.map(it)) }
                rawSections.forEach { it.pieces.forEach { piece -> if (piece.literal == null) { cleanedPoints.add(piece.start); cleanedPoints.add(piece.end) } } }
                val secondMap = if (options.cleanWatermark) NormalizationAnchors(cleanedPoints.array()) else null
                val clean = if (secondMap != null) original.reader().use { IndexedText.build(it, temp(), secondMap, true) } else original.reopen()
                clean.use {
                    fun normalized(position: Int) = secondMap?.map(position) ?: position
                    fun mapped(chapter: Int, offset: Int) = normalized(firstMap.map(raw(chapter, offset)))
                    val sections = rawSections.map { section ->
                        val pieces = section.pieces.mapNotNull { piece ->
                            if (piece.literal != null) piece else {
                                val a = normalized(piece.start); val b = normalized(piece.end)
                                val range = if (options.cleanWatermark && !section.chunked) clean.trimmedRange(a, b) else a until b
                                range?.takeIf { !it.isEmpty() }?.let { PreparedTxt.Piece(it.first, it.last + 1) }
                            }
                        }.toMutableList()
                        if (options.cleanWatermark) {
                            while (pieces.firstOrNull()?.literal?.isBlank() == true) pieces.removeAt(0)
                            while (pieces.lastOrNull()?.literal?.isBlank() == true) pieces.removeAt(pieces.lastIndex)
                        }
                        if (pieces.isEmpty()) pieces += PreparedTxt.Piece(0, 0, section.title)
                        section.copy(title = if (options.mode == SplitMode.PER_FILE) book.title else section.title, pieces = pieces)
                    }
                    val output = PreparedTxt(clean, document.encoding, source.fingerprint,
                        effective.copy(cleanWatermark = false), sections, document.plan)
                    val origins = OutputOrigins(sections)
                    val blocks = List(sections.size) { ArrayList<RichBlock>() }
                    val styles = List(sections.size) { ArrayList<InlineStyle>() }
                    val links = List(sections.size) { ArrayList<LinkSpan>() }
                    rich.forEachIndexed { i, r ->
                        checkpoint()
                        r?.blocks?.forEach { block -> origins.intersections(mapped(i, block.start), mapped(i, block.end)).forEach {
                            blocks[it.chapter] += block.copy(start = it.start, end = it.end)
                        } }
                        r?.spans?.forEach { span -> origins.intersections(mapped(i, span.start), mapped(i, span.end)).forEach {
                            styles[it.chapter] += span.copy(start = it.start, end = it.end)
                        } }
                        r?.links?.forEach { span -> origins.intersections(mapped(i, span.start), mapped(i, span.end)).forEach {
                            links[it.chapter] += span.copy(start = it.start, end = it.end)
                        } }
                    }
                    val hasRich = rich.any { it != null }
                    val staged = ArrayList<String>()
                    var committed = false
                    try {
                        sections.forEachIndexed { i, section ->
                            checkpoint(); progress(.35f + .5f * i / sections.size)
                            val name = files.newFileName(); staged += name
                            val count = output.reader(section).use { files.write(name, it) }
                            check(count == section.length)
                            if (hasRich) {
                                // 样式块之外新增的标题/段落也必须覆盖，防止分页器整章退回纯文本。
                                val complete = ArrayList<RichBlock>(); var cursor = 0
                                fun gap(end: Int) {
                                    val start = cursor; var nonBlank = false
                                    while (cursor < end) {
                                        checkpoint()
                                        val next = minOf(cursor + 8192, end)
                                        if (!nonBlank && output.readRange(section, cursor, next).isNotBlank()) nonBlank = true
                                        cursor = next
                                    }
                                    if (nonBlank) complete += RichBlock(RichKind.BODY, 0, "", start, end)
                                }
                                for (block in blocks[i].sortedBy { it.start }) {
                                    if (block.start < cursor || block.end <= block.start) continue
                                    gap(block.start); complete += block; cursor = block.end
                                }
                                gap(section.length)
                                files.writeRich(name, RichChapter(section.length, complete, styles[i], links[i]).encode())
                            }
                        }
                        db.inTransaction {
                            checkpoint()
                            db.writableDatabase.delete("chapters", "book_id=?", arrayOf(book.id.toString()))
                            var total = 0L
                            sections.forEachIndexed { i, section ->
                                check(db.insertChapter(Chapter(bookId = book.id, indexInBook = i, title = section.title,
                                    fileName = staged[i], charCount = section.length, sourceName = book.title, volume = section.volume)) > 0)
                                total += section.length
                            }
                            require(total <= Int.MAX_VALUE)
                            db.updateBookStats(book.id, sections.size, total.toInt())
                            oldMarks.forEach { mark ->
                                checkpoint()
                                val a = mapped(mark.chapterIndex, mark.startOffset)
                                val b = mapped(mark.chapterIndex, mark.endOffset)
                                val ranges = if (b > a) origins.intersections(a, b) else emptyList()
                                if (ranges.isEmpty()) {
                                    val (chapter, offset) = origins.locate(a)
                                    db.updateMark(mark.copy(chapterIndex = chapter, startOffset = offset, endOffset = offset))
                                } else ranges.forEachIndexed { index, range ->
                                    val moved = mark.copy(chapterIndex = range.chapter, startOffset = range.start, endOffset = range.end)
                                    if (index == 0) db.updateMark(moved) else check(db.insertMark(moved.copy(id = 0)) > 0)
                                }
                            }
                            val (chapter, offset) = origins.locate(mapped(book.lastChapterIndex, book.lastCharOffset))
                            db.updateProgress(book.id, chapter, offset, book.progress)
                            checkpoint()
                        }
                        committed = true
                        chapters.forEach { files.deleteChapter(it.fileName) }
                        if (options.rememberCorrections && (options.ignoredHeadingLines.isNotEmpty() || options.forcedHeadingLines.isNotEmpty())) runCatching { corrections.save(book.id, source.fingerprint,
                            ChapterCorrections.captureChoices(original.lines, effective.ignoredHeadingLines, effective.forcedHeadingLines)) }
                        progress(1f)
                        return sections.size
                    } finally { if (!committed) staged.forEach { files.deleteChapter(it) } }
                }
            }
        } } finally { temporary.forEach { it.delete() } }
    }
}
