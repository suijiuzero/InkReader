package com.inkreader

import android.app.Instrumentation
import android.content.Context
import android.net.Uri
import android.util.Base64
import com.inkreader.data.model.*
import com.inkreader.data.repo.BookRepository
import com.inkreader.parse.*
import java.io.File
import java.io.Reader
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.*
import org.json.JSONObject

/** These checks use the instrumentation runner's disposable library, never the user's books. */
object ExtendedRecognitionChecks {
    suspend fun run(runner: Instrumentation, context: Context, repo: BookRepository,
        test: suspend (String, suspend () -> JSONObject) -> Unit) {
        test("64 frozen development/holdout files agree across Android preview and actual import") {
            val assets = runner.context.assets
            val frozen = JSONObject(assets.open("recognition-eval/frozen-sha256.json").bufferedReader().use { it.readText() })
            val rows = assets.open("recognition-eval/manifest.tsv").bufferedReader().use { it.readLines() }.drop(1).filter { it.isNotBlank() }
            val metrics = JSONObject()
            for (row in rows) {
                val (id, partition, language) = row.split('\t')
                val bytes = assets.open("recognition-eval/$id.txt").use { it.readBytes() }
                val goldBytes = assets.open("recognition-eval/$id.gold").use { it.readBytes() }
                for ((name, value) in listOf("$id.txt" to bytes, "$id.gold" to goldBytes)) {
                    val hash = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
                    check(frozen.getString(name) == hash) { "$id bundled input/annotation differs" }
                }
                val gold = String(goldBytes, Charsets.UTF_8).lines()
                val expected = gold.filter { it.startsWith("L\t") }.map { it.substring(2).toInt() }.toSet()
                val titles = gold.filter { it.startsWith("T\t") }.map { it.substring(2) }
                val paragraphs = gold.filter { it.startsWith("P64\t") }.map {
                    String(Base64.decode(it.substring(4), Base64.DEFAULT), Charsets.UTF_8).lines().joinToString("\n") { line -> line.trimEnd() }
                }
                val source = File(context.cacheDir, "$id.txt").apply { writeBytes(bytes) }
                val uri = Uri.fromFile(source); val options = SplitOptions(cleanWatermark = false)
                val preview = repo.previewImport(listOf(uri), ImportMode.SEPARATE, options, ImportOrder.SELECTION)
                check(preview.failedFiles == 0 && preview.analysis?.parts?.isEmpty() == true) { "$id preview" }
                val decisions = preview.analysis!!.decisions.filter { it.accepted }
                val actual = decisions.map { it.line }.toSet()
                val cohort = "$partition/$language"
                val c = metrics.optJSONObject(cohort) ?: JSONObject().put("cases", 0).put("TP", 0).put("FP", 0).put("FN", 0).also { metrics.put(cohort, it) }
                c.put("cases", c.getInt("cases") + 1).put("TP", c.getInt("TP") + (expected intersect actual).size)
                    .put("FP", c.getInt("FP") + (actual - expected).size).put("FN", c.getInt("FN") + (expected - actual).size)
                check(expected == actual && titles == decisions.map { it.title }) { "$id boundaries $actual expected $expected" }
                val result = repo.importDocuments(listOf(uri), ImportMode.SEPARATE, null, splitOptions = options,
                    fileOrder = ImportOrder.SELECTION) { _, _ -> }
                check(result.failed.isEmpty()) { "$id import ${result.failed}" }
                val book = result.bookIds.single(); val chapters = repo.chapters(book)
                check(preview.estimatedChapters == chapters.size) { "$id preview/import counts" }
                if (titles.isNotEmpty()) check(chapters.map { it.title } == titles) { "$id imported titles" }
                val body = chapters.map { repo.chapterText(it) }.joinToString("\n\n")
                for (paragraph in paragraphs) check(Regex(Regex.escape(paragraph)).findAll(body).count() == 1) { "$id paragraph lost/duplicated" }
                repo.deleteBook(book); source.delete()
            }
            JSONObject().put("cases", rows.size).put("cohorts", metrics).put("bodyErrors", 0)
        }
        test("per-book recognition corrections survive repository restart and can be disabled or forgotten") {
            val body = "正文保留测试。".repeat(40)
            val file = File(context.cacheDir, "remember-boundaries.txt").apply {
                writeText((1..3).joinToString("\n\n") { "第${it}章\n\n$body" })
            }
            val uri = Uri.fromFile(file); val options = SplitOptions(cleanWatermark = false)
            val preview = repo.previewImport(listOf(uri), ImportMode.SEPARATE, options)
            val middle = preview.analysis!!.decisions.single { it.title == "第2章" }.line
            val edited = options.copy(ignoredHeadingLines = setOf(middle))
            val imported = repo.importDocuments(listOf(uri), ImportMode.SEPARATE, null, splitOptions = edited) { _, _ -> }
            val id = imported.bookIds.single()
            check(repo.chapters(id).size == 2 && repo.savedCorrectionCount(id) == 3)
            val restarted = BookRepository(context)
            try {
                check(restarted.savedCorrectionCount(id) == 3)
                check(restarted.resplitBook(id, options) == 2)
                check(restarted.chapters(id).map { restarted.chapterText(it) }.joinToString().contains("第2章"))
                check(restarted.resplitBook(id, options.copy(useSavedCorrections = false)) == 3)
                check(restarted.resplitBook(id, options) == 2)
                restarted.clearRecognitionMemory(id)
                check(restarted.savedCorrectionCount(id) == 0 && restarted.resplitBook(id, options) == 3)
            } finally { restarted.db.close() }
            val another = repo.importDocuments(listOf(uri), ImportMode.SEPARATE, null, splitOptions = options) { _, _ -> }.bookIds.single()
            check(repo.chapters(another).size == 3 && repo.savedCorrectionCount(another) == 0)
            repo.deleteBook(another); repo.deleteBook(id)
            check(repo.savedCorrectionCount(id) == 0)
            JSONObject().put("rememberedAnchors", 3).put("bookIsolation", true)
        }
        test("preview cache reuses identical bytes and invalidates a changed file at the same URI") {
            val file = File(context.cacheDir, "cache-same-uri.txt").apply { writeText("第1章\n\n缓存原文。") }
            val uri = Uri.fromFile(file); val options = SplitOptions(cleanWatermark = false)
            repo.previewImport(listOf(uri), ImportMode.SEPARATE, options)
            val hits = repo.recognitionCacheHits
            val first = repo.importDocuments(listOf(uri), ImportMode.SEPARATE, null, splitOptions = options) { _, _ -> }.bookIds.single()
            check(repo.recognitionCacheHits == hits + 1); repo.deleteBook(first)
            file.writeText("第1章\n\n更新正文。\n\n第2章\n\n第二段正文。")
            val second = repo.importDocuments(listOf(uri), ImportMode.SEPARATE, null, splitOptions = options) { _, _ -> }.bookIds.single()
            check(repo.recognitionCacheHits == hits + 1 && repo.chapters(second).size == 2)
            check(repo.chapterText(repo.chapters(second).first()).contains("更新正文"))
            repo.deleteBook(second)
            JSONObject().put("validatedCacheHit", true).put("changedSourceInvalidated", true)
        }
        test("concurrent file previews remain isolated and cancelled work leaves no raw temporary file") {
            val files = (1..4).map { index -> File(context.cacheDir, "parallel-$index.txt").apply {
                writeText((1..index + 2).joinToString("\n\n") { "第${it}章\n\n" + "完整正文。".repeat(50) })
            } }
            coroutineScope { files.mapIndexed { i, file -> async(Dispatchers.IO) {
                check(repo.previewImport(listOf(Uri.fromFile(file)), ImportMode.SEPARATE, SplitOptions()).estimatedChapters == i + 3)
            } }.awaitAll() }
            check(File(context.cacheDir, "indexed-txt").walkTopDown().none { it.isFile && it.name.startsWith("raw-") })
            JSONObject().put("concurrentSources", 4).put("rawFiles", 0)
        }
        test("large TXT uses indexed preview and streamed import without losing chapter markers or statistics") {
            val source = File(context.cacheDir, "large-indexed.txt")
            val body = "完整正文🐉。".repeat(1600)
            source.bufferedWriter().use { writer -> repeat(400) { i ->
                writer.append("第${i + 1}章\n\n").append(body).append("MARK_${i + 1}_END\n\n")
            } }
            val uri = Uri.fromFile(source); val options = SplitOptions(cleanWatermark = false)
            val started = android.os.SystemClock.elapsedRealtime()
            val preview = repo.previewImport(listOf(uri), ImportMode.SEPARATE, options)
            check(preview.estimatedChapters == 400 && preview.analysis!!.parts.isEmpty())
            val result = repo.importDocuments(listOf(uri), ImportMode.SEPARATE, null, splitOptions = options) { _, _ -> }
            check(result.failed.isEmpty()) { result.failed.toString() }
            val id = result.bookIds.single(); val chapters = repo.chapters(id)
            check(chapters.size == 400)
            for ((i, chapter) in chapters.withIndex()) {
                val text = repo.chapterText(chapter)
                check(text == body + "MARK_${i + 1}_END") { "Large TXT body changed at $i" }
                check(chapter.charCount == text.length && '\uFFFD' !in text)
            }
            check(repo.book(id)!!.totalChars == chapters.sumOf { it.charCount })
            val perFile = repo.previewImport(listOf(uri), ImportMode.SEPARATE, options.copy(mode = SplitMode.PER_FILE))
            check(perFile.estimatedChapters == 1 && perFile.analysis!!.parts.isEmpty())
            repo.deleteBook(id)
            JSONObject().put("sourceBytes", source.length()).put("chapters", 400)
                .put("ms", android.os.SystemClock.elapsedRealtime() - started).also { source.delete() }
        }
        test("streamed UTF-8 writer preserves split surrogate pairs and rolls back reader failures") {
            val name = repo.files.newFileName()
            val body = "x".repeat(16383) + "🐉" + "尾声"
            check(repo.files.write(name, body.reader()) == body.length)
            check(repo.files.read(name) == body)
            val broken = object : Reader() {
                var read = false
                override fun read(buffer: CharArray, offset: Int, count: Int): Int {
                    if (read) throw java.io.IOException("injected read failure")
                    read = true; buffer[offset] = 'x'; return 1
                }
                override fun close() = Unit
            }
            try { repo.files.write(name, broken); error("Expected reader failure") } catch (_: java.io.IOException) { }
            check(repo.files.read(name) == body) { "Atomic writer replaced good content on failure" }
            repo.files.deleteChapter(name)
            JSONObject().put("surrogatePairPreserved", true).put("atomicRollback", true)
        }
    }
}
