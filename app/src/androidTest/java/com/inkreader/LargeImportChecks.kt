package com.inkreader

import android.app.Instrumentation
import android.content.Context
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import com.inkreader.data.model.*
import com.inkreader.data.repo.BookRepository
import com.inkreader.parse.ChapterSplitter
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/** Generated 13 MB novels through the real preview, journal, SQLite and AtomicFile paths. */
object LargeImportChecks {
    private const val SAMPLE_INTERVAL_MS = 100L

    /** Sample without forcing GC so the measurements include ordinary allocation pressure. */
    private suspend fun <T> measure(stages: JSONObject, name: String, work: suspend () -> T): T {
        val runtime = Runtime.getRuntime()
        fun heapKiB() = (runtime.totalMemory() - runtime.freeMemory()) / 1024
        val startHeap = heapKiB()
        val startPss = Debug.getPss()
        var peakHeap = startHeap
        var peakPss = startPss
        var samples = 1
        val sampler = thread(name = "large-import-memory") {
            while (!Thread.currentThread().isInterrupted) {
                try { Thread.sleep(SAMPLE_INTERVAL_MS) } catch (_: InterruptedException) { break }
                peakHeap = maxOf(peakHeap, heapKiB())
                peakPss = maxOf(peakPss, Debug.getPss())
                samples++
            }
        }
        val started = SystemClock.elapsedRealtime()
        try {
            return work()
        } finally {
            val elapsed = SystemClock.elapsedRealtime() - started
            sampler.interrupt()
            sampler.join()
            stages.put(name, JSONObject().put("elapsedMs", elapsed).put("startHeapKiB", startHeap)
                .put("sampledPeakHeapKiB", maxOf(peakHeap, heapKiB())).put("startPssKiB", startPss)
                .put("sampledPeakPssKiB", maxOf(peakPss, Debug.getPss())).put("samples", samples + 1))
        }
    }

    suspend fun run(
        runner: Instrumentation, context: Context, style: String? = null,
        repeats: Int = 1, changeBoundaries: Boolean = false
    ): JSONObject {
        require(style == null || style == "long" || style == "short")
        require(repeats in 1..5)
        val repo = BookRepository(context)
        val rows = JSONArray()
        try {
            val paragraph = "这是一段用于大书导入性能测试的普通正文。人物继续赶路，在天亮之前抵达城门，随后整理行李并阅读来信。没有广告，也不包含章节引用。".repeat(9)
            for (short in listOf(false, true)) {
                if (style != null && style != if (short) "short" else "long") continue
                val file = File(context.cacheDir, if (short) "generated-short.txt" else "generated-long.txt")
                val line = if (short) paragraph.chunked(25).joinToString("\n") else paragraph
                val body = List(6) { line }.joinToString("\n\n")
                file.bufferedWriter().use { out ->
                    for (n in 1..1320) out.write("第${n}章 测速章节\n\n$body\n\n")
                }
                val uri = Uri.fromFile(file)
                val options = SplitOptions()
                val stages = JSONObject()
                val preview = measure(stages, "previewCold") {
                    repo.previewImport(listOf(uri), ImportMode.SEPARATE, options)
                }
                check(preview.estimatedChapters == 1320 && preview.failedFiles == 0)
                val repeated = measure(stages, "previewWarm") {
                    repo.previewImport(listOf(uri), ImportMode.SEPARATE, options)
                }
                check(repeated.estimatedChapters == 1320 && repeated.failedFiles == 0)
                val progress = ArrayList<Float>(); var saves = 0
                val result = measure(stages, "import") {
                    repo.importResumable(listOf(uri), ImportMode.SEPARATE, null, options, ImportOrder.SELECTION) { stage, fraction ->
                        check(fraction in 0f..1f && (progress.isEmpty() || fraction >= progress.last()))
                        progress += fraction
                        if (stage == context.getString(R.string.repo_stage_save_chapters, 1320, 1320)) saves++
                    }
                }
                check(result.failed.isEmpty() && result.bookIds.size == 1 && saves == 1)
                check(!repo.hasIncompleteImport())
                val bookId = result.bookIds.single()
                val expected = ChapterSplitter.cleanBodyWatermarks(body)
                suspend fun checkBook() {
                    val chapters = repo.chapters(bookId)
                    check(chapters.size == 1320)
                    for ((i, chapter) in chapters.withIndex()) {
                        check(chapter.indexInBook == i && chapter.title == "第${i + 1}章 测速章节")
                        check(repo.chapterText(chapter) == expected) { "Body mismatch at chapter $i" }
                    }
                }
                checkBook()
                val anchorChapter = 661
                val anchorOffset = 5
                val markedText = expected.substring(anchorOffset, anchorOffset + 12)
                val originalMark = if (changeBoundaries) {
                    repo.addMark(Mark(bookId = bookId, chapterIndex = anchorChapter, startOffset = anchorOffset,
                        endOffset = anchorOffset + markedText.length))
                    repo.updateProgress(bookId, anchorChapter, anchorOffset, .5f)
                    repo.marks(bookId).single()
                } else null
                val resplitOptions = if (changeBoundaries) {
                    // Reconstructed source is title, blank line, exact body and a blank separator.
                    val stride = expected.count { it == '\n' } + 4
                    options.copy(ignoredHeadingLines = (1 until 1320 step 2).mapTo(HashSet()) { 1 + it * stride })
                } else options
                repeat(repeats) { attempt ->
                    val name = "resplit" + if (attempt == 0) "" else "-${attempt + 1}"
                    val progressTimes = JSONObject()
                    val oldFiles = repo.chapters(bookId).map { it.fileName }
                    val started = SystemClock.elapsedRealtime()
                    check(measure(stages, name) {
                        repo.resplitBook(bookId, resplitOptions) { progress ->
                            val point = when (progress) {
                                .06f -> "sourceRebuilt"
                                .12f -> "normalized"
                                .16f -> "planned"
                                .20f -> "split"
                                .24f -> "aligned"
                                .3f -> "undoReady"
                                .85f -> "filesStaged"
                                else -> null
                            }
                            if (point != null && !progressTimes.has(point)) {
                                progressTimes.put(point, SystemClock.elapsedRealtime() - started)
                            }
                        }
                    } == if (changeBoundaries) 660 else 1320)
                    val chapters = repo.chapters(bookId)
                    val oldFileSet = oldFiles.toHashSet()
                    val reused = chapters.count { it.fileName in oldFileSet }
                    check(reused == if (changeBoundaries) 0 else 1320)
                    stages.getJSONObject(name).put("progressElapsedMs", progressTimes).put("reusedChapterFiles", reused)
                    if (originalMark != null) {
                        for ((i, chapter) in chapters.withIndex()) {
                            check(chapter.indexInBook == i && chapter.title == "第${i * 2 + 1}章 测速章节")
                            check(repo.chapterText(chapter) == "$expected\n\n第${i * 2 + 2}章 测速章节\n\n$expected") {
                                "Merged body mismatch at chapter $i"
                            }
                        }
                        val target = anchorChapter / 2
                        val offset = expected.length + "\n\n第${anchorChapter + 1}章 测速章节\n\n".length + anchorOffset
                        val mark = repo.marks(bookId).single()
                        check(mark.id == originalMark.id && mark.chapterIndex == target && mark.startOffset == offset)
                        check(repo.chapterText(chapters[target]).substring(mark.startOffset, mark.endOffset) == markedText)
                        check(repo.book(bookId)!!.let { it.lastChapterIndex == target && it.lastCharOffset == offset })
                        val undoName = "undo" + if (attempt == 0) "" else "-${attempt + 1}"
                        check(measure(stages, undoName) { repo.undoBookChange(bookId) } == 1)
                        check(repo.marks(bookId).single() == originalMark)
                        check(repo.book(bookId)!!.let { it.lastChapterIndex == anchorChapter && it.lastCharOffset == anchorOffset })
                    }
                    checkBook()
                }
                rows.put(JSONObject().put("style", if (short) "short" else "long").put("bytes", file.length())
                    .put("stages", stages).put("chaptersVerified", 1320).put("progressUpdates", progress.size))
                repo.deleteBook(bookId)
                // A cancellation raised during saving must roll back files and DB, and remain resumable.
                if (short && !changeBoundaries) {
                    var stopped = false
                    try {
                        repo.importResumable(listOf(uri), ImportMode.SEPARATE, null, options, ImportOrder.SELECTION) { stage, _ ->
                            if (stage == context.getString(R.string.repo_stage_save_chapters, 0, 1320)) {
                                stopped = true; throw CancellationException("Generated import pause")
                            }
                        }
                        error("Import was not cancelled")
                    } catch (_: CancellationException) { }
                    check(stopped && repo.hasIncompleteImport() && repo.books(ShelfSort.ADDED).isEmpty())
                    val resumed = repo.resumeImport { _, _ -> }
                    check(resumed.failed.isEmpty() && repo.chapters(resumed.bookIds.single()).size == 1320)
                    check(!repo.hasIncompleteImport())
                    repo.deleteBook(resumed.bookIds.single())
                }
                runner.sendStatus(0, android.os.Bundle().apply { putString("stream", "Verified generated 1320-chapter ${if (short) "short" else "long"} import\n") })
            }
            return JSONObject().put("passed", true).put("runs", rows)
                .put("changedBoundaries", changeBoundaries).put("cancellationAndResume", !changeBoundaries && style != "long")
                .put("sampleIntervalMs", SAMPLE_INTERVAL_MS).put("maxHeapKiB", Runtime.getRuntime().maxMemory() / 1024)
        } finally { repo.db.close() }
    }

    suspend fun checkResplitFileReuse(context: Context): List<String> {
        val repo = BookRepository(context)
        try {
            return listOf(false, true).map { streaming ->
                val watermark = "手机阅读请访问 https://example.com"
                val markedText = "保留此处的高亮。"
                val bodies = listOf(
                    "这段文字在重新分章后保持原样。".repeat(10),
                    "阅读顺序和位置应随这一章一起保留。".repeat(10) + "\n\n$watermark\n\n$markedText",
                    "原有书尾必须完整保留。".repeat(10)
                )
                val file = File(context.cacheDir, "resplit-mixed.txt")
                file.writeText(bodies.mapIndexed { index, body -> "第${index + 1}章 验证\n\n$body" }.joinToString("\n\n"))
                val originalOptions = SplitOptions(cleanWatermark = false)
                val id = repo.importDocuments(listOf(Uri.fromFile(file)), ImportMode.SEPARATE, null,
                    splitOptions = originalOptions) { _, _ -> }.bookIds.single()
                val original = repo.chapters(id)
                check(original.size == bodies.size && original.map { repo.chapterText(it) } == bodies)
                val markOffset = bodies[1].indexOf(markedText)
                repo.addMark(Mark(bookId = id, chapterIndex = 1, startOffset = markOffset, endOffset = markOffset + markedText.length))
                repo.updateProgress(id, 1, markOffset, .5f)
                val originalMark = repo.marks(id).single()
                val originalPosition = repo.book(id)!!.lastCharOffset
                val options = originalOptions.copy(cleanWatermark = true).let { clean ->
                    if (streaming) repo.prepareResplitSource(id, clean).use { it.options } else clean
                }

                val directory = File(context.filesDir, "chapters")
                val originalFiles = directory.list().orEmpty().toSet()
                fun verifyUnchangedFiles() = check(directory.list().orEmpty().toSet() == originalFiles)
                var cancelled = false
                try {
                    repo.resplitBook(id, options) { if (it >= .65f) throw CancellationException("Cancel mixed staged files") }
                } catch (_: CancellationException) {
                    cancelled = true
                }
                check(cancelled && repo.chapters(id) == original && repo.marks(id).single() == originalMark)
                verifyUnchangedFiles()
                check(!repo.undoAvailable(id))

                check(repo.resplitBook(id, options) == 3)
                val current = repo.chapters(id)
                val expected = bodies.map(ChapterSplitter::cleanBodyWatermarks)
                check(current.map { repo.chapterText(it) } == expected)
                check(current.indices.filter { current[it].fileName == original[it].fileName } == listOf(0, 2))
                val moved = repo.marks(id).single()
                check(moved.id == originalMark.id && moved.chapterIndex == 1)
                check(expected[1].substring(moved.startOffset, moved.endOffset) == markedText)
                check(repo.book(id)!!.lastCharOffset == expected[1].indexOf(markedText))
                check(repo.undoAvailable(id) && repo.undoBookChange(id) == 1)
                check(repo.chapters(id).map { repo.chapterText(it) } == bodies)
                check(repo.marks(id).single() == originalMark && repo.book(id)!!.lastCharOffset == originalPosition)
                check(!repo.undoAvailable(id))
                repo.deleteBook(id)
                "PASS ${if (streaming) "streaming" else "in-memory"} mixed reuse, cancellation, mark and progress remap, undo"
            }
        } finally {
            repo.db.close()
        }
    }
}
