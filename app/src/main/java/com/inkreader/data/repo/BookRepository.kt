package com.inkreader.data.repo

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.inkreader.R
import com.inkreader.data.db.InkDb
import com.inkreader.data.file.FileStore
import com.inkreader.data.model.Book
import com.inkreader.data.model.BookGroup
import com.inkreader.data.model.Chapter
import com.inkreader.data.model.ImportMode
import com.inkreader.data.model.ImportOrder
import com.inkreader.data.model.Mark
import com.inkreader.data.model.MarkType
import com.inkreader.data.model.SearchHit
import com.inkreader.data.model.ShelfSort
import com.inkreader.data.model.SourceType
import com.inkreader.data.model.SplitMode
import com.inkreader.data.model.SplitOptions
import com.inkreader.parse.ChapterSplitter
import com.inkreader.parse.CharsetDetector
import com.inkreader.parse.DocumentFormat
import com.inkreader.parse.DocumentParsers
import com.inkreader.parse.ParseErrorCode
import com.inkreader.parse.ParseErrors
import com.inkreader.parse.ParseException
import com.inkreader.parse.RichChapter
import com.inkreader.parse.RichBlock
import com.inkreader.parse.RichKind
import com.inkreader.parse.RichTransforms
import com.inkreader.parse.RichMarkdown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException

/**
 * 书籍仓库：负责导入解析、预扫描预览、增删改查、章节合并/拆分（批注无损迁移）、整书重分章、全文搜索。
 * 所有耗时操作都在 IO 线程执行，UI 只拿结果。
 */
class BookRepository(context: Context) {

    private val appContext = context.applicationContext
    val db = InkDb(appContext)
    val files = FileStore(appContext)
    private val writes = BookWriteGate()
    private val progressClock = ProgressClock()
    fun newProgressToken(bookId: Long): Long = progressClock.next(bookId)
    private suspend fun <T> bookWrite(bookId: Long, block: suspend () -> T): T =
        withContext(Dispatchers.IO) { writes.write(bookId) { progressClock.next(bookId); block() } }

    private fun chapterBook(id: Long): Long? = db.readableDatabase.rawQuery(
        "SELECT book_id FROM chapters WHERE id=?", arrayOf(id.toString())
    ).use { if (it.moveToFirst()) it.getLong(0) else null }
    private fun markBook(id: Long): Long? = db.readableDatabase.rawQuery(
        "SELECT book_id FROM marks WHERE id=?", arrayOf(id.toString())
    ).use { if (it.moveToFirst()) it.getLong(0) else null }


    /** 单个文件的导入结果：批量导入时要逐个说清楚「成没成、为什么没成」 */
    data class ImportFileResult(
        val name: String,
        val ok: Boolean,
        val reason: String = "",
        val bookId: Long = 0,
        val chapterCount: Int = 0
    )

    data class ImportResult(
        val bookIds: List<Long> = emptyList(),
        val files: List<ImportFileResult> = emptyList()
    ) {
        val failed: List<ImportFileResult> get() = files.filter { !it.ok }
        val succeeded: List<ImportFileResult> get() = files.filter { it.ok }

        /** 「文件名：原因」形式的失败清单 */
        fun errors(context: Context): List<String> =
            failed.map { context.getString(R.string.err_file_reason, it.name, it.reason) }
    }

    /**
     * 导入前实时预分章预览数据：
     * 在 ImportDialog 中展示预计拆分的章数、识别到的卷数及前几章标题预览。
     */
    data class ImportPreview(
        val estimatedChapters: Int,
        val volumeCount: Int,
        val sampleTitles: List<String>,
        val sampleVolumes: List<String> = emptyList()
    )

    // ==================== 导入前实时预分章预览 ====================

    suspend fun previewImport(
        uris: List<Uri>,
        mode: ImportMode,
        options: SplitOptions,
        fileOrder: ImportOrder = ImportOrder.AUTO
    ): ImportPreview = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext ImportPreview(0, 0, emptyList())
        val ordered = if (fileOrder == ImportOrder.AUTO && uris.size > 1) orderByFileName(uris) else uris
        val sampleTitles = ArrayList<String>()
        val volumes = LinkedHashSet<String>()
        var totalChapters = 0

        // 预览最多采样前 5 个文件，兼顾响应速度与准确性
        val inspectUris = ordered.take(5)
        for ((i, uri) in inspectUris.withIndex()) {
            val name = displayName(uri) ?: appContext.getString(R.string.repo_fallback_file_n, i + 1)
            val fileTitle = ChapterSplitter.titleFromFileName(name)
            currentCoroutineContext().ensureActive()
            val bytes = try { readBytes(uri, currentCoroutineContext()) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null } ?: continue
            val parsed = try { parseFile(bytes, name, options) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null } ?: continue
            currentCoroutineContext().ensureActive()
            // 预览必须和真正导入走同一套。单文件「各自成书」时 importSingle 用的是解析器原始分章
            //（只有 PER_FILE 才整篇成一章），多文件 / 合并导入才走 splitUnitsWithVolume。
            // 以前这两个分支写重了（effectiveMode 恒等于 options.mode），预览报的章数和导入结果对不上。
            val units = if (mode == ImportMode.SEPARATE && ordered.size == 1 &&
                options.mode != SplitMode.PER_FILE
            ) {
                parsed.parts.map { ChapterSplitter.Part(it.title, it.content, it.volume) }
            } else {
                splitUnitsWithVolume(parsed, fileTitle, options)
            }
            totalChapters += units.size
            for (u in units) {
                if (u.volume.isNotBlank()) volumes += u.volume
                if (sampleTitles.size < 4) {
                    val label = if (u.volume.isNotBlank()) "[${u.volume}] ${u.title}" else u.title
                    sampleTitles += label
                }
            }
        }
        if (ordered.size > inspectUris.size && inspectUris.isNotEmpty()) {
            val avg = (totalChapters.toFloat() / inspectUris.size).coerceAtLeast(1f)
            totalChapters = (avg * ordered.size).toInt()
        }
        ImportPreview(
            estimatedChapters = totalChapters.coerceAtLeast(1),
            volumeCount = volumes.size,
            sampleTitles = sampleTitles,
            sampleVolumes = volumes.take(3)
        )
    }

    // ==================== 导入 ====================

    suspend fun importDocuments(
        uris: List<Uri>,
        mode: ImportMode,
        customTitle: String?,
        splitMode: SplitMode = SplitMode.SMART,
        splitOptions: SplitOptions = SplitOptions(mode = splitMode),
        fileOrder: ImportOrder = ImportOrder.AUTO,
        onProgress: (stage: String, fraction: Float) -> Unit
    ): ImportResult = withContext(Dispatchers.IO) {
        val cancellation = currentCoroutineContext()
        cancellation.ensureActive()
        val ids = ArrayList<Long>()
        val results = ArrayList<ImportFileResult>()
        val ordered = if (fileOrder == ImportOrder.AUTO && uris.size > 1) orderByFileName(uris) else uris
        val total = ordered.size.coerceAtLeast(1)

        if (mode == ImportMode.MERGE && ordered.size > 1) {
            val title = customTitle?.takeIf { it.isNotBlank() }
                ?: guessMergedTitle(ordered.mapNotNull { displayName(it) })
            onProgress(appContext.getString(R.string.repo_stage_prepare_merge), 0.02f)
            cancellation.ensureActive()

            val hue = hueFromString(title)
            val bookId = db.insertBook(
                Book(title = title, sourceType = SourceType.MERGED, coverHue = hue, createdAt = now())
            )
            check(bookId > 0) { "insertBook failed" }
            ids += bookId

            var index = 0
            var totalChars = 0
            try {
            for ((i, uri) in ordered.withIndex()) {
                val name = displayName(uri) ?: appContext.getString(R.string.repo_fallback_file_n, i + 1)
                onProgress(appContext.getString(R.string.repo_stage_parse, name), (i.toFloat() / total) * 0.95f + 0.02f)
                cancellation.ensureActive()
                val bytes = try {
                    readBytes(uri, cancellation)
                } catch (e: ParseException) {
                    results += ImportFileResult(name, false, ParseErrors.reason(appContext, e))
                    continue
                }
                if (bytes == null) {
                    results += ImportFileResult(name, false, ParseErrors.reason(appContext, ParseErrorCode.READ_FAILED))
                    continue
                }
                val parsed = try {
                    parseFile(bytes, name, splitOptions)
                } catch (e: ParseException) {
                    results += ImportFileResult(name, false, ParseErrors.reason(appContext, e))
                    continue
                }
                val fileTitle = ChapterSplitter.titleFromFileName(name)
                val units = splitUnitsWithVolume(parsed, fileTitle, splitOptions)
                var fileAdded = 0
                val staged = ArrayList<String>()
                val previousIndex = index
                val previousChars = totalChars
                try {
                db.inTransaction {
                for (u in units) {
                    cancellation.ensureActive()
                    val body = prepareBody(parsed.sourceType, u.content)
                    val fileName = files.newFileName()
                    staged += fileName
                    writeChapter(fileName, body)
                    val vol = ChapterSplitter.detectVolumeFromChapter(u.title, body.text, u.volume)
                    val newId = db.insertChapter(
                        Chapter(
                            bookId = bookId,
                            indexInBook = index,
                            title = u.title,
                            fileName = fileName,
                            charCount = body.text.length,
                            sourceName = name,
                            volume = vol
                        )
                    )
                    check(newId > 0) { "insertChapter failed" }
                    index++
                    fileAdded++
                    totalChars += body.text.length
                }
                db.updateBookStats(bookId, index, totalChars)
                }
                } catch (e: Exception) {
                    staged.forEach { files.deleteChapter(it) }
                    index = previousIndex
                    totalChars = previousChars
                    if (e is CancellationException) throw e
                    results += ImportFileResult(name, false, e.localizedMessage ?: appContext.getString(R.string.action_unknown_error))
                    continue
                }
                results += ImportFileResult(name, true, bookId = bookId, chapterCount = fileAdded)
            }
            if (index == 0) {
                db.deleteBook(bookId).forEach { files.deleteChapter(it) }
                ids.clear()
                if (results.none { !it.ok }) {
                    results += ImportFileResult(title, false, appContext.getString(R.string.repo_no_content))
                }
            } else {
                db.updateBookStats(bookId, index, totalChars)
                if (fileOrder == ImportOrder.AUTO && splitOptions.mode == SplitMode.SMART && index > 1) {
                    val namedFiles = ordered.asSequence().filter {
                        ChapterSplitter.chapterOrderFromName(displayName(it) ?: "") != null
                    }.take(2).count()
                    if (namedFiles < 2) {
                        onProgress(appContext.getString(R.string.repo_stage_sort), 0.97f)
                        autoSortChapters(bookId)
                    }
                }
            }
            onProgress(appContext.getString(R.string.repo_stage_done), 1f)
            return@withContext ImportResult(ids, results)
            } catch (cancelled: CancellationException) {
                // Earlier files committed atomically remain valid; never leave an empty merge shell.
                if (index == 0) db.deleteBook(bookId).forEach { files.deleteChapter(it) }
                throw cancelled
            }
        }

        // 各自成书（含单文件导入）
        for ((i, uri) in ordered.withIndex()) {
            val name = displayName(uri) ?: appContext.getString(R.string.repo_fallback_doc_n, i + 1)
            onProgress(appContext.getString(R.string.repo_stage_parse, name), (i.toFloat() / total) * 0.9f + 0.03f)
            currentCoroutineContext().ensureActive()
            val bytes = try {
                readBytes(uri, currentCoroutineContext())
            } catch (e: ParseException) {
                results += ImportFileResult(name, false, ParseErrors.reason(appContext, e))
                continue
            }
            if (bytes == null) {
                results += ImportFileResult(name, false, ParseErrors.reason(appContext, ParseErrorCode.READ_FAILED))
                continue
            }
            val before = ids.size
            val chapters = try {
                importSingle(bytes, name, customTitle, ids, splitOptions, cancellation)
            } catch (e: ParseException) {
                results += ImportFileResult(name, false, ParseErrors.reason(appContext, e))
                continue
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                results += ImportFileResult(
                    name, false,
                    appContext.getString(
                        R.string.repo_import_failed,
                        t.message ?: appContext.getString(R.string.action_unknown_error)
                    )
                )
                continue
            }
            results += ImportFileResult(
                name = name,
                ok = true,
                bookId = ids.lastOrNull() ?: 0L,
                chapterCount = chapters
            )
            if (fileOrder == ImportOrder.AUTO && ids.size > before && splitOptions.mode == SplitMode.SMART) {
                onProgress(appContext.getString(R.string.repo_stage_sort), (i.toFloat() / total) * 0.9f + 0.08f)
                autoSortChapters(ids.last())
            }
        }
        onProgress(appContext.getString(R.string.repo_stage_done), 1f)
        ImportResult(ids, results)
    }

    /** 单个文件的解析结果 */
    private data class FileParse(
        val parts: List<ChapterSplitter.Part>,
        val metaTitle: String?,
        val author: String,
        val sourceType: SourceType
    )

    /** 章节正文定稿：正文（纯文本）+ 可选的样式覆盖 */
    private class ChapterBody(val text: String, val rich: RichChapter?)

    private fun prepareBody(sourceType: SourceType, content: String): ChapterBody =
        if (sourceType == SourceType.MD) {
            val rendered = RichMarkdown.render(content)
            ChapterBody(rendered.text, rendered.rich)
        } else {
            ChapterBody(content, null)
        }

    private fun writeChapter(fileName: String, body: ChapterBody) {
        files.write(fileName, body.text)
        val rich = body.rich
        if (rich != null) files.writeRich(fileName, rich.encode()) else files.deleteRich(fileName)
    }

    /**
     * 统一解析入口：交给 [DocumentParsers] 识别格式并分派解析器。
     * 当为纯文本 TXT 且用户指定了自定义切分策略 / 净网清洗时，直接按 [options] 切分。
     */
    private fun parseFile(
        bytes: ByteArray,
        name: String,
        options: SplitOptions = SplitOptions(mode = SplitMode.SMART, cleanWatermark = false)
    ): FileParse {
        val doc = DocumentParsers.parse(bytes, name)
        val sourceType = DocumentParsers.sourceTypeOf(doc.format)
        // 显式档位（强制按章 / 按编号 / 固定字数 / 自定义前缀）：**任何格式**都按提取出来的纯文本重切。
        // 以前这段只在 TXT 上生效 —— docx / epub / pdf 选了这些档位其实什么都不做，和界面承诺不符。
        val ruleMode = options.mode == SplitMode.STRICT_CHAP ||
            options.mode == SplitMode.NUMBERED ||
            options.mode == SplitMode.FIXED_SIZE ||
            options.mode == SplitMode.CUSTOM
        val parts = if (ruleMode) {
            val raw = if (doc.format == DocumentFormat.TXT) {
                CharsetDetector.decode(bytes)
            } else {
                // 非 TXT 先把解析器提取出的正文（含它自己识别的小标题）拼成纯文本再切
                flatten(doc.parts)
            }
            val norm = ChapterSplitter.normalize(raw)
            var splitParts = ChapterSplitter.split(norm, "正文", options)
            val title = doc.title
            if (splitParts.isNotEmpty() && title != null) {
                val first = splitParts.first().content.trim()
                if (first.startsWith(title) && first.length - title.length < 60) {
                    val rest = first.removePrefix(title).trim()
                    splitParts = if (rest.isEmpty()) splitParts.drop(1).ifEmpty { splitParts }
                    else listOf(splitParts.first().copy(content = rest)) + splitParts.drop(1)
                }
            }
            splitParts
        } else if (doc.format == DocumentFormat.TXT &&
            (options.cleanWatermark || options.customPattern.isNotBlank())
        ) {
            val raw = CharsetDetector.decode(bytes)
            val norm = ChapterSplitter.normalize(raw)
            var splitParts = ChapterSplitter.split(norm, "正文", options)
            val title = doc.title
            if (splitParts.isNotEmpty() && title != null) {
                val first = splitParts.first().content.trim()
                if (first.startsWith(title) && first.length - title.length < 60) {
                    val rest = first.removePrefix(title).trim()
                    splitParts = if (rest.isEmpty()) splitParts.drop(1).ifEmpty { splitParts }
                    else listOf(splitParts.first().copy(content = rest)) + splitParts.drop(1)
                }
            }
            splitParts
        } else if (options.cleanWatermark) {
            doc.parts.map { p ->
                p.copy(
                    title = ChapterSplitter.cleanChapterTitle(p.title),
                    content = ChapterSplitter.cleanBodyWatermarks(p.content),
                    volume = ChapterSplitter.detectVolumeFromChapter(p.title, p.content, p.volume)
                )
            }
        } else {
            doc.parts
        }
        // 水印净网有可能把整篇清空（纯导航页 / 纯水印文本）。DocumentParsers 的「无正文」校验
        // 发生在清洗之前，所以这里要再验一次 —— 否则会导入成一堆只有标题、正文为空的空章。
        if (parts.all { it.content.isBlank() }) {
            throw ParseException(ParseErrorCode.NO_CONTENT)
        }
        return FileParse(
            parts = parts,
            metaTitle = doc.title,
            author = doc.author,
            sourceType = sourceType
        )
    }

    private fun splitUnitsWithVolume(
        parsed: FileParse,
        fileTitle: String,
        options: SplitOptions
    ): List<ChapterSplitter.Part> {
        val parts = parsed.parts
        val mode = options.mode
        return when {
            mode == SplitMode.IN_FILE ||
                mode == SplitMode.STRICT_CHAP ||
                mode == SplitMode.NUMBERED ||
                mode == SplitMode.FIXED_SIZE ||
                mode == SplitMode.CUSTOM ->
                parts.map {
                    ChapterSplitter.Part(
                        title = it.title.ifBlank { fileTitle },
                        content = ChapterSplitter.normalize(it.content),
                        volume = it.volume
                    )
                }

            mode == SplitMode.PER_FILE ->
                listOf(
                    ChapterSplitter.Part(
                        title = if (options.cleanWatermark) ChapterSplitter.cleanChapterTitle(fileTitle) else fileTitle,
                        content = ChapterSplitter.normalize(flatten(parts)),
                        volume = ""
                    )
                )

            looksLikeRealChapters(parts) ->
                parts.map {
                    ChapterSplitter.Part(
                        title = it.title.ifBlank { fileTitle },
                        content = ChapterSplitter.normalize(it.content),
                        volume = it.volume
                    )
                }

            else -> {
                val only = parts.firstOrNull()
                val title = when {
                    ChapterSplitter.chapterOrderFromTitle(fileTitle) != null -> fileTitle
                    only != null && only.title.isNotBlank() -> only.title
                    else -> fileTitle
                }
                val body = if (parts.size == 1) parts[0].content else flatten(parts)
                listOf(
                    ChapterSplitter.Part(
                        title = if (options.cleanWatermark) ChapterSplitter.cleanChapterTitle(title) else title,
                        content = ChapterSplitter.normalize(body),
                        volume = only?.volume.orEmpty()
                    )
                )
            }
        }
    }

    private fun looksLikeRealChapters(parts: List<ChapterSplitter.Part>): Boolean {
        if (parts.size < 2) return false
        val distinct = parts
            .mapNotNull { ChapterSplitter.chapterLevelOrderFromTitle(it.title) }
            .distinct()
            .size
        return distinct >= 2
    }

    private fun flatten(parts: List<ChapterSplitter.Part>): String =
        parts.joinToString("\n\n") {
            val realTitle = it.title.isNotBlank() && !ChapterSplitter.isFallbackSectionTitle(it.title)
            if (realTitle && it.content.isNotBlank()) "${it.title}\n\n${it.content}"
            else it.content
        }

    private fun orderByFileName(uris: List<Uri>): List<Uri> =
        uris.withIndex()
            .map { entry ->
                val name = displayName(entry.value).orEmpty()
                Triple(entry, name, ChapterSplitter.fileOrderKey(name))
            }
            .sortedWith(
                compareBy(
                    { it.third.order }, { it.third.family }, { it.third.segment },
                    { it.second }, { it.first.index }
                )
            )
            .map { it.first.value }

    /** 导入单个文件为一本书，返回章节数 */
    private fun importSingle(
        bytes: ByteArray,
        name: String,
        customTitle: String?,
        ids: MutableList<Long>,
        splitOptions: SplitOptions = SplitOptions(),
        cancellation: CoroutineContext = EmptyCoroutineContext
    ): Int {
        val parsedFile = parseFile(bytes, name, splitOptions)
        val fileTitle = ChapterSplitter.titleFromFileName(name)
        val parts = if (splitOptions.mode == SplitMode.PER_FILE) {
            splitUnitsWithVolume(parsedFile, fileTitle, splitOptions)
        } else {
            parsedFile.parts
        }
        val metaTitle = parsedFile.metaTitle
        val author = parsedFile.author
        val sourceType = parsedFile.sourceType

        val title = customTitle?.takeIf { it.isNotBlank() }
            ?: metaTitle?.takeIf { it.isNotBlank() }
            ?: fileTitle

        val staged = ArrayList<String>()
        val result = try { db.inTransaction {
        val bookId = db.insertBook(
            Book(
                title = title,
                author = author,
                sourceType = sourceType,
                coverHue = hueFromString(title),
                createdAt = now()
            )
        )
        check(bookId > 0) { "insertBook failed" }

        var totalChars = 0
        var inserted = 0
        for ((i, p) in parts.withIndex()) {
            cancellation.ensureActive()
            val body = prepareBody(sourceType, p.content)
            val fileName = files.newFileName()
            staged += fileName
            writeChapter(fileName, body)
            val chTitle = p.title.ifBlank { appContext.getString(R.string.label_chapter_n, i + 1) }
            val vol = ChapterSplitter.detectVolumeFromChapter(chTitle, body.text, p.volume)
            val newId = db.insertChapter(
                Chapter(
                    bookId = bookId,
                    indexInBook = inserted,
                    title = chTitle,
                    fileName = fileName,
                    charCount = body.text.length,
                    sourceName = name,
                    volume = vol
                )
            )
            check(newId > 0) { "insertChapter failed" }
            inserted++
            totalChars += body.text.length
        }
        cancellation.ensureActive()
        db.updateBookStats(bookId, inserted, totalChars)
        check(inserted > 0) { "no chapters imported" }
        bookId to inserted
        } } catch (t: Throwable) {
            staged.forEach { files.deleteChapter(it) }
            throw t
        }
        ids += result.first
        return result.second
    }

    private fun guessMergedTitle(names: List<String>): String {
        if (names.isEmpty()) return appContext.getString(R.string.repo_merged_collection)
        if (names.size == 1) return appContext.getString(
            R.string.repo_collection_named, ChapterSplitter.titleFromFileName(names[0])
        )
        val base = names.map { ChapterSplitter.titleFromFileName(it) }
        val common = commonPrefix(base)
        return if (common.length >= 2) appContext.getString(R.string.repo_collection_named, common)
        else appContext.getString(R.string.repo_collection_etc, base.first(), names.size)
    }

    private fun commonPrefix(list: List<String>): String {
        if (list.isEmpty()) return ""
        var prefix = list.first()
        for (s in list) {
            while (!s.startsWith(prefix)) {
                prefix = prefix.dropLast(1)
                if (prefix.isEmpty()) return ""
            }
        }
        return prefix.trim()
    }

    // ==================== 新建 / 手写 ====================

    suspend fun createManualBook(title: String, author: String): Long = withContext(Dispatchers.IO) {
        val t = title.ifBlank { appContext.getString(R.string.repo_untitled_book) }
        val id = db.insertBook(
            Book(
                title = t, author = author, sourceType = SourceType.MANUAL,
                coverHue = hueFromString(t), createdAt = now()
            )
        )
        val fileName = files.newFileName()
        files.write(fileName, "")
        db.insertChapter(Chapter(bookId = id, indexInBook = 0, title = appContext.getString(R.string.repo_first_chapter), fileName = fileName))
        db.updateBookStats(id, 1, 0)
        id
    }

    suspend fun addEmptyChapter(bookId: Long, title: String): Long = bookWrite(bookId) {
        val chapters = db.chaptersOf(bookId)
        val fileName = files.newFileName()
        files.write(fileName, "")
        val id = db.insertChapter(
            Chapter(
                bookId = bookId,
                indexInBook = chapters.size,
                title = title.ifBlank { appContext.getString(R.string.label_chapter_n, chapters.size + 1) },
                fileName = fileName
            )
        )
        refreshStats(bookId)
        id
    }

    /**
     * 往已有书籍追加文件。
     */
    suspend fun appendFilesAsChapters(
        bookId: Long,
        uris: List<Uri>,
        splitMode: SplitMode = SplitMode.SMART,
        splitOptions: SplitOptions = SplitOptions(mode = splitMode),
        fileOrder: ImportOrder = ImportOrder.AUTO,
        onProgress: (String, Float) -> Unit = { _, _ -> }
    ): List<String> = bookWrite(bookId) {
        val cancellation = currentCoroutineContext()
        val errors = ArrayList<String>()
        val chapters = db.chaptersOf(bookId)
        var index = chapters.size
        val total = uris.size.coerceAtLeast(1)
        val inserted = ArrayList<Pair<Long, String>>()
        for ((i, uri) in uris.withIndex()) {
            val name = displayName(uri) ?: appContext.getString(R.string.repo_fallback_file_n, i + 1)
            onProgress(appContext.getString(R.string.repo_stage_parse, name), i.toFloat() / total)
            currentCoroutineContext().ensureActive()
            val bytes = try {
                readBytes(uri, currentCoroutineContext())
            } catch (e: ParseException) {
                errors += appContext.getString(R.string.err_file_reason, name, ParseErrors.reason(appContext, e)); continue
            }
            if (bytes == null) {
                errors += appContext.getString(R.string.err_file_reason, name, ParseErrors.reason(appContext, ParseErrorCode.READ_FAILED)); continue
            }
            val parsed = try {
                parseFile(bytes, name, splitOptions)
            } catch (e: ParseException) {
                errors += appContext.getString(R.string.err_file_reason, name, ParseErrors.reason(appContext, e)); continue
            }
            val fileTitle = ChapterSplitter.titleFromFileName(name)
            val units = splitUnitsWithVolume(parsed, fileTitle, splitOptions)
            val staged = ArrayList<String>()
            val pendingInserted = ArrayList<Pair<Long, String>>()
            val previousIndex = index
            try {
            db.inTransaction {
            for (u in units) {
                cancellation.ensureActive()
                val body = prepareBody(parsed.sourceType, u.content)
                val fileName = files.newFileName()
                staged += fileName
                writeChapter(fileName, body)
                val vol = ChapterSplitter.detectVolumeFromChapter(u.title, body.text, u.volume)
                val newId = db.insertChapter(
                    Chapter(
                        bookId = bookId, indexInBook = index++,
                        title = u.title, fileName = fileName,
                        charCount = body.text.length, sourceName = name,
                        volume = vol
                    )
                )
                check(newId > 0) { "insertChapter failed" }
                pendingInserted += newId to u.title
            }
            refreshStats(bookId)
            }
            inserted += pendingInserted
            } catch (e: Exception) {
                staged.forEach { files.deleteChapter(it) }
                index = previousIndex
                if (e is CancellationException) throw e
                errors += appContext.getString(R.string.err_file_reason, name, e.localizedMessage ?: appContext.getString(R.string.action_unknown_error))
            }
        }
        if (fileOrder == ImportOrder.AUTO) insertByChapterOrder(bookId, inserted)
        onProgress(appContext.getString(R.string.repo_stage_done), 1f)
        refreshStats(bookId)
        errors
    }

    private suspend fun insertByChapterOrder(bookId: Long, inserted: List<Pair<Long, String>>) {
        if (inserted.isEmpty()) return
        val withOrder = inserted.mapNotNull { (id, title) ->
            ChapterSplitter.chapterOrderFromTitle(title)?.let { id to it }
        }
        if (withOrder.isEmpty()) return
        val all = db.chaptersOf(bookId)
        val volumes = all.map { it.volume }.filter { it.isNotBlank() }.distinct()
        if (volumes.size > 1 || ChapterSplitter.hasChapterNumberReset(all.map { ChapterSplitter.chapterOrderFromTitle(it.title) })) return
        val newIds = inserted.map { it.first }.toSet()
        val order = all.filter { it.id !in newIds }.toMutableList()
        withOrder.sortedBy { it.second }.forEach { (id, n) ->
            val ch = all.firstOrNull { it.id == id } ?: return@forEach
            val pos = order.indexOfFirst { exist ->
                val eo = ChapterSplitter.chapterOrderFromTitle(exist.title)
                eo != null && eo > n
            }
            if (pos >= 0) order.add(pos, ch) else order.add(ch)
        }
        inserted.mapNotNull { (id, _) -> all.firstOrNull { it.id == id } }
            .filter { ch -> order.none { it.id == ch.id } }
            .forEach { order.add(it) }
        if (order.size == all.size) reorderChapters(bookId, order.map { it.id })
    }

    /**
     * 按章节标题里的章号把整本重排一遍（章节管理里的「自动排序」）。
     */
    suspend fun autoSortChapters(bookId: Long): Int = bookWrite(bookId) {
        val all = db.chaptersOf(bookId)
        if (all.size <= 1) return@bookWrite all.size
        val orders = all.map { ChapterSplitter.chapterOrderFromTitle(it.title) }
        if (ChapterSplitter.hasChapterNumberReset(orders)) return@bookWrite all.size
        val slots = all.indices.filter { orders[it] != null }
        if (slots.size < 2) return@bookWrite all.size
        val ranked = slots.sortedWith(compareBy({ orders[it]!! }, { it }))
        val sorted = all.toMutableList()
        slots.forEachIndexed { k, slot -> sorted[slot] = all[ranked[k]] }
        if (sorted.map { it.id } != all.map { it.id }) {
            reorderChapters(bookId, sorted.map { it.id })
        }
        sorted.size
    }

    // ==================== 补救层：合并章节 / 拆分章节（批注无损迁移） / 整书重新分章 ====================

    /**
     * 将 [chapterId] 并入上一章末尾，并把本章内的所有书签、标签、高亮、笔记无损平移到上一章
     * （偏移自动加上 `上一章字数 + 2`）。后续章节的批注章号同步 -1。
     */
    suspend fun mergeChapterWithPrev(bookId: Long, chapterId: Long): Boolean = bookWrite(bookId) {
        val all = db.chaptersOf(bookId)
        val currIdx = all.indexOfFirst { it.id == chapterId }
        if (currIdx <= 0) return@bookWrite false
        val prev = all[currIdx - 1]
        val curr = all[currIdx]

        val prevText = files.read(prev.fileName)
        val currText = files.read(curr.fileName)
        val shift = if (prevText.isEmpty()) 0 else prevText.length + 2
        val mergedText = when {
            prevText.isEmpty() -> currText
            currText.isEmpty() -> prevText
            else -> "$prevText\n\n$currText"
        }

        val mergedFile = files.newFileName()
        // 旧文件保持不动；新文件与数据库提交失败都不会破坏原正文。
        val deletedFile = try {
            val rich = RichTransforms.merge(mergedText,
                RichChapter.decode(files.readRich(prev.fileName)),
                RichChapter.decode(files.readRich(curr.fileName)), shift)
            writeChapter(mergedFile, ChapterBody(mergedText, rich))
            db.inTransaction {
                db.updateChapterFile(prev.id, mergedFile, mergedText.length)
                // 平移规则抽在 MarkShifts 里（纯函数、有单测）：算错会静默弄坏用户批注
                val allMarks = db.marksOf(bookId)
                MarkShifts.forMergeWithPrev(allMarks, currIdx, shift).forEachIndexed { i, m ->
                    if (m != allMarks[i]) db.updateMark(m)
                }
                val del = db.deleteChapter(curr.id)
                val remaining = all.filter { it.id != curr.id }
                db.reindexChapters(bookId, remaining.map { it.id })
                db.getBook(bookId)?.let { book ->
                    when {
                        book.lastChapterIndex == currIdx -> db.updateProgress(bookId, currIdx - 1, book.lastCharOffset + shift, book.progress)
                        book.lastChapterIndex > currIdx -> db.updateProgress(bookId, book.lastChapterIndex - 1, book.lastCharOffset, book.progress)
                    }
                }
                refreshStats(bookId)
                del
            }
        } catch (t: Throwable) {
            files.deleteChapter(mergedFile)
            throw t
        }
        files.deleteChapter(prev.fileName)
        if (deletedFile != null) runCatching { files.deleteChapter(deletedFile) }
        true
    }

    /**
     * 将 [chapterId] 并入下一章开头（等价于把下一章并入本章末尾），批注无损迁移。
     */
    suspend fun mergeChapterWithNext(bookId: Long, chapterId: Long): Boolean = bookWrite(bookId) {
        val all = db.chaptersOf(bookId)
        val currIdx = all.indexOfFirst { it.id == chapterId }
        if (currIdx < 0 || currIdx >= all.lastIndex) return@bookWrite false
        val next = all[currIdx + 1]
        mergeChapterWithPrev(bookId, next.id)
    }

    /**
     * 从 [chapterId] 的字符偏移 [splitOffset] 处拆分为两章，批注无损迁移：
     *  · 位于 [splitOffset] 之前的批注留在原章；
     *  · 位于 [splitOffset] 之后的批注自动迁移到新拆出的下一章，并扣减前半章长度；
     *  · 若拆出段落的第一行是短标题行，自动提取为新章标题。
     * @return 新章节的 id，失败返回 null
     */
    suspend fun splitChapterAt(
        bookId: Long,
        chapterId: Long,
        splitOffset: Int,
        customNewTitle: String? = null
    ): Long? = bookWrite(bookId) {
        val all = db.chaptersOf(bookId)
        val currIdx = all.indexOfFirst { it.id == chapterId }
        if (currIdx < 0) return@bookWrite null
        val curr = all[currIdx]
        val fullText = files.read(curr.fileName)
        if (splitOffset <= 0 || splitOffset >= fullText.length) return@bookWrite null

        val firstRaw = fullText.substring(0, splitOffset)
        val firstText = firstRaw.trimEnd()
        val secondRaw = fullText.substring(splitOffset)
        val leadingTrimmed = secondRaw.length - secondRaw.trimStart().length
        val secondTrimmed = secondRaw.trimStart()
        if (firstText.isEmpty() || secondTrimmed.isEmpty()) return@bookWrite null

        // 推断新章标题：如果未手工指定且后半段首行是短行，则用首行做标题
        val firstLineEnd = secondTrimmed.indexOf('\n')
        val candidateLine = if (firstLineEnd >= 0) secondTrimmed.substring(0, firstLineEnd).trim() else secondTrimmed.trim()
        val autoHeadingUsed = customNewTitle.isNullOrBlank() &&
            candidateLine.length in 1..38 &&
            firstLineEnd > 0 &&
            secondTrimmed.substring(firstLineEnd + 1).isNotBlank()

        val newTitle = when {
            !customNewTitle.isNullOrBlank() -> customNewTitle.trim()
            autoHeadingUsed -> ChapterSplitter.cleanChapterTitle(candidateLine)
            candidateLine.length in 1..28 -> ChapterSplitter.cleanChapterTitle(candidateLine)
            else -> "${curr.title}（续）"
        }

        val secondBody: String
        val bodyShiftFromSplit: Int
        if (autoHeadingUsed) {
            val afterFirstLine = secondTrimmed.substring(firstLineEnd + 1)
            val blankTrim = afterFirstLine.length - afterFirstLine.trimStart().length
            secondBody = afterFirstLine.trimStart()
            bodyShiftFromSplit = splitOffset + leadingTrimmed + firstLineEnd + 1 + blankTrim
        } else {
            secondBody = secondTrimmed
            bodyShiftFromSplit = splitOffset + leadingTrimmed
        }

        val firstFileName = files.newFileName()
        val newFileName = files.newFileName()

        val newVol = ChapterSplitter.detectVolumeFromChapter(newTitle, secondBody, curr.volume)

        val newChapterId = try {
            val oldRich = RichChapter.decode(files.readRich(curr.fileName))
            writeChapter(firstFileName, ChapterBody(firstText, RichTransforms.slice(oldRich, fullText, 0, firstText.length)))
            writeChapter(newFileName, ChapterBody(secondBody, RichTransforms.slice(oldRich, fullText, bodyShiftFromSplit, fullText.length)))
            db.inTransaction {
            db.updateChapterFile(curr.id, firstFileName, firstText.length)
            // 平移规则抽在 MarkShifts 里（纯函数、有单测）
            val allMarks = db.marksOf(bookId)
            MarkShifts.forSplitAt(
                allMarks,
                currIdx = currIdx,
                splitOffset = splitOffset,
                bodyShiftFromSplit = bodyShiftFromSplit,
                firstTextLength = firstText.length,
                secondBodyLength = secondBody.length
            ).forEachIndexed { i, m ->
                if (m != allMarks[i]) db.updateMark(m)
            }

            val insertedId = db.insertChapter(
                Chapter(
                    bookId = bookId,
                    indexInBook = currIdx + 1,
                    title = newTitle,
                    fileName = newFileName,
                    charCount = secondBody.length,
                    sourceName = curr.sourceName,
                    volume = newVol
                )
            )
            // 插入失败（-1）不能继续往下：下面还要把这个 id 写进章节顺序表
            check(insertedId > 0) { "insertChapter failed" }
            val reordered = ArrayList<Long>(all.size + 1)
            for ((i, c) in all.withIndex()) {
                reordered += c.id
                if (i == currIdx) reordered += insertedId
            }
            db.reindexChapters(bookId, reordered)
            refreshStats(bookId)
            db.getBook(bookId)?.let { b ->
                when {
                    b.lastChapterIndex > currIdx -> db.updateProgress(bookId, b.lastChapterIndex + 1, b.lastCharOffset, b.progress)
                    b.lastChapterIndex == currIdx && b.lastCharOffset >= splitOffset ->
                        db.updateProgress(bookId, currIdx + 1, (b.lastCharOffset - bodyShiftFromSplit).coerceIn(0, secondBody.length), b.progress)
                    b.lastChapterIndex == currIdx -> db.updateProgress(bookId, currIdx, b.lastCharOffset.coerceAtMost(firstText.length), b.progress)
                }
            }
            insertedId
            }
        } catch (t: Throwable) {
            files.deleteChapter(firstFileName)
            files.deleteChapter(newFileName)
            throw t
        }
        files.deleteChapter(curr.fileName)
        newChapterId
    }

    /**
     * 对已导入的整本书按新策略 [options] 重新分章，并无损迁移全书批注（按全文片段或全局字符坐标定位）。
     * @return 重新切分后的总章数；-1 表示正文超过 [MAX_RESPLIT_CHARS]、为避免 OOM 直接拒绝
     */
    suspend fun resplitBook(
        bookId: Long,
        options: SplitOptions
    ): Int = bookWrite(bookId) {
        val book = db.getBook(bookId) ?: return@bookWrite 0
        val oldChapters = db.chaptersOf(bookId)
        if (oldChapters.isEmpty()) return@bookWrite 0
        val oldMarks = db.marksOf(bookId)
        val charBudget = minOf(MAX_RESPLIT_CHARS.toLong(), Runtime.getRuntime().maxMemory() / 48).coerceAtLeast(1)

        // 旧锚点以实际正文为基准，标题的插入也计入坐标；上限在每次追加前检查。
        val fullSb = StringBuilder()
        val oldChapterGlobalStarts = IntArray(oldChapters.size)
        val oldRich = ArrayList<Pair<Int, RichChapter>>()
        for ((i, ch) in oldChapters.withIndex()) {
            if (files.file(ch.fileName).length() > charBudget * 4L) return@bookWrite -1
            val body = files.read(ch.fileName)
            val realTitle = ch.title.isNotBlank() && !ChapterSplitter.isFallbackSectionTitle(ch.title)
            val heading = if (realTitle && !body.trim().startsWith(ch.title.trim())) "${ch.title.trim()}\n\n" else ""
            if (fullSb.length.toLong() + body.length + heading.length + 2 > charBudget) return@bookWrite -1
            if (fullSb.isNotEmpty()) fullSb.append("\n\n")
            fullSb.append(heading)
            oldChapterGlobalStarts[i] = fullSb.length
            RichChapter.decode(files.readRich(ch.fileName))?.takeIf { it.len == body.length }?.let {
                oldRich += fullSb.length to it
            }
            fullSb.append(body)
        }
        val fullText = fullSb.toString()
        if (fullText.isBlank()) return@bookWrite oldChapters.size
        // 整本重分章得把全文读进内存（切分还会再复制几份），超大书直接把 App 撑爆。
        // 这是手机上的硬上限，超过就拒绝（返回 -1，由界面给提示），不要硬跑。
        if (fullText.length > MAX_RESPLIT_CHARS) {
            Log.w(TAG, "resplit refused: ${fullText.length} chars > $MAX_RESPLIT_CHARS")
            return@bookWrite -1
        }

        // 2. 用新策略重新切分
        val newParts = ChapterSplitter.split(ChapterSplitter.normalize(fullText), book.title, options)
        if (newParts.isEmpty()) return@bookWrite oldChapters.size
        val mappings = TextRemap.align(fullText, newParts.map { it.content })

        fun mappedRich(text: String, map: IntArray): RichChapter? {
            if (map.isEmpty() || oldRich.isEmpty()) return null
            val spans = oldRich.flatMap { (base, rich) -> rich.spans.mapNotNull { s ->
                val start = TextRemap.lowerBound(map, base + s.start)
                val end = TextRemap.lowerBound(map, base + s.end)
                if (end > start) s.copy(start = start, end = end) else null
            } }
            val links = oldRich.flatMap { (base, rich) -> rich.links.mapNotNull { s ->
                val start = TextRemap.lowerBound(map, base + s.start)
                val end = TextRemap.lowerBound(map, base + s.end)
                if (end > start) s.copy(start = start, end = end) else null
            } }
            val blocks = ArrayList<RichBlock>()
            var start = 0
            while (start < text.length) {
                val end = text.indexOf("\n\n", start).let { if (it < 0) text.length else it }
                if (end > start) {
                    val sourcePos = map[start]
                    val sourceBlock = oldRich.firstNotNullOfOrNull { (base, rich) ->
                        rich.blocks.firstOrNull { sourcePos in (base + it.start) until (base + it.end) }
                    }
                    blocks += sourceBlock?.copy(start = start, end = end)
                        ?: RichBlock(RichKind.BODY, 0, "", start, end)
                }
                start = end + 2
            }
            return RichChapter(text.length, blocks, spans, links)
        }

        // 3. 写入新章节文件，计算新章节的全局区间以迁移批注
        data class NewChapData(
            val title: String,
            val fileName: String,
            val text: String,
            val volume: String,
            val mapping: IntArray
        )

        val newChapList = ArrayList<NewChapData>(newParts.size)
        val stagedFiles = ArrayList<String>()
        try {
        for ((i, p) in newParts.withIndex()) {
            // 已落盘的是显示正文，绝不能再次解释为 Markdown。
            val body = ChapterBody(p.content, mappedRich(p.content, mappings[i]))
            val fn = files.newFileName()
            stagedFiles += fn
            writeChapter(fn, body)
            val chTitle = p.title.ifBlank { appContext.getString(R.string.label_chapter_n, i + 1) }
            val vol = ChapterSplitter.detectVolumeFromChapter(chTitle, body.text, p.volume)
            newChapList += NewChapData(chTitle, fn, body.text, vol, mappings[i])
        }

        // 4. 在单个事务中替换章节并迁移所有批注
        db.inTransaction {
            val wdb = db.writableDatabase
            wdb.delete("chapters", "book_id=?", arrayOf(bookId.toString()))
            var totalChars = 0
            newChapList.forEachIndexed { i, nc ->
                val newId = db.insertChapter(
                    Chapter(
                        bookId = bookId,
                        indexInBook = i,
                        title = nc.title,
                        fileName = nc.fileName,
                        charCount = nc.text.length,
                        sourceName = book.title,
                        volume = nc.volume
                    )
                )
                check(newId > 0) { "insertChapter failed" }
                totalChars += nc.text.length
            }
            db.updateBookStats(bookId, newChapList.size, totalChars)

            // 按实际字符来源迁移，重复对白不会被误认成全书第一处。
            for (m in oldMarks) {
                val base = oldChapterGlobalStarts.getOrElse(m.chapterIndex) { 0 }
                val (matchedChap, matchedStart) = TextRemap.locate(mappings, base + m.startOffset)
                val matchedEnd = TextRemap.lowerBound(mappings[matchedChap], base + m.endOffset).coerceAtLeast(matchedStart)
                db.updateMark(
                    m.copy(
                        chapterIndex = matchedChap,
                        startOffset = matchedStart,
                        endOffset = matchedEnd
                    )
                )
            }

            val oldPosition = oldChapterGlobalStarts.getOrElse(book.lastChapterIndex) { 0 } + book.lastCharOffset
            val (safeLastChap, safeOffset) = TextRemap.locate(mappings, oldPosition)
            db.updateProgress(bookId, safeLastChap, safeOffset, book.progress)
        }
        } catch (t: Throwable) {
            // 事务没成：新章节都还没入库，它们刚写下的正文文件全是孤儿，清掉
            stagedFiles.forEach { files.deleteChapter(it) }
            throw t
        }

        oldChapters.forEach { files.deleteChapter(it.fileName) }
        newChapList.size
    }

    // ==================== 查询 ====================
    suspend fun books(sort: ShelfSort): List<Book> = withContext(Dispatchers.IO) { db.getBooks(sort) }
    suspend fun book(id: Long): Book? = withContext(Dispatchers.IO) { db.getBook(id) }

    // ==================== 分组 ====================

    suspend fun groups(): List<BookGroup> = withContext(Dispatchers.IO) { db.groups() }

    suspend fun createGroup(name: String): Long = withContext(Dispatchers.IO) {
        db.insertGroup(name.trim().ifBlank { appContext.getString(R.string.repo_untitled_group) })
    }

    suspend fun renameGroup(id: Long, name: String) = withContext(Dispatchers.IO) {
        db.updateGroupName(id, name.trim().ifBlank { appContext.getString(R.string.repo_untitled_group) })
    }

    suspend fun deleteGroup(id: Long) = withContext(Dispatchers.IO) { db.deleteGroup(id) }

    suspend fun moveBooksToGroup(ids: List<Long>, groupId: Long) = withContext(Dispatchers.IO) {
        db.setBooksGroup(ids, groupId)
    }

    suspend fun chapters(bookId: Long): List<Chapter> = withContext(Dispatchers.IO) { db.chaptersOf(bookId) }

    suspend fun chaptersByIds(bookId: Long, ids: List<Long>): List<Chapter> = withContext(Dispatchers.IO) {
        val all = db.chaptersOf(bookId)
        if (ids.isEmpty()) all else {
            val byId = all.associateBy { it.id }
            ids.mapNotNull(byId::get)
        }
    }

    suspend fun chapterText(chapter: Chapter): String =
        withContext(Dispatchers.IO) { writes.write(chapter.bookId) {
            val current = db.chapterOf(chapter.bookId, chapter.id)
                ?: throw IOException("章节已更新，请重新打开")
            files.read(current.fileName)
        } }

    suspend fun chapterRich(chapter: Chapter, textLength: Int): RichChapter? =
        withContext(Dispatchers.IO) {
            writes.write(chapter.bookId) {
                val current = db.chapterOf(chapter.bookId, chapter.id)
                    ?: return@write null
                val raw = files.readRich(current.fileName)
                if (raw.isBlank()) null else RichChapter.decode(raw)?.takeIf { it.len == textLength }
            }
        }

    suspend fun updateProgress(bookId: Long, chapterIndex: Int, offset: Int, progress: Float,
                               token: Long = newProgressToken(bookId)) = withContext(Dispatchers.IO) {
        writes.write(bookId) {
            if (progressClock.isLatest(bookId, token)) db.updateProgress(bookId, chapterIndex, offset, progress)
        }
    }

    suspend fun updateBookMeta(bookId: Long, title: String, author: String) =
        bookWrite(bookId) { db.updateBookMeta(bookId, title, author) }

    suspend fun deleteBook(bookId: Long) = bookWrite(bookId) {
        val cover = db.getBook(bookId)?.coverPath.orEmpty()
        db.deleteBook(bookId).forEach { runCatching { files.deleteChapter(it) } }
        files.deleteCover(cover)
        com.inkreader.ui.component.CoverCache.invalidate()
    }

    suspend fun deleteChapter(bookId: Long, chapterId: Long) = bookWrite(bookId) {
        val (curId, curIdx) = currentChapterId(bookId)
        val ch = db.chaptersOf(bookId).firstOrNull { it.id == chapterId }
        val file = db.inTransaction {
            val deleted = db.deleteChapter(chapterId)
            ch?.let {
                db.deleteMarksInChapter(bookId, it.indexInBook)
                db.marksOf(bookId).filter { m -> m.chapterIndex > it.indexInBook }.forEach { m ->
                    db.updateMark(m.copy(chapterIndex = m.chapterIndex - 1))
                }
                // 章节编号重排也放进同一个事务：批注的章号已经按新编号移好了，
                // 这一步要是失败，库里的 idx 和批注就对不上
                reindex(bookId)
            }
            deleted
        }
        if (file != null) runCatching { files.deleteChapter(file) }
        if (ch != null) {
            reanchorLastChapter(bookId, curId, curIdx)
            refreshStats(bookId)
        }
    }

    suspend fun renameChapter(chapterId: Long, title: String) = withContext(Dispatchers.IO) {
        chapterBook(chapterId)?.let { id -> bookWrite(id) { db.updateChapterTitle(chapterId, title) } }
    }

    suspend fun moveChapter(bookId: Long, chapterId: Long, delta: Int) = bookWrite(bookId) {
        val (curId, curIdx) = currentChapterId(bookId)
        val list = db.chaptersOf(bookId).toMutableList()
        val from = list.indexOfFirst { it.id == chapterId }
        if (from < 0) return@bookWrite
        val to = from + delta
        if (to !in list.indices) return@bookWrite
        val item = list.removeAt(from)
        list.add(to, item)
        val oldIdx = item.indexInBook
        val newIdx = to
        // 章节编号与批注章号一起改：只改一半会让高亮/笔记落到别的章上
        db.inTransaction {
            db.reindexChapters(bookId, list.map { it.id })
            db.marksOf(bookId)
                .filter { it.chapterIndex in minOf(oldIdx, newIdx)..maxOf(oldIdx, newIdx) }
                .forEach { m ->
                    val shifted = if (m.chapterIndex == oldIdx) newIdx
                    else if (oldIdx < newIdx) m.chapterIndex - 1 else m.chapterIndex + 1
                    db.updateMark(m.copy(chapterIndex = shifted))
                }
        }
        reanchorLastChapter(bookId, curId, curIdx)
        refreshStats(bookId)
    }

    suspend fun reorderChapters(bookId: Long, orderedIds: List<Long>) = bookWrite(bookId) {
        val (curId, curIdx) = currentChapterId(bookId)
        val old = db.chaptersOf(bookId).associate { it.id to it.indexInBook }
        val newPos = orderedIds.withIndex().associate { (i, id) -> (old[id] ?: -1) to i }
        // 同上：编号与批注章号必须同一事务
        db.inTransaction {
            db.reindexChapters(bookId, orderedIds)
            db.marksOf(bookId).forEach { m ->
                val np = newPos[m.chapterIndex]
                if (np != null && np != m.chapterIndex) db.updateMark(m.copy(chapterIndex = np))
            }
        }
        reanchorLastChapter(bookId, curId, curIdx)
        refreshStats(bookId)
    }

    /** 保存章节正文（编辑器） */
    suspend fun saveChapterText(chapter: Chapter, text: String, expectedText: String? = null) = bookWrite(chapter.bookId) {
        val current = db.chapterOf(chapter.bookId, chapter.id)
            ?: throw IOException("章节已被删除，无法保存")
        val before = files.read(current.fileName)
        if (expectedText != null && before != expectedText) throw IOException("章节已在别处更新，请重新打开后编辑")
        if (before == text) return@bookWrite
        val change = TextEditMap(before, text)
        val rich = RichTransforms.edit(RichChapter.decode(files.readRich(current.fileName)), change)
        val nextFile = files.newFileName()
        try {
            writeChapter(nextFile, ChapterBody(text, rich))
            db.inTransaction {
                db.updateChapterFile(current.id, nextFile, text.length)
                db.marksOf(chapter.bookId, current.indexInBook).forEach { mark ->
                    val (start, end) = change.range(mark.startOffset, mark.endOffset)
                    db.updateMark(mark.copy(startOffset = start, endOffset = end,
                        snippet = if (end > start) text.substring(start, end).take(200) else mark.snippet))
                }
                db.getBook(chapter.bookId)?.takeIf { it.lastChapterIndex == current.indexInBook }?.let { book ->
                    db.updateProgress(book.id, book.lastChapterIndex, change.offset(book.lastCharOffset), book.progress)
                }
                refreshStats(chapter.bookId)
            }
        } catch (t: Throwable) {
            files.deleteChapter(nextFile)
            throw t
        }
        files.deleteChapter(current.fileName)
    }

    private fun reindex(bookId: Long) {
        val list = db.chaptersOf(bookId)
        db.reindexChapters(bookId, list.map { it.id })
    }

    /**
     * 改动前先记下「上次读到的那一章」是谁（id + 编号）。
     * 章节增删移之后再用 [reanchorLastChapter] 把它找回来。
     */
    private fun currentChapterId(bookId: Long): Pair<Long?, Int> {
        val idx = db.getBook(bookId)?.lastChapterIndex ?: 0
        val id = db.chaptersOf(bookId).firstOrNull { it.indexInBook == idx }?.id
        return id to idx
    }

    /**
     * 章节数量 / 顺序变化后，把「上次读到第几章」重新指到**同一章**上。
     *
     * 按章节 id 认章，而不是按编号做 +1/-1 推算：删除、移动、重排之后编号都会变，
     * 只按编号推在「删掉的正是正在读的那一章」这类情况上会算歪 —— 重新打开书就落到隔壁章去了。
     * 认不出原来那一章（已被删）时，落到原编号位置并把字符偏移清零。
     */
    private fun reanchorLastChapter(bookId: Long, prevChapterId: Long?, fallbackIndex: Int) {
        val b = db.getBook(bookId) ?: return
        val list = db.chaptersOf(bookId)
        if (list.isEmpty()) return
        val idx = prevChapterId?.let { id -> list.indexOfFirst { it.id == id } } ?: -1
        if (idx >= 0) {
            if (idx != b.lastChapterIndex) db.updateProgress(bookId, idx, b.lastCharOffset, b.progress)
        } else {
            db.updateProgress(bookId, fallbackIndex.coerceIn(0, list.lastIndex), 0, b.progress)
        }
    }

    fun refreshStats(bookId: Long) {
        val list = db.chaptersOf(bookId)
        db.updateBookStats(bookId, list.size, list.sumOf { it.charCount })
    }

    // ==================== 自定义封面 ====================

    suspend fun decodeCoverSource(uri: Uri, maxDim: Int = 1600): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            appContext.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            appContext.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }.getOrNull()
    }

    suspend fun saveCover(bookId: Long, jpeg: ByteArray): String = bookWrite(bookId) {
        val name = files.coverNameFor(bookId)
        files.writeCover(name, jpeg)
        db.updateBookCover(bookId, name)
        name
    }

    suspend fun clearCover(bookId: Long) = bookWrite(bookId) {
        files.deleteCover(files.coverNameFor(bookId))
        db.updateBookCover(bookId, "")
    }

    // ==================== 标记 ====================

    suspend fun marks(bookId: Long, chapterIndex: Int = -1): List<Mark> =
        withContext(Dispatchers.IO) { db.marksOf(bookId, chapterIndex) }

    suspend fun addMark(mark: Mark): Long = bookWrite(mark.bookId) { db.insertMark(mark) }

    suspend fun updateMark(mark: Mark) = bookWrite(mark.bookId) { db.updateMark(mark) }

    suspend fun deleteMark(id: Long) = withContext(Dispatchers.IO) { markBook(id)?.let { bookWrite(it) { db.deleteMark(id) } } }

    suspend fun toggleBookmark(bookId: Long, chapterIndex: Int, offset: Int): Boolean =
        bookWrite(bookId) {
            val existing = db.findMark(bookId, chapterIndex, offset, offset, MarkType.BOOKMARK)
            if (existing != null) {
                db.deleteMark(existing.id); false
            } else {
                db.insertMark(
                    Mark(
                        bookId = bookId, chapterIndex = chapterIndex,
                        startOffset = offset, endOffset = offset, type = MarkType.BOOKMARK
                    )
                ); true
            }
        }

    // ==================== 搜索 ====================

    suspend fun searchInBook(
        bookId: Long,
        keyword: String,
        maxHits: Int = 300,
        onProgress: (Float) -> Unit = {}
    ): List<SearchHit> = withContext(Dispatchers.IO) {
        if (keyword.isBlank() || maxHits <= 0) return@withContext emptyList()
        val chapters = db.chaptersOf(bookId)
        val out = ArrayList<SearchHit>()
        for ((i, ch) in chapters.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress(chapters.size.toFloat().let { (i.toFloat() / it) })
            currentCoroutineContext().ensureActive()
            val text = files.read(ch.fileName)
            var from = 0
            while (out.size < maxHits) {
                currentCoroutineContext().ensureActive()
                val idx = text.indexOf(keyword, from, ignoreCase = true)
                if (idx < 0) break
                val s = (idx - 18).coerceAtLeast(0)
                val e = (idx + keyword.length + 18).coerceAtMost(text.length)
                val ctx = text.substring(s, e).replace('\n', ' ').trim()
                out += SearchHit(
                    chapterIndex = ch.indexInBook,
                    chapterTitle = ch.title,
                    offset = idx,
                    context = (if (s > 0) "…" else "") + ctx + (if (e < text.length) "…" else "")
                )
                from = idx + keyword.length
            }
            if (out.size >= maxHits) break
        }
        onProgress(1f)
        out
    }

    // ==================== 工具 ====================

    fun displayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        var name: String? = null
        runCatching {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { c: Cursor ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun readBytes(uri: Uri, cancellation: CoroutineContext = EmptyCoroutineContext): ByteArray? {
        cancellation.ensureActive()
        declaredSize(uri)?.let { if (it > MAX_IMPORT_BYTES) throw ParseException(ParseErrorCode.TOO_LARGE) }
        val stream = try {
            appContext.contentResolver.openInputStream(uri)
        } catch (t: Throwable) {
            null
        }
        if (stream != null) {
            stream.use { return readBounded(it, MAX_IMPORT_BYTES, cancellation) }
        }
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            val f = File(path)
            if (f.length() > MAX_IMPORT_BYTES) throw ParseException(ParseErrorCode.TOO_LARGE)
            val fs = try {
                f.inputStream().buffered()
            } catch (t: Throwable) {
                return null
            }
            fs.use { return readBounded(it, MAX_IMPORT_BYTES, cancellation) }
        }
        return null
    }

    private fun declaredSize(uri: Uri): Long? {
        if (uri.scheme == "file") {
            return runCatching { uri.path?.let { File(it).length() } }.getOrNull()
        }
        return runCatching {
            appContext.contentResolver.query(
                uri, arrayOf(OpenableColumns.SIZE), null, null, null
            )?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.SIZE)
                if (i >= 0 && c.moveToFirst() && !c.isNull(i)) c.getLong(i) else null
            }
        }.getOrNull()
    }

    private fun readBounded(input: InputStream, limit: Long, cancellation: CoroutineContext): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            cancellation.ensureActive()
            val n = try { input.read(buf) } catch (e: IOException) {
                throw ParseException(ParseErrorCode.READ_FAILED, detail = e.message)
            }
            if (n < 0) break
            if (out.size() + n.toLong() > limit) throw ParseException(ParseErrorCode.TOO_LARGE)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val MAX_IMPORT_BYTES = 200L * 1024 * 1024

        /** 整本重分章的字符数上限（全文要读进内存，切分还会再复制几份） */
        const val MAX_RESPLIT_CHARS = 20_000_000

        const val TAG = "BookRepo"
    }
}

/** 由字符串稳定生成一个 0..359 的色相，用于自动封面配色 */
fun hueFromString(s: String): Int {
    if (s.isEmpty()) return 32
    var h = 7
    for (c in s) h = (h * 31 + c.code) % 100000
    return h % 360
}
