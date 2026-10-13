package com.inkreader.parse

/**
 * 章节切分。
 * 先用一组标题模式扫描候选行；命中足够多且密度合理时按标题切分，
 * 否则退化为按字数在段落边界切分，保证任何 TXT 都能得到可用的目录。
 */
object ChapterSplitter {

    data class Part(val title: String, val content: String)

    // ---------------------------------------------------------------- 编号与规则片段

    /** 阿拉伯 / 全角数字 + 中日通用汉字数字（含大写壹贰…） */
    private const val KANJI_DIGITS = "0-9０-９零〇一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰仟"

    /** 罗马数字用到的字符 */
    private const val ROMAN_CHARS = "IVXLCDMivxlcdm"

    /** 英文数字单词（Chapter One / Part Twenty / Volume One Hundred） */
    private const val EN_WORDS =
        "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|" +
            "fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|" +
            "seventy|eighty|ninety|hundred|thousand|and"

    /**
     * 统一的「编号」片段：阿拉伯 / 全角 / 汉字数字、罗马数字、英文数字单词。
     *
     * 罗马与英文后面加了「不能再接字母」的断言，免得 `Chapter Vivid`、`Chapter Ones`
     * 这种被切出一个假编号（`Viv`、`One`）。汉字数字不进这个断言 —— 汉字本身就不会和拉丁字母粘连。
     */
    private const val NUM =
        "(?:[$KANJI_DIGITS]{1,10}" +
            "|[$ROMAN_CHARS]{1,15}(?![A-Za-z])" +
            "|(?i:(?:$EN_WORDS)(?:[\\s-]+(?:$EN_WORDS)){0,4})(?![A-Za-z]))"

    /** 章级标记词：中日通用（節/话/話/巻/編 都认；节/話 是同一层级的不同写法） */
    private const val CH_MARKS = "章回節节卷巻集部篇編编幕折話话"

    /** 章级标记（严格版）：**不含「节 / 節」**，判断文件是否真的按章排好时用 */
    private const val CH_MARKS_STRICT = "章回卷巻集部篇編编幕折話话"

    /** 特殊章节用词（序章 / 终章 / 番外 / 幕間 / Prologue / Interlude…）：位置固定，但没有章号 */
    private const val SPECIAL_WORDS =
        "prologue|epilogue|interlude|afterword|foreword|preface" +
            "|序章|序言|序曲|序説|楔子|引子|前言|开篇|卷首语|終章|终章|後記|后记|尾声|番外" +
            "|结语|完本感言|作者的话|内容提要|目录|幕間|幕间|番外編|番外篇|プロローグ" +
            "|エピローグ|あとがき|まえがき|外伝|外传|付録|附录"

    /** 特殊章节标题（中 / 日 / 英）：位置明确但不是「编号」的那一类 */
    private val specialTitle = Regex(
        "^\\s{0,6}[（(【\\[]?\\s*(?i:$SPECIAL_WORDS)\\s*[)）】\\]]?\\s*(.{0,24})$"
    )

    /** 强模式：几乎不会误判 */
    private val strong = listOf(
        // 中 / 日：第X章・第X話・第X節・第X巻（阿拉伯、全角、汉字数字都认）
        Regex("^\\s{0,6}第\\s*[$KANJI_DIGITS]{1,10}\\s*[$CH_MARKS]\\s*(.{0,40})$"),
        // 英 / 日：Chapter / Part / Book / Volume / Episode + 编号（阿拉伯、罗马、英文数字单词、汉字数字）
        Regex("^\\s{0,6}(?i:chapter|part|book|volume|vol|episode|ep)\\.?\\s*$NUM\\s*[:：.、\\-–—]?\\s*(.{0,50})$"),
        specialTitle
    )

    /** 弱模式：数字编号，容易和正文里的数字行冲突，需配合密度判断 */
    private val weak = listOf(
        Regex("^\\s{0,6}[0-9０-９]{1,4}\\s*[、.．，,：:]\\s*\\S.{0,36}$"),
        Regex("^\\s{0,6}[（(]\\s*[0-9０-９]{1,4}\\s*[)）]\\s*\\S?.{0,28}$"),
        Regex("^\\s{0,6}[0-9０-９]{1,4}\\s+\\S.{0,36}$"),
        Regex("^\\s{0,6}[卷巻]\\s*[$KANJI_DIGITS]{1,10}\\s*(.{0,30})$")
    )

    private const val SENT_END = "。！？；…”』」）) "
    private const val TITLE_FORBIDDEN_END = "。！？，；：”"

    private const val FALLBACK_CHARS = 18000
    private const val MAX_CHAPTER_CHARS = 120000

    /** 归一化：统一换行符、去掉行尾空白、压缩连续空行、剔除零宽字符 */
    fun normalize(raw: String): String {
        val s = raw.replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\uFEFF", "")
            .replace("\u200B", "")
            .replace("\u00A0", " ")
            .replace("\u3000", "　")
        val lines = s.split("\n").map { it.trimEnd() }
        val sb = StringBuilder(s.length)
        var blank = 0
        for (line in lines) {
            if (line.isBlank()) {
                blank++
                if (blank <= 1) sb.append('\n')
            } else {
                blank = 0
                sb.append(line).append('\n')
            }
        }
        return sb.toString().trim()
    }

    /** 段落化：以空行分段；无空行时以句末标点 + 缩进启发式分段 */
    fun toParagraphs(text: String): List<String> {
        val blocks = text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        if (blocks.size >= 4) return blocks.map { it.replace("\n", "") }
        // 没有空行分隔：按行处理，短行且下一行以中文开头视为新段落
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size <= 1) return blocks.ifEmpty { listOf(text) }
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for ((i, line) in lines.withIndex()) {
            if (cur.isEmpty()) {
                cur.append(line)
            } else {
                val prevEnds = SENT_END.indexOf(cur.last()) >= 0
                val startsNew = line.length < cur.length && prevEnds && i > 0
                if (startsNew && cur.length > 60) {
                    out += cur.toString()
                    cur.setLength(0)
                    cur.append(line)
                } else {
                    cur.append(line)
                }
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return if (out.isEmpty()) listOf(text) else out
    }

    /** 把段落拼成正文，段间空一行 */
    fun joinParagraphs(paragraphs: List<String>): String = paragraphs.joinToString("\n\n")

    fun split(text: String, fallbackTitle: String = "正文"): List<Part> {
        if (text.isBlank()) return listOf(Part(fallbackTitle, ""))
        val lines = text.split("\n")

        val strongIdx = findTitleLines(lines, strong)
        val chosen: List<Int>
        val patternsUsed: List<Regex>
        if (strongIdx.size >= 2) {
            chosen = strongIdx
            patternsUsed = strong
        } else {
            val weakIdx = findTitleLines(lines, strong + weak)
            // 弱模式命中密度必须合理：平均每章不少于 300 字
            val avgGap = if (weakIdx.size > 1) text.length.toFloat() / weakIdx.size else Float.MAX_VALUE
            if (weakIdx.size >= 3 && avgGap >= 300) {
                chosen = weakIdx
                patternsUsed = strong + weak
            } else if (strongIdx.size == 1) {
                chosen = strongIdx
                patternsUsed = strong
            } else {
                return splitBySize(text, fallbackTitle)
            }
        }

        val parts = ArrayList<Part>()
        // 第一个标题之前的内容作为前言
        val head = lines.subList(0, chosen.first()).joinToString("\n").trim()
        if (head.isNotEmpty() && head.length >= 200) {
            parts += Part(guessHeadTitle(head), head)
        } else if (head.isNotEmpty()) {
            // 太短，直接并到第一章前面
        }

        for ((i, lineIdx) in chosen.withIndex()) {
            val endIdx = if (i + 1 < chosen.size) chosen[i + 1] else lines.size
            val title = lines[lineIdx].trim().ifEmpty { "第 ${i + 1} 章" }
            val bodyLines = lines.subList(lineIdx + 1, endIdx)
            var body = bodyLines.joinToString("\n").trim()
            if (i == 0 && head.isNotEmpty() && head.length < 200) body = "$head\n\n$body"
            if (body.isEmpty()) body = title
            // 单章过长时再按字数拆
            if (body.length > MAX_CHAPTER_CHARS) {
                val subs = splitBySize(body, title)
                subs.forEachIndexed { k, p ->
                    parts += Part(if (k == 0) title else "$title（${k + 1}）", p.content)
                }
            } else {
                parts += Part(title, body)
            }
        }
        if (parts.isEmpty()) return splitBySize(text, fallbackTitle)
        return parts
    }

    private fun guessHeadTitle(head: String): String {
        val first = head.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (first.isNotEmpty() && first.length <= 20) first else "前言"
    }

    private fun findTitleLines(lines: List<String>, patterns: List<Regex>): List<Int> {
        val out = ArrayList<Int>()
        for (i in lines.indices) {
            val raw = lines[i]
            val t = raw.trim()
            if (t.isEmpty() || t.length > 44) continue
            // 标题行不应以句末标点结尾（那更像正文）
            if (TITLE_FORBIDDEN_END.indexOf(t.last()) >= 0) continue
            // 必须独占一行：前后至少一侧为空行，或本身是首行
            val prevBlank = i == 0 || lines[i - 1].isBlank()
            val nextBlank = i == lines.lastIndex || lines[i + 1].isBlank()
            val nextNonBlankTooLong = run {
                val nx = lines.getOrNull(i + 1)
                nx != null && nx.isNotBlank() && nx.trim().length > 44
            }
            if (!(prevBlank || nextBlank || nextNonBlankTooLong)) continue
            // 正文里引用某一章：「第一章里提到的…」「第三章的约定」，不是标题
            if (looksLikeChapterReference(t)) continue
            if (patterns.any { it.matches(t) }) out += i
        }
        // 过滤明显异常：相邻标题间隔过小（<40 字符）说明可能是误判的正文
        if (out.size < 2) return out
        val filtered = ArrayList<Int>()
        var lastPos = -1_000_000
        var lastLine = -1
        for (idx in out) {
            val gap = lines.subList((lastLine + 1).coerceAtLeast(0), idx).sumOf { it.length }
            if (lastLine < 0 || gap >= 40) {
                filtered += idx
                lastLine = idx
                lastPos = idx
            }
        }
        return filtered.ifEmpty { out }
    }

    /**
     * 判断这一行是不是「正文里引用某一章」而不是章节标题。
     *
     * 中文：「第一章 初见」是标题；「第一章里提到的那个名字」是在**谈论**某一章。
     * 区别就在「第X章」紧跟着的那个字：跟着「的 / 里 / 中 / 是 / 有 / 说 …」这类连接词，
     * 基本可以断定是正文引用。真标题后面通常直接接标题词（或一个空格）。
     *
     * 英文同理：「Chapter 1 is where…」是正文；靠的是编号后面那个词是不是 is/was/of 这类
     * 功能词（[EN_REFERENCE_WORDS]）。`Chapter 1: The Beginning` 以冒号开头，属于标题，
     * 所以这里**不能**把整个「Chapter 1: xxx」当标记，只能看到编号为止。
     *
     * 注意：只用来**否决**候选，不影响其它判定；正常的「第X章 xxx」「Chapter 1 xxx」不会被误伤。
     */
    private fun looksLikeChapterReference(line: String): Boolean {
        chapterMarkerRegex.find(line)?.let { m ->
            // 关键：看标记**紧挨着的下一个字**，不做 trim。
            // 「第一章里提到的…」= 标记后面直接粘着连接词，是正文引用；
            // 「第一話 始まりの朝」「第一章 初见」= 后面是空格，是真标题
            // （否则日文标题里以「始/後/前…」开头的词会被当成连接词误杀）。
            val next = line.getOrNull(m.range.last + 1) ?: return false
            return next in REFERENCE_CONNECTORS ||
                next == '\u2018' || next == '\u201C' || next == '「' || next == '“' || next == '"'
        }
        enMarkerRegex.find(line)?.let { m ->
            val rest = line.substring(m.range.last + 1).trimStart()
            if (rest.isEmpty()) return false
            if (rest[0] == ':' || rest[0] == '：') return false
            val word = rest.takeWhile { it.isLetter() }.lowercase()
            return word in EN_REFERENCE_WORDS
        }
        return false
    }

    /** 「第X章」这类标记（只要标记本身，不含后面的标题词） */
    private val chapterMarkerRegex =
        Regex("第\\s*[$KANJI_DIGITS]{1,10}\\s*[$CH_MARKS]")

    /** 英文标记 + 编号：只到编号为止，后面的 `:` 属于标题内容，不算「在引用某章」 */
    private val enMarkerRegex =
        Regex("(?i)\\b(?:chapter|part|book|volume|vol|episode|ep)\\.?\\s*($NUM)")

    /**
     * 英文标记后面紧跟这些词 → 多半是正文在引用某一章：
     * of/is/was/were/are/has/have/had/in/to/and/or/that/which/for/from/by/as/at/on/with/
     * will/would/can/could/should/because/since/while/when/where/than/not/it
     *
     * 刻意**不含 the** —— 「Chapter 1 The Beginning」是合法标题。
     */
    private val EN_REFERENCE_WORDS = setOf(
        "of", "is", "was", "were", "are", "has", "have", "had", "in", "to", "and", "or",
        "that", "which", "for", "from", "by", "as", "at", "on", "with", "will", "would",
        "can", "could", "should", "because", "since", "while", "when", "where", "than",
        "not", "it"
    )

    /**
     * 「第X章」后面紧跟这些字 → 多半是正文在引用某一章：
     * 的/里/中/是/有/说/讲/提/写/描/介/在/和/与/前/后/时/末/开/结/对/从/把/被/让/使/令/就/都/还/也/又/只要/会/能
     */
    private const val REFERENCE_CONNECTORS =
        "的里中内后前末始结尾时是有在写着说讲提描介对和与及或当从把被让使令就都还也又只要会能"

    private fun splitBySize(text: String, baseTitle: String): List<Part> {
        val paragraphs = toParagraphs(text)
        if (paragraphs.size <= 1 && text.length <= FALLBACK_CHARS) {
            return listOf(Part(baseTitle, text))
        }
        val parts = ArrayList<Part>()
        val buf = ArrayList<String>()
        var size = 0
        var n = 1
        fun flush() {
            if (buf.isEmpty()) return
            parts += Part("第 $n 节", joinParagraphs(buf))
            buf.clear()
            size = 0
            n++
        }
        for (p in paragraphs) {
            buf += p
            size += p.length + 2
            if (size >= FALLBACK_CHARS) flush()
        }
        flush()
        if (parts.isEmpty()) parts += Part(baseTitle, text)
        return parts
    }

    /** 合并导入时，用文件名生成一个章节标题 */
    fun titleFromFileName(name: String): String {
        val base = name.substringAfterLast('/').substringBeforeLast('.')
        return base.trim().ifEmpty { "未命名" }
    }

    /**
     * 从文件名里抠出「第几」用于排序。
     *
     * 多文件导入时，文件是系统选择器给的顺序（往往是乱的），而文件名通常已经带了章号
     * （「第52章 取舍之间.txt」）。这里把它解析成整数，导入前据此排序：
     *   1. `第X章/回/节/卷/集/部/篇/幕/折`（X 可以是阿拉伯数字，也可以是中文数字）
     *   2. 开头的阿拉伯数字，如 `003 破境.txt`、`12、威胁.txt`
     *   3. 整个名字就是个数字，如 `28.txt`
     * 解析不出来返回 null（这类文件保持原有相对顺序，排在有章号的后面）。
     */
    fun chapterOrderFromName(name: String): Int? {
        val base = name.substringAfterLast('/').substringBeforeLast('.').trim()
        return chapterOrderFromTitle(base)
    }

    /**
     * 只认「章 / 回 / 卷 / 集 / 部 / 篇 / 幕 / 折」这一级（**不认「节」**），
     * 用来判断一个文件是不是真的按「章」分好的。
     *
     * 为什么要单独一个严格版：解析器把「第X节」也当章节标题，于是一个
     * 「第10章（内含三节）」的文件会被拆成 3 章 —— 而用户要的是「一整章」。
     * 智能解析用这个判断，只有出现**两个以上不同的章号**才算「合集」。
     */
    fun chapterLevelOrderFromTitle(title: String): Int? = orderOf(title, CH_MARKS_STRICT)

    /**
     * 从章节标题里解析排序用的章号（追加章节、自动排序都用它）。
     *
     * · 前置内容（序 / 楔子 / 前言 / Prologue…）→ -1（排在第一章前）
     * · 编号章节（第X章 / 第X話 / Chapter 1 / Part Two / Volume III…）→ 该编号
     * · 开头的阿拉伯数字（`003 破境`、`12、威胁`）→ 该数字
     * · 后置内容（后记 / 尾声 / 番外 / Epilogue / あとがき…）→ 很大的值（排在最后）
     * · 位置不固定的（幕間 / Interlude）或认不出来的 → null：
     *   这类章节不硬排，由排序逻辑**钉在原位**
     */
    fun chapterOrderFromTitle(title: String): Int? = orderOf(title, CH_MARKS)

    /**
     * 阅读页章首页那行蓝色小字「第 N 章」要不要显示。
     *
     * · 标题自带编号（第X章 / 第一話 / Chapter One / Part Two / `003 破境`）→ 不显示：
     *   大标题里已经写着章号了，再补一行「第 N 章」是重复，而且按列表位置算出来的数字还可能是错的。
     * · 序章 / 前言 / 尾声 / 番外 / 幕間 / Prologue / Interlude 这类特殊章节 → 不显示：
     *   它们本来就没有章号，硬套一个「第 1 章」只会让人以为序章就是第一章。
     * · 其余（认不出编号的普通标题）→ 显示，由阅读页用列表位置兜底。
     */
    fun showsOrdinalLabel(title: String): Boolean {
        val t = title.trim()
        if (t.isEmpty()) return true
        if (chapterOrderFromTitle(t) != null) return false
        return !specialTitle.containsMatchIn(t)
    }

    private fun orderOf(title: String, marks: String): Int? {
        val t = title.trim()
        if (t.isEmpty()) return null
        if (frontMatter.containsMatchIn(t)) return ORDER_FRONT_MATTER
        numberedOrder(t, marks)?.let { return it }
        // 开头的阿拉伯 / 全角数字
        leadingNumber.find(t)?.let { m -> halfWidthInt(m.groupValues[1])?.let { return it } }
        // 整个标题就是个数字
        halfWidthInt(t)?.let { return it }
        if (backMatter.containsMatchIn(t)) return ORDER_BACK_MATTER
        return null
    }

    /**
     * 编号章节的排序值：中日「第X章 / 話 / 節 / 巻」与英日
     * 「Chapter / Part / Book / Volume / Episode + 编号」。
     *
     * 编号统一交给 [parseNumberToken] 解析成整数 —— 所以 `Chapter 10` 排在 `Chapter 2` 之后，
     * 而不是按字符串顺序；`Chapter One`、`Volume III` 也一律化成数字。
     */
    private fun numberedOrder(t: String, marks: String): Int? {
        Regex("第\\s*([$KANJI_DIGITS]{1,10})\\s*[$marks]").find(t)?.let { m ->
            parseNumberToken(m.groupValues[1])?.let { return it }
        }
        Regex("(?i)\\b(?:chapter|part|book|volume|vol|episode|ep)\\.?\\s*($NUM)")
            .find(t)?.let { m ->
                parseNumberToken(m.groupValues[1])?.let { return it }
            }
        // 裸卷号：`卷三`、`巻2`（行首的「卷/巻 + 编号」，不带「第」）
        Regex("^\\s*[卷巻]\\s*([$KANJI_DIGITS]{1,10})").find(t)?.let { m ->
            parseNumberToken(m.groupValues[1])?.let { return it }
        }
        return null
    }

    /** 前置内容：位置固定在正文之前，排到第一章前面 */
    private val frontMatter = Regex(
        "^(序|序章|序言|序曲|序説|自序|楔子|引子|前言|题记|引言|开篇|卷首语" +
            "|(?i:prologue|foreword|preface)|まえがき|プロローグ)"
    )

    /** 后置内容：位置固定在正文之后，排到最后 */
    private val backMatter = Regex(
        "^(后记|后序|尾声|终章|结语|番外|附录|外传|終章|後記|あとがき|エピローグ|付録" +
            "|(?i:epilogue|afterword))"
    )

    /** 行首的阿拉伯 / 全角数字编号（`003 破境`、`12、威胁`） */
    private val leadingNumber = Regex("^\\s*([0-9０-９]{1,6})\\s*[\\s._\\-、,，:：]")

    /**
     * 统一编号解析：阿拉伯 / 全角数字 → 罗马数字（严格校验）→ 英文数字单词 → 汉字数字。
     * 转不了返回 null，调用方据此回退到「钉原位」。
     */
    private fun parseNumberToken(raw: String): Int? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        halfWidthInt(s)?.let { return it }
        romanToInt(s)?.let { return it }
        englishToInt(s)?.let { return it }
        return chineseToInt(s)
    }

    /** 阿拉伯数字转整数，顺带把全角数字折成半角 */
    private fun halfWidthInt(s: String): Int? {
        if (s.isEmpty()) return null
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in '０'..'９') '0' + (c - '０') else c)
        return sb.toString().toIntOrNull()
    }

    /**
     * 罗马数字 → 整数。用严格校验（[ROMAN_STRICT]）拦住 `CIVIC`、`Vivid` 这类
     * 恰好只由罗马字符拼成的普通单词，免得被切出一个假编号。
     */
    private fun romanToInt(s: String): Int? {
        if (!ROMAN_STRICT.matches(s)) return null
        var total = 0
        for (i in s.indices) {
            val v = romanValue(s[i]) ?: return null
            val next = if (i + 1 < s.length) romanValue(s[i + 1]) ?: 0 else 0
            total += if (v < next) -v else v
        }
        return total.takeIf { it > 0 }
    }

    private fun romanValue(c: Char): Int? = when (c.uppercaseChar()) {
        'I' -> 1
        'V' -> 5
        'X' -> 10
        'L' -> 50
        'C' -> 100
        'D' -> 500
        'M' -> 1000
        else -> null
    }

    /** 英文数字单词 → 整数，支持 `twenty-one`、`one hundred and five` */
    private fun englishToInt(s: String): Int? {
        val words = s.lowercase().split(Regex("[\\s\\-]+"))
            .filter { it.isNotEmpty() && it != "and" }
        if (words.isEmpty()) return null
        var total = 0
        var current = 0
        for (w in words) {
            val v = EN_WORD_VALUE[w] ?: return null
            when {
                v == 100 -> current = (if (current == 0) 1 else current) * 100
                v >= 1000 -> {
                    total += (if (current == 0) 1 else current) * v
                    current = 0
                }

                else -> current += v
            }
        }
        val n = total + current
        return n.takeIf { it > 0 }
    }

    /** 严格罗马数字：`MCMXCIX` 合法，`CIVIC` 这类普通单词不合法 */
    private val ROMAN_STRICT =
        Regex("(?i)^M{0,4}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})$")

    /** 英文数字单词表（一～十九、整十、百、千） */
    private val EN_WORD_VALUE = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
        "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80,
        "ninety" to 90, "hundred" to 100, "thousand" to 1000
    )

    /** 前置内容（序、楔子…）的排序值 */
    const val ORDER_FRONT_MATTER = -1

    /** 后置内容（后记、番外…）的排序值 */
    const val ORDER_BACK_MATTER = 2_000_000

    /** 中文数字转整数，支持「二十三」「一百零五」「十」及大写「壹贰…拾佰仟」；转不了返回 null */
    private fun chineseToInt(s: String): Int? {
        if (s.isEmpty()) return null
        val digits = mapOf(
            '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
            '壹' to 1, '贰' to 2, '叁' to 3, '肆' to 4, '伍' to 5, '陆' to 6,
            '柒' to 7, '捌' to 8, '玖' to 9
        )
        val units = mapOf(
            '十' to 10, '百' to 100, '千' to 1000,
            '拾' to 10, '佰' to 100, '仟' to 1000
        )
        var total = 0
        var section = 0
        var number = 0
        for (c in s) {
            when {
                digits.containsKey(c) -> number = digits.getValue(c)
                units.containsKey(c) -> {
                    val u = units.getValue(c)
                    // 「十三」= 10 + 3，所以十前面没数字时按 1 算
                    section += (if (number == 0) 1 else number) * u
                    number = 0
                }

                else -> return null
            }
        }
        total += section + number
        return total.takeIf { it > 0 }
    }
}
