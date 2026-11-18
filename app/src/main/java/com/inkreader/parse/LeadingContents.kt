package com.inkreader.parse

import com.inkreader.parse.ChapterSplitter.contentsKey
import com.inkreader.parse.ChapterSplitter.extractVolumeTitle
import com.inkreader.parse.ChapterSplitter.headingText
import com.inkreader.parse.ChapterSplitter.isContentsEntry
import com.inkreader.parse.ChapterSplitter.isNumberedChapterTitle
import com.inkreader.parse.ChapterSplitter.lineCharacters
import com.inkreader.parse.ChapterSplitter.mayBeHeading
import java.util.Locale

/** Identifies a contents-only file or repeated entries at the start of a chapter sequence. */
internal object LeadingContents {
    /** 文首目录区：候选行之间除标题外的正文少于这么多字，就算「密集成簇」 */
    private const val TOC_GAP_CHARS = 30

    /** 文首目录区至少这么多行才算目录（更短的一簇更像正常短章） */
    private const val MIN_ENTRIES = 4
    private const val MAX_ENTRY_CHARS = 96

    private val tocPageTail = Regex("(?:[.·…．⋯]{2,}\\s*|\\t+|\\s{2,})[0-9０-９]{1,6}\\s*$")

    fun withoutPageNumber(raw: String): String {
        // A page-number suffix cannot end in a letter.
        if (raw.lastOrNull()?.isLetter() == true) return raw.trim()
        return tocPageTail.replace(raw, "").trim()
    }

    fun entryTitle(raw: String): String = headingText(withoutPageNumber(raw))

    private val tocLabels = setOf("目录", "目錄", "目次", "contents", "table of contents")

    fun isLabel(raw: String): Boolean =
        raw.trim().lowercase(Locale.ROOT) in tocLabels

    data class NumberedList(val labelLine: Int, val entryCount: Int, val followsBody: Boolean)

    /** A labelled suffix containing only numbered entries and volume banners. */
    fun numberedList(lines: List<String>, fenced: BooleanArray): NumberedList? {
        val label = (0 until minOf(lines.size, 64)).firstOrNull {
            !fenced[it] && lineCharacters(lines, it, trim = true) <= 17 &&
                isLabel(lines[it])
        } ?: return null
        var entries = 0
        for (i in label + 1 until lines.size) {
            if (i and 511 == 0) ParseCancellation.checkpoint()
            val length = lineCharacters(lines, i, trim = true)
            if (length == 0) continue
            if (length > MAX_ENTRY_CHARS || fenced[i]) return null
            val title = entryTitle(lines[i])
            if (extractVolumeTitle(title) != null) continue
            if (!isContentsEntry(title)) return null
            entries++
        }
        if (entries < MIN_ENTRIES) return null
        return NumberedList(label, entries, hasChapterBodyBefore(lines, fenced, label))
    }

    private fun hasChapterBodyBefore(lines: List<String>, fenced: BooleanArray, label: Int): Boolean {
        var sawChapter = false
        var bodyChars = 0
        for (i in 0 until label) {
            if (fenced[i]) continue
            val length = lineCharacters(lines, i, trim = true)
            if (length in 1..44 && isNumberedChapterTitle(lines[i])) {
                sawChapter = true
            } else if (sawChapter) {
                bodyChars += length
                if (bodyChars > MAX_ENTRY_CHARS) return true
            }
        }
        return false
    }

    fun withoutRepeatedEntries(lines: List<String>, candidates: List<Int>): List<Int> {
        if (candidates.size < MIN_ENTRIES) return candidates
        val explicit = lines.take(256).indexOfFirst(::isLabel)
        if (candidates.first() > (if (explicit >= 0) 256 else 45)) return candidates
        var end = 1
        while (end < candidates.size) {
            val gap = lines.subList(candidates[end - 1] + 1, candidates[end]).sumOf { it.trim().length }
            if (gap > TOC_GAP_CHARS) break
            end++
        }
        if (end < MIN_ENTRIES) return candidates
        // Unlabelled short chapters spanning real volumes are not evidence of a contents page.
        if (explicit < 0 && (candidates.first()..candidates[end - 1]).any { extractVolumeTitle(lines[it]) != null }) return candidates
        var activeVolume = ""
        var cursorLine = 0
        val keys = candidates.map { candidate ->
            while (cursorLine <= candidate) {
                val raw = lines[cursorLine++]
                if (raw.length <= 96 && mayBeHeading(raw.trim())) {
                    extractVolumeTitle(entryTitle(raw))?.let { volume ->
                        activeVolume = headingText(volume).lowercase().filterNot { it.isWhitespace() || it in ":：.、—–-" }
                    }
                }
            }
            "$activeVolume|${contentsKey(lines[candidate])}"
        }
        // Repeated chapter numbers in different volumes are distinct contents entries.
        val seen = HashSet<String>()
        val duplicate = (0 until end).firstOrNull { !seen.add(keys[it]) }
        val tocEnd = duplicate ?: end
        if (tocEnd < MIN_ENTRIES - 1) return candidates
        // A missing entry must leave the cursor in place for later entries. Record
        // the last occurrence of each TOC key so misses never rescan the suffix.
        val toc = keys.subList(0, tocEnd)
        val lastPositions = toc.associateWithTo(HashMap()) { -1 }
        for (index in tocEnd until keys.size) {
            if (index and 1023 == 0) ParseCancellation.checkpoint()
            lastPositions.replace(keys[index], index)
        }
        var cursor = tocEnd
        var matched = 0
        for (key in toc) {
            if (lastPositions.getValue(key) < cursor) continue
            while (keys[cursor] != key) cursor++
            cursor++
            matched++
        }
        // Number matches alone need several entries in the same order. Never delete a merely dense cluster.
        if (matched < 3 || matched * 2 < tocEnd) return candidates
        return candidates.subList(tocEnd, candidates.size).toList()
    }
}
