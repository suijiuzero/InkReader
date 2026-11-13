package com.inkreader.tools

import com.inkreader.data.model.SplitMode
import com.inkreader.data.model.SplitOptions
import com.inkreader.parse.ChapterSplitter

/** 用生产分章器跑三个回归场景（绕开本机无法加载 JUnit 测试类的问题）。 */
object RegressionCheck {
    private const val BODY = "　　他抬头看了看天色，把刀收回鞘里，转身朝城门走去。"
    private var failed = 0

    private fun check(name: String, ok: Boolean, detail: String) {
        println((if (ok) "PASS  " else "FAIL  ") + name + "   " + detail)
        if (!ok) failed++
    }

    private fun split(text: String) = ChapterSplitter.split(text, options = SplitOptions(mode = SplitMode.SMART))

    @JvmStatic
    fun main(args: Array<String>) {
        // 1) 装饰性 ~~~ 不能吞掉后续章节
        run {
            val sb = StringBuilder()
            for (i in 1..3) { sb.appendLine("第${i}章 标题$i"); sb.appendLine(BODY); sb.appendLine(BODY) }
            sb.appendLine("“不要！~~~好舒服！~~~~不可以！~~~”")
            sb.appendLine(BODY)
            for (i in 4..8) { sb.appendLine("第${i}章 标题$i"); sb.appendLine(BODY); sb.appendLine(BODY) }
            val nums = split(sb.toString()).mapNotNull { ChapterSplitter.chapterOrderFromTitle(it.title) }
            check("装饰性 ~~~ 不吞章节", nums == listOf(1, 2, 3, 4, 5, 6, 7, 8), "实际 $nums")
        }
        // 2) 标题粘进正文，不应产生空章
        run {
            val sb = StringBuilder()
            for (i in 1..5) {
                sb.appendLine("第${i}章 标题$i"); sb.appendLine()
                sb.appendLine("第${i}章 标题$i$BODY"); sb.appendLine(BODY); sb.appendLine(BODY); sb.appendLine()
            }
            val parts = split(sb.toString())
            check("粘标题不产生空章", parts.size == 5 && parts.all { it.content.isNotBlank() },
                "章数 ${parts.size}，空章 ${parts.count { it.content.isBlank() }}")
        }
        // 3) 编号打错的重复章（第82章 切磋 / 第80章 切磋）
        run {
            val sb = StringBuilder()
            sb.appendLine("第81章 前情"); sb.appendLine(BODY); sb.appendLine(BODY)
            sb.appendLine("第82章 切磋"); sb.appendLine(); sb.appendLine("第80章 切磋")
            sb.appendLine(BODY); sb.appendLine(BODY)
            sb.appendLine("第83章 后事"); sb.appendLine(BODY)
            val parts = split(sb.toString())
            check("错号同章名合并", parts.all { it.content.isNotBlank() } && parts.size <= 4,
                "章数 ${parts.size}，空章 ${parts.count { it.content.isBlank() }}")
        }
        println(if (failed == 0) "\n全部通过" else "\n失败 $failed 项")
        if (failed != 0) kotlin.system.exitProcess(1)
    }
}
