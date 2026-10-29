package com.inkreader.parse

/** Numeric-only features shared by training export and on-device inference. No book names or dictionaries. */
internal class HeadingFeatures(private val lines: List<String>) {
    companion object {
        val names = listOf("length", "short", "one_char", "digits", "han", "latin", "other_letters", "punctuation",
            "sentence_end", "blank_before", "blank_after", "blank_both", "previous_length", "next_length",
            "position", "uppercase", "numeric_only", "quote_start", "colon_end", "following_body", "near_short_lines")
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
}
