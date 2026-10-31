package com.inkreader.tools

import com.inkreader.parse.HeadingFeatures
import java.io.File
import java.nio.charset.Charset

/**
 * 导出 32 维特征，**直接调用生产代码** [HeadingFeatures.at32]（前 21 维由同一份 [HeadingFeatures.at] 算出）。
 *
 * 用法：
 *   exportFeatures <book.txt> <out.jsonl> [charset]
 *
 * 每行输出：
 *   {"idx":行号,"text":"该行","x21":[...],"x32":[...],"prev":"前一非空行","next":"后一非空行"}
 *
 * 设计约束（与 Python 侧约定）：
 *   · 这里只**导出**，不做任何判断；标签由 Python 侧按同一规则生成
 *   · 行号是 0 基，与 Python 一致
 *   · 浮点一律 Double，不做 Float 降级
 */
object ExportFeatures {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            System.err.println("usage: ExportFeatures <book.txt> <out.jsonl> [charset]")
            kotlin.system.exitProcess(2)
        }
        val src = File(args[0])
        val dst = File(args[1])
        val charset = Charset.forName(args.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "UTF-8")
        val text = src.readText(charset)
        val lines = text.split("\n").map { it.removeSuffix("\r") }
        val features = HeadingFeatures(lines)
        val out = StringBuilder()
        var written = 0
        for (i in features.candidates) {
            val x32 = features.at32(i)
            val x21 = features.at(i)
            if (x32.size != 32 || x21.size != 21) {
                System.err.println("维度异常: 21=${x21.size} 32=${x32.size}")
                kotlin.system.exitProcess(3)
            }
            // 相邻非空行（供 Python 生成标签时的上下文，不参与特征）
            var p = i - 1
            while (p >= 0 && lines[p].trim().isEmpty()) p--
            var n = i + 1
            while (n < lines.size && lines[n].trim().isEmpty()) n++
            out.append("{\"idx\":").append(i)
                .append(",\"text\":").append(json(lines[i].trim()))
                .append(",\"x21\":").append(arr(x21))
                .append(",\"x32\":").append(arr(x32))
                .append(",\"prev\":").append(json(if (p >= 0) lines[p].trim() else ""))
                .append(",\"next\":").append(json(if (n < lines.size) lines[n].trim() else ""))
                .append(",\"total\":").append(lines.size)
                .append("}\n")
            written++
        }
        dst.writeText(out.toString(), Charsets.UTF_8)
        println("exported $written rows -> ${dst.path}  lines=${lines.size}")
    }

    private fun arr(v: DoubleArray): String = v.joinToString(",", "[", "]") { fmt(it) }

    private fun fmt(v: Double): String =
        if (v == 0.0) "0" else String.format(java.util.Locale.ROOT, "%.17g", v)

    private fun json(s: String): String {
        val b = StringBuilder(s.length + 2)
        b.append('"')
        for (c in s) {
            when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else -> if (c < ' ') b.append(String.format("\\u%04x", c.code)) else b.append(c)
            }
        }
        b.append('"')
        return b.toString()
    }
}
