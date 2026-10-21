package com.inkreader

import android.app.Instrumentation
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.net.Uri
import com.inkreader.data.repo.BookRepository
import com.inkreader.data.model.*
import com.inkreader.parse.RichChapter
import com.inkreader.parse.RichKind
import com.inkreader.ui.reader.*
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.runBlocking
import java.io.File
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.database.DatabaseErrorHandler
import java.util.UUID
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.cancel
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import com.inkreader.ui.editor.EditorScreen
import com.inkreader.ui.theme.InkTheme
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import android.os.ParcelFileDescriptor
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import com.inkreader.parse.DocumentParsers
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font

/** 使用测试 APK 的独立存储，故障注入不会碰用户书架。无需额外测试框架。 */
class AuditInstrumentation : Instrumentation() {
    private var auditArguments: Bundle? = null
    override fun onCreate(arguments: Bundle?) { auditArguments = arguments; super.onCreate(arguments); start() }

    override fun onStart() {
        if (auditArguments?.getString("voiceService") == "true") {
            ComputerVoiceServiceChecks.run(this)
            return
        }
        if (auditArguments?.getString("voiceBridge") == "true") {
            ComputerVoiceChecks.run(this, auditArguments ?: Bundle())
            return
        }
        val results = Bundle()
        val passed = ArrayList<String>()
        val root = File(targetContext.cacheDir, "audit-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String): File = File(root, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        }
        try {
            runBlocking {
                val repo = BookRepository(isolated)
                val book = repo.createManualBook("test-delete", "")
                repo.addEmptyChapter(book, "second")
                repo.addEmptyChapter(book, "third")
                (0..2).forEach { repo.addMark(Mark(bookId = book, chapterIndex = it, startOffset = 0, endOffset = 0)) }
                repo.deleteChapter(book, repo.chapters(book).first().id)
                check(repo.marks(book).map { it.chapterIndex } == listOf(0, 1))
                passed += "delete preserves next chapter annotations"

                val splitBook = repo.createManualBook("test-split", "")
                var chapter = repo.chapters(splitBook).single()
                val body = "前半正文。\n\n后半正文。"
                repo.saveChapterText(chapter, body)
                chapter = repo.chapters(splitBook).single()
                val originalRich = "ikr1 ${body.length}\nK 0 0 0 ${body.length} \n"
                repo.files.writeRich(chapter.fileName, originalRich)
                repo.db.writableDatabase.execSQL("CREATE TRIGGER reject_split BEFORE INSERT ON chapters BEGIN SELECT RAISE(ABORT, 'injected split failure'); END")
                try { repo.splitChapterAt(splitBook, chapter.id, 7, "second"); error("split must fail") }
                catch (_: IllegalStateException) { }
                repo.db.writableDatabase.execSQL("DROP TRIGGER reject_split")
                check(repo.chapters(splitBook).size == 1)
                check(repo.chapterText(chapter) == body)
                check(repo.files.readRich(chapter.fileName) == originalRich)
                passed += "split rollback preserves body and rich sidecar"

                val newId = repo.splitChapterAt(splitBook, chapter.id, 7, "second")
                check(newId != null)
                chapter = repo.chapters(splitBook).first()
                check(repo.chapterText(chapter) == body.substring(0, 7).trimEnd())
                check(repo.mergeChapterWithPrev(splitBook, newId!!))
                check(repo.chapters(splitBook).size == 1)
                passed += "split and merge commit valid files"

                val missingBook = repo.createManualBook("test-read-failure", "")
                val missingId = repo.addEmptyChapter(missingBook, "second")
                val missingChapter = repo.chapters(missingBook).last()
                repo.files.file(missingChapter.fileName).delete()
                check(runCatching { repo.mergeChapterWithPrev(missingBook, missingId) }.isFailure)
                check(repo.chapters(missingBook).size == 2)
                passed += "missing source aborts destructive operation"

                val utf16 = File(isolated.cacheDir, "utf16.txt").apply {
                    writeBytes(byteArrayOf(-1, -2) + "第一章 开始\n\n正文内容。".toByteArray(Charsets.UTF_16LE))
                }
                val imported = repo.importDocuments(listOf(Uri.fromFile(utf16)), ImportMode.SEPARATE, null) { _, _ -> }
                check(imported.failed.isEmpty() && imported.bookIds.size == 1)
                passed += "UTF16 actual import succeeds"

                val beforeBooks = repo.books(ShelfSort.RECENT).size
                val beforeFiles = File(isolated.filesDir, "chapters").listFiles()!!.map { it.name }.toSet()
                repo.db.writableDatabase.execSQL("CREATE TRIGGER reject_import BEFORE INSERT ON chapters WHEN NEW.idx=1 BEGIN SELECT RAISE(ABORT, 'injected import failure'); END")
                val source = File(isolated.cacheDir, "rollback.txt").apply {
                    writeText("第一章 开始\n\n" + "甲正文。".repeat(50) + "\n\n第二章 继续\n\n" + "乙正文。".repeat(50))
                }
                val failed = repo.importDocuments(listOf(Uri.fromFile(source)), ImportMode.SEPARATE, null) { _, _ -> }
                repo.db.writableDatabase.execSQL("DROP TRIGGER reject_import")
                check(failed.failed.size == 1 && failed.bookIds.isEmpty())
                check(repo.books(ShelfSort.RECENT).size == beforeBooks)
                check(File(isolated.filesDir, "chapters").listFiles()!!.map { it.name }.toSet() == beforeFiles)
                passed += "import rollback removes staged files and rows"

                val appendBook = repo.createManualBook("test-append", "")
                val beforeAppendFiles = File(isolated.filesDir, "chapters").listFiles()!!.map { it.name }.toSet()
                repo.db.writableDatabase.execSQL("CREATE TRIGGER reject_append BEFORE INSERT ON chapters WHEN NEW.book_id=$appendBook AND NEW.idx=2 BEGIN SELECT RAISE(ABORT, 'injected append failure'); END")
                val appendErrors = repo.appendFilesAsChapters(appendBook, listOf(Uri.fromFile(source)))
                repo.db.writableDatabase.execSQL("DROP TRIGGER reject_append")
                check(appendErrors.size == 1 && repo.chapters(appendBook).size == 1)
                check(repo.book(appendBook)!!.totalChapters == 1)
                check(File(isolated.filesDir, "chapters").listFiles()!!.map { it.name }.toSet() == beforeAppendFiles)
                val bad = File(isolated.cacheDir, "bad.txt").apply { writeText(source.readText()) }
                val good1 = File(isolated.cacheDir, "good-first.txt").apply { writeText("完整的第一份正文。".repeat(20)) }
                val good2 = File(isolated.cacheDir, "good-last.txt").apply { writeText("完整的最后一份正文。".repeat(20)) }
                repo.db.writableDatabase.execSQL("CREATE TRIGGER reject_merge BEFORE INSERT ON chapters WHEN NEW.source='bad.txt' AND NEW.idx=1 BEGIN SELECT RAISE(ABORT, 'injected merge failure'); END")
                val merged = repo.importDocuments(listOf(bad, good1, good2).map { Uri.fromFile(it) }, ImportMode.MERGE, "test-merge") { _, _ -> }
                repo.db.writableDatabase.execSQL("DROP TRIGGER reject_merge")
                check(merged.failed.size == 1 && merged.succeeded.size == 2)
                val mergedId = merged.bookIds.single()
                check(repo.chapters(mergedId).size == 2 && repo.book(mergedId)!!.totalChapters == 2)
                check(repo.chapters(mergedId).none { it.sourceName == "bad.txt" })
                passed += "append and merged import rollback per-file failures"

                val md = File(isolated.cacheDir, "styles.md").apply {
                    writeText("# 第一章 开始\n\n**重复对白**\n\n```\n**literal**\n```\n\n# 第二章 继续\n\n**重复对白**\n")
                }
                val mdId = repo.importDocuments(listOf(Uri.fromFile(md)), ImportMode.SEPARATE, null) { _, _ -> }.bookIds.single()
                val mdChs = repo.chapters(mdId)
                check(mdChs.size == 2)
                repo.addMark(Mark(bookId = mdId, chapterIndex = 1, startOffset = 0, endOffset = 4, snippet = "重复对白", type = MarkType.HIGHLIGHT))
                check(repo.resplitBook(mdId, SplitOptions(cleanWatermark = false)) >= 2)
                val resplit = repo.chapters(mdId)
                val moved = repo.marks(mdId).single()
                check(moved.chapterIndex == 1 && moved.startOffset == 0)
                check(repo.chapterText(resplit[0]).contains("**literal**"))
                val rich = repo.chapterRich(resplit[0], repo.chapterText(resplit[0]).length)!!
                check(rich.spans.any { it.bold })
                check(rich.blocks.any { it.kind == RichKind.CODE })
                passed += "resplit preserves repeated-text anchor and Markdown literal/styles"
                fun zip(name: String, entries: Map<String, String>): File {
                    val file = File(isolated.cacheDir, name)
                    ZipOutputStream(file.outputStream()).use { output ->
                        entries.forEach { (path, body) ->
                            output.putNextEntry(ZipEntry(path))
                            output.write(body.toByteArray(Charsets.UTF_8))
                            output.closeEntry()
                        }
                    }
                    return file
                }
                val styleSource = File(isolated.cacheDir, "style-regression.md").apply {
                    writeText("# 第一章\n\nalpha **bold** omega\n\n```\ncode\n```\n\n# 第二章\n\nend")
                }
                val styleId = repo.importDocuments(listOf(Uri.fromFile(styleSource)), ImportMode.SEPARATE, null) { _, _ -> }.bookIds.single()
                val styleChapter = repo.chapters(styleId).first()
                val styleText = repo.chapterText(styleChapter)
                val sidecar = repo.files.readRich(styleChapter.fileName)
                check(sidecar.isNotBlank())
                repo.saveChapterText(styleChapter, styleText, expectedText = styleText)
                check(repo.chapters(styleId).first().fileName == styleChapter.fileName)
                check(repo.files.readRich(styleChapter.fileName) == sidecar)
                repo.addMark(Mark(bookId = styleId, chapterIndex = 0, startOffset = 6, endOffset = 10))
                val changed = "XX$styleText"
                repo.saveChapterText(styleChapter, changed, expectedText = styleText)
                var styled = repo.chapters(styleId).first()
                check(repo.marks(styleId).single().startOffset == 8)
                check(repo.chapterRich(styleChapter, changed.length)!!.spans.any { it.bold && it.start == 8 })
                try { repo.saveChapterText(styleChapter, "stale overwrite", expectedText = styleText); error("stale edit must be rejected") }
                catch (_: java.io.IOException) { }
                check(repo.chapterText(styled) == changed)
                val splitId = repo.splitChapterAt(styleId, styled.id, changed.indexOf("\n\n"), "code")!!
                check(repo.mergeChapterWithPrev(styleId, splitId))
                styled = repo.chapters(styleId).first()
                check(repo.chapterText(styled) == changed)
                check(repo.chapterRich(styled, changed.length)!!.spans.any { it.bold })
                check(repo.chapterRich(styled, changed.length)!!.blocks.any { it.kind == RichKind.CODE })
                passed += "unchanged editor body, edit anchors/styles, stale save rejection, styled split/merge"

                val epubContainer = "<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>"
                val epubOpf = "<package><manifest><item id=\"a\" href=\"chapter+1.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"b\" href=\"chapter2.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"a\"/><itemref idref=\"b\"/></spine></package>"
                val epubEntries = linkedMapOf("META-INF/container.xml" to epubContainer, "OEBPS/content.opf" to epubOpf,
                    "OEBPS/chapter+1.xhtml" to "<html><body><p>plus filename body</p></body></html>",
                    "OEBPS/chapter2.xhtml" to "<html><body><p>second body</p></body></html>")
                val plusEpub = zip("plus.epub", epubEntries)
                check(DocumentParsers.parse(plusEpub.readBytes(), plusEpub.name).parts.size == 2)
                val missingEpub = zip("missing.epub", epubEntries.filterKeys { it != "OEBPS/chapter2.xhtml" })
                val missingResult = repo.importDocuments(listOf(Uri.fromFile(missingEpub)), ImportMode.SEPARATE, null) { _, _ -> }
                check(missingResult.bookIds.isEmpty() && missingResult.failed.size == 1)
                passed += "EPUB plus filename and missing spine chapter refusal"
                val volumeBook = repo.createManualBook("volume-append", "")
                val firstVolume = repo.chapters(volumeBook).first()
                repo.renameChapter(firstVolume.id, "第一章")
                repo.db.updateChapterVolume(firstVolume.id, "第一卷")
                for (n in 2..5) {
                    val id = repo.addEmptyChapter(volumeBook, "第${n}章")
                    repo.db.updateChapterVolume(id, "第一卷")
                }
                for (n in 1..2) {
                    val id = repo.addEmptyChapter(volumeBook, "第${n}章")
                    repo.db.updateChapterVolume(id, "第二卷")
                }
                val volumeIds = repo.chapters(volumeBook).map { it.id }
                val thirdVolumeChapter = File(isolated.cacheDir, "第三章.txt").apply { writeText("新增正文。") }
                check(repo.appendFilesAsChapters(volumeBook, listOf(Uri.fromFile(thirdVolumeChapter)),
                    splitOptions = SplitOptions(mode = SplitMode.PER_FILE, cleanWatermark = false)).isEmpty())
                check(repo.chapters(volumeBook).take(7).map { it.id } == volumeIds)
                check(repo.chapters(volumeBook).last().title == "第三章")
                passed += "append preserves physical order across volume numbering resets"
                val docx = zip("smoke.docx", mapOf("word/document.xml" to
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>DOCX regression body.</w:t></w:r></w:p></w:body></w:document>"))
                val html = File(isolated.cacheDir, "smoke.html").apply {
                    writeText("<html><head><title>HTML regression</title></head><body><h1>Chapter 1</h1><p>HTML regression body.</p></body></html>")
                }
                val epub = zip("smoke.epub", mapOf(
                    "mimetype" to "application/epub+zip",
                    "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>",
                    "OEBPS/content.opf" to "<package><metadata><title>EPUB regression</title></metadata><manifest><item id=\"c1\" href=\"chapter.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"c1\"/></spine></package>",
                    "OEBPS/chapter.xhtml" to "<html><body><h1>Chapter 1</h1><p>EPUB regression body.</p></body></html>"
                ))
                DocumentParsers.init(targetContext)
                val pdf = File(isolated.cacheDir, "smoke.pdf")
                PDDocument().use { document ->
                    val page = PDPage()
                    document.addPage(page)
                    PDPageContentStream(document, page).use {
                        it.beginText()
                        it.setFont(PDType1Font.HELVETICA, 12f)
                        it.newLineAtOffset(20f, 700f)
                        it.showText("PDF regression body.")
                        it.endText()
                    }
                    document.save(pdf)
                }
                for (file in listOf(docx, html, epub, pdf)) {
                    val result = repo.importDocuments(listOf(Uri.fromFile(file)), ImportMode.SEPARATE, null) { _, _ -> }
                    check(result.failed.isEmpty()) { "${file.name}: ${result.failed}" }
                    val id = result.bookIds.single()
                    check(repo.chapters(id).any { repo.chapterText(it).contains("regression body.") })
                }
                passed += "DOCX, HTML, EPUB and PDF actual imports (TXT/Markdown also covered)"
                repo.db.close()
            }
            runOnMainSync {
                val sample = "甲乙\n丙丁\n\n尾段\n\n末段"
                val blocks = Paginator.toBlocks(sample)
                blocks.forEach { check(sample.substring(it.start, it.start + it.text.length) == it.text) }
                val measurer = TextMeasurer(createFontFamilyResolver(targetContext), Density(1f), LayoutDirection.Ltr)
                val style = TextStyle(fontSize = 24.sp, lineHeight = 40.sp)
                val pages = Paginator(measurer).paginate(blocks, style, 150, 1, 0f, Density(1f))
                check(pages.isNotEmpty() && pages.last().endOffset == sample.length)
                val normal = Paginator(measurer).paginate(Paginator.toBlocks("甲".repeat(30)), style, 150, 240, 0f, Density(1f))
                val inset = Paginator(measurer).paginate(Paginator.toBlocks("甲".repeat(30)), style, 150, 240, 0f, Density(1f), firstPageInsetPx = 160f)
                check(inset.size > normal.size)
            }
            passed += "original offsets, tiny-window pagination, heading inset"
            var readerContainer: AppContainer? = null
            var reader: ReaderSession? = null
            runBlocking {
                val repo = BookRepository(isolated)
                val readerBook = repo.createManualBook("test-reader", "")
                var ch = repo.chapters(readerBook).single()
                repo.saveChapterText(ch, "甲".repeat(80))
                repo.addEmptyChapter(readerBook, "second")
                ch = repo.chapters(readerBook).last()
                repo.saveChapterText(ch, "乙".repeat(80))
                repo.updateProgress(readerBook, 1, 50, 0.7f)
                runOnMainSync {
                    readerContainer = AppContainer(isolated)
                    reader = ReaderSession(readerContainer!!, readerBook, readerContainer!!.scope)
                    reader!!.open(0, 0)
                }
                repo.db.close()
            }
            try {
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (reader!!.chapterText.isEmpty() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
                runOnMainSync {
                    check(reader!!.chapterIndex == 0 && reader!!.scrollOffset == 0)
                    reader!!.setPaginationContext(TextMeasurer(createFontFamilyResolver(targetContext), Density(1f), LayoutDirection.Ltr),
                        TextStyle(fontSize = 24.sp, lineHeight = 40.sp), 150, 240, Density(1f)) {
                            TextMeasurer(createFontFamilyResolver(targetContext), Density(1f), LayoutDirection.Ltr)
                        }
                    reader!!.loadChapter(0)
                    reader!!.loadChapter(1)
                }
                val countDeadline = SystemClock.elapsedRealtime() + 5000
                while ((reader!!.bookTotalPages == 0 || reader!!.chapterIndex != 1 || reader!!.pages.isEmpty()) && SystemClock.elapsedRealtime() < countDeadline) Thread.sleep(20)
                runOnMainSync {
                    check(reader!!.chapterIndex == 1 && reader!!.chapterText.startsWith("乙"))
                    check(reader!!.bookTotalPages > 0 && reader!!.pagesBefore!!.last() == reader!!.bookTotalPages)
                    val counts = reader!!.pagesBefore!!
                    check(counts[2] - counts[1] == reader!!.pages.size)
                    reader!!.onUserPage(1)
                    reader!!.jumpToOffset(0)
                    check(reader!!.pageIndex == 0)
                }
                passed += "reader zero-offset navigation, latest chapter request, real page count"
            } finally {
                runOnMainSync { readerContainer!!.scope.cancel() }
                readerContainer!!.repo.db.close()
            }
            sendStatus(0, Bundle().apply { putString("stream", "data and reader checks passed; starting editor UI checks\n") })
            val activityRef = AtomicReference<MainActivity?>()
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) {
                    if (activity is MainActivity) activityRef.set(activity)
                }
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
            val app = targetContext.applicationContext as Application
            runOnMainSync { app.registerActivityLifecycleCallbacks(callbacks) }
            val activity = try {
                // Android 16 限制后台拉起页面；测试以 shell 身份只启动本应用，并设置等待上限。
                ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(
                    "am start --user 0 -f 0x10008000 -n ${targetContext.packageName}/.MainActivity"
                )).use { it.readBytes() }
                val activityDeadline = SystemClock.elapsedRealtime() + 5000
                while (activityRef.get() == null && SystemClock.elapsedRealtime() < activityDeadline) Thread.sleep(20)
                activityRef.get() ?: error("editor test activity did not start")
            } finally {
                runOnMainSync { app.unregisterActivityLifecycleCallbacks(callbacks) }
            }
            lateinit var editorContainer: AppContainer
            runOnMainSync { editorContainer = AppContainer(isolated) }
            try {
                val editorBook = runBlocking { editorContainer.repo.createManualBook("test-editor", "") }
                val editorChapter = runBlocking { editorContainer.repo.chapters(editorBook).single() }
                runBlocking { editorContainer.repo.saveChapterText(editorChapter, "original") }
                for (systemBack in listOf(true, false)) {
                    val returned = AtomicBoolean(false)
                    val expectedBody = runBlocking { editorContainer.repo.chapterText(editorChapter) }
                    runOnMainSync {
                        activity.setContent {
                            InkTheme(readerTheme = ReaderTheme.MIST) {
                                EditorScreen(editorContainer, editorBook, editorChapter.id, "test-editor") {
                                    activity.setContent { InkTheme(readerTheme = ReaderTheme.MIST) { Text("editor-test-complete") } }
                                    returned.set(true)
                                }
                            }
                        }
                    }
                    val deadline = SystemClock.elapsedRealtime() + 5000
                    val newText = if (systemBack) "system-back-saved-immediately" else "toolbar-back-saved-immediately"
                    var edited = false
                    while (!edited && SystemClock.elapsedRealtime() < deadline) {
                        val editable = nodes(uiAutomation.rootInActiveWindow).lastOrNull {
                            it.isEditable && it.isEnabled && it.text?.toString() == expectedBody && it.refresh()
                        }
                        edited = editable?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                        }) == true
                        if (!edited) Thread.sleep(30)
                    }
                    check(edited) { "editor body input action did not reach the current page" }
                    if (systemBack) {
                        runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
                    } else {
                        var back = nodes(uiAutomation.rootInActiveWindow).firstOrNull {
                            it.contentDescription?.toString() == targetContext.getString(R.string.action_back)
                        } ?: error("editor back button not found")
                        while (!back.isClickable && back.parent != null) back = back.parent
                        check(back.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    }
                    val saveDeadline = SystemClock.elapsedRealtime() + 5000
                    while (!returned.get() && SystemClock.elapsedRealtime() < saveDeadline) Thread.sleep(20)
                    check(returned.get()) { "editor did not complete save and return" }
                    check(runBlocking { editorContainer.repo.chapterText(editorChapter) } == newText)
                }
                passed += "editor system and toolbar return await saving actual input"
            } finally {
                runOnMainSync {
                    activity.setContent { InkRoot() }
                    editorContainer.scope.cancel()
                }
                editorContainer.repo.db.close()
            }
            results.putString("stream", "PASS ${passed.size} device checks\n" + passed.joinToString("\n"))
            root.deleteRecursively()
            finish(-1, results)
        } catch (t: Throwable) {
            results.putString("stream", "FAILED after ${passed.size} checks\n" + t.stackTraceToString())
            root.deleteRecursively()
            finish(0, results)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val result = ArrayList<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.packageName?.toString() == targetContext.packageName) result += node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it) }
        }
        visit(root)
        return result
    }
}
