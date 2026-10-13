package com.inkreader.parse

import android.content.Context
import com.inkreader.data.model.SourceType
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * 统一解析入口。
 *
 * 导入流程只需要调用 [parse]：它负责识别格式 → 选解析器 → 统一错误。
 * 失败一律抛 [ParseException]，提示文案由 [ParseErrors] 生成，
 * 这样「暂不支持该文件格式」「文件内容为空」「没有提取到文字」这些说法全局一致。
 */
object DocumentParsers {

    private val parsers: Map<DocumentFormat, DocumentParser> by lazy {
        listOf(
            TxtDocumentParser(),
            DocxDocumentParser(),
            MarkdownParser(),
            PdfParser(),
            HtmlDocumentParser(),
            EpubParser()
        ).associateBy { it.format }
    }

    /** pdfbox 需要初始化一次（加载字体/字型表等资源），在 Application 启动时调用 */
    fun init(context: Context) {
        runCatching { PDFBoxResourceLoader.init(context.applicationContext) }
    }

    fun detect(bytes: ByteArray, name: String): DocumentFormat =
        FormatDetector.detect(bytes, name)

    /**
     * @throws ParseException 文件为空、格式不支持、损坏、无正文…都从这里抛出
     */
    fun parse(bytes: ByteArray, name: String): ParsedDoc {
        val format = FormatDetector.detect(bytes, name)
        if (bytes.isEmpty()) throw ParseException(ParseErrorCode.EMPTY, format)
        if (!format.isSupported) throw ParseException(ParseErrorCode.UNSUPPORTED_FORMAT, format)
        val parser = parsers[format] ?: throw ParseException(ParseErrorCode.UNSUPPORTED_FORMAT, format)

        val doc = try {
            parser.parse(bytes, name)
        } catch (e: ParseException) {
            throw e
        } catch (t: Throwable) {
            throw ParseException(ParseErrorCode.CORRUPTED, format, t.message)
        }

        if (doc.parts.isEmpty() || doc.parts.all { it.content.isBlank() }) {
            throw ParseException(ParseErrorCode.NO_CONTENT, format)
        }
        return doc
    }

    fun sourceTypeOf(format: DocumentFormat): SourceType = when (format) {
        DocumentFormat.TXT -> SourceType.TXT
        DocumentFormat.DOCX -> SourceType.DOCX
        DocumentFormat.MARKDOWN -> SourceType.MD
        DocumentFormat.PDF -> SourceType.PDF
        DocumentFormat.HTML -> SourceType.HTML
        DocumentFormat.EPUB -> SourceType.EPUB
        else -> SourceType.TXT
    }
}