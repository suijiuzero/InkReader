package com.inkreader.tools

import com.inkreader.data.model.SplitMode
import com.inkreader.data.model.SplitOptions
import com.inkreader.parse.ChapterSplitter
import com.inkreader.parse.CharsetDetector
import com.inkreader.parse.IndexedText
import com.inkreader.parse.PreparedTxt
import java.io.File
import java.io.StringReader

/**
 * 两条导入路径对比：内存路径 ChapterSplitter.split vs 流式路径 PreparedTxt.sections。
 * 同一本书两者应给出相同的章节数与标题。
 */
object PathParity {
    @JvmStatic
    fun main(args: Array<String>) {
        val file = File(args[0])
        val bytes = file.readBytes()
        val det = CharsetDetector.detect(bytes)
        // App 读入后会先规范化换行；对比时必须同源，否则 \r 会改变标题匹配结果
        val text = String(bytes, det.bomLength, bytes.size - det.bomLength, det.charset)
            .replace("\r\n", "\n").replace("\r", "\n")
        val options = SplitOptions(mode = SplitMode.SMART, cleanWatermark = false)

        val memory = ChapterSplitter.split(text, file.nameWithoutExtension, options)

        val temp = File.createTempFile("ink-parity", ".txt")
        val streaming = try {
            StringReader(text).use { reader ->
                IndexedText.build(reader, temp).use { index ->
                    PreparedTxt(index, det, "parity", options).use { doc -> doc.sections }
                }
            }
        } catch (t: Throwable) {
            println("streaming failed: " + t)
            emptyList()
        } finally {
            temp.delete()
        }

        println("charset=" + det.charset.name())
        println("内存路径 ${memory.size} 章 / 流式路径 ${streaming.size} 章")
        val n = minOf(memory.size, streaming.size)
        var firstDiff = -1
        val diffs = ArrayList<String>()
        for (i in 0 until n) {
            val a = memory[i].title.trim()
            val b = streaming[i].title.trim()
            if (a != b) {
                if (firstDiff < 0) firstDiff = i
                if (diffs.size < 12) diffs.add("  #${i + 1}\n    内存: ${a.take(40)}\n    流式: ${b.take(40)}")
            }
        }
        if (firstDiff < 0 && memory.size == streaming.size) {
            println("PASS 两条路径完全一致")
        } else {
            println("首个差异在第 ${firstDiff + 1} 章")
            diffs.forEach { println(it) }
            if (memory.size > n) {
                println("  内存多出的前几个:")
                memory.drop(n).take(6).forEach { println("    内存: " + it.title.take(40)) }
            }
            if (streaming.size > n) {
                println("  流式多出的前几个:")
                streaming.drop(n).take(6).forEach { println("    流式: " + it.title.take(40)) }
            }
        }
    }
}
