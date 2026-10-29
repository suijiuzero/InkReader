package com.inkreader.parse

/** Layout evidence only, never a list of known book/story names. Opt-in for TXT. */
internal object UnnumberedTitles {
    fun find(lines: List<String>, fenced: BooleanArray, reconstructedFirstTitle: String = ""): List<Int> {
        val numeric = Regex("^[零〇一二三四五六七八九十百千万兩两壹贰貳叁參肆伍陆陸柒捌玖拾佰仟0-9０-９]+$")
        val allowed = Regex("^[\\p{IsHan}A-Za-z《》〈〉（）()·・—－ -]{1,28}$")
        val rejectedStarts = listOf("他说", "他說", "她说", "她說", "我说", "我說", "你说", "你說", "于是", "於是", "然后", "然後", "所以", "因此", "但是", "然而", "请注意", "請注意")
        var candidates = lines.indices.filter { i ->
            if (i and 511 == 0) ParseCancellation.checkpoint()
            val title = lines[i].trim()
            !fenced[i] && title.isNotEmpty() && title.length <= 28 && allowed.matches(title) &&
                title.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } &&
                !numeric.matches(title) && rejectedStarts.none { title.startsWith(it) } &&
                (i == 0 || lines[i - 1].isBlank()) && (i == lines.lastIndex || lines[i + 1].isBlank())
        }
        val prefix = LongArray(lines.size + 1)
        for (i in lines.indices) {
            if (i and 511 == 0) ParseCancellation.checkpoint()
            val count = (lines as? SizedLines)?.characters(i, true) ?: lines[i].trim().length
            prefix[i + 1] = prefix[i] + count.toLong()
        }
        // Reconstruction prepends the known first chapter title before its body.
        // A retained collection-name line immediately after it is body metadata,
        // rather than a competing heading. Use stored metadata only in this case.
        if (reconstructedFirstTitle.isNotBlank() && candidates.firstOrNull() == 0 &&
            lines[0].trim() == reconstructedFirstTitle.trim()) {
            val clustered = candidates.takeWhile { i -> i == 0 || prefix[i] - prefix[1] <= 80 }
            if (clustered.size > 1) candidates = listOf(0) + candidates.drop(clustered.size)
        }
        fun body(p: Int): Long {
            val i = candidates[p]
            val end = candidates.getOrNull(p + 1) ?: lines.size
            return prefix[end] - prefix[i + 1]
        }
        val supported = candidates.filterIndexed { p, _ ->
            body(p) >= 160 || (p == candidates.lastIndex && body(p) >= 80 &&
                p > 0 && body(p - 1) >= 300)
        }
        if (supported.size < 2) return emptyList()
        // A pair needs substantially more body evidence; isolated short lines and
        // dense poem/list rows do not establish a collection heading style.
        if (supported.size == 2 && (supported.any { lines[it].trim().length < 2 } ||
                supported.none { i -> body(candidates.binarySearch(i)) >= 300 })) return emptyList()
        if (supported.size < 4 && supported.any { lines[it].trim().length == 1 }) return emptyList()
        return supported
    }
}
