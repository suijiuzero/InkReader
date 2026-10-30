package com.inkreader

import android.content.Context
import android.net.Uri
import com.inkreader.data.model.*
import com.inkreader.data.repo.BookRepository
import com.inkreader.parse.ChapterSplitter
import org.json.JSONObject
import java.io.File

object DuplicateChapterImportChecks {
    suspend fun run(context: Context): JSONObject {
        val repo = BookRepository(context)
        val prose = "This complete paragraph must remain after duplicate-heading cleanup. ".repeat(15)
        val source = "第五十八章 出发\n\n$prose\n\n第59章 到达\n\n正文\n\n第59章 到达\n\n$prose\n\n第60章 归来\n\n$prose"
        val file = File(context.cacheDir, "duplicate-content-marker.txt").apply { writeText(source) }
        val options = SplitOptions(cleanWatermark = false)
        suspend fun verify(id: Long) {
            val chapters = repo.chapters(id)
            check(chapters.map { ChapterSplitter.chapterOrderFromTitle(it.title) } == listOf(58, 59, 60))
            val bodies = chapters.map { repo.chapterText(it) }
            check(bodies == listOf(prose, "正文\n\n\n$prose", prose)) { "Repeated-heading cleanup changed body text" }
        }
        try {
            val imported = repo.importResumable(listOf(Uri.fromFile(file)), ImportMode.SEPARATE, null, options, ImportOrder.SELECTION) { _, _ -> }
            check(imported.failed.isEmpty()); verify(imported.bookIds.single())
            // Recreate an already-imported chapter whose body still contains the extra title.
            val chapter = repo.chapters(imported.bookIds.single())[1]
            repo.saveChapterText(chapter, "正文\n\n第59章 到达\n\n$prose")
            repo.resplitBook(imported.bookIds.single(), options)
            verify(imported.bookIds.single())
            file.writeText("第14章 空章\n\n第15章 继续\n\n$prose")
            val empty = repo.importResumable(listOf(Uri.fromFile(file)), ImportMode.SEPARATE, null, options.copy(cleanWatermark = true), ImportOrder.SELECTION) { _, _ -> }
            check(empty.failed.isEmpty())
            val empties = repo.chapters(empty.bookIds.single())
            check(empties.size == 2 && repo.chapterText(empties.first()).isEmpty() && repo.chapterText(empties.last()) == prose)
            return JSONObject().put("passed", 3).put("emptyChapterDoesNotInventBody", true).put("freshImport", true).put("existingBookResplit", true).put("allProsePreserved", true)
        } finally { repo.db.close() }
    }
}
