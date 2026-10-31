package com.inkreader.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A3 全书结构校验的回归测试。纯 Kotlin，不依赖 Android。 */
class HeadingStructureFilterTest {

    private fun lines(vararg s: String): List<String> = s.toList()

    @Test
    fun repeatedUnnumberedFillerIsRejected() {
        // 「詩曰：」在古典小说里每回都出现，文本重复 ≥3 次且无编号 → 不是章节
        val body = (1..40).map { "正文第${it}行，这是一段足够长度的叙述文字，用于把结构撑起来。" }
        val ls = (body + listOf("詩曰：", "正文甲", "詩曰：", "正文乙", "詩曰：", "正文丙")).toList()
        val scored = mapOf(
            ls.indexOf("詩曰：") to 0.9,
            ls.lastIndexOf("詩曰：") to 0.9
        )
        val accepted = HeadingStructureFilter.accept(ls, scored, 0.50)
        assertTrue("重复出现的无编号套话不应被接受", accepted.isEmpty())
    }

    @Test
    fun numberedRepeatedTitlesSurviveVolumeReset() {
        // 第一卷/第一章、第二卷/第一章 —— 有编号的同名标题是真实的卷内重置
        val ls = mutableListOf<String>()
        repeat(30) { ls.add("填充正文，保证全书长度足够，内容不参与判定。") }
        ls.add("第一卷")
        ls.add("第一章 初遇")
        repeat(30) { ls.add("填充正文，保证全书长度足够，内容不参与判定。") }
        ls.add("第二章 再见")
        repeat(30) { ls.add("填充正文，保证全书长度足够，内容不参与判定。") }
        ls.add("第二卷")
        ls.add("第一章 初遇")
        repeat(30) { ls.add("填充正文，保证全书长度足够，内容不参与判定。") }
        val i1 = ls.indexOf("第一卷")
        val i2 = ls.indexOf("第一章 初遇")
        val i3 = ls.lastIndexOf("第一章 初遇")
        val scored = mapOf(i1 to 0.9, i2 to 0.85, i3 to 0.80)
        val accepted = HeadingStructureFilter.accept(ls, scored, 0.50)
        assertTrue("卷标题应保留", i1 in accepted)
        assertTrue("第一卷的第一章应保留", i2 in accepted)
        assertTrue("第二卷的同名第一章也应保留（卷内重置）", i3 in accepted)
    }

    @Test
    fun leadingTocBlockIsSuppressed() {
        // 开头连续成片的标题形态行 = 目录区
        val ls = mutableListOf("# 书名", "# 作者")
        val toc = (1..8).map { "第${it}章 标题" }
        ls.addAll(toc)
        repeat(60) { ls.add("正文段落，长度足够长，用于区分目录区与正文区，不应该被当成标题行。") }
        val scored = toc.mapIndexed { k, _ -> (k + 2) to 0.8 }.toMap()
        val accepted = HeadingStructureFilter.accept(ls, scored, 0.50)
        assertTrue("连续成片的目录区应被整体否决", accepted.isEmpty())
    }

    @Test
    fun boilerplateIsRejected() {
        val ls = mutableListOf<String>()
        repeat(40) { ls.add("正文段落，长度足够长，用于把结构撑起来，不参与标题判定。") }
        ls.add("Author: Lewis Carroll")
        ls.add("Language: English")
        ls.add("Contents")
        ls.add("START: FULL LICENSE")
        val base = 40
        val scored = mapOf(
            base to 0.95, base + 1 to 0.95, base + 2 to 0.95, base + 3 to 0.95
        )
        val accepted = HeadingStructureFilter.accept(ls, scored, 0.50)
        assertTrue("样板文字不应被接受", accepted.isEmpty())
    }

    @Test
    fun normalChaptersAreAccepted() {
        val ls = mutableListOf<String>()
        repeat(20) { ls.add("填充正文，保证全书长度足够，内容不参与判定。") }
        ls.add("第一章 初入江湖")
        repeat(20) { ls.add("填充正文，保证全书长度足够，内容不参与判定。") }
        ls.add("第二章 风起云涌")
        repeat(20) { ls.add("填充正文，保证全书长度足够，内容不参与判定。") }
        ls.add("第三章 少年归来")
        repeat(20) { ls.add("填充正文，保证全书长度足够，内容不参与判定。") }
        val idx = listOf("第一章 初入江湖", "第二章 风起云涌", "第三章 少年归来")
            .map { ls.indexOf(it) }
        val scored = idx.associateWith { 0.9 }
        val accepted = HeadingStructureFilter.accept(ls, scored, 0.50)
        assertEquals(3, accepted.size)
        idx.forEach { assertTrue("正常章节 $it 应被接受", it in accepted) }
    }

    @Test
    fun numberedChainRescuesLowScoreHeading() {
        // 第1~6章里，第4章分数偏低，但编号连续 → 应被补回
        val ls = mutableListOf<String>()
        repeat(15) { ls.add("填充正文，保证全书长度足够，内容不参与判定。") }
        val names = (1..6).map { "第${it}章 标题" }
        names.forEach {
            ls.add(it)
            repeat(15) { ls.add("填充正文，保证全书长度足够，内容不参与判定。") }
        }
        val idx = names.map { ls.indexOf(it) }
        val scored = idx.mapIndexed { k, i -> i to if (k == 3) 0.30 else 0.90 }.toMap()
        val accepted = HeadingStructureFilter.accept(ls, scored, 0.50)
        assertTrue("低分但编号连续的第4章应被补回", idx[3] in accepted)
        assertFalse("超出候选范围的索引不应出现", accepted.any { it !in idx })
    }
}
