package com.inkreader.data.repo

import android.content.Context
import android.util.AtomicFile
import com.inkreader.parse.ChapterCorrections
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ChapterCorrectionStore(context: Context) {
    private val root = File(context.filesDir, "recognition-notes")
    fun load(bookId: Long): List<ChapterCorrections.Anchor> = runCatching {
        require(bookId > 0)
        val file = File(root, "book_$bookId.json")
        if (!file.isFile || file.length() > 131072) return emptyList()
        val json = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
        require(json.getInt("version") == 1 && json.getLong("book") == bookId)
        val values = json.getJSONArray("anchors"); require(values.length() <= 40)
        (0 until values.length()).map { i -> values.getJSONObject(i).let {
            ChapterCorrections.Anchor(it.getString("title"), it.getString("context"), it.getBoolean("keep"))
        } }
    }.getOrDefault(emptyList())
    fun save(bookId: Long, sourceHash: String, anchors: List<ChapterCorrections.Anchor>) {
        require(bookId > 0 && anchors.size <= 40)
        check(root.isDirectory || root.mkdirs())
        val values = JSONArray()
        anchors.forEach { values.put(JSONObject().put("title", it.title).put("context", it.context).put("keep", it.keep)) }
        val value = JSONObject().put("version", 1).put("book", bookId).put("source", sourceHash).put("anchors", values)
        val atomic = AtomicFile(File(root, "book_$bookId.json")); val output = atomic.startWrite()
        try { output.write(value.toString().toByteArray()); atomic.finishWrite(output) }
        catch (t: Throwable) { atomic.failWrite(output); throw t }
    }
    fun clear(bookId: Long) { require(bookId > 0); AtomicFile(File(root, "book_$bookId.json")).delete() }
}
