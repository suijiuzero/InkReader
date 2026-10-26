package com.inkreader.data.repo

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.util.AtomicFile
import com.inkreader.data.db.InkDb
import com.inkreader.data.file.FileStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** 逻辑备份不复制活动数据库；正文按流读写，所有内容校验完才修改书库。 */
class LibraryArchive(private val context: Context, private val db: InkDb, private val files: FileStore) {
    companion object {
        private val gate = Any()
        private const val MAX_BYTES = 4L * 1024 * 1024 * 1024
        private val pathPattern = Regex("(?:library\\.json|(?:chapters|rich)/[0-9]+/[0-9]+\\.(?:txt|rich)|covers/[0-9]+\\.bin|notes/[0-9]+\\.json)")
        private val preferenceKeys = setOf("font_size", "line_height", "para_spacing", "font", "theme", "page_mode", "page_animation",
            "margin", "volume_key_turn", "tag_sound", "chapter_flash", "indent", "brightness", "letter_spacing", "shelf_sort", "shelf_view", "tts_rate", "tts_pitch", "tts_timer")
        private fun checkpoint() { if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException() }
    }
    private val notes = File(context.filesDir, "recognition-notes")
    private fun rows(table: String, where: String = "", args: Array<String>? = null): JSONArray = JSONArray().also { out ->
        db.readableDatabase.rawQuery("SELECT * FROM $table $where", args).use { cursor ->
            while (cursor.moveToNext()) out.put(JSONObject().also { row -> cursor.columnNames.forEachIndexed { i, name ->
                row.put(name, when (cursor.getType(i)) { Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i); Cursor.FIELD_TYPE_NULL -> JSONObject.NULL; else -> cursor.getString(i) })
            } })
        }
    }
    private fun values(row: JSONObject, table: String): ContentValues {
        val columns = db.readableDatabase.rawQuery("PRAGMA table_info($table)", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
        return ContentValues().apply { row.keys().forEach { key ->
            require(key in columns) { "Unexpected $table field" }
            if (key != "id") when (val value = row.get(key)) {
                JSONObject.NULL -> putNull(key); is String -> { require(value.length <= 1000000); put(key, value) }
                is Double -> { require(value.isFinite()); put(key, value) }; is Number -> put(key, value.toLong())
                else -> error("Invalid field")
            }
        } }
    }
    fun write(output: OutputStream, bookId: Long? = null) = synchronized(gate) {
        db.inTransaction {
            val books = rows("books", if (bookId == null) "ORDER BY id" else "WHERE id=?", bookId?.let { arrayOf(it.toString()) })
            require(bookId == null || books.length() == 1)
            val entries = JSONObject()
            ZipOutputStream(output.buffered()).use { zip ->
                fun add(name: String, file: File) {
                    checkpoint(); require(pathPattern.matches(name))
                    zip.putNextEntry(ZipEntry(name)); val hash = MessageDigest.getInstance("SHA-256"); var size = 0L
                    AtomicFile(file).openRead().use { input -> val buffer = ByteArray(32768)
                        while (true) { checkpoint(); val n = input.read(buffer); if (n < 0) break
                            hash.update(buffer, 0, n); zip.write(buffer, 0, n); size += n }
                    }
                    zip.closeEntry()
                    entries.put(name, JSONObject().put("bytes", size).put("sha256", hash.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }))
                }
                for (i in 0 until books.length()) {
                    val book = books.getJSONObject(i); val id = book.getLong("id")
                    val chapters = rows("chapters", "WHERE book_id=? ORDER BY idx,id", arrayOf(id.toString()))
                    for (j in 0 until chapters.length()) {
                        val chapter = chapters.getJSONObject(j); val name = chapter.getString("file")
                        val textPath = "chapters/$id/$j.txt"; add(textPath, files.file(name)); chapter.put("archive_text", textPath)
                        val rich = files.file(files.richNameOf(name))
                        if (rich.isFile || File(rich.path + ".bak").isFile) { val richPath = "rich/$id/$j.rich"; add(richPath, rich); chapter.put("archive_rich", richPath) }
                    }
                    book.put("chapters", chapters).put("marks", rows("marks", "WHERE book_id=? ORDER BY id", arrayOf(id.toString())))
                    if (book.optString("cover_path").isNotEmpty()) { val path = "covers/$id.bin"; add(path, files.coverFile(book.getString("cover_path"))); book.put("archive_cover", path) }
                    val note = File(notes, "book_$id.json")
                    if (note.isFile || File(note.path + ".bak").isFile) { val path = "notes/$id.json"; add(path, note); book.put("archive_notes", path) }
                }
                val preferences = JSONObject()
                if (bookId == null) context.getSharedPreferences("ink_prefs", Context.MODE_PRIVATE).all.forEach { (key, value) ->
                    if (key in preferenceKeys && value != null) preferences.put(key, JSONObject().put("type", value.javaClass.simpleName).put("value", value))
                }
                val manifest = JSONObject().put("format", "ink-reader-library").put("version", 1).put("books", books)
                    .put("groups", if (bookId == null) rows("groups", "ORDER BY sort_order,id") else
                        rows("groups", "WHERE id=?", arrayOf(books.getJSONObject(0).optLong("group_id").toString())))
                    .put("preferences", preferences).put("files", entries).toString().toByteArray()
                require(manifest.size <= 16 * 1024 * 1024) { "Backup metadata too large" }
                zip.putNextEntry(ZipEntry("library.json")); zip.write(manifest); zip.closeEntry()
            }
        }
    }

    /** 普通恢复添加新书，避免覆盖已有内容；撤销只允许替换调用方指定的同一本书。 */
    fun restore(input: InputStream, replaceBook: Long? = null): Int = synchronized(gate) {
        val staging = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val stagedChapters = ArrayList<String>(); val stagedCovers = ArrayList<String>(); val newNotes = ArrayList<Long>()
        val previousNotes = replaceBook?.let { id ->
            val note = File(notes, "book_$id.json")
            if (!note.isFile && !File(note.path + ".bak").isFile) null else AtomicFile(note).openRead().use { input ->
                require(note.length() <= 8L * 1024 * 1024); input.readBytes()
            }
        }
        val prefs = context.getSharedPreferences("ink_prefs", Context.MODE_PRIVATE)
        val originalPrefs = prefs.all.filterKeys { it in preferenceKeys }
        var prefsChanged = false
        val obsolete = ArrayList<String>(); var obsoleteCover = ""
        try {
            val hashes = HashMap<String, Pair<Long, String>>(); var total = 0L
            ZipInputStream(input.buffered()).use { zip ->
                val buffer = ByteArray(32768)
                while (true) {
                    checkpoint(); val entry = zip.nextEntry ?: break
                    require(pathPattern.matches(entry.name) && !entry.isDirectory && entry.name !in hashes && hashes.size < 100000) { "Invalid archive entry" }
                    val target = File(staging, entry.name); check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                    val hash = MessageDigest.getInstance("SHA-256"); var size = 0L
                    target.outputStream().use { output -> while (true) {
                        checkpoint(); val n = zip.read(buffer); if (n < 0) break; size += n; total += n
                        require(total <= MAX_BYTES && size <= 256L * 1024 * 1024 && (entry.name != "library.json" || size <= 16 * 1024 * 1024)) { "Archive exceeds budget" }
                        output.write(buffer, 0, n); hash.update(buffer, 0, n)
                    } }
                    hashes[entry.name] = size to hash.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
                    zip.closeEntry()
                }
            }
            val manifest = JSONObject(File(staging, "library.json").readText())
            require(manifest.getString("format") == "ink-reader-library" && manifest.getInt("version") == 1)
            val expectedFiles = manifest.getJSONObject("files")
            require(expectedFiles.keys().asSequence().toSet() == hashes.keys - "library.json")
            expectedFiles.keys().forEach { name -> val expected = expectedFiles.getJSONObject(name)
                require(hashes[name] == (expected.getLong("bytes") to expected.getString("sha256"))) { "Archive checksum mismatch" } }
            fun source(path: String) = File(staging, path).also { require(path in hashes && path != "library.json") }
            val books = manifest.getJSONArray("books"); require(books.length() <= 50000)
            if (replaceBook != null) require(books.length() == 1 && books.getJSONObject(0).getLong("id") == replaceBook && db.getBook(replaceBook) != null)
            val groups = manifest.getJSONArray("groups"); val groupMap = HashMap<Long, Long>()
            val oldIds = HashSet<Long>()
            db.inTransaction {
                for (i in 0 until groups.length()) {
                    val group = groups.getJSONObject(i); val old = group.getLong("id")
                    val existing = if (replaceBook != null) db.groups().firstOrNull { it.id == old }?.id else null
                    val id = existing ?: db.writableDatabase.insertOrThrow("groups", null, values(group, "groups"))
                    require(groupMap.put(old, id) == null)
                }
                for (i in 0 until books.length()) {
                    checkpoint(); val book = books.getJSONObject(i); val old = book.getLong("id"); require(oldIds.add(old))
                    val metadata = JSONObject(book.toString())
                    listOf("chapters", "marks", "archive_cover", "archive_notes").forEach { metadata.remove(it) }
                    val content = values(metadata, "books").apply { put("group_id", groupMap[book.optLong("group_id")] ?: 0L); put("cover_path", "") }
                    val id = if (replaceBook != null) {
                        obsolete += db.chaptersOf(replaceBook).map { it.fileName }; obsoleteCover = db.getBook(replaceBook)!!.coverPath
                        db.writableDatabase.delete("chapters", "book_id=?", arrayOf(replaceBook.toString()))
                        db.writableDatabase.delete("marks", "book_id=?", arrayOf(replaceBook.toString()))
                        check(db.writableDatabase.update("books", content, "id=?", arrayOf(replaceBook.toString())) == 1); replaceBook
                    } else db.writableDatabase.insertOrThrow("books", null, content)
                    val chapters = book.getJSONArray("chapters"); require(chapters.length() in 1..500000)
                    var chars = 0L; val sizes = ArrayList<Int>()
                    for (j in 0 until chapters.length()) {
                        checkpoint(); val chapter = JSONObject(chapters.getJSONObject(j).toString())
                        val name = files.newFileName(); stagedChapters += name
                        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        val count = java.io.InputStreamReader(source(chapter.getString("archive_text")).inputStream(), decoder).use { files.write(name, it) }
                        require(count == chapter.getInt("chars")) { "Chapter length mismatch" }; chars += count; sizes += count
                        chapter.optString("archive_rich").takeIf { it.isNotEmpty() }?.let { path ->
                            val rich = source(path); require(rich.length() <= 16L * 1024 * 1024) { "Rich metadata exceeds budget" }
                            rich.copyTo(files.file(files.richNameOf(name)), overwrite = false)
                        }
                        chapter.remove("archive_text"); chapter.remove("archive_rich")
                        val cv = values(chapter, "chapters").apply { put("book_id", id); put("idx", j); put("file", name) }
                        if (replaceBook != null) cv.put("id", chapter.getLong("id"))
                        db.writableDatabase.insertOrThrow("chapters", null, cv)
                    }
                    require(chars <= Int.MAX_VALUE && chars == book.getLong("total_chars") && chapters.length() == book.getInt("total_chapters"))
                    val marks = book.getJSONArray("marks")
                    for (j in 0 until marks.length()) {
                        val mark = marks.getJSONObject(j); val chapter = mark.getInt("chapter")
                        require(chapter in sizes.indices && mark.getInt("start_off") in 0..sizes[chapter] && mark.getInt("end_off") in mark.getInt("start_off")..sizes[chapter])
                        val cv = values(mark, "marks").apply { put("book_id", id) }
                        if (replaceBook != null) cv.put("id", mark.getLong("id"))
                        db.writableDatabase.insertOrThrow("marks", null, cv)
                    }
                    book.optString("archive_cover").takeIf { it.isNotEmpty() }?.let { path ->
                        val name = "restored_${UUID.randomUUID()}.jpg"; stagedCovers += name; source(path).copyTo(files.coverFile(name)); db.updateBookCover(id, name)
                    }
                    book.optString("archive_notes").takeIf { it.isNotEmpty() }?.let { path ->
                        val noteFile = source(path); require(noteFile.length() <= 8L * 1024 * 1024)
                        val note = JSONObject(noteFile.readText()); require(note.getInt("version") == 1 && note.getLong("book") == old)
                        note.put("book", id); check(notes.isDirectory || notes.mkdirs())
                        val atomic = AtomicFile(File(notes, "book_$id.json")); val output = atomic.startWrite(); newNotes += id
                        try { output.write(note.toString().toByteArray()); atomic.finishWrite(output) } catch (t: Throwable) { atomic.failWrite(output); throw t }
                    }
                    if (replaceBook != null && !book.has("archive_notes")) { newNotes += id; AtomicFile(File(notes, "book_$id.json")).delete() }
                }
                if (replaceBook == null) {
                    val saved = manifest.getJSONObject("preferences"); val editor = prefs.edit()
                    preferenceKeys.forEach { editor.remove(it) }
                    saved.keys().forEach { key -> require(key in preferenceKeys); val row = saved.getJSONObject(key)
                        val expectedType = when (key) {
                            "indent", "volume_key_turn", "tag_sound", "chapter_flash" -> "Boolean"
                            "tts_timer" -> "Integer"
                            "font", "theme", "page_mode", "page_animation", "shelf_sort", "shelf_view" -> "String"
                            else -> "Float"
                        }
                        require(row.getString("type") == expectedType) { "Preference type mismatch" }
                        if (expectedType == "Float") {
                            val value = row.getDouble("value")
                            val range = when (key) {
                                "font_size" -> 1.0..300.0; "line_height" -> .1..20.0
                                "para_spacing" -> 0.0..100.0; "margin" -> 0.0..1000.0
                                "brightness" -> -1.0..1.0; "letter_spacing" -> -1.0..10.0
                                else -> .1..10.0
                            }
                            require(value.isFinite() && value in range) { "Invalid preference value" }
                        }
                        if (expectedType == "Integer") require(row.getInt("value") in 0..1440)
                        if (expectedType == "String") require(row.getString("value").length <= 200)
                        when (row.getString("type")) {
                            "Float" -> editor.putFloat(key, row.getDouble("value").toFloat())
                            "Integer" -> editor.putInt(key, row.getInt("value")); "Long" -> editor.putLong(key, row.getLong("value"))
                            "Boolean" -> editor.putBoolean(key, row.getBoolean("value")); "String" -> editor.putString(key, row.getString("value"))
                            else -> error("Unsupported preference")
                        }
                    }
                    prefsChanged = true; check(editor.commit())
                }
                checkpoint()
            }
            obsolete.forEach { files.deleteChapter(it) }; if (obsoleteCover.isNotEmpty()) files.deleteCover(obsoleteCover)
            books.length()
        } catch (t: Throwable) {
            stagedChapters.forEach { files.deleteChapter(it) }; stagedCovers.forEach { files.deleteCover(it) }
            newNotes.forEach { AtomicFile(File(notes, "book_$it.json")).delete() }
            if (replaceBook != null && previousNotes != null) {
                val atomic = AtomicFile(File(notes, "book_$replaceBook.json")); val output = atomic.startWrite()
                try { output.write(previousNotes); atomic.finishWrite(output) } catch (failure: Throwable) { atomic.failWrite(output); t.addSuppressed(failure) }
            }
            if (prefsChanged) {
                val editor = prefs.edit(); preferenceKeys.forEach { editor.remove(it) }
                originalPrefs.forEach { (key, value) -> when (value) {
                    is Float -> editor.putFloat(key, value); is Int -> editor.putInt(key, value); is Long -> editor.putLong(key, value)
                    is Boolean -> editor.putBoolean(key, value); is String -> editor.putString(key, value)
                } }; editor.commit()
            }
            throw t
        } finally { staging.deleteRecursively() }
    }
}
