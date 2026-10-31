package com.inkreader.tools

import com.inkreader.data.model.SplitMode
import com.inkreader.data.model.SplitOptions
import com.inkreader.parse.ChapterSplitter
import com.inkreader.parse.HeadingReason
import java.io.File
import java.nio.charset.Charset

/**
 * 端到端验证：直接调用**生产分章器** `ChapterSplitter.analyze`，
 * 对比「模型开关关闭 / 打开」两种情况的章节数，并列出模型新增的章节。
 *
 * 用法：SplitE2E <book.txt> [charset]
 */
object SplitE2E {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("usage: SplitE2E <book.txt> [charset]")
            kotlin.system.exitProcess(2)
        }
        val charset = Charset.forName(args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "UTF-8")
        val text = File(args[0]).readText(charset)
        val base = SplitOptions(mode = SplitMode.SMART, cleanWatermark = false,
            detectUnnumberedTitles = false, modelSuggestions = false)
        val withModel = base.copy(modelSuggestions = true)

        val off = ChapterSplitter.analyze(text, File(args[0]).nameWithoutExtension, base)
        val on = ChapterSplitter.analyze(text, File(args[0]).nameWithoutExtension, withModel)
        println("模型关闭: ${off.parts.size} 章")
        println("模型打开: ${on.parts.size} 章")

        val modelAccepted = on.decisions.filter { HeadingReason.MODEL_ACCEPTED in it.reasons }
        val modelPending = on.decisions.filter { HeadingReason.MODEL_SUGGESTION in it.reasons }
        println("其中 MODEL_ACCEPTED=${modelAccepted.size}，MODEL_SUGGESTION(待确认)=${modelPending.size}")

        val hist = HashMap<String, Int>()
        for (d in on.decisions) {
            if (!d.accepted) continue
            val key = d.reasons.joinToString("+") { it.name }
            hist[key] = (hist[key] ?: 0) + 1
        }
        println("\n已接受判定按原因分组：")
        hist.entries.sortedByDescending { it.value }.forEach { println("  " + it.key + " = " + it.value) }
        println("已接受总数 = " + on.decisions.count { it.accepted } + " / 全部判定 " + on.decisions.size)
        val offLines = off.parts.map { it.title.trim() }.toSet()
        val added = on.parts.map { it.title.trim() }.filterNot { it in offLines }
        println("\n模型新增的章节（前 25 条）：")
        added.take(25).forEach { println("  + $it") }
        println("\n模型新增合计 ${added.size} 条")
        println("\n最终章节列表（前 15 条）：")
        on.parts.take(15).forEach { println("  - ${it.title.trim()}") }
    }
}
