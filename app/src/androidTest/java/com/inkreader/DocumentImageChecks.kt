package com.inkreader

import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import com.inkreader.data.model.*
import com.inkreader.data.repo.BookRepository
import com.inkreader.data.repo.LibraryArchive
import com.inkreader.parse.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object DocumentImageChecks {
    data class Result(val docxBook: Long, val report: JSONObject)
    suspend fun run(runner: Instrumentation, context: Context, repo: BookRepository): Result {
        DocumentParsers.init(context)
        val passed = ArrayList<String>()
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); val paint = Paint()
        for (y in 0 until 6) for (x in 0 until 10) {
            paint.color = if ((x + y) % 2 == 0) Color.rgb(225, 58, 75) else Color.rgb(35, 180, 130)
            canvas.drawRect(x * 32f, y * 30f, (x + 1) * 32f, (y + 1) * 30f, paint)
        }
        val picture = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val fixtures = File(context.cacheDir, "image-fixtures").apply { mkdirs() }
        fun zipFile(name: String, entries: Map<String, ByteArray>): File = File(fixtures, name).also { file ->
            ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() } }
        }
        fun xml(value: String) = value.toByteArray()
        val longBody = "IMAGE_BODY_SENTINEL follows the illustration and remains selectable. ".repeat(180)
        val docx = zipFile("Image DOCX.docx", linkedMapOf(
            "[Content_Types].xml" to xml("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>"),
            "word/styles.xml" to xml("<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:style w:type=\"paragraph\" w:styleId=\"2\"><w:name w:val=\"Normal (Web)\"/></w:style></w:styles>"),
            "word/_rels/document.xml.rels" to xml("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/picture.png\"/><Relationship Id=\"external\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"https://example.invalid/no-fetch.png\" TargetMode=\"External\"/></Relationships>"),
            "word/document.xml" to xml("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\"><w:body><w:p><w:pPr><w:outlineLvl w:val=\"0\"/></w:pPr><w:r><w:t>第一章 图文</w:t></w:r></w:p><w:p><w:pPr><w:pStyle w:val=\"2\"/></w:pPr><w:r><w:t>BEFORE_DOCX</w:t></w:r></w:p><w:p><mc:AlternateContent><mc:Choice Requires=\"a\"><w:r><w:drawing><a:blip r:embed=\"rId1\"/></w:drawing></w:r></mc:Choice><mc:Fallback><w:r><w:pict><a:blip r:embed=\"rId1\"/></w:pict></w:r></mc:Fallback></mc:AlternateContent></w:p><w:p><w:r><w:t>AFTER_DOCX $longBody</w:t></w:r></w:p></w:body></w:document>"),
            "word/media/picture.png" to picture))
        val epub = zipFile("Image EPUB.epub", linkedMapOf(
            "META-INF/container.xml" to xml("<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"OEBPS/book.opf\"/></rootfiles></container>"),
            "OEBPS/book.opf" to xml("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>Image EPUB</dc:title></metadata><manifest><item id=\"c\" href=\"text/chapter.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"i\" href=\"images/p.png\" media-type=\"image/png\"/></manifest><spine><itemref idref=\"c\"/></spine></package>"),
            "OEBPS/text/chapter.xhtml" to xml("<html><body><h1>第一章 图文</h1><p>BEFORE_EPUB</p><p><img src=\"../images/p.png\"/></p><p>AFTER_EPUB</p></body></html>"), "OEBPS/images/p.png" to picture))
        val html = File(fixtures, "Image HTML.html").apply { writeText("<html><head><title>Image HTML</title></head><body><h1>第一章 图文</h1><p>BEFORE_HTML</p><img src=\"data:image/png;base64,${android.util.Base64.encodeToString(picture, android.util.Base64.NO_WRAP)}\"/><p>AFTER_HTML</p><img src=\"https://example.invalid/no-fetch.png\"/></body></html>") }
        fun pdf(name: String, withText: Boolean): File = File(fixtures, name).also { file ->
            PDDocument().use { document ->
                val page = PDPage(PDRectangle(400f, 600f)); document.addPage(page)
                val image = LosslessFactory.createFromImage(document, bitmap)
                PDPageContentStream(document, page).use { stream ->
                    if (withText) { stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12f); stream.newLineAtOffset(40f, 550f); stream.showText("BEFORE_PDF"); stream.endText() }
                    stream.drawImage(image, 40f, 300f, 320f, 180f)
                    if (withText) { stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12f); stream.newLineAtOffset(40f, 200f); stream.showText("AFTER_PDF"); stream.endText() }
                }
                document.save(file)
            }
        }
        val pdfText = pdf("Image PDF.pdf", true); val scan = pdf("Image Scan.pdf", false)
        val rotatedJpeg = File(fixtures, "rotated.jpg").apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) } }
        android.media.ExifInterface(rotatedJpeg.path).apply {
            setAttribute(android.media.ExifInterface.TAG_ORIENTATION, "6"); saveAttributes()
        }
        val rotated = File(fixtures, "Image Rotated.html").apply { writeText("<html><head><title>Image Rotated</title></head><body><h1>第一章 图文</h1><p>BEFORE_ROTATED</p><img src=\"data:image/jpeg;base64,${android.util.Base64.encodeToString(rotatedJpeg.readBytes(), android.util.Base64.NO_WRAP)}\"/><p>AFTER_ROTATED</p></body></html>") }
        bitmap.recycle()
        val ids = ArrayList<Long>()
        for ((file, tag) in listOf(docx to "DOCX", epub to "EPUB", html to "HTML", pdfText to "PDF", scan to "SCAN", rotated to "ROTATED")) {
            val result = repo.importResumable(listOf(Uri.fromFile(file)), ImportMode.SEPARATE, null,
                SplitOptions(cleanWatermark = false), ImportOrder.SELECTION) { _, _ -> }
            check(result.failed.isEmpty() && result.bookIds.size == 1) { "Image import failed: $tag" }
            val id = result.bookIds.single(); ids += id
            val chapters = repo.chapters(id)
            var images = 0
            for (chapter in chapters) {
                val text = repo.chapterText(chapter); val rich = repo.chapterRich(chapter, text.length)
                for (block in rich?.blocks.orEmpty().filter { it.kind == RichKind.IMAGE }) {
                    val ref = DocumentImages.reference(block.marker) ?: error("Invalid image reference")
                    check(text.substring(block.start, block.end) == "\uFFFC")
                    val asset = repo.files.imageFile(ref.key); check(asset.isFile && DocumentImages.hash(asset.readBytes()) == ref.key)
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(asset.path, bounds)
                    if (tag == "ROTATED") check(bounds.outWidth == 320 && bounds.outHeight == 180 && ref.width == 180 && ref.height == 320)
                    else check(bounds.outWidth == ref.width && bounds.outHeight == ref.height)
                    images++
                }
                if (tag == "EPUB") check(text.lineSequence().firstOrNull()?.trim() != chapter.title)
                if (tag != "SCAN") {
                    check(text.contains("BEFORE_$tag") && text.contains("AFTER_$tag"))
                    check(text.indexOf("BEFORE_$tag") < text.indexOf('\uFFFC') && text.indexOf('\uFFFC') < text.indexOf("AFTER_$tag"))
                }
                check(!text.contains("[[ink-image:"))
            }
            check(images == 1) { "Expected exactly one embedded image: $tag/$images" }
            passed += "$tag embedded image bytes, reference, text order and chapter offsets"
            runner.sendStatus(0, android.os.Bundle().apply { putString("stream", "Image fixture $tag passed\n") })
        }
        // Single-file mode uses original DOCX paragraphs once, with one illustration.
        val single = repo.importResumable(listOf(Uri.fromFile(docx)), ImportMode.SEPARATE, "Image single",
            SplitOptions(mode = SplitMode.PER_FILE, cleanWatermark = false), ImportOrder.SELECTION) { _, _ -> }
        val singleChapter = repo.chapters(single.bookIds.single()).single(); val singleText = repo.chapterText(singleChapter)
        check(singleText.count { it == '\uFFFC' } == 1 && singleText.indexOf("第一章 图文") == singleText.lastIndexOf("第一章 图文"))
        repo.deleteBook(single.bookIds.single()); passed += "DOCX single-file mode keeps one title and one image"
        val first = repo.chapters(ids.first()).first(); val original = repo.chapterText(first)
        repo.saveChapterText(first, original.replace("BEFORE_DOCX", "EDITED_BEFORE_DOCX"))
        var current = repo.chapters(ids.first()).first(); var currentText = repo.chapterText(current)
        val split = repo.splitChapterAt(ids.first(), current.id, currentText.indexOf('\uFFFC'), "Image split") ?: error("Image split failed")
        check(repo.mergeChapterWithPrev(ids.first(), split))
        check(repo.resplitBook(ids.first(), SplitOptions(mode = SplitMode.FIXED_SIZE, fixedChunkChars = 2000, cleanWatermark = false)) > 1)
        var imageCount = 0
        for (chapter in repo.chapters(ids.first())) { val text = repo.chapterText(chapter); imageCount += repo.chapterRich(chapter, text.length)?.blocks?.count { it.kind == RichKind.IMAGE } ?: 0 }
        check(imageCount == 1); passed += "image survives editing, chapter split, merge and fixed-size resplit"
        val archive = LibraryArchive(context, repo.db, repo.files)
        val backup = ByteArrayOutputStream().also { archive.write(it) }.toByteArray()
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(backup.inputStream()).use { zip -> while (true) { val entry = zip.nextEntry ?: break; files[entry.name] = zip.readBytes(); zip.closeEntry() } }
        val manifest = JSONObject(files.getValue("library.json").toString(Charsets.UTF_8))
        check(manifest.getInt("version") == 2 && files.keys.any { it.startsWith("images/") })
        val imageKeys = files.keys.filter { it.startsWith("images/") }
        for (id in repo.books(ShelfSort.ADDED).map { it.id }) repo.deleteBook(id)
        check(File(context.filesDir, "images").listFiles().orEmpty().isEmpty())
        // Missing declared payload must not create a partially restored library.
        val missing = imageKeys.first(); val damaged = JSONObject(manifest.toString()); damaged.getJSONObject("files").remove(missing)
        val invalid = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip -> files.forEach { (name, bytes) -> if (name != missing) {
            zip.putNextEntry(ZipEntry(name)); zip.write(if (name == "library.json") damaged.toString().toByteArray() else bytes); zip.closeEntry()
        } } } }.toByteArray()
        check(runCatching { archive.restore(invalid.inputStream()) }.isFailure)
        check(repo.books(ShelfSort.ADDED).isEmpty() && File(context.filesDir, "images").listFiles().orEmpty().isEmpty())
        check(archive.restore(backup.inputStream()) == 6)
        for (chapter in repo.books(ShelfSort.ADDED).flatMap { repo.chapters(it.id) }) {
            val text = repo.chapterText(chapter)
            for (key in DocumentImages.references(repo.chapterRich(chapter, text.length))) check(repo.files.imageFile(key).isFile)
        }
        passed += "backup v2 restores shared assets; missing-image rollback and orphan collection"
        val restored = repo.books(ShelfSort.ADDED).first { it.title == "Image DOCX" }.id
        return Result(restored, JSONObject().put("passed", true).put("checks", org.json.JSONArray(passed)).put("storedAssets", imageKeys.size))
    }
}
