package com.inkreader.parse

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.BulletList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * Markdown → 「阅读用纯文本 + 样式覆盖」。
 *
 * 为什么不用「正则剥标记」：嵌套强调、围栏代码块、表格、嵌套列表这些结构
 * 逐条正则根本处理不对（`**a *b* c**` 会被拆错），而且剥完标记就再也
 * 无法知道哪一段该加粗。这里交给 commonmark 出 AST，我们**只走一遍 AST**，
 * 一边拼出阅读用的纯文本、一边把「块类型」和「行内样式区间」记下来。
 *
 * 输出的纯文本就是墨阅原有的正文形态（朗读、搜索、选区、进度全按它的字符偏移走），
 * 样式覆盖另存，两边偏移严格对齐 —— 这是格式化能贯穿分页的前提。
 */
object RichMarkdown {

    /** @param rich 为 null 表示这次没能生成样式（调用方按普通正文处理） */
    data class Rendered(val text: String, val rich: RichChapter?)

    private val parser: Parser = Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                TaskListItemsExtension.create(),
                StrikethroughExtension.create()
            )
        )
        .build()

    fun render(markdown: String): Rendered =
        runCatching { Renderer(markdown).run() }
            // 极端情况（内存不足之类）宁可退回「原文即正文」，也不能让整本书打不开
            .getOrElse { Rendered(markdown, null) }

    // ---------------------------------------------------------------- 渲染器

    private class Renderer(private val markdown: String) {

        private val sb = StringBuilder(markdown.length)
        private val blocks = ArrayList<RichBlock>(64)
        private val spans = ArrayList<InlineStyle>(64)
        private val links = ArrayList<LinkSpan>(8)

        fun run(): Rendered {
            val doc: Document = parser.parse(markdown) as Document
            blocks(childrenOf(doc), quoteDepth = 0)
            val text = sb.toString()
            val rich = RichChapter(text.length, blocks, spans, links)
            return if (text.isNotBlank() && sane(rich)) {
                Rendered(text, rich)
            } else {
                Rendered(text, null)
            }
        }

        /** 块必须有序、不重叠、落在正文内；对不上就整体弃用样式（正文照旧能读） */
        private fun sane(rich: RichChapter): Boolean {
            var prev = 0
            for (b in rich.blocks) {
                if (b.start < prev || b.end > rich.len || b.end <= b.start) return false
                prev = b.end
            }
            for (s in rich.spans) {
                if (s.start < 0 || s.end > rich.len || s.end <= s.start) return false
            }
            return true
        }

        // ------------------------------ 块级

        private fun blocks(nodes: List<Node>, quoteDepth: Int, listIndent: Int = 0) {
            for (n in nodes) {
                when (n) {
                    is Heading -> emit(RichKind.HEADING, n.level, "") { inline(n) }
                    is Paragraph -> {
                        if (quoteDepth > 0) emit(RichKind.QUOTE, quoteDepth - 1, "") { inline(n) }
                        else emit(RichKind.BODY, 0, "") { inline(n) }
                    }

                    is FencedCodeBlock -> emitLiteral(RichKind.CODE, 0, n.literal)
                    is IndentedCodeBlock -> emitLiteral(RichKind.CODE, 0, n.literal)

                    // 分隔线：正文放一个全角空格占位（渲染端照着这个块画一条线）
                    is ThematicBreak -> emit(RichKind.RULE, 0, "") { it.append('\u3000') }

                    is BlockQuote -> blocks(childrenOf(n), quoteDepth + 1, listIndent)

                    is BulletList -> list(n, listIndent, ordered = null, bullet = "•")
                    is OrderedList -> list(n, listIndent, ordered = n, bullet = "")

                    is TableBlock -> table(n)

                    is HtmlBlock -> {
                        val t = HtmlTextExtractor.stripTags(n.literal).trim()
                        if (t.isNotEmpty()) emit(RichKind.BODY, 0, "") { it.append(t) }
                    }

                    else -> if (n.firstChild != null) blocks(childrenOf(n), quoteDepth, listIndent)
                }
            }
        }

        /**
         * 列表。
         *
         * 每个列表项自己成一个块：项目符号 / 序号交给渲染端画在左侧留白里，
         * 缩进由「块层级」表达，所以嵌套列表不会再被拍平成一串同级缩进。
         */
        private fun list(
            listNode: Node,
            indent: Int,
            ordered: OrderedList?,
            bullet: String
        ) {
            val start = ordered?.startNumber ?: 1
            val delim = ordered?.delimiter?.toString() ?: "."
            var i = 0
            var item = listNode.firstChild
            while (item != null) {
                if (item is ListItem) {
                    // 任务列表：勾选状态是 ListItem 的第一个子节点（不是段落里的行内节点），
                    // 拿它当项目符号画在留白里，选中态直接可辨。
                    var child = item.firstChild
                    var task = ""
                    if (child is TaskListItemMarker) {
                        task = if (child.isChecked) "☑" else "☐"
                        child = child.next
                    }
                    val marker = when {
                        task.isNotEmpty() -> task
                        ordered != null -> "${start + i}$delim"
                        else -> bullet
                    }
                    var firstParagraph = true
                    while (child != null) {
                        when (child) {
                            is Paragraph -> {
                                val m = if (firstParagraph) marker else ""
                                emit(RichKind.LIST, indent, m) { inline(child) }
                                firstParagraph = false
                            }

                            is BulletList -> list(child, indent + 1, null, "•")
                            is OrderedList -> list(child, indent + 1, child, "")
                            // 列表项里的代码块 / 引用 / 表格
                            else -> blocks(listOf(child), quoteDepth = 0, listIndent = indent)
                        }
                        child = child.next
                    }
                }
                item = item.next
                i++
            }
        }

        private fun table(node: Node) {
            val head = ArrayList<List<String>>()
            val body = ArrayList<List<String>>()
            var child = node.firstChild
            while (child != null) {
                when (child) {
                    is TableHead -> collectRows(child, head)
                    is TableBody -> collectRows(child, body)
                }
                child = child.next
            }
            val rows = head + body
            if (rows.isEmpty()) return
            val cols = rows.maxOf { it.size }
            if (cols == 0) return

            // 等宽字体下按「显示宽度」补空格才能对齐（中文占两格）
            val widths = IntArray(cols)
            for (row in rows) {
                for (c in 0 until cols) {
                    widths[c] = maxOf(widths[c], displayWidth(row.getOrElse(c) { "" }))
                }
            }
            val total = widths.sum() + (cols - 1) * 3
            // 超宽表格不再补空格，交给自动换行，免得挤成一坨看不清
            val pad = total <= 72

            rows.forEachIndexed { i, row ->
                val kind = if (i < head.size) RichKind.TABLE_HEAD else RichKind.TABLE_ROW
                emit(kind, 0, "") { b ->
                    for (c in 0 until cols) {
                        if (c > 0) b.append(" │ ")
                        val cell = row.getOrElse(c) { "" }
                        b.append(cell)
                        if (pad && c < cols - 1) {
                            repeat((widths[c] - displayWidth(cell)).coerceAtLeast(0)) { b.append(' ') }
                        }
                    }
                }
            }
        }

        private fun collectRows(rowHolder: Node, out: MutableList<List<String>>) {
            var row = rowHolder.firstChild
            while (row != null) {
                if (row is TableRow) {
                    val cells = ArrayList<String>(4)
                    var cell = row.firstChild
                    while (cell != null) {
                        if (cell is TableCell) {
                            cells += plainInline(cell).trim().replace('\n', ' ')
                        }
                        cell = cell.next
                    }
                    out += cells
                }
                row = row.next
            }
        }

        // ------------------------------ 写入

        /**
         * 追加一个块。
         *
         * 正文里的块之间用空行分隔（和墨阅原有的正文形态一致）；
         * 写完之后若发现块是空的（空段落之类）就整体回退，不留空段。
         */
        private fun emit(kind: RichKind, level: Int, marker: String, writer: (StringBuilder) -> Unit) {
            val before = sb.length
            if (before > 0) sb.append("\n\n")
            val start = sb.length
            writer(sb)
            var end = sb.length
            while (end > start && sb[end - 1] == ' ') end--
            if (end <= start) {
                sb.setLength(before)
                return
            }
            sb.setLength(end)
            blocks += RichBlock(kind, level, marker, start, end)
        }

        private fun emitLiteral(kind: RichKind, level: Int, literal: String) {
            val body = literal.trimEnd('\n')
            if (body.isBlank()) return
            emit(kind, level, "") { it.append(body) }
        }

        // ------------------------------ 行内

        private fun inline(parent: Node) {
            var n = parent.firstChild
            while (n != null) {
                when (n) {
                    is Text -> sb.append(n.literal)
                    // 行内代码的文字在 literal 上（Code 是叶子节点，没有子节点）
                    is Code -> {
                        val lit = n.literal
                        style(code = true) { sb.append(lit) }
                    }
                    is Emphasis -> style(italic = true) { inline(n) }
                    is StrongEmphasis -> style(bold = true) { inline(n) }
                    is Strikethrough -> style(strike = true) { inline(n) }

                    is Link -> {
                        val s = sb.length
                        inline(n)
                        val e = sb.length
                        // 只认安全协议，其余退化成普通文字（不给「点一下跳出去」的机会）
                        if (e > s && isSafeUrl(n.destination)) links += LinkSpan(s, e, n.destination)
                    }

                    is Image -> {
                        val alt = plainInline(n).trim()
                        sb.append(if (alt.isEmpty()) "［图片］" else "［图片：$alt］")
                    }

                    is SoftLineBreak -> sb.append(' ')
                    is HardLineBreak -> sb.append('\n')

                    is HtmlInline -> {
                        val t = HtmlTextExtractor.stripTags(n.literal).trim()
                        if (t.isNotEmpty()) sb.append(t)
                    }

                    is TaskListItemMarker -> sb.append(if (n.isChecked) "☑ " else "☐ ")

                    else -> if (n.firstChild != null) inline(n)
                }
                n = n.next
            }
        }

        private fun style(
            bold: Boolean = false,
            italic: Boolean = false,
            strike: Boolean = false,
            code: Boolean = false,
            body: () -> Unit
        ) {
            val s = sb.length
            body()
            val e = sb.length
            if (e > s) spans += InlineStyle(s, e, bold, italic, strike, code)
        }

        /** 只要文字（图片替代文字、表格单元格用），不带任何样式 */
        private fun plainInline(parent: Node): String {
            val out = StringBuilder()
            var n = parent.firstChild
            while (n != null) {
                when (n) {
                    is Text -> out.append(n.literal)
                    is Code -> out.append(n.literal)
                    is SoftLineBreak -> out.append(' ')
                    is HardLineBreak -> out.append('\n')
                    is Image -> out.append("［图片］")
                    is HtmlInline -> out.append(HtmlTextExtractor.stripTags(n.literal))
                    is TaskListItemMarker -> out.append(if (n.isChecked) "☑ " else "☐ ")
                    else -> if (n.firstChild != null) out.append(plainInline(n))
                }
                n = n.next
            }
            return out.toString()
        }
    }

    // ---------------------------------------------------------------- 小工具

    private fun childrenOf(n: Node): List<Node> {
        val out = ArrayList<Node>(4)
        var c = n.firstChild
        while (c != null) {
            out += c
            c = c.next
        }
        return out
    }

    /** 中文/全角按两格算，等宽字体下表格才能对齐 */
    private fun displayWidth(s: String): Int {
        var w = 0
        for (ch in s) w += if (isWide(ch)) 2 else 1
        return w
    }

    private fun isWide(ch: Char): Boolean {
        val code = ch.code
        return code in 0x1100..0x115F || code in 0x2E80..0xA4CF ||
            code in 0xAC00..0xD7A3 || code in 0xF900..0xFAFF ||
            code in 0xFE30..0xFE4F || code in 0xFF00..0xFF60 ||
            code in 0xFFE0..0xFFE6
    }

    /** 只有 http/https/mailto 允许被点开 */
    fun isSafeUrl(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("http://") || u.startsWith("https://") || u.startsWith("mailto:")
    }
}