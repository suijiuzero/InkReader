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
    private val western = Regex("^(?iu:cap[ií]tulo|capitolo|cap[ií]tol|chapitre|kapitel|глава|розділ|rozdział|chương|bab|bölüm|الفصل|ألفصل|अध्याय|บทที่)(?=\\s|[:：.]|[0-9])\\s*[:：.]?\\s*([0-9]{1,9}|\\p{L}{1,20})(?=\\s|[:：.,、—–-]|$)")
    private val strictRoman = Regex("(?i)M{0,3}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})")
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
        western.find(text)?.let { match ->
            val token = match.groupValues[1]
            val label = match.value.substring(0, match.groups[1]!!.range.first - match.range.first)
                .trim().trimEnd('.', ':', '：').lowercase(java.util.Locale.ROOT)
            val number = when {
                token.all { it.isDigit() } -> token
                token.isNotEmpty() && strictRoman.matches(token) -> token
                label == "chapitre" && token.lowercase(java.util.Locale.ROOT) in setOf("premier", "première") -> "1"
                label in setOf("capítulo", "capitulo") && token.lowercase(java.util.Locale.ROOT) in setOf("primero", "primer", "primera") -> "1"
                label == "capitolo" && token.lowercase(java.util.Locale.ROOT) in setOf("primo", "prima") -> "1"
                else -> null
            }
            if (number != null) text = text.replaceRange(match.range, "Chapter $number")
        }
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
