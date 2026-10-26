package com.inkreader

import android.content.Context
import android.net.Uri
import com.inkreader.data.model.*
import com.inkreader.data.repo.BookRepository
import com.inkreader.data.repo.ChapterCorrectionStore
import com.inkreader.parse.ChapterCorrections
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

object UpgradeChecks {
    suspend fun run(context: Context, repo: BookRepository, test: suspend (String, suspend () -> JSONObject) -> Unit) {
        test("complete backup round trip preserves body rich text marks covers groups corrections progress and preferences") {
            val book = repo.createManualBook("archive-roundtrip", "Author")
            val chapter = repo.chapters(book).single()
            val body = "备份原文🐉。\n\n每一个字都保留。"
            repo.saveChapterText(chapter, body)
            val current = repo.chapters(book).single()
            val rich = "ikr1 ${body.length}\nK 0 0 0 ${body.length} \n"
            repo.files.writeRich(current.fileName, rich)
            val cover = repo.files.coverNameFor(book)
            val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
            val coverBytes = ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle(); repo.files.writeCover(cover, coverBytes); repo.db.updateBookCover(book, cover)
            repo.addMark(Mark(bookId = book, chapterIndex = 0, startOffset = 0, endOffset = 4, note = "PRIVATE_NOTE", snippet = body.take(4)))
            val group = repo.createGroup("archive-group"); repo.moveBooksToGroup(listOf(book), group)
            repo.updateProgress(book, 0, 5, .4f)
            ChapterCorrectionStore(context).save(book, "source", listOf(ChapterCorrections.Anchor("第1章", "context", false)))
            val prefs = context.getSharedPreferences("ink_prefs", Context.MODE_PRIVATE)
            prefs.edit().putFloat("font_size", 21.5f).putBoolean("indent", false).commit()
            val before = repo.books(ShelfSort.ADDED).map { it.id }.toSet()
            val archive = File(context.cacheDir, "roundtrip.inkbackup")
            repo.writeBackup(Uri.fromFile(archive))
            prefs.edit().putFloat("font_size", 11f).putBoolean("indent", true).commit()
            val restored = repo.restoreBackup(Uri.fromFile(archive))
            val added = repo.books(ShelfSort.ADDED).filter { it.id !in before }
            check(restored == before.size && added.size == before.size)
            val copy = added.single { it.title == "archive-roundtrip" }
            val copyChapter = repo.chapters(copy.id).single()
            check(repo.chapterText(copyChapter) == body && repo.files.readRich(copyChapter.fileName) == rich)
            check(repo.files.coverFile(copy.coverPath).readBytes().contentEquals(coverBytes))
            check(repo.marks(copy.id).single().let { it.note == "PRIVATE_NOTE" && it.startOffset == 0 && it.endOffset == 4 })
            check(copy.groupId != group && repo.groups().any { it.id == copy.groupId && it.name == "archive-group" })
            check(copy.lastCharOffset == 5 && copy.progress == .4f && repo.savedCorrectionCount(copy.id) == 1)
            check(prefs.getFloat("font_size", 0f) == 21.5f && !prefs.getBoolean("indent", true))
            check(repo.chapterText(repo.chapters(book).single()) == body)
            added.forEach { repo.deleteBook(it.id) }; repo.deleteBook(book)
            JSONObject().put("restoredBooks", restored).put("originalsKept", true)
        }
        test("malformed traversal checksum and SQL failures leave the original library and files unchanged") {
            val originalBooks = repo.books(ShelfSort.ADDED)
            val originalFiles = File(context.filesDir, "chapters").listFiles().orEmpty().map { it.name }.toSet()
            val archive = File(context.cacheDir, "bad-archive.inkbackup")
            val base = File(context.cacheDir, "valid-archive.inkbackup")
            repo.writeBackup(Uri.fromFile(base))
            fun repack(mutator: (String, ByteArray) -> Pair<String, ByteArray>) {
                ZipOutputStream(archive.outputStream()).use { output -> ZipInputStream(base.inputStream()).use { input ->
                    while (true) { val entry = input.nextEntry ?: break
                        val (name, bytes) = mutator(entry.name, input.readBytes()); output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry()
                    }
                } }
            }
            suspend fun rejects() {
                try { repo.restoreBackup(Uri.fromFile(archive)); error("Invalid backup was accepted") } catch (e: Exception) {
                    check(e.message != "Invalid backup was accepted")
                }
                check(repo.books(ShelfSort.ADDED) == originalBooks)
                check(File(context.filesDir, "chapters").listFiles().orEmpty().map { it.name }.toSet() == originalFiles)
            }
            repack { name, bytes -> if (name.startsWith("chapters/")) name to (bytes + 'X'.code.toByte()) else name to bytes }; rejects()
            repack { name, bytes -> if (name == "library.json") "../library.json" to bytes else name to bytes }; rejects()
            repack { name, bytes -> if (name == "library.json") {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                json.getJSONObject("preferences").put("font_size", JSONObject().put("type", "String").put("value", "bad-type"))
                name to json.toString().toByteArray()
            } else name to bytes }; rejects()
            base.copyTo(archive, overwrite = true)
            repo.db.writableDatabase.execSQL("CREATE TRIGGER reject_restore BEFORE INSERT ON chapters BEGIN SELECT RAISE(ABORT, 'injected restore failure'); END")
            try { rejects() } finally { repo.db.writableDatabase.execSQL("DROP TRIGGER reject_restore") }
            JSONObject().put("rejectedFaults", 4).put("orphanFiles", 0)
        }
        test("chapter undo persists and restores exact chapter IDs marks body and reading progress") {
            val id = repo.createManualBook("undo-roundtrip", "")
            val chapter = repo.chapters(id).single()
            repo.renameChapter(chapter.id, "第一章")
            val body = "第一章\n\n" + "第一段原文。".repeat(30) + "\n\n第二章\n\n" + "第二段原文。".repeat(30)
            repo.saveChapterText(chapter, body)
            repo.addMark(Mark(bookId = id, chapterIndex = 0, startOffset = 12, endOffset = 16, note = "KEEP_NOTE"))
            repo.updateProgress(id, 0, 15, .2f)
            val oldChapters = repo.chapters(id); val oldMarks = repo.marks(id); val oldBook = repo.book(id)
            val count = repo.resplitBook(id, SplitOptions(cleanWatermark = false))
            check(count == 2 && repo.undoAvailable(id)) { "Resplit produced $count chapters; undo=${repo.undoAvailable(id)}" }
            val restarted = BookRepository(context)
            try { check(restarted.undoAvailable(id)); restarted.undoBookChange(id) } finally { restarted.db.close() }
            val restored = repo.chapters(id).single()
            check(restored.id == oldChapters.single().id && repo.chapterText(restored) == body)
            check(repo.marks(id) == oldMarks && repo.book(id)!!.lastCharOffset == oldBook!!.lastCharOffset)
            check(!repo.undoAvailable(id)); repo.deleteBook(id)
            JSONObject().put("acrossRestart", true).put("stableAnnotationIds", true)
        }
        test("failed structure edit preserves the previous undo snapshot and removes staged files") {
            val id = repo.createManualBook("undo-failure", "")
            val chapter = repo.chapters(id).single()
            val text = "Alpha paragraph. ".repeat(50) + "\n\n" + "Beta paragraph. ".repeat(50)
            repo.saveChapterText(chapter, text)
            val before = repo.chapters(id).single()
            check(repo.splitChapterAt(id, before.id, text.indexOf("Beta"), "Part B") != null)
            val split = repo.chapters(id)
            val filesBefore = File(context.filesDir, "chapters").listFiles().orEmpty().map { it.name }.toSet()
            repo.db.writableDatabase.execSQL("CREATE TRIGGER reject_undo_edit BEFORE UPDATE OF file ON chapters BEGIN SELECT RAISE(ABORT, 'injected edit failure'); END")
            var rejected = false
            try { repo.mergeChapterWithPrev(id, split.last().id) } catch (_: Exception) { rejected = true }
            finally { repo.db.writableDatabase.execSQL("DROP TRIGGER reject_undo_edit") }
            check(rejected && repo.chapters(id) == split)
            check(File(context.filesDir, "chapters").listFiles().orEmpty().map { it.name }.toSet() == filesBefore)
            repo.undoBookChange(id)
            check(repo.chapters(id).single().id == before.id && repo.chapterText(repo.chapters(id).single()) == text)
            repo.deleteBook(id)
            JSONObject().put("previousUndoKept", true).put("orphanFiles", 0)
        }
        test("disk resplit preserves unicode annotations rich styles progress and source validation") {
            val id = repo.createManualBook("stream-resplit", "")
            val chapter = repo.chapters(id).single()
            repo.renameChapter(chapter.id, "第一章")
            val paragraph = "风从书页间吹过，🐉仍在阅读。\n\n"
            val body = "第一章\r\n\r\n" + paragraph.repeat(45000) + "第二章\r\n\r\n" + paragraph.repeat(45000) + "保留尾巴TAIL_END"
            repo.saveChapterText(chapter, body)
            val old = repo.chapters(id).single()
            val point = body.indexOf("第二章") + 20
            repo.addMark(Mark(bookId = id, chapterIndex = 0, startOffset = point, endOffset = point + 8, note = "KEEP_STREAM_NOTE"))
            repo.updateProgress(id, 0, point, .51f)
            val rich = com.inkreader.parse.RichChapter(body.length,
                listOf(com.inkreader.parse.RichBlock(com.inkreader.parse.RichKind.BODY, 0, "", 0, body.length)),
                listOf(com.inkreader.parse.InlineStyle(point, point + 8, bold = true)), emptyList())
            repo.files.writeRich(old.fileName, rich.encode())
            val preview = repo.prepareResplitSource(id, SplitOptions(cleanWatermark = false))
            val page = try { repo.recognitionPage(preview.uri, preview.options, "第二章", 0, false) } finally { preview.close() }
            check(page.total >= 1)
            val engine = com.inkreader.data.repo.StreamingResplit(context, repo.db, repo.files, ChapterCorrectionStore(context))
            var rejected = false
            try { engine.run(repo.book(id)!!, listOf(old), SplitOptions(sourceFingerprint = "stale")) {} }
            catch (_: IllegalArgumentException) { rejected = true }
            check(rejected && repo.chapters(id).single() == old)
            val filesBefore = File(context.filesDir, "chapters").listFiles().orEmpty().map { it.name }.toSet()
            val originalMarks = repo.marks(id)
            var cancelled = false
            try { engine.run(repo.book(id)!!, listOf(old), SplitOptions(cleanWatermark = false)) {
                if (it >= .5f) throw kotlinx.coroutines.CancellationException("cancel after staging chapters")
            } } catch (_: kotlinx.coroutines.CancellationException) { cancelled = true }
            check(cancelled && repo.chapters(id).single() == old && repo.marks(id) == originalMarks)
            check(File(context.filesDir, "chapters").listFiles().orEmpty().map { it.name }.toSet() == filesBefore)
            repo.db.writableDatabase.execSQL("CREATE TRIGGER reject_disk_resplit BEFORE INSERT ON chapters WHEN NEW.book_id=$id BEGIN SELECT RAISE(ABORT, 'injected disk resplit failure'); END")
            var failed = false
            try { engine.run(repo.book(id)!!, listOf(old), SplitOptions(cleanWatermark = true)) {} }
            catch (_: Exception) { failed = true }
            finally { repo.db.writableDatabase.execSQL("DROP TRIGGER reject_disk_resplit") }
            check(failed && repo.chapters(id).single() == old && repo.marks(id) == originalMarks)
            check(File(context.filesDir, "chapters").listFiles().orEmpty().map { it.name }.toSet() == filesBefore)
            val count = engine.run(repo.book(id)!!, listOf(old), SplitOptions(cleanWatermark = false)) {}
            val result = repo.chapters(id)
            check(count > 60 && result.size == count)
            check(repo.chapterText(result.last()).endsWith("保留尾巴TAIL_END"))
            val mark = repo.marks(id).single()
            val selected = repo.chapterText(result[mark.chapterIndex]).substring(mark.startOffset, mark.endOffset)
            check(selected == body.substring(point, point + 8))
            val movedRich = com.inkreader.parse.RichChapter.decode(repo.files.readRich(result[mark.chapterIndex].fileName))!!
            check(movedRich.spans.any { it.bold && it.start == mark.startOffset && it.end == mark.endOffset })
            check(repo.book(id)!!.let { it.lastChapterIndex == mark.chapterIndex && it.lastCharOffset == mark.startOffset })
            val expectedChars = com.inkreader.parse.ChapterSplitter.normalize(body).replace("第一章", "").replace("第二章", "").filterNot { it.isWhitespace() }
            val actualChars = buildString { for (item in result) append(repo.chapterText(item).filterNot { c -> c.isWhitespace() }) }
            check(actualChars == expectedChars) { "Stream resplit lost body characters" }
            repo.deleteBook(id)
            JSONObject().put("chapters", count).put("sourceChars", body.length).put("annotationsAndStyles", true)
        }
        test("interrupted separate merge and append jobs resume without rereading or duplicating committed sources") {
            var scenarios = 0
            for (scenario in listOf("separate", "merge", "append")) {
                val first = File(context.cacheDir, "resume-first.txt").apply { writeText("FIRST_ORIGINAL " + "第一份正文。".repeat(60)) }
                val second = File(context.cacheDir, "resume-second.md").apply { writeText("# Second\n\nSECOND_ORIGINAL " + "第二份正文。".repeat(60)) }
                val baseline = repo.books(ShelfSort.ADDED).map { it.id }.toSet()
                val target = if (scenario == "append") repo.createManualBook("resume-append", "") else 0L
                var paused = false
                try {
                    repo.importResumable(listOf(Uri.fromFile(first), Uri.fromFile(second)),
                        if (scenario == "separate") ImportMode.SEPARATE else ImportMode.MERGE, null,
                        SplitOptions(mode = SplitMode.PER_FILE, cleanWatermark = false), ImportOrder.SELECTION, target) { _, fraction ->
                        if (fraction >= .5f) throw kotlinx.coroutines.CancellationException("simulated process interruption")
                    }
                } catch (_: kotlinx.coroutines.CancellationException) { paused = true }
                check(paused && repo.hasIncompleteImport())
                first.writeText("THIS_SOURCE_CHANGED_AFTER_COMMIT")
                val restarted = BookRepository(context)
                val result = try { restarted.resumeImport { _, _ -> } } finally { restarted.db.close() }
                check(result.failed.isEmpty() && result.succeeded.size == 2 && !repo.hasIncompleteImport())
                val added = repo.books(ShelfSort.ADDED).filter { it.id !in baseline }
                check(added.size == if (scenario == "separate") 2 else 1)
                val body = buildString { for (book in added) for (chapter in repo.chapters(book.id)) append(repo.chapterText(chapter)) }
                check(Regex("FIRST_ORIGINAL").findAll(body).count() == 1 && Regex("SECOND_ORIGINAL").findAll(body).count() == 1)
                check(!body.contains("THIS_SOURCE_CHANGED_AFTER_COMMIT"))
                added.forEach { repo.deleteBook(it.id) }; first.delete(); second.delete(); scenarios++
            }
            JSONObject().put("scenarios", scenarios).put("duplicates", 0).put("completedSourcesSkipped", true)
        }
        test("failed import transaction never records completion and can be retried after restart") {
            val source = File(context.cacheDir, "retry-source.txt").apply { writeText("RETRY_BODY " + "事务正文。".repeat(60)) }
            val before = repo.books(ShelfSort.ADDED).map { it.id }.toSet()
            repo.db.writableDatabase.execSQL("CREATE TRIGGER reject_import_receipt BEFORE INSERT ON import_receipts BEGIN SELECT RAISE(ABORT, 'injected receipt failure'); END")
            try {
                val failed = repo.importResumable(listOf(Uri.fromFile(source)), ImportMode.SEPARATE, null,
                    SplitOptions(cleanWatermark = false), ImportOrder.SELECTION) { _, _ -> }
                check(failed.bookIds.isEmpty() && failed.failed.size == 1 && repo.hasIncompleteImport())
                check(repo.books(ShelfSort.ADDED).map { it.id }.toSet() == before)
            } finally { repo.db.writableDatabase.execSQL("DROP TRIGGER reject_import_receipt") }
            val resumed = repo.resumeImport { _, _ -> }
            check(resumed.succeeded.size == 1 && !repo.hasIncompleteImport())
            val id = resumed.bookIds.single(); check(repo.chapters(id).isNotEmpty()); repo.deleteBook(id); source.delete()
            JSONObject().put("atomicCompletionReceipt", true).put("retryBooks", 1)
        }
        test("streamed exact search warms candidate indexes and invalidates after chapter edit") {
            val source = File(context.cacheDir, "search-performance.txt").apply {
                writeText((1..20).joinToString("\n\n") { i -> "第${i}章\n\n" + "测试搜索正文风景与日记。".repeat(4000) + if (i == 20) " UNIQUE_NEEDLE🐉 𐐀A END" else "" })
            }
            val id = repo.importDocuments(listOf(Uri.fromFile(source)), ImportMode.SEPARATE, null,
                splitOptions = SplitOptions(cleanWatermark = false)) { _, _ -> }.bookIds.single()
            val start = android.os.SystemClock.elapsedRealtime()
            val cold = repo.searchInBook(id, "unique_needle🐉")
            val coldMs = android.os.SystemClock.elapsedRealtime() - start
            val skipped = repo.searchSkippedChapters
            val warmStart = android.os.SystemClock.elapsedRealtime()
            val warm = repo.searchInBook(id, "unique_needle🐉")
            val warmMs = android.os.SystemClock.elapsedRealtime() - warmStart
            val warmSkipped = repo.searchSkippedChapters - skipped
            check(cold.size == 1 && cold == warm && warmSkipped >= 19)
            val supplementaryCold = repo.searchInBook(id, "𐐨a")
            val supplementaryWarm = repo.searchInBook(id, "𐐨a")
            val lastText = repo.chapterText(repo.chapters(id).last())
            val expectedCase = lastText.indexOf("𐐨a", ignoreCase = true)
            check(supplementaryCold == supplementaryWarm)
            check(if (expectedCase < 0) supplementaryCold.isEmpty() else supplementaryCold.single().offset == expectedCase)

            val chapter = repo.chapters(id).last()
            repo.saveChapterText(chapter, "UPDATED_BODY " + "更新正文。".repeat(50))
            check(repo.searchInBook(id, "unique_needle🐉").isEmpty())
            check(repo.searchInBook(id, "updated_body").size == 1)
            repo.deleteBook(id); source.delete()
            JSONObject().put("coldMs", coldMs).put("warmMs", warmMs).put("warmSkippedChapters", warmSkipped)
                .put("hits", cold.size).put("editInvalidated", true)
        }
    }
}
