package com.inkreader.data.repo

import com.inkreader.data.model.Mark

/**
 * 章节合并 / 拆分时，批注（书签、高亮、笔记）的字符偏移平移规则。
 *
 * 单独抽成纯函数是为了能直接单测：这部分算错会**静默弄坏用户批注**，
 * 而它原本埋在需要数据库的仓库方法里，完全没有测试覆盖。
 * 返回的列表与入参一一对应，值没变的元素保持原对象引用（比较即可判断要不要落库）。
 */
object MarkShifts {

    /**
     * 「把第 currIdx 章并入它上一章」。
     *
     * 该章批注整体挪到上一章，偏移加上 [shift]（上一章长度 + 两章之间的空行）；
     * 它后面所有章的批注章号 -1。返回的列表与 [marks] 按下标一一对应。
     */
    fun forMergeWithPrev(marks: List<Mark>, currIdx: Int, shift: Int): List<Mark> = marks.map { m ->
        when {
            m.chapterIndex == currIdx -> m.copy(
                chapterIndex = currIdx - 1,
                startOffset = m.startOffset + shift,
                endOffset = m.endOffset + shift
            )

            m.chapterIndex > currIdx -> m.copy(chapterIndex = m.chapterIndex - 1)
            else -> m
        }
    }

    /**
     * 「按字符偏移 [splitOffset] 把第 currIdx 章拆成两章」。
     *
     * · 拆点之后的批注搬到新章，偏移减去 [bodyShiftFromSplit]（新章正文首字符在原文里的位置）；
     * · 起点在拆点前、但跨过拆点的高亮（endOffset 超出前半章）截断到前半章末尾；
     * · 其后各章的批注章号 +1。
     */
    fun forSplitAt(
        marks: List<Mark>,
        currIdx: Int,
        splitOffset: Int,
        bodyShiftFromSplit: Int,
        firstTextLength: Int,
        secondBodyLength: Int
    ): List<Mark> = marks.map { m ->
        when {
            m.chapterIndex > currIdx -> m.copy(chapterIndex = m.chapterIndex + 1)

            m.chapterIndex == currIdx && m.startOffset >= splitOffset -> {
                val ns = (m.startOffset - bodyShiftFromSplit).coerceIn(0, secondBodyLength)
                val ne = (m.endOffset - bodyShiftFromSplit).coerceIn(ns, secondBodyLength)
                m.copy(chapterIndex = currIdx + 1, startOffset = ns, endOffset = ne)
            }

            m.chapterIndex == currIdx && m.endOffset > firstTextLength ->
                m.copy(startOffset = m.startOffset.coerceIn(0, firstTextLength), endOffset = firstTextLength)

            else -> m
        }
    }

    fun forDelete(marks: List<Mark>, deletedIndex: Int): List<Mark> = marks
        .filter { it.chapterIndex != deletedIndex }
        .map { if (it.chapterIndex > deletedIndex) it.copy(chapterIndex = it.chapterIndex - 1) else it }
}
