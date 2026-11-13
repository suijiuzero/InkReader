package com.inkreader.tools

import com.inkreader.data.model.SplitMode
import com.inkreader.data.model.SplitOptions
import com.inkreader.parse.ChapterSplitter
import java.io.File
import java.nio.charset.Charset

/** 诊断探针：看单行标题能否被识别/取号，以及整本书 plan 出了多少个标题点。 */
object Probe {
    @JvmStatic
    fun main(args: Array<String>) {
        val samples = listOf(
            "第 110 章 确认（修改）",
            "第 111 章 错过（教师福利和赛琳娜福利）",
            "第 112 章 阿兹克的解释",
            "第 113 章 请求",
            "第112章 无空格测试",
            "第 99 章 红烟囱"
        )
        for (s in samples) {
            println("$s  -> number=" + ChapterSplitter.chapterOrderFromTitle(s) +
                " isNumbered=" + ChapterSplitter.isNumberedChapterTitle(s))
        }
        if (args.isEmpty()) return
        val charset = Charset.forName("UTF-8")
        val text = File(args[0]).readText(charset)
        val lines = text.split("\n")
        val options = SplitOptions(mode = SplitMode.SMART, cleanWatermark = false)
        val plan = ChapterSplitter.plan(lines, text.length, options)
        println("plan.headings=" + plan.headings.size)
        println("前 5: " + plan.headings.take(5))
        println("后 5: " + plan.headings.takeLast(5))
        File(args[0]).parentFile.resolve(File(args[0]).nameWithoutExtension + ".plan.txt")
            .writeText(plan.headings.joinToString("\n") { "$it\t${lines[it].trim()}" }, Charsets.UTF_8)
    }
}
