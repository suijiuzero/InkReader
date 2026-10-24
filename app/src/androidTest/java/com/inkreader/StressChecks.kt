package com.inkreader

import android.app.Instrumentation
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.SystemClock
import com.inkreader.data.model.*
import com.inkreader.data.picker.PickerDraftStore
import com.inkreader.data.picker.OrderedSelection
import com.inkreader.data.picker.SelectionBatch
import com.inkreader.data.repo.BookRepository
import com.inkreader.parse.ChapterSplitter
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.Random

/** Real Android/SQLite/fsync pressure in the runner's disposable storage, never the user library. */
object StressChecks {
    suspend fun run(runner: Instrumentation, context: Context, mode: String): JSONObject {
        if (mode == "additional") return AdditionalStressChecks.run(runner, context)
        if (mode == "soak") return SoakChecks.run(runner, context)
        val results = JSONArray()
        val repo = BookRepository(context)
        suspend fun test(name: String, block: suspend () -> JSONObject) {
            val start = SystemClock.elapsedRealtime()
            val record = JSONObject().put("name", name)
            try { record.put("details", block()).put("status", "PASS") }
            catch (t: Throwable) { record.put("status", "FAIL").put("error", t.stackTraceToString()) }
            record.put("ms", SystemClock.elapsedRealtime() - start)
            val runtime = Runtime.getRuntime()
            record.put("heapUsedMiB", (runtime.totalMemory() - runtime.freeMemory()) / 1048576)
            results.put(record)
            runner.sendStatus(0, Bundle().apply { putString("stream", record.toString() + "\n") })
        }
        fun file(name: String, body: String) = File(context.cacheDir, name).apply { writeText(body) }
        fun books() = repo.db.getBooks(ShelfSort.RECENT)
        try {
            test("cancel separate import before first source is read") {
                val sources = (1..30).map { Uri.fromFile(file("cancel-$it.txt", "CANCEL_BODY_$it ".repeat(50))) }
                val before = books().size
                val parent = Job()
                val job = CoroutineScope(Dispatchers.IO + parent).launch {
                    repo.importDocuments(sources, ImportMode.SEPARATE, null, splitMode = SplitMode.PER_FILE) { _, _ -> parent.cancel() }
                }
                job.join()
                check(books().size == before) { "Cancellation still imported ${books().size - before} books" }
                JSONObject().put("sources", sources.size)
            }
            test("cancel search stops before reading more chapters") {
                val id = repo.createManualBook("cancel-search", "")
                repeat(40) { repo.addEmptyChapter(id, "chapter-$it") }
                var callbacks = 0
                val parent = Job()
                val job = CoroutineScope(Dispatchers.IO + parent).launch {
                    repo.searchInBook(id, "no-hit") { callbacks++; parent.cancel() }
                }
                job.join()
                check(callbacks == 1) { "Cancelled search kept scanning: $callbacks callbacks" }
                JSONObject().put("chapters", 41).put("progressCallbacks", callbacks)
            }
            test("twenty thousand file drafts use a bounded Binder token and immutable snapshots") {
                val store = PickerDraftStore(context, java.util.UUID.randomUUID().toString())
                val values = (1..20000).flatMap { listOf("content://stress/document/" + "a".repeat(100) + it, "file-$it.txt", "120", "root/subfolder") }
                val token = store.save(values)
                val parcel = Parcel.obtain()
                val bytes = try { parcel.writeBundle(Bundle().apply { putString("selection", token) }); parcel.dataSize() } finally { parcel.recycle() }
                check(bytes < 1024 && store.restore(token) == values)
                val changed = store.save(values.dropLast(4))
                check(changed != token && store.restore(token) == values && store.restore(changed) == values.dropLast(4))
                check(store.restore("../../ink_reader.db").isEmpty())
                store.clear()
                check(store.restore(token).isEmpty())
                JSONObject().put("files", 20000).put("binderBytes", bytes)
            }
            test("cancel merged import and append preserve previously committed data") {
                val source = Uri.fromFile(file("cancel-merge.txt", "CANCEL_MERGE_BODY ".repeat(40)))
                val before = books().size
                val parent = Job()
                var mergeCallbacks = 0
                CoroutineScope(Dispatchers.IO + parent).launch {
                    repo.importDocuments(listOf(source, source), ImportMode.MERGE, "cancelled-merge", splitMode = SplitMode.PER_FILE) { _, fraction ->
                        mergeCallbacks++
                        if (mergeCallbacks == 2) parent.cancel()
                    }
                }.join()
                check(books().size == before) { "Empty cancelled merge book remained" }
                val id = repo.createManualBook("cancelled-append", "")
                val initial = repo.chapters(id)
                val appendParent = Job()
                CoroutineScope(Dispatchers.IO + appendParent).launch {
                    repo.appendFilesAsChapters(id, listOf(source, source), splitMode = SplitMode.PER_FILE) { _, _ -> appendParent.cancel() }
                }.join()
                check(repo.chapters(id) == initial && repo.book(id)!!.totalChapters == 1)
                JSONObject().put("emptyMergeBooks", 0).put("unexpectedAppendedChapters", 0)
            }
            if (mode != "quick") {
                test("ten thousand chapter headings keep complete body markers") {
                    val text = (1..10000).joinToString("\n\n") { "第${it}章 压力测试\n\nBODY_${it}_END " + "正文内容。".repeat(15) }
                    val parts = ChapterSplitter.split(text, "stress")
                    check(parts.size == 10000) { "Expected 10000 chapters, got ${parts.size}" }
                    parts.forEachIndexed { index, part -> check(part.content.contains("BODY_${index + 1}_END")) }
                    JSONObject().put("chapters", parts.size).put("characters", text.length)
                }
                test("seeded multilingual malformed heading corpus preserves all body markers") {
                    val random = Random(20261024)
                    var checked = 0
                    val heads = listOf("第%d章 标题", "Chapter %d Title", "第%d話 タイトル", "%d. 标题", "【第%d章】标题")
                    repeat(200) { sample ->
                        val count = 3 + random.nextInt(20)
                        val template = heads[random.nextInt(heads.size)]
                        val text = (1..count).joinToString(if (random.nextBoolean()) "\r\n\r\n" else "\n\n") {
                            template.format(it) + "\n\n" + "UNIQUE_${sample}_${it}_END 中文日本語 English 🌸 ".repeat(12)
                        }
                        val joined = ChapterSplitter.split(text, "fuzz").joinToString("\n") { it.content }
                        for (i in 1..count) check(Regex("UNIQUE_${sample}_${i}_END").findAll(joined).count() == 12)
                        checked += count
                    }
                    JSONObject().put("books", 200).put("bodyMarkers", checked * 12).put("seed", 20261024)
                }
                test("ten MiB unheaded TXT fallback keeps every character") {
                    val text = "ABCDEFGHIJKLMNOPQRSTUVWXYZ正文。\n".repeat(350000)
                    val parts = ChapterSplitter.split(text, "large")
                    check(parts.sumOf { p -> p.content.count { !it.isWhitespace() } } == text.count { !it.isWhitespace() })
                    JSONObject().put("characters", text.length).put("chapters", parts.size)
                }
                test("one thousand real files merge in chosen reverse order") {
                    val sources = (1..1000).map { n -> Uri.fromFile(file("batch-$n.txt", "BATCH_${n}_END ".repeat(12))) }.reversed()
                    val result = repo.importDocuments(sources, ImportMode.MERGE, "1000-file-stress",
                        splitMode = SplitMode.PER_FILE, fileOrder = ImportOrder.SELECTION) { _, _ -> }
                    check(result.failed.isEmpty()) { result.failed.take(3).toString() }
                    val id = result.bookIds.single()
                    val chapters = repo.chapters(id)
                    check(chapters.size == 1000)
                    chapters.forEachIndexed { index, chapter ->
                        check(chapter.sourceName == "batch-${1000 - index}.txt")
                        check(repo.files.read(chapter.fileName).contains("BATCH_${1000 - index}_END"))
                    }
                    check(repo.book(id)!!.totalChapters == 1000)
                    JSONObject().put("files", 1000).put("chapters", chapters.size)
                }
                test("three hundred random chapter reads in a thousand-chapter book") {
                    val book = books().first { it.title == "1000-file-stress" }
                    val chapters = repo.chapters(book.id)
                    repeat(300) { n ->
                        val chapter = chapters[(n * 7919) % chapters.size]
                        check(repo.chapterText(chapter).contains("BATCH_"))
                        check(repo.chapterRich(chapter, chapter.charCount) == null)
                    }
                    check(repo.db.chapterOf(book.id + 999999, chapters.first().id) == null)
                    JSONObject().put("chapterReads", 300).put("richReads", 300).put("bookChapters", chapters.size)
                }
                test("corrupt and missing files do not poison following valid imports") {
                    val bad = listOf(file("empty.txt", ""), file("bad.docx", "PK\u0003\u0004broken"), file("bad.epub", "PK\u0003\u0004broken"))
                    val missing = File(context.cacheDir, "missing.txt")
                    val good = file("last-valid.txt", "VALID_AFTER_ERRORS ".repeat(50))
                    val result = repo.importDocuments((bad + missing + good).map(Uri::fromFile), ImportMode.SEPARATE, null) { _, _ -> }
                    check(result.failed.size == 4 && result.succeeded.size == 1)
                    check(repo.chapterText(repo.chapters(result.bookIds.single()).single()).contains("VALID_AFTER_ERRORS"))
                    JSONObject().put("rejected", 4).put("validAfterFailures", 1)
                }
                test("oversized source is rejected without loading 201 MiB into memory") {
                    val source = File(context.cacheDir, "oversized.txt")
                    RandomAccessFile(source, "rw").use { it.setLength(201L * 1024 * 1024) }
                    val before = books().size
                    val result = repo.importDocuments(listOf(Uri.fromFile(source)), ImportMode.SEPARATE, null) { _, _ -> }
                    check(result.failed.size == 1 && books().size == before)
                    source.delete()
                    JSONObject().put("declaredBytes", 201L * 1024 * 1024)
                }
                test("one hundred concurrent chapter additions and bookmark toggles stay consistent") {
                    val id = repo.createManualBook("concurrent", "")
                    coroutineScope { (1..100).map { n -> async { repo.addEmptyChapter(id, "added-$n") } }.awaitAll() }
                    coroutineScope { (1..100).map { async { repo.toggleBookmark(id, 0, 0) } }.awaitAll() }
                    val chapters = repo.chapters(id)
                    check(chapters.map { it.indexInBook } == (0..100).toList())
                    check(chapters.map { it.fileName }.toSet().size == 101)
                    check(repo.marks(id).none { it.type == MarkType.BOOKMARK })
                    check(repo.book(id)!!.totalChapters == 101)
                    JSONObject().put("concurrentWrites", 200).put("chapters", chapters.size)
                }
                test("fifty thousand selection IDs keep range and dedup order") {
                    val ids = (1..50000).map { "content://test/$it" }
                    val batch = SelectionBatch(ids, ids[24999])
                    val selection = OrderedSelection(listOf(ids.last()))
                    selection.append(batch.fromAnchor(true))
                    selection.append(batch.all())
                    check(selection.size == 50000 && selection.items.take(3) == listOf(ids.last(), ids[24999], ids[24998]))
                    check(selection.append(batch.all(true)) == 0)
                    JSONObject().put("files", 50000)
                }
                test("long unbroken paragraphs paginate with exact offsets under a time limit") {
                    lateinit var measurer: androidx.compose.ui.text.TextMeasurer
                    runner.runOnMainSync {
                        measurer = androidx.compose.ui.text.TextMeasurer(
                            androidx.compose.ui.text.font.createFontFamilyResolver(context),
                            androidx.compose.ui.unit.Density(1f), androidx.compose.ui.unit.LayoutDirection.Ltr)
                    }
                    val samples = listOf("abcdefg".repeat(35000), "长段落正文测试".repeat(14000), "日本語 English 🌸 é ".repeat(4000))
                    var count = 0
                    withTimeout(60000) {
                        withContext(Dispatchers.Default) {
                            for (text in samples) {
                                val blocks = com.inkreader.ui.reader.Paginator.toBlocks(text)
                                val pages = com.inkreader.ui.reader.Paginator(measurer).paginate(blocks,
                                    androidx.compose.ui.text.TextStyle(fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp)),
                                    480, 800, 10f, androidx.compose.ui.unit.Density(1f), cancellation = currentCoroutineContext())
                                check(pages.isNotEmpty() && pages.first().startOffset == 0 && pages.last().endOffset == text.trimEnd().length)
                                check(pages.zipWithNext().all { (a,b) -> a.endOffset == b.startOffset })
                                for (page in pages) check(page.startOffset == 0 || !Character.isLowSurrogate(text[page.startOffset]))
                                count += pages.size
                            }
                        }
                    }
                    JSONObject().put("characters", samples.sumOf { it.length }).put("pages", count)
                }
                test("SQLite integrity, chapter indices, references, stats and body files after pressure") {
                    val database = repo.db.readableDatabase
                    database.rawQuery("PRAGMA integrity_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok") }
                    database.rawQuery("SELECT COUNT(*) FROM chapters c LEFT JOIN books b ON c.book_id=b.id WHERE b.id IS NULL", null).use { check(it.moveToFirst() && it.getInt(0) == 0) }
                    var total = 0
                    for (book in books()) {
                        val chapters = repo.chapters(book.id)
                        check(chapters.map { it.indexInBook } == chapters.indices.toList())
                        check(book.totalChapters == chapters.size)
                        check(book.totalChars == chapters.sumOf { it.charCount })
                        chapters.forEach { check(repo.files.read(it.fileName).length == it.charCount) }
                        total += chapters.size
                    }
                    JSONObject().put("books", books().size).put("chapters", total)
                }
            }
        } finally { repo.db.close() }
        val failed = (0 until results.length()).count { results.getJSONObject(it).getString("status") != "PASS" }
        return JSONObject().put("checks", results).put("failed", failed).put("passed", results.length() - failed)
    }
}
