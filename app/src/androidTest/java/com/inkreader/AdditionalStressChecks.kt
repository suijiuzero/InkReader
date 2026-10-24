package com.inkreader

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import com.inkreader.data.db.InkDb
import com.inkreader.data.export.BookExporter
import com.inkreader.data.model.*
import com.inkreader.data.repo.BookRepository
import com.inkreader.parse.DocumentParsers
import com.inkreader.parse.ParseException
import com.inkreader.ui.reader.ReaderSession
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object AdditionalStressChecks {
    suspend fun run(runner: Instrumentation, context: Context): JSONObject {
        val results = JSONArray()
        val repo = BookRepository(context)
        suspend fun test(name: String, block: suspend () -> JSONObject) {
            val begin = SystemClock.elapsedRealtime()
            val result = JSONObject().put("name", name)
            try { result.put("details", block()).put("status", "PASS") }
            catch (t: Throwable) { result.put("status", "FAIL").put("error", t.stackTraceToString()) }
            result.put("ms", SystemClock.elapsedRealtime() - begin)
            results.put(result)
            runner.sendStatus(0, Bundle().apply { putString("stream", result.toString() + "\n") })
        }
        fun zip(name: String, entries: Map<String, String>): File = File(context.cacheDir, name).apply {
            ZipOutputStream(outputStream()).use { out -> entries.forEach { (path, body) ->
                out.putNextEntry(ZipEntry(path)); out.write(body.toByteArray()); out.closeEntry()
            } }
        }
        try {
            test("large DOCX, HTML and Markdown imports retain all authored paragraphs") {
                val word = zip("large.docx", mapOf("word/document.xml" to
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" +
                    (1..4000).joinToString("") { "<w:p><w:r><w:t>DOCX_BODY_${it}_END 中文正文。</w:t></w:r></w:p>" } + "</w:body></w:document>"))
                val html = File(context.cacheDir, "large.html").apply { writeText("<html><body>" +
                    (1..4000).joinToString("") { "<p>HTML_BODY_${it}_END &amp; text.</p>" } + "</body></html>") }
                val md = File(context.cacheDir, "large.md").apply { writeText((1..200).joinToString("\n\n") {
                    "# 第${it}章 Markdown\n\n**MD_BODY_${it}_END** English 日本語。" }) }
                for ((file, prefix, expected) in listOf(Triple(word,"DOCX",4000),Triple(html,"HTML",4000),Triple(md,"MD",200))) {
                    val imported = repo.importDocuments(listOf(Uri.fromFile(file)), ImportMode.SEPARATE, null) { _,_ -> }
                    check(imported.failed.isEmpty()) { imported.failed.toString() }
                    val body = repo.chapters(imported.bookIds.single()).map { repo.chapterText(it) }.joinToString("\n")
                    val markers = Regex("${prefix}_BODY_[0-9]+_END").findAll(body).map { it.value }.toList()
                    check(markers.size == expected && markers.toSet().size == expected) { "$prefix: ${markers.size}/$expected" }
                }
                JSONObject().put("DOCXParagraphs",4000).put("HTMLParagraphs",4000).put("MarkdownChapters",200)
            }
            test("two hundred EPUB spine entries preserve reverse reading order") {
                val entries = linkedMapOf("META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"book.opf\"/></rootfiles></container>")
                val manifest = (1..200).joinToString("") { "<item id=\"c$it\" href=\"chapter-$it.xhtml\" media-type=\"application/xhtml+xml\"/>" }
                val spine = (200 downTo 1).joinToString("") { "<itemref idref=\"c$it\"/>" }
                entries["book.opf"] = "<package><metadata/><manifest>$manifest</manifest><spine>$spine</spine></package>"
                (1..200).forEach { entries["chapter-$it.xhtml"] = "<html><body><h1>第${it}章 EPUB</h1><p>EPUB_BODY_${it}_END 正文。</p></body></html>" }
                val file = zip("large.epub",entries)
                val doc = DocumentParsers.parse(file.readBytes(),file.name)
                check(doc.parts.size == 200)
                doc.parts.forEachIndexed { i,p -> check(p.content.contains("EPUB_BODY_${200-i}_END")) }
                JSONObject().put("spineEntries",200)
            }
            test("one hundred PDF pages retain one thousand unique text markers") {
                val file = File(context.cacheDir,"large.pdf")
                PDDocument().use { pdf ->
                    repeat(100) { pageNumber ->
                        val page = PDPage(); pdf.addPage(page)
                        PDPageContentStream(pdf,page).use { out ->
                            out.beginText(); out.setFont(PDType1Font.HELVETICA,12f); out.newLineAtOffset(40f,740f)
                            repeat(10) { line -> out.showText("PDF_BODY_${pageNumber}_${line}_END. Story text."); out.newLineAtOffset(0f,-24f) }
                            out.endText()
                        }
                    }
                    pdf.save(file)
                }
                val imported = repo.importDocuments(listOf(Uri.fromFile(file)),ImportMode.SEPARATE,null) { _,_ -> }
                check(imported.failed.isEmpty()) { imported.failed.toString() }
                val body = repo.chapters(imported.bookIds.single()).map { repo.chapterText(it) }.joinToString("\n")
                val markers = Regex("PDF_BODY_[0-9]+_[0-9]+_END").findAll(body).map { it.value }.toList()
                check(markers.size==1000 && markers.toSet().size==1000) { "PDF retained ${markers.size}/1000 markers" }
                JSONObject().put("pages",100).put("bodyMarkers",1000)
            }
            test("old database v1 upgrades and reopens without losing existing rows") {
                val dbFile = File(context.cacheDir,"old-library.db")
                SQLiteDatabase.openOrCreateDatabase(dbFile,null).use { old ->
                    old.execSQL("CREATE TABLE books(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,author TEXT DEFAULT '',source TEXT DEFAULT 'TXT',hue INTEGER DEFAULT 0,total_chapters INTEGER DEFAULT 0,total_chars INTEGER DEFAULT 0,last_chapter INTEGER DEFAULT 0,last_offset INTEGER DEFAULT 0,last_read INTEGER DEFAULT 0,created INTEGER DEFAULT 0,progress REAL DEFAULT 0)")
                    old.execSQL("CREATE TABLE chapters(id INTEGER PRIMARY KEY AUTOINCREMENT,book_id INTEGER,idx INTEGER,title TEXT,file TEXT,chars INTEGER,source TEXT)")
                    old.execSQL("CREATE TABLE marks(id INTEGER PRIMARY KEY AUTOINCREMENT,book_id INTEGER,chapter INTEGER,start_off INTEGER,end_off INTEGER,type TEXT,color INTEGER,note TEXT,snippet TEXT,created INTEGER)")
                    old.execSQL("INSERT INTO books(id,title,total_chapters,total_chars) VALUES(7,'MIGRATION_SENTINEL',1,8)")
                    old.execSQL("INSERT INTO chapters VALUES(11,7,0,'old chapter','old.txt',8,'original.txt')")
                    old.version=1
                }
                val migration = object : ContextWrapper(context) {
                    override fun getApplicationContext(): Context=this
                    override fun getDatabasePath(name: String)=dbFile
                    override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?)=SQLiteDatabase.openOrCreateDatabase(dbFile,factory)
                    override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,errorHandler:DatabaseErrorHandler?)=SQLiteDatabase.openDatabase(dbFile.path,factory,SQLiteDatabase.CREATE_IF_NECESSARY,errorHandler)
                }
                repeat(2) {
                    val db=InkDb(migration)
                    check(db.getBook(7)!!.title=="MIGRATION_SENTINEL")
                    check(db.chapterOf(7,11)!!.volume=="")
                    check(db.groups().isEmpty())
                    check(db.readableDatabase.version==4)
                    db.close()
                }
                JSONObject().put("oldVersion",1).put("newVersion",4).put("reopens",2)
            }
            test("concurrent TXT exports keep independent share files and exclude private annotations") {
                val title="AUDIT_EXPORT_${UUID.randomUUID()}"
                val id=repo.createManualBook(title,"")
                val first=repo.chapters(id).single()
                repo.renameChapter(first.id,"第一章 导出")
                repo.saveChapterText(first,"EXPORT_BODY 中文 🌸\nSecond line.")
                repo.addMark(Mark(bookId=id,chapterIndex=0,startOffset=0,endOffset=6,note="PRIVATE_NOTE_MUST_NOT_EXPORT",snippet="EXPORT"))
                val book=repo.book(id)!!
                val exportContext=object:ContextWrapper(context) { override fun getCacheDir()=runner.targetContext.cacheDir }
                val exports=coroutineScope { (1..2).map { async { BookExporter.export(exportContext,book,repo.chapters(id),true,repo::chapterText) } }.awaitAll() }
                try {
                    check(exports.map { it.shareUri }.toSet().size==2)
                    for (result in exports) {
                        val bytes=context.contentResolver.openInputStream(result.shareUri)!!.use { it.readBytes() }
                        check(bytes.take(3)==listOf(0xEF.toByte(),0xBB.toByte(),0xBF.toByte()))
                        val body=bytes.toString(Charsets.UTF_8)
                        check(body.contains("EXPORT_BODY 中文 🌸\r\nSecond line.") && !body.contains("PRIVATE_NOTE_MUST_NOT_EXPORT"))
                        check(!body.replace("\r\n","").contains('\n'))
                    }
                    JSONObject().put("exports",2).put("uniqueShareUris",2)
                } finally {
                    exports.forEach { result ->
                        // Both URI paths and names belong to this randomly named audit export only.
                        val token=result.shareUri.pathSegments.dropLast(1).lastOrNull()
                        if (token!=null && token.matches(Regex("[a-f0-9-]{36}"))) File(File(runner.targetContext.cacheDir,"exports"),token).deleteRecursively()
                    }
                    val downloads=android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    context.contentResolver.query(downloads,arrayOf("_id"),"_display_name LIKE ?",arrayOf("$title%"),null)?.use { cursor ->
                        while(cursor.moveToNext()) context.contentResolver.delete(android.content.ContentUris.withAppendedId(downloads,cursor.getLong(0)),null,null)
                    }
                }
            }
            test("twelve reader sessions with rapid chapter requests always settle on latest chapter") {
                val id=repo.createManualBook("reader-pressure","")
                repeat(19) { repo.addEmptyChapter(id,"chapter-$it") }
                repo.chapters(id).forEachIndexed { i,ch -> repo.saveChapterText(ch,"READER_${i}_END ".repeat(200)) }
                lateinit var container:AppContainer
                runner.runOnMainSync { container=AppContainer(context); container.prefs.save(ReaderPrefs(pageMode=com.inkreader.data.model.PageMode.SCROLL)) }
                try {
                    repeat(12) {
                        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
                        lateinit var reader:ReaderSession
                        try {
                            runner.runOnMainSync { reader=ReaderSession(container,id,scope); reader.open(0,0) }
                            val opened=SystemClock.elapsedRealtime()+10000
                            while(reader.chapters.isEmpty() && SystemClock.elapsedRealtime()<opened) delay(10)
                            check(reader.chapters.size==20)
                            runner.runOnMainSync { repeat(100) { n -> reader.loadChapter(n%20,0) }; reader.loadChapter(19,0) }
                            val end=SystemClock.elapsedRealtime()+10000
                            while((reader.chapterIndex!=19 || !reader.chapterText.startsWith("READER_19_END")) && SystemClock.elapsedRealtime()<end) delay(10)
                            check(reader.chapterIndex==19 && reader.chapterText.startsWith("READER_19_END")) {
                                "Latest request lost: session=$it index=${reader.chapterIndex} prefix=${reader.chapterText.take(24)}"
                            }
                        } finally { scope.cancel() }
                    }
                } finally { container.scope.cancel(); container.repo.db.close() }
                JSONObject().put("sessions",12).put("chapterRequests",1212)
            }
        } finally { repo.db.close() }
        val failed=(0 until results.length()).count { results.getJSONObject(it).getString("status")!="PASS" }
        return JSONObject().put("checks",results).put("passed",results.length()-failed).put("failed",failed)
    }
}
