package com.inkreader.parse

import org.junit.Assert.*
import org.junit.Test

class OutputOriginsTest {
    @Test fun sourceRangesMapPositionsAndCrossChapterHighlightsWithoutPerCharacterStorage() {
        val sections = listOf(PreparedTxt.Section("A", listOf(PreparedTxt.Piece(10, 20), PreparedTxt.Piece(0, 0, "\n\n"), PreparedTxt.Piece(25, 30))),
            PreparedTxt.Section("B", listOf(PreparedTxt.Piece(40, 50))))
        val map = OutputOrigins(sections)
        assertEquals(0 to 3, map.locate(13))
        assertEquals(0 to 12, map.locate(22))
        assertEquals(1 to 0, map.locate(35))
        assertEquals(0 to 17, map.locate(30, endBias = true))
        assertEquals(listOf(OutputOrigins.Range(0, 3, 17), OutputOrigins.Range(1, 0, 6)), map.intersections(13, 46))
    }
}
