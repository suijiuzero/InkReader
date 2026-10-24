package com.inkreader.data.picker

/** Main-thread selection draft. Values should be stable document IDs or URIs, not display names. */
class OrderedSelection<T>(initial: Iterable<T> = emptyList()) {
    private val entries = initial.distinct().toMutableList()

    /** A snapshot: previously rendered/confirmed order cannot be changed by later edits. */
    val items: List<T> get() = entries.toList()
    val size: Int get() = entries.size

    fun positionOf(item: T): Int? = entries.indexOf(item).takeIf { it >= 0 }?.plus(1)

    /** Returns whether the item is now selected. Reselecting a removed item puts it at the end. */
    fun toggle(item: T): Boolean {
        if (entries.remove(item)) return false
        entries.add(item)
        return true
    }

    /** Append a batch in its requested order without duplicating or moving existing selections. */
    fun append(items: Iterable<T>): Int {
        val seen = entries.toHashSet()
        val added = items.filter { seen.add(it) }
        entries.addAll(added)
        return added.size
    }

    fun move(from: Int, to: Int): Boolean {
        if (from !in entries.indices || to !in entries.indices || from == to) return false
        entries.add(to, entries.removeAt(from))
        return true
    }

    fun clear() { entries.clear() }
}
