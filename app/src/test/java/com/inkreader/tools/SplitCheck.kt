package com.inkreader.tools

import com.inkreader.parse.HeadingFeatures
import com.inkreader.parse.HeadingStructureFilter
import java.io.File
import java.nio.charset.Charset

/**
 * A3 一致性校验：用**生产代码**跑 32 维打分 + `HeadingStructureFilter`，
 * 输出最终被接受的行号，供 Python 侧逐行比对。
 *
 * 用法：SplitCheck <book.txt> <weights.txt> <out.txt> [charset]
 */
object SplitCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 3) {
            System.err.println("usage: SplitCheck <book.txt> <weights.txt> <out.txt> [charset]")
            kotlin.system.exitProcess(2)
        }
        val charset = Charset.forName(args.getOrNull(3)?.takeIf { it.isNotBlank() } ?: "UTF-8")
        val lines = File(args[0]).readText(charset).split("\n").map { it.removeSuffix("\r") }
        val w = File(args[1]).readLines().map { it.trim() }.filter { it.isNotEmpty() }.map { it.toDouble() }
        if (w.size != 33) { System.err.println("权重应为 33"); kotlin.system.exitProcess(3) }
        val f = HeadingFeatures(lines)
        val scored = LinkedHashMap<Int, Double>()
        for (i in f.candidates) {
            val x = f.at32(i)
            var z = w[0]
            for (k in 0 until 32) z += x[k] * w[k + 1]
            val p = 1.0 / (1.0 + kotlin.math.exp(-z.coerceIn(-40.0, 40.0)))
            if (p >= 0.25) scored[i] = p
        }
        val accepted = HeadingStructureFilter.accept(lines, scored, 0.50)
        val out = StringBuilder()
        for (i in accepted.sorted()) out.append(i).append('\n')
        File(args[2]).writeText(out.toString(), Charsets.UTF_8)
        println("scored=${scored.size} accepted=${accepted.size} -> ${args[2]}")
    }
}
