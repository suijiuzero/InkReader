package com.inkreader.parse

import com.inkreader.data.repo.TextEditMap

/** 正文变换与旁挂样式始终使用相同的字符坐标。 */
object RichTransforms {
    private fun canonical(text: String, blocks: List<RichBlock>, spans: List<InlineStyle>, links: List<LinkSpan>): RichChapter? {
        if (text.isEmpty()) return null
        val result = ArrayList<RichBlock>()
        var start = 0
        while (start < text.length) {
            val end = text.indexOf("\n\n", start).let { if (it < 0) text.length else it }
            val old = blocks.firstOrNull { it.start < end && it.end > start }
            result += old?.copy(start = start, end = end) ?: RichBlock(RichKind.BODY, 0, "", start, end)
            start = end + 2
        }
        // 尾部两个换行也需要一个零长块，以保持块区间与正文的对应。
        if (text.endsWith("\n\n")) result += RichBlock(RichKind.BODY, 0, "", text.length, text.length)
        return RichChapter(text.length, result, spans, links)
    }

    fun slice(rich: RichChapter?, text: String, start: Int, end: Int): RichChapter? {
        if (rich == null) return null
        val body = text.substring(start, end)
        fun clipped(a: Int, b: Int) = (a - start).coerceIn(0, body.length) to (b - start).coerceIn(0, body.length)
        val blocks = rich.blocks.mapNotNull { b -> clipped(b.start, b.end).let { (a, z) -> if (z > a) b.copy(start = a, end = z) else null } }
        val spans = rich.spans.mapNotNull { b -> clipped(b.start, b.end).let { (a, z) -> if (z > a) b.copy(start = a, end = z) else null } }
        val links = rich.links.mapNotNull { b -> clipped(b.start, b.end).let { (a, z) -> if (z > a) b.copy(start = a, end = z) else null } }
        return canonical(body, blocks, spans, links)
    }

    fun merge(text: String, first: RichChapter?, second: RichChapter?, shift: Int): RichChapter? {
        if (first == null && second == null) return null
        val blocks = first?.blocks.orEmpty() + second?.blocks.orEmpty().map { it.copy(start = it.start + shift, end = it.end + shift) }
        val spans = first?.spans.orEmpty() + second?.spans.orEmpty().map { it.copy(start = it.start + shift, end = it.end + shift) }
        val links = first?.links.orEmpty() + second?.links.orEmpty().map { it.copy(start = it.start + shift, end = it.end + shift) }
        return canonical(text, blocks, spans, links)
    }

    fun edit(rich: RichChapter?, change: TextEditMap): RichChapter? {
        if (rich == null) return null
        val blocks = rich.blocks.mapNotNull { b -> change.range(b.start, b.end).let { (a, z) -> if (z > a) b.copy(start = a, end = z) else null } }
        val spans = rich.spans.mapNotNull { b -> change.range(b.start, b.end).let { (a, z) -> if (z > a) b.copy(start = a, end = z) else null } }
        val links = rich.links.mapNotNull { b -> change.range(b.start, b.end).let { (a, z) -> if (z > a) b.copy(start = a, end = z) else null } }
        return canonical(change.after, blocks, spans, links)
    }
}
