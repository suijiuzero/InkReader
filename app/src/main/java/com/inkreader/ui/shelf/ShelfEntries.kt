package com.inkreader.ui.shelf

import com.inkreader.data.model.Book
import com.inkreader.data.model.BookGroup
import com.inkreader.data.model.ShelfSort

internal sealed interface ShelfEntry {
    val key: String
    val title: String
    val books: List<Book>

    data class Single(val book: Book) : ShelfEntry {
        override val key get() = "b${book.id}"
        override val title get() = book.title
        override val books get() = listOf(book)
    }

    data class Group(val group: BookGroup, override val books: List<Book>) : ShelfEntry {
        override val key get() = "g${group.id}"
        override val title get() = group.name
    }
}

internal fun shelfEntries(books: List<Book>, groups: List<BookGroup>, sort: ShelfSort): List<ShelfEntry> {
    val byGroup = books.groupBy { it.groupId }
    val entries = groups.map { ShelfEntry.Group(it, byGroup[it.id].orEmpty()) } +
        byGroup[0L].orEmpty().map { ShelfEntry.Single(it) }
    // Rank groups by their best member using the same order as single books.
    // Keep empty groups visible and place them after populated entries for numeric sorts.
    val bookOrder = when (sort) {
        ShelfSort.RECENT -> compareByDescending(Book::lastReadAt).thenByDescending(Book::createdAt)
        ShelfSort.ADDED -> compareByDescending(Book::createdAt)
        ShelfSort.PROGRESS -> compareByDescending(Book::displayProgress).thenByDescending(Book::lastReadAt)
        ShelfSort.TITLE -> null
    }
    val comparison = if (bookOrder == null) {
        compareBy(String.CASE_INSENSITIVE_ORDER) { entry: ShelfEntry -> entry.title }
    } else {
        val ranked = entries.associate { it.key to it.books.minWithOrNull(bookOrder) }
        compareBy(nullsLast(bookOrder)) { entry: ShelfEntry -> ranked[entry.key] }
    }
    return entries.sortedWith(comparison.thenBy { it.key })
}
