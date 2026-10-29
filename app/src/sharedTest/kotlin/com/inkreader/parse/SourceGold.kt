package com.inkreader.parse

import java.util.Base64

/** Frozen answers are read independently; no labels are inferred from parser output. */
object SourceGold {
    data class Gold(val titles: List<String>, val lines: Set<Int>, val paragraphs: List<String>)
    data class Score(val tp: Int, val fp: Int, val fn: Int, val bodyErrors: Int, val titlesMatch: Boolean) {
        val exact get() = fp == 0 && fn == 0 && bodyErrors == 0 && titlesMatch
    }
    fun normalizeContract(raw: String): String {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').replace("\uFEFF", "")
            .replace("\u200B", "").replace('\u00A0', ' ').lines().map { it.trimEnd() }
        val out = arrayListOf<String>()
        for (line in lines) if (line.isNotBlank() || out.lastOrNull()?.isNotBlank() == true) out += line
        return out.joinToString("\n").trim()
    }
    fun read(raw: String, source: String): Gold {
        val rows = raw.lines()
        val titles = rows.filter { it.startsWith("T\t") }.map { it.substring(2) }
        val rawLines = source.lines()
        val boundaries = rows.filter { it.startsWith("L\t") }.map { it.substring(2).toInt() }
        require(titles.size == boundaries.size)
        val normalizedLines = normalizeContract(source).lines()
        val mapped = boundaries.mapIndexed { index, line ->
            require(rawLines[line - 1].trim() == titles[index]) { "Raw source label points to a different line" }
            val matches = normalizedLines.indices.filter { normalizedLines[it].trim() == titles[index] }
            require(matches.size == 1) { "Ambiguous normalized source label" }
            matches.single() + 1
        }.toSet()
        return Gold(titles, mapped,
            rows.filter { it.startsWith("P64\t") }.map { String(Base64.getDecoder().decode(it.substring(4)), Charsets.UTF_8) })
    }
    fun bodyErrors(body: String, gold: Gold): Int {
        val text = normalizeContract(body)
        val paragraphs = gold.paragraphs.map { normalizeContract(it).trim() }
        val actualCounts = text.split(Regex("\\n[ \\t]*\\n")).map { it.trim() }.groupingBy { it }.eachCount()
        var errors = 0
        var cursor = 0
        // Check order and exact multiplicity, including naturally repeated dialogue.
        for (paragraph in paragraphs) {
            val at = text.indexOf(paragraph, cursor)
            if (at < 0) errors++ else cursor = at + paragraph.length
        }
        for ((paragraph, count) in paragraphs.groupingBy { it }.eachCount()) {
            if (actualCounts[paragraph] != count) errors++
        }
        return errors
    }
    /** Source blocks may cross the app's explicit size-fallback chapter seams.
     * Only whitespace at those seams may differ; internal spaces and every glyph
     * still match exactly. This does not relax ordinary paragraph evaluation. */
    fun blockErrors(parts: List<String>, gold: Gold): Int {
        val seam = '\u0001'
        val text = parts.joinToString(seam.toString()) { normalizeContract(it) }
        val needles = gold.paragraphs.map { normalizeContract(it) }
        fun ends(at: Int) = at == text.length || text.getOrNull(at) == seam || text.startsWith("\n\n", at)
        fun matches(needle: String): List<Pair<Int,Int>> {
            val found = arrayListOf<Pair<Int,Int>>()
            var start = 0
            while (start < text.length) {
                start = text.indexOf(needle[0], start)
                if (start < 0) break
                if (start == 0 || text[start - 1] == seam || (start >= 2 && text.substring(start - 2, start) == "\n\n")) {
                    var a = start; var b = 0
                    while (b < needle.length && a < text.length) {
                        if (text[a] == seam) { a++; if (needle[b].isWhitespace()) b++ }
                        else if (text[a] == needle[b]) { a++; b++ }
                        else break
                    }
                    if (b == needle.length && ends(a)) found += start to a
                }
                start++
            }
            return found
        }
        val locations = needles.distinct().associateWith(::matches)
        var errors = 0; var cursor = 0
        for (needle in needles) {
            val match = locations.getValue(needle).firstOrNull { it.first >= cursor }
            if (match == null) errors++ else cursor = match.second
        }
        for ((needle, count) in needles.groupingBy { it }.eachCount()) if (locations.getValue(needle).size != count) errors++
        return errors
    }
    fun score(result: ChapterAnalysis, gold: Gold, blocks: Boolean = false): Score {
        val accepted = result.decisions.filter { it.accepted }
        val actual = accepted.map { it.line }.toSet()
        return Score((gold.lines intersect actual).size, (actual - gold.lines).size,
            (gold.lines - actual).size, if (blocks) blockErrors(result.parts.map { it.content }, gold)
                else bodyErrors(result.parts.joinToString("\n\n") { it.content }, gold),
            accepted.map { it.title } == gold.titles)
    }
}
