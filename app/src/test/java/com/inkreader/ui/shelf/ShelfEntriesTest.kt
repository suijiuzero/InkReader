package com.inkreader.ui.shelf

import com.inkreader.data.model.Book
import com.inkreader.data.model.BookGroup
import com.inkreader.data.model.ShelfSort
import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfEntriesTest {
    @Test fun recentInterleavesGroupsAndSinglesWithoutCountingImportsAsReads() {
        val groups = listOf(BookGroup(1, "Older"), BookGroup(2, "Newest"), BookGroup(3, "Empty"))
        val books = listOf(
            Book(1, "Group read", groupId = 1, lastReadAt = 50, createdAt = 10),
            Book(2, "Group import", groupId = 1, lastReadAt = 0, createdAt = 100),
            Book(3, "Single read", lastReadAt = 60, createdAt = 20),
            Book(4, "Latest read", groupId = 2, lastReadAt = 80, createdAt = 5),
            Book(5, "Single import", lastReadAt = 0, createdAt = 110)
        )
        assertEquals(listOf("g2", "b3", "g1", "b5", "g3"), shelfEntries(books, groups, ShelfSort.RECENT).map { it.key })
        assertEquals(listOf("b5", "g1", "b3", "g2", "g3"), shelfEntries(books, groups, ShelfSort.ADDED).map { it.key })
    }
}
