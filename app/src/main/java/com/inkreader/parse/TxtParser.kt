package com.inkreader.parse

/** TXT 解析：字节流 → 编码识别 → 归一化 → 章节切分 */
object TxtParser {

    private const val PUNCT_END = "。！？，、；：,.!?;:"

    data class Parsed(val title: String?, val parts: List<ChapterSplitter.Part>)

    fun parse(bytes: ByteArray, displayName: String): Parsed {
        val raw = CharsetDetector.decode(bytes)
        val text = ChapterSplitter.normalize(raw)
        if (text.isBlank()) return Parsed(null, listOf(ChapterSplitter.Part("空文件", "")))

        val title = detectTitle(text, displayName)
        var parts = ChapterSplitter.split(text, "正文")

        // 去掉开头可能重复书名的那一段（很多 TXT 首行就是书名）
        if (parts.isNotEmpty() && title != null) {
            val first = parts.first().content.trim()
            if (first.startsWith(title) && first.length - title.length < 60) {
                val rest = first.removePrefix(title).trim()
                parts = if (rest.isEmpty()) parts.drop(1).ifEmpty { parts }
                else listOf(parts.first().copy(content = rest)) + parts.drop(1)
            }
        }
        return Parsed(title, parts.ifEmpty { listOf(ChapterSplitter.Part("正文", text)) })
    }

    /**
     * 从正文首部推断书名：
     * 取前 6 行里第一个「短、不以标点结尾、不含明显广告词」的行。
     */
    private fun detectTitle(text: String, displayName: String): String? {
        val adWords = listOf("下载", "整理", "www.", "http", ".com", ".net", "txt", "电子书", "校对", "转载", "手打")
        val head = text.lineSequence().take(6).map { it.trim() }.filter { it.isNotEmpty() }.toList()
        for (line in head) {
            if (line.length in 2..28 &&
                PUNCT_END.indexOf(line.last()) < 0 &&
                adWords.none { line.contains(it, ignoreCase = true) }
            ) {
                return line
            }
        }
        return null
    }

    /** 判断是否是老式二进制 .doc（OLE2 复合文档头） */
    fun isOle2(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() && bytes[3] == 0xE0.toByte()

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())
}
