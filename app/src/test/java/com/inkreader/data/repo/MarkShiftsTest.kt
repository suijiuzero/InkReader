package com.inkreader.data.repo

import com.inkreader.data.model.Mark
import com.inkreader.data.model.MarkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 批注（书签 / 高亮 / 笔记）在章节合并、拆分时的偏移平移回归测试。
 *
 * 这几条路径算错会**静默弄坏用户批注**（高亮跑到别的章、笔记对不上位置），
 * 而它们原本埋在需要数据库的仓库方法里，一直没有测试覆盖。
 */
class MarkShiftsTest {

    private fun mark(chapter: Int, start: Int, end: Int) =
        Mark(
            bookId = 1L,
            chapterIndex = chapter,
            startOffset = start,
            endOffset = end,
            type = MarkType.HIGHLIGHT,
            snippet = "片段"
        )

    /** 并入上一章：本章批注偏移加上「上一章长度 + 2」，其后各章章号 -1 */
    @Test
    fun mergeWithPrev_shiftsOffsetsAndIndices() {
        val marks = listOf(mark(0, 10, 20), mark(2, 5, 9), mark(3, 100, 120))
        val out = MarkShifts.forMergeWithPrev(marks, currIdx = 2, shift = 1000)

        // 上一章的批注不动
        assertEquals(marks[0], out[0])
        // 本章批注搬到上一章，并加上偏移
        assertEquals(1, out[1].chapterIndex)
        assertEquals(1005, out[1].startOffset)
        assertEquals(1009, out[1].endOffset)
        // 后面的章号整体 -1，偏移不变
        assertEquals(2, out[2].chapterIndex)
        assertEquals(100, out[2].startOffset)
    }

    /** 拆分：位于拆点之后的批注搬到新章，并扣掉新章正文在原文里的起点 */
    @Test
    fun splitAt_movesMarksAfterSplitPointToNewChapter() {
        // 第 1 章前半段 [0, 500)，拆点 500，新章正文在原文里从 505 开始
        val marks = listOf(
            mark(1, 10, 40),    // 拆点之前，原样留下
            mark(1, 600, 640),  // 拆点之后 → 新章，偏移 600 - 505 = 95
            mark(2, 0, 10)      // 后面的章 → 章号 +1
        )
        val out = MarkShifts.forSplitAt(
            marks,
            currIdx = 1,
            splitOffset = 500,
            bodyShiftFromSplit = 505,
            firstTextLength = 500,
            secondBodyLength = 800
        )

        assertEquals(marks[0], out[0])
        assertEquals(2, out[1].chapterIndex)
        assertEquals(95, out[1].startOffset)
        assertEquals(135, out[1].endOffset)
        assertEquals(3, out[2].chapterIndex)
    }

    /** 拆分：起点在拆点之前、但跨过拆点的高亮截断到前半章末尾 */
    @Test
    fun splitAt_truncatesHighlightCrossingSplitPoint() {
        val out = MarkShifts.forSplitAt(
            listOf(mark(1, 480, 700)),
            currIdx = 1,
            splitOffset = 500,
            bodyShiftFromSplit = 505,
            firstTextLength = 500,
            secondBodyLength = 800
        )

        assertEquals(1, out[0].chapterIndex)
        assertEquals(480, out[0].startOffset)
        assertEquals(500, out[0].endOffset)
    }

    @Test fun crossingSplitKeepsTailWithNoteAndDoesNotDuplicateBookmarks() {
        val range = mark(1, 480, 700).copy(id = 10, note = "KEEP_NOTE")
        val bookmark = mark(1, 500, 500)
        val tail = MarkShifts.splitTails(listOf(range, bookmark), 1, 500, 505, 800).single()
        assertEquals(0L, tail.id); assertEquals(2, tail.chapterIndex)
        assertEquals(0, tail.startOffset); assertEquals(195, tail.endOffset); assertEquals("KEEP_NOTE", tail.note)
        assertEquals(50, MarkShifts.splitTails(listOf(range), 1, 500, 505, 50).single().endOffset)
    }

    /** 没受影响的批注保持同一个对象实例：调用方据此跳过无谓的落库 */
    @Test
    fun unaffectedMarks_keepSameInstance() {
        val keep = mark(0, 1, 3)
        val out = MarkShifts.forMergeWithPrev(listOf(keep), currIdx = 5, shift = 100)
        assertSame(keep, out[0])
        assertEquals(keep, out[0])
    }
}
