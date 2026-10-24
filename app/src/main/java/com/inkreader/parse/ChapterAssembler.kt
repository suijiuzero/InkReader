package com.inkreader.parse

import com.inkreader.data.model.SplitMode
import com.inkreader.data.model.SplitOptions

/**
 * 「带层级的段落流 → 章节」的组装规则。
 *
 * 原先只存在于 [DocxParser] 内部，现在 Markdown / PDF / HTML / EPUB 解析出来的
 * 也都是同一形态（每个段落带一个标题层级，正文为 [BODY]），统一走这里，
 * 保证不同格式的分章行为一致：
 *  · 结合明确章号与卷标题选择分章层级，未满足条件时按标题样式层级处理；
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

    internal data class Heading(val level: Int, val title: String, val start: Int, val end: Int = start + 1)
    internal data class Section(val heading: Heading, val start: Int, val end: Int, val volume: String)

    /** Prefer repeated explicit chapter headings when parents are volumes or a single book title. */
    internal fun plan(headings: List<Heading>, end: Int): List<Section> {
        if (headings.isEmpty()) return emptyList()
        val numbered = headings.filter { ChapterSplitter.isNumberedChapterTitle(it.title) }
        val repeated = numbered.groupingBy { it.level }.eachCount().filterValues { it >= 2 }.keys.sorted()
        val semantic = repeated.firstOrNull { level ->
            val parents = headings.filter { it.level < level }
            parents.none { ChapterSplitter.isNumberedChapterTitle(it.title) } &&
                parents.count { ChapterSplitter.extractVolumeTitle(it.title) == null && !ChapterSplitter.isBookendTitle(it.title) } <= 1
        }
        val shallow = headings.minOf { it.level }
        val declaredParent = numbered.any { it.level == shallow }
        val level = semantic ?: (if (declaredParent) shallow else chooseChapterLevel(headings.map { it.level })) ?: return emptyList()
        val chapters = headings.filter {
            (it.level == level && (semantic == null || ChapterSplitter.extractVolumeTitle(it.title) == null)) ||
                (semantic != null && it.level < level && ChapterSplitter.isBookendTitle(it.title))
        }
        val volumes = headings.filter { (it.level < level || (semantic != null && it.level == level)) && ChapterSplitter.extractVolumeTitle(it.title) != null }
        val starts = ArrayList<Int>()
        val names = ArrayList<String>()
        var volumeIndex = 0
        var volume = ""
        chapters.forEachIndexed { index, chapter ->
            val previous = chapters.getOrNull(index - 1)?.end ?: 0
            var start = chapter.start
            while (volumeIndex < volumes.size && volumes[volumeIndex].start < chapter.start) {
                val current = volumes[volumeIndex++]
                if (current.start >= previous && start == chapter.start) start = current.start
                volume = current.title
            }
            starts += start
            names += volume
        }
        return chapters.mapIndexed { index, chapter ->
            Section(chapter, starts[index], starts.getOrNull(index + 1) ?: end, names[index])
        }
    }

    fun assemble(paragraphs: List<Para>, fallbackTitle: String = "正文"): List<ChapterSplitter.Part> {
        if (paragraphs.isEmpty()) return emptyList()
        val headings = paragraphs.mapIndexedNotNull { index, para ->
            if (para.level >= 0) Heading(para.level, para.text, index) else null
        }
        if (headings.isEmpty()) {
            return ChapterSplitter.split(ChapterSplitter.normalize(paragraphs.joinToString("\n\n") { it.text }), fallbackTitle)
        }
        val sections = plan(headings, paragraphs.size)
        val parts = ArrayList<ChapterSplitter.Part>()
        val head = paragraphs.take(sections.first().start).joinToString("\n\n") { it.text }.trim()
        if (head.isNotEmpty()) {
            val first = head.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
            parts += ChapterSplitter.Part(first.takeIf { it.length in 1..24 } ?: "前言", head)
        }
        sections.forEachIndexed { index, section ->
            val title = section.heading.title.ifBlank { "第 ${index + 1} 章" }
            val body = (section.start until section.end).filter { it !in section.heading.start until section.heading.end }
                .joinToString("\n\n") { paragraphs[it].text }.trim().ifEmpty { title }
            if (body.length <= 120000) {
                parts += ChapterSplitter.Part(title, body, section.volume)
            } else {
                val chunks = ChapterSplitter.split(body, title, SplitOptions(
                    mode = SplitMode.FIXED_SIZE, fixedChunkChars = 50000, cleanWatermark = false))
                chunks.forEachIndexed { number, chunk -> parts += chunk.copy(
                    title = if (number == 0) title else "$title（${number + 1}）", volume = section.volume) }
            }
        }
        return parts
    }
}
