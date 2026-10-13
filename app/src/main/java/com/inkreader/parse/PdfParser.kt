package com.inkreader.parse

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * PDF 解析（不做 OCR）。
 *
 * PDF 里没有「章节」这种结构，只有页和一堆带坐标的字形，所以这里的做法是：
 *  1. 用 pdfbox 逐页取出「读序正确」的文字行，同时记下每行的字号与 y 坐标
 *     —— 覆盖 [PDFTextStripper.writeString] 收集**词**，再按 y 变化切行，
 *        这样拿到的是库自己排好的顺序，不是自己乱猜的；
 *  2. 按行距（y 间隙）把行聚合成段落 —— 页与页之间强制断开，保证跨页顺序不乱；
 *  3. 用「段落字号明显大于正文基准字号」推断标题，得到标题层级后交给
 *     [ChapterAssembler] 分章；推断不出来就退回 [ChapterSplitter] 的「第X章」识别；
 *  4. 页眉页脚（页码、每页重复的书名）按「出现在多数页的首尾行」特征剔除。
 *
 * 提取不到任何文字时抛 [ParseErrorCode.NEEDS_OCR]，提示用户这是扫描件。
 */
class PdfParser : DocumentParser {

    override val format: DocumentFormat = DocumentFormat.PDF

    override fun parse(bytes: ByteArray, displayName: String): ParsedDoc {
        val doc = open(bytes)
        doc.use { document ->
            val info = runCatching { document.documentInformation }.getOrNull()
            val metaTitle = runCatching { info?.title }.getOrNull()?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 60 && !looksLikeFileName(it) }
            val metaAuthor = runCatching { info?.author }.getOrNull()?.trim().orEmpty()

            val stripper = StyledStripper()
            stripper.sortByPosition = true
            val pages = try {
                stripper.run(document)
            } catch (t: Throwable) {
                throw ParseException(ParseErrorCode.CORRUPTED, format, t.message)
            }

            val styledChars = pages.sumOf { page -> page.sumOf { it.text.length } }
            val ratio = if (stripper.glyphChars > 0) {
                styledChars.toFloat() / stripper.glyphChars
            } else 0f

            val parts = when {
                // 字形流和收集到的文字量对得上：可以放心用字号推断标题
                styledChars > 0 && ratio in 0.7f..1.35f -> buildFromPages(pages)

                // 收集异常：退回标准提取（多一次解析，但保证正文是对的）
                else -> {
                    val plain = runCatching {
                        PDFTextStripper().apply {
                            sortByPosition = true
                            lineSeparator = "\n"
                        }.getText(document)
                    }.getOrDefault("")
                    val normalized = ChapterSplitter.normalize(plain)
                    if (normalized.isBlank()) {
                        throw ParseException(ParseErrorCode.NEEDS_OCR, format)
                    }
                    ChapterSplitter.split(normalized, "正文")
                }
            }

            if (parts.isEmpty()) throw ParseException(ParseErrorCode.NEEDS_OCR, format)
            if (parts.all { it.content.isBlank() }) throw ParseException(ParseErrorCode.NO_CONTENT, format)

            val title = metaTitle ?: ChapterSplitter.titleFromFileName(displayName)
            return ParsedDoc(title, metaAuthor, parts, format)
        }
    }

    private fun open(bytes: ByteArray): PDDocument {
        return try {
            PDDocument.load(ByteArrayInputStream(bytes))
        } catch (e: InvalidPasswordException) {
            throw ParseException(ParseErrorCode.ENCRYPTED, format)
        } catch (t: Throwable) {
            throw ParseException(ParseErrorCode.CORRUPTED, format, t.message)
        }
    }

    /** PDF 内部元数据里的 title 常常就是文件名，用来当书名没有意义 */
    private fun looksLikeFileName(s: String): Boolean =
        s.endsWith(".pdf", true) || s.endsWith(".doc", true) || s.endsWith(".docx", true)

    // ------------------------------------------------------------------ 页面 → 章节

    private val PUNCT_END = "。！？；：，、,.!?;:"

    private fun buildFromPages(pages: List<List<StyledStripper.Line>>): List<ChapterSplitter.Part> {
        val artifacts = findRunningHeads(pages)
        val allLines = pages.flatten()
        if (allLines.isEmpty()) return emptyList()

        val bodySize = bodyFontSize(allLines)

        // 段落聚合（逐页进行，页边界天然成为段落边界）
        val paras = ArrayList<RawPara>()
        for (page in pages) {
            val lines = page.filter { !isArtifact(it.text, artifacts) }
            if (lines.isEmpty()) continue
            val gap = paragraphGap(lines)
            val buf = StringBuilder()
            var size = 0f
            var prevY = Float.NaN
            var prevParaEnd = false
            for (line in lines) {
                val newPara = buf.isEmpty() ||
                    prevParaEnd ||
                    (!prevY.isNaN() && abs(line.y - prevY) > gap)
                if (newPara && buf.isNotEmpty()) {
                    paras += RawPara(buf.toString(), size)
                    buf.setLength(0)
                    size = 0f
                } else if (buf.isNotEmpty()) {
                    joinLine(buf, line.text)
                } else {
                    buf.append(line.text)
                }
                size = max(size, line.size)
                prevY = line.y
                prevParaEnd = line.paraEnd
            }
            if (buf.isNotEmpty()) paras += RawPara(buf.toString(), size)
        }
        if (paras.isEmpty()) return emptyList()

        val headings = paras
            .filter { isHeadingCandidate(it, bodySize) }
            .map { bucket(it.size) }
            .distinct()
            .sortedDescending()

        val leveled = if (headings.size < 2) {
            // 只认出一两个「大字号段落」，不足以说明这是标题体系，交给正则分章
            paras.map { ChapterAssembler.Para(ChapterAssembler.BODY, it.text) }
        } else {
            paras.map { p ->
                val level = if (isHeadingCandidate(p, bodySize)) {
                    headings.indexOf(bucket(p.size)).coerceAtLeast(0).coerceAtMost(5)
                } else ChapterAssembler.BODY
                ChapterAssembler.Para(level, p.text)
            }
        }
        return ChapterAssembler.assemble(leveled)
    }

    private class RawPara(val text: String, val size: Float)

    /** 正文字号：按「字号 → 字符数」加权取众数，比取平均值抗标题干扰 */
    private fun bodyFontSize(lines: List<StyledStripper.Line>): Float {
        val weight = HashMap<Int, Int>()
        for (l in lines) weight[bucket(l.size)] = (weight[bucket(l.size)] ?: 0) + l.text.length
        val best = weight.maxByOrNull { it.value }?.key ?: return 10f
        return best / 2f
    }

    /** 字号按 0.5pt 分桶，避免浮点误差把同一字号拆成好几个 */
    private fun bucket(size: Float): Int = Math.round(size * 2f)

    private fun isHeadingCandidate(p: RawPara, bodySize: Float): Boolean {
        val t = p.text.trim()
        if (t.isEmpty() || t.length > 40) return false
        if (t.contains('\n')) return false
        if (PUNCT_END.indexOf(t.last()) >= 0) return false
        return bucket(p.size) >= bucket(bodySize * 1.15f)
    }

    /** 段落间距阈值：行距中位数 × 1.6（下限 6pt），比固定值更适应不同排版 */
    private fun paragraphGap(lines: List<StyledStripper.Line>): Float {
        if (lines.size < 3) return 6f
        val gaps = ArrayList<Float>(lines.size)
        for (i in 1 until lines.size) {
            val d = lines[i].y - lines[i - 1].y
            if (d > 0.1f) gaps += d
        }
        if (gaps.isEmpty()) return 6f
        gaps.sort()
        val median = gaps[gaps.size / 2]
        return max(6f, median * 1.6f)
    }

    /** 行内拼接：中文直接接，西文之间补空格（PDF 换行会把单词拆开） */
    private fun joinLine(buf: StringBuilder, next: String) {
        if (buf.isEmpty() || next.isEmpty()) {
            buf.append(next)
            return
        }
        val a = buf.last()
        val b = next.first()
        if (isLatinWordChar(a) && isLatinWordChar(b)) buf.append(' ')
        buf.append(next)
    }

    private fun isLatinWordChar(c: Char): Boolean =
        c.isLetterOrDigit() && c.code < 0x2E80

    // ------------------------------------------------------------------ 页眉页脚

    /**
     * 找出「每页首尾重复出现」的行（页码、页眉书名）。
     * 判据：把数字统一替换成 # 之后，同一条文本出现在多数页面的开头/结尾。
     */
    private fun findRunningHeads(pages: List<List<StyledStripper.Line>>): Set<String> {
        if (pages.size < 4) return emptySet()
        val counts = HashMap<String, Int>()
        for (page in pages) {
            if (page.isEmpty()) continue
            val candidates = LinkedHashSet<String>()
            page.take(2).forEach { candidates += normalizeArtifact(it.text) }
            page.takeLast(2).forEach { candidates += normalizeArtifact(it.text) }
            for (c in candidates) {
                if (c.isNotEmpty()) counts[c] = (counts[c] ?: 0) + 1
            }
        }
        val threshold = max(3, (pages.size * 0.55f).toInt())
        return counts.filterValues { it >= threshold }.keys
    }

    private fun isArtifact(text: String, artifacts: Set<String>): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        // 纯页码：即使只有两三页也剔掉
        if (Regex("^[-—–\\[\\]()\\s]*\\d{1,4}[-—–\\[\\]()\\s]*$").matches(t)) return true
        if (Regex("(?i)^(第\\s*\\d+\\s*页|page\\s*\\d+|\\d+\\s*/\\s*\\d+)$").matches(t)) return true
        return normalizeArtifact(t) in artifacts
    }

    /** 归一化：去掉空白、把数字换成 #，让「第 12 页」「第 13 页」落到同一条 */
    private fun normalizeArtifact(text: String): String =
        text.lowercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("\\d+"), "#")
            .trim()

    // ------------------------------------------------------------------ 词流收集

    /**
     * 覆盖 [PDFTextStripper.writeString] 收集「词 + 字号 + y 坐标」。
     *
     * PDFBox 默认就是逐词回调 writeString（词之间回调 writeWordSeparator、行之间回调
     * writeLineSeparator），所以按 y 坐标变化切行即可，顺序完全沿用库排好的读序。
     * 同时用 [processTextPosition] 统计字形字符数，作为「收集是否完整」的校验依据。
     */
    private class StyledStripper : PDFTextStripper() {

        class Line(val text: String, val size: Float, val y: Float, val paraEnd: Boolean)

        private val result = ArrayList<MutableList<Line>>()
        private var current: MutableList<Line>? = null
        private val buf = StringBuilder()
        private var lineSize = 0f
        private var lineY = Float.NaN
        private var paraEndPending = false

        /** 字形流里的字符总数，用于校验收集是否完整/是否重复 */
        var glyphChars = 0
            private set

        fun run(document: PDDocument): List<List<Line>> {
            getText(document)
            return result
        }

        override fun processTextPosition(text: TextPosition?) {
            super.processTextPosition(text)
            glyphChars += text?.unicode?.length ?: 0
        }

        override fun startPage(page: PDPage) {
            super.startPage(page)
            flushLine()
            current = ArrayList<Line>().also { result += it }
            lineY = Float.NaN
        }

        override fun endPage(page: PDPage) {
            flushLine()
            super.endPage(page)
            current = null
        }

        override fun writeString(text: String?, positions: MutableList<TextPosition>?) {
            if (text.isNullOrEmpty()) return
            val first = positions?.firstOrNull()
            if (first != null) {
                val y = first.yDirAdj
                val size = first.fontSizeInPt
                if (!lineY.isNaN() && abs(y - lineY) > max(1.2f, size * 0.5f)) flushLine()
                lineY = y
                lineSize = max(lineSize, size)
            }
            buf.append(text)
        }

        override fun writeWordSeparator() {
            if (buf.isNotEmpty() && buf.last() != ' ') buf.append(' ')
        }

        override fun writeLineSeparator() {
            // 行由 y 坐标变化切分，这里不需要额外动作
        }

        override fun writeParagraphStart() = Unit

        override fun writeParagraphEnd() {
            paraEndPending = true
        }

        private fun flushLine() {
            val t = buf.toString().trim()
            buf.setLength(0)
            if (t.isNotEmpty()) {
                val y = if (lineY.isNaN()) 0f else lineY
                current?.add(Line(t, lineSize, y, paraEndPending))
            }
            lineSize = 0f
            paraEndPending = false
        }
    }
}