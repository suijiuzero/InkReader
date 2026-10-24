package com.inkreader

import android.app.Instrumentation
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import com.inkreader.data.model.*
import com.inkreader.data.repo.BookRepository
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Repeated real file/database churn; each round must return to an empty isolated library. */
object SoakChecks {
    suspend fun run(runner: Instrumentation, context: Context): JSONObject {
        val repo = BookRepository(context)
        val results = JSONArray()
        val start = SystemClock.elapsedRealtime()
        val sources = (1..25).map { n ->
            Uri.fromFile(File(context.cacheDir,"soak-$n.txt").apply { writeText((1..6).joinToString("\n\n") { ch ->
                "第${ch}章 反复测试\n\n" + "SOAK_${n}_${ch}_END 正文保留测试。".repeat(20)
            }) })
        }
        var baseHeap = 0L
        var endHeap = 0L
        val fdStart = File("/proc/self/fd").list()?.size ?: 0
        try {
            repeat(20) { round ->
                val before = SystemClock.elapsedRealtime()
                val imported = repo.importDocuments(sources,ImportMode.SEPARATE,null,fileOrder=ImportOrder.SELECTION) { _,_ -> }
                check(imported.failed.isEmpty() && imported.bookIds.size==25)
                val group=repo.createGroup("soak-group-$round")
                repo.moveBooksToGroup(imported.bookIds,group)
                for ((n,id) in imported.bookIds.withIndex()) {
                    val chapters=repo.chapters(id)
                    check(chapters.size==6)
                    repo.addMark(Mark(bookId=id,chapterIndex=0,startOffset=0,endOffset=4,note="SOAK_NOTE"))
                    check(repo.searchInBook(id,"SOAK_").size==120)
                    val first=chapters.first()
                    val original=repo.chapterText(first)
                    repo.saveChapterText(first,"PREFIX_$round " + original,expectedText=original)
                    check(repo.chapterText(first).startsWith("PREFIX_$round "))
                    repo.deleteBook(id)
                }
                repo.deleteGroup(group)
                check(repo.books(ShelfSort.RECENT).isEmpty() && repo.groups().isEmpty())
                for (table in listOf("chapters","marks")) repo.db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table",null).use {
                    check(it.moveToFirst() && it.getInt(0)==0)
                }
                check(File(context.filesDir,"chapters").listFiles().orEmpty().isEmpty()) { "Orphan files after round $round" }
                System.gc(); delay(30)
                val runtime=Runtime.getRuntime()
                val heap=runtime.totalMemory()-runtime.freeMemory()
                if(round==0) baseHeap=heap
                endHeap=heap
                val result=JSONObject().put("name","churn round ${round+1}/20").put("status","PASS")
                    .put("ms",SystemClock.elapsedRealtime()-before).put("heapAfterGcMiB",heap/1048576.0)
                results.put(result)
                runner.sendStatus(0,Bundle().apply { putString("stream",result.toString()+"\n") })
            }
            repo.db.readableDatabase.rawQuery("PRAGMA integrity_check",null).use { check(it.moveToFirst() && it.getString(0)=="ok") }
            val fdEnd=File("/proc/self/fd").list()?.size ?: 0
            check(endHeap-baseHeap < 32L*1048576) { "Unexpected retained heap growth: ${endHeap-baseHeap}" }
            check(fdEnd-fdStart < 16) { "Open descriptors grew: $fdStart -> $fdEnd" }
            return JSONObject().put("checks",results).put("passed",20).put("failed",0)
                .put("booksImportedAndDeleted",500).put("chaptersImportedAndDeleted",3000)
                .put("heapGrowthAfterGcMiB",(endHeap-baseHeap)/1048576.0)
                .put("fileDescriptorsBefore",fdStart).put("fileDescriptorsAfter",fdEnd)
                .put("seconds",(SystemClock.elapsedRealtime()-start)/1000.0)
        } finally { repo.db.close() }
    }
}
