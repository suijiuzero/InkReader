package com.inkreader.parse

/**
 * 文档格式。
 *
 * [label] 用于错误提示里的「（PDF 文档）」这类说明；[isSupported] 标记本版本能否解析 ——
 * 不支持的格式不是「导入失败」，而是明确的「暂不支持该文件格式」。
 */
enum class DocumentFormat(val label: String, val extensions: List<String>) {
    TXT("纯文本", listOf("txt")),
    MARKDOWN("Markdown 文档", listOf("md", "markdown")),
    DOCX("Word 文档", listOf("docx")),
    PDF("PDF 文档", listOf("pdf")),
    HTML("网页", listOf("html", "htm", "xhtml")),
    EPUB("EPUB 电子书", listOf("epub")),

    /** 老式二进制 .doc（OLE2 复合文档），本版本不支持 */
    DOC("老式 Word 文档", listOf("doc")),

    /** 普通 ZIP 压缩包，本版本不支持（与 docx / epub 同为 PK 头，靠内部条目区分） */
    ZIP("ZIP 压缩包", listOf("zip")),

    /** 其它 Office Open XML 文档，本版本不支持 */
    XLSX("Excel 表格", listOf("xlsx", "xlsm")),
    PPTX("PPT 演示文稿", listOf("pptx")),

    UNKNOWN("未知格式", emptyList());

    val isSupported: Boolean
        get() = this != DOC && this != ZIP && this != XLSX && this != PPTX && this != UNKNOWN
}

/**
 * 格式识别。
 *
 * 只看扩展名是不够的：用户会把 .docx 改名成 .txt、把 ZIP 直接拖进来、把网页存成 .txt。
 * 所以这里以**内容特征为主、扩展名为辅**：
 *  · 魔数（OLE2 / %PDF / PK）
 *  · PK 开头再看压缩包内部条目（word/document.xml → docx，META-INF/container.xml → epub）
 *  · 纯文本再嗅探 HTML / Markdown 特征
 *  · 都不像文本（含 NUL 字节）→ UNKNOWN，交给调用方给出「暂不支持」提示
 */
object FormatDetector {

    private val HTML_MARKERS = listOf(
        "<!doctype html", "<html", "<head", "<body", "<meta ", "<div", "<p>", "<span", "<title>"
    )

    fun detect(bytes: ByteArray, name: String): DocumentFormat {
        val ext = extensionOf(name)

        // 1. 魔数
        if (isOle2(bytes)) return DocumentFormat.DOC
        if (isPdf(bytes)) return DocumentFormat.PDF
        if (isZip(bytes)) {
            val inspected = inspectZip(bytes)
            if (inspected != DocumentFormat.ZIP) return inspected
            return when (ext) {
                "docx" -> DocumentFormat.DOCX
                "epub" -> DocumentFormat.EPUB
                "xlsx", "xlsm" -> DocumentFormat.XLSX
                "pptx" -> DocumentFormat.PPTX
                else -> DocumentFormat.ZIP
            }
        }

        // 2. 扩展名（明确指向某格式时优先）
        when (ext) {
            "md", "markdown" -> return DocumentFormat.MARKDOWN
            "html", "htm", "xhtml" -> return DocumentFormat.HTML
            "pdf" -> return DocumentFormat.PDF
            "epub" -> return DocumentFormat.EPUB
            "docx" -> return DocumentFormat.DOCX
        }

        // 3. 内容嗅探：只对「看起来是文本」的字节解码，避免把二进制当文本读
        if (!looksLikeText(bytes)) return DocumentFormat.UNKNOWN
        val head = CharsetDetector.decode(bytes.copyOf(minOf(bytes.size, 4096)))
        val lower = head.lowercase()
        if (HTML_MARKERS.any { lower.contains(it) }) return DocumentFormat.HTML
        if (looksLikeMarkdown(head)) return DocumentFormat.MARKDOWN
        if (ext in DocumentFormat.TXT.extensions || head.isNotBlank()) return DocumentFormat.TXT
        return DocumentFormat.UNKNOWN
    }

    /** PK 开头但扩展名不确定：读内部条目名判断到底是哪种「ZIP 家族」格式 */
    private fun inspectZip(bytes: ByteArray): DocumentFormat {
        var found = DocumentFormat.ZIP
        runCatching {
            java.util.zip.ZipInputStream(bytes.inputStream().buffered()).use { zip ->
                var guard = 0
                var inflated = 0L
                val buffer = ByteArray(8192)
                while (guard++ < 512) {
                    val entry = zip.nextEntry ?: break
                    val n = entry.name.lowercase()
                    when {
                        n == "meta-inf/container.xml" -> found = DocumentFormat.EPUB
                        n == "word/document.xml" || n == "word/document2.xml" -> found = DocumentFormat.DOCX
                        n == "xl/workbook.xml" -> found = DocumentFormat.XLSX
                        n == "ppt/presentation.xml" -> found = DocumentFormat.PPTX
                    }
                    if (found != DocumentFormat.ZIP) break
                    // closeEntry/nextEntry also inflate skipped data: drain it under a shared budget first.
                    while (inflated <= 2L * 1024 * 1024) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        inflated += count
                    }
                    if (inflated > 2L * 1024 * 1024) break
                    zip.closeEntry()
                }
            }
        }
        return found
    }

    /** 文本流里不该出现 NUL；出现基本可以断定是二进制（图片、音频、未知容器…） */
    private fun looksLikeText(bytes: ByteArray): Boolean {
        val charset = CharsetDetector.detect(bytes).charset
        if (charset == Charsets.UTF_16LE || charset == Charsets.UTF_16BE) return true
        val n = minOf(bytes.size, 4096)
        for (i in 0 until n) if (bytes[i] == 0.toByte()) return false
        return bytes.isNotEmpty()
    }

    /**
     * Markdown 特征：至少两行以 # 开头的标题，或者出现 ``` 代码块围栏。
     * 阈值定得保守一些，免得把普通 TXT 误判成 Markdown（那会让正文里多出莫名其妙的标题）。
     */
    private fun looksLikeMarkdown(text: String): Boolean {
        var headings = 0
        var fences = 0
        for (line in text.lineSequence()) {
            val t = line.trimStart()
            if (Regex("^#{1,6}\\s+\\S").containsMatchIn(t)) headings++
            if (t.startsWith("```") || t.startsWith("~~~")) fences++
            if (headings >= 2 || fences >= 2) return true
        }
        return false
    }

    fun extensionOf(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "").lowercase()

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            ((bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()) ||
                (bytes[2] == 0x05.toByte() && bytes[3] == 0x06.toByte()) ||
                (bytes[2] == 0x07.toByte() && bytes[3] == 0x08.toByte()))

    fun isOle2(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() && bytes[3] == 0xE0.toByte() &&
            bytes[4] == 0xA1.toByte() && bytes[5] == 0xB1.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0xE1.toByte()

    fun isPdf(bytes: ByteArray): Boolean =
        bytes.size >= 5 &&
            bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte() && bytes[4] == 0x2D.toByte()
}
