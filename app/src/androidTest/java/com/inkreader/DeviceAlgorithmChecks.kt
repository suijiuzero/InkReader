package com.inkreader

import android.content.Context
import android.net.Uri
import com.inkreader.data.model.*
import com.inkreader.data.repo.BookRepository
import com.inkreader.parse.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real Android parser/repository checks using only the supplied disposable context. */
object DeviceAlgorithmChecks {
    suspend fun run(context: Context, repo: BookRepository): List<String> {
        val passed = ArrayList<String>()
        val body = "独立测试正文，保留全部文字。".repeat(30)
        suspend fun import(file: File): Long {
            val result = repo.importDocuments(listOf(Uri.fromFile(file)), ImportMode.SEPARATE, null) { _, _ -> }
            check(result.failed.isEmpty()) { "${file.name}: ${result.failed}" }
            return result.bookIds.single()
        }
        fun file(name: String, text: String) = File(context.cacheDir, name).apply { writeText(text) }
        fun zip(name: String, entries: Map<String, String>): File = File(context.cacheDir, name).apply {
            ZipOutputStream(outputStream()).use { zip -> entries.forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
            } }
        }
        val titles = listOf("第一章 开始", "第二章 继续", "最终章（上）", "最终章（中）", "最终章（下）", "番外一")
        val text = titles.joinToString("\n\n") { "$it\n\n$body" }
        for ((name, bytes) in listOf("chapters-utf8.txt" to text.toByteArray(),
            "chapters-gb.txt" to text.toByteArray(charset("GB18030")),
            "chapters-utf16.txt" to (byteArrayOf(-2,-1) + text.toByteArray(Charsets.UTF_16BE)))) {
            val id = import(File(context.cacheDir, name).apply { writeBytes(bytes) })
            val chapters = repo.chapters(id)
            check(chapters.map { it.title } == titles) { "$name titles: ${chapters.map { it.title }}" }
            check(chapters.all { repo.chapterText(it).contains(body) })
        }
        passed += "new final chapters and extras survive real UTF8, GB18030 and UTF16 imports"

        val files = listOf("最终章（下）", "最终章（上）", "最终章（中）").map { file("$it.txt", body) }
        val merged = repo.importDocuments(files.map { Uri.fromFile(it) }, ImportMode.MERGE, "分段排序测试",
            splitOptions = SplitOptions(mode = SplitMode.PER_FILE, cleanWatermark = false)) { _, _ -> }
        check(merged.failed.isEmpty())
        check(repo.chapters(merged.bookIds.single()).map { it.title } == listOf("最终章（上）", "最终章（中）", "最终章（下）"))
        passed += "multipart file import orders upper middle lower through repository"

        val docxXml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" +
            listOf(0 to "第一卷 春", -1 to "卷首说明", 1 to "第一章 初见", -1 to body, 1 to "第二章 重逢", -1 to body,
                0 to "第二卷 冬", -1 to "冬卷说明", 1 to "第三章 归来", -1 to body).joinToString("") { (level, value) ->
                "<w:p>" + (if (level >= 0) "<w:pPr><w:outlineLvl w:val=\"$level\"/></w:pPr>" else "") + "<w:r><w:t>$value</w:t></w:r></w:p>"
            } + "</w:body></w:document>"
        val structuredDocx = zip("structured.docx", mapOf("word/document.xml" to docxXml))
        val structuredHtml = file("structured.html", "<h1>第一卷 春</h1><p>卷首说明</p><h2>第一章 初见</h2><p>$body</p>" +
            "<h2>第二章 重逢</h2><p>$body</p><h1>第二卷 冬</h1><p>冬卷说明</p><h2>第三章 归来</h2><p>$body</p>")
        for (source in listOf(structuredDocx, structuredHtml)) {
            val id = import(source)
            val chapters = repo.chapters(id)
            check(chapters.map { it.title } == listOf("第一章 初见", "第二章 重逢", "第三章 归来")) { "${source.name}: ${chapters.map { it.title }}" }
            check(chapters.map { it.volume } == listOf("第一卷 春", "第一卷 春", "第二卷 冬"))
            check(repo.chapterText(chapters.last()).contains("冬卷说明"))
        }
        passed += "actual Word XML and HTML imports preserve structured chapters and volume introductions"

        val epub = zip("spine.epub", mapOf("META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>",
            "OEBPS/content.opf" to "<package><manifest><item id=\"a\" href=\"z.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"b\" href=\"a.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"a\"/><itemref idref=\"b\"/></spine></package>",
            "OEBPS/a.xhtml" to "<h1>第二章 重逢</h1><p>$body</p>", "OEBPS/z.xhtml" to "<h1>第一章 初见</h1><p>$body</p>"))
        check(repo.chapters(import(epub)).map { it.title } == listOf("第一章 初见", "第二章 重逢"))
        val mislabeled = File(context.cacheDir, "word-renamed.epub").apply { writeBytes(structuredDocx.readBytes()) }
        check(repo.chapters(import(mislabeled)).size == 3)
        passed += "actual EPUB spine order and renamed Word container detection"

        val pdf = File(context.cacheDir, "multi-page.pdf")
        PDDocument().use { document ->
            for (number in 1..4) {
                val page = PDPage(); document.addPage(page)
                PDPageContentStream(document, page).use { output ->
                    for ((value, y) in listOf("Chapter $number" to 740f, "Paragraph alpha." to 620f, "42" to 400f, "Paragraph omega." to 250f, "$number" to 30f)) {
                        output.beginText(); output.setFont(PDType1Font.HELVETICA, 12f); output.newLineAtOffset(40f,y)
                        output.showText(value); output.endText()
                    }
                }
            }
            document.save(pdf)
        }
        val pdfId = import(pdf)
        val pdfChapters = repo.chapters(pdfId)
        val pdfBody = pdfChapters.map { it.title + "\n" + repo.chapterText(it) }.joinToString("\n")
        check((1..4).all { "Chapter $it" in pdfBody }) { "PDF lost heading: $pdfBody" }
        check(Regex("42").findAll(pdfBody).count() == 4) { "PDF lost middle numbers: $pdfBody" }
        passed += "actual four-page PDF retains chapter labels and middle-page numbers"

        val bad = file("corrupt.docx", "not an office archive")
        val badResult = repo.importDocuments(listOf(Uri.fromFile(bad)), ImportMode.SEPARATE, null) { _, _ -> }
        check(badResult.failed.size == 1 && badResult.bookIds.isEmpty())
        passed += "corrupt document rejected without creating a partial book"
        return passed
    }
}
