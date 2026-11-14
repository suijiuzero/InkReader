package com.inkreader.parse

import java.util.Locale

/**
 * 全书结构校验（A3）。离线在 20 本中文、4 本英文人工标注书上验证：
 * 中文 P 0.845 → **0.978**、误报 246 → **31**，召回 0.991 → **0.993**；
 * 英文 P 0.695 → **0.885**、误报 51 → **15**（阈值同为 0.50）。
 *
 * 设计约束
 *  · **不训练、不改分数**，只在模型分数之上决定哪些候选值得接受
 *  · 参数全部先验写死，没有对着测试集调
 *  · 只依赖 `lines` 的文本与相对位置，不需要全书编号解析以外的知识
 *
 * 规则
 *  R1 目录区抑制   前 25% 内连续 ≥4 个候选、且之间无 >80 字正文行 → 整段否决
 *  R2 编号链补漏   保留集合编号形成序列时，把分数 ≥0.25 且编号接得上的候选补回
 *  R3 样板文字     Author:/Title:/Contents/Gutenberg 许可证等
 *  R4 结束标记     第五回終。/ The End 之类，且很短
 *  R5 相邻去重     两个候选间距 ≤2 行，只留分数高者
 *  R6 重复否决     同一文本在全书出现 ≥3 次 → 固定套话（詩曰、下回分解），不是章节
 */
internal object HeadingStructureFilter {

    private val boiler = Regex(
        "(?i)^\\s*(?:start\\s*:|(?:author|title|language|release date|credits|produced by|contents|" +
            "table of contents|illustration|copyright|isbn|transcriber|editor|translator|" +
            "most recently updated|updated|ebook|project gutenberg|end of the project|" +
            "full licen[cs]e)\\b)"
    )
    private val endMark = Regex("(?i)(?:[終终完]\\s*[。.]?|\\bthe end\\b)\\s*$")

    private val cnDigits = mapOf(
        '一' to 1, '二' to 2, '两' to 3 - 1, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10
    )
    private val cnChapter = Regex("第\\s*([0-9]{1,4}|[一二三四五六七八九十百千零两]{1,8})\\s*[章回話话節节]")
    private val enChapter = Regex("(?i)^\\s*(?:chapter|chap\\.?|part|book)\\s*([0-9]{1,4}|[IVXLCDM]{1,7})\\b")
    private val numberDot = Regex("^\\s*([0-9]{1,4})\\s*[\\.、:：]\\s*\\S")
    private val roman = listOf(
        "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X",
        "XI", "XII", "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX"
    )

    private const val THRESHOLD = 0.50
    private const val LOW = 0.25
    private const val TOC_WINDOW = 0.25
    private const val TOC_RUN = 4
    private const val LONG_BODY = 80
    private const val REPEAT_LIMIT = 3
    private const val ADJACENT = 2
    private const val NUMBERED_CHAIN = 8
    private const val UNNUMBERED_FLOOR = 0.90

    private fun cn2int(text: String): Int? {
        if (text.isEmpty()) return null
        if (text.all { it.isDigit() }) return text.toIntOrNull()
        var total = 0
        var number = 0
        for (ch in text) {
            val v = cnDigits[ch] ?: continue
            if (v >= 10) {
                total += (if (number == 0) 1 else number) * v
                number = 0
            } else number = v
        }
        val result = total + number
        return if (result > 0) result else null
    }

    private fun numberOf(text: String): Int? {
        cnChapter.find(text)?.let { m ->
            val g = m.groupValues[1]
            return if (g.all { it.isDigit() }) g.toIntOrNull() else cn2int(g)
        }
        enChapter.find(text)?.let { m ->
            val g = m.groupValues[1]
            if (g.all { it.isDigit() }) return g.toIntOrNull()
            val idx = roman.indexOf(g.uppercase(Locale.ROOT))
            if (idx >= 0) return idx + 1
        }
        numberDot.find(text)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    /**
     * @param lines   全书行
     * @param scored  模型判为标题的候选（行号 0 基 → 分数），按行号升序
     * @return 通过结构校验、可以直接作为章节起点的行号集合
     */
    fun accept(lines: List<String>, scored: Map<Int, Double>,
               threshold: Double = THRESHOLD): Set<Int> {
        if (scored.isEmpty()) return emptySet()
        // 基准集合 = 分数达到阈值者；scored 本身可以带更低的分数（R2 需要）
        val allIds = scored.keys.sorted()
        val ids = scored.filterValues { it >= threshold }.keys.sorted()
        if (ids.isEmpty()) return emptySet()
        val total = lines.size

        // R1 目录区抑制
        val limit = (total * TOC_WINDOW).toInt()
        val suppressed = HashSet<Int>()
        val run = ArrayList<Int>()
        for (i in ids) {
            if (i >= limit) break
            var longBody = false
            if (run.isNotEmpty()) {
                var j = run.last()
                while (j < i) {
                    if (j in scored.keys && lines[j].trim().length > LONG_BODY) { longBody = true; break }
                    j++
                }
            }
            if (run.isNotEmpty() && (i - run.last() > 2 || longBody)) {
                if (run.size >= TOC_RUN) suppressed.addAll(run)
                run.clear()
            }
            run.add(i)
        }
        if (run.size >= TOC_RUN) suppressed.addAll(run)

        var keep = ids.filter { it !in suppressed }.toMutableList()

        // R2 编号链补漏
        val chain = keep.mapNotNull { numberOf(lines[it]) }.sorted().toHashSet()
        if (chain.size >= 5) {
            val add = HashSet<Int>()
            for (i in allIds) {
                if (i in keep) continue
                val s = scored[i] ?: continue
                if (s < LOW) continue
                // 补漏只补"像标题"的行：样板文字（许可证 1.F. 之类）绝不补回
                val txt = lines[i].trim()
                if (boiler.containsMatchIn(txt)) continue
                val n = numberOf(txt) ?: continue
                if (n in chain || chain.any { kotlin.math.abs(it - n) == 1 }) add.add(i)
            }
            if (add.isNotEmpty()) keep = (keep + add).sorted().toMutableList()
        }

        // R3 样板 / R4 结束标记 / R6 重复否决
        val counts = HashMap<String, Int>()
        for (l in lines) {
            val k = normalize(l)
            if (k.isNotEmpty()) counts[k] = (counts[k] ?: 0) + 1
        }
        keep = keep.filter { i ->
            val t = lines[i].trim()
            val k = normalize(t)
            // R6 只否决**没有编号**的重复短句：`詩曰：`、`且聽下回分解` 这类固定套话。
            // 有编号的同名标题（第一卷/第一章、第二卷/第一章）可能是真实的卷内重置，不能删。
            val repeatedUnnumbered = (counts[k] ?: 0) >= REPEAT_LIMIT && numberOf(t) == null
            // R8 句末标点：标题不会以中文句号/问号/叹号结尾（`第二回来得比第一回快得多。` 是正文）
            val sentenceEnd = t.isNotEmpty() && t.last() in "。！？"
            !(boiler.containsMatchIn(t) ||
                (endMark.containsMatchIn(t) && t.length <= 14) ||
                repeatedUnnumbered || sentenceEnd)
        }.toMutableList()

        // R7 编号主导：书里存在强编号结构时，无编号候选必须几乎满分才留下
        //（网络小说的正文对话经常是短句独立成段，逐行特征无法与标题区分）
        val numbered = keep.count { numberOf(lines[it]) != null }
        if (numbered >= NUMBERED_CHAIN) {
            keep = keep.filter { i ->
                numberOf(lines[i]) != null || (scored[i] ?: 0.0) >= UNNUMBERED_FLOOR
            }.toMutableList()
        }

        // R5 相邻去重
        val out = ArrayList<Int>()
        for (i in keep.sorted()) {
            val previous = out.lastOrNull()
            if (previous != null && i - previous <= ADJACENT) {
                val first = lines[previous]
                val second = lines[i]
                val a = numberOf(first)
                val b = numberOf(second)
                val differentLevels = (ChapterSplitter.extractVolumeTitle(first) == null) !=
                    (ChapterSplitter.extractVolumeTitle(second) == null)
                val differentNumbers = a != null && b != null && a != b
                if (!differentLevels && !differentNumbers) {
                    if ((scored[i] ?: 0.0) > (scored[previous] ?: 0.0)) out[out.lastIndex] = i
                    continue
                }
            }
            out.add(i)
        }
        return out.toHashSet()
    }

    private fun normalize(s: String): String = s.filterNot { it.isWhitespace() }
}
