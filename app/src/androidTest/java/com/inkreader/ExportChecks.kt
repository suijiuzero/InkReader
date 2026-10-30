package com.inkreader

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.inkreader.data.export.*
import com.inkreader.data.model.*
import com.inkreader.data.repo.BookRepository
import com.inkreader.parse.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

object ExportChecks {
    suspend fun run(runner: android.app.Instrumentation, isolated: Context, stress: Boolean): JSONObject {
        val context = runner.targetContext
        DocumentParsers.init(context)
        val repo = BookRepository(isolated)
        val passed = ArrayList<String>()
        val retained = File(context.cacheDir, "export-qa").apply { mkdirs() }
        val sessionDirs = ArrayList<File>()
        fun output(result: ExportResult): File {
            val file = File(context.cacheDir, "exports/${result.shareUri.pathSegments.dropLast(1).last()}/${result.fileName}")
            check(file.isFile) { "Export output missing" }; sessionDirs += file.parentFile!!
            check(result.shareIntent.type == ExportFormat.entries.first { result.fileName.endsWith("." + it.extension) }.mime)
            check(!result.downloadOk && result.downloadPath == null)
            return file
        }
        try {
            if (stress) {
                val chapters = (1..1320).map { Chapter(it.toLong(), 1, it - 1, "Chapter $it", "") }
                val body = "The explorer crossed the silent valley and found a new path. ".repeat(170)
                val stats = JSONObject()
                for (format in ExportFormat.entries) {
                    val start = System.nanoTime()
                    val result = BookExporter.export(context, Book(title = "Large Export QA"), chapters, true,
                        { body }, format = format, saveToDownloads = false)
                    val file = output(result)
                    if (format == ExportFormat.PDF) {
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { check(it.pageCount >= 1320) } }
                    }
                    stats.put(format.name, JSONObject().put("bytes", file.length()).put("ms", (System.nanoTime() - start) / 1000000))
                    file.parentFile!!.deleteRecursively()
                    passed += "1320-chapter ${format.name}"
                }
                return JSONObject().put("passed", passed.size).put("checks", passed).put("large", stats)
            }
            val source = DocumentImageChecks.run(runner, isolated, repo)
            val id = source.docxBook
            val selected = repo.chapters(id)
            val snapshot = repo.createExportSnapshot(id, selected.map { it.id })
            try {
                val before = snapshot.text(snapshot.chapters.first())
                repo.saveChapterText(selected.first(), "Changed after snapshot")
                check(snapshot.text(snapshot.chapters.first()) == before)
                passed += "coherent immutable snapshot"
                for (format in ExportFormat.entries) {
                    val result = BookExporter.export(context, snapshot.book.copy(title = "Export QA & 中文 日本語"), snapshot.chapters,
                        true, { snapshot.text(it) }, format, { snapshot.rich(it) }, snapshot::image, false)
                    val file = output(result)
                    file.copyTo(File(retained, "sample.${format.extension}"), overwrite = true)
                    when (format) {
                        ExportFormat.TXT -> {
                            val bytes = file.readBytes(); check(bytes.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
                            val text = bytes.toString(Charsets.UTF_8); check(text.contains("BEFORE_DOCX") && text.contains("AFTER_DOCX") && text.contains("〔图片〕"))
                        }
                        ExportFormat.DOCX, ExportFormat.EPUB -> {
                            ZipFile(file).use { zip ->
                                val names = zip.entries().asSequence().map { it.name }.toList()
                                for (name in names.filter { it.endsWith(".xml") || it.endsWith(".rels") || it.endsWith(".xhtml") || it.endsWith(".opf") }) {
                                    zip.getInputStream(zip.getEntry(name)).use { input -> DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(input) }
                                }
                                if (format == ExportFormat.EPUB) { check(names.first() == "mimetype"); check(zip.getEntry("mimetype").method == 0); check(names.count { it.startsWith("OEBPS/media/") } == 1) }
                                else check(names.count { it.startsWith("word/media/") } == 1)
                            }
                            val doc = if (format == ExportFormat.EPUB) EpubParser().parse(file.readBytes(), file.name)
                                else DocxDocumentParser().parse(file.readBytes(), file.name)
                            check(doc.parts.size == snapshot.chapters.size) { "Export roundtrip added chapters" }
                            val bodies = doc.parts.joinToString("\n") { it.content }
                            check(bodies.indexOf("BEFORE_DOCX") < bodies.indexOf("[[ink-image:"))
                            check(bodies.indexOf("[[ink-image:") < bodies.indexOf("AFTER_DOCX"))
                            check(doc.images.size == 1)
                        }
                        ExportFormat.PDF -> {
                            PDDocument.load(file).use { pdf ->
                                val text = PDFTextStripper().getText(pdf)
                                check(text.contains("BEFORE_DOCX") && text.contains("AFTER_DOCX"))
                                val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
                                check(normalized.filterNot(Char::isWhitespace).contains("第一章图文")) { "CJK text not extractable" }
                                val original = snapshot.chapters.joinToString("") { it.title + snapshot.text(it) }
                                val expectedLetters = java.text.Normalizer.normalize(original, java.text.Normalizer.Form.NFKC).filter(Char::isLetter)
                                check(normalized.filter(Char::isLetter) == expectedLetters) { "PDF pagination dropped or repeated text" }
                            }
                            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { pdf ->
                                check(pdf.pageCount > 0); pdf.openPage(0).use { page ->
                                    val bitmap = android.graphics.Bitmap.createBitmap(page.width, page.height, android.graphics.Bitmap.Config.ARGB_8888)
                                    try { page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        var colors = 0
                                        for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
                                            val c = bitmap.getPixel(x, y); if (kotlin.math.abs(android.graphics.Color.red(c) - android.graphics.Color.green(c)) > 30) colors++
                                        }
                                        check(colors > 100) { "PDF image not rendered" }
                                    } finally { bitmap.recycle() }
                                }
                            } }
                        }
                    }
                    passed += "${format.name} structure/content/media/share"
                }
                val rotatedBook = repo.books(ShelfSort.ADDED).first { it.title == "Image Rotated" }
                val rotatedSnapshot = repo.createExportSnapshot(rotatedBook.id, repo.chapters(rotatedBook.id).map { it.id })
                try {
                    for (format in listOf(ExportFormat.EPUB, ExportFormat.DOCX)) {
                        val result = BookExporter.export(context, rotatedSnapshot.book, rotatedSnapshot.chapters, true,
                            { rotatedSnapshot.text(it) }, format, { rotatedSnapshot.rich(it) }, rotatedSnapshot::image, false)
                        ZipFile(output(result)).use { zip ->
                            val entry = zip.entries().asSequence().single { it.name.contains("/media/") }
                            check(entry.name.endsWith(".png"))
                            val bitmap = zip.getInputStream(entry).use { android.graphics.BitmapFactory.decodeStream(it) }!!
                            try { check(bitmap.width == 180 && bitmap.height == 320)
                                val pixel = bitmap.getPixel(10, 10)
                                check(android.graphics.Color.green(pixel) > android.graphics.Color.red(pixel))
                            } finally { bitmap.recycle() }
                        }
                        passed += "${format.name} bakes JPEG EXIF orientation"
                    }
                } finally { rotatedSnapshot.close() }
                val one = BookExporter.export(context, snapshot.book, snapshot.chapters.take(1), false, { snapshot.text(it) },
                    ExportFormat.EPUB, { snapshot.rich(it) }, snapshot::image, false)
                check(output(one).name.contains("-")); passed += "single chapter naming"
                // Cancellation must remove the partial session and must never publish to Downloads.
                for (format in ExportFormat.entries) coroutineScope {
                    val beforeDirs = File(context.cacheDir, "exports").listFiles().orEmpty().map { it.name }.toSet()
                    val started = CompletableDeferred<Unit>()
                    val job = launch(Dispatchers.IO) { BookExporter.export(context, snapshot.book, snapshot.chapters, true,
                        { started.complete(Unit); delay(10000); snapshot.text(it) }, format, { snapshot.rich(it) }, snapshot::image, false) }
                    started.await(); job.cancelAndJoin()
                    check(File(context.cacheDir, "exports").listFiles().orEmpty().map { it.name }.toSet() == beforeDirs)
                    passed += "${format.name} cancellation cleanup"
                }
            } finally { snapshot.close() }
            return JSONObject().put("passed", passed.size).put("checks", passed).put("files", "cache/export-qa public synthetic only")
        } finally { repo.db.close(); sessionDirs.forEach { it.deleteRecursively() } }
    }
}
