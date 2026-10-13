package com.inkreader.data.repo

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.inkreader.R
import com.inkreader.data.db.InkDb
import com.inkreader.data.file.FileStore
import com.inkreader.data.model.Book
import com.inkreader.data.model.BookGroup
import com.inkreader.data.model.Chapter
import com.inkreader.data.model.ImportMode
import com.inkreader.data.model.Mark
import com.inkreader.data.model.MarkType
import com.inkreader.data.model.SearchHit
import com.inkreader.data.model.ShelfSort
import com.inkreader.data.model.SourceType
import com.inkreader.data.model.SplitMode
import com.inkreader.parse.ChapterSplitter
import com.inkreader.parse.DocumentParsers
import com.inkreader.parse.ParseErrorCode
import com.inkreader.parse.ParseErrors
import com.inkreader.parse.ParseException
import com.inkreader.parse.RichChapter
import com.inkreader.parse.RichMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * 书籍仓库：负责导入解析、增删改查、章节重排、全文搜索。
 * 所有耗时操作都在 IO 线程执行，UI 只拿结果。
 */
class BookRepository(context: Context) {

    private val appContext = context.applicationContext
    val db = InkDb(appContext)
    val files = FileStore(appContext)

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

    // ==================== 导入 ====================

    suspend fun importDocuments(
        uris: List<Uri>,
        mode: ImportMode,
        customTitle: String?,
        splitMode: SplitMode = SplitMode.SMART,
        onProgress: (stage: String, fraction: Float) -> Unit
    ): ImportResult = withContext(Dispatchers.IO) {
        val ids = ArrayList<Long>()
        val results = ArrayList<ImportFileResult>()
        // 多文件导入：文件名里带「第X章」这类章号时，按章号排序再导入。
        // 系统文件选择器给的顺序通常是乱的，而这类文件名本身已经说明了先后。
        val ordered = if (uris.size > 1) orderByFileName(uris) else uris
        val total = ordered.size.coerceAtLeast(1)

        if (mode == ImportMode.MERGE && ordered.size > 1) {
            val title = customTitle?.takeIf { it.isNotBlank() }
                ?: guessMergedTitle(ordered.mapNotNull { displayName(it) })
            onProgress(appContext.getString(R.string.repo_stage_prepare_merge), 0.02f)

            var hue = hueFromString(title)
            val bookId = db.insertBook(
                Book(title = title, sourceType = SourceType.MERGED, coverHue = hue, createdAt = now())
            )
            ids += bookId

            var index = 0
            var totalChars = 0
            for ((i, uri) in ordered.withIndex()) {
                val name = displayName(uri) ?: appContext.getString(R.string.repo_fallback_file_n, i + 1)
                onProgress(appContext.getString(R.string.repo_stage_parse, name), (i.toFloat() / total) * 0.95f + 0.02f)
                val bytes = try {
                    readBytes(uri)
                } catch (e: ParseException) {
                    // 体积超限等在读之前就能判定的失败，单独给出原因
                    results += ImportFileResult(name, false, ParseErrors.reason(appContext, e))
                    continue
                }
                if (bytes == null) {
                    results += ImportFileResult(name, false, ParseErrors.reason(appContext, ParseErrorCode.READ_FAILED))
                    continue
                }
                val parsed = try {
                    parseFile(bytes, name)
                } catch (e: ParseException) {
                    results += ImportFileResult(name, false, ParseErrors.reason(appContext, e))
                    continue
                }
                val fileTitle = ChapterSplitter.titleFromFileName(name)
                val units: List<Pair<String, String>> = splitUnits(parsed, fileTitle, splitMode)
                for ((cTitle, content) in units) {
                    val body = prepareBody(parsed.sourceType, content)
                    val fileName = files.newFileName()
                    writeChapter(fileName, body)
                    db.insertChapter(
                        Chapter(
                            bookId = bookId,
                            indexInBook = index++,
                            title = cTitle,
                            fileName = fileName,
                            charCount = body.text.length,
                            sourceName = name
                        )
                    )
                    totalChars += body.text.length
                }
                results += ImportFileResult(name, true, bookId = bookId, chapterCount = units.size)
            }
            if (index == 0) {
                db.deleteBook(bookId).forEach { files.deleteChapter(it) }
                ids.clear()
                // 逐文件的原因已经记在 results 里了，只有「一个都没失败」时才需要兜底文案
                if (results.none { !it.ok }) {
                    results += ImportFileResult(title, false, appContext.getString(R.string.repo_no_content))
                }
            } else {
                db.updateBookStats(bookId, index, totalChars)
                // 智能解析：合并完成后按章号把整本排一遍。
                // 文件名排序只管「文件之间」的先后，合集文件内部各章的顺序、
                // 以及「单章文件」和「合集」交错的边界，都得靠这一步才保证有序。
                if (splitMode == SplitMode.SMART && index > 1) {
                    onProgress(appContext.getString(R.string.repo_stage_sort), 0.97f)
                    autoSortChapters(bookId)
                }
            }
            onProgress(appContext.getString(R.string.repo_stage_done), 1f)
            return@withContext ImportResult(ids, results)
        }

        // 各自成书
        for ((i, uri) in ordered.withIndex()) {
            val name = displayName(uri) ?: appContext.getString(R.string.repo_fallback_doc_n, i + 1)
            onProgress(appContext.getString(R.string.repo_stage_parse, name), (i.toFloat() / total) * 0.9f + 0.03f)
            val bytes = try {
                readBytes(uri)
            } catch (e: ParseException) {
                // 体积超限等在读之前就能判定的失败，单独给出原因
                results += ImportFileResult(name, false, ParseErrors.reason(appContext, e))
                continue
            }
            if (bytes == null) {
                results += ImportFileResult(name, false, ParseErrors.reason(appContext, ParseErrorCode.READ_FAILED))
                continue
            }
            val before = ids.size
            val chapters = try {
                importSingle(bytes, name, customTitle, ids)
            } catch (e: ParseException) {
                results += ImportFileResult(name, false, ParseErrors.reason(appContext, e))
                continue
            } catch (t: Throwable) {
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
            // 单个文件导入也按章号排一遍：文件内部章节乱序时能自动纠正，
            // 本来就顺的不会有任何变化（顺序没变不写库）。
            if (ids.size > before) {
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

    /**
     * 正文定稿。
     *
     * 只有 Markdown 需要额外产出样式覆盖：正文仍然落成纯文本（朗读、搜索、
     * 选区、进度全按它的字符偏移走，一个字都不用改），格式化信息另存一份，
     * 阅读时两者一起交给分页器 —— 这样加粗跨页、代码块缩进这类事才成立。
     */
    private fun prepareBody(sourceType: SourceType, content: String): ChapterBody =
        if (sourceType == SourceType.MD) {
            val rendered = RichMarkdown.render(content)
            ChapterBody(rendered.text, rendered.rich)
        } else {
            ChapterBody(content, null)
        }

    /** 落盘正文；没有样式覆盖时顺手清掉旧的，避免编辑过的章节用着过期的样式 */
    private fun writeChapter(fileName: String, body: ChapterBody) {
        files.write(fileName, body.text)
        val rich = body.rich
        if (rich != null) files.writeRich(fileName, rich.encode()) else files.deleteRich(fileName)
    }

    /**
     * 统一解析入口：交给 [DocumentParsers] 识别格式并分派解析器
     * （TXT / DOCX / Markdown / PDF / HTML / EPUB）。
     *
     * @throws ParseException 失败时抛出，调用方用 [ParseErrors] 生成统一提示
     */
    private fun parseFile(bytes: ByteArray, name: String): FileParse {
        val doc = DocumentParsers.parse(bytes, name)
        return FileParse(
            parts = doc.parts,
            metaTitle = doc.title,
            author = doc.author,
            sourceType = DocumentParsers.sourceTypeOf(doc.format)
        )
    }

    /**
     * 把一个文件切成「章节（标题, 正文）」列表，按 [SplitMode] 决定粒度。
     *
     * SMART 是逐文件判断的：文件内有多个章节标题才拆，否则整篇算一章 ——
     * 这样「1~9 章合集」和「第10章」混在一起导入时，两边都能得到正确结果。
     */
    private fun splitUnits(
        parsed: FileParse,
        fileTitle: String,
        mode: SplitMode
    ): List<Pair<String, String>> {
        val parts = parsed.parts
        return when {
            mode == SplitMode.IN_FILE ->
                parts.map { it.title.ifBlank { fileTitle } to ChapterSplitter.normalize(it.content) }

            mode == SplitMode.PER_FILE ->
                listOf(fileTitle to ChapterSplitter.normalize(flatten(parts)))

            looksLikeRealChapters(parts) ->
                parts.map { it.title.ifBlank { fileTitle } to ChapterSplitter.normalize(it.content) }

            else -> {
                // 智能 + 不算「一章一章分好的文件」：整篇作为一章。
                // 章名优先用文件名（通常就是「第10章 破境」这种干净写法），
                // 文件名里没有章号时才退回文件内部识别出的标题。
                val only = parts.firstOrNull()
                val title = when {
                    ChapterSplitter.chapterOrderFromTitle(fileTitle) != null -> fileTitle
                    only != null && only.title.isNotBlank() -> only.title
                    else -> fileTitle
                }
                val body = if (parts.size == 1) parts[0].content else flatten(parts)
                listOf(title to ChapterSplitter.normalize(body))
            }
        }
    }

    /**
     * 判断这个文件是不是「真的按章排好的」（而不是解析器在没找到标题时按字数兜底切的，
     * 也不是只按「第X节」这种小节切的）。
     *
     * 判据：**出现两个以上不同的「章/回/卷」级章号**才算合集。
     * 这一点同时挡住了两种情况：
     *  · 「第10章 xxx」内部只有三节 → 章号只有一个（10）或没有 → 不拆，整篇作为一章；
     *  · 内部没标题、被按字数兜底切成几段 → 各段标题章号相同或没有 → 不拆。
     */
    private fun looksLikeRealChapters(parts: List<ChapterSplitter.Part>): Boolean {
        if (parts.size < 2) return false
        val distinct = parts
            .mapNotNull { ChapterSplitter.chapterLevelOrderFromTitle(it.title) }
            .distinct()
            .size
        return distinct >= 2
    }

    /** 把整个文件压平成一坨纯文本（「一个文件就是一章」时用） */
    private fun flatten(parts: List<ChapterSplitter.Part>): String =
        parts.joinToString("\n\n") {
            if (it.title.isNotBlank() && it.content.isNotBlank()) "${it.title}\n\n${it.content}"
            else it.content
        }

    /**
     * 按文件名里的章号排序。
     *
     * 系统文件选择器返回的顺序通常和用户预期不一致（比如倒序、按文件系统顺序），
     * 而「第52章 取舍之间.txt」这种文件名本身已经带了先后。这里：
     *  · 解析得出章号的，按章号升序排在前面；
     *  · 解析不出来的，保持原相对顺序排到最后（不会因为排序丢文件）。
     * 章号相同时用文件名兜底比较，保证结果稳定。
     */
    private fun orderByFileName(uris: List<Uri>): List<Uri> =
        uris.withIndex()
            .sortedWith(
                compareBy(
                    { (i, u) -> ChapterSplitter.chapterOrderFromName(displayName(u) ?: "") ?: Int.MAX_VALUE },
                    { (_, u) -> displayName(u) ?: "" },
                    { (i, _) -> i }
                )
            )
            .map { it.value }

    /** 导入单个文件为一本书，返回章节数 */
    private fun importSingle(
        bytes: ByteArray,
        name: String,
        customTitle: String?,
        ids: MutableList<Long>
    ): Int {
        val parsedFile = parseFile(bytes, name)
        val parts = parsedFile.parts
        val metaTitle = parsedFile.metaTitle
        val author = parsedFile.author
        val sourceType = parsedFile.sourceType

        val title = customTitle?.takeIf { it.isNotBlank() }
            ?: metaTitle?.takeIf { it.isNotBlank() }
            ?: ChapterSplitter.titleFromFileName(name)

        val bookId = db.insertBook(
            Book(
                title = title,
                author = author,
                sourceType = sourceType,
                coverHue = hueFromString(title),
                createdAt = now()
            )
        )
        ids += bookId

        var totalChars = 0
        for ((i, p) in parts.withIndex()) {
            val body = prepareBody(sourceType, p.content)
            val fileName = files.newFileName()
            writeChapter(fileName, body)
            db.insertChapter(
                Chapter(
                    bookId = bookId,
                    indexInBook = i,
                    title = p.title.ifBlank { appContext.getString(R.string.label_chapter_n, i + 1) },
                    fileName = fileName,
                    charCount = body.text.length,
                    sourceName = name
                )
            )
            totalChars += body.text.length
        }
        db.updateBookStats(bookId, parts.size, totalChars)
        return parts.size
    }

    private fun guessMergedTitle(names: List<String>): String {
        if (names.isEmpty()) return appContext.getString(R.string.repo_merged_collection)
        if (names.size == 1) return appContext.getString(
            R.string.repo_collection_named, ChapterSplitter.titleFromFileName(names[0])
        )
        // 取共同前缀，否则用「首篇 等 N 篇」
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

    suspend fun addEmptyChapter(bookId: Long, title: String): Long = withContext(Dispatchers.IO) {
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
     *
     * @param splitMode 章节划分方式：SMART（默认，按文件内容逐个判断）/ IN_FILE / PER_FILE
     */
    suspend fun appendFilesAsChapters(
        bookId: Long,
        uris: List<Uri>,
        splitMode: SplitMode = SplitMode.SMART,
        onProgress: (String, Float) -> Unit = { _, _ -> }
    ): List<String> = withContext(Dispatchers.IO) {
        val errors = ArrayList<String>()
        val chapters = db.chaptersOf(bookId)
        var index = chapters.size
        val total = uris.size.coerceAtLeast(1)
        // 新插入的章节（id 与标题），用于随后按章号插到正确位置
        val inserted = ArrayList<Pair<Long, String>>()
        for ((i, uri) in uris.withIndex()) {
            val name = displayName(uri) ?: appContext.getString(R.string.repo_fallback_file_n, i + 1)
            onProgress(appContext.getString(R.string.repo_stage_parse, name), i.toFloat() / total)
            val bytes = try {
                readBytes(uri)
            } catch (e: ParseException) {
                errors += appContext.getString(R.string.err_file_reason, name, ParseErrors.reason(appContext, e)); continue
            }
            if (bytes == null) {
                errors += appContext.getString(R.string.err_file_reason, name, ParseErrors.reason(appContext, ParseErrorCode.READ_FAILED)); continue
            }
            val parsed = try {
                parseFile(bytes, name)
            } catch (e: ParseException) {
                errors += appContext.getString(R.string.err_file_reason, name, ParseErrors.reason(appContext, e)); continue
            }
            val fileTitle = ChapterSplitter.titleFromFileName(name)
            val units: List<Pair<String, String>> = splitUnits(parsed, fileTitle, splitMode)
            for ((title, content) in units) {
                val body = prepareBody(parsed.sourceType, content)
                val fileName = files.newFileName()
                writeChapter(fileName, body)
                val newId = db.insertChapter(
                    Chapter(
                        bookId = bookId, indexInBook = index++,
                        title = title, fileName = fileName,
                        charCount = body.text.length, sourceName = name
                    )
                )
                if (newId > 0) inserted += newId to title
            }
        }
        // 追加进来的章节如果文件名带章号（「第2章 …」），插到对应位置而不是一律扔到末尾
        insertByChapterOrder(bookId, inserted)
        onProgress(appContext.getString(R.string.repo_stage_done), 1f)
        refreshStats(bookId)
        errors
    }

    /**
     * 把刚插入的章节按章号移动到正确位置。
     *
     * 只移动「新章节」，已有章节之间的相对顺序不动 —— 插入第 2 章时，
     * 已有 [1, 3] 会变成 [1, 2, 3]，而不是把整本按章号重排一遍。
     * 解析不出章号的新章节保持追加在末尾。
     */
    private suspend fun insertByChapterOrder(bookId: Long, inserted: List<Pair<Long, String>>) {
        if (inserted.isEmpty()) return
        val withOrder = inserted.mapNotNull { (id, title) ->
            ChapterSplitter.chapterOrderFromTitle(title)?.let { id to it }
        }
        if (withOrder.isEmpty()) return
        val all = db.chaptersOf(bookId)
        val newIds = inserted.map { it.first }.toSet()
        val order = all.filter { it.id !in newIds }.toMutableList()
        // 章号小的先插，保证多章一起追加时它们的相对顺序也对
        withOrder.sortedBy { it.second }.forEach { (id, n) ->
            val ch = all.firstOrNull { it.id == id } ?: return@forEach
            val pos = order.indexOfFirst { exist ->
                val eo = ChapterSplitter.chapterOrderFromTitle(exist.title)
                eo != null && eo > n
            }
            if (pos >= 0) order.add(pos, ch) else order.add(ch)
        }
        // 剩下的（没有章号的）保持追加顺序接在后面
        inserted.mapNotNull { (id, _) -> all.firstOrNull { it.id == id } }
            .filter { ch -> order.none { it.id == ch.id } }
            .forEach { order.add(it) }
        if (order.size == all.size) reorderChapters(bookId, order.map { it.id })
    }

    /**
     * 按章节标题里的章号把整本重排一遍（章节管理里的「自动排序」）。
     *
     * 只动「能解析出章号」的章节：把它们按编号升序填回**原来占据的那几个位置**；
     * 认不出章号的（幕间、Interlude、没有编号的标题…）一律**钉在原位**，
     * 既不前移也不后甩 —— 早期实现把它们统一甩到书末，会把夹在正文中间的插曲排错。
     * 章号支持中文 / 英语 / 日语写法，`Chapter 10` 会排在 `Chapter 2` 之后。
     * 返回章节总数。
     */
    suspend fun autoSortChapters(bookId: Long): Int = withContext(Dispatchers.IO) {
        val all = db.chaptersOf(bookId)
        if (all.size <= 1) return@withContext all.size
        val orders = all.map { ChapterSplitter.chapterOrderFromTitle(it.title) }
        val slots = all.indices.filter { orders[it] != null }
        if (slots.size < 2) return@withContext all.size
        // 待排序的章节按（章号, 原下标）稳定升序，再依次填回原来的槽位
        val ranked = slots.sortedWith(compareBy({ orders[it]!! }, { it }))
        val sorted = all.toMutableList()
        slots.forEachIndexed { k, slot -> sorted[slot] = all[ranked[k]] }
        if (sorted.map { it.id } != all.map { it.id }) {
            reorderChapters(bookId, sorted.map { it.id })
        }
        sorted.size
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

    /** 解散分组：组内书籍回到未分组 */
    suspend fun deleteGroup(id: Long) = withContext(Dispatchers.IO) { db.deleteGroup(id) }

    /** 把若干本书放进分组；[groupId] = 0 表示移出分组 */
    suspend fun moveBooksToGroup(ids: List<Long>, groupId: Long) = withContext(Dispatchers.IO) {
        db.setBooksGroup(ids, groupId)
    }
    suspend fun chapters(bookId: Long): List<Chapter> = withContext(Dispatchers.IO) { db.chaptersOf(bookId) }

    /** 按 id 顺序取章节（导出用）；ids 为空表示整本 */
    suspend fun chaptersByIds(bookId: Long, ids: List<Long>): List<Chapter> = withContext(Dispatchers.IO) {
        val all = db.chaptersOf(bookId)
        if (ids.isEmpty()) all else ids.mapNotNull { id -> all.firstOrNull { it.id == id } }
    }

    suspend fun chapterText(chapter: Chapter): String =
        withContext(Dispatchers.IO) { files.read(chapter.fileName) }

    /**
     * 该章的样式覆盖（只有 Markdown 导入的章节才有）。
     *
     * 读盘时会校验长度：正文和覆盖对不上（比如以后改了正文）就返回 null，
     * 阅读端按普通正文走 —— 宁可没有格式，也不能错位或打不开。
     */
    suspend fun chapterRich(chapter: Chapter, textLength: Int): RichChapter? =
        withContext(Dispatchers.IO) {
            val raw = files.readRich(chapter.fileName)
            if (raw.isBlank()) return@withContext null
            RichChapter.decode(raw)?.takeIf { it.len == textLength }
        }

    suspend fun updateProgress(bookId: Long, chapterIndex: Int, offset: Int, progress: Float) =
        withContext(Dispatchers.IO) { db.updateProgress(bookId, chapterIndex, offset, progress) }

    suspend fun updateBookMeta(bookId: Long, title: String, author: String) =
        withContext(Dispatchers.IO) { db.updateBookMeta(bookId, title, author) }

    suspend fun deleteBook(bookId: Long) = withContext(Dispatchers.IO) {
        // 库里的删除已是一个事务；文件删除是 best-effort，个别文件删不掉不该让整次删除失败
        db.deleteBook(bookId).forEach { runCatching { files.deleteChapter(it) } }
    }

    suspend fun deleteChapter(bookId: Long, chapterId: Long) = withContext(Dispatchers.IO) {
        val ch = db.chaptersOf(bookId).firstOrNull { it.id == chapterId }
        // 删本章行 + 标记前移 + 删本章标记：几条 SQL 放一个事务，避免中途失败留下错位数据
        val file = db.inTransaction {
            val deleted = db.deleteChapter(chapterId)
            ch?.let {
                db.marksOf(bookId).filter { m -> m.chapterIndex > it.indexInBook }.forEach { m ->
                    db.updateMark(m.copy(chapterIndex = m.chapterIndex - 1))
                }
                db.deleteMarksInChapter(bookId, it.indexInBook)
            }
            deleted
        }
        if (file != null) runCatching { files.deleteChapter(file) }
        if (ch != null) {
            reindex(bookId)
            refreshStats(bookId)
        }
    }

    suspend fun renameChapter(chapterId: Long, title: String) =
        withContext(Dispatchers.IO) { db.updateChapterTitle(chapterId, title) }

    suspend fun moveChapter(bookId: Long, chapterId: Long, delta: Int) = withContext(Dispatchers.IO) {
        val list = db.chaptersOf(bookId).toMutableList()
        val from = list.indexOfFirst { it.id == chapterId }
        if (from < 0) return@withContext
        val to = from + delta
        if (to !in list.indices) return@withContext
        val item = list.removeAt(from)
        list.add(to, item)
        // 标记跟随章节移动
        val marks = db.marksOf(bookId)
        val oldIdx = item.indexInBook
        val newIdx = to
        db.reindexChapters(bookId, list.map { it.id })
        marks.forEach { m ->
            when (m.chapterIndex) {
                oldIdx -> db.updateMark(m.copy(chapterIndex = newIdx))
                in minOf(oldIdx, newIdx)..maxOf(oldIdx, newIdx) -> {
                    val shifted = if (oldIdx < newIdx) m.chapterIndex - 1 else m.chapterIndex + 1
                    db.updateMark(m.copy(chapterIndex = shifted))
                }
            }
        }
        refreshStats(bookId)
    }

    suspend fun reorderChapters(bookId: Long, orderedIds: List<Long>) = withContext(Dispatchers.IO) {
        val old = db.chaptersOf(bookId).associate { it.id to it.indexInBook }
        db.reindexChapters(bookId, orderedIds)
        val marks = db.marksOf(bookId)
        val newPos = orderedIds.withIndex().associate { (i, id) -> (old[id] ?: -1) to i }
        marks.forEach { m ->
            val np = newPos[m.chapterIndex]
            if (np != null && np != m.chapterIndex) db.updateMark(m.copy(chapterIndex = np))
        }
        refreshStats(bookId)
    }

    /** 保存章节正文（编辑器） */
    suspend fun saveChapterText(chapter: Chapter, text: String) = withContext(Dispatchers.IO) {
        files.write(chapter.fileName, text)
        // 手改过的正文和原来的样式覆盖对不上了，直接作废，免得按旧偏移乱套样式
        files.deleteRich(chapter.fileName)
        db.updateChapterFile(chapter.id, chapter.fileName, text.length)
        refreshStats(chapter.bookId)
    }

    private fun reindex(bookId: Long) {
        val list = db.chaptersOf(bookId)
        db.reindexChapters(bookId, list.map { it.id })
    }

    fun refreshStats(bookId: Long) {
        val list = db.chaptersOf(bookId)
        db.updateBookStats(bookId, list.size, list.sumOf { it.charCount })
    }

    // ==================== 自定义封面 ====================

    /**
     * 解码用户选的图片。先用 inJustDecodeBounds 量尺寸再按 2 的幂降采样，
     * 避免直接 decode 一张 5000×4000 的照片把内存打爆。
     */
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

    /** 保存裁切好的封面（JPEG 字节），返回私有目录里的文件名 */
    suspend fun saveCover(bookId: Long, jpeg: ByteArray): String = withContext(Dispatchers.IO) {
        val name = files.coverNameFor(bookId)
        files.writeCover(name, jpeg)
        db.updateBookCover(bookId, name)
        name
    }

    /** 恢复默认封面：清库里的字段 + 删掉私有目录里的图 */
    suspend fun clearCover(bookId: Long) = withContext(Dispatchers.IO) {
        files.deleteCover(files.coverNameFor(bookId))
        db.updateBookCover(bookId, "")
    }

    // ==================== 标记 ====================

    suspend fun marks(bookId: Long, chapterIndex: Int = -1): List<Mark> =
        withContext(Dispatchers.IO) { db.marksOf(bookId, chapterIndex) }

    suspend fun addMark(mark: Mark): Long = withContext(Dispatchers.IO) { db.insertMark(mark) }

    suspend fun updateMark(mark: Mark) = withContext(Dispatchers.IO) { db.updateMark(mark) }

    suspend fun deleteMark(id: Long) = withContext(Dispatchers.IO) { db.deleteMark(id) }

    suspend fun toggleBookmark(bookId: Long, chapterIndex: Int, offset: Int): Boolean =
        withContext(Dispatchers.IO) {
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
        if (keyword.isBlank()) return@withContext emptyList()
        val chapters = db.chaptersOf(bookId)
        val out = ArrayList<SearchHit>()
        for ((i, ch) in chapters.withIndex()) {
            onProgress(chapters.size.toFloat().let { (i.toFloat() / it) })
            val text = files.read(ch.fileName)
            var from = 0
            while (out.size < maxHits) {
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

    /**
     * 读取选择器返回的文件。
     *
     * 标准做法是走 ContentResolver；但部分 ROM 的自带文件管理器会回 `file://` 而不是
     * `content://`（它自己有完整存储权限，就不走 provider 了），这种情况再退一步直接用
     * File 读。两条路都失败才返回 null，由调用方给出「读取失败」提示。
     */
    private fun readBytes(uri: Uri): ByteArray? {
        // 先按「声明大小」快速失败，省掉一次无谓的整文件读取；声明缺失/不准时，
        // 由 readBounded 边读边数兜底 —— 两条路都保证不会把超大文件整个读进内存。
        declaredSize(uri)?.let { if (it > MAX_IMPORT_BYTES) throw ParseException(ParseErrorCode.TOO_LARGE) }
        val stream = try {
            appContext.contentResolver.openInputStream(uri)
        } catch (t: Throwable) {
            null
        }
        if (stream != null) {
            stream.use { return readBounded(it, MAX_IMPORT_BYTES) }
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
            fs.use { return readBounded(it, MAX_IMPORT_BYTES) }
        }
        return null
    }

    /** 文件的「声明大小」；provider 或文件系统给不出时返回 null */
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

    /** 分块读取，累计超过 [limit] 立即抛 TOO_LARGE，绝不把超大文件整个读进内存 */
    private fun readBounded(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n.toLong() > limit) throw ParseException(ParseErrorCode.TOO_LARGE)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        /** 单个文件的导入体积上限；超过直接拒绝，避免读入内存把进程打爆 */
        const val MAX_IMPORT_BYTES = 200L * 1024 * 1024
    }
}

/** 由字符串稳定生成一个 0..359 的色相，用于自动封面配色 */
fun hueFromString(s: String): Int {
    if (s.isEmpty()) return 32
    var h = 7
    for (c in s) h = (h * 31 + c.code) % 100000
    return h % 360
}
