package com.inkreader

import android.app.Instrumentation
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import com.inkreader.data.model.*
import com.inkreader.data.repo.BookRepository
import com.inkreader.parse.*
import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** End-to-end parser/preview/import checks in AuditInstrumentation's disposable storage. */
object RecognitionChecks {
    suspend fun run(runner: Instrumentation, context: Context): JSONObject {
        val checks = JSONArray()
        val repo = BookRepository(context)
        suspend fun test(name: String, block: suspend () -> JSONObject) {
            val start = SystemClock.elapsedRealtime()
            val row = JSONObject().put("name", name)
            try { row.put("details", block()).put("status", "PASS") }
            catch (t: Throwable) { row.put("status", "FAIL").put("error", t.stackTraceToString()) }
            row.put("ms", SystemClock.elapsedRealtime() - start)
            checks.put(row)
            runner.sendStatus(0, Bundle().apply { putString("stream", row.toString() + "\n") })
        }
        try {
            test("existing 154-file multilingual corpus preserves previous recognition and all body blocks") {
                val assets = runner.context.assets
                val manifest = JSONArray(assets.open("txt-corpus/manifest.json").bufferedReader().use { it.readText() })
                var limitations = 0
                var rejected = 0
                for (index in 0 until manifest.length()) {
                    val item = manifest.getJSONObject(index)
                    val id = item.getString("id")
                    val name = item.getString("file")
                    val source = File(context.cacheDir, name).apply {
                        writeBytes(assets.open("txt-corpus/$name").use { it.readBytes() })
                    }
                    val result = repo.importDocuments(listOf(Uri.fromFile(source)), ImportMode.SEPARATE, null,
                        splitOptions = SplitOptions(cleanWatermark = false)) { _, _ -> }
                    if (item.optBoolean("reject")) {
                        check(result.bookIds.isEmpty() && result.failed.size == 1) { "$id must be rejected" }
                        rejected++
                        continue
                    }
                    check(result.failed.isEmpty()) { "$id import: ${result.failed}" }
                    val chapters = repo.chapters(result.bookIds.single())
                    val actual = chapters.map { it.title }
                    val expected = item.optJSONArray("titles")
                    if (expected != null && !item.optBoolean("plain")) {
                        val titles = (0 until expected.length()).map { expected.getString(it) }
                        check(actual == titles) { "$id titles: $actual expected $titles" }
                    }
                    item.optJSONArray("desired_titles")?.let { desired ->
                        if (actual != (0 until desired.length()).map { desired.getString(it) }) limitations++
                    }
                    val body = chapters.map { repo.chapterText(it) }.joinToString("\n\n")
                    val blocks = item.getJSONArray("blocks")
                    val expectedBlocks = (0 until blocks.length()).map { blocks.getString(it) }.groupingBy { it }.eachCount()
                    for ((block, count) in expectedBlocks) {
                        check(Regex(Regex.escape(block)).findAll(body).count() == count) { "$id body block lost/duplicated, expected $count" }
                    }
                    check('\uFFFD' !in body) { "$id has replacement characters" }
                    repo.deleteBook(result.bookIds.single())
                }
                JSONObject().put("cases", manifest.length()).put("rejectedEmpty", rejected)
                    .put("recognitionLimitations", limitations)
            }
            test("all independently annotated gold files decode and split on Android") {
                val assets = runner.context.assets
                val ids = assets.open("recognition-gold/manifest.tsv").bufferedReader().use { it.readLines() }.drop(1)
                    .filter { it.isNotBlank() }.map { it.substringBefore('\t') }
                val bundled = assets.list("recognition-gold").orEmpty().filter { it.endsWith(".gold") }.map { it.removeSuffix(".gold") }.toSet()
                check(ids.toSet() == bundled) { "Bundled gold manifest does not match its cases" }
                var headings = 0
                for (id in ids) {
                    val source = File(context.cacheDir, "$id.txt").apply { writeBytes(assets.open("recognition-gold/$id.txt").use { it.readBytes() }) }
                    val gold = assets.open("recognition-gold/$id.gold").bufferedReader().use { it.readLines() }
                    val titles = gold.filter { it.startsWith("T\t") }.map { it.substring(2) }
                    val markers = gold.filter { it.startsWith("P\t") }.map { it.substring(2) }
                    val uri = Uri.fromFile(source)
                    val options = SplitOptions(cleanWatermark = false)
                    val preview = repo.previewImport(listOf(uri), ImportMode.SEPARATE, options, ImportOrder.SELECTION)
                    check(preview.sampledFiles == 1 && preview.failedFiles == 0)
                    check(preview.analysis == null || preview.analysis.parts.isEmpty()) { "$id preview retains the full book body" }
                    val result = repo.importDocuments(listOf(uri), ImportMode.SEPARATE, null,
                        splitOptions = options, fileOrder = ImportOrder.SELECTION) { _, _ -> }
                    check(result.failed.isEmpty()) { "$id: ${result.failed}" }
                    val chapters = repo.chapters(result.bookIds.single())
                    check(chapters.size == preview.estimatedChapters) { "$id preview/import count differs" }
                    if (titles.isNotEmpty()) check(chapters.map { it.title } == titles) { "$id: ${chapters.map { it.title }}" }
                    else check(chapters.size == 1)
                    val body = chapters.map { repo.chapterText(it) }.joinToString("\n")
                    for (marker in markers) check(Regex(Regex.escape(marker)).findAll(body).count() == 1) { "$id: $marker" }
                    check('\uFFFD' !in body)
                    headings += titles.size
                    repo.deleteBook(result.bookIds.single())
                }
                JSONObject().put("cases", ids.size).put("expectedHeadings", headings)
            }
            test("manual encoding choice and ignored boundary match the actual imported body") {
                val body = "こんにちは。これは読書の物語です。".repeat(25)
                val text = listOf("第一章 朝", "第二章 昼", "第三章 夜").joinToString("\n\n") { "$it\n\n$body" }
                val file = File(context.cacheDir, "manual-codec.txt").apply { writeBytes(text.toByteArray(Charset.forName("windows-31j"))) }
                val uri = Uri.fromFile(file)
                val options = SplitOptions(textEncoding = "windows-31j", cleanWatermark = false)
                val initial = repo.previewImport(listOf(uri), ImportMode.SEPARATE, options)
                val middle = initial.analysis!!.decisions.single { it.title == "第二章 昼" }
                val changed = options.copy(ignoredHeadingLines = setOf(middle.line))
                val preview = repo.previewImport(listOf(uri), ImportMode.SEPARATE, changed)
                check(preview.estimatedChapters == 2)
                val result = repo.importDocuments(listOf(uri), ImportMode.SEPARATE, null,
                    splitOptions = changed, fileOrder = ImportOrder.SELECTION) { _, _ -> }
                val chapters = repo.chapters(result.bookIds.single())
                check(chapters.map { it.title } == listOf("第一章 朝", "第三章 夜"))
                check("第二章 昼" in repo.chapterText(chapters.first()))
                check(chapters.sumOf { Regex(Regex.escape(body)).findAll(repo.chapterText(it)).count() } == 3)
                JSONObject().put("chapters", chapters.size)
            }
            test("all unreadable previews report zero chapters and sampled counts") {
                val empty = File(context.cacheDir, "empty-preview.txt").apply { writeText("") }
                val preview = repo.previewImport(listOf(Uri.fromFile(empty)), ImportMode.SEPARATE, SplitOptions())
                check(preview.estimatedChapters == 0 && preview.sampledFiles == 0 && preview.failedFiles == 1)
                JSONObject().put("estimatedChapters", preview.estimatedChapters)
            }
            test("cancelled large preview stops and a new small preview remains usable") {
                val file = File(context.cacheDir, "cancel-preview.txt").apply { writeText("正文测试。".repeat(1_500_000)) }
                val parent = Job()
                val job = CoroutineScope(Dispatchers.IO + parent).launch {
                    repo.previewImport(listOf(Uri.fromFile(file)), ImportMode.SEPARATE, SplitOptions())
                }
                delay(50)
                val start = SystemClock.elapsedRealtime()
                parent.cancel()
                withTimeout(8000) { job.join() }
                val small = File(context.cacheDir, "after-cancel.txt").apply { writeText("第一章\n\n完整正文。") }
                check(repo.previewImport(listOf(Uri.fromFile(small)), ImportMode.SEPARATE, SplitOptions()).estimatedChapters == 1)
                JSONObject().put("cancelAndRecoveryMs", SystemClock.elapsedRealtime() - start)
            }
            test("concurrent analyses do not share temporary decisions or overrides") {
                val text = (1..80).joinToString("\n\n") { "第${it}章 标题\n\n" + "完整正文。".repeat(40) }
                coroutineScope {
                    (1..8).map { index -> async(Dispatchers.Default) {
                        val result = ChapterSplitter.analyze(text, options = SplitOptions(cleanWatermark = false,
                            ignoredHeadingLines = if (index % 2 == 0) setOf(1) else emptySet()))
                        check(result.parts.size == if (index % 2 == 0) 79 else 80)
                    } }.awaitAll()
                }
                JSONObject().put("workers", 8)
            }
            ExtendedRecognitionChecks.run(runner, context, repo, ::test)
            UpgradeChecks.run(context, repo, ::test)
            AuditFollowupChecks.run(context, repo, ::test)
            test("original database invariants hold after recognition checks") {
                repo.db.readableDatabase.rawQuery("PRAGMA integrity_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok") }
                repo.books(ShelfSort.ADDED).forEach { book ->
                    val chapters = repo.chapters(book.id)
                    check(book.totalChapters == chapters.size)
                    check(book.totalChars == chapters.sumOf { it.charCount })
                }
                JSONObject().put("integrity", "ok")
            }
            test("mismatched book and chapter IDs cannot delete another book's content") {
                val first = repo.createManualBook("ownership-first", "")
                val second = repo.createManualBook("ownership-second", "")
                val chapter = repo.chapters(second).single()
                repo.saveChapterText(chapter, "KEEP_OTHER_BOOK_BODY")
                repo.addMark(Mark(bookId = second, chapterIndex = 0, startOffset = 0, endOffset = 4))
                repo.deleteChapter(first, chapter.id)
                check(repo.chapters(second).single().id == chapter.id)
                check(repo.chapterText(repo.chapters(second).single()) == "KEEP_OTHER_BOOK_BODY")
                check(repo.marks(second).size == 1)
                check(repo.book(second)!!.totalChapters == 1)
                JSONObject().put("protectedBooks", 2)
            }
        } finally { repo.db.close() }
        val failed = (0 until checks.length()).count { checks.getJSONObject(it).getString("status") != "PASS" }
        return JSONObject().put("checks", checks).put("passed", checks.length() - failed).put("failed", failed)
    }
}
