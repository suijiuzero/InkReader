package com.inkreader.parse

/** Numeric-only features shared by training export and on-device inference. No book names or dictionaries. */
internal class HeadingFeatures(private val lines: List<String>) {
    companion object {
        val names = listOf("length", "short", "one_char", "digits", "han", "latin", "other_letters", "punctuation",
            "sentence_end", "blank_before", "blank_after", "blank_both", "previous_length", "next_length",
            "position", "uppercase", "numeric_only", "quote_start", "colon_end", "following_body", "near_short_lines")
        /** [at32] 的维度名，前 21 个与 [names] 相同。 */
        val namesV2 = names + listOf("prev_ends_soft", "para_at_start", "para_len_le3", "para_len_norm",
            "prev_short", "next_short", "prev_headinglike", "next_headinglike",
            "has_cn_num", "has_en_num", "has_decor")
    }
    private val lengths = IntArray(lines.size) { i ->
        if (i and 511 == 0) ParseCancellation.checkpoint()
        (lines as? SizedLines)?.characters(i, true) ?: lines[i].trim().length
    }
    val candidates = lines.indices.filter { lengths[it] in 1..96 }
    private val prefix = LongArray(lines.size + 1)
    private val previous = IntArray(lines.size) { -1 }
    private val next = IntArray(lines.size) { -1 }
    private val nextCandidate = IntArray(lines.size) { lines.size }
    private val paragraphStart = IntArray(lines.size)
    private val paragraphEnd = IntArray(lines.size)
    init {
        var last = -1
        for (i in lines.indices) {
            if (i and 511 == 0) ParseCancellation.checkpoint()
            prefix[i + 1] = prefix[i] + lengths[i]
            previous[i] = last
            if (lengths[i] > 0) last = i
        }
        var following = -1; var short = lines.size
        for (i in lines.indices.reversed()) {
            if (i and 511 == 0) ParseCancellation.checkpoint()
            next[i] = following; nextCandidate[i] = short
            if (lengths[i] > 0) following = i
            if (lengths[i] in 1..96) short = i
        }
        // 段落边界：连续非空行算一段（与离线训练的 para_idx/para_len 同义）
        var i = 0
        while (i < lines.size) {
            if (i and 511 == 0) ParseCancellation.checkpoint()
            if (lengths[i] == 0) { paragraphStart[i] = i; paragraphEnd[i] = i; i++; continue }
            val a = i
            while (i + 1 < lines.size && lengths[i + 1] > 0) i++
            val b = i
            for (j in a..b) { paragraphStart[j] = a; paragraphEnd[j] = b }
            i++
        }
    }
    fun at(i: Int): DoubleArray {
        require(i in lines.indices && lengths[i] in 1..96)
        val text = lines[i].trim()
        val codes = text.codePoints().toArray()
        val n = codes.size.toDouble()
        fun ratio(test: (Int) -> Boolean) = codes.count(test) / n
        fun flag(value: Boolean) = if (value) 1.0 else 0.0
        val before = i == 0 || lengths[i - 1] == 0
        val after = i == lines.lastIndex || lengths[i + 1] == 0
        return doubleArrayOf(text.length / 96.0, flag(text.length <= 28), flag(codes.size == 1),
            ratio { Character.isDigit(it) }, ratio { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN },
            ratio { Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN },
            ratio { Character.isLetter(it) && Character.UnicodeScript.of(it) !in setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.LATIN) },
            ratio { !Character.isLetterOrDigit(it) && !Character.isWhitespace(it) },
            flag(text.last() in "。！？；，、.!?;,"), flag(before), flag(after), flag(before && after),
            (previous[i].takeIf { it >= 0 }?.let { lengths[it] } ?: 0).coerceAtMost(300) / 300.0,
            (next[i].takeIf { it >= 0 }?.let { lengths[it] } ?: 0).coerceAtMost(300) / 300.0,
            i.toDouble() / lines.size.coerceAtLeast(1), ratio { Character.isUpperCase(it) },
            flag(codes.all { Character.isDigit(it) || Character.isWhitespace(it) }),
            flag(text.first() in "\"“‘「『《"), flag(text.last() in ":："),
            (prefix[nextCandidate[i]] - prefix[i + 1]).coerceAtMost(1000) / 1000.0,
            ((i - 5).coerceAtLeast(0)..(i + 5).coerceAtMost(lines.lastIndex)).count { it != i && lengths[it] in 1..96 } / 10.0)
    }

    /**
     * 32 维版本：前 21 维与 [at] 完全一致（同一份代码算出，不是复刻），
     * 后 11 维是上下文特征。**这里是特征语义的唯一权威**，Python 只读导出的向量。
     */
    fun at32(i: Int): DoubleArray {
        val base = at(i)
        val out = DoubleArray(32)
        base.copyInto(out)
        val text = lines[i].trim()
        val p = previous[i]
        val n = next[i]
        val prevText = if (p >= 0) lines[p].trim() else ""
        val nextText = if (n >= 0) lines[n].trim() else ""
        val paraLen = paragraphEnd[i] - paragraphStart[i] + 1
        out[21] = if (prevText.isNotEmpty() && prevText.last() in SOFT_END) 1.0 else 0.0
        out[22] = if (i == paragraphStart[i]) 1.0 else 0.0
        out[23] = if (paraLen <= 3) 1.0 else 0.0
        out[24] = (paraLen / 12.0).coerceAtMost(1.0)
        out[25] = if (prevText.isNotEmpty() && prevText.length <= 20) 1.0 else 0.0
        out[26] = if (nextText.isNotEmpty() && nextText.length <= 20) 1.0 else 0.0
        out[27] = if (looksLikeHeading(prevText)) 1.0 else 0.0
        out[28] = if (looksLikeHeading(nextText)) 1.0 else 0.0
        out[29] = if (CN_NUMBER.containsMatchIn(text)) 1.0 else 0.0
        out[30] = if (WESTERN_NUMBER.containsMatchIn(text)) 1.0 else 0.0
        out[31] = if (DECOR_PREFIX.containsMatchIn(text)) 1.0 else 0.0
        return out
    }

    private val SOFT_END = "，,、；;：:"
    private val CN_NUMBER = Regex("^\\s*第\\s*[0-9０-９一二三四五六七八九十百千零两〇○]{1,10}\\s*[章回話话節节巻卷部篇]")
    private val WESTERN_NUMBER = Regex("(?i)^\\s*(?:chapter|chap\\.|part|book|canto|chapitre|kapitel|cap[íi]tulo|capitolo|hoofdstuk)\\b")
    private val DECOR_PREFIX = Regex("^\\s*[☆★◆◇※●○▲△■□✦✧❖§]")
    private val PAREN_NUMBER = Regex("^\\s*[（(]\\s*[0-9一二三四五六七八九十百]{1,4}\\s*[）)]")
    private val NUM_DOT = Regex("^\\s*[0-9]{1,4}\\s*[\\.、]\\s*\\S")
    private val CN_INDEX = Regex("^\\s*[一二三四五六七八九十]{1,3}\\s*[、\\.]\\s*\\S")
    private val JA_HEAD = Regex("^\\s*(?:第\\s*[0-9０-９一二三四五六七八九十百]{1,6}\\s*[話话章節节]|序章|序|プロローグ|エピローグ|幕間|その[一二三四五六七八九十])")

    /** 与离线 looks() 同义：形态上像不像章节标题（不看上下文）。 */
    private fun looksLikeHeading(raw: String): Boolean {
        val s = raw.trim()
        if (s.isEmpty() || s.length > 80) return false
        if (s.last() in "。！？.!?" && !WESTERN_NUMBER.containsMatchIn(s)) return false
        return CN_NUMBER.containsMatchIn(s) || WESTERN_NUMBER.containsMatchIn(s) ||
            DECOR_PREFIX.containsMatchIn(s) || PAREN_NUMBER.containsMatchIn(s) ||
            NUM_DOT.containsMatchIn(s) || CN_INDEX.containsMatchIn(s) || JA_HEAD.containsMatchIn(s)
    }
}
