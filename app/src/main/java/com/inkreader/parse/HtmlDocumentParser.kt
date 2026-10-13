package com.inkreader.parse

/**
 * HTML / HTM 解析：把网页正文抽成段落流，再走通用分章。
 *
 * 网页里的 `<nav>` `<aside>` `<footer>` 这些虽然会保留文字，但标题层级（h1~h6）
 * 才是分章依据 —— 没有标题时 [ChapterAssembler] 会退回正则分章，不会把整篇塞成一章。
 */
class HtmlDocumentParser : DocumentParser {

    override val format: DocumentFormat = DocumentFormat.HTML

    override fun parse(bytes: ByteArray, displayName: String): ParsedDoc {
        val html = HtmlTextExtractor.decodeHtml(bytes)
        if (html.isBlank()) throw ParseException(ParseErrorCode.EMPTY, format)

        val extracted = HtmlTextExtractor.extract(html)
        val paragraphs = extracted.paragraphs
        if (paragraphs.isEmpty()) throw ParseException(ParseErrorCode.NO_CONTENT, format)

        val title = extracted.title?.takeIf { it.isNotBlank() }
            ?: paragraphs.firstOrNull { it.level == 0 }?.text?.takeIf { it.length <= 40 }
            ?: ChapterSplitter.titleFromFileName(displayName)

        val parts = ChapterAssembler.assemble(paragraphs)
        return ParsedDoc(title, extracted.author.orEmpty(), parts, format)
    }
}