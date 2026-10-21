package com.inkreader.parse

/**
 * 轻量 HTML 正文提取。
 *
 * 没有引入 jsoup：App 只需要「把网页变成可读的段落流」，用不着完整 DOM/CSS 选择器，
 * 而这个状态机已经能覆盖真实网页里 95% 的情况 —— 并且对**残缺标签**是容错的
 * （EPUB 的 XHTML 大多规范，但用户存下来的网页经常缺结束标签）。
 *
 * 输出与 DOCX 解析一致：[ChapterAssembler.Para] 列表（h1~h6 → 层级 0~5，其余 → [ChapterAssembler.BODY]），
 * 这样 HTML / EPUB / Markdown 能共用同一套分章规则。
 */
object HtmlTextExtractor {

    class Extracted(
        val title: String?,
        val author: String?,
        val paragraphs: List<ChapterAssembler.Para>
    )

    /** 整段丢弃内容的标签（脚本、样式、矢量图、表单控件…） */
    private val SKIP_TAGS = setOf(
        "script", "style", "noscript", "svg", "canvas", "iframe", "template",
        "object", "embed", "audio", "video", "select", "option", "textarea", "button"
    )

    /** 会断开段落的块级标签 */
    private val BLOCK_TAGS = setOf(
        "p", "div", "li", "ul", "ol", "dl", "dt", "dd", "blockquote", "pre",
        "section", "article", "header", "footer", "aside", "main", "nav", "figure",
        "figcaption", "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption",
        "hr", "body", "html", "head", "form", "fieldset", "address", "details",
        "summary", "center", "hgroup", "ruby"
    )

    private val VOID_TAGS = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")

    private val HEADING = Regex("^h([1-6])$")

    private val ATTR = Regex(
        "([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))"
    )

    fun extract(html: String): Extracted {
        val paras = ArrayList<ChapterAssembler.Para>()
        val cur = StringBuilder()
        var curLevel = ChapterAssembler.BODY
        var titleBuf: StringBuilder? = null
        var docTitle: String? = null
        var author: String? = null
        var skipTag: String? = null
        var skipDepth = 1
        var inPre = false

        fun flush() {
            val t = cur.toString().trim()
            cur.setLength(0)
            if (t.isNotEmpty()) {
                // 兜底：段落自身就是一行「第X章」而标签层级没体现出来时，仍按正文处理，
                // 交给 ChapterSplitter / ChapterAssembler 的启发式判断。
                paras += ChapterAssembler.Para(curLevel, t)
            }
            curLevel = ChapterAssembler.BODY
        }

        var i = 0
        val n = html.length
        while (i < n) {
            if (html[i] != '<') {
                val next = html.indexOf('<', i)
                val end = if (next < 0) n else next
                val text = html.substring(i, end)
                when {
                    skipTag != null -> Unit
                    titleBuf != null -> titleBuf.append(decodeEntities(text))
                    else -> appendText(text, cur, preserveWs = inPre)
                }
                i = end
                continue
            }

            // ---- 标签 ----
            var p = i + 1
            var closing = false
            if (p < n && html[p] == '/') {
                closing = true
                p++
            }

            // 注释 / DOCTYPE / CDATA / 处理指令
            if (p < n && (html[p] == '!' || html[p] == '?')) {
                if (html.startsWith("<!--", i)) {
                    val e = html.indexOf("-->", i + 4)
                    i = if (e < 0) n else e + 3
                    continue
                }
                if (html.startsWith("<![CDATA[", i)) {
                    val e = html.indexOf("]]>", i + 9)
                    val body = html.substring(i + 9, if (e < 0) n else e)
                    if (skipTag == null) appendText(body, cur, preserveWs = inPre)
                    i = if (e < 0) n else e + 3
                    continue
                }
                val e = html.indexOf('>', p)
                i = if (e < 0) n else e + 1
                continue
            }

            val nameStart = p
            while (p < n && (html[p].isLetterOrDigit() || html[p] == '-' || html[p] == '_' || html[p] == ':')) p++
            val name = html.substring(nameStart, p).lowercase().substringAfterLast(':')
            if (name.isEmpty()) {
                i++
                continue
            }
            val tagEnd = html.indexOf('>', p)
            val attrs = if (tagEnd < 0) "" else html.substring(p, tagEnd)
            val selfClosing = attrs.trimEnd().endsWith("/")
            i = if (tagEnd < 0) n else tagEnd + 1

            // 正在跳过的标签内部：只数同名的开合，等它闭合
            if (skipTag != null) {
                if (name == skipTag) {
                    if (closing) {
                        skipDepth--
                        if (skipDepth <= 0) skipTag = null
                    } else if (!selfClosing) skipDepth++
                }
                continue
            }

            when {
                name == "title" -> {
                    if (closing) {
                        titleBuf?.let { docTitle = it.toString().trim().ifEmpty { null } }
                        titleBuf = null
                    } else {
                        titleBuf = StringBuilder()
                    }
                }

                name == "meta" -> {
                    val key = attrValue(attrs, "name")?.lowercase()
                        ?: attrValue(attrs, "property")?.lowercase()
                    if (key == "author" || key == "og:novel:author" || key == "article:author") {
                        attrValue(attrs, "content")?.trim()?.takeIf { it.isNotEmpty() }
                            ?.let { if (author == null) author = it }
                    }
                }

                name == "br" || name == "hr" -> flush()

                name == "pre" -> {
                    flush()
                    inPre = !closing
                }

                HEADING.matches(name) -> {
                    if (!closing) {
                        flush()
                        curLevel = name[1].digitToInt() - 1
                    } else {
                        flush()
                    }
                }

                name in SKIP_TAGS -> {
                    if (!closing && !selfClosing && name !in VOID_TAGS) {
                        flush()
                        skipTag = name
                        skipDepth = 1
                    }
                }

                name in BLOCK_TAGS -> flush()

                // 行内标签（a / span / b / em / code / img …）不产生段落边界，直接忽略
                else -> Unit
            }
        }
        flush()

        return Extracted(
            title = docTitle,
            author = author,
            paragraphs = paras
        )
    }

    /**
     * 按声明里的编码解码 HTML 字节。
     *
     * 网页的编码不一定和 BOM/UTF-8 校验的结果一致 —— `<meta charset="gbk">` 的老网页很常见，
     * 所以先按 Latin-1 扫一遍头部取出 charset 声明，能认出来就用它，否则回到通用嗅探。
     */
    fun decodeHtml(bytes: ByteArray): String {
        val head = String(bytes.copyOf(minOf(bytes.size, 2048)), Charsets.ISO_8859_1)
        val declared = Regex("(?i)charset\\s*=\\s*[\"']?([a-zA-Z0-9_\\-]+)").find(head)
            ?.groupValues?.getOrNull(1)
        if (declared != null && !declared.equals("utf-8", true) && !declared.equals("utf8", true)) {
            val cs = runCatching { java.nio.charset.Charset.forName(declared) }.getOrNull()
            if (cs != null) return runCatching { String(bytes, cs) }.getOrDefault(CharsetDetector.decode(bytes))
        }
        return CharsetDetector.decode(bytes)
    }

    /** 去掉所有标签，只留文本（Markdown 里的内嵌 HTML 用它） */
    fun stripTags(html: String): String {
        if (html.indexOf('<') < 0) return decodeEntities(html)
        val sb = StringBuilder(html.length)
        var i = 0
        while (i < html.length) {
            if (html[i] == '<') {
                val end = html.indexOf('>', i)
                if (end < 0) break
                i = end + 1
            } else {
                sb.append(html[i])
                i++
            }
        }
        return decodeEntities(sb.toString())
    }

    /** 文本追加：普通模式下压缩连续空白；[preserveWs] 为 true（<pre> 内）时原样保留 */
    private fun appendText(text: String, sb: StringBuilder, preserveWs: Boolean) {
        val decoded = decodeEntities(text)
        if (preserveWs) {
            sb.append(decoded)
            return
        }
        for (c in decoded) {
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C') {
                if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
            } else {
                sb.append(c)
            }
        }
    }

    /** HTML 实体解码：具名实体 + 十进制/十六进制数字实体 */
    fun decodeEntities(s: String): String {
        if (s.isEmpty()) return s
        if (s.indexOf('&') < 0 && s.indexOf('\u00A0') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i)
                if (semi > i + 1 && semi <= i + 12) {
                    val ch = entityToChar(s.substring(i + 1, semi))
                    if (ch != null) {
                        sb.append(ch)
                        i = semi + 1
                        continue
                    }
                }
            }
            if (c == '\u00A0') sb.append(' ') else sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun entityToChar(body: String): String? {
        if (body.isEmpty()) return null
        if (body[0] == '#') {
            val code = if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) {
                body.substring(2).toIntOrNull(16)
            } else {
                body.substring(1).toIntOrNull()
            }
            return when {
                code == null -> null
                code in 1..0xFFFF -> code.toChar().toString()
                code in 0x10000..0x10FFFF -> {
                    val v = code - 0x10000
                    String(charArrayOf(((v shr 10) + 0xD800).toChar(), ((v and 0x3FF) + 0xDC00).toChar()))
                }

                else -> null
            }
        }
        return when (body.lowercase()) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            "emsp", "ensp", "thinsp", "hairsp" -> " "
            "mdash" -> "—"
            "ndash" -> "–"
            "hellip" -> "…"
            "ldquo" -> "“"
            "rdquo" -> "”"
            "lsquo" -> "‘"
            "rsquo" -> "’"
            "laquo" -> "«"
            "raquo" -> "»"
            "middot" -> "·"
            "bull" -> "•"
            "sect" -> "§"
            "para" -> "¶"
            "dagger" -> "†"
            "copy" -> "©"
            "reg" -> "®"
            "trade" -> "™"
            "deg" -> "°"
            "times" -> "×"
            "divide" -> "÷"
            "euro" -> "€"
            "pound" -> "£"
            "yen" -> "¥"
            "cent" -> "¢"
            else -> null
        }
    }

    /** 从标签属性串里取值（兼容单引号 / 双引号 / 无引号） */
    fun attrValue(attrs: String, name: String): String? {
        for (m in ATTR.findAll(attrs)) {
            if (m.groupValues[1].lowercase() == name) {
                val v = m.groupValues[3].ifEmpty { m.groupValues[4].ifEmpty { m.groupValues[5] } }
                return v
            }
        }
        return null
    }
}