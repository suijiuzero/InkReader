package com.inkreader.parse

import kotlin.math.abs
import kotlin.math.max

/** PDF 字形读取与段落聚合分离，聚合过程可直接验证每一行均被保留。 */
object PdfParagraphs {
    data class Line(val text: String, val size: Float, val y: Float, val paraEnd: Boolean)
    data class Para(val text: String, val size: Float)

    fun collect(pages: List<List<Line>>, artifact: (String) -> Boolean, gap: (List<Line>) -> Float,
                artifactAt: ((Line, Int, Int) -> Boolean)? = null): List<Para> {
        val out = ArrayList<Para>()
        for (page in pages) {
            val lines = page.filterIndexed { index, line -> !artifact(line.text) && artifactAt?.invoke(line, index, page.size) != true }
            val threshold = gap(lines)
            val buf = StringBuilder()
            var size = 0f
            var y = Float.NaN
            var paragraphEnd = false
            fun flush() {
                if (buf.isNotEmpty()) out += Para(buf.toString(), size)
                buf.setLength(0)
                size = 0f
            }
            for (line in lines) {
                if (DocumentImages.isToken(line.text) || line.text == "〔图片〕") {
                    flush(); out += Para(line.text, line.size); y = line.y; paragraphEnd = true; continue
                }
                if (paragraphEnd || (!y.isNaN() && abs(line.y - y) > threshold)) flush()
                if (buf.isNotEmpty() && latin(buf.last()) && line.text.firstOrNull()?.let(::latin) == true) buf.append(' ')
                buf.append(line.text)
                size = max(size, line.size)
                y = line.y
                paragraphEnd = line.paraEnd
            }
            flush()
        }
        return out
    }
    private fun latin(c: Char): Boolean = c.isLetterOrDigit() && c.code < 0x2E80
}
