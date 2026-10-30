package com.inkreader.data.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.inkreader.data.model.Book
import com.inkreader.data.model.Chapter
import com.inkreader.parse.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real reading files, independent from the complete library backup format. */
internal object ReadingFormats {
    internal fun xml(text: String): String = buildString(text.length) {
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i); i += Character.charCount(cp)
            when (cp) {
                38 -> append("&amp;"); 60 -> append("&lt;"); 62 -> append("&gt;")
                34 -> append("&quot;"); 39 -> append("&apos;")
                else -> if (cp == 9 || cp == 10 || cp == 13 || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF) appendCodePoint(cp) else append('\uFFFD')
            }
        }
    }
    private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
    private data class Piece(val text: String = "", val image: ImageReference? = null)
    private fun pieces(text: String, rich: RichChapter?): Sequence<Piece> = sequence {
        var at = 0
        if (rich?.len == text.length) for (block in rich.blocks.filter { it.kind == RichKind.IMAGE }.sortedBy { it.start }) {
            val ref = DocumentImages.reference(block.marker) ?: continue
            if (block.start < at || block.end > text.length || text.substring(block.start, block.end) != "\uFFFC") continue
            if (block.start > at) yield(Piece(text.substring(at, block.start)))
            yield(Piece(image = ref)); at = block.end
        }
        if (at < text.length) yield(Piece(text.substring(at)))
    }
    private data class Media(val key: String, val ext: String, val mime: String, val file: File, val width: Int, val height: Int)
    /** Portable media; EXIF transforms are baked once, sources are never modified. */
    private class MediaPool(private val dir: File, private val source: (String) -> File) {
        val items = LinkedHashMap<String, Media>()
        fun get(ref: ImageReference): Media = items.getOrPut(ref.key) {
            val input = source(ref.key)
            check(input.isFile && input.length() <= 16L * 1024 * 1024) { "Missing or oversized illustration" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(input.path, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unreadable illustration" }
            val orientation = if (bounds.outMimeType == "image/jpeg") input.inputStream().use(DocumentImageOrientation::read) else 1
            val direct = orientation == 1 && bounds.outMimeType in setOf("image/png", "image/jpeg", "image/gif")
            if (direct) Media(ref.key, when (bounds.outMimeType) { "image/jpeg" -> "jpg"; "image/gif" -> "gif"; else -> "png" }, bounds.outMimeType!!, input, ref.width, ref.height)
            else {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (bounds.outWidth / inSampleSize > 4096 || bounds.outHeight / inSampleSize > 4096 || bounds.outWidth.toLong() * bounds.outHeight / inSampleSize / inSampleSize > 8L * 1024 * 1024) inSampleSize *= 2
                }
                val original = BitmapFactory.decodeFile(input.path, opts) ?: error("Unreadable illustration")
                val bitmap = if (orientation in 2..8) Bitmap.createBitmap(original, 0, 0, original.width, original.height, DocumentImageOrientation.matrix(orientation), true) else original
                val file = File(dir, "${ref.key}.png")
                try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { if (bitmap !== original) bitmap.recycle(); original.recycle() }
                check(file.length() <= 16L * 1024 * 1024) { "Illustration too large to export" }
                Media(ref.key, "png", "image/png", file, ref.width, ref.height)
            }
        }
    }
    private fun ZipOutputStream.entry(path: String, content: String) {
        putNextEntry(ZipEntry(path)); write(content.toByteArray(Charsets.UTF_8)); closeEntry()
    }
    private fun ZipOutputStream.media(path: String, file: File, check: () -> Unit) {
        putNextEntry(ZipEntry(path)); file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { check(); val n = input.read(buffer); if (n < 0) break; write(buffer, 0, n) }
        }; closeEntry()
    }
    suspend fun write(format: ExportFormat, target: File, book: Book, chapters: List<Chapter>,
        text: suspend (Chapter) -> String, rich: suspend (Chapter) -> RichChapter?, image: (String) -> File,
        progress: (Float) -> Unit) {
        val job = currentCoroutineContext()
        val check = { job.ensureActive() }
        val work = File(target.parentFile, "media-work").apply { mkdirs() }
        val media = MediaPool(work, image)
        try {
            when (format) {
                ExportFormat.EPUB -> epub(target, book, chapters, text, rich, media, check, progress)
                ExportFormat.DOCX -> docx(target, book, chapters, text, rich, media, check, progress)
                ExportFormat.PDF -> pdf(target, chapters, text, rich, media, check, progress)
                else -> error("Unsupported writer")
            }
        } finally { work.deleteRecursively() }
    }
    private suspend fun epub(target: File, book: Book, chapters: List<Chapter>, text: suspend (Chapter) -> String,
        rich: suspend (Chapter) -> RichChapter?, media: MediaPool, check: () -> Unit, progress: (Float) -> Unit) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            val mime = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = size; crc = CRC32().apply { update(mime) }.value })
            zip.write(mime); zip.closeEntry()
            zip.entry("META-INF/container.xml", XML + """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            for ((i, ch) in chapters.withIndex()) {
                check()
                zip.putNextEntry(ZipEntry("OEBPS/ch$i.xhtml"))
                fun out(s: String) { zip.write(s.toByteArray()) }
                out(XML + """<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="und"><head><title>${xml(ch.title)}</title><style>body{line-height:1.7}p{white-space:pre-wrap}img{max-width:100%;height:auto}h1{font-size:1.5em}</style></head><body><h1>${xml(ch.title)}</h1>""")
                for (piece in pieces(text(ch), rich(ch))) {
                    check()
                    if (piece.image != null) {
                        val item = media.get(piece.image)
                        out("<p><img src=\"media/${item.key}.${item.ext}\" alt=\"Illustration\"/></p>")
                    } else for (paragraph in piece.text.splitToSequence("\n\n")) {
                        check(); if (paragraph.isNotBlank()) out("<p>${xml(paragraph)}</p>")
                    }
                }
                out("</body></html>"); zip.closeEntry(); progress((i + 1f) / (chapters.size + 1))
            }
            val nav = chapters.mapIndexed { i, ch -> "<li><a href=\"ch$i.xhtml\">${xml(ch.title)}</a></li>" }.joinToString("")
            zip.entry("OEBPS/nav.xhtml", XML + """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="und"><head><title>${xml(book.title)}</title></head><body><nav epub:type="toc" id="toc"><h1>${xml(book.title)}</h1><ol>$nav</ol></nav></body></html>""")
            val manifest = chapters.indices.joinToString("") { "<item id=\"c$it\" href=\"ch$it.xhtml\" media-type=\"application/xhtml+xml\"/>" } + media.items.values.joinToString("") { "<item id=\"i${it.key}\" href=\"media/${it.key}.${it.ext}\" media-type=\"${it.mime}\"/>" }
            val spine = chapters.indices.joinToString("") { "<itemref idref=\"c$it\"/>" }
            val modified = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
            zip.entry("OEBPS/package.opf", XML + """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:uuid:${UUID.randomUUID()}</dc:identifier><dc:title>${xml(book.title)}</dc:title><dc:creator>${xml(book.author.ifBlank { "Unknown" })}</dc:creator><dc:language>und</dc:language><meta property="dcterms:modified">$modified</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$manifest</manifest><spine>$spine</spine></package>""")
            for (item in media.items.values) zip.media("OEBPS/media/${item.key}.${item.ext}", item.file, check)
        }
    }
    private suspend fun docx(target: File, book: Book, chapters: List<Chapter>, text: suspend (Chapter) -> String,
        rich: suspend (Chapter) -> RichChapter?, media: MediaPool, check: () -> Unit, progress: (Float) -> Unit) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.entry("_rels/.rels", XML + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="doc" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="core" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>""")
            zip.entry("docProps/core.xml", XML + """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${xml(book.title)}</dc:title><dc:creator>${xml(book.author)}</dc:creator></cp:coreProperties>""")
            zip.entry("word/styles.xml", XML + """<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:pPr><w:spacing w:after="160"/></w:pPr></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style></w:styles>""")
            zip.putNextEntry(ZipEntry("word/document.xml"))
            fun out(s: String) { zip.write(s.toByteArray()) }
            fun paragraph(value: String, heading: Boolean = false, pageBreak: Boolean = false) {
                out("<w:p>" + if (heading) "<w:pPr><w:pStyle w:val=\"Heading1\"/>${if (pageBreak) "<w:pageBreakBefore/>" else ""}</w:pPr>" else "")
                out("<w:r>")
                for ((i, line) in value.splitToSequence('\n').withIndex()) { check(); if (i > 0) out("<w:br/>"); out("<w:t xml:space=\"preserve\">${xml(line)}</w:t>") }
                out("</w:r></w:p>")
            }
            out(XML + """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>""")
            var imageId = 0
            for ((i, ch) in chapters.withIndex()) {
                check(); paragraph(ch.title, true, i > 0)
                for (piece in pieces(text(ch), rich(ch))) {
                    check()
                    if (piece.image == null) for (p in piece.text.splitToSequence("\n\n")) { if (p.isNotBlank()) paragraph(p) }
                    else {
                        val item = media.get(piece.image); imageId++
                        val scale = minOf(5486400.0 / item.width, 8229600.0 / item.height, 9525.0)
                        val cx = (item.width * scale).toLong().coerceAtLeast(1); val cy = (item.height * scale).toLong().coerceAtLeast(1)
                        out("""<w:p><w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="$cx" cy="$cy"/><wp:docPr id="$imageId" name="Illustration $imageId"/><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="$imageId" name="Illustration $imageId"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="i${item.key}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>""")
                    }
                }
                progress((i + 1f) / (chapters.size + 1))
            }
            out("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/></w:sectPr></w:body></w:document>"); zip.closeEntry()
            zip.entry("word/_rels/document.xml.rels", XML + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="styles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" + media.items.values.joinToString("") { "<Relationship Id=\"i${it.key}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/${it.key}.${it.ext}\"/>" } + "</Relationships>")
            zip.entry("[Content_Types].xml", XML + """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""" + media.items.values.distinctBy { it.ext }.joinToString("") { "<Default Extension=\"${it.ext}\" ContentType=\"${it.mime}\"/>" } + "</Types>")
            for (item in media.items.values) zip.media("word/media/${item.key}.${item.ext}", item.file, check)
        }
    }
    private suspend fun pdf(target: File, chapters: List<Chapter>, text: suspend (Chapter) -> String,
        rich: suspend (Chapter) -> RichChapter?, media: MediaPool, check: () -> Unit, progress: (Float) -> Unit) {
        val document = PdfDocument()
        var page: PdfDocument.Page? = null
        var y = 46f; var pageNumber = 0
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 12f }
        val heading = TextPaint(body).apply { textSize = 19f; isFakeBoldText = true }
        val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = 9f }
        fun finishPage() { page?.let { it.canvas.drawText(pageNumber.toString(), 292f, 816f, footer); document.finishPage(it) }; page = null }
        fun newPage() { check(); finishPage(); pageNumber++; page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()); y = 46f }
        fun drawText(value: String, paint: TextPaint) {
            var offset = 0
            while (offset < value.length) {
                check()
                var end = minOf(offset + 8192, value.length)
                if (end < value.length && Character.isHighSurrogate(value[end - 1])) end--
                val chunk = value.substring(offset, end)
                val layout = StaticLayout.Builder.obtain(chunk, 0, chunk.length, paint, 503).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false).setLineSpacing(3f, 1f).build()
                var line = 0
                while (line < layout.lineCount) {
                    check(); if (page == null || y + layout.getLineBottom(line) - layout.getLineTop(line) > 790f) newPage()
                    val top = layout.getLineTop(line); var last = line
                    while (last + 1 < layout.lineCount && y + layout.getLineBottom(last + 1) - top <= 790f) last++
                    val height = layout.getLineBottom(last) - top
                    val canvas = page!!.canvas
                    canvas.save(); canvas.clipRect(46f, y, 549f, y + height); canvas.translate(46f, y - top); layout.draw(canvas); canvas.restore()
                    y += height; line = last + 1
                    if (line < layout.lineCount) newPage()
                }
                offset = end
            }
            y += 8f
        }
        try {
            for ((i, ch) in chapters.withIndex()) {
                newPage(); drawText(ch.title, heading)
                for (piece in pieces(text(ch), rich(ch))) {
                    check()
                    if (piece.image == null) drawText(piece.text.trim('\n'), body)
                    else {
                        val item = media.get(piece.image)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(item.file.path, bounds)
                        val options = BitmapFactory.Options().apply { inSampleSize = 1; while (bounds.outWidth / inSampleSize > 2048 || bounds.outHeight / inSampleSize > 2048) inSampleSize *= 2 }
                        val bitmap = BitmapFactory.decodeFile(item.file.path, options) ?: error("Unreadable illustration")
                        try {
                            val scale = minOf(503f / item.width, 700f / item.height, 1f)
                            val w = item.width * scale; val h = item.height * scale
                            if (y + h > 790f) newPage()
                            page!!.canvas.drawBitmap(bitmap, null, RectF(46f + (503f - w) / 2, y, 46f + (503f + w) / 2, y + h), null); y += h + 10f
                        } finally { bitmap.recycle() }
                    }
                }
                progress((i + 1f) / (chapters.size + 1))
            }
            finishPage(); check()
            target.outputStream().buffered().use { raw -> document.writeTo(object : java.io.FilterOutputStream(raw) {
                override fun write(b: ByteArray, off: Int, len: Int) { check(); out.write(b, off, len) }
                override fun write(b: Int) { check(); out.write(b) }
            }) }
        } finally { finishPage(); document.close() }
    }
}
