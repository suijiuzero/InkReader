package com.inkreader.parse

import java.text.Normalizer

/** Recognition-only transforms. The source title and body are never rewritten. */
object HeadingNumbers {
    data class Interval(val first: Int, val last: Int)
    private val wrappers = mapOf('〔' to '〕', '【' to '】', '[' to ']', '(' to ')', '（' to '）')
    private fun wrapped(raw: String): Pair<String, String>? {
        val text = raw.trim()
        val close = wrappers[text.firstOrNull()] ?: return null
        val end = text.indexOf(close, 1)
        return if (end > 1) text.substring(1, end) to text.substring(end) else null
    }
    private val western = Regex("^(?iu:cap[ií]tulo|cap[ií]tol|chapitre|kapitel|глава|розділ|rozdział|chương|bab|bölüm|الفصل|ألفصل|अध्याय|บทที่)\\s*[:：.]?\\s*(?=\\d)")
    private val korean = Regex("^제?\\s*([0-9]{1,9})\\s*장(?=\\s|[:：.]|$)")
    private val cnRange = Regex("^(?:第\\s*)?([0-9]{1,9})\\s*[-–—~～至到]\\s*([0-9]{1,9})\\s*([章回话話])(?=\\s|[:：、.]|$)")
    private val enRange = Regex("^(?i:chapter|chap|ch|episode|ep)\\.?\\s*([0-9]{1,9})\\s*[-–—~～至到]\\s*([0-9]{1,9})(?=\\s|[:：、.]|$)")

    fun digits(raw: String): String {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        return buildString(normalized.length) {
            var i = 0
            while (i < normalized.length) {
                val cp = normalized.codePointAt(i)
                val digit = Character.digit(cp, 10)
                if (digit >= 0) append(('0'.code + digit).toChar()) else appendCodePoint(cp)
                i += Character.charCount(cp)
            }
        }
    }

    private fun labels(raw: String): String {
        var text = digits(raw.trim()).replace("\u200E", "").replace("\u200F", "")
        text = western.replaceFirst(text, "Chapter ")
        korean.find(text)?.let { text = text.replaceRange(it.range, "Chapter ${it.groupValues[1]}") }
        return text
    }

    fun interval(raw: String): Interval? {
        wrapped(raw)?.let { return interval(it.first) }
        val text = labels(raw)
        val match = cnRange.find(text) ?: enRange.find(text) ?: return null
        val first = match.groupValues[1].toIntOrNull() ?: return null
        val last = match.groupValues[2].toIntOrNull() ?: return null
        return Interval(first, last).takeIf { first > 0 && last >= first && last - first <= 1000 }
    }

    fun canonical(raw: String): String {
        wrapped(raw)?.let { return raw.trim().first() + canonical(it.first) + it.second }
        val text = labels(raw)
        val match = cnRange.find(text) ?: enRange.find(text) ?: return text
        val interval = interval(text) ?: return text
        val mark = match.groupValues.getOrNull(3)
        return text.replaceRange(match.range, if (mark != null) "第${interval.first}$mark" else "Chapter ${interval.first}")
    }
}
