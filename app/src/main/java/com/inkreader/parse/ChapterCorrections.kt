package com.inkreader.parse

import java.security.MessageDigest

/** Exact title + nearby body anchors. Ambiguous or edited anchors never force a split. */
object ChapterCorrections {
    data class Anchor(val title: String, val context: String, val keep: Boolean)
    data class Resolution(val ignored: Set<Int>, val confirmed: Set<Int>, val unmatched: Int)
    private fun context(lines: List<String>, line: Int): String {
        val next = (line + 1 until minOf(lines.size, line + 5)).firstOrNull { lines[it].isNotBlank() }
        val value = lines[line].trim() + "\u0000" + (next?.let { lines[it].trim().take(160) } ?: "")
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
    }
    fun capture(lines: List<String>, decisions: List<HeadingDecision>): List<Anchor> = decisions
        .filter { it.accepted || HeadingReason.USER_IGNORED in it.reasons }.take(40).mapNotNull {
        val line = it.line - 1
        if (line !in lines.indices) null else Anchor(lines[line].trim(), context(lines, line), it.accepted)
    }
    fun captureChoices(lines: List<String>, ignored: Set<Int>, confirmed: Set<Int>): List<Anchor> {
        require((ignored + confirmed).size <= 10000)
        return (ignored + confirmed).sorted().mapNotNull { number ->
            val line = number - 1
            if (line !in lines.indices || lines[line].trim().isEmpty()) null
            else Anchor(lines[line].trim(), context(lines, line), number !in ignored)
        }
    }
    fun resolve(lines: List<String>, anchors: List<Anchor>): Resolution {
        val titles = anchors.map { it.title }.toSet()
        val matches = HashMap<String, MutableList<Int>>()
        for (i in lines.indices) {
            if (i and 511 == 0) ParseCancellation.checkpoint()
            if (lines[i].trim() in titles) matches.getOrPut(context(lines, i)) { ArrayList() }.add(i + 1)
        }
        val ignored = HashSet<Int>(); val confirmed = HashSet<Int>(); var unmatched = 0
        for (anchor in anchors) {
            val line = matches[anchor.context]?.singleOrNull()
            if (line == null) unmatched++ else if (anchor.keep) confirmed += line else ignored += line
        }
        return Resolution(ignored, confirmed - ignored, unmatched)
    }
}
