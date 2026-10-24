package com.inkreader.data.picker

/** Snapshot of visible files only, in the current display order. Never scans subfolders. */
class SelectionBatch<T>(files: List<T>, anchor: T) {
    val files = files.distinct()
    private val start = this.files.indexOf(anchor)

    fun fromAnchor(reverse: Boolean = false): List<T> = when {
        start < 0 -> emptyList()
        reverse -> files.take(start + 1).asReversed()
        else -> files.drop(start)
    }

    fun all(reverse: Boolean = false): List<T> =
        if (start < 0) emptyList() else if (reverse) files.asReversed() else files.toList()

    /** Both endpoints included; direction follows the first file toward the second. */
    fun through(end: T): List<T> {
        val finish = files.indexOf(end)
        if (start < 0 || finish < 0) return emptyList()
        return if (finish >= start) files.subList(start, finish + 1).toList()
            else files.subList(finish, start + 1).asReversed().toList()
    }
}
