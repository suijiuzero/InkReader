package com.inkreader.parse

/**
 * Markdown 解析（导入阶段）。
 *
 * 这里**不再把标记剥掉**：正文以「原始 Markdown」形态按章切好交给仓库层，
 * 由 [RichMarkdown] 在落盘前一次性渲染成「阅读用纯文本 + 样式覆盖」。
 * 之所以把两步分开，是因为导入流程还会做归一化、合并、按文件名排序等操作，
 * 只有在正文最终定稿之后再渲染，样式区间才和落盘的正文严格对齐。
 *
 * 分章使用共享的标题层级与明确章号规划；
 * 全文没有标题就退回 [ChapterSplitter] 的启发式分章（第X章 / 按字数）。
 */
class MarkdownParser : DocumentParser {

    override val format: DocumentFormat = DocumentFormat.MARKDOWN

    override fun parse(bytes: ByteArray, displayName: String): ParsedDoc {
        val src = CharsetDetector.decode(bytes).replace("\r\n", "\n").replace("\r", "\n")
        if (src.isBlank()) throw ParseException(ParseErrorCode.EMPTY, format)

        val meta = parseFrontMatter(src)
        val body = meta.body
        if (body.isBlank()) throw ParseException(ParseErrorCode.NO_CONTENT, format)

        val fallbackTitle = ChapterSplitter.titleFromFileName(displayName)
        val chunks = splitByHeadings(body, fallbackTitle)
        if (chunks.isEmpty()) throw ParseException(ParseErrorCode.NO_CONTENT, format)

        val parts = chunks
        val title = meta.title?.takeIf { it.isNotBlank() }
            ?: chunks.firstOrNull { it.title.isNotBlank() }?.title
            ?: fallbackTitle

        return ParsedDoc(title, meta.author.orEmpty(), parts, format)
    }

    private class FrontMatter(val title: String?, val author: String?, val body: String)

    /** Scan headings once; fenced and indented code never supplies chapter boundaries. */
    private fun splitByHeadings(body: String, fallbackTitle: String): List<ChapterSplitter.Part> {
        val lines = body.split('\n')
        val headings = ArrayList<ChapterAssembler.Heading>()
        var fence: String? = null
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val head = line.trimStart()
            if (fence != null) {
                if (!line.startsWith("    ") && !line.startsWith("\t") && closesFence(head, fence)) fence = null
                index++
                continue
            }
            if (line.startsWith("    ") || line.startsWith("\t")) { index++; continue }
            val marker = fenceOf(head)
            if (marker != null) { fence = marker; index++; continue }
            val atx = ATX_HEADING.find(head)
            if (atx != null) {
                headings += ChapterAssembler.Heading(atx.groupValues[1].length, plainTitle(atx.groupValues[2]), index)
                index++
                continue
            }
            val nextLine = lines.getOrNull(index + 1)
            val next = nextLine?.trim()
            val level = when {
                nextLine == null || nextLine.startsWith("    ") || nextLine.startsWith("\t") -> 0
                next != null && SETEXT_EQ.matches(next) -> 1
                next != null && SETEXT_DASH.matches(next) -> 2
                else -> 0
            }
            if (head.isNotBlank() && level != 0) {
                headings += ChapterAssembler.Heading(level, plainTitle(head), index, index + 2)
                index += 2
            } else index++
        }
        if (headings.isEmpty()) return ChapterSplitter.split(ChapterSplitter.normalize(body), fallbackTitle)
        val sections = ChapterAssembler.plan(headings, lines.size)
        val parts = ArrayList<ChapterSplitter.Part>()
        val prefix = lines.take(sections.first().start).joinToString("\n").trim('\n').trimEnd()
        if (prefix.isNotBlank()) {
            val first = prefix.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            parts += ChapterSplitter.Part(if (first.length in 1..24) plainTitle(first) else "前言", prefix)
        }
        sections.forEachIndexed { number, section ->
            val content = (section.start until section.end).filter { it !in section.heading.start until section.heading.end }
                .joinToString("\n") { lines[it] }.trim('\n').trimEnd()
            parts += ChapterSplitter.Part(section.heading.title.ifBlank { "第 ${number + 1} 章" }, content, section.volume)
        }
        return parts
    }

    // ---------------------------------------------------------------- 前置元数据

    private fun parseFrontMatter(src: String): FrontMatter {
        val text = src.replace("\r\n", "\n").replace("\r", "\n")
        val lines = text.split("\n")
        if (lines.isEmpty() || lines[0].trim() != "---") return FrontMatter(null, null, text)

        var end = -1
        for (i in 1 until lines.size) {
            val t = lines[i].trim()
            if (t == "---" || t == "...") {
                end = i
                break
            }
        }
        if (end < 0) return FrontMatter(null, null, text)

        var title: String? = null
        var author: String? = null
        for (i in 1 until end) {
            val line = lines[i]
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim().trim('"', '\'')
            when (key) {
                "title" -> if (value.isNotEmpty()) title = value
                "author", "creator" -> if (value.isNotEmpty()) author = value
            }
        }
        val body = lines.drop(end + 1).joinToString("\n")
        return FrontMatter(title, author, body)
    }

    // ---------------------------------------------------------------- 小工具

    private fun fenceOf(line: String): String? {
        val c = line.firstOrNull() ?: return null
        if (c != '`' && c != '~') return null
        val marker = line.takeWhile { it == c }
        return marker.takeIf { it.length >= 3 }
    }

    private fun closesFence(line: String, opening: String): Boolean {
        val marker = fenceOf(line) ?: return false
        return marker.first() == opening.first() && marker.length >= opening.length &&
            line.drop(marker.length).isBlank()
    }

    /** 章标题是纯文字，这里只清掉标题里可能夹着的行内标记 */
    private fun plainTitle(raw: String): String {
        var s = raw.trim().replace(Regex("^#{1,6}\\s+"), "").removeSuffix("#").trim()
        for (p in TITLE_NOISE) s = p.replace(s) { it.groupValues[1] }
        return s.trim()
    }

    private companion object {
        val ATX_HEADING = Regex("^(#{1,6})\\s+(.*)$")
        val SETEXT_EQ = Regex("^={2,}\\s*$")
        val SETEXT_DASH = Regex("^-{2,}\\s*$")

        val TITLE_NOISE = listOf(
            Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"),
            Regex("\\[([^\\]]*)\\]\\([^)]*\\)"),
            Regex("`([^`]*)`"),
            Regex("\\*\\*\\*([^*]+)\\*\\*\\*"),
            Regex("\\*\\*([^*]+)\\*\\*"),
            Regex("__([^_]+)__"),
            Regex("\\*([^*]+)\\*"),
            Regex("_([^_]+)_"),
            Regex("~~([^~]+)~~")
        )
    }
}
