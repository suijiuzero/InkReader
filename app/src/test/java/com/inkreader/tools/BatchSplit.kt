package com.inkreader.tools

import com.inkreader.data.model.SplitMode
import com.inkreader.data.model.SplitOptions
import com.inkreader.parse.ChapterSplitter
import java.io.File
import java.nio.charset.Charset

/**
 * 批量体检：对目录下每个 .txt 跑生产分章器，输出可疑指标。
 *
 * 用法：BatchSplit <目录> [递归 true/false]
 * 输出 TSV：文件 / 行数 / 字符数 / 章节数 / 最大章字数 / 带(n)后缀章数 / 同名重复章数 / 疑似回退
 */
object BatchSplit {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args[0])
        val recursive = args.getOrNull(1)?.toBoolean() ?: true
        val files = (if (recursive) root.walkTopDown() else root.listFiles()?.asSequence() ?: emptySequence())
            .filter { it.isFile && it.extension.lowercase() in setOf("txt", "md") }
            .sortedBy { it.path }
            .toList()
        println("files\t" + files.size)
        val sb = StringBuilder()
        sb.append("file\tlines\tchars\theadingLines\tchapters\tratio\tmaxChapChars\tsuffixed\tdupeTitles\temptyBody\n")
        for (f in files) {
            // 与 App 一致：用 CharsetDetector 嗅探编码，而不是硬套 UTF-8
            val bytes = f.readBytes()
            val det = com.inkreader.parse.CharsetDetector.detect(bytes)
            val text = String(bytes, det.bomLength, bytes.size - det.bomLength, det.charset)
            val options = SplitOptions(mode = SplitMode.SMART, cleanWatermark = false, modelSuggestions = false)
            val parts = runCatching { ChapterSplitter.split(text, f.nameWithoutExtension, options) }.getOrNull()
                ?: continue
            val headingLines = text.lineSequence().count { ChapterSplitter.chapterOrderFromTitle(it.trim()) != null }
            val maxChap = parts.maxOfOrNull { it.content.length } ?: 0
            val suffixed = parts.count { Regex("（\\d+）$").containsMatchIn(it.title.trim()) }
            val dupes = parts.groupingBy { it.title }.eachCount().count { it.value > 1 }
            val emptyBody = parts.count { it.content.isBlank() }
            sb.append(f.name).append('\t').append(text.count { it == '\n' } + 1)
                .append('\t').append(text.length)
                .append('\t').append(headingLines)
                .append('\t').append(parts.size)
                .append('\t').append(if (headingLines == 0) 0.0 else parts.size.toDouble() / headingLines)
                .append('\t').append(maxChap)
                .append('\t').append(suffixed)
                .append('\t').append(dupes)
                .append('\t').append(emptyBody)
                .append('\n')
            // 明细：空章 / 重名章，便于定位算法问题
            for (p in parts) if (p.content.isBlank()) println("EMPTY\t${f.name}\t${p.title.trim()}")
            parts.groupingBy { it.title.trim() }.eachCount().filterValues { it > 1 }.forEach { (tt, n) ->
                println("DUP\t${f.name}\t$tt\t$n")
            }
            println("done\t${f.name}\t${parts.size}")
        }
        // 直接写 UTF-8 报告，避免控制台 GBK 把中文文件名弄乱
        val report = File(root, "batch-report.tsv")
        report.writeText(sb.toString(), Charsets.UTF_8)
        println("report\t" + report.path)
        println(sb.toString())
    }
}
