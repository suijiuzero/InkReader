package com.inkreader

import android.content.Context
import android.net.Uri
import com.inkreader.data.model.*
import com.inkreader.data.repo.BookRepository
import java.io.File
import kotlinx.coroutines.async
import org.json.JSONObject

/** 针对复审中发现的失败重试顺序、跨界批注和原子文件恢复问题。 */
object AuditFollowupChecks {
    suspend fun run(context: Context, repo: BookRepository, test: suspend (String, suspend () -> JSONObject) -> Unit) {
        test("retrying failed earlier files preserves selected order for merge and append") {
            for (append in listOf(false, true)) {
                val baseline = repo.books(ShelfSort.ADDED).map { it.id }.toSet()
                val sources = listOf("a", "b", "c", "d").map { part ->
                    File(context.cacheDir, "audit-retry-$part.txt").apply { writeText("SOURCE_$part " + "正文保留。".repeat(60)) }
                }
                val target = if (append) repo.createManualBook("audit-retry-target", "") else 0L
                try {
                    repo.db.writableDatabase.execSQL("CREATE TRIGGER reject_earlier_file BEFORE INSERT ON chapters WHEN NEW.source IN ('audit-retry-a.txt','audit-retry-c.txt') BEGIN SELECT RAISE(ABORT, 'first attempt failure'); END")
                    val first = try { repo.importResumable(sources.map { Uri.fromFile(it) }, ImportMode.MERGE, "audit-retry-merge",
                        SplitOptions(mode = SplitMode.PER_FILE, cleanWatermark = false), ImportOrder.SELECTION, target) { _, _ -> } }
                    finally { repo.db.writableDatabase.execSQL("DROP TRIGGER reject_earlier_file") }
                    check(first.failed.size == 2 && first.succeeded.size == 2 && repo.hasIncompleteImport())
                    val resumed = repo.resumeImport { _, _ -> }
                    check(resumed.failed.isEmpty() && !repo.hasIncompleteImport())
                    val id = resumed.bookIds.single()
                    val names = repo.chapters(id).map { it.sourceName }.filter { it.startsWith("audit-retry-") }
                    check(names == sources.map { it.name }) { "Selected order after retry: $names" }
                    if (append) check(repo.chapters(id).first().sourceName !in sources.map { it.name })
                } finally {
                    if (repo.hasIncompleteImport()) repo.discardIncompleteImport()
                    repo.books(ShelfSort.ADDED).filter { it.id !in baseline }.forEach { repo.deleteBook(it.id) }
                    sources.forEach { it.delete() }
                }
            }
            JSONObject().put("mergeAndAppend", true).put("selectedOrder", "a,b,c,d")
        }
        test("resplit and manual split preserve both sides of crossing highlights and notes") {
            for (manual in listOf(false, true)) {
                val id = repo.createManualBook("audit-crossing-mark", "")
                try {
                    val old = repo.chapters(id).single(); repo.renameChapter(old.id, "第一章")
                    val body = "第一章\n\n" + "甲段正文。".repeat(60) + "FIRST_END\n\n第二章\n\nSECOND_START" + "乙段正文。".repeat(60)
                    repo.saveChapterText(old, body)
                    val start = body.indexOf("FIRST_END"); val end = body.indexOf("SECOND_START") + "SECOND_START".length
                    val markId = repo.addMark(Mark(bookId = id, chapterIndex = 0, startOffset = start, endOffset = end,
                        note = "CROSSING_NOTE", snippet = "FIRST_END / SECOND_START"))
                    if (manual) check(repo.splitChapterAt(id, old.id, body.indexOf("SECOND_START"), "Manual second") != null)
                    else check(repo.resplitBook(id, SplitOptions(cleanWatermark = false)) == 2)
                    val chapters = repo.chapters(id); val marks = repo.marks(id)
                    check(marks.size == 2 && marks.all { it.note == "CROSSING_NOTE" }) { "manual=$manual retained ${marks.size} fragments" }
                    check(marks.any { it.id == markId })
                    val selected = buildString { for (mark in marks.sortedBy { it.chapterIndex }) {
                        val text = repo.chapterText(chapters[mark.chapterIndex])
                        append(text.substring(mark.startOffset, mark.endOffset))
                    } }
                    check("FIRST_END" in selected && "SECOND_START" in selected)
                } finally { repo.deleteBook(id) }
            }
            JSONObject().put("operations", 2).put("crossingRangesPreserved", true)
        }
        test("merging an empty chapter keeps its bookmark and progress within the resulting body") {
            val id = repo.createManualBook("audit-empty-merge", "")
            try {
                val first = repo.chapters(id).single(); repo.saveChapterText(first, "BODY")
                val second = repo.addEmptyChapter(id, "Empty")
                repo.addMark(Mark(bookId = id, chapterIndex = 1, startOffset = 0, endOffset = 0, type = MarkType.BOOKMARK))
                repo.updateProgress(id, 1, 0, .5f)
                check(repo.mergeChapterWithPrev(id, second))
                val result = repo.chapters(id).single(); val mark = repo.marks(id).single()
                check(repo.chapterText(result) == "BODY")
                check(mark.startOffset == 4 && mark.endOffset == 4 && repo.book(id)!!.lastCharOffset == 4)
            } finally { repo.deleteBook(id) }
            JSONObject().put("bookmarkOffset", 4).put("bodyChars", 4)
        }
        test("rich sidecars recover AtomicFile backups and deletion cleans their backup files") {
            val id = repo.createManualBook("audit-rich-atomic", "")
            try {
                val chapter = repo.chapters(id).single(); repo.saveChapterText(chapter, "RICH_BODY")
                val current = repo.chapters(id).single(); val rich = "ikr1 9\nK 0 0 0 9 \nS 0 4 1\n"
                repo.files.writeRich(current.fileName, rich)
                val file = repo.files.file(repo.files.richNameOf(current.fileName)); val backup = File(file.path + ".bak")
                check(file.renameTo(backup))
                val bodyFile = repo.files.file(current.fileName); val bodyBackup = File(bodyFile.path + ".bak")
                check(bodyFile.renameTo(bodyBackup))
                val notes = com.inkreader.data.repo.ChapterCorrectionStore(context)
                notes.save(id, "source", listOf(com.inkreader.parse.ChapterCorrections.Anchor("第1章", "context", false)))
                val noteFile = File(context.filesDir, "recognition-notes/book_$id.json")
                check(noteFile.renameTo(File(noteFile.path + ".bak")))
                val archive = File(context.cacheDir, "audit-atomic-backup.zip")
                repo.writeBackup(Uri.fromFile(archive))
                java.util.zip.ZipFile(archive).use { zip ->
                    check(zip.getInputStream(zip.getEntry("rich/$id/0.rich")).bufferedReader().use { it.readText() } == rich)
                    check(zip.getEntry("notes/$id.json") != null)
                    check(zip.getInputStream(zip.getEntry("chapters/$id/0.txt")).bufferedReader().use { it.readText() } == "RICH_BODY")
                }
                check(noteFile.renameTo(File(noteFile.path + ".bak")))
                check(repo.savedCorrectionCount(id) == 1)
                check(file.renameTo(backup))
                check(repo.files.readRich(current.fileName) == rich) { "Committed sidecar backup was ignored" }
                check(file.renameTo(backup))
                repo.files.deleteRich(current.fileName)
                check(!file.exists() && !backup.exists())
            } finally { repo.deleteBook(id) }
            JSONObject().put("recovered", true).put("backupRemovedOnDelete", true)
        }
        test("a failed append never deletes an existing book with zero chapters") {
            val id = repo.createManualBook("audit-empty-existing-target", "")
            val bad = File(context.cacheDir, "audit-empty-source.txt").apply { writeText("") }
            try {
                repo.deleteChapter(id, repo.chapters(id).single().id)
                check(repo.book(id) != null && repo.chapters(id).isEmpty())
                val failed = repo.importResumable(listOf(Uri.fromFile(bad)), ImportMode.MERGE, null,
                    SplitOptions(), ImportOrder.SELECTION, id) { _, _ -> }
                check(failed.failed.size == 1 && repo.book(id) != null) { "Failed append deleted its existing target" }
            } finally { if (repo.hasIncompleteImport()) repo.discardIncompleteImport(); repo.deleteBook(id); bad.delete() }
            JSONObject().put("existingEmptyBookKept", true)
        }
        test("discard serializes with an active import instead of erasing its completion journal") {
            val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
            val source = File(context.cacheDir, "audit-discard-serialized.txt").apply { writeText("SERIALIZED_IMPORT " + "正文。".repeat(50)) }
            val baseline = repo.books(ShelfSort.ADDED).map { it.id }.toSet()
            try { kotlinx.coroutines.coroutineScope {
                val importing = async(kotlinx.coroutines.Dispatchers.IO) {
                    repo.importResumable(listOf(Uri.fromFile(source)), ImportMode.SEPARATE, null,
                        SplitOptions(cleanWatermark = false), ImportOrder.SELECTION) { _, fraction ->
                        if (fraction == 0f) { entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
                    }
                }
                check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val discard = async(kotlinx.coroutines.Dispatchers.IO) { repo.discardIncompleteImport() }
                try {
                    kotlinx.coroutines.delay(100)
                    check(!discard.isCompleted && repo.hasIncompleteImport())
                } finally { release.countDown() }
                check(importing.await().succeeded.size == 1); discard.await()
                check(!repo.hasIncompleteImport())
            } } finally {
                release.countDown()
                if (repo.hasIncompleteImport()) repo.discardIncompleteImport()
                repo.books(ShelfSort.ADDED).filter { it.id !in baseline }.forEach { repo.deleteBook(it.id) }; source.delete()
            }
            JSONObject().put("discardWaited", true).put("importCompleted", true)
        }
        test("invalid reorder arguments cannot alter chapter indices or another book") {
            val id = repo.createManualBook("audit-order-arguments", "")
            val other = repo.createManualBook("audit-order-other", "")
            try {
                repo.addEmptyChapter(id, "Second")
                val before = repo.chapters(id); val foreign = repo.chapters(other).single()
                for (bad in listOf(listOf(before.first().id), listOf(before.first().id, before.first().id), listOf(before.first().id, foreign.id))) {
                    var rejected = false
                    try { repo.reorderChapters(id, bad) } catch (_: IllegalArgumentException) { rejected = true }
                    check(rejected && repo.chapters(id) == before && repo.chapters(other).single() == foreign)
                }
            } finally { repo.deleteBook(id); repo.deleteBook(other) }
            JSONObject().put("invalidOrdersRejected", 3)
        }
    }
}
