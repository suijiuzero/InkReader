package com.inkreader.parse

import com.inkreader.data.model.SplitMode
import com.inkreader.data.model.SplitOptions
import java.text.Normalizer

/**
 * 智能章节切分引擎。
 *
 * 兼具四大增强能力：
 *  1) 同族模式投票（Pattern Family Voting）：按标题形态分族投票，防跨模式误切（如「第X章」主模式书中出现「第三节 刑事责任」法条引用）；
 *  2) 章节序号连续性校验（Sequence Continuity Outlier Rejection）：基于前后章号连续性剔除正文对话/引用中的离群章号（如 第12章 → 第100章 → 第13章）；
 *  3) 无规则奇葩排版的自动归纳（High-Frequency Short-Line Prefix Auto-Induction）：强弱正则均未命中时，自动统计高频短行前缀（如 `◆ 01`、`§ 1`、`Part 1`）提取本书专属切分规则；
 *  4) 二级「卷 → 章」提取与标题/正文水印净网清洗：识别「第X卷」归属、剥离标题尾部求票括注与正文中夹杂的网址水印行。
 */
object ChapterSplitter {

    data class Part(
        val title: String,
        val content: String,
        val volume: String = ""
    )

    // ---------------------------------------------------------------- 编号与规则片段

    /** 阿拉伯 / 全角数字 + 中日通用汉字数字（含大写壹贰…） */
    private const val KANJI_DIGITS = "0-9０-９零〇一二三四五六七八九十百千万萬两兩壹贰貳叁參肆伍陆陸柒捌玖拾佰仟"
    private const val SPACED_CN_NUMBER = "[$KANJI_DIGITS](?:\\s*[$KANJI_DIGITS]){0,9}"

    /** 罗马数字用到的字符 */
    private const val ROMAN_CHARS = "IVXLCDMivxlcdm"

    /** 英文数字单词（Chapter One / Part Twenty / Volume One Hundred） */
    private const val EN_WORDS =
        "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|" +
            "fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|" +
            "seventy|eighty|ninety|hundred|thousand|and"

    /**
     * 统一的「编号」片段：阿拉伯 / 全角 / 汉字数字、罗马数字、英文数字单词。
     */
    private const val NUM =
        "(?:[$KANJI_DIGITS]{1,10}" +
            "|[$ROMAN_CHARS]{1,15}(?![A-Za-z])" +
            "|(?i:(?:$EN_WORDS)(?:[\\s-]+(?:$EN_WORDS)){0,4})(?![A-Za-z]))"

    /** 章级标记词：中日通用（節/话/話/巻/編 都认；节/話 是同一层级的不同写法） */
    private const val CH_MARKS = "章回節节卷巻集部篇編编幕折話话"

    /** 章级标记（严格版）：**不含「节 / 節」**，判断文件是否真的按章排好时用 */
    private const val CH_MARKS_STRICT = "章回卷巻集部篇編编幕折話话"

    /** 只保留「章 / 回 / 話 / 话」这一层（最细的分章单位） */
    private const val CH_MARKS_CHAP = "章回話话"

    /** 特殊章节用词（序章 / 终章 / 番外 / 幕間 / Prologue / Interlude…）：位置固定，但没有章号 */
    private const val FRONT_WORDS =
        "序章|序言|序曲|序説|自序|楔子|引子|前言|引言|题记|題記|开篇|卷首语|序" +
            "|prologue|foreword|preface|まえがき|プロローグ"
    private const val BACK_WORDS =
        "最终章|最終章|最终话|最終話|最终回|最終回|终章|終章|终幕|終幕|终话|終話" +
            "|大结局|大結局|结局篇|結局篇|完结篇|完結篇|结语|結語|尾声|尾聲" +
            "|后记|後記|后序|後序|完本感言|完结感言|番外篇|番外編|番外|外传|外傳|外伝" +
            "|后日谈|後日談|附录|附錄|付録|epilogue|afterword|エピローグ|あとがき"
    private const val SPECIAL_WORDS = FRONT_WORDS + "|" + BACK_WORDS +
        "|interlude|幕間|幕间|间章|間章|插章|作者的话|内容提要|目录"

    /** 完整匹配，括号成对；编号只属于特殊章，不能被误当正文章号排序。 */
    private fun specialPattern(words: String): Regex {
        val brackets = listOf("（" to "）", "(" to ")", "【" to "】", "[" to "]", "「" to "」", "『" to "』")
        val bracketSuffix = brackets.joinToString("|") { (open, close) ->
            Regex.escape(open) + "[^（）()【】\\[\\]「」『』\\r\\n]{1,24}" + Regex.escape(close)
        }
        val label = "(?i:$words)(?:\\s*(?:第\\s*[$KANJI_DIGITS]{1,6}\\s*[章回话話篇節节]|[$KANJI_DIGITS]{1,6}))?" +
            "(?:[上中下](?:篇|部|編|编))?"
        val textStart = "[^（）()【】\\[\\]「」『』\\s\\r\\n]"
        val subtitle = "(?:\\s*[:：—–\\-·]\\s*$textStart[^\\r\\n]{0,23}|\\s+$textStart[^\\r\\n]{0,23})?"
        val core = "$label(?:\\s*(?:$bracketSuffix)){0,2}$subtitle"
        val wrapped = brackets.joinToString("|") { (open, close) ->
            val tail = if (open == "【" || open == "[") "(?:\\s*$textStart[^\\r\\n]{0,23})?" else subtitle
            Regex.escape(open) + "\\s*$core\\s*" + Regex.escape(close) + tail
        }
        return Regex("^\\s{0,6}(?:$core|$wrapped)\\s*$")
    }

    /** 特殊章节标题（中 / 日 / 英）：位置明确但不是「编号」的那一类 */
    private val specialTitle = specialPattern(SPECIAL_WORDS)

    private const val EN_CHAPTER_WORDS = "chapter|chap|ch|episode|ep"
    private const val EN_ALL_WORDS = "$EN_CHAPTER_WORDS|part|book|volume|vol"
    private const val EN_NUMBER = "\\.?\\s*(?:(?i:no)\\.?\\s*|#\\s*)?"

    /** 没有「第」的章号必须有边界，避免把「三章小说」当成标题。 */
    private fun cnPattern(marks: String) = Regex(
        "^\\s{0,6}(?:第\\s*[$KANJI_DIGITS]{1,10}\\s*[$marks]|" +
            "[$KANJI_DIGITS]{1,10}\\s*[$marks](?=\\s|[:：.、（(【\\[]|$))\\s*(.{0,40})$"
    )

    // 中 / 日：第X章・第X話・第X節・第X巻（阿拉伯、全角、汉字数字都认）；含「节 / 節」
    private val cnTitle =
        cnPattern(CH_MARKS)

    // 章级标题（严格版）：**不含「节 / 節」**，「第X节」是章内小节而不是一章
    private val cnChapterTitle =
        cnPattern(CH_MARKS_STRICT)

    // 只认「章 / 回 / 話 / 话」这一层（把「卷 / 部 / 篇 / 集 / 幕 / 折」这类上级划分排除在外）
    private val cnChapOnlyTitle =
        cnPattern(CH_MARKS_CHAP)

    /** 只认「节 / 節」这一层 */
    private val cnSectionOnlyTitle =
        cnPattern("節节")

    private fun labeledTitle(marks: String) = Regex(
        "^\\s{0,6}(?:[A-Za-z]{1,8}|[正文免费收费公众订阅VIP上架]{1,6})(?:\\s+|\\s*[:：]\\s*)" +
            "第\\s*[$KANJI_DIGITS]{1,10}\\s*[$marks]\\s*(.{0,40})$"
    )

    private fun bracketTitle(marks: String): Regex {
        val marker = "\\s*第\\s*[$KANJI_DIGITS]{1,10}\\s*[$marks]\\s*"
        val pairs = listOf("（" to "）", "(" to ")", "【" to "】", "[" to "]")
        val wrapped = pairs.joinToString("|") { (open, close) -> Regex.escape(open) + marker + Regex.escape(close) }
        return Regex("^\\s{0,6}(?:$wrapped)\\s*(.{0,40})$")
    }

    /** 松：含「节 / 卷」。只在「全文没有两个以上章级标题」时才轮得到它 */
    private val cnLabeledTitle = labeledTitle(CH_MARKS)
    private val cnBracketTitle = bracketTitle(CH_MARKS)

    /** 章级：不含「节 / 節」，但含「卷 / 部 / 篇」 */
    private val cnLabeledTitleStrict = labeledTitle(CH_MARKS_STRICT)
    private val cnBracketTitleStrict = bracketTitle(CH_MARKS_STRICT)

    /** 只认「章 / 回 / 話 / 话」 */
    private val cnLabeledTitleChap = labeledTitle(CH_MARKS_CHAP)
    private val cnBracketTitleChap = bracketTitle(CH_MARKS_CHAP)

    /**
     * 「上级划分标题」：`第二卷 惊雷`、`第三部 风云`、`巻2 風の章`、`卷三 风起`
     * （卷 / 巻 / 集 / 部 / 篇 / 編 / 编 / 幕 / 折）。
     */
    private val upperTitle = Regex(
        "^\\s{0,6}(?:第\\s*[$KANJI_DIGITS]{1,10}\\s*[卷巻集部篇編编幕折]" +
            "|[卷巻]\\s*[$KANJI_DIGITS]{1,10}" +
            "|[上中下前后後][卷部篇編编](?=\\s|[:：]|$)" +
            "|(?i:volume|vol|book)\\.?\\s*$NUM)\\s*(.{0,40})$"
    )

    // 英 / 日：Chapter / Part / Book / Volume / Episode + 编号
    private val enTitle =
        Regex("^\\s{0,6}(?i:$EN_ALL_WORDS)$EN_NUMBER$NUM\\s*[:：.、\\-–—]?\\s*(.{0,50})$")

    /** 仅认英文 Chapter / Episode（不含 Part / Volume / Book） */
    private val enChapOnlyTitle =
        Regex("^\\s{0,6}(?i:$EN_CHAPTER_WORDS)$EN_NUMBER$NUM\\s*[:：.、\\-–—]?\\s*(.{0,50})$")

    /** 强模式：包含章、卷、节、特殊章 */
    private val strong =
        listOf(cnTitle, cnLabeledTitle, cnBracketTitle, enTitle, specialTitle, upperTitle)

    /** 章级强模式（**不含「节 / 節」**） */
    private val strongChapter =
        listOf(cnChapterTitle, cnLabeledTitleStrict, cnBracketTitleStrict, enTitle, specialTitle, upperTitle)

    /** 只认「章 / 回 / 話」一级，把「卷 / 部 / 篇 / 集」这类上级标题排除在外 */
    private val strongChapOnly =
        listOf(cnChapOnlyTitle, cnLabeledTitleChap, cnBracketTitleChap, enChapOnlyTitle, specialTitle)

    private val headingWrappers = listOf("【" to "】", "[" to "]", "(" to ")", "「" to "」", "『" to "』")
    private val headingDecor = Regex("^[★☆◆◇※]{1,3}\\s*(.+?)(?:\\s*[★☆◆◇※]{1,3})?$")

    internal fun isNumberedChapterTitle(raw: String): Boolean {
        val title = headingText(raw)
        return title.length in 1..44 && TITLE_FORBIDDEN_END.indexOf(title.last()) < 0 &&
            !looksLikeChapterReference(title) &&
            listOf(cnChapOnlyTitle, cnLabeledTitleChap, cnBracketTitleChap, enChapOnlyTitle).any { it.matches(title) }
    }

    internal fun isBookendTitle(raw: String): Boolean {
        val title = headingText(raw)
        return title.length in 1..44 && TITLE_FORBIDDEN_END.indexOf(title.last()) < 0 &&
            (frontMatter.matches(title) || backMatter.matches(title))
    }

    /** 仅识别时规范化，标题和正文仍以原文保存；弱数字列表不做去括号处理。 */
    private fun headingText(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length > 96) return trimmed
        var text = Normalizer.normalize(trimmed, Normalizer.Form.NFKC)
        repeat(2) {
            headingWrappers.firstOrNull { (open, close) -> text.startsWith(open) && text.endsWith(close) }?.let { (open, close) ->
                val inner = text.substring(open.length, text.length - close.length).trim()
                if (strong.any { it.matches(inner) }) text = inner
            }
            headingDecor.matchEntire(text)?.groupValues?.get(1)?.trim()?.let { inner ->
                if (strong.any { it.matches(inner) }) text = inner
            }
        }
        return text
    }

    /** 弱模式：数字编号，容易和正文里的数字行冲突，需配合密度判断 */
    private val weak = listOf(
        Regex("^\\s{0,6}[0-9０-９]{1,4}\\s*[、.．，,：:]\\s*\\S.{0,36}$"),
        Regex("^\\s{0,6}[（(]\\s*[0-9０-９]{1,4}\\s*[)）]\\s*\\S?.{0,28}$"),
        Regex("^\\s{0,6}[0-9０-９]{1,4}\\s+\\S.{0,36}$"),
        // 中文序号：`一、xxx`、`（一）xxx`、`十一、xxx`（社科 / 散文集常用）
        Regex("^\\s{0,6}[$KANJI_DIGITS]{1,3}\\s*[、.．，,：:]\\s*\\S.{0,36}$"),
        Regex("^\\s{0,6}[（(]\\s*[$KANJI_DIGITS]{1,3}\\s*[)）]\\s*\\S?.{0,28}$"),
        Regex("^\\s{0,6}[卷巻]\\s*[$KANJI_DIGITS]{1,10}\\s*(.{0,30})$")
    )

    private const val SENT_END = "。！？；…”』」）) "
    private const val TITLE_FORBIDDEN_END = "。！？，；："
    private const val SENTENCE_PUNCT = "。！？；，、："

    private const val FALLBACK_CHARS = 18000
    private const val MAX_CHAPTER_CHARS = 120000

    /** 文首目录区：候选行之间除标题外的正文少于这么多字，就算「密集成簇」 */
    private const val TOC_GAP_CHARS = 30

    /** 文首目录区至少这么多行才算目录（更短的一簇更像正常短章） */
    private const val TOC_MIN_LINES = 4

    // ---------------------------------------------------------------- 水印与标题去噪规则

    /**
     * 章节标题尾部的求票 / 加更 / 字数括注：
     * 例：`第一章 风起（求月票！第一更）`、`第12章 破境【3000字大章】`、`第三章 决战 (求收藏求推荐票)`。
     * 只剥离含典型网文求票/加更词的尾部括号，绝不误伤 `（上）`、`（下）`、`（1）`、`（一）` 等正经章节序号。
     */
    private val TITLE_TAIL_NOISE = Regex(
        "(\\s*[（(【\\[](?:[^)）】\\]]*?(?:求月票|求收藏|求推荐|求订阅|求打赏|求追读|求鲜花|求评价|" +
            "第一更|第二更|第三更|第四更|第五更|一更|二更|三更|四更|五更|加更|补更|保底|二合一|三合一|" +
            "[0-9０-９一二三四五六七八九十百千]+\\s*字|大章|修错字|捉虫|改错|免费|VIP|公众版)[^)）】\\]]*?)[)）】\\]]\\s*)+$",
        RegexOption.IGNORE_CASE
    )

    /**
     * 正文里夹杂的独立网址 / 小说站水印行：
     * 例：`=== 某某小说网 www.xxx.com ===`、`手机阅读请访问 http://...`、`本书由 xxx 首发，请勿转载`。
     */
    private val BODY_WATERMARK_LINE = Regex(
        "^\\s*(?:[=\\-*~—_★☆◆◇※#]{2,}.*[=\\-*~—_★☆◆◇※#]{2,}|" +
            ".*(?:https?://|www\\.|[a-zA-Z0-9_-]+\\.(?:com|net|org|cc|la|me|tw|uk|info|xyz|top|vip))" +
            ".*(?:小说|阅读|首发|更新|最新章节|下载|整理|手打|转载|书友|论坛|最快|无弹窗)?.*|" +
            ".*(?:最新章节请访问|手机阅读请访问|本书由.*首发|请记住本站域名|一秒记住.*为您提供|天才一秒记住|未完待续.*求月票).*)" +
            "\\s*$",
        RegexOption.IGNORE_CASE
    )

    /** 清洗章节标题尾部的求票括注与首尾多余装饰符 */
    fun cleanChapterTitle(rawTitle: String): String {
        var t = rawTitle.trim()
        if (t.isEmpty()) return t
        val stripped = TITLE_TAIL_NOISE.replace(t, "").trim()
        if (stripped.isNotEmpty()) t = stripped
        return t
    }

    /** 剥离正文中夹杂的独立小说网网址与求票水印行 */
    fun cleanBodyWatermarks(rawBody: String): String {
        if (rawBody.isEmpty()) return rawBody
        val lines = rawBody.split("\n")
        val kept = lines.filterNot { line ->
            val t = line.trim()
            t.isNotEmpty() && t.length <= 90 && isWatermarkLine(t)
        }
        return kept.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun isWatermarkLine(trimmed: String): Boolean {
        if (upperTitle.matches(trimmed) || strong.any { it.matches(trimmed) }) return false
        val hasUrl = Regex("(?i)(?:https?://|www\\.|\\b[a-z0-9_-]+\\.(?:com|net|org|cc|la|tw|xyz|top|vip)\\b)")
            .containsMatchIn(trimmed)
        val adKeywords = listOf(
            "最新章节", "手机阅读", "本站域名", "一秒记住", "无弹窗", "小说网",
            "手打吧", "txt下载", "电子书下载", "求月票", "求订阅", "求收藏", "加更规则"
        )
        if (hasUrl && (trimmed.startsWith("手机阅读请访问") || trimmed.startsWith("最新章节请访问") ||
                trimmed.startsWith("请记住本站域名"))) return true
        val wrappedByDecor = (trimmed.startsWith("===") && trimmed.endsWith("===")) ||
            (trimmed.startsWith("---") && trimmed.endsWith("---")) ||
            (trimmed.startsWith("***") && trimmed.endsWith("***"))
        if (wrappedByDecor && (hasUrl || adKeywords.any { trimmed.contains(it, ignoreCase = true) })) {
            return true
        }
        if (trimmed.length <= 45 && adKeywords.count { trimmed.contains(it, ignoreCase = true) } >= 2) {
            return true
        }
        return false
    }

    /** 判断一行是否为「第X卷 / 第X部 / Volume X」上级分卷标题，是则返回规范化卷名 */
    fun extractVolumeTitle(line: String): String? {
        val t = headingText(line)
        if (t.isEmpty() || t.length > 44) return null
        if (TITLE_FORBIDDEN_END.indexOf(t.last()) >= 0) return null
        return if (upperTitle.matches(t)) line.trim() else null
    }

    /** 从章节标题或正文前几行推断其所属分卷名 */
    fun detectVolumeFromChapter(title: String, content: String, existingVolume: String = ""): String {
        if (existingVolume.isNotBlank()) return existingVolume.trim()
        extractVolumeTitle(title)?.let { return it }
        val firstLine = content.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        extractVolumeTitle(firstLine)?.let { return it }
        return ""
    }

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
        // 分章不修改段落内部的换行、缩进或英文单词。
        return text.split(Regex("\\n[ \t]*\\n"))
            .filter { it.isNotEmpty() }.ifEmpty { listOf(text) }
    }

    /** 把段落拼成正文，段间空一行 */
    fun joinParagraphs(paragraphs: List<String>): String = paragraphs.joinToString("\n\n")

    /**
     * 主切分入口（支持 [SplitOptions] 策略、自定义规则与净网开关；默认保持向后兼容）。
     */
    fun split(
        text: String,
        fallbackTitle: String = "正文",
        options: SplitOptions = SplitOptions(mode = SplitMode.SMART, cleanWatermark = false)
    ): List<Part> {
        if (text.isBlank()) return listOf(Part(fallbackTitle, ""))

        // 1. 特殊切分档位：整篇作为一章 / 固定字数切分
        if (options.mode == SplitMode.PER_FILE) {
            val body = if (options.cleanWatermark) cleanBodyWatermarks(text) else text
            val title = if (options.cleanWatermark) cleanChapterTitle(fallbackTitle) else fallbackTitle
            return listOf(Part(title, body))
        }
        if (options.mode == SplitMode.FIXED_SIZE) {
            val cleaned = if (options.cleanWatermark) cleanBodyWatermarks(text) else text
            return splitBySize(cleaned, fallbackTitle, options.fixedChunkChars.coerceIn(2000, 50000))
        }

        val lines = text.split("\n")

        // 2. 自定义前缀或正则表达式切分
        if (options.mode == SplitMode.CUSTOM && options.customPattern.isNotBlank()) {
            val customRegex = buildCustomRegex(options.customPattern.trim())
            val customIdx = customRegex?.let { findTitleLines(lines, listOf(it), contextGated = false, canonical = false) }.orEmpty()
            if (customIdx.isNotEmpty()) {
                return assembleParts(lines, text, customIdx, fallbackTitle, options.cleanWatermark)
            }
            // 自定义规则一行都没命中（或正则本身写不合法）：明确按固定字数兜底，
            // 不要悄悄滑进 SMART 自动管线 —— 用户显式指定了规则，却拿到完全另一套结果，
            // 会被当成「规则没生效」的 bug，而且很难察觉。
            return splitBySize(
                if (options.cleanWatermark) cleanBodyWatermarks(text) else text,
                fallbackTitle
            )
        }

        // 3. 按档位挑选候选标题行
        val chosen: List<Int> = when (options.mode) {
            SplitMode.STRICT_CHAP -> {
                val rawChap = findTitleLines(lines, strongChapOnly, contextGated = false)
                val voted = filterByDominantFamily(lines, rawChap)
                val cleaned = filterSequenceOutliers(lines, voted)
                if (cleaned.isNotEmpty()) cleaned
                else return splitBySize(
                    if (options.cleanWatermark) cleanBodyWatermarks(text) else text,
                    fallbackTitle
                )
            }

            SplitMode.NUMBERED -> {
                val rawWeak = findTitleLines(lines, weak, contextGated = true)
                val rawChap = findTitleLines(lines, strongChapOnly, contextGated = false)
                val combo = (rawChap + rawWeak).sorted().distinct()
                val cleaned = filterSequenceOutliers(lines, combo)
                if (cleaned.size >= 2) cleaned
                else return splitBySize(
                    if (options.cleanWatermark) cleanBodyWatermarks(text) else text,
                    fallbackTitle
                )
            }

            else -> {
                // SMART / IN_FILE 默认智能多级管线：
                // 强模式（整行就是一个「第X章 …」）特征足够强，不强制要求上下有空行；
                // 只有弱模式（`1.`、`一、` 这类容易和正文列表撞车的写法）才需要上下文把关。
                val strongIdx = findTitleLines(lines, strong, contextGated = false)
                val chapOnlyIdx = findTitleLines(lines, strongChapOnly, contextGated = false)
                val chapterIdx = findTitleLines(lines, strongChapter, contextGated = false)

                val primaryCandidates: List<Int> = when {
                    chapOnlyIdx.size >= 2 -> {
                        val voted = filterByDominantFamily(lines, chapOnlyIdx)
                        filterSequenceOutliers(lines, voted)
                    }
                    chapterIdx.size >= 2 -> {
                        val voted = filterByDominantFamily(lines, chapterIdx)
                        filterSequenceOutliers(lines, voted)
                    }
                    strongIdx.size >= 2 -> {
                        val voted = filterByDominantFamily(lines, strongIdx)
                        filterSequenceOutliers(lines, voted)
                    }
                    else -> emptyList()
                }

                if (primaryCandidates.size >= 2) {
                    primaryCandidates
                } else {
                    // 退到弱模式：弱候选要过上下文 + 密度两道关，再过同族投票与序号连续性校验
                    val weakOnly = findTitleLines(lines, weak, contextGated = true)
                    val combo = (strongIdx + weakOnly).sorted().distinct()
                    val avgGap = if (combo.size > 1) text.length.toFloat() / combo.size else Float.MAX_VALUE
                    if (combo.size >= 3 && avgGap >= 300) {
                        filterSequenceOutliers(lines, combo)
                    } else {
                        // 第三道自学习防线：无规则奇葩排版的自动归纳（如 `◆ 01`、`§ 1`、`★ 01`）
                        val induced = induceCustomPrefixLines(lines, text.length)
                        if (induced.size >= 3) {
                            induced
                        } else if (strongIdx.size == 1) {
                            strongIdx
                        } else {
                            return splitBySize(
                                if (options.cleanWatermark) cleanBodyWatermarks(text) else text,
                                fallbackTitle
                            )
                        }
                    }
                }
            }
        }

        if (chosen.isEmpty()) {
            return splitBySize(
                if (options.cleanWatermark) cleanBodyWatermarks(text) else text,
                fallbackTitle
            )
        }
        return assembleParts(lines, text, recoverNumberGaps(lines, chosen), fallbackTitle, options.cleanWatermark)
    }

    /** 把用户输入的自定义前缀或正则表达式转成行级匹配 Regex */
    private fun buildCustomRegex(pattern: String): Regex? {
        if (pattern.isEmpty()) return null
        val looksLikeRegex = pattern.any { it in "^$*+?()[]{}|\\" }
        return runCatching {
            if (looksLikeRegex) {
                val p = if (pattern.startsWith("^")) pattern else "^\\s{0,6}(?:$pattern).*$"
                Regex(p, RegexOption.IGNORE_CASE)
            } else {
                val escaped = Regex.escape(pattern)
                // 字面前缀：行首是这个字面量即可，后面跟什么都行 ——
                // 标题尾巴上中文、数字、空格、标点都可能出现，不能按「英文数字类字符」收窄。
                // 行长度上限由 findTitleLines 统一把关（>44 字不算标题）。
                Regex("^\\s{0,6}$escaped.{0,36}$", RegexOption.IGNORE_CASE)
            }
        }.getOrNull()
    }

    /**
     * 根据选定的章节标题行下标列表 [chosen] 组装出最终的 [Part] 列表，
     * 同时追踪上级分卷（「第X卷」）并按需执行标题与正文水印净网。
     */
    private fun assembleParts(
        lines: List<String>,
        fullText: String,
        chosen: List<Int>,
        fallbackTitle: String,
        cleanWatermark: Boolean
    ): List<Part> {
        val parts = ArrayList<Part>()

        // 扫描第一个章节标题之前是否已经出现了「第一卷 xxx」
        var activeVolume = ""
        for (k in 0 until chosen.first()) {
            extractVolumeTitle(lines[k])?.let { activeVolume = it }
        }

        // 第一个标题之前的内容作为前言
        val head = lines.subList(0, chosen.first()).joinToString("\n").trim()
        if (head.isNotEmpty() && head.length >= 200) {
            val hTitle = guessHeadTitle(head)
            val hBody = if (cleanWatermark) cleanBodyWatermarks(head) else head
            parts += Part(if (cleanWatermark) cleanChapterTitle(hTitle) else hTitle, hBody, activeVolume)
        }

        // 上一章末尾剥出来的卷标题（`第二卷 惊雷`），挂到这一章的开头当卷首，并更新当前所属卷
        var carryTitles = ""
        for ((i, lineIdx) in chosen.withIndex()) {
            val endIdx = if (i + 1 < chosen.size) chosen[i + 1] else lines.size
            val rawTitle = lines[lineIdx].trim().ifEmpty { "第 ${i + 1} 章" }
            val title = if (cleanWatermark) cleanChapterTitle(rawTitle) else rawTitle

            // 如果标题本身就是分卷名，也同步更新 activeVolume
            extractVolumeTitle(rawTitle)?.let { activeVolume = it }

            val bodyLines = lines.subList(lineIdx + 1, endIdx)
            val hasNext = i + 1 < chosen.size
            val (keptLines, peeled) =
                if (hasNext) peelTrailingUpperTitles(bodyLines) else bodyLines to emptyList()

            var body = keptLines.joinToString("\n").trim()
            if (carryTitles.isNotEmpty()) {
                carryTitles.lineSequence().forEach { l ->
                    extractVolumeTitle(l)?.let { activeVolume = it }
                }
                body = if (body.isEmpty()) carryTitles else "$carryTitles\n\n$body"
            }
            if (i == 0 && head.isNotEmpty() && head.length < 200) {
                body = "$head\n\n$body"
            }
            if (cleanWatermark) {
                body = cleanBodyWatermarks(body)
            }
            if (body.isEmpty()) body = title

            val chapterVolume = activeVolume
            carryTitles = peeled.joinToString("\n")

            // 单章过长时再按字数拆
            if (body.length > MAX_CHAPTER_CHARS) {
                val subs = splitBySize(body, title)
                subs.forEachIndexed { k, p ->
                    parts += Part(
                        title = if (k == 0) title else "$title（${k + 1}）",
                        content = p.content,
                        volume = chapterVolume
                    )
                }
            } else {
                parts += Part(title, body, chapterVolume)
            }
        }
        if (parts.isEmpty()) return splitBySize(fullText, fallbackTitle)
        return parts
    }

    // ---------------------------------------------------------------- 算法层增强 1：同族模式投票

    private enum class HeadingFamily {
        CHAP_CN,       // 第X章 / 第X回 / 第X话
        LABELED_CN,    // 正文 第X章 / VIP 第X章
        BRACKET_CN,    // 【第X章】 / （第X章）
        EN_CHAP,       // Chapter X / Episode X
        SECT_CN,       // 第X节
        UPPER_VOL,     // 第X卷 / 第X部 / Volume X
        SPECIAL,       // 序章 / 楔子 / 番外 / 后记
        OTHER
    }

    private fun classifyFamily(line: String): HeadingFamily {
        val t = headingText(line)
        return when {
            specialTitle.matches(t) -> HeadingFamily.SPECIAL
            cnChapOnlyTitle.matches(t) -> HeadingFamily.CHAP_CN
            cnLabeledTitleChap.matches(t) -> HeadingFamily.LABELED_CN
            cnBracketTitleChap.matches(t) -> HeadingFamily.BRACKET_CN
            enChapOnlyTitle.matches(t) -> HeadingFamily.EN_CHAP
            cnSectionOnlyTitle.matches(t) || labeledTitle("節节").matches(t) || bracketTitle("節节").matches(t) ->
                HeadingFamily.SECT_CN
            upperTitle.matches(t) || enTitle.matches(t) -> HeadingFamily.UPPER_VOL
            else -> HeadingFamily.OTHER
        }
    }

    /**
     * 同族模式投票（防跨模式误伤）：
     * 统计全文候选标题行的主模式族。当章级主模式族（如 `第X章`、`【第X章】`、`正文 第X章`、`Chapter X`）
     * 已经形成稳定规模（≥2 章）时，自动剔除混进来的异族小节（如正文中引用的法条 `第三节 刑事责任`）。
     * `序章 / 番外 / 后记` 等特殊章节不受投票剔除影响。
     */
    private fun filterByDominantFamily(lines: List<String>, candidates: List<Int>): List<Int> {
        if (candidates.size < 2) return candidates
        val families = candidates.map { classifyFamily(lines[it]) }
        val chapFamilyCount = families.count {
            it == HeadingFamily.CHAP_CN ||
                it == HeadingFamily.LABELED_CN ||
                it == HeadingFamily.BRACKET_CN ||
                it == HeadingFamily.EN_CHAP
        }
        if (chapFamilyCount >= 2) {
            val filtered = candidates.filterIndexed { idx, _ ->
                val f = families[idx]
                f != HeadingFamily.SECT_CN
            }
            if (filtered.size >= 2) return filtered
        }
        return candidates
    }

    // ---------------------------------------------------------------- 算法层增强 2：序号连续性离群剔除

    /**
     * 章节序号连续性校验（防正文对话/引用误判）：
     * 把识别出的章节序号转成整数（`一百零二` → 102）。
     * 若上下文是 `第12章` → `第100章` → `第13章`（前后两章序号连续或单调递进，中间突然冒出一个
     * 远超上下文区间或倒退的离群章号，且距离前后章节标题不太远），则判定为正文台词/引用噪点并剔除。
     *
     * 注意：
     *  · 保留分卷重编号（如 `第20章` → `第1章` → `第2章`，因为 `1` 和后面的 `2` 是连续的！）；
     *  · 保留无章号或特殊章节（`序章`、`番外`）。
     */
    private fun filterSequenceOutliers(lines: List<String>, candidates: List<Int>): List<Int> {
        if (candidates.size < 3) return candidates
        val orders = candidates.map { idx ->
            val o = chapterOrderFromTitle(lines[idx].trim())
            if (o != null && o >= 0 && o < ORDER_BACK_MATTER) o else null
        }

        val keep = BooleanArray(candidates.size) { true }
        val prefix = LongArray(lines.size + 1)
        for (line in lines.indices) prefix[line + 1] = prefix[line] + lines[line].length
        var i = 1
        while (i < candidates.lastIndex) {
            val curr = orders[i]
            val prev = orders[i - 1]
            val next = orders[i + 1]
            if (curr != null && prev != null && next != null) {
                // 前后章本身是顺接的（例如 prev=12, next=13；或 prev=1, next=2），
                // 而中间这个 curr 既不在 [prev, next] 之间，又和 next 不连续（排除分卷从 1 重开的情况）
                val neighborsContinuous = next in prev..(prev + 5) && next >= prev
                val currFitsBetween = curr in prev..next
                val currStartsNewVolume = curr == 1 && next == 2
                if (neighborsContinuous && !currFitsBetween && !currStartsNewVolume) {
                    val jumpFromPrev = kotlin.math.abs(curr - prev)
                    val jumpToNext = kotlin.math.abs(next - curr)
                    val nearby = prefix[candidates[i + 1]] - prefix[candidates[i - 1] + 1] <= 1200
                    val crossesVolume = nearby && (candidates[i - 1] + 1 until candidates[i + 1]).any {
                        extractVolumeTitle(lines[it]) != null
                    }
                    if (jumpFromPrev >= 5 && jumpToNext >= 5 && nearby && !crossesVolume) {
                        keep[i] = false
                        i += 2
                        continue
                    }
                }
            }
            i++
        }
        val out = ArrayList<Int>(candidates.size)
        for (k in candidates.indices) {
            if (keep[k]) out += candidates[k]
        }
        return if (out.size >= 2) out else candidates
    }

    // ---------------------------------------------------------------- 编号缺口局部补查

    private data class ContinuityHeading(val number: Int, val kind: String, val stem: String, val segment: Int)
    private val gapCnMarker = Regex("^(?:第\\s*)?($SPACED_CN_NUMBER)\\s*([章回話话])")
    private val gapEnMarker = Regex("^(?i:$EN_CHAPTER_WORDS)$EN_NUMBER($NUM)")
    private val gapLabel = Regex("^(?:[A-Za-z]{1,8}|[正文免费收费公众订阅VIP上架]{1,6})(?:\\s+|\\s*:\\s*)(?=第)")
    private val gapDecor = Regex("^[★☆◆◇※]{1,3}\\s*|\\s*[★☆◆◇※]{1,3}$")
    private val gapMarkdown = Regex("^#{1,6}\\s+")
    private val numberSpaces = Regex("\\s+")

    /** 只为缺口比较放宽字间空格和包装；不改变全书主识别规则，也不改写原文。 */
    private fun continuityHeading(raw: String, relaxed: Boolean): ContinuityHeading? {
        if (!relaxed && !isNumberedChapterTitle(raw)) return null
        if (raw.trim().length !in 1..80) return null
        var title = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
        if (relaxed && title.first() in "「『“\"") return null
        if (relaxed) title = gapMarkdown.replaceFirst(title, "")
        title = gapDecor.replace(title, "").trim()
        repeat(2) {
            val pair = headingWrappers.firstOrNull { title.startsWith(it.first) }
            if (pair != null) {
                val close = title.indexOf(pair.second, pair.first.length)
                if (close < 0) return null
                val inner = title.substring(pair.first.length, close).trim()
                if (close == title.length - pair.second.length) title = inner
                else if (gapCnMarker.matchEntire(inner) != null || gapEnMarker.matchEntire(inner) != null)
                    title = inner + " " + title.substring(close + pair.second.length).trim()
            }
        }
        title = gapLabel.replaceFirst(title, "")
        if (specialTitle.matches(title)) return null
        val cn = gapCnMarker.find(title)
        val en = if (cn == null) gapEnMarker.find(title) else null
        val match = cn ?: en ?: return null
        val token = if (cn != null) numberSpaces.replace(cn.groupValues[1], "") else match.groupValues[1]
        val number = parseNumberToken(token)?.takeIf { it in 1 until ORDER_BACK_MATTER } ?: return null
        val kind = if (cn != null) cn.groupValues[2].replace('話', '话')
            else if (title.startsWith("ep", ignoreCase = true)) "话" else "章"
        val tail = title.substring(match.range.last + 1)
        val subtitle = tail.trimStart { it.isWhitespace() || it in ":.、—–-" }
        if (cn != null && !title.startsWith("第") && tail.isNotEmpty() &&
            !tail.first().isWhitespace() && tail.first() !in ":.、([") return null
        // 松规则必须比主规则更严格地排除引用和句子，不能只凭章号相符就切开正文。
        if (relaxed && (tail.any { it in "。！？；，!?;\"“”「」『』" } ||
                TITLE_FORBIDDEN_END.indexOf(title.last()) >= 0 ||
                looksLikeChapterReference(if (cn != null) title.replaceRange(cn.groups[1]!!.range, token) else title) ||
                (cn != null && subtitle.firstOrNull()?.let { it in REFERENCE_CONNECTORS } == true) ||
                (en != null && subtitle.takeWhile { it.isLetter() }.lowercase() in EN_REFERENCE_WORDS))) return null
        val normalized = title.replaceRange(match.groups[1]!!.range, number.toString())
        val suffix = segmentSuffix.find(normalized)
        val segment = suffix?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.let { "上中下".indexOf(it) } ?: -1
        val stem = numberSpaces.replace(if (suffix == null) normalized else normalized.removeRange(suffix.range), "")
        return ContinuityHeading(number, kind, stem, segment)
    }

    /**
     * 仅在两个已确认、同类的章号之间补查；上下篇同号不构成缺口。
     * 每段只扫描一次，最多补查 32 个缺号、5 万行、200 万字符；不跨卷或特殊章。
     * 找不到、同号候选有歧义或候选顺序倒置时保留正文，不创建空章或重排内容。
     */
    internal fun recoverNumberGaps(lines: List<String>, chosen: List<Int>): List<Int> {
        if (chosen.size < 2) return chosen
        val recovered = ArrayList<Int>()
        for (pair in chosen.zipWithNext()) {
            val left = continuityHeading(lines[pair.first], relaxed = false) ?: continue
            val right = continuityHeading(lines[pair.second], relaxed = false) ?: continue
            if (left.kind != right.kind || right.number - left.number !in 2..33 || pair.second - pair.first > 50000) continue
            val found = ArrayList<Pair<Int, ContinuityHeading>>()
            val counts = LongArray(pair.second - pair.first + 1)
            var fence: String? = null
            var blocked = false
            var scannedChars = 0L
            for (line in pair.first + 1 until pair.second) {
                val raw = lines[line]
                scannedChars += raw.length.toLong() + 1
                if (scannedChars > 2_000_000) { blocked = true; break }
                val text = raw.trim()
                counts[line - pair.first] = counts[line - pair.first - 1] + text.length
                if (text.startsWith("```") || text.startsWith("~~~")) {
                    val run = text.takeWhile { it == text.first() }
                    if (fence == null) fence = run
                    else if (run.first() == fence.first() && run.length >= fence.length && text == run) fence = null
                    continue
                }
                if (fence != null) continue
                if (extractVolumeTitle(text) != null || specialTitle.matches(headingText(text))) { blocked = true; break }
                if (!lines[line - 1].isBlank() && !lines[line + 1].isBlank()) continue
                val heading = continuityHeading(text, relaxed = true) ?: continue
                if (heading.kind != left.kind) continue
                // 边界编号也要参与顺序检查：12 → 松规则14 → 松规则13 → 14
                // 不能先丢掉缺口外的14，再把孤立的13误当成可靠漏章。
                if (heading.number <= left.number || heading.number >= right.number) { blocked = true; break }
                found += line to heading
            }
            if (blocked || found.isEmpty()) continue
            val unambiguous = found.groupBy { it.second.number }.values.flatMap { group ->
                if (group.size == 1) group
                else if (group.all { it.second.segment >= 0 } && group.map { it.second.stem }.distinct().size == 1 &&
                    group.zipWithNext().all { it.first.second.segment < it.second.second.segment }) group
                else emptyList()
            }.sortedBy { it.first }
            if (unambiguous.zipWithNext().any { it.first.second.number > it.second.second.number }) continue
            unambiguous.forEach { candidate ->
                val index = found.binarySearchBy(candidate.first) { it.first }
                val previous = found.getOrNull(index - 1)?.first ?: pair.first
                val next = found.getOrNull(index + 1)?.first ?: pair.second
                fun bodyChars(start: Int, end: Int) = counts[end - pair.first - 1] - counts[start - pair.first]
                if (bodyChars(previous, candidate.first) >= 40 && bodyChars(candidate.first, next) >= 40)
                    recovered += candidate.first
            }
        }
        return if (recovered.isEmpty()) chosen else (chosen + recovered).sorted()
    }

    // ---------------------------------------------------------------- 算法层增强 3：奇葩前缀自动归纳

    /**
     * 无规则奇葩排版的自动归纳：
     * 当强弱正则均未命中，但全文存在高频重复的独立短行前缀模式（例如每隔几百/几千字出现一行
     * `◆ 01`、`§ 1`、`★ 01`、`#01`、`Part 1`、`【01】`）时，通过统计短行「首符号/词 + 数字」签名，
     * 自动归纳出这本书专属的切分规则，避免直接退化到 1.8 万字硬切。
     */
    private val exoticPrefixRegex = Regex(
        "^\\s{0,4}(?:([◆◇★☆§※●○▲△■□✦✧❖#＃@＠~～=＝\\-*+]{1,4}|[【\\[(（])\\s*([0-9０-９]{1,4}|[$KANJI_DIGITS]{1,6})\\s*[】\\])）]?|" +
            "([A-Za-z]{2,10})\\s+([0-9０-９]{1,4}))(?:\\s*[:：.、\\-–—]?\\s*(.{0,28}))?$"
    )

    private fun induceCustomPrefixLines(lines: List<String>, totalChars: Int): List<Int> {
        if (totalChars < 600 || lines.size < 6) return emptyList()
        val buckets = HashMap<String, MutableList<Int>>()

        for (i in lines.indices) {
            val t = lines[i].trim()
            if (t.length !in 2..34) continue
            if (TITLE_FORBIDDEN_END.indexOf(t.last()) >= 0) continue
            val prevBlank = i == 0 || lines[i - 1].isBlank()
            val nextBlank = i == lines.lastIndex || lines[i + 1].isBlank()
            if (!prevBlank && !nextBlank) continue

            val m = exoticPrefixRegex.matchEntire(t) ?: continue
            val symPrefix = m.groupValues[1]
            val wordPrefix = m.groupValues[3]
            val signature = when {
                symPrefix.isNotEmpty() -> "SYM:${symPrefix.first()}"
                wordPrefix.isNotEmpty() -> "WORD:${wordPrefix.lowercase()}"
                else -> continue
            }
            buckets.getOrPut(signature) { ArrayList() }.add(i)
        }

        val best = buckets.values
            .filter { it.size >= 3 }
            .maxByOrNull { it.size }
            ?: return emptyList()

        // 过滤间距过小的密集行
        val filtered = ArrayList<Int>()
        var lastLine = -1
        for (idx in best) {
            val gap = if (lastLine < 0) Int.MAX_VALUE
            else lines.subList(lastLine + 1, idx).sumOf { it.length }
            if (gap >= 80) {
                filtered += idx
                lastLine = idx
            }
        }
        val avgGap = if (filtered.size >= 3) totalChars.toFloat() / filtered.size else 0f
        return if (filtered.size >= 3 && avgGap >= 120f) filtered else emptyList()
    }

    private fun guessHeadTitle(head: String): String {
        val first = head.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (first.isNotEmpty() && first.length <= 20) first else "前言"
    }

    /**
     * 把一章末尾残留的「上级划分标题」剥出来，交给下一章当卷首行。
     */
    private fun peelTrailingUpperTitles(lines: List<String>): Pair<List<String>, List<String>> {
        var end = lines.size
        val peeled = ArrayList<String>()
        while (end > 0) {
            val t = lines[end - 1].trim()
            if (t.isEmpty()) {
                end--
                continue
            }
            if (extractVolumeTitle(t) == null) break
            peeled.add(0, lines[end - 1])
            end--
        }
        if (peeled.isEmpty() || end == 0) return lines to emptyList()
        return lines.subList(0, end) to peeled
    }

    /**
     * 扫出候选标题行。
     */
    private fun findTitleLines(
        lines: List<String>,
        patterns: List<Regex>,
        contextGated: Boolean,
        canonical: Boolean = true
    ): List<Int> {
        val out = ArrayList<Int>()
        val adjacentWithoutBody = HashSet<Int>()
        for (i in lines.indices) {
            val raw = lines[i]
            val t = if (canonical) headingText(raw) else raw.trim()
            if (t.isEmpty() || t.length > 44) continue
            // 标题行不应以句末标点结尾（那更像正文）
            if (TITLE_FORBIDDEN_END.indexOf(t.last()) >= 0) continue
            // 正文里引用某一章：「第一章里提到的…」「第三章的约定」，不是标题
            if (looksLikeChapterReference(t)) continue
            if (patterns.none { it.matches(t) }) continue

            val prevBlank = i == 0 || lines[i - 1].isBlank()
            val nextBlank = i == lines.lastIndex || lines[i + 1].isBlank()
            val nextLong = run {
                val nx = lines.getOrNull(i + 1)
                nx != null && nx.isNotBlank() && nx.trim().length > 44
            }
            val standalone = prevBlank || nextBlank || nextLong

            if (contextGated) {
                if (!standalone) continue
            } else {
                val nextIsTitle = run {
                    val nx = lines.getOrNull(i + 1)?.trim()
                    nx != null && nx.isNotEmpty() && patterns.any { it.matches(if (canonical) headingText(nx) else nx) }
                }
                if (nextIsTitle && !standalone) adjacentWithoutBody += i
            }
            out += i
        }
        val candidates = dropLeadingToc(lines, out)
        // 先让目录检测看到完整的密集标题簇，再抑制没有正文间隔的相邻候选。
        // 提前丢掉目录中间项，会使目录不足最小行数，最后一条目录项反而被保留。
        if (!contextGated) return candidates.filterNot { it in adjacentWithoutBody }
        // 弱模式再做一次「相邻间隔过小」的过滤；强模式不做 —— 卷标题紧邻章标题是正常写法
        if (!contextGated || candidates.size < 2) return candidates
        val filtered = ArrayList<Int>()
        var lastLine = -1
        for (idx in candidates) {
            val gap = lines.subList((lastLine + 1).coerceAtLeast(0), idx).sumOf { it.length }
            if (lastLine < 0 || gap >= 40) {
                filtered += idx
                lastLine = idx
            }
        }
        return filtered.ifEmpty { candidates }
    }

    /**
     * 文首目录区检测。
     *
     * 允许目录出现在前 45 行以内（兼容书名 + 作者 + 内容简介后面跟目录的常见 TXT 排版）。
     */
    private fun dropLeadingToc(lines: List<String>, candidates: List<Int>): List<Int> {
        if (candidates.size < TOC_MIN_LINES || candidates.first() > 45) return candidates
        var end = 1
        while (end < candidates.size) {
            val gap = lines.subList(candidates[end - 1] + 1, candidates[end])
                .sumOf { it.trim().length }
            if (gap > TOC_GAP_CHARS) break
            end++
        }
        if (end < TOC_MIN_LINES) return candidates

        val seenTitles = HashSet<String>()
        var tocEnd = -1
        for (k in 0 until end) {
            val norm = headingText(cleanChapterTitle(lines[candidates[k]].trim()))
            if (!seenTitles.add(norm)) {
                tocEnd = k
                break
            }
        }
        if (tocEnd < 0) {
            val restTitles = candidates.subList(end, candidates.size)
                .map { headingText(cleanChapterTitle(lines[it].trim())) }
                .toHashSet()
            val hit = candidates.subList(0, end).count {
                headingText(cleanChapterTitle(lines[it].trim())) in restTitles
            }
            if (hit * 2 < end) return candidates
            tocEnd = end
        }
        if (tocEnd < TOC_MIN_LINES - 1) return candidates
        return candidates.subList(tocEnd, candidates.size).toList()
    }

    /**
     * 判断这一行是不是「正文里引用某一章」而不是章节标题。
     */
    private fun looksLikeChapterReference(line: String): Boolean {
        chapterMarkerRegex.find(line)?.let { m ->
            val next = line.getOrNull(m.range.last + 1) ?: return false
            if (next == '「' || next == '“' || next == '"' || next == '\u2018' || next == '\u201C') {
                val t = line.trim()
                return t.length > 24 || SENTENCE_PUNCT.any { it in t }
            }
            val rest = line.substring(m.range.last + 1)
            return REFERENCE_PHRASES.any { rest.startsWith(it) } ||
                (next in REFERENCE_CONNECTORS && SENTENCE_PUNCT.any { it in rest })
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
        Regex("(?:第\\s*[$KANJI_DIGITS]{1,10}\\s*[$CH_MARKS]|^[$KANJI_DIGITS]{1,10}\\s*[$CH_MARKS])")

    /** 英文标记 + 编号：只到编号为止，后面的 `:` 属于标题内容，不算「在引用某章」 */
    private val enMarkerRegex =
        Regex("(?i)\\b(?:$EN_ALL_WORDS)$EN_NUMBER($NUM)")

    private val EN_REFERENCE_WORDS = setOf(
        "of", "is", "was", "were", "are", "has", "have", "had", "in", "to", "and", "or",
        "that", "which", "for", "from", "by", "as", "at", "on", "with", "will", "would",
        "can", "could", "should", "because", "since", "while", "when", "where", "than",
        "not", "it"
    )

    private val REFERENCE_PHRASES = listOf("里提到", "中提到", "的内容", "的约定是", "写着", "所说", "提到的")

    private const val REFERENCE_CONNECTORS =
        "的里中内末结时是有写着说讲提描介对和及或被让使令就都还也又只要能"

    private val FALLBACK_SECTION = Regex("^第\\s*[0-9０-９]+\\s*节$")

    /** 是否是 [splitBySize] 生成的兜底小节标题（导入时用来避免把假标题拼回正文） */
    fun isFallbackSectionTitle(title: String): Boolean = FALLBACK_SECTION.matches(title.trim())

    private fun splitBySize(
        text: String,
        baseTitle: String,
        chunkChars: Int = FALLBACK_CHARS
    ): List<Part> {
        if (text.length <= chunkChars) return listOf(Part(baseTitle, text))
        val parts = ArrayList<Part>()
        var offset = 0
        while (offset < text.length) {
            val hardEnd = (offset + chunkChars).coerceAtMost(text.length)
            var end = hardEnd
            if (hardEnd < text.length) {
                val floor = offset + chunkChars / 2
                val paragraph = text.lastIndexOf("\n\n", hardEnd - 1)
                val line = text.lastIndexOf('\n', hardEnd - 1)
                end = when {
                    paragraph >= floor -> (paragraph + 2).coerceAtMost(hardEnd)
                    line >= floor -> line + 1
                    else -> hardEnd
                }
                if (end > offset && Character.isHighSurrogate(text[end - 1]) &&
                    Character.isLowSurrogate(text[end])) end--
            }
            check(end > offset)
            parts += Part("第 ${parts.size + 1} 节", text.substring(offset, end))
            offset = end
        }
        return parts
    }

    /** 合并导入时，用文件名生成一个章节标题 */
    fun titleFromFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        return base.trim().ifEmpty { "未命名" }
    }

    /**
     * 从文件名里抠出「第几」用于排序。
     */
    fun chapterOrderFromName(name: String): Int? {
        val base = name.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').trim()
        return chapterOrderFromTitle(base)
    }

    internal data class FileOrderKey(val order: Int, val family: String, val segment: Int)
    private val segmentSuffix = Regex("(?:\\(([上中下])(?:篇|部)?\\)|\\[([上中下])(?:篇|部)?\\]|([上中下])(?:篇|部))$")

    internal fun fileOrderKey(name: String): FileOrderKey {
        val base = headingText(titleFromFileName(name))
        val suffix = segmentSuffix.find(base)?.takeIf { isNumberedChapterTitle(base) || isBookendTitle(base) }
        val family = if (suffix != null) base.removeRange(suffix.range).trimEnd() else base
        val segment = suffix?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.let { "上中下".indexOf(it) } ?: -1
        return FileOrderKey(chapterOrderFromName(name) ?: Int.MAX_VALUE, family, segment)
    }

    /**
     * 只认「章 / 回 / 卷 / 集 / 部 / 篇 / 幕 / 折」这一级（**不认「节」**），
     * 用来判断一个文件是不是真的按「章」分好的。
     */
    fun chapterLevelOrderFromTitle(title: String): Int? {
        val canonical = headingText(title)
        if (cnSectionOnlyTitle.matches(canonical) || labeledTitle("節节").matches(canonical) || bracketTitle("節节").matches(canonical)) return null
        return orderOf(title, CH_MARKS_STRICT)
    }

    /**
     * 从章节标题里解析排序用的章号（追加章节、自动排序都用它）。
     */
    fun chapterOrderFromTitle(title: String): Int? = orderOf(title, CH_MARKS)

    /**
     * 检测「分卷重新计号」：第一卷是 `第一 ~ 二十章`，第二卷又从 `第一章` 重新开始。
     *
     * 判据（只看有编号的章，跳过认不出的幕间与序章 / 番外哨兵值）：
     * 后面某一章的章号**比此前最大值小**，并且满足下面任一条 ——
     *  · 这个号此前已经出现过（`[1..20, 1, 2…]`）；
     *  · 它回到了很小的号（≤3）而此前已经数到两位数（`[100..120, 1..20]`）。
     * 两条都是「章号不是全局唯一」的硬证据。
     *
     * 刻意**不**把所有回退都当重计：`[1, 3, 2, 4]` 这种短暂乱序只是顺序错了，
     * 按号重排正好能修正它，不该被这条守护挡掉。
     */
    fun hasChapterNumberReset(orders: List<Int?>): Boolean {
        val seen = HashSet<Int>()
        var max = Int.MIN_VALUE
        for (o in orders) {
            if (o == null || o < 0 || o >= ORDER_BACK_MATTER) continue
            if (o < max && (o in seen || (o <= 3 && max >= 10))) return true
            seen += o
            if (o > max) max = o
        }
        return false
    }

    /**
     * 阅读页章首页那行蓝色小字「第 N 章」要不要显示。
     */
    fun showsOrdinalLabel(title: String): Boolean {
        val t = headingText(title)
        if (t.isEmpty()) return true
        if (chapterOrderFromTitle(t) != null) return false
        return !specialTitle.containsMatchIn(t)
    }

    private fun orderOf(title: String, marks: String): Int? {
        val t = headingText(title)
        if (t.isEmpty()) return null
        val cleaned = cleanChapterTitle(t)
        if (frontMatter.matches(cleaned)) return ORDER_FRONT_MATTER
        if (backMatter.matches(cleaned)) return ORDER_BACK_MATTER
        numberedOrder(t, marks)?.let { return it }
        // 章号区间：`4-6`、`第4~6章` —— 取区间起点当排序值
        rangeNumber.find(t)?.let { m -> halfWidthInt(m.groupValues[1])?.let { return it } }
        // 开头的阿拉伯 / 全角数字
        leadingNumber.find(t)?.let { m -> halfWidthInt(m.groupValues[1])?.let { return it } }
        // 结尾的阿拉伯 / 全角数字：`你好1`、`Part12`（文件名序号最常见的写法）
        trailingNumber.find(t)?.let { m -> halfWidthInt(m.groupValues[1])?.let { return it } }
        // 奇葩前缀数字：`◆ 01`、`§ 12`
        exoticPrefixRegex.matchEntire(t)?.let { m ->
            val token = m.groupValues[2].ifEmpty { m.groupValues[4] }
            parseNumberToken(token)?.let { return it }
        }
        // 整个标题就是个数字
        halfWidthInt(t)?.let { return it }
        return null
    }

    private fun numberedOrder(t: String, marks: String): Int? {
        Regex("第\\s*($SPACED_CN_NUMBER)\\s*[$marks]").find(t)?.let { m ->
            parseNumberToken(m.groupValues[1])?.let { return it }
        }
        Regex("^([$KANJI_DIGITS]{1,10})\\s*[$marks](?=\\s|[:：.、（(【\\[]|$)").find(t)?.let { m ->
            parseNumberToken(m.groupValues[1])?.let { return it }
        }
        Regex("(?i)\\b(?:$EN_ALL_WORDS)$EN_NUMBER($NUM)")
            .find(t)?.let { m ->
                parseNumberToken(m.groupValues[1])?.let { return it }
            }
        // 裸卷号：`卷三`、`巻2`
        Regex("^\\s*[卷巻]\\s*([$KANJI_DIGITS]{1,10})").find(t)?.let { m ->
            parseNumberToken(m.groupValues[1])?.let { return it }
        }
        // 中文序号行首：`一、xxx`、`（十二）xxx`
        Regex("^\\s*[（(]?\\s*([$KANJI_DIGITS]{1,4})\\s*[)）、.．，,：:]").find(t)?.let { m ->
            parseNumberToken(m.groupValues[1])?.let { return it }
        }
        return null
    }

    /** 前置内容：位置固定在正文之前，排到第一章前面 */
    private val frontMatter = specialPattern(FRONT_WORDS)

    /** 后置内容：位置固定在正文之后，排到最后 */
    private val backMatter = specialPattern(BACK_WORDS)

    private val leadingNumber = Regex("^\\s*([0-9０-９]{1,6})\\s*[\\s._\\-、,，:：~～－—―–]")

    /**
     * 结尾的阿拉伯 / 全角数字：`你好1`、`Part12`、`卷2`。
     *
     * 多文件导入时文件名常常就这么起名（一本书拆成「你好1」「你好2」…）。认不出序号会有两个后果：
     *  1. 文件之间的顺序只能听系统选择器的（它给的顺序并不可靠）；
     *  2. 合并导入时会被当成「没编号的文件」，退回按文件**内部**标题分章
     *     —— 于是每个文件都按里面的「第1节」切，最后全是「第1节」。
     */
    private val trailingNumber = Regex("([0-9０-９]{1,6})\\s*$")

    /**
     * 章号区间：`5-7`、`第3-6章`、**`诡秘之主1-4`**（前缀是书名也要认）。
     *
     * 一本书拆成多个文件、名字写成章号区间是常见做法。两个要点：
     *  · 区间**不一定在开头**（前缀常常是书名），所以允许前面有一段**不含数字**的前缀；
     *  · 排序值取**区间起点** —— 取结尾的 `4` 只是碰巧能和 `5-7` 排对，
     *    一旦区间跨度重叠（`1-9` 与 `10-12`）或前缀变化就会乱。
     */
    private val rangeNumber = Regex(
        "^(?:[^0-9０-９]{0,16}?)(?:第\\s*)?([0-9０-９]{1,6})\\s*(?:[\\-–—―－~～]|至|到)\\s*[0-9０-９]{1,6}"
    )

    private fun parseNumberToken(raw: String): Int? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        halfWidthInt(numberSpaces.replace(s, ""))?.let { return it }
        romanToInt(s)?.let { return it }
        englishToInt(s)?.let { return it }
        return chineseToInt(numberSpaces.replace(s, ""))
    }

    /** 阿拉伯数字转整数，顺带把全角数字折成半角 */
    private fun halfWidthInt(s: String): Int? {
        if (s.isEmpty()) return null
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in '０'..'９') '0' + (c - '０') else c)
        return sb.toString().toIntOrNull()
    }

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

    private val ROMAN_STRICT =
        Regex("(?i)^M{0,4}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})$")

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

    /**
     * 中文数字转整数，支持「二十三」「一百零五」「十」、大写「壹贰…拾佰仟」，
     * 以及无单位的位值写法「一〇八」「一二三」；转不了返回 null。
     */
    private fun chineseToInt(s: String): Int? {
        if (s.isEmpty()) return null
        val digits = mapOf(
            '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '兩' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
            '壹' to 1, '贰' to 2, '貳' to 2, '叁' to 3, '參' to 3, '肆' to 4, '伍' to 5, '陆' to 6, '陸' to 6,
            '柒' to 7, '捌' to 8, '玖' to 9
        )
        val units = mapOf(
            '十' to 10, '百' to 100, '千' to 1000,
            '拾' to 10, '佰' to 100, '仟' to 1000, '万' to 10000, '萬' to 10000
        )
        // 无单位位值写法：`一〇八` = 108、`一二三` = 123、`二〇二五` = 2025
        if (s.length >= 2 && s.none { units.containsKey(it) }) {
            var v = 0L
            for (c in s) {
                v = v * 10 + (digits[c] ?: return null)
                if (v > Int.MAX_VALUE) return null
            }
            return v.toInt()
        }
        var total = 0
        var section = 0
        var number = 0
        for (c in s) {
            when {
                digits.containsKey(c) -> number = digits.getValue(c)
                units.containsKey(c) -> {
                    val u = units.getValue(c)
                    if (u == 10000) {
                        total += (section + number).coerceAtLeast(1) * u
                        section = 0
                    } else {
                        section += (if (number == 0) 1 else number) * u
                    }
                    number = 0
                }

                else -> return null
            }
        }
        total += section + number
        return total
    }
}
