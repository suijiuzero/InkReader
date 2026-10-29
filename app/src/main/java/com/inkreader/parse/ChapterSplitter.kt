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
    private const val CH_MARKS = "章回節节卷巻集部篇編编幕折話话齣出"

    /** 章级标记（严格版）：**不含「节 / 節」**，判断文件是否真的按章排好时用 */
    private const val CH_MARKS_STRICT = "章回卷巻集部篇編编幕折話话齣出"

    /** 只保留「章 / 回 / 話 / 话」这一层（最细的分章单位） */
    private const val CH_MARKS_CHAP = "章回話话齣出"

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
        val brackets = listOf("（" to "）", "(" to ")", "【" to "】", "[" to "]", "「" to "」", "『" to "』", "〔" to "〕")
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
            "[$KANJI_DIGITS]{1,10}\\s*[$marks](?=\\s|[:：.、（(【\\[]|$))\\s*(.{0,160})$"
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
            "第\\s*[$KANJI_DIGITS]{1,10}\\s*[$marks]\\s*(.{0,160})$"
    )

    private fun bracketTitle(marks: String): Regex {
        val marker = "\\s*第\\s*[$KANJI_DIGITS]{1,10}\\s*[$marks]\\s*"
        val pairs = listOf("（" to "）", "(" to ")", "【" to "】", "[" to "]", "〔" to "〕")
        val wrapped = pairs.joinToString("|") { (open, close) -> Regex.escape(open) + marker + Regex.escape(close) }
        return Regex("^\\s{0,6}(?:$wrapped)\\s*(.{0,160})$")
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
            "|(?i:volume|vol|book)\\.?\\s*$NUM)\\s*(.{0,160})$"
    )

    // 英 / 日：Chapter / Part / Book / Volume / Episode + 编号
    private val enTitle =
        Regex("^\\s{0,6}(?i:$EN_ALL_WORDS)$EN_NUMBER$NUM\\s*[:：.、\\-–—]?\\s*(.{0,160})$")

    /** 仅认英文 Chapter / Episode（不含 Part / Volume / Book） */
    private val enChapOnlyTitle =
        Regex("^\\s{0,6}(?i:$EN_CHAPTER_WORDS)$EN_NUMBER$NUM\\s*[:：.、\\-–—]?\\s*(.{0,160})$")

    /** 强模式：包含章、卷、节、特殊章 */
    private val strong =
        listOf(cnTitle, cnLabeledTitle, cnBracketTitle, enTitle, specialTitle, upperTitle)

    /** 章级强模式（**不含「节 / 節」**） */
    private val strongChapter =
        listOf(cnChapterTitle, cnLabeledTitleStrict, cnBracketTitleStrict, enTitle, specialTitle, upperTitle)

    /** 只认「章 / 回 / 話」一级，把「卷 / 部 / 篇 / 集」这类上级标题排除在外 */
    private val strongChapOnly =
        listOf(cnChapOnlyTitle, cnLabeledTitleChap, cnBracketTitleChap, enChapOnlyTitle, specialTitle)

    private val headingWrappers = listOf("【" to "】", "[" to "]", "(" to ")", "「" to "」", "『" to "』", "〔" to "〕")
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
        if (trimmed.length > 200) return trimmed
        var text = HeadingNumbers.canonical(trimmed)
        repeat(2) {
            headingWrappers.firstOrNull { (open, close) -> text.startsWith(open) && text.endsWith(close) }?.let { (open, close) ->
                val inner = HeadingNumbers.canonical(text.substring(open.length, text.length - close.length).trim())
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
        val raw = line.trim()
        if (raw.isEmpty()) return null
        if (!mayBeHeading(raw)) return null
        val t = headingText(raw)
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
        ParseCancellation.checkpoint()
        val s = raw.replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\uFEFF", "")
            .replace("\u200B", "")
            .replace("\u00A0", " ")
            .replace("\u3000", "　")
        val lines = s.split("\n").map { it.trimEnd() }
        val sb = StringBuilder(s.length)
        var blank = 0
        for ((index, line) in lines.withIndex()) {
            if (index and 511 == 0) ParseCancellation.checkpoint()
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
    ): List<Part> = splitInternal(text, fallbackTitle, options, null)

    fun analyze(
        text: String,
        fallbackTitle: String = "正文",
        options: SplitOptions = SplitOptions(mode = SplitMode.SMART, cleanWatermark = false)
    ): ChapterAnalysis {
        val trace = AnalysisTrace()
        val parts = splitInternal(text, fallbackTitle, options, trace)
        return ChapterAnalysis(parts, trace.decisions, trace.gaps, trace.toc,
            trace.fallback, trace.truncated)
    }

    private class AnalysisTrace {
        val decisions = ArrayList<HeadingDecision>()
        val gaps = ArrayList<NumberGap>()
        var toc = 0
        var fallback = false
        var truncated = false
    }

    private fun splitInternal(text: String, fallbackTitle: String, options: SplitOptions, trace: AnalysisTrace?): List<Part> {
        if (text.isBlank()) return listOf(Part(fallbackTitle, ""))

        // 1. 特殊切分档位：整篇作为一章 / 固定字数切分
        if (options.mode == SplitMode.PER_FILE) {
            val body = if (options.cleanWatermark) cleanBodyWatermarks(text) else text
            val title = if (options.cleanWatermark) cleanChapterTitle(fallbackTitle) else fallbackTitle
            return listOf(Part(title, body))
        }
        if (options.mode == SplitMode.FIXED_SIZE) {
            trace?.fallback = true
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
            trace?.fallback = true
            // 自定义规则一行都没命中（或正则本身写不合法）：明确按固定字数兜底，
            // 不要悄悄滑进 SMART 自动管线 —— 用户显式指定了规则，却拿到完全另一套结果，
            // 会被当成「规则没生效」的 bug，而且很难察觉。
            return splitBySize(
                if (options.cleanWatermark) cleanBodyWatermarks(text) else text,
                fallbackTitle
            )
        }

        val plan = plan(lines, text.length, options)
        if (trace != null) {
            trace.decisions += plan.decisions
            trace.gaps += plan.gaps
            trace.toc = plan.skippedTocEntries
            trace.truncated = plan.truncated
        }
        if (plan.headings.isEmpty()) {
            trace?.fallback = true
            return splitBySize(if (options.cleanWatermark) cleanBodyWatermarks(text) else text, fallbackTitle)
        }
        return assembleParts(lines, text, plan.headings, fallbackTitle, options.cleanWatermark, trace, plan.mergeLeadingBody)
    }

    fun plan(lines: List<String>, totalChars: Int, options: SplitOptions): ChapterPlan {
        if (options.mode == SplitMode.CUSTOM && options.customPattern.isNotBlank()) {
            val regex = buildCustomRegex(options.customPattern.trim())
            val selected = regex?.let { findTitleLines(lines, listOf(it), contextGated = false, canonical = false) }.orEmpty()
            return ChapterPlan(selected, selected.take(500).map { HeadingDecision(it + 1, lines[it].trim(), 100,
                setOf(HeadingReason.TITLE_SHAPE), true) }, emptyList(), 0, false, selected.size > 500, selected)
        }
        // Canonical title forms and code fences are computed once, shared by all rule passes.
        val memo = HashMap<Int, String>()
        val canonical = object : AbstractList<String>() {
            override val size get() = lines.size
            override fun get(index: Int): String {
                if (index and 511 == 0) ParseCancellation.checkpoint()
                val trimmed = lines[index].trim()
                if (!mayBeHeading(trimmed)) return trimmed
                return memo.getOrPut(index) { headingText(trimmed) }
            }
        }
        val fenced = fencedLines(lines)
        val global = chooseTitleLines(lines, canonical, fenced, totalChars, options.mode)
        val regionalCandidates = augmentFlexibleHeadings(lines, canonical, fenced,
            regionalTitleLines(lines, canonical, fenced, global, options.mode), options.mode)
        val skippedToc = leadingTocLines(lines, canonical, fenced)
        val regional = regionalCandidates.filterNot { it in skippedToc }
        if (regional.isEmpty() && options.forcedHeadingLines.isEmpty())
            return ChapterPlan(emptyList(), emptyList(), emptyList(), 0, false, false)
        val recovered = recoverNumberGaps(lines, regional)
        val scored = scoreHeadings(lines, canonical, fenced, recovered).toMutableList()
        val sequenceChoice = SequencePlanner.resolve(lines, scored)
        for (i in scored.indices) {
            val decision = scored[i]
            if (decision.line - 1 in sequenceChoice.review) scored[i] = decision.copy(
                accepted = decision.accepted && decision.line - 1 !in sequenceChoice.rejected,
                reasons = decision.reasons + HeadingReason.SEQUENCE_ALTERNATIVE)
        }
        for (line in options.forcedHeadingLines.sorted()) {
            val index = line - 1
            if (index !in lines.indices || lines[index].isBlank()) continue
            val position = scored.indexOfFirst { it.line == line }
            if (position >= 0) scored[position] = scored[position].copy(
                accepted = true, reasons = scored[position].reasons + HeadingReason.USER_CONFIRMED)
            else scored += HeadingDecision(line, lines[index].trim(), 100, setOf(HeadingReason.USER_CONFIRMED), true)
        }
        scored.sortBy { it.line }
        val selected = (scored.filter { it.accepted }.map { it.line - 1 } +
            options.forcedHeadingLines.map { it - 1 }.filter { it in lines.indices && lines[it].isNotBlank() })
            .distinct().sorted()
        val decisions = ArrayList<HeadingDecision>()
        run {
            val regionalSet = regional.toHashSet()
            val globalSet = global.toHashSet()
            scored.take(500).forEach { decision ->
                val idx = decision.line - 1
                val reasons = decision.reasons.toMutableSet()
                if (idx !in regionalSet) reasons += HeadingReason.NUMBER_RECOVERY
                if (idx in regionalSet && idx !in globalSet) reasons += HeadingReason.LOCAL_STYLE
                if (decision.line in options.ignoredHeadingLines) reasons += HeadingReason.USER_IGNORED
                decisions += decision.copy(reasons = reasons,
                    accepted = decision.accepted && decision.line !in options.ignoredHeadingLines)
            }
        }
        val chosen = selected.filterNot { it + 1 in options.ignoredHeadingLines }
        return ChapterPlan(chosen, decisions, unresolvedGaps(lines, selected).take(100),
            skippedToc.count { canonical[it].trim() !in tocLabels },
            skippedToc.isNotEmpty() || selected.firstOrNull()?.let { it + 1 in options.ignoredHeadingLines } == true,
            scored.size > 500, scored.map { it.line - 1 },
            scored.filter { !it.accepted || it.score < 74 || HeadingReason.SEQUENCE_ALTERNATIVE in it.reasons }.map { it.line - 1 }.toSet())
    }

    private fun lineCharacters(lines: List<String>, index: Int, trim: Boolean = false): Int =
        (lines as? SizedLines)?.characters(index, trim) ?: if (trim) lines[index].trim().length else lines[index].length

    private fun sliceLines(lines: List<String>, start: Int, end: Int): List<String> {
        if (lines !is SizedLines) return lines.subList(start, end)
        return object : AbstractList<String>(), SizedLines {
            override val size get() = end - start
            override fun get(index: Int) = lines[index + start]
            override fun characters(index: Int, trim: Boolean) = lines.characters(index + start, trim)
            override fun fence(index: Int) = lines.fence(index + start)
        }
    }

    private fun chooseTitleLines(lines: List<String>, canonical: List<String>, fenced: BooleanArray,
        totalChars: Int, mode: SplitMode): List<Int> {
        fun find(patterns: List<Regex>, contextGated: Boolean) =
            findTitleLines(lines, patterns, contextGated, canonicalLines = canonical, fenced = fenced)
        return when (mode) {
            SplitMode.STRICT_CHAP -> {
                val rawChap = find(strongChapOnly, contextGated = false)
                val voted = filterByDominantFamily(lines, rawChap)
                val cleaned = filterSequenceOutliers(lines, voted)
                if (cleaned.isNotEmpty()) cleaned
                else emptyList()
            }

            SplitMode.NUMBERED -> {
                val rawWeak = find(weak, contextGated = true)
                val rawChap = find(strongChapOnly, contextGated = false)
                val combo = (rawChap + rawWeak).sorted().distinct()
                val cleaned = filterSequenceOutliers(lines, combo)
                if (cleaned.size >= 2) cleaned
                else emptyList()
            }

            else -> {
                // SMART / IN_FILE 默认智能多级管线：
                // 强模式（整行就是一个「第X章 …」）特征足够强，不强制要求上下有空行；
                // 只有弱模式（`1.`、`一、` 这类容易和正文列表撞车的写法）才需要上下文把关。
                val strongIdx = find(strong, contextGated = false)
                val chapOnlyIdx = find(strongChapOnly, contextGated = false)
                val chapterIdx = find(strongChapter, contextGated = false)

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
                    val weakOnly = find(weak, contextGated = true)
                    val combo = (strongIdx + weakOnly).sorted().distinct()
                        .filterNot { extractVolumeTitle(lines[it]) != null }
                    val avgGap = if (combo.size > 1) totalChars.toFloat() / combo.size else Float.MAX_VALUE
                    if (combo.size >= 3 && avgGap >= 300) {
                        filterSequenceOutliers(lines, combo)
                    } else {
                        // 第三道自学习防线：无规则奇葩排版的自动归纳（如 `◆ 01`、`§ 1`、`★ 01`）
                        val induced = induceCustomPrefixLines(lines, totalChars)
                        if (induced.size >= 3) {
                            induced
                        } else if (strongIdx.size == 1) {
                            strongIdx
                        } else {
                            emptyList()
                        }
                    }
                }
            }
        }
    }

    private val headingInitials = SPECIAL_WORDS.split('|').map { it.first() }.toSet() +
        "零〇一二三四五六七八九十百千万萬两兩壹贰貳叁參肆伍陆陸柒捌玖拾佰仟第卷巻上中下前后後序楔引题題开終终最大全结結完尾附付番外幕间間插作内目まプエあ【[(（「『★☆◆◇※".toSet()
    private val englishStarts = listOf("chapter", "chap", "ch", "episode", "ep", "part", "book", "volume", "vol",
        "prologue", "foreword", "preface", "epilogue", "afterword", "interlude", "cap", "roz", "bab", "böl").groupBy { it.first() }

    /** A cheap superset of built-in title starts. Custom prefixes bypass this gate. */
    private fun mayBeHeading(text: String): Boolean {
        val first = text.firstOrNull() ?: return false
        if (first in headingInitials || first.isDigit()) return true
        // Compatibility numbers/letters and non-CJK scripts still reach NFKC (e.g. ①, Ⅳ, full-width Chapter).
        if (first.code > 127 && first !in '\u4E00'..'\u9FFF') return true
        if (text.indexOf('第') in 1..16) return true
        return englishStarts[first.lowercaseChar()]?.any { text.startsWith(it, true) } == true
    }

    private fun fencedLines(lines: List<String>): BooleanArray {
        val result = BooleanArray(lines.size)
        var fence: FenceInfo? = null
        for (i in lines.indices) {
            if (i and 511 == 0) ParseCancellation.checkpoint()
            val info = if (lines is SizedLines) lines.fence(i) else {
                val text = lines[i].trimStart()
                val run = text.takeWhile { it == '`' || it == '~' }
                run.takeIf { it.isNotEmpty() && it.all { c -> c == it.first() } }
                    ?.let { FenceInfo(it.first(), it.length, text.trim() == it) }
            }
            val valid = info != null && info.length >= 3
            if (fence != null) {
                result[i] = true
                if (valid && info!!.marker == fence.marker && info.length >= fence.length && info.onlyMarker) fence = null
            } else if (valid) {
                fence = info
                result[i] = true
            }
        }
        return result
    }

    /** Select each explicit volume independently; numeric subsections cannot override chapters in that volume. */
    private fun regionalTitleLines(lines: List<String>, canonical: List<String>, fenced: BooleanArray,
        global: List<Int>, mode: SplitMode): List<Int> {
        if (mode != SplitMode.SMART && mode != SplitMode.IN_FILE) return global
        val volumes = lines.indices.filter { !fenced[it] && extractVolumeTitle(canonical[it]) != null }
        if (volumes.isEmpty()) return global
        val starts = (listOf(0) + volumes).distinct().sorted()
        val result = ArrayList<Int>()
        for ((position, start) in starts.withIndex()) {
            val end = starts.getOrNull(position + 1) ?: lines.size
            val localLines = sliceLines(lines, start, end)
            val first = global.binarySearch(start).let { if (it < 0) -it - 1 else it }
            val last = global.binarySearch(end).let { if (it < 0) -it - 1 else it }
            val existing = global.subList(first, last)
            val local = chooseTitleLines(localLines, canonical.subList(start, end),
                fenced.copyOfRange(start, end), (start until end).sumOf { lineCharacters(lines, it) }, mode)
            // A volume label alone is not an additional chapter. Preserve the existing result in uncertain regions.
            val actual = local.filter { extractVolumeTitle(localLines[it]) == null }
            if (actual.size >= 2) result += actual.map { it + start }
            else result += existing
        }
        return result.distinct().sorted()
    }

    private val bareNumber = Regex("^(?:[0-9零〇一二三四五六七八九十百千万萬两兩壹贰貳叁參肆伍陆陸柒捌玖拾佰仟]{1,9}|[IVXLCDM]{1,9}\\.?)$")
    // Dotless titles require an uppercase subtitle, keeping ordinary "I was ..."
    // narrative paragraphs from breaking an otherwise coherent Roman series.
    private val romanTitled = Regex("^([IVXLCDM]{1,9})(?:\\.\\s*\\S|\\s+\\p{Lu}).{0,229}$")

    /** Bare numerals need a coherent body-backed series, never isolated page numbers. */
    private fun augmentFlexibleHeadings(lines: List<String>, canonical: List<String>, fenced: BooleanArray,
        original: List<Int>, mode: SplitMode): List<Int> {
        if (mode != SplitMode.SMART && mode != SplitMode.IN_FILE && mode != SplitMode.NUMBERED) return original
        val prefix = LongArray(lines.size + 1)
        for (i in lines.indices) prefix[i + 1] = prefix[i] + lineCharacters(lines, i, true)
        val result = original.toMutableSet()
        val candidates = lines.indices.filter { i ->
            val text = canonical[i]
            val longRoman = romanTitled.matches(text) && chapterOrderFromTitle(text) != null
            if (fenced[i] || i in result || text.length !in 1..240) false
            else (longRoman || (text.length <= 44 && (bareNumber.matches(text) || weak.any { it.matches(text) }))) &&
                (i == 0 || lines[i - 1].isBlank()) && (i == lines.lastIndex || lines[i + 1].isBlank())
        }
        var start = 0
        while (start < candidates.size) {
            var end = start + 1
            while (end < candidates.size) {
                val a = chapterOrderFromTitle(canonical[candidates[end - 1]])
                val b = chapterOrderFromTitle(canonical[candidates[end]])
                val nextOriginal = original.binarySearch(candidates[end - 1]).let { if (it < 0) -it - 1 else it + 1 }
                if (a == null || b != a + 1 || original.getOrNull(nextOriginal)?.let { it < candidates[end] } == true) break
                end++
            }
            val run = candidates.subList(start, end)
            if (run.size >= 3) {
                val first = run.first()
                val previous = original.lastOrNull { it < first }
                val startsBook = prefix[first] < 200 && (chapterOrderFromTitle(canonical[first]) ?: 0) in 1..20
                val continues = previous != null &&
                    chapterOrderFromTitle(canonical[first]) == (HeadingNumbers.interval(lines[previous])?.last
                        ?: chapterOrderFromTitle(lines[previous]) ?: -100) + 1 &&
                    original.none { it > first && it < run.last() }
                val hasBody = run.indices.all { position ->
                    val line = run[position]
                    val next = run.getOrNull(position + 1) ?: original.firstOrNull { it > line } ?: lines.size
                    prefix[next] - prefix[line + 1] >= 160
                }
                if ((startsBook || continues) && hasBody) result.addAll(run)
            }
            start = end
        }
        return result.sorted()
    }

    /** Combine independent evidence. Existing strong titles remain conservative; code and clear outliers are rejected. */
    private fun scoreHeadings(lines: List<String>, canonical: List<String>, fenced: BooleanArray,
        candidates: List<Int>): List<HeadingDecision> {
        val prefix = LongArray(lines.size + 1)
        val volume = IntArray(lines.size)
        var region = 0
        for (i in lines.indices) {
            if (i and 511 == 0) ParseCancellation.checkpoint()
            prefix[i + 1] = prefix[i] + lineCharacters(lines, i, true)
            if (extractVolumeTitle(canonical[i]) != null && !fenced[i]) region++
            volume[i] = region
        }
        val numbers = candidates.map { chapterOrderFromTitle(canonical[it])?.takeIf { n -> n in 1 until ORDER_BACK_MATTER } }
        return candidates.mapIndexed { index, line ->
            val reasons = linkedSetOf(HeadingReason.TITLE_SHAPE)
            val family = classifyFamily(canonical[line])
            var score = if (family == HeadingFamily.OTHER) 46 else 58
            val isolated = line == 0 || lines[line - 1].isBlank() || line == lines.lastIndex || lines[line + 1].isBlank()
            if (isolated) { score += 12; reasons += HeadingReason.ISOLATED_LINE }
            val previous = candidates.getOrNull(index - 1)
            val next = candidates.getOrNull(index + 1)
            val number = numbers[index]
            if (number != null) {
                val pn = previous?.let { HeadingNumbers.interval(lines[it])?.last } ?: numbers.getOrNull(index - 1)
                val nn = numbers.getOrNull(index + 1)
                val follows = previous != null && volume[previous] == volume[line] && pn != null && number - pn in 0..1
                val precedes = next != null && volume[next] == volume[line] && nn != null && nn - number in 0..1
                if (follows || precedes) { score += 16; reasons += HeadingReason.NUMBER_SEQUENCE }
                if (previous != null && next != null && pn == number && volume[previous] == volume[line] &&
                    prefix[next] - prefix[line + 1] < 40 && segmentSuffix.find(canonical[line]) == null &&
                    segmentSuffix.find(canonical[previous]) == null) score -= 60
                // A short isolated false chapter between two sequential anchors is still a quotation.
                if (previous != null && next != null && volume[previous] == volume[next] &&
                    pn != null && nn != null && nn == pn + 1 && number !in pn..nn &&
                    prefix[next] - prefix[previous + 1] < 1200) score -= 60
            }
            if (next == null || prefix[next] - prefix[line + 1] >= 40) score += 6
            if (fenced[line]) score = 0
            HeadingDecision(line + 1, lines[line].trim(), score.coerceIn(0, 100), reasons, score >= 50)
        }
    }

    private fun unresolvedGaps(lines: List<String>, selected: List<Int>): List<NumberGap> {
        val result = ArrayList<NumberGap>()
        for ((start, end) in selected.zipWithNext()) {
            val left = continuityHeading(lines[start], relaxed = true) ?: continue
            val right = continuityHeading(lines[end], relaxed = true) ?: continue
            if (left.kind != right.kind || right.number - left.end !in 2..33) continue
            if ((start + 1 until end).any {
                    extractVolumeTitle(lines[it]) != null || specialTitle.matches(headingText(lines[it]))
                }) continue
            result += NumberGap(lines[start].trim(), lines[end].trim(), (left.end + 1 until right.number).toList())
            if (result.size == 100) break
        }
        return result
    }

    private val tocLabels = setOf("目录", "目錄", "目次", "Contents", "CONTENTS")
    private fun leadingTocLines(lines: List<String>, canonical: List<String>, fenced: BooleanArray): Set<Int> {
        val all = lines.indices.filter { index ->
            val text = canonical[index]
            !fenced[index] && text.length in 1..96 && mayBeHeading(text) &&
                (strong.any { it.matches(tocTitle(text)) } || weak.any { it.matches(tocTitle(text)) })
        }
        val kept = dropLeadingToc(lines, all).toHashSet()
        return all.filterNot { it in kept }.toSet()
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
        cleanWatermark: Boolean,
        trace: AnalysisTrace? = null,
        mergeLeadingBody: Boolean = false
    ): List<Part> {
        val parts = ArrayList<Part>()

        // 扫描第一个章节标题之前是否已经出现了「第一卷 xxx」
        var activeVolume = ""
        for (k in 0 until chosen.first()) {
            extractVolumeTitle(lines[k])?.let { activeVolume = it }
        }

        // 第一个标题之前的内容作为前言
        val head = lines.subList(0, chosen.first()).joinToString("\n").trim()
        if (head.isNotEmpty() && head.length >= 200 && !mergeLeadingBody) {
            val hTitle = guessHeadTitle(head)
            val hBody = if (cleanWatermark) cleanBodyWatermarks(head) else head
            parts += Part(if (cleanWatermark) cleanChapterTitle(hTitle) else hTitle, hBody, activeVolume)
        }

        // 上一章末尾剥出来的卷标题（`第二卷 惊雷`），挂到这一章的开头当卷首，并更新当前所属卷
        var carryTitles = ""
        for ((i, lineIdx) in chosen.withIndex()) {
            ParseCancellation.checkpoint()
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
            if (i == 0 && head.isNotEmpty() && (head.length < 200 || mergeLeadingBody)) {
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
                trace?.fallback = true
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
        for (line in lines.indices) prefix[line + 1] = prefix[line] + lineCharacters(lines, line)
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

    private data class ContinuityHeading(val number: Int, val kind: String, val stem: String, val segment: Int, val end: Int = number)
    private val gapCnMarker = Regex("^(?:第\\s*)?($SPACED_CN_NUMBER)\\s*([章回話话齣出])")
    private val gapEnMarker = Regex("^(?i:$EN_CHAPTER_WORDS)$EN_NUMBER($NUM)")
    private val gapLabel = Regex("^(?:[A-Za-z]{1,8}|[正文免费收费公众订阅VIP上架]{1,6})(?:\\s+|\\s*:\\s*)(?=第)")
    private val gapDecor = Regex("^[★☆◆◇※]{1,3}\\s*|\\s*[★☆◆◇※]{1,3}$")
    private val gapMarkdown = Regex("^#{1,6}\\s+")
    private val numberSpaces = Regex("\\s+")

    /** 只为缺口比较放宽字间空格和包装；不改变全书主识别规则，也不改写原文。 */
    private fun continuityHeading(raw: String, relaxed: Boolean): ContinuityHeading? {
        if (!relaxed && !isNumberedChapterTitle(raw)) return null
        if (raw.trim().length !in 1..80) return null
        var title = HeadingNumbers.canonical(raw.trim())
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
        return ContinuityHeading(number, kind, stem, segment, HeadingNumbers.interval(raw)?.last ?: number)
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
            if (left.kind != right.kind || right.number - left.end !in 2..33 || pair.second - pair.first > 50000) continue
            val found = ArrayList<Pair<Int, ContinuityHeading>>()
            val counts = LongArray(pair.second - pair.first + 1)
            var fence: String? = null
            var blocked = false
            var scannedChars = 0L
            for (line in pair.first + 1 until pair.second) {
                val raw = lines[line]
                scannedChars += lineCharacters(lines, line).toLong() + 1
                if (scannedChars > 2_000_000) { blocked = true; break }
                val text = raw.trim()
                counts[line - pair.first] = counts[line - pair.first - 1] + lineCharacters(lines, line, true)
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
                if (heading.number <= left.end || heading.end >= right.number) { blocked = true; break }
                found += line to heading
            }
            if (blocked || found.isEmpty()) continue
            val unambiguous = found.groupBy { it.second.number }.values.flatMap { group ->
                if (group.size == 1) group
                else if (group.all { it.second.segment >= 0 } && group.map { it.second.stem }.distinct().size == 1 &&
                    group.zipWithNext().all { it.first.second.segment < it.second.second.segment }) group
                else emptyList()
            }.sortedBy { it.first }
            if (unambiguous.zipWithNext().any { it.first.second.end > it.second.second.number }) continue
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
            if (i and 511 == 0) ParseCancellation.checkpoint()
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
        canonical: Boolean = true,
        canonicalLines: List<String>? = null,
        fenced: BooleanArray = fencedLines(lines)
    ): List<Int> {
        val out = ArrayList<Int>()
        val adjacentWithoutBody = HashSet<Int>()
        var inExplicitList = false
        for (i in lines.indices) {
            if (i and 511 == 0) ParseCancellation.checkpoint()
            if (fenced[i]) continue
            val raw = lines[i]
            if (contextGated) {
                if (explicitListLabel.matches(raw.trim())) inExplicitList = true
                if (inExplicitList && isNumberedChapterTitle(raw)) inExplicitList = false
                if (inExplicitList) continue
            }
            val canonicalText = if (canonical) canonicalLines?.get(i) ?: headingText(raw) else raw.trim()
            if (canonicalText.length > 200 || (canonical && !mayBeHeading(canonicalText))) continue
            val t = if (canonical) tocPageTail.replace(canonicalText, "").trim() else canonicalText
            if (t.isEmpty() || t.length > (if (canonical && !contextGated) 180 else 44)) continue
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
            if (t.length > 44 && (!prevBlank || !nextBlank || chapterOrderFromTitle(t) == null)) continue

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
        val coherent = out.filterIndexed { index, line ->
            if (lines[line].trim().length <= 44) true
            else {
                val number = chapterOrderFromTitle(lines[line])
                val before = out.getOrNull(index - 1)?.let { chapterOrderFromTitle(lines[it]) }
                val after = out.getOrNull(index + 1)?.let { chapterOrderFromTitle(lines[it]) }
                number != null && out.size >= 3 && ((before != null && number == before + 1) || (after != null && after == number + 1))
            }
        }
        val candidates = dropLeadingToc(lines, coherent)
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
    private val tocPageTail = Regex("(?:[.·…．⋯]{2,}\\s*|\\t+|\\s{2,})[0-9０-９]{1,6}\\s*$")
    private fun tocTitle(raw: String): String = headingText(tocPageTail.replace(raw, "").trim())
    private fun tocKey(raw: String): String {
        val title = tocPageTail.replace(cleanChapterTitle(raw), "").trim()
        val heading = continuityHeading(title, relaxed = true)
        return if (heading != null) "${heading.kind}:${heading.number}:${heading.end}:${heading.segment}"
        else headingText(title).lowercase().filterNot { it.isWhitespace() || it in ":：.、—–-" }
    }

    private fun dropLeadingToc(lines: List<String>, candidates: List<Int>): List<Int> {
        if (candidates.size < TOC_MIN_LINES) return candidates
        val explicit = lines.take(256).indexOfFirst { it.trim() in tocLabels }
        if (candidates.first() > (if (explicit >= 0) 256 else 45)) return candidates
        var end = 1
        while (end < candidates.size) {
            val gap = lines.subList(candidates[end - 1] + 1, candidates[end]).sumOf { it.trim().length }
            if (gap > TOC_GAP_CHARS) break
            end++
        }
        if (end < TOC_MIN_LINES) return candidates
        // Unlabelled short chapters spanning real volumes are not evidence of a contents page.
        if (explicit < 0 && (candidates.first()..candidates[end - 1]).any { extractVolumeTitle(lines[it]) != null }) return candidates
        var activeVolume = ""
        var cursorLine = 0
        val keys = candidates.map { candidate ->
            while (cursorLine <= candidate) {
                val raw = lines[cursorLine++]
                if (raw.length <= 96 && mayBeHeading(raw.trim())) {
                    extractVolumeTitle(tocTitle(raw))?.let { volume ->
                        activeVolume = headingText(volume).lowercase().filterNot { it.isWhitespace() || it in ":：.、—–-" }
                    }
                }
            }
            "$activeVolume|${tocKey(lines[candidate])}"
        }
        // Repeated chapter numbers in different volumes are distinct contents entries.
        val seen = HashSet<String>()
        val duplicate = (0 until end).firstOrNull { !seen.add(keys[it]) }
        val tocEnd = duplicate ?: end
        if (tocEnd < TOC_MIN_LINES - 1) return candidates
        val rest = keys.subList(tocEnd, keys.size)
        var cursor = 0
        var matched = 0
        for (key in keys.take(tocEnd)) {
            val index = (cursor until rest.size).firstOrNull { rest[it] == key } ?: continue
            cursor = index + 1
            matched++
        }
        // Number matches alone need several entries in the same order. Never delete a merely dense cluster.
        if (matched < 3 || matched * 2 < tocEnd) return candidates
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
            val word = rest.removePrefix(".").trimStart().takeWhile { it.isLetter() }.lowercase()
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

    private val explicitListLabel = Regex("(?iu)^(?:操作步骤|步骤|清单|列表|例如|例子|steps|checklist|examples)\\s*[:：]$")
    private val REFERENCE_PHRASES = listOf("里提到", "中提到", "里说到", "中说到", "的内容", "的约定是", "写着", "所说", "提到的")

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
        // Entire numeric headings include Chinese positional and financial numerals.
        if (Regex("^[IVXLCDM]{1,9}\\.$").matches(t)) parseNumberToken(t.removeSuffix("."))?.let { return it }
        parseNumberToken(t)?.let { return it }
        return null
    }

    private fun numberedOrder(t: String, marks: String): Int? {
        romanTitled.matchEntire(t)?.let { match -> romanToInt(match.groupValues[1])?.let { return it } }
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
