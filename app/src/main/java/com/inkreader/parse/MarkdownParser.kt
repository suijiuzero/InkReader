package com.inkreader.parse

/**
 * Markdown 解析（导入阶段）。
 *
 * 这里**不再把标记剥掉**：正文以「原始 Markdown」形态按章切好交给仓库层，
 * 由 [RichMarkdown] 在落盘前一次性渲染成「阅读用纯文本 + 样式覆盖」。
 * 之所以把两步分开，是因为导入流程还会做归一化、合并、按文件名排序等操作，
 * 只有在正文最终定稿之后再渲染，样式区间才和落盘的正文严格对齐。
 *
 * 分章规则与旧版一致：取文档里最浅的标题层级作为章标题；
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

        val parts = chunks.map { ChapterSplitter.Part(it.title.ifBlank { fallbackTitle }, it.markdown) }
        val title = meta.title?.takeIf { it.isNotBlank() }
            ?: chunks.firstOrNull { it.title.isNotBlank() }?.title
            ?: fallbackTitle

        return ParsedDoc(title, meta.author.orEmpty(), parts, format)
    }

    private class Chunk(val title: String, val markdown: String)

    private class FrontMatter(val title: String?, val author: String?, val body: String)

    /**
     * 按标题分章。
     *
     * 只有「最浅的那一级标题」负责分章（和旧版 [ChapterAssembler] 的规则一致），
     * 更深的标题留在正文里，交给渲染端当小标题。
     * 全文一个标题都没有时退回 [ChapterSplitter]，行为与旧版相同。
     */
    private fun splitByHeadings(body: String, fallbackTitle: String): List<Chunk> {
        val lines = body.split('\n')
        val level = shallowestLevel(lines)
        if (level == 0) {
            // 没有 Markdown 标题：交给启发式分章（第X章 / 按字数），与旧版一致
            val normalized = ChapterSplitter.normalize(body)
            return ChapterSplitter.split(normalized, fallbackTitle)
                .map { Chunk(it.title, it.content) }
        }

        val out = ArrayList<Chunk>()
        val buf = StringBuilder()
        var curTitle: String? = null
        var inFence = false
        var fence = ""

        fun flush() {
            val markdown = buf.toString().trim('\n').trimEnd()
            buf.setLength(0)
            if (curTitle != null) {
                out += Chunk(curTitle!!, markdown)
            } else if (markdown.isNotBlank()) {
                // 第一个标题之前的内容：标题取首行（够短的话），否则叫「前言」
                val first = markdown.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                out += Chunk(
                    if (first.isNotEmpty() && first.length <= 24) plainTitle(first) else "前言",
                    markdown
                )
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val head = line.trimStart()

            val marker = fenceOf(head)
            if (inFence) {
                buf.append(line).append('\n')
                if (closesFence(head, fence)) inFence = false
                i++
                continue
            }
            if (marker != null) {
                inFence = true
                fence = marker
                buf.append(line).append('\n')
                i++
                continue
            }

            val atx = ATX_HEADING.find(head)
            if (atx != null) {
                if (atx.groupValues[1].length == level) {
                    flush()
                    curTitle = plainTitle(atx.groupValues[2])
                        .ifBlank { "第 ${out.size + 1} 章" }
                } else {
                    buf.append(line).append('\n')
                }
                i++
                continue
            }

            val next = lines.getOrNull(i + 1)?.trim()
            if (head.trim().isNotEmpty() && next != null) {
                val setext = when {
                    SETEXT_EQ.matches(next) -> 1
                    SETEXT_DASH.matches(next) -> 2
                    else -> 0
                }
                if (setext != 0 && setext == level) {
                    flush()
                    curTitle = plainTitle(head.trim()).ifBlank { "第 ${out.size + 1} 章" }
                    i += 2
                    continue
                }
            }

            buf.append(line).append('\n')
            i++
        }
        flush()
        return out
    }

    /** 文档中最浅的标题层级（1..6），没有标题返回 0 */
    private fun shallowestLevel(lines: List<String>): Int {
        val levels = ArrayList<Int>()
        var inFence = false
        var fence = ""
        var i = 0
        while (i < lines.size) {
            val head = lines[i].trimStart()
            val marker = fenceOf(head)
            if (inFence) {
                if (closesFence(head, fence)) inFence = false
                i++
                continue
            }
            if (marker != null) {
                inFence = true
                fence = marker
                i++
                continue
            }
            val atx = ATX_HEADING.find(head)
            if (atx != null) {
                levels += atx.groupValues[1].length
                i++
                continue
            }
            val next = lines.getOrNull(i + 1)?.trim()
            if (head.trim().isNotEmpty() && next != null) {
                when {
                    SETEXT_EQ.matches(next) -> levels += 1
                    SETEXT_DASH.matches(next) -> levels += 2
                }
            }
            i++
        }
        return ChapterAssembler.chooseChapterLevel(levels) ?: 0
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
