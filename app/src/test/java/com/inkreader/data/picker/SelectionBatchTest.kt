package com.inkreader.data.picker

import org.junit.Assert.*
import org.junit.Test

class SelectionBatchTest {
    @Test fun directionsIncludeAnchorAndBothBoundaries() {
        val batch = SelectionBatch(listOf("a", "b", "c", "d", "e"), "c")
        assertEquals(listOf("c", "d", "e"), batch.fromAnchor())
        assertEquals(listOf("c", "b", "a"), batch.fromAnchor(true))
        assertEquals(listOf("a", "b", "c", "d", "e"), batch.all())
        assertEquals(listOf("e", "d", "c", "b", "a"), batch.all(true))
    }

    @Test fun rangesFollowAnchorTowardEndpointIncludingBoth() {
        val batch = SelectionBatch(listOf("a", "b", "c", "d", "e"), "c")
        assertEquals(listOf("c", "d"), batch.through("d"))
        assertEquals(listOf("c", "b", "a"), batch.through("a"))
        assertEquals(listOf("c"), batch.through("c"))
        assertTrue(batch.through("missing").isEmpty())
    }

    @Test fun respectsFilteredDescendingSnapshotWithoutChangingInput() {
        val visible = mutableListOf("e", "c", "a")
        val batch = SelectionBatch(visible, "c")
        visible.clear()
        assertEquals(listOf("c", "a"), batch.fromAnchor())
        assertEquals(listOf("c", "e"), batch.fromAnchor(true))
        assertEquals(listOf("e", "c", "a"), batch.all())
        assertEquals(listOf("c", "e"), batch.through("e"))
    }

    @Test fun singleFileAndInvalidAnchorNeverSelectUnrelatedFiles() {
        val single = SelectionBatch(listOf("a", "a"), "a")
        assertEquals(listOf("a"), single.all(true))
        assertEquals(listOf("a"), single.fromAnchor(true))
        for (batch in listOf(SelectionBatch(emptyList(), "a"), SelectionBatch(listOf("b"), "a"))) {
            assertTrue(batch.all().isEmpty())
            assertTrue(batch.fromAnchor().isEmpty())
            assertTrue(batch.through("b").isEmpty())
        }
    }

    @Test fun rangeAppendsAcrossFoldersWithoutMovingPreviouslyChosenFiles() {
        val selected = OrderedSelection(listOf("other/old", "b"))
        val batch = SelectionBatch(listOf("a", "b", "c", "d"), "c")
        assertEquals(2, selected.append(batch.through("a")))
        assertEquals(listOf("other/old", "b", "c", "a"), selected.items)
        assertEquals(0, selected.append(batch.through("a")))
    }
}
