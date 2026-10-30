package com.inkreader

import android.app.Instrumentation
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.inkreader.data.model.*
import com.inkreader.data.repo.BookRepository
import com.inkreader.parse.ChapterSplitter
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Generated 13 MB novels through the real preview, journal, SQLite and AtomicFile paths. */
object LargeImportChecks {
    suspend fun run(runner: Instrumentation, context: Context): JSONObject {
        val repo = BookRepository(context)
        val rows = JSONArray()
        try {
            val paragraph = "这是一段用于大书导入性能测试的普通正文。人物继续赶路，在天亮之前抵达城门，随后整理行李并阅读来信。没有广告，也不包含章节引用。".repeat(9)
            for (short in listOf(false, true)) {
                val file = File(context.cacheDir, if (short) "generated-short.txt" else "generated-long.txt")
                val line = if (short) paragraph.chunked(25).joinToString("\n") else paragraph
                val body = List(6) { line }.joinToString("\n\n")
                file.bufferedWriter().use { out ->
                    for (n in 1..1320) out.write("第${n}章 测速章节\n\n$body\n\n")
                }
                val uri = Uri.fromFile(file)
                val options = SplitOptions()
                var start = SystemClock.elapsedRealtime()
                val preview = repo.previewImport(listOf(uri), ImportMode.SEPARATE, options)
                val cold = SystemClock.elapsedRealtime() - start
                check(preview.estimatedChapters == 1320 && preview.failedFiles == 0)
                start = SystemClock.elapsedRealtime()
                check(repo.previewImport(listOf(uri), ImportMode.SEPARATE, options).estimatedChapters == 1320)
                val warm = SystemClock.elapsedRealtime() - start
                val progress = ArrayList<Float>(); var saves = 0
                start = SystemClock.elapsedRealtime()
                val result = repo.importResumable(listOf(uri), ImportMode.SEPARATE, null, options, ImportOrder.SELECTION) { stage, fraction ->
                    check(fraction in 0f..1f && (progress.isEmpty() || fraction >= progress.last()))
                    progress += fraction
                    if (stage == context.getString(R.string.repo_stage_save_chapters, 1320, 1320)) saves++
                }
                val imported = SystemClock.elapsedRealtime() - start
                check(result.failed.isEmpty() && result.bookIds.size == 1 && saves == 1)
                check(!repo.hasIncompleteImport())
                val chapters = repo.chapters(result.bookIds.single())
                check(chapters.size == 1320)
                val expected = ChapterSplitter.cleanBodyWatermarks(body)
                for ((i, chapter) in chapters.withIndex()) {
                    check(chapter.indexInBook == i && chapter.title == "第${i + 1}章 测速章节")
                    check(repo.chapterText(chapter) == expected) { "Body mismatch at chapter $i" }
                }
                rows.put(JSONObject().put("style", if (short) "short" else "long").put("bytes", file.length())
                    .put("previewColdMs", cold).put("previewWarmMs", warm).put("importMs", imported)
                    .put("chaptersVerified", chapters.size).put("progressUpdates", progress.size))
                repo.deleteBook(result.bookIds.single())
                // A cancellation raised during saving must roll back files and DB, and remain resumable.
                if (short) {
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
            return JSONObject().put("passed", true).put("runs", rows).put("cancellationAndResume", true)
        } finally { repo.db.close() }
    }
}
