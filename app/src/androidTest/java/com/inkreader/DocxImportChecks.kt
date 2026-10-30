package com.inkreader

import android.content.Context
import android.net.Uri
import com.inkreader.data.model.*
import com.inkreader.data.repo.BookRepository
import com.inkreader.parse.DocxParser
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DocxImportChecks {
    suspend fun run(context: Context): JSONObject {
        val repo = BookRepository(context)
        try {
            val body = (1..30).map { "普通正文第${it}段，人物坐在窗前看书，随后走出房间。" } + listOf("原文里确实重复的一句话。", "原文里确实重复的一句话。")
            val file = File(context.cacheDir, "normal-web.docx")
            fun writeDoc(styleName: String, paragraphProperties: String = "") {
                ZipOutputStream(file.outputStream()).use { zip ->
                    fun entry(name: String, xml: String) { zip.putNextEntry(ZipEntry(name)); zip.write(xml.toByteArray()); zip.closeEntry() }
                    entry("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>")
                    entry("word/styles.xml", "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:style w:type=\"paragraph\" w:styleId=\"1\"><w:name w:val=\"Normal\"/></w:style><w:style w:type=\"paragraph\" w:styleId=\"2\"><w:name w:val=\"$styleName\"/><w:basedOn w:val=\"1\"/></w:style></w:styles>")
                    entry("word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + body.joinToString("") { "<w:p><w:pPr><w:pStyle w:val=\"2\"/>$paragraphProperties</w:pPr><w:r><w:t>$it</w:t></w:r></w:p>" } + "</w:body></w:document>")
                }
            }
            for ((styleName, properties) in listOf("Normal (Web)" to "", "Heading 1" to "<w:outlineLvl w:val=\"9\"/>")) {
                writeDoc(styleName, properties)
                val parsed = file.inputStream().use { DocxParser().parse(it) }
                check(parsed.parts.size == 1 && parsed.parts.single().content == body.joinToString("\n\n"))
                for (mode in listOf(SplitMode.SMART, SplitMode.PER_FILE)) {
                    val preview = repo.previewImport(listOf(Uri.fromFile(file)), ImportMode.SEPARATE, SplitOptions(mode = mode))
                    check(preview.estimatedChapters == 1)
                    val result = repo.importResumable(listOf(Uri.fromFile(file)), ImportMode.SEPARATE, null, SplitOptions(mode = mode), ImportOrder.SELECTION) { _, _ -> }
                    check(result.failed.isEmpty())
                    val chapters = repo.chapters(result.bookIds.single())
                    check(chapters.size == 1 && repo.chapterText(chapters.single()) == body.joinToString("\n\n"))
                    repo.deleteBook(result.bookIds.single())
                }
            }
            // Explicit heading definitions keep working even with numeric style IDs.
            writeDoc("Heading 2")
            check(file.inputStream().use { DocxParser().parse(it) }.parts.size == body.size)
            return JSONObject().put("passed", true).put("modes", 4).put("bodyParagraphsCompared", body.size * 4)
                .put("numericNamedHeadingPreserved", true)
        } finally { repo.db.close() }
    }
}
