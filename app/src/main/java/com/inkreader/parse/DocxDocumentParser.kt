package com.inkreader.parse

/**
 * DOCX 解析器适配：包一层既有的 [DocxParser]。
 *
 * 保留 OOXML 段落、标题样式与嵌入图片，并区分损坏、空内容和资源超限。
 */
class DocxDocumentParser : DocumentParser {

    override val format: DocumentFormat = DocumentFormat.DOCX

    override fun parse(bytes: ByteArray, displayName: String): ParsedDoc {
        if (!FormatDetector.isZip(bytes)) {
            throw ParseException(ParseErrorCode.CORRUPTED, format, "文件不是有效的 docx 结构")
        }
        val parsed = try {
            DocxParser().parse(bytes.inputStream())
        } catch (e: ParseException) { throw e
        } catch (e: java.util.concurrent.CancellationException) { throw e
        } catch (t: Throwable) {
            throw ParseException(ParseErrorCode.CORRUPTED, format, t.message)
        }
        if (parsed.parts.isEmpty()) {
            throw ParseException(ParseErrorCode.NO_CONTENT, format)
        }
        return ParsedDoc(parsed.title, parsed.author.orEmpty(), parsed.parts, format, sourceParts = parsed.sourceParts, images = parsed.images)
    }
}