package com.inkreader.data.export

import com.inkreader.data.model.Book
import com.inkreader.data.model.Chapter
import com.inkreader.parse.RichChapter
import java.io.Closeable
import java.io.File

/** A coherent, temporary copy; long format generation never holds the book write lock. */
class ExportSnapshot(val book: Book, val chapters: List<Chapter>, private val directory: File) : Closeable {
    fun text(chapter: Chapter): String = File(directory, "${chapter.id}.txt").readText()
    fun rich(chapter: Chapter): RichChapter? = File(directory, "${chapter.id}.rich").takeIf { it.isFile }
        ?.readText()?.let(RichChapter::decode)
    fun image(key: String): File = File(directory, "$key.img")
    override fun close() { directory.deleteRecursively() }
}
