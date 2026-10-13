package com.inkreader.parse

/**
 * TXT 解析器适配：把既有的 [TxtParser]（编码嗅探 + 书名推断 + 章节切分）
 * 接入统一架构，行为与改造前完全一致，只是失败时改为抛出可读的错误。
 */
class TxtDocumentParser : DocumentParser {

    override val format: DocumentFormat = DocumentFormat.TXT

    override fun parse(bytes: ByteArray, displayName: String): ParsedDoc {
        if (bytes.isEmpty()) throw ParseException(ParseErrorCode.EMPTY, format)
        val parsed = TxtParser.parse(bytes, displayName)
        if (parsed.parts.isEmpty() || parsed.parts.all { it.content.isBlank() }) {
            throw ParseException(ParseErrorCode.EMPTY, format)
        }
        return ParsedDoc(parsed.title, "", parsed.parts, format)
    }
}