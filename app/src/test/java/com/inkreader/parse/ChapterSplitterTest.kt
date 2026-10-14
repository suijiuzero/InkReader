package com.inkreader.parse

import com.inkreader.data.model.SplitMode
import com.inkreader.data.model.SplitOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChapterSplitter 的回归测试，覆盖：
 *  · 「章 / 节 / 卷」的分层判定与同族模式投票
 *  · 章节序号连续性离群剔除（防正文对话/引用误切）
 *  · 无规则奇葩排版的短行前缀自动归纳（如 `◆ 01`、`§ 1`）
 *  · 二级「卷 → 章」归属识别
 *  · 章节标题尾部求票括注与正文网址水印净网清洗
 *  · 切分档位（STRICT_CHAP / NUMBERED / FIXED_SIZE / CUSTOM）
 */
class ChapterSplitterTest {

    private val body = "正文".repeat(30)
    private val longBody = "正文".repeat(200)

    private fun split(
        raw: String,
        options: SplitOptions = SplitOptions(mode = SplitMode.SMART, cleanWatermark = false)
    ) = ChapterSplitter.split(ChapterSplitter.normalize(raw), "正文", options)

    @Test
    fun chaptersWithSections_splitByChapterOnly() {
        val raw = """
            第一章 初见

            $body

            第一节 相遇

            $body

            第二节 别离

            $body

            第二章 重逢

            $body

            第一节 归途

            $body
        """.trimIndent()

        val parts = split(raw)

        assertEquals(2, parts.size)
        assertEquals("第一章 初见", parts[0].title)
        assertEquals("第二章 重逢", parts[1].title)
        assertFalse(parts.any { it.title.contains("第一节") || it.title.contains("第二节") })
        assertTrue(parts[0].content.contains("第一节 相遇"))
        assertTrue(parts[0].content.contains("第二节 别离"))
        assertTrue(parts[1].content.contains("第一节 归途"))
    }

    @Test
    fun sectionsWithoutAnyChapter_stillSplitBySection() {
        val raw = """
            第一节 春

            $body

            第二节 夏

            $body

            第三节 秋

            $body
        """.trimIndent()

        val parts = split(raw)

        assertEquals(3, parts.size)
        assertEquals(listOf("第一节 春", "第二节 夏", "第三节 秋"), parts.map { it.title })
    }

    @Test
    fun plainChapterBook_splitsNormally() {
        val raw = """
            第一章 起因

            $body

            第二章 经过

            $body

            第三章 结果

            $body
        """.trimIndent()

        val parts = split(raw)

        assertEquals(3, parts.size)
        assertEquals(listOf("第一章 起因", "第二章 经过", "第三章 结果"), parts.map { it.title })
    }

    @Test
    fun englishChapters_splitNormally() {
        val raw = """
            Chapter 1 The Beginning

            $body

            Chapter 2 The Return

            $body

            Chapter 3 The End

            $body
        """.trimIndent()

        val parts = split(raw)

        assertEquals(3, parts.size)
        assertEquals("Chapter 1 The Beginning", parts[0].title)
        assertEquals("Chapter 3 The End", parts[2].title)
    }

    @Test
    fun chapterLevelOrder_ignoresSectionNumbering() {
        assertEquals(10, ChapterSplitter.chapterLevelOrderFromTitle("第十章 破境"))
        assertNull(ChapterSplitter.chapterLevelOrderFromTitle("第十节 小试"))
    }

    @Test
    fun specialWordGluedToProse_isNotAChapter() {
        val raw = """
            第一章 初见

            $body

            前言不搭后语的样子

            $body

            第二章 重逢

            $body
        """.trimIndent()

        val parts = split(raw)

        assertEquals(2, parts.size)
        assertEquals(listOf("第一章 初见", "第二章 重逢"), parts.map { it.title })
        assertTrue(parts[0].content.contains("前言不搭后语的样子"))
    }

    @Test
    fun specialWordWithBoundary_stillRecognized() {
        val raw = """
            序章

            $body

            第一章 初见

            $body

            第二章 重逢

            $body

            番外1 后日谈

            $body
        """.trimIndent()

        val parts = split(raw)

        assertEquals(4, parts.size)
        assertEquals("序章", parts[0].title)
        assertTrue(parts.last().title.contains("番外1"))
    }

    @Test
    fun volumeThenChapter_keepsChapterTitle() {
        val raw = """
            第一卷 风起
            第一章 初遇

            $body

            第二章 重逢

            $body
        """.trimIndent()

        val parts = split(raw)

        assertEquals(2, parts.size)
        assertEquals(listOf("第一章 初遇", "第二章 重逢"), parts.map { it.title })
        assertTrue(parts[0].content.contains("第一卷 风起"))
        assertEquals("第一卷 风起", parts[0].volume)
        assertEquals("第一卷 风起", parts[1].volume)
    }

    @Test
    fun noBlankLines_strongTitleStillFound() {
        val raw = """
            第一章 初遇
            “谁？”
            $body
            第二章 重逢
            “是我。”
            $body
        """.trimIndent()

        val parts = split(raw)

        assertEquals(2, parts.size)
        assertEquals(listOf("第一章 初遇", "第二章 重逢"), parts.map { it.title })
    }

    @Test
    fun chineseOrdinalList_splitsByWeakPattern() {
        val raw = """
            一、春

            $longBody

            二、夏

            $longBody

            三、秋

            $longBody
        """.trimIndent()

        val parts = split(raw)

        assertEquals(3, parts.size)
        assertEquals(listOf("一、春", "二、夏", "三、秋"), parts.map { it.title })
    }

    @Test
    fun labeledChapterTitle_splitsNormally() {
        val raw = """
            正文 第一章 初遇

            $body

            VIP 第二章 重逢

            $body
        """.trimIndent()

        val parts = split(raw)

        assertEquals(2, parts.size)
        assertEquals(listOf("正文 第一章 初遇", "VIP 第二章 重逢"), parts.map { it.title })
    }

    @Test
    fun bracketChapterTitle_splitsNormally() {
        val raw = """
            【第1章】初遇

            $body

            【第2章】重逢

            $body
        """.trimIndent()

        val parts = split(raw)

        assertEquals(2, parts.size)
        assertEquals(listOf("【第1章】初遇", "【第2章】重逢"), parts.map { it.title })
    }

    @Test
    fun fallbackSectionTitle_detected() {
        assertTrue(ChapterSplitter.isFallbackSectionTitle("第 3 节"))
        assertTrue(ChapterSplitter.isFallbackSectionTitle("第12节"))
        assertFalse(ChapterSplitter.isFallbackSectionTitle("第三节 小试"))
        assertFalse(ChapterSplitter.isFallbackSectionTitle("第 3 章"))
    }

    @Test
    fun chineseDigitRun_parsesByPosition() {
        assertEquals(108, ChapterSplitter.chapterOrderFromTitle("第一〇八章 破境"))
        assertEquals(101, ChapterSplitter.chapterOrderFromTitle("第一〇一章 出发"))
        assertEquals(123, ChapterSplitter.chapterOrderFromTitle("第一二三章 归一"))
        assertEquals(108, ChapterSplitter.chapterOrderFromTitle("第一百零八章 破境"))
        assertEquals(23, ChapterSplitter.chapterOrderFromTitle("第二十三章 破境"))
    }

    @Test
    fun chapterZero_hasOrderZero() {
        assertEquals(0, ChapterSplitter.chapterOrderFromTitle("第零章 楔子"))
        assertEquals(0, ChapterSplitter.chapterOrderFromTitle("第〇章 楔子"))
    }

    @Test
    fun chapterRangeInName_usesRangeStart() {
        assertEquals(4, ChapterSplitter.chapterOrderFromName("4-6.txt"))
        assertEquals(4, ChapterSplitter.chapterOrderFromName("4~6.txt"))
        assertEquals(4, ChapterSplitter.chapterOrderFromName("4－6.txt"))
        assertEquals(4, ChapterSplitter.chapterOrderFromName("4—6 破境.txt"))
        assertEquals(1, ChapterSplitter.chapterOrderFromName("1-3.txt"))
        assertEquals(4, ChapterSplitter.chapterOrderFromName("第4-6章.txt"))
        assertEquals(52, ChapterSplitter.chapterOrderFromName("第52章 取舍之间.txt"))
        assertEquals(3, ChapterSplitter.chapterOrderFromName("003 破境.txt"))
        assertEquals(12, ChapterSplitter.chapterOrderFromName("12、威胁.txt"))
        assertEquals(28, ChapterSplitter.chapterOrderFromName("28.txt"))
        assertNull(ChapterSplitter.chapterOrderFromName("正文.txt"))
    }

    @Test
    fun volumeNumberingRestart_detected() {
        assertTrue(ChapterSplitter.hasChapterNumberReset((1..20).toList() + (1..20).toList()))
        assertTrue(
            ChapterSplitter.hasChapterNumberReset(
                listOf(
                    ChapterSplitter.ORDER_FRONT_MATTER, 1, 2, 3, 4, null, 1, 2, 3, 4,
                    ChapterSplitter.ORDER_BACK_MATTER
                )
            )
        )
        assertFalse(ChapterSplitter.hasChapterNumberReset(listOf(1, 1, 2, 2, 3)))
        assertFalse(ChapterSplitter.hasChapterNumberReset(listOf(1, 3, 2, 4)))
        assertFalse(ChapterSplitter.hasChapterNumberReset(listOf(1, 2, 3, 4)))
    }

    @Test
    fun midBookVolumeTitle_movesToNextChapter() {
        val raw = """
            第一章 初遇

            $body

            第二卷 惊雷
            第二章 决战

            $body
        """.trimIndent()

        val parts = split(raw)

        assertEquals(2, parts.size)
        assertEquals(listOf("第一章 初遇", "第二章 决战"), parts.map { it.title })
        assertFalse(parts[0].content.contains("第二卷"))
        assertTrue(parts[1].content.startsWith("第二卷 惊雷"))
        assertEquals("第二卷 惊雷", parts[1].volume)
    }

    @Test
    fun leadingTocWithBlankLines_isNotSplitAsChapters() {
        val raw = """
            目录

            第一章 初见

            第二章 重逢

            第三章 结局

            第四章 归途

            第一章 初见

            $body

            第二章 重逢

            $body

            第三章 结局

            $body

            第四章 归途

            $body
        """.trimIndent()

        val parts = split(raw)

        assertEquals(4, parts.size)
        assertEquals(
            listOf("第一章 初见", "第二章 重逢", "第三章 结局", "第四章 归途"),
            parts.map { it.title }
        )
        assertTrue(parts[0].content.contains("目录"))
    }

    @Test
    fun bracketSection_isNotChapterLevel() {
        val raw = """
            正文 第一章 初遇

            $body

            【第一节】小试

            $body

            正文 第二章 重逢

            $body
        """.trimIndent()

        val parts = split(raw)

        assertEquals(2, parts.size)
        assertEquals(listOf("正文 第一章 初遇", "正文 第二章 重逢"), parts.map { it.title })
        assertTrue(parts[0].content.contains("【第一节】小试"))
    }

    @Test
    fun quotedTitle_isNotAReference() {
        val raw = """
            第一章「初见」

            $body

            第二章“重逢”

            $body
        """.trimIndent()

        val parts = split(raw)

        assertEquals(2, parts.size)
        assertEquals(listOf("第一章「初见」", "第二章“重逢”"), parts.map { it.title })
    }

    @Test
    fun gluedTitleAfterNumber_isNotAReference() {
        val raw = """
            第一章前夜

            $body

            第二章从零开始

            $body
        """.trimIndent()

        val parts = split(raw)

        assertEquals(2, parts.size)
        assertEquals(listOf("第一章前夜", "第二章从零开始"), parts.map { it.title })
    }

    @Test
    fun proseReference_isStillNotAChapter() {
        val raw = """
            第一章 初见

            $body

            第二章里提到的那个名字

            $body

            第三章 重逢

            $body
        """.trimIndent()

        val parts = split(raw)

        assertEquals(2, parts.size)
        assertEquals(listOf("第一章 初见", "第三章 重逢"), parts.map { it.title })
        assertTrue(parts[0].content.contains("第二章里提到的那个名字"))
    }

    // ==================== 四大维度新增能力单测 ====================

    /** 维度一（算法层）：章节序号连续性离群剔除 —— 正文台词或引用突然跳到「第一百章」时自动作为噪点剔除 */
    @Test
    fun sequenceContinuity_rejectsOutlierJumpInProse() {
        val raw = """
            第十二章 潜入

            $body

            第一百章 终极审判

            $body

            第十三章 撤离

            $body
        """.trimIndent()

        val parts = split(raw)

        assertEquals(2, parts.size)
        assertEquals(listOf("第十二章 潜入", "第十三章 撤离"), parts.map { it.title })
        assertTrue(parts[0].content.contains("第一百章 终极审判"))
    }

    /** 维度一（算法层）：无规则奇葩排版的自动归纳 —— 全文无「第X章」，但高频重复 `◆ 01` 短行前缀 */
    @Test
    fun exoticPrefixPattern_autoInducedWithoutStandardHeadings() {
        val raw = """
            ◆ 01 楔子

            $longBody

            ◆ 02 暗流

            $longBody

            ◆ 03 破局

            $longBody

            ◆ 04 尾声

            $longBody
        """.trimIndent()

        val parts = split(raw)

        assertEquals(4, parts.size)
        assertEquals(
            listOf("◆ 01 楔子", "◆ 02 暗流", "◆ 03 破局", "◆ 04 尾声"),
            parts.map { it.title }
        )
    }

    /** 维度四（净网清洗）：自动剥离标题尾部的求票括注与正文中夹杂的网址水印行，同时保留（上）（下） */
    @Test
    fun watermarkAndTitleNoise_cleanedProperly() {
        val raw = """
            第一章 风起（求月票！第一更）

            $body
            === 某某小说网 www.example.com 最新章节 ===

            第二章 决战（上）【3000字大章】

            $body
            手机阅读请访问 https://m.example.org 最快更新
        """.trimIndent()

        val parts = split(
            raw,
            SplitOptions(mode = SplitMode.SMART, cleanWatermark = true)
        )

        assertEquals(2, parts.size)
        assertEquals("第一章 风起", parts[0].title)
        assertEquals("第二章 决战（上）", parts[1].title)
        assertFalse(parts[0].content.contains("www.example.com"))
        assertFalse(parts[1].content.contains("https://m.example.org"))
    }

    /** 维度二（切分策略档位）：支持按自定义前缀或正则表达式切分 */
    @Test
    fun customPrefixStrategy_splitsByUserPattern() {
        val raw = """
            §1 序幕

            $body

            §2 展开

            $body

            §3 终曲

            $body
        """.trimIndent()

        val parts = split(
            raw,
            SplitOptions(mode = SplitMode.CUSTOM, customPattern = "§", cleanWatermark = true)
        )

        assertEquals(3, parts.size)
        assertEquals(listOf("§1 序幕", "§2 展开", "§3 终曲"), parts.map { it.title })
    }

    /** 档位 STRICT_CHAP：只认「章 / 回」这一级，章内小节留在正文里 */
    @Test
    fun strictChapMode_ignoresSections() {
        val raw = """
            第一章 初见

            $body

            第一节 相遇

            $body

            第二章 重逢

            $body
        """.trimIndent()

        val parts = split(raw, SplitOptions(mode = SplitMode.STRICT_CHAP, cleanWatermark = false))

        assertEquals(2, parts.size)
        assertEquals(listOf("第一章 初见", "第二章 重逢"), parts.map { it.title })
        assertTrue(parts[0].content.contains("第一节 相遇"))
    }

    /** 档位 NUMBERED：`1、` `一、` 这类数字序号也要能切 */
    @Test
    fun numberedMode_splitsByWeakNumbering() {
        val raw = """
            1、春

            $longBody

            2、夏

            $longBody

            3、秋

            $longBody
        """.trimIndent()

        val parts = split(raw, SplitOptions(mode = SplitMode.NUMBERED, cleanWatermark = false))

        assertEquals(3, parts.size)
        assertEquals(listOf("1、春", "2、夏", "3、秋"), parts.map { it.title })
    }

    /** 档位 FIXED_SIZE：不认标题，按固定字数在段落边界切 */
    @Test
    fun fixedSizeMode_splitsByChunkSize() {
        val raw = (1..120).joinToString("\n\n") { "第$it 段。" + "正文".repeat(20) }

        val parts = split(
            raw,
            SplitOptions(mode = SplitMode.FIXED_SIZE, cleanWatermark = false, fixedChunkChars = 2000)
        )

        assertTrue("应该切成多段，实际 ${parts.size}", parts.size >= 2)
        // 在段落边界切，允许略微超出设定值，但不能成倍超
        assertTrue(parts.all { it.content.length <= 3000 })
    }

    /** 档位 PER_FILE：整篇作为一章，章节名用传进来的名字 */
    @Test
    fun perFileMode_keepsWholeFileAsOneChapter() {
        val raw = """
            第一章 初见

            $body

            第二章 重逢

            $body
        """.trimIndent()

        val parts = ChapterSplitter.split(
            ChapterSplitter.normalize(raw),
            "第 10 章 破境",
            SplitOptions(mode = SplitMode.PER_FILE, cleanWatermark = false)
        )

        assertEquals(1, parts.size)
        assertEquals("第 10 章 破境", parts[0].title)
        // 内部标题不丢，作为正文留在这一章里
        assertTrue(parts[0].content.contains("第一章 初见"))
        assertTrue(parts[0].content.contains("第二章 重逢"))
    }

    /** 档位 IN_FILE：文件内部自己带的标题就是章节 */
    @Test
    fun inFileMode_keepsInFileHeadings() {
        val raw = """
            第一章 初见

            $body

            第二章 重逢

            $body
        """.trimIndent()

        val parts = split(raw, SplitOptions(mode = SplitMode.IN_FILE, cleanWatermark = false))

        assertEquals(2, parts.size)
        assertEquals(listOf("第一章 初见", "第二章 重逢"), parts.map { it.title })
    }

    /** 自定义前缀命中不到任何一行时，退回按字数兜底，而不是悄悄滑进 SMART 自动管线 */
    @Test
    fun customPatternWithNoHit_fallsBackToFixedSize() {
        val raw = """
            第一章 初见

            $longBody
        """.trimIndent()

        val parts = split(
            raw,
            SplitOptions(mode = SplitMode.CUSTOM, customPattern = "§§§", cleanWatermark = false)
        )

        // 一行都没命中 → 整篇按字数切（这里只有一段，所以就是一章），且不会凭空多出「第 1 章」这类自动识别结果
        assertEquals(1, parts.size)
        assertFalse(parts[0].title.startsWith("第"))
    }

    /**
     * 文件名尾部的序号要能认出来 —— 「你好1.txt / 你好2.txt / 你好3.txt」这种拆书命名很常见。
     *
     * 认不出的后果有两个：文件之间顺序只能听系统选择器的（不可靠），
     * 而且合并导入时会被当成「没编号」，退回按文件内部的「第1节」分章，最后全是「第1节」。
     */
    @Test
    fun trailingNumberInFileName_isParsedAsOrder() {
        assertEquals(1, ChapterSplitter.chapterOrderFromName("你好1.txt"))
        assertEquals(2, ChapterSplitter.chapterOrderFromName("你好2.txt"))
        assertEquals(12, ChapterSplitter.chapterOrderFromName("你好12.txt"))
        assertEquals(3, ChapterSplitter.chapterOrderFromName("/sdcard/books/你好3.txt"))
        assertEquals(7, ChapterSplitter.chapterOrderFromName("Part7"))
        // 全角数字同样认
        assertEquals(5, ChapterSplitter.chapterOrderFromName("你好５.txt"))
        // 有明确「第X章」时仍以它为准，不会被尾部序号带跑
        assertEquals(9, ChapterSplitter.chapterOrderFromName("第9章 决战2024.txt"))
        // 卷级标记（第一部）优先于尾部序号：那是「第几部」，不是文件序号
        assertEquals(1, ChapterSplitter.chapterOrderFromName("第一部3.txt"))
        // 前置内容（前言 / 序）解析成 -1，排序时会排在第一章之前
        assertEquals(-1, ChapterSplitter.chapterOrderFromName("前言.txt"))
        // 真的认不出来的返回 null（保持原顺序排最后）
        assertNull(ChapterSplitter.chapterOrderFromName("杂记.txt"))
    }

    /**
     * 「区间文件名」：一本书拆成若干文件，名字写成章号区间 —— `诡秘之主1-4`、`5-7`、`8-11`。
     *
     * 关键点：区间可能**不在开头**（前缀是书名），而且排序必须取**区间起点**
     * （取结尾的 4 会导致与后面的 `5-7` 之间关系错乱：4 < 5 只是碰巧对，前缀一变就错）。
     */
    @Test
    fun chapterRangeInFileName_usesRangeStart() {
        assertEquals(1, ChapterSplitter.chapterOrderFromName("诡秘之主1-4.txt"))
        assertEquals(5, ChapterSplitter.chapterOrderFromName("5-7.txt"))
        assertEquals(8, ChapterSplitter.chapterOrderFromName("8-11.txt"))
        assertEquals(5, ChapterSplitter.chapterOrderFromName("诡秘之主5-7.txt"))
        // 全角与各种破折号
        assertEquals(1, ChapterSplitter.chapterOrderFromName("诡秘之主１－４.txt"))
        assertEquals(9, ChapterSplitter.chapterOrderFromName("诡秘之主9~12.txt"))
        // 「第X-Y章」同样取起点
        assertEquals(3, ChapterSplitter.chapterOrderFromName("第3-6章 风起.txt"))
        // 中文「到 / 至」写法
        assertEquals(1, ChapterSplitter.chapterOrderFromName("诡秘之主1到4.txt"))
        assertEquals(7, ChapterSplitter.chapterOrderFromName("诡秘之主7至9.txt"))
    }
}
