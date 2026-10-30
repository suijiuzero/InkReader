package com.inkreader.parse

import com.inkreader.data.model.SplitOptions

/** Collapse adjacent repeated *heading lines*, never repeated prose or numbered subparts. */
internal object DuplicateChapterHeadings {
    data class Result(val headings: List<Int>, val omitted: Set<Int>)
    private data class Info(val number: Int, val kind: String, val subtitle: String, val segmented: Boolean)
    private val prefix = Regex("^(?:第\\s*[0-9零〇一二三四五六七八九十百千万萬两兩壹贰貳叁參肆伍陆陸柒捌玖拾佰仟]{1,10}\\s*([章回话話节節])|(?i:chapter|chap|episode|ep)\\.?\\s+[0-9IVXLCDM]+)(.*)$")
    private val subpart = Regex(".*[（(\\[【].*[）)\\]】]$")
    private val contentMarkers = setOf("正文", "正文内容", "正文內容", "章节正文", "章節正文")
    private val metadata = Regex("(?i)^(?:作者|著者|author|by)\\s*[:：].{1,40}$")
    private fun info(raw: String): Info? {
        if (raw.length !in 1..80 || raw.last() in "。！？；，!?;：:") return null
        val title = HeadingNumbers.canonical(raw.trim())
        if (ChapterSplitter.looksLikeChapterReference(title)) return null
        val match = prefix.matchEntire(title) ?: return null
        val number = ChapterSplitter.chapterOrderFromTitle(title)?.takeIf { it in 1..10000000 } ?: return null
        val kind = match.groupValues[1].ifEmpty { if (title.startsWith("ep", true)) "话" else "章" }.replace('話', '话').replace('節', '节')
        val subtitleText = match.groupValues[2].trimStart { it.isWhitespace() || it in ":：.,，、—–-·" }
        val subtitle = subtitleText.filterNot { it.isWhitespace() }.lowercase(java.util.Locale.ROOT)
        if (subtitle.startsWith("里提到") || subtitle.startsWith("中提到") || subtitle.startsWith("的内容") ||
            subtitleText.substringBefore(' ').lowercase(java.util.Locale.ROOT) in setOf("is", "was", "mentions", "describes")) return null
        return Info(number, kind, subtitle, subpart.matches(title))
    }
    fun resolve(lines: List<String>, chosen: List<Int>, options: SplitOptions, excluded: Set<Int> = emptySet(), candidates: Set<Int>? = null): Result {
        if (chosen.isEmpty() || options.mode == com.inkreader.data.model.SplitMode.CUSTOM) return Result(chosen, emptySet())
        val bookLabel = lines.asSequence().take(24).map { it.trim() }.firstOrNull { it.isNotBlank() }
            ?.takeIf { it.length <= 60 && info(it) == null }
        val selected = chosen.toMutableSet(); val omitted = HashSet<Int>(); val processed = HashSet<Int>()
        val protected = (options.forcedHeadingLines + options.ignoredHeadingLines).map { it - 1 }.toSet() + excluded
        fun shortLine(index: Int, max: Int): String? {
            if ((lines as? SizedLines)?.characters(index, true)?.let { it > max } == true) return null
            return lines[index].trim().takeIf { it.length <= max }
        }
        for (anchor in chosen) {
            ParseCancellation.checkpoint()
            if (anchor in processed || anchor in protected) continue
            val base = shortLine(anchor, 80)?.let(::info) ?: continue
            val cluster = linkedMapOf(anchor to base)
            for (step in listOf(-1, 1)) {
                var index = anchor + step; var chars = 0; var proseLines = 0
                while (index in lines.indices && kotlin.math.abs(index - anchor) <= 12) {
                    if (index in protected) break
                    val raw = shortLine(index, 80) ?: break
                    if (raw.isNotBlank()) {
                        val other = info(raw)
                        if (other != null) {
                            if (candidates != null && index !in candidates) break
                            if (other.number != base.number || other.kind != base.kind ||
                                (other.subtitle.isEmpty() && base.segmented || base.subtitle.isEmpty() && other.segmented) ||
                                other.subtitle.isNotEmpty() && base.subtitle.isNotEmpty() && other.subtitle != base.subtitle) break
                            cluster[index] = other
                        } else {
                            // Allow explicit author/book/content labels between header copies; retain these lines in the body.
                            chars += raw.length; proseLines++
                            if ((raw != bookLabel && !metadata.matches(raw) && raw !in contentMarkers) || raw.length > 60 || chars > 100 || proseLines > 3 || raw.any { it in "。！？；，!?;" } ||
                                ChapterSplitter.extractVolumeTitle(raw) != null || index in selected) break
                        }
                    }
                    index += step
                }
            }
            if (cluster.size < 2) continue
            val named = cluster.values.map { it.subtitle }.filter { it.isNotEmpty() }.distinct()
            if (named.size > 1) continue
            // A richer copy supplies the title; an exact repeated copy keeps its original first position.
            val keep = cluster.keys.sortedWith(compareByDescending<Int> { cluster.getValue(it).subtitle.length }.thenBy { it }).first()
            selected.removeAll(cluster.keys); selected += keep
            omitted += cluster.keys.filter { it != keep }; processed += cluster.keys
        }
        return Result(selected.sorted(), omitted)
    }
}
