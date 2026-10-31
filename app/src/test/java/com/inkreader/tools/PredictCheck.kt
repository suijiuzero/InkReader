package com.inkreader.tools

import com.inkreader.parse.HeadingFeatures
import java.io.File
import java.nio.charset.Charset

/**
 * 一致性校验：用**生产代码** `HeadingFeatures.at32()` 算特征，乘上同一份权重，
 * 输出 `idx<TAB>线性输出<TAB>sigmoid`，供 Python 侧逐行比对。
 *
 * 用法：
 *   PredictCheck <book.txt> <weights.txt> <out.txt> [charset]
 *
 * weights.txt 每行一个 Double，第一行是 bias，之后 32 个是各维权重（与 at32 顺序一致）。
 * 全部用 Double，不做 Float 降级。
 */
object PredictCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 3) {
            System.err.println("usage: PredictCheck <book.txt> <weights.txt> <out.txt> [charset]")
            kotlin.system.exitProcess(2)
        }
        val charset = Charset.forName(args.getOrNull(3)?.takeIf { it.isNotBlank() } ?: "UTF-8")
        val lines = File(args[0]).readText(charset).split("\n").map { it.removeSuffix("\r") }
        val weights = File(args[1]).readLines().map { it.trim() }.filter { it.isNotEmpty() }.map { it.toDouble() }
        if (weights.size != 33) {
            System.err.println("权重个数应为 33（bias + 32），实际 ${weights.size}")
            kotlin.system.exitProcess(3)
        }
        val features = HeadingFeatures(lines)
        val out = StringBuilder()
        var n = 0
        for (i in features.candidates) {
            val x = features.at32(i)
            if (x.size != 32) { System.err.println("维度异常 ${x.size}"); kotlin.system.exitProcess(4) }
            var z = weights[0]
            for (k in 0 until 32) z += x[k] * weights[k + 1]
            val p = 1.0 / (1.0 + kotlin.math.exp(-z.coerceIn(-40.0, 40.0)))
            out.append(i).append('\t')
                .append(String.format(java.util.Locale.ROOT, "%.17g", z)).append('\t')
                .append(String.format(java.util.Locale.ROOT, "%.17g", p)).append('\n')
            n++
        }
        File(args[2]).writeText(out.toString(), Charsets.UTF_8)
        println("predicted $n rows -> ${args[2]}")
    }
}
