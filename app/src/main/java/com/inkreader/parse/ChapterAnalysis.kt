package com.inkreader.parse

/** Scores are rule evidence, not calibrated probabilities. Lines refer to normalized source TXT. */
enum class HeadingReason { TITLE_SHAPE, ISOLATED_LINE, NUMBER_SEQUENCE, LOCAL_STYLE, NUMBER_RECOVERY, USER_IGNORED, USER_CONFIRMED, SEQUENCE_ALTERNATIVE, UNNUMBERED_TITLE }

data class HeadingDecision(
    val line: Int,
    val title: String,
    val score: Int,
    val reasons: Set<HeadingReason>,
    val accepted: Boolean
)

data class NumberGap(val after: String, val before: String, val missing: List<Int>)

data class ChapterAnalysis(
    val parts: List<ChapterSplitter.Part>,
    val decisions: List<HeadingDecision>,
    val gaps: List<NumberGap>,
    val skippedTocEntries: Int,
    val usedSizeFallback: Boolean,
    val truncated: Boolean
)

/** Body-free recognition plan shared by previews, in-memory and file-backed sources. */
data class ChapterPlan(
    val headings: List<Int>,
    val decisions: List<HeadingDecision>,
    val gaps: List<NumberGap>,
    val skippedTocEntries: Int,
    val mergeLeadingBody: Boolean,
    val truncated: Boolean,
    val reviewLines: List<Int> = emptyList(),
    val doubtLines: Set<Int> = emptySet()
)
