package com.inkreader.parse

import android.content.Context
import com.inkreader.R

/**
 * 解析失败的原因分类。所有解析器都只抛这个异常，提示文案由 [ParseErrors] 统一生成，
 * 避免「读取失败」「导入失败」「解析失败」三种说法在不同地方各写一遍。
 */
enum class ParseErrorCode {
    /** 格式本身不支持（ZIP、老式 .doc、Excel…） */
    UNSUPPORTED_FORMAT,

    /** 结构损坏：zip 解不开、docx 里没有 document.xml、pdf 结构非法… */
    CORRUPTED,

    /** 文件内容为空（0 字节或只有空白） */
    EMPTY,

    /** 能打开但没解析出正文 */
    NO_CONTENT,

    /** 加密文档 */
    ENCRYPTED,

    /** PDF 里没有文字层，多半是扫描件 */
    NEEDS_OCR,

    /** 读取文件字节失败（权限、ROM 差异等），由导入流程抛出 */
    READ_FAILED,

    /** 文件超过导入体积上限，为免打爆内存直接拒绝读入 */
    TOO_LARGE
}

class ParseException(
    val code: ParseErrorCode,
    val format: DocumentFormat = DocumentFormat.UNKNOWN,
    /** 内部细节（异常消息等），会以括注形式附在提示后面 */
    val detail: String? = null
) : Exception(code.name)

/** 统一的错误文案（走资源，随设备语言切换） */
object ParseErrors {

    fun reason(
        context: Context,
        code: ParseErrorCode,
        format: DocumentFormat = DocumentFormat.UNKNOWN,
        detail: String? = null
    ): String {
        val base = when (code) {
            ParseErrorCode.UNSUPPORTED_FORMAT ->
                if (format == DocumentFormat.DOC) context.getString(R.string.err_unsupported_doc)
                else context.getString(R.string.err_unsupported_format, formatLabel(context, format))

            ParseErrorCode.CORRUPTED ->
                context.getString(R.string.err_corrupted, formatLabel(context, format))
            ParseErrorCode.EMPTY -> context.getString(R.string.err_empty)
            ParseErrorCode.NO_CONTENT -> context.getString(R.string.err_no_content)
            ParseErrorCode.ENCRYPTED -> context.getString(R.string.err_encrypted)
            ParseErrorCode.NEEDS_OCR -> context.getString(R.string.err_needs_ocr)
            ParseErrorCode.READ_FAILED -> context.getString(R.string.err_read_failed)
            ParseErrorCode.TOO_LARGE -> context.getString(R.string.err_too_large)
        }
        val d = detail?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        return if (d == null) base else context.getString(R.string.err_detail_wrap, base, d)
    }

    fun reason(context: Context, e: ParseException): String = reason(context, e.code, e.format, e.detail)

    /** 「文件名：原因」，导入结果里的失败条目统一长这样 */
    fun describe(context: Context, fileName: String, e: ParseException): String =
        context.getString(R.string.err_file_reason, fileName, reason(context, e))

    /** 格式名（「PDF 文档」这类）走资源，便于随语言切换 */
    fun formatLabel(context: Context, format: DocumentFormat): String = context.getString(
        when (format) {
            DocumentFormat.TXT -> R.string.fmt_txt
            DocumentFormat.MARKDOWN -> R.string.fmt_markdown
            DocumentFormat.DOCX -> R.string.fmt_docx
            DocumentFormat.PDF -> R.string.fmt_pdf
            DocumentFormat.HTML -> R.string.fmt_html
            DocumentFormat.EPUB -> R.string.fmt_epub
            DocumentFormat.DOC -> R.string.fmt_doc
            DocumentFormat.ZIP -> R.string.fmt_zip
            DocumentFormat.XLSX -> R.string.fmt_xlsx
            DocumentFormat.PPTX -> R.string.fmt_pptx
            DocumentFormat.UNKNOWN -> R.string.fmt_unknown
        }
    )
}

/**
 * 解析结果的统一形态。
 *
 * 各格式解析器（TXT / DOCX / Markdown / PDF / HTML / EPUB）最终都收敛到
 * 「书名 + 作者 + 章节列表」，之后的合并、分章、排序、落盘全部复用原有流程。
 */
data class ParsedDoc(
    val title: String?,
    val author: String,
    val parts: List<ChapterSplitter.Part>,
    val format: DocumentFormat
)

/** 一种格式对应一个解析器 */
interface DocumentParser {
    val format: DocumentFormat

    /**
     * @throws ParseException 解析失败时抛出，调用方据此给出具体提示
     */
    fun parse(bytes: ByteArray, displayName: String): ParsedDoc
}