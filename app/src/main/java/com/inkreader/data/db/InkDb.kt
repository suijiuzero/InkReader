package com.inkreader.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.inkreader.data.model.Book
import com.inkreader.data.model.BookGroup
import com.inkreader.data.model.Chapter
import com.inkreader.data.model.Mark
import com.inkreader.data.model.MarkType
import com.inkreader.data.model.ShelfSort
import com.inkreader.data.model.SourceType

/**
 * 轻量 SQLite 存储层。
 * 有意不使用 ORM / 注解处理器：表结构简单，手写 SQL 可以去掉一整类构建期风险，
 * 也让编译速度和产物体积更可控。
 */
class InkDb(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE books(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              title TEXT NOT NULL,
              author TEXT DEFAULT '',
              source TEXT DEFAULT 'TXT',
              hue INTEGER DEFAULT 0,
              total_chapters INTEGER DEFAULT 0,
              total_chars INTEGER DEFAULT 0,
              last_chapter INTEGER DEFAULT 0,
              last_offset INTEGER DEFAULT 0,
              last_read INTEGER DEFAULT 0,
              created INTEGER DEFAULT 0,
              progress REAL DEFAULT 0,
              cover_path TEXT DEFAULT '',
              group_id INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE chapters(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              book_id INTEGER NOT NULL,
              idx INTEGER NOT NULL,
              title TEXT DEFAULT '',
              file TEXT DEFAULT '',
              chars INTEGER DEFAULT 0,
              source TEXT DEFAULT '',
              volume TEXT DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_chapters_book ON chapters(book_id, idx)")
        db.execSQL(
            """
            CREATE TABLE marks(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              book_id INTEGER NOT NULL,
              chapter INTEGER DEFAULT 0,
              start_off INTEGER DEFAULT 0,
              end_off INTEGER DEFAULT 0,
              type TEXT DEFAULT 'HIGHLIGHT',
              color INTEGER DEFAULT 0,
              note TEXT DEFAULT '',
              snippet TEXT DEFAULT '',
              created INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_marks_book ON marks(book_id, chapter)")
        // 书架分组（一本书最多属于一个分组；group_id = 0 表示未分组）
        db.execSQL(
            """
            CREATE TABLE groups(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              sort_order INTEGER DEFAULT 0,
              created INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // 加列一律先查 PRAGMA 再 ALTER（幂等），并且**不吞异常**：
        // 升级期就该失败，而不是把版本号提上去、等到之后某次写库才抛 "no such column"。
        //（历史上这里用 runCatching 把失败吞掉了，写侧又没有兜底，属于难定位的雷。）
        if (old < 2) addColumn(db, "books", "cover_path", "TEXT DEFAULT ''")
        if (old < 3) {
            addColumn(db, "books", "group_id", "INTEGER DEFAULT 0")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS groups(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  name TEXT NOT NULL,
                  sort_order INTEGER DEFAULT 0,
                  created INTEGER DEFAULT 0
                )
                """.trimIndent()
            )
        }
        if (old < 4) addColumn(db, "chapters", "volume", "TEXT DEFAULT ''")
    }

    /** 列不存在才加（ALTER 重复执行会抛 duplicate column name，这里先查一次避开） */
    private fun addColumn(db: SQLiteDatabase, table: String, column: String, type: String) {
        if (hasColumn(db, table, column)) return
        db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == column) return true
            }
            false
        }

    // ---------------- 分组 ----------------

    fun insertGroup(name: String): Long {
        val cv = ContentValues().apply {
            put("name", name)
            put("sort_order", 0)
            put("created", System.currentTimeMillis())
        }
        return writableDatabase.insert("groups", null, cv)
    }

    fun updateGroupName(id: Long, name: String) {
        val cv = ContentValues().apply { put("name", name) }
        writableDatabase.update("groups", cv, "id=?", arrayOf(id.toString()))
    }

    /** 删除分组：组内书籍回到「未分组」，书本身不动。两步必须同事务，避免出现组没了书还挂着 group_id */
    fun deleteGroup(id: Long) {
        inTransaction {
            val db = writableDatabase
            db.update("books", ContentValues().apply { put("group_id", 0L) }, "group_id=?", arrayOf(id.toString()))
            db.delete("groups", "id=?", arrayOf(id.toString()))
        }
    }

    fun groups(): List<BookGroup> {
        readableDatabase.rawQuery(
            "SELECT * FROM groups ORDER BY sort_order ASC, created ASC, id ASC",
            null
        ).use { c ->
            val out = ArrayList<BookGroup>(c.count)
            while (c.moveToNext()) {
                out += BookGroup(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    name = c.getString(c.getColumnIndexOrThrow("name")) ?: "",
                    sortOrder = c.getInt(c.getColumnIndexOrThrow("sort_order"))
                )
            }
            return out
        }
    }

    /** 把若干本书放进一个分组（groupId = 0 表示移出分组） */
    fun setBooksGroup(ids: List<Long>, groupId: Long) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues().apply { put("group_id", groupId) }
            ids.forEach { db.update("books", cv, "id=?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------------- Book ----------------

    fun insertBook(b: Book): Long {
        val cv = ContentValues().apply {
            put("title", b.title)
            put("author", b.author)
            put("source", b.sourceType.name)
            put("hue", b.coverHue)
            put("total_chapters", b.totalChapters)
            put("total_chars", b.totalChars)
            put("last_chapter", b.lastChapterIndex)
            put("last_offset", b.lastCharOffset)
            put("last_read", b.lastReadAt)
            put("created", b.createdAt)
            put("progress", b.progress.toDouble())
            put("cover_path", b.coverPath)
            put("group_id", b.groupId)
        }
        return writableDatabase.insert("books", null, cv)
    }

    /** 设置 / 清空自定义封面 */
    fun updateBookCover(id: Long, coverPath: String) {
        val cv = ContentValues().apply { put("cover_path", coverPath) }
        writableDatabase.update("books", cv, "id=?", arrayOf(id.toString()))
    }

    fun updateBookMeta(id: Long, title: String, author: String) {
        val cv = ContentValues().apply {
            put("title", title)
            put("author", author)
        }
        writableDatabase.update("books", cv, "id=?", arrayOf(id.toString()))
    }

    fun updateBookStats(id: Long, chapters: Int, chars: Int) {
        val cv = ContentValues().apply {
            put("total_chapters", chapters)
            put("total_chars", chars)
        }
        writableDatabase.update("books", cv, "id=?", arrayOf(id.toString()))
    }

    fun updateProgress(id: Long, chapterIndex: Int, charOffset: Int, progress: Float) {
        val cv = ContentValues().apply {
            put("last_chapter", chapterIndex)
            put("last_offset", charOffset)
            put("progress", progress.toDouble())
            put("last_read", System.currentTimeMillis())
        }
        writableDatabase.update("books", cv, "id=?", arrayOf(id.toString()))
    }

    fun getBook(id: Long): Book? {
        readableDatabase.rawQuery("SELECT * FROM books WHERE id=?", arrayOf(id.toString())).use { c ->
            return if (c.moveToFirst()) bookFrom(c) else null
        }
    }

    fun getBooks(sort: ShelfSort): List<Book> {
        val order = when (sort) {
            ShelfSort.RECENT -> "last_read DESC, created DESC"
            ShelfSort.TITLE -> "title COLLATE NOCASE ASC"
            ShelfSort.PROGRESS -> "progress DESC, last_read DESC"
            ShelfSort.ADDED -> "created DESC"
        }
        readableDatabase.rawQuery("SELECT * FROM books ORDER BY $order", null).use { c ->
            val out = ArrayList<Book>(c.count)
            while (c.moveToNext()) out += bookFrom(c)
            return out
        }
    }

    /**
     * 在同一个事务里执行 [block]；块内抛异常则整体回滚。
     * 用于「多条 SQL 必须同生共死」的场景（删书、删章、重排、合并、拆分）。
     */
    fun <T> inTransaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    /** 删除一本书：标记、章节、书籍本身必须一起删掉，避免留下半截数据 */
    fun deleteBook(id: Long): List<String> {
        val files = chaptersOf(id).map { it.fileName }.filter { it.isNotEmpty() }
        inTransaction {
            val db = writableDatabase
            db.delete("marks", "book_id=?", arrayOf(id.toString()))
            db.delete("chapters", "book_id=?", arrayOf(id.toString()))
            db.delete("books", "id=?", arrayOf(id.toString()))
        }
        return files
    }

    private fun bookFrom(c: android.database.Cursor) = Book(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        title = c.getString(c.getColumnIndexOrThrow("title")) ?: "",
        author = c.getString(c.getColumnIndexOrThrow("author")) ?: "",
        sourceType = runCatching {
            SourceType.valueOf(c.getString(c.getColumnIndexOrThrow("source")) ?: "TXT")
        }.getOrDefault(SourceType.TXT),
        coverHue = c.getInt(c.getColumnIndexOrThrow("hue")),
        coverPath = c.getColumnIndex("cover_path").let { i ->
            if (i >= 0) c.getString(i) ?: "" else ""
        },
        groupId = c.getColumnIndex("group_id").let { i ->
            if (i >= 0) c.getLong(i) else 0L
        },
        totalChapters = c.getInt(c.getColumnIndexOrThrow("total_chapters")),
        totalChars = c.getInt(c.getColumnIndexOrThrow("total_chars")),
        lastChapterIndex = c.getInt(c.getColumnIndexOrThrow("last_chapter")),
        lastCharOffset = c.getInt(c.getColumnIndexOrThrow("last_offset")),
        lastReadAt = c.getLong(c.getColumnIndexOrThrow("last_read")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created")),
        progress = c.getFloat(c.getColumnIndexOrThrow("progress"))
    )

    // ---------------- Chapter ----------------

    fun insertChapter(ch: Chapter): Long {
        val cv = ContentValues().apply {
            put("book_id", ch.bookId)
            put("idx", ch.indexInBook)
            put("title", ch.title)
            put("file", ch.fileName)
            put("chars", ch.charCount)
            put("source", ch.sourceName)
            put("volume", ch.volume)
        }
        return writableDatabase.insert("chapters", null, cv)
    }

    fun chaptersOf(bookId: Long): List<Chapter> {
        readableDatabase.rawQuery(
            "SELECT * FROM chapters WHERE book_id=? ORDER BY idx ASC",
            arrayOf(bookId.toString())
        ).use { c ->
            val out = ArrayList<Chapter>(c.count)
            while (c.moveToNext()) out += chapterFrom(c)
            return out
        }
    }

    fun updateChapterTitle(id: Long, title: String) {
        val cv = ContentValues().apply { put("title", title) }
        writableDatabase.update("chapters", cv, "id=?", arrayOf(id.toString()))
    }

    fun updateChapterVolume(id: Long, volume: String) {
        val cv = ContentValues().apply { put("volume", volume) }
        writableDatabase.update("chapters", cv, "id=?", arrayOf(id.toString()))
    }

    fun updateChapterFile(id: Long, fileName: String, chars: Int) {
        val cv = ContentValues().apply {
            put("file", fileName)
            put("chars", chars)
        }
        writableDatabase.update("chapters", cv, "id=?", arrayOf(id.toString()))
    }

    fun deleteChapter(id: Long): String? {
        val file = readableDatabase.rawQuery(
            "SELECT file FROM chapters WHERE id=?", arrayOf(id.toString())
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        writableDatabase.delete("chapters", "id=?", arrayOf(id.toString()))
        return file
    }

    fun setChapterIndex(id: Long, idx: Int) {
        val cv = ContentValues().apply { put("idx", idx) }
        writableDatabase.update("chapters", cv, "id=?", arrayOf(id.toString()))
    }

    /** 章节重排后统一重建索引 */
    fun reindexChapters(bookId: Long, orderedIds: List<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            orderedIds.forEachIndexed { i, id ->
                db.update(
                    "chapters",
                    ContentValues().apply { put("idx", i) },
                    "id=?", arrayOf(id.toString())
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun chapterCount(bookId: Long): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM chapters WHERE book_id=?", arrayOf(bookId.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun chapterFrom(c: android.database.Cursor) = Chapter(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        bookId = c.getLong(c.getColumnIndexOrThrow("book_id")),
        indexInBook = c.getInt(c.getColumnIndexOrThrow("idx")),
        title = c.getString(c.getColumnIndexOrThrow("title")) ?: "",
        fileName = c.getString(c.getColumnIndexOrThrow("file")) ?: "",
        charCount = c.getInt(c.getColumnIndexOrThrow("chars")),
        sourceName = c.getString(c.getColumnIndexOrThrow("source")) ?: "",
        volume = c.getColumnIndex("volume").let { i ->
            if (i >= 0) c.getString(i) ?: "" else ""
        }
    )

    // ---------------- Mark ----------------

    fun insertMark(m: Mark): Long {
        val cv = ContentValues().apply {
            put("book_id", m.bookId)
            put("chapter", m.chapterIndex)
            put("start_off", m.startOffset)
            put("end_off", m.endOffset)
            put("type", m.type.name)
            put("color", m.color)
            put("note", m.note)
            put("snippet", m.snippet)
            put("created", m.createdAt)
        }
        return writableDatabase.insert("marks", null, cv)
    }

    fun marksOf(bookId: Long, chapterIndex: Int = -1): List<Mark> {
        val (sql, args) = if (chapterIndex < 0)
            "SELECT * FROM marks WHERE book_id=? ORDER BY chapter ASC, start_off ASC" to arrayOf(bookId.toString())
        else
            "SELECT * FROM marks WHERE book_id=? AND chapter=? ORDER BY start_off ASC" to
                arrayOf(bookId.toString(), chapterIndex.toString())
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<Mark>(c.count)
            while (c.moveToNext()) out += markFrom(c)
            return out
        }
    }

    fun findMark(bookId: Long, chapterIndex: Int, start: Int, end: Int, type: MarkType): Mark? =
        readableDatabase.rawQuery(
            "SELECT * FROM marks WHERE book_id=? AND chapter=? AND start_off=? AND end_off=? AND type=?",
            arrayOf(bookId.toString(), chapterIndex.toString(), start.toString(), end.toString(), type.name)
        ).use { c -> if (c.moveToFirst()) markFrom(c) else null }

    fun updateMark(m: Mark) {
        val cv = ContentValues().apply {
            put("chapter", m.chapterIndex)
            put("start_off", m.startOffset)
            put("end_off", m.endOffset)
            put("type", m.type.name)
            put("color", m.color)
            put("note", m.note)
            put("snippet", m.snippet)
        }
        writableDatabase.update("marks", cv, "id=?", arrayOf(m.id.toString()))
    }

    fun deleteMark(id: Long) {
        writableDatabase.delete("marks", "id=?", arrayOf(id.toString()))
    }

    fun deleteMarksInChapter(bookId: Long, chapterIndex: Int) {
        writableDatabase.delete(
            "marks", "book_id=? AND chapter=?",
            arrayOf(bookId.toString(), chapterIndex.toString())
        )
    }

    private fun markFrom(c: android.database.Cursor) = Mark(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        bookId = c.getLong(c.getColumnIndexOrThrow("book_id")),
        chapterIndex = c.getInt(c.getColumnIndexOrThrow("chapter")),
        startOffset = c.getInt(c.getColumnIndexOrThrow("start_off")),
        endOffset = c.getInt(c.getColumnIndexOrThrow("end_off")),
        type = runCatching {
            MarkType.valueOf(c.getString(c.getColumnIndexOrThrow("type")) ?: "HIGHLIGHT")
        }.getOrDefault(MarkType.HIGHLIGHT),
        color = c.getInt(c.getColumnIndexOrThrow("color")),
        note = c.getString(c.getColumnIndexOrThrow("note")) ?: "",
        snippet = c.getString(c.getColumnIndexOrThrow("snippet")) ?: "",
        createdAt = c.getLong(c.getColumnIndexOrThrow("created"))
    )

    companion object {
        private const val DB_NAME = "ink_reader.db"
        private const val DB_VERSION = 4
    }
}
