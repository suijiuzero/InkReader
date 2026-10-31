package com.inkreader.tools

import com.inkreader.parse.HeadingFeatures
import java.io.File
import java.nio.charset.Charset

/**
 * 批量导出：一次 JVM 启动处理整份书单，避免每本书启一次 Gradle。
 *
 * 用法：
 *   ExportBatch <list.txt> <out.jsonl>
 *
 * list.txt 每行：`<bookId>\t<absolutePath>\t<charset>`
 * 输出每行：`{"book":"...","idx":N,"text":"...","x32":[...],"prev":"...","next":"..."}`
 *
 * 只导出特征与相邻行，不导出正文段落。
 */
object ExportBatch {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            System.err.println("usage: ExportBatch <list.txt> <out.jsonl>")
            kotlin.system.exitProcess(2)
        }
        val list = File(args[0]).readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val out = File(args[1]).bufferedWriter(Charsets.UTF_8)
        var books = 0
        var rows = 0L
        out.use { w ->
            for (line in list) {
                val parts = line.split('\t')
                if (parts.size < 2) continue
                val id = parts[0]
                val path = parts[1]
                val charset = Charset.forName(parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "UTF-8")
                val f = File(path)
                if (!f.exists()) { System.err.println("missing: $path"); continue }
                val text = try { f.readText(charset) } catch (e: Exception) {
                    System.err.println("read failed: $path (${e.message})"); continue
                }
                val lines = text.split("\n").map { it.removeSuffix("\r") }
                if (lines.size < 40) continue
                val features = HeadingFeatures(lines)
                var n = 0
                for (i in features.candidates) {
                    val x = features.at32(i)
                    if (x.size != 32) continue
                    var p = i - 1
                    while (p >= 0 && lines[p].trim().isEmpty()) p--
                    var q = i + 1
                    while (q < lines.size && lines[q].trim().isEmpty()) q++
                    w.write("{\"book\":")
                    w.write(json(id))
                    w.write(",\"idx\":")
                    w.write(i.toString())
                    w.write(",\"text\":")
                    w.write(json(lines[i].trim()))
                    w.write(",\"x32\":")
                    w.write(arr(x))
                    w.write(",\"prev\":")
                    w.write(json(if (p >= 0) lines[p].trim() else ""))
                    w.write(",\"next\":")
                    w.write(json(if (q < lines.size) lines[q].trim() else ""))
                    w.write(",\"total\":")
                    w.write(lines.size.toString())
                    w.write("}\n")
                    n++
                }
                rows += n
                books++
                if (books % 20 == 0) println("  ...$books books, $rows rows")
            }
        }
        println("done: $books books, $rows rows -> ${args[1]}")
    }

    private fun arr(v: DoubleArray): String = v.joinToString(",", "[", "]") {
        if (it == 0.0) "0" else String.format(java.util.Locale.ROOT, "%.17g", it)
    }

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
