package com.inkreader.data.picker

import org.junit.Assert.*
import org.junit.Test

class OrderedSelectionTest {
    @Test fun batchKeepsExistingOrderAndAppendsOnlyNewIdsInRequestedOrder() {
        val selection = OrderedSelection(listOf("other-folder", "middle"))
        assertEquals(2, selection.append(listOf("last", "middle", "first", "last")))
        assertEquals(listOf("other-folder", "middle", "last", "first"), selection.items)
    }

    @Test fun repeatedBatchAndEmptyBatchAreNoOpsWithoutChangingConfirmedSnapshot() {
        val selection = OrderedSelection<String>()
        assertEquals(3, selection.append(listOf("third", "second", "first")))
        val snapshot = selection.items
        assertEquals(0, selection.append(snapshot))
        assertEquals(0, selection.append(emptyList()))
        assertEquals(snapshot, selection.items)
        selection.toggle("second")
        assertEquals(listOf("third", "second", "first"), snapshot)
    }

    @Test fun selectionTracksClickOrderAndRenumbersAfterRemovingOne() {
        val selection = OrderedSelection<String>()
        listOf("zeta", "alpha", "middle").forEach { assertTrue(selection.toggle(it)) }
        assertEquals(listOf("zeta", "alpha", "middle"), selection.items)
        assertEquals(1, selection.positionOf("zeta"))
        assertEquals(3, selection.positionOf("middle"))
        assertFalse(selection.toggle("alpha"))
        assertEquals(listOf("zeta", "middle"), selection.items)
        assertEquals(2, selection.positionOf("middle"))
        assertNull(selection.positionOf("alpha"))
        assertTrue(selection.toggle("alpha"))
        assertEquals(listOf("zeta", "middle", "alpha"), selection.items)
    }

    @Test fun duplicateRestoredIdsAreSelectedOnlyOnce() {
        val selection = OrderedSelection(listOf("same-uri", "other-uri", "same-uri"))
        assertEquals(2, selection.size)
        assertEquals(listOf("same-uri", "other-uri"), selection.items)
    }

    @Test fun movingBothDirectionsKeepsEverySelectedFileExactlyOnce() {
        val selection = OrderedSelection(listOf("zeta", "alpha", "middle"))
        assertTrue(selection.move(2, 0))
        assertEquals(listOf("middle", "zeta", "alpha"), selection.items)
        assertTrue(selection.move(0, 2))
        assertEquals(listOf("zeta", "alpha", "middle"), selection.items)
        assertEquals(3, selection.items.toSet().size)
    }

    @Test fun boundaryMovesAreNoOpsAndSnapshotsRemainIndependent() {
        val selection = OrderedSelection(listOf("one", "two"))
        val confirmed = selection.items
        assertFalse(selection.move(-1, 0))
        assertFalse(selection.move(0, 2))
        assertFalse(selection.move(0, 0))
        assertEquals(confirmed, selection.items)
        selection.toggle("one")
        selection.clear()
        assertEquals(0, selection.size)
        assertEquals(listOf("one", "two"), confirmed)
        assertNull(selection.positionOf("one"))
    }

    @Test fun supportedFileNamesCoverReaderFormatsWithoutShowingArchivesOrExecutableFiles() {
        listOf("novel.TXT", "a.docx", "a.MD", "a.markdown", "a.pdf", "a.html", "a.htm", "a.xhtml", "a.epub")
            .forEach { assertTrue(it, DocumentTreeBrowser.supportedName(it)) }
        listOf("a.doc", "a.zip", "a.apk", "a.exe", "a.xlsx", "a.pptx", "a.txt.exe", "no-extension", "")
            .forEach { assertFalse(it, DocumentTreeBrowser.supportedName(it)) }
    }
}
