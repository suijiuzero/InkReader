package com.inkreader.parse

import java.util.Locale

/** Running text is evidence only at page edges; chapter headings and middle-page text are protected. */
internal class PdfArtifacts(pages: List<List<PdfParagraphs.Line>>) {
    private val repeated: Set<String>
    init {
        val counts = HashMap<String, Int>()
        for (page in pages) {
            val keys = (page.take(2) + page.takeLast(2)).map { it.text.trim() }
                .filter { it.length in 1..60 && it.none { c -> c in "。！？；.!?;" } }
                .map(::normalize).toSet()
            keys.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        repeated = if (pages.size < 4) emptySet() else
            counts.filterValues { it >= maxOf(3, (pages.size * .55f).toInt()) }.keys
    }

    fun drop(line: PdfParagraphs.Line, index: Int, count: Int): Boolean {
        if (index >= 2 && index < count - 2) return false
        val text = line.text.trim()
        if (ChapterSplitter.isNumberedChapterTitle(text) || ChapterSplitter.isBookendTitle(text)) return false
        return text.isEmpty() || pageNumber.matches(text) || labeledPage.matches(text) || normalize(text) in repeated
    }

    private fun normalize(text: String) = text.lowercase(Locale.ROOT).replace(spaces, "").replace(digits, "#")
    private companion object {
        val spaces = Regex("\\s+")
        val digits = Regex("\\d+")
        val pageNumber = Regex("^[-—–\\[\\]()\\s]*\\d{1,4}[-—–\\[\\]()\\s]*$")
        val labeledPage = Regex("(?i)^(第\\s*\\d+\\s*页|page\\s*\\d+|\\d+\\s*/\\s*\\d+)$")
    }
}
