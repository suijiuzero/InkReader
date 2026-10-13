package com.inkreader.parse

/**
 * DOCX 解析器适配：包一层既有的 [DocxParser]。
 *
 * 解析逻辑（OOXML 段落 / 标题样式 / 表格 / 图片占位）一行没动，
 * 只是把「解析不出来」的几种情况区分开，方便给出具体提示。
 */
class DocxDocumentParser : DocumentParser {

    override val format: DocumentFormat = DocumentFormat.DOCX

    override fun parse(bytes: ByteArray, displayName: String): ParsedDoc {
        if (!FormatDetector.isZip(bytes)) {
            throw ParseException(ParseErrorCode.CORRUPTED, format, "文件不是有效的 docx 结构")
        }
        val parsed = try {
            DocxParser().parse(bytes.inputStream())
        } catch (t: Throwable) {
            throw ParseException(ParseErrorCode.CORRUPTED, format, t.message)
        }
        if (parsed.parts.isEmpty()) {
            throw ParseException(ParseErrorCode.NO_CONTENT, format)
        }
        return ParsedDoc(parsed.title, parsed.author.orEmpty(), parsed.parts, format)
    }
}