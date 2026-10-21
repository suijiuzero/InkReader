package com.inkreader.parse

/**
 * 「带层级的段落流 → 章节」的组装规则。
 *
 * 原先只存在于 [DocxParser] 内部，现在 Markdown / PDF / HTML / EPUB 解析出来的
 * 也都是同一形态（每个段落带一个标题层级，正文为 [BODY]），统一走这里，
 * 保证不同格式的分章行为一致：
 *  · 取文档中最浅的标题层级作为分章依据，更深的层级留在正文里；
 *  · 没有标题时退回 [ChapterSplitter] 的启发式分章；
 *  · 标题之前的内容作为「前言」；
 *  · 单章过长时按字数再拆。
 */
object ChapterAssembler {

    /** 正文段落（非标题） */
    const val BODY = -1

    /** 标题层级 0..5，越小越靠上；[BODY] 表示正文 */
    data class Para(val level: Int, val text: String)

    /** 单次书名标题后若有重复的下一层标题，选择重复层级。 */
    fun chooseChapterLevel(levels: List<Int>): Int? {
        val counts = levels.filter { it >= 0 }.groupingBy { it }.eachCount()
        val shallow = counts.keys.minOrNull() ?: return null
        return if (counts[shallow] == 1) {
            counts.keys.sorted().firstOrNull { it > shallow && counts.getValue(it) >= 2 } ?: shallow
        } else shallow
    }

    fun assemble(paragraphs: List<Para>, fallbackTitle: String = "正文"): List<ChapterSplitter.Part> {
        if (paragraphs.isEmpty()) return emptyList()

        // 找到文档中最浅的标题层级，只有它作为分章依据；更深的层级留在正文里
        val levels = paragraphs.mapNotNull { if (it.level >= 0) it.level else null }.distinct().sorted()
        val chapterLevel = chooseChapterLevel(paragraphs.map { it.level })

        val plain = paragraphs.joinToString("\n\n") { it.text }

        if (chapterLevel == null) {
            // 没有标题样式：退回 TXT 的章节识别逻辑
            val normalized = ChapterSplitter.normalize(plain)
            return ChapterSplitter.split(normalized, fallbackTitle)
        }

        val parts = ArrayList<ChapterSplitter.Part>()
        val headBuf = ArrayList<String>()
        var curTitle: String? = null
        val curBuf = ArrayList<String>()

        fun flushChapter() {
            val t = curTitle ?: return
            val body = curBuf.joinToString("\n\n").trim()
            parts += ChapterSplitter.Part(t, body.ifEmpty { t })
            curBuf.clear()
        }

        for (p in paragraphs) {
            if (p.level == chapterLevel) {
                flushChapter()
                curTitle = p.text.ifBlank { "第 ${parts.size + 1} 章" }
            } else {
                val line = if (p.level in (chapterLevel + 1)..(chapterLevel + 3)) {
                    // 子标题：保留为独立段落，前面留一个空行拉开层次
                    curBuf.add("")
                    p.text
                } else p.text
                if (curTitle == null) headBuf += line else curBuf += line
            }
        }
        flushChapter()

        // 标题之前的内容作为前言
        val head = headBuf.joinToString("\n\n").trim()
        if (head.isNotEmpty()) {
            val firstLine = head.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val t = if (firstLine.isNotEmpty() && firstLine.length <= 24) firstLine else "前言"
            parts.add(0, ChapterSplitter.Part(t, head))
        }

        // 单章过长时再按字数拆
        val result = ArrayList<ChapterSplitter.Part>()
        for (p in parts) {
            if (p.content.length > 120000) {
                ChapterSplitter.split(p.content, p.title).forEachIndexed { i, sub ->
                    result += ChapterSplitter.Part(
                        if (i == 0) p.title else "${p.title}（${i + 1}）",
                        sub.content
                    )
                }
            } else result += p
        }
        return if (result.isEmpty()) {
            ChapterSplitter.split(ChapterSplitter.normalize(plain), fallbackTitle)
        } else result
    }
}