package com.inkreader.data.repo

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.inkreader.R
import com.inkreader.data.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 作业配置原子落盘，逐文件完成凭据与章节在同一SQLite事务提交；重启不重复导入已完成文件。 */
class ImportJournal(private val context: Context, private val repo: BookRepository) {
    companion object { private val gate = Mutex() }
    private val file = File(context.filesDir, "pending-import.json")
    fun pending() = file.isFile || File(file.path + ".bak").isFile
    private fun read(): JSONObject {
        require(file.length() <= 4L * 1024 * 1024)
        val job = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
        require(job.getInt("version") == 1 && Regex("[a-f0-9-]{36}").matches(job.getString("id")))
        require(job.getJSONArray("uris").length() in 1..10000)
        return job
    }
    private fun save(job: JSONObject) {
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try { val bytes = job.toString().toByteArray(); require(bytes.size <= 4 * 1024 * 1024)
            output.write(bytes); atomic.finishWrite(output)
        } catch (t: Throwable) { atomic.failWrite(output); throw t }
    }
    suspend fun discard() = gate.withLock { finish() }
    private suspend fun finish() {
        val job = runCatching { read() }.getOrNull()
        val id = job?.getString("id")
        if (id != null && job.getLong("target") == 0L) {
            repo.importReceipt("$id:target")?.first?.let { target ->
                repo.removeEmptyImportTarget(target)
            }
        }
        AtomicFile(file).delete()
        if (!pending() && id != null) repo.clearImportReceipts(id)
    }
    private fun encode(options: SplitOptions) = JSONObject()
        .put("mode", options.mode.name).put("clean", options.cleanWatermark).put("pattern", options.customPattern)
        .put("chunk", options.fixedChunkChars).put("encoding", options.textEncoding)
        .put("ignored", JSONArray(options.ignoredHeadingLines.toList())).put("forced", JSONArray(options.forcedHeadingLines.toList()))
        .put("remember", options.rememberCorrections).put("memory", options.useSavedCorrections).put("fingerprint", options.sourceFingerprint)
        .put("unnumbered", options.detectUnnumberedTitles)
    private fun decode(json: JSONObject): SplitOptions {
        fun lines(name: String): Set<Int> = json.getJSONArray(name).let { array ->
            require(array.length() <= 10000); (0 until array.length()).map { array.getInt(it).also { line -> require(line > 0) } }.toSet()
        }
        val options = SplitOptions(mode = SplitMode.valueOf(json.getString("mode")), cleanWatermark = json.getBoolean("clean"),
            customPattern = json.getString("pattern"), fixedChunkChars = json.getInt("chunk"), textEncoding = json.getString("encoding"),
            ignoredHeadingLines = lines("ignored"), forcedHeadingLines = lines("forced"),
            rememberCorrections = json.getBoolean("remember"), useSavedCorrections = json.getBoolean("memory"),
            sourceFingerprint = json.getString("fingerprint"), detectUnnumberedTitles = json.optBoolean("unnumbered", false))
        require(options.ignoredHeadingLines.size + options.forcedHeadingLines.size <= 10000)
        return options
    }
    suspend fun start(uris: List<Uri>, mode: ImportMode, title: String?, options: SplitOptions, order: ImportOrder,
        target: Long, progress: (String, Float) -> Unit): BookRepository.ImportResult = withContext(Dispatchers.IO) { gate.withLock {
        check(!pending()) { context.getString(R.string.recovery_existing) }
        require(uris.size in 1..10000)
        val ordered = if (order == ImportOrder.AUTO) repo.orderByFileName(uris) else uris
        val job = JSONObject().put("version", 1).put("id", UUID.randomUUID().toString())
            .put("uris", JSONArray(ordered.map { it.toString() })).put("mode", mode.name).put("title", title ?: JSONObject.NULL)
            .put("options", encode(options)).put("order", order.name).put("target", target)
        save(job)
        execute(job, progress)
    } }
    suspend fun resume(progress: (String, Float) -> Unit): BookRepository.ImportResult = withContext(Dispatchers.IO) { gate.withLock { execute(read(), progress) } }
    private suspend fun execute(job: JSONObject, progress: (String, Float) -> Unit): BookRepository.ImportResult {
        val id = job.getString("id"); val array = job.getJSONArray("uris")
        val uris = (0 until array.length()).map { array.getString(it).also { value -> require(value.length <= 8192) } }.map { Uri.parse(it) }
        val options = decode(job.getJSONObject("options")); val mode = ImportMode.valueOf(job.getString("mode"))
        val order = ImportOrder.valueOf(job.getString("order"))
        val title = if (job.isNull("title")) null else job.getString("title")
        var target = job.getLong("target")
        val appending = target > 0
        if (mode == ImportMode.MERGE && uris.size > 1 && target == 0L)
            target = repo.createImportTarget(id, title?.takeIf { it.isNotBlank() } ?: repo.guessMergedTitle(uris.mapNotNull { repo.displayName(it) }))
        val results = ArrayList<BookRepository.ImportFileResult>(); val bookIds = LinkedHashSet<Long>()
        val inserted = ArrayList<Long>()
        for ((index, uri) in uris.withIndex()) {
            currentCoroutineContext().ensureActive()
            val name = repo.displayName(uri) ?: context.getString(R.string.repo_fallback_file_n, index + 1)
            progress(context.getString(R.string.repo_stage_parse, name), index.toFloat() / uris.size)
            val key = "$id:$index"
            val existing = repo.importReceipt(key)
            if (existing == null) {
                if (target > 0) {
                    check(repo.book(target) != null) { context.getString(R.string.recovery_target_missing) }
                    val errors = repo.appendFilesAsChapters(target, listOf(uri), splitOptions = options,
                        fileOrder = ImportOrder.SELECTION, receipt = key)
                    if (errors.isNotEmpty()) results += BookRepository.ImportFileResult(name, false, errors.joinToString("; "))
                } else {
                    val result = repo.importDocuments(listOf(uri), ImportMode.SEPARATE, title,
                        splitOptions = options, fileOrder = ImportOrder.SELECTION, receipt = key) { _, _ -> }
                    results += result.failed
                }
            }
            val receipt = repo.importReceipt(key)
            if (receipt != null) {
                bookIds += receipt.first; inserted += receipt.second
                results += BookRepository.ImportFileResult(name, true, bookId = receipt.first, chapterCount = receipt.second.size)
                if (target == 0L && order == ImportOrder.AUTO && options.mode == SplitMode.SMART && repo.book(receipt.first) != null)
                    repo.autoSortChapters(receipt.first)
            }
        }
        // 较早文件失败而较晚文件已提交时，重试补入的章不能留在队尾。
        if (target > 0) repo.restoreImportSourceOrder(target, inserted)
        if (target > 0 && order == ImportOrder.AUTO) {
            if (appending) repo.finishImportOrder(target, inserted)
            else if (options.mode == SplitMode.SMART && uris.count { com.inkreader.parse.ChapterSplitter.chapterOrderFromName(repo.displayName(it).orEmpty()) != null } < 2)
                repo.autoSortChapters(target)
        }
        if (!appending && target > 0 && repo.removeEmptyImportTarget(target)) {
            // 空目标删除后，允许恢复时重新创建；没有任何成功源需要跳过。
            repo.clearImportReceipts(id)
        }
        if (results.size == uris.size && results.all { it.ok }) finish()
        progress(context.getString(R.string.repo_stage_done), 1f)
        return BookRepository.ImportResult(bookIds.toList(), results)
    }
}
