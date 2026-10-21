package com.inkreader.data.export

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.inkreader.R
import com.inkreader.data.model.Book
import com.inkreader.data.model.Chapter
import com.inkreader.parse.ChapterSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** 行尾统一 CRLF，保证 Windows 记事本与手机自带阅读器都不错行 */
private const val CRLF = "\r\n"

/** UTF-8 BOM（EF BB BF），少了它部分阅读器会按 GBK 猜编码导致乱码 */
private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

private val CRLF_BYTES = CRLF.toByteArray(Charsets.UTF_8)

private const val EXT_TXT = ".txt"
private const val MIME_TXT = "text/plain"

/** 分享副本所在目录：cacheDir/exports/ */
private const val EXPORT_DIR = "exports"

/** 与 AndroidManifest 里 FileProvider 的 authorities 保持一致 */
private const val AUTHORITY_SUFFIX = ".fileprovider"

/** 文件名主体（不含扩展名）的最大长度，过长只截断主体、保留 .txt */
private const val MAX_BASE_NAME = 80

/** Windows / Android 通用非法文件名字符，外加控制字符与 DEL */
private val ILLEGAL_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")

/** 复制文件用的缓冲区，避免整本内容进内存 */
private const val COPY_BUFFER = 64 * 1024

/** 导出结果：文件落在哪、以及一个配好的分享 Intent */
data class ExportResult(
    val fileName: String,          // 例如 "活着.txt"
    val downloadPath: String?,     // 下载目录里对用户可见的路径，写失败时为 null
    val downloadOk: Boolean,       // 是否成功写进系统「下载」目录
    val shareUri: Uri,             // 一定是可分享的 content:// URI（FileProvider）
    val shareIntent: Intent,       // 已经 setType("text/plain") + EXTRA_STREAM + FLAG_GRANT_READ_URI_PERMISSION + setClipData 的 ACTION_SEND
    val byteCount: Long
)

/**
 * 整本 / 单章导出为 TXT。
 *
 * 只导出「章节标题 + 正文」，不读 Mark 表，因此导出内容里绝不会出现书签、高亮、笔记。
 * 正文逐章流式写入，不会把整本书拼成一个大字符串。
 */
object BookExporter {

    /**
     * 导出为 TXT。两种落盘都要做：
     *  (a) 系统「下载」目录（文件管理器里能找到）
     *  (b) 应用 cacheDir/exports/ 下的副本 —— 用于分享，因为 Android 10+ 拿不到下载目录的 File 句柄
     * 分享 Intent 里用 (b) 的 FileProvider URI。
     *
     * @param includeHeader true=写「书名 + 作者」文件头（整本导出）；
     *                      false=不写文件头（单章导出，只有章节标题 + 正文）
     * @param chapters 要导出的章节，按顺序（单章导出时就是 size==1 的列表）
     * @param readChapter 读单章正文的挂起函数，调用方会传 repo::chapterText；逐章流式写入
     * @param onProgress 0f..1f，在 IO 线程回调
     * 失败时抛异常（IOException 等），由 UI 捕获提示。
     */
    suspend fun export(
        context: Context,
        book: Book,
        chapters: List<Chapter>,
        includeHeader: Boolean,
        readChapter: suspend (Chapter) -> String,
        onProgress: (Float) -> Unit = {}
    ): ExportResult = withContext(Dispatchers.IO) {
        require(chapters.isNotEmpty()) { context.getString(R.string.export_no_chapters) }

        val fileName = buildFileName(context, book, chapters, includeHeader)

        // (b) 分享副本：先清掉上次导出的残留，避免 cache 无限增长
        val exportDir = File(context.cacheDir, EXPORT_DIR)
        if (!exportDir.isDirectory && !exportDir.mkdirs()) {
            throw IOException(context.getString(R.string.export_dir_create_failed, exportDir.absolutePath))
        }
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        exportDir.listFiles()?.forEach { old -> if (old.isDirectory && old.lastModified() < cutoff) old.deleteRecursively() }
        val sessionDir = File(exportDir, java.util.UUID.randomUUID().toString())
        check(sessionDir.mkdirs())
        val shareFile = File(sessionDir, fileName)
        try {
            writeTxt(context, shareFile, book, chapters, includeHeader, readChapter, onProgress)
        } catch (t: Throwable) {
            shareFile.delete()
            sessionDir.delete()
            throw t
        }
        val byteCount = shareFile.length()

        val shareUri = FileProvider.getUriForFile(
            context, context.packageName + AUTHORITY_SUFFIX, shareFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TXT
            putExtra(Intent.EXTRA_STREAM, shareUri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            putExtra(Intent.EXTRA_TITLE, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, fileName, shareUri)
        }

        // (a) 系统下载目录：整段失败都只降级为 downloadOk=false，不抛异常
        val downloadPath = try {
            copyToDownloads(context, shareFile, fileName)
        } catch (e: Exception) {
            null
        }

        onProgress(1f)
        ExportResult(
            fileName = fileName,
            downloadPath = downloadPath,
            downloadOk = downloadPath != null,
            shareUri = shareUri,
            shareIntent = shareIntent,
            byteCount = byteCount
        )
    }

    // ==================== 文件名 ====================

    /** 整本导出用书名，单章导出（且不写文件头）用「书名-章节标题」 */
    private fun buildFileName(
        context: Context,
        book: Book,
        chapters: List<Chapter>,
        includeHeader: Boolean
    ): String {
        val title = book.title.trim().ifEmpty { context.getString(R.string.repo_untitled_book) }
        val raw = if (!includeHeader && chapters.size == 1) {
            "$title-${chapterTitle(context, chapters.first(), 0)}"
        } else {
            title
        }
        return sanitizeFileName(context, raw)
    }

    /** 清洗非法字符、去掉首尾空格与点，过长截断主体并保留扩展名 */
    private fun sanitizeFileName(context: Context, raw: String): String {
        val fallback = context.getString(R.string.repo_untitled_book)
        var base = ILLEGAL_NAME_CHARS.replace(raw, "_").trim().trim('.').trim()
        if (base.isEmpty()) base = fallback
        if (base.length > MAX_BASE_NAME) {
            base = base.take(MAX_BASE_NAME).trim().trim('.').trim()
        }
        if (base.isEmpty()) base = fallback
        return base + EXT_TXT
    }

    private fun chapterTitle(context: Context, chapter: Chapter, position: Int): String =
        chapter.title.trim().ifEmpty { context.getString(R.string.label_chapter_n, position + 1) }

    // ==================== 写正文 ====================

    /** 逐章流式写入 TXT：BOM + 可选文件头 + 每章「空行 / 标题 / 空行 / 正文」 */
    private suspend fun writeTxt(
        context: Context,
        target: File,
        book: Book,
        chapters: List<Chapter>,
        includeHeader: Boolean,
        readChapter: suspend (Chapter) -> String,
        onProgress: (Float) -> Unit
    ) {
        val total = chapters.size.toFloat()
        onProgress(0f)
        BufferedOutputStream(FileOutputStream(target)).use { raw ->
            val out = TxtWriter(raw)
            out.writeBom()

            if (includeHeader) {
                out.writeLine(book.title.trim().ifEmpty { context.getString(R.string.repo_untitled_book) })
                val author = book.author.trim()
                if (author.isNotEmpty()) out.writeLine(context.getString(R.string.export_author_prefix) + author)
            }

            for ((i, chapter) in chapters.withIndex()) {
                // 文件开头不空行；紧跟文件头空一行；章与章之间空两行
                out.blankLines(
                    when {
                        !includeHeader && i == 0 -> 0
                        i == 0 -> 1
                        else -> 2
                    }
                )
                out.writeLine(chapterTitle(context, chapter, i))
                out.blankLines(1)
                out.writeText(ChapterSplitter.normalize(readChapter(chapter)))
                onProgress((i + 1) / total)
            }

            // 保证文件末尾一定有一个换行
            out.finish()
        }
    }

    // ==================== 落盘：系统下载目录 ====================

    /**
     * 复制到系统「下载」目录，返回给用户看的路径；任何失败都返回 null（不抛异常）。
     * 注意 MediaStore 遇到同名文件会自动改名，返回的路径只作提示用。
     */
    @Suppress("DEPRECATION")
    private fun copyToDownloads(context: Context, src: File, fileName: String): String? {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val displayPath = File(dir, fileName).absolutePath

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (writeViaMediaStore(context, src, fileName)) displayPath else null
        } else {
            // API ≤ 28 需要外部存储可写（还要有 WRITE_EXTERNAL_STORAGE 运行时权限）
            if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return null
            if (!dir.exists() && !dir.mkdirs()) return null
            var dest = File(dir, fileName)
            var suffix = 1
            while (dest.exists()) dest = File(dir, "${fileName.removeSuffix(EXT_TXT)} (${suffix++})$EXT_TXT")
            try {
                src.inputStream().use { input ->
                    FileOutputStream(dest).use { output -> copy(input, output) }
                }
                dest.absolutePath
            } catch (e: Exception) {
                dest.delete()
                null
            }
        }
    }

    /** API ≥ 29 走 MediaStore.Downloads，写前 IS_PENDING=1、写后置 0 */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeViaMediaStore(context: Context, src: File, fileName: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, MIME_TXT)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        try {
            val output = resolver.openOutputStream(uri)
                ?: throw IOException(context.getString(R.string.export_downloads_unwritable))
            output.use { out ->
                src.inputStream().use { input -> copy(input, out) }
            }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return true
        } catch (e: Exception) {
            // 失败时清掉 pending 记录，别在下载目录里留半个空文件
            runCatching { resolver.delete(uri, null, null) }
            return false
        }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
    }
}

/**
 * 极小的流式文本写入器：换行统一转 CRLF，并记录行尾换行数，
 * 以便精确控制「文件头后空一行 / 章与章空两行」，且不必把全文拼成一个大字符串。
 */
private class TxtWriter(private val out: OutputStream) {

    /** 是否已经写过内容（文件头也算）；文件开头不写空行 */
    private var hasText = false

    /** 结尾处连续换行的个数：0 表示最后一行还没收尾，1 表示无空行，n 表示 n-1 个空行 */
    private var trailingNewlines = 0

    fun writeBom() {
        out.write(UTF8_BOM)
    }

    /** 写一行（自动补 CRLF）；传空串等价于只写一个换行 */
    fun writeLine(line: String) {
        if (line.isNotEmpty()) writeText(line)
        newline()
    }

    /** 写正文：内部换行统一转 CRLF，末尾不额外补换行 */
    fun writeText(text: String) {
        if (text.isEmpty()) return
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        out.write(normalized.replace("\n", CRLF).toByteArray(Charsets.UTF_8))
        hasText = true
        var n = 0
        while (n < normalized.length && normalized[normalized.length - 1 - n] == '\n') n++
        trailingNewlines = n
    }

    /** 保证结尾恰好有 [count] 个空行；文件开头（还没写过内容）什么都不写 */
    fun blankLines(count: Int) {
        if (count <= 0 || !hasText) return
        if (trailingNewlines == 0) newline()
        while (trailingNewlines - 1 < count) newline()
    }

    /** 收尾：保证文件以换行结束 */
    fun finish() {
        if (trailingNewlines == 0) newline()
    }

    private fun newline() {
        out.write(CRLF_BYTES)
        trailingNewlines++
    }
}
