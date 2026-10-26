package com.inkreader.parse

/** 全书校对按页返回标题与短上下文，不把整本正文交给界面。 */
object RecognitionBrowser {
    data class Row(val line: Int, val title: String, val context: String, val selected: Boolean, val editable: Boolean)
    data class Page(val fingerprint: String, val rows: List<Row>, val total: Int, val page: Int,
        val chapters: Int, val sourceLines: Int, val sourceView: Boolean)

    fun page(document: PreparedTxt, query: String, page: Int, doubtsOnly: Boolean): Page {
        val lines = document.text.lines
        val target = query.trim().takeIf { it.startsWith("#") }?.drop(1)?.toIntOrNull()
        val sourceView = target != null
        val selected = document.plan.headings.toHashSet()
        val edited = document.options.ignoredHeadingLines + document.options.forcedHeadingLines
        val decisions = document.plan.decisions.associateBy { it.line - 1 }
        val candidates = if (sourceView) {
            val first = (target!! - 1).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
            (first until minOf(lines.size, first + 40)).toList()
        } else {
            val needle = HeadingNumbers.digits(query.trim()).lowercase()
            (document.plan.headings + document.plan.reviewLines + edited.map { it - 1 } + decisions.keys).distinct().sorted().filter { i ->
                i in lines.indices && (!doubtsOnly || i + 1 in edited || i in document.plan.doubtLines || decisions[i]?.let { !it.accepted || it.score < 74 } == true ||
                    document.plan.gaps.any { it.after == lines[i].trim() || it.before == lines[i].trim() }) &&
                    (needle.isEmpty() || HeadingNumbers.digits(lines[i]).lowercase().contains(needle))
            }
        }
        val safePage = page.coerceIn(0, ((candidates.size - 1) / 40).coerceAtLeast(0))
        val visible = if (sourceView) candidates else candidates.drop(safePage * 40).take(40)
        val rows = visible.map { i ->
            ParseCancellation.checkpoint()
            val title = lines[i].trim()
            val length = (lines as? SizedLines)?.characters(i, true) ?: title.length
            val near = ((i - 1).coerceAtLeast(0)..minOf(lines.lastIndex, i + 3)).filter { it != i && lines[it].isNotBlank() }
                .take(2).joinToString("\n") { lines[it].trim().take(140) }
            Row(i + 1, title, near, i in selected, title.isNotEmpty() && length <= 200)
        }
        return Page(document.fingerprint, rows, candidates.size, if (sourceView) 0 else safePage,
            document.sections.size, lines.size, sourceView)
    }
}
