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
        sb.append("file\tlines\tchars\tchapters\tmaxChapChars\tsuffixed\tdupeTitles\temptyBody\n")
        for (f in files) {
            // 与 App 一致：用 CharsetDetector 嗅探编码，而不是硬套 UTF-8
            val bytes = f.readBytes()
            val det = com.inkreader.parse.CharsetDetector.detect(bytes)
            val text = String(bytes, det.bomLength, bytes.size - det.bomLength, det.charset)
            val options = SplitOptions(mode = SplitMode.SMART, cleanWatermark = false, modelSuggestions = false)
            val parts = runCatching { ChapterSplitter.split(text, f.nameWithoutExtension, options) }.getOrNull()
                ?: continue
            val maxChap = parts.maxOfOrNull { it.content.length } ?: 0
            val suffixed = parts.count { Regex("（\\d+）$").containsMatchIn(it.title.trim()) }
            val dupes = parts.groupingBy { it.title }.eachCount().count { it.value > 1 }
            val emptyBody = parts.count { it.content.isBlank() }
            sb.append(f.name).append('\t').append(text.count { it == '\n' } + 1)
                .append('\t').append(text.length)
                .append('\t').append(parts.size)
                .append('\t').append(maxChap)
                .append('\t').append(suffixed)
                .append('\t').append(dupes)
                .append('\t').append(emptyBody)
                .append('\n')
            println("done\t${f.name}\t${parts.size}")
        }
        println(sb.toString())
    }
}
