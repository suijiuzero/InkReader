package com.inkreader.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkreader.data.model.ReaderPrefs
import com.inkreader.parse.InlineStyle
import com.inkreader.parse.LinkSpan
import com.inkreader.parse.RichChapter
import com.inkreader.parse.RichKind
import kotlin.math.floor
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 一个段落块，start 为块首字符在整章正文中的绝对偏移。
 *
 * [kind] / [level] / [marker] / [spans] / [links] 是 Markdown 的格式化信息，
 * 普通 TXT / DOCX 章节全是默认值（BODY、无样式），行为和以前完全一样。
 */
data class Block(
    val index: Int,
    val start: Int,
    val text: String,
    val kind: RichKind = RichKind.BODY,
    val level: Int = 0,
    val marker: String = "",
    val spans: List<InlineStyle> = emptyList(),
    val links: List<LinkSpan> = emptyList()
)

/** 会改变字形度量的行内样式，测量与绘制共用。 */
fun inlineText(text: String, start: Int, block: Block?): AnnotatedString = buildAnnotatedString {
    append(text)
    block?.spans?.forEach { span ->
        val s = (span.start - start).coerceIn(0, text.length)
        val e = (span.end - start).coerceIn(0, text.length)
        if (e > s) addStyle(SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            textDecoration = if (span.strike) TextDecoration.LineThrough else null,
            fontFamily = if (span.code) FontFamily.Monospace else null
        ), s, e)
    }
}

/** 页面中的一段内容（段落可能被跨页切开） */
data class Placed(val blockIndex: Int, val start: Int, val end: Int, val continuation: Boolean,
    val imageHeightPx: Float = 0f)

/** 一页 */
data class Page(
    val items: List<Placed>,
    val startOffset: Int,
    val endOffset: Int
)

/**
 * 块的排版样式。
 *
 * 分页和渲染**共用**这一份规则：分页时用它量高度，渲染时用它画正文。
 * 两边必须完全一致，否则「上一页最后一行被裁掉」或者「翻页后行距忽然变了」。
 *
 * 纯文本章节（TXT / DOCX）只会走到 BODY 分支，量出来的结果和改造前逐像素相同。
 */
class BlockStyler(
    private val base: TextStyle,
    private val paraGapPx: Float,
    private val density: Density,
    private val firstLineIndentSp: Float,
    private val mutedColor: Color = Color.Unspecified
) {

    private val baseSp: Float = run {
        val v = base.fontSize.value
        if (v.isNaN() || v <= 0f) 19f else v
    }

    /** 正文样式的字号（sp），渲染端画装饰时要对齐用 */
    val baseFontSizeSp: Float get() = baseSp

    fun styleOf(block: Block): TextStyle = when (block.kind) {
        RichKind.HEADING -> {
            val f = when (block.level) {
                1 -> 1.52f
                2 -> 1.34f
                3 -> 1.18f
                4 -> 1.08f
                5 -> 1.02f
                else -> 1f
            }
            val fs = baseSp * f
            base.copy(
                fontSize = TextUnit(fs, TextUnitType.Sp),
                lineHeight = TextUnit(fs * 1.5f, TextUnitType.Sp),
                fontWeight = FontWeight.SemiBold,
                textIndent = null
            )
        }

        RichKind.CODE -> {
            val fs = baseSp * 0.92f
            base.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = TextUnit(fs, TextUnitType.Sp),
                lineHeight = TextUnit(fs * 1.6f, TextUnitType.Sp),
                letterSpacing = TextUnit(0f, TextUnitType.Sp),
                textIndent = null
            )
        }

        RichKind.TABLE_HEAD, RichKind.TABLE_ROW -> {
            val fs = baseSp * 0.86f
            base.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = TextUnit(fs, TextUnitType.Sp),
                lineHeight = TextUnit(fs * 1.65f, TextUnitType.Sp),
                letterSpacing = TextUnit(0f, TextUnitType.Sp),
                fontWeight = if (block.kind == RichKind.TABLE_HEAD) FontWeight.Medium else FontWeight.Normal,
                textIndent = null
            )
        }

        RichKind.QUOTE ->
            base.copy(color = if (mutedColor == Color.Unspecified) base.color else mutedColor, textIndent = null)

        else -> base
    }

    /**
     * 这一段要留出多少左侧缩进。
     *
     * 列表项 / 引用块整段右移（首行和续行一样，项目符号、引用竖线画在留白里）；
     * 普通段落只有首行缩进两个字，续行顶格 —— 中文排版的常规做法。
     */
    fun indentForSlice(block: Block, continuation: Boolean): TextIndent? {
        val gutter = when (block.kind) {
            RichKind.LIST -> listGutter(block.level)
            RichKind.QUOTE -> quoteGutter(block.level)
            else -> 0f
        }
        if (gutter > 0f) {
            val g = with(density) { gutter.dp.toSp() }
            return TextIndent(firstLine = g, restLine = g)
        }
        if (block.kind != RichKind.BODY) return null
        if (continuation || firstLineIndentSp <= 0f) return null
        return TextIndent(firstLine = TextUnit(firstLineIndentSp, TextUnitType.Sp))
    }

    /** 渲染端画项目符号 / 引用竖线用的留白宽度（dp） */
    fun gutterDp(block: Block): Float = when (block.kind) {
        RichKind.LIST -> listGutter(block.level)
        RichKind.QUOTE -> quoteGutter(block.level)
        else -> 0f
    }

    private fun listGutter(level: Int): Float = 18f + 16f * level.coerceIn(0, 5).toFloat()

    private fun quoteGutter(level: Int): Float = 14f + 12f * level.coerceIn(0, 5).toFloat()

    /** 这个块之前要留多少空隙 */
    private fun gapBefore(block: Block): Float = when (block.kind) {
        RichKind.HEADING -> paraGapPx * when (block.level) {
            1 -> 1.8f
            2 -> 1.5f
            3 -> 1.3f
            else -> 1.1f
        }

        RichKind.CODE -> paraGapPx * 1.15f
        RichKind.QUOTE -> paraGapPx * 0.5f
        RichKind.LIST -> paraGapPx * 0.35f
        RichKind.TABLE_HEAD, RichKind.TABLE_ROW -> paraGapPx * 0.2f
        RichKind.RULE -> paraGapPx * 1.4f
        else -> paraGapPx
    }

    /** 这个块之后要留多少空隙 */
    private fun gapAfter(block: Block): Float = when (block.kind) {
        RichKind.HEADING -> paraGapPx * 0.9f
        RichKind.CODE -> paraGapPx * 0.9f
        RichKind.QUOTE -> paraGapPx * 0.35f
        RichKind.LIST -> paraGapPx * 0.25f
        RichKind.TABLE_HEAD, RichKind.TABLE_ROW -> paraGapPx * 0.1f
        RichKind.RULE -> paraGapPx * 1.2f
        else -> paraGapPx * 0.4f
    }

    /** 两个块之间的空隙：前一块想留的和后一块想要的那个，取大的 */
    fun gapBetween(prev: Block?, next: Block): Float {
        if (prev == null || prev.index == next.index) return 0f
        return maxOf(gapAfter(prev), gapBefore(next))
    }
}

/** 按阅读偏好建一个块样式表（分页与渲染都从这里取规则） */
fun inkBlockStyler(
    base: TextStyle,
    prefs: ReaderPrefs,
    density: Density,
    paraGapPx: Float,
    mutedColor: Color = Color.Unspecified
): BlockStyler = BlockStyler(
    base = base,
    paraGapPx = paraGapPx,
    density = density,
    firstLineIndentSp = if (prefs.firstLineIndent) prefs.fontSize * 2f else 0f,
    mutedColor = mutedColor
)

/**
 * 分页器。
 *
 * 按「段落块」为单位做贪心填充：能整段放下就放下，放不下就用 TextMeasurer
 * 找到当前可用高度内最后一行的行尾位置，把段落切开，剩余部分顺延到下一页。
 * 这样既能精确控制段间距，也能保证任意字号/行距下排版都不会溢出页面。
 *
 * 每个块可以有自己的 [BlockStyler]（标题更大、代码块等宽、列表缩进），
 * 行高与段距都按块算 —— 所以「加粗跨页」「代码块被切开也不丢缩进」都成立：
 * 切分只按测量到的行尾切，切完每一截仍然带着同一个块的样式。
 */
class Paginator(private val measurer: TextMeasurer) {

    /**
     * @param maxPages 只排出前 N 页就返回（默认不限）。
     *   跨章衔接页里「下一章的第一页」只需要一页，用它避免为了一个衔接页
     *   把整章都排一遍——这是跨章滑动能做到「无感」的关键。
     * @param styler 逐块样式；不传就按整章统一样式排（TXT / DOCX 就是这种）
     */
    fun paginate(
        blocks: List<Block>,
        style: TextStyle,
        widthPx: Int,
        heightPx: Int,
        paraGapPx: Float,
        density: Density,
        maxPages: Int = Int.MAX_VALUE,
        /** 首行缩进（sp）。必须参与测量：渲染时首行少了两个字，
         *  段落就可能多出一行，不算进去最后一行会溢出页面被裁掉 */
        firstLineIndentSp: Float = 0f,
        /** 每章第一页顶部要留出的高度（px）：给章首页的大标题用。
         *  渲染端会照着同一个高度画标题，所以两边必须一致 */
        firstPageInsetPx: Float = 0f,
        styler: BlockStyler? = null,
        cancellation: CoroutineContext = EmptyCoroutineContext
    ): List<Page> {
        if (blocks.isEmpty() || widthPx <= 0 || heightPx <= 0) return emptyList()

        val st = styler ?: BlockStyler(style, paraGapPx, density, firstLineIndentSp)
        val constraints = Constraints(maxWidth = widthPx)

        // 同一种样式的行高只探一次：真测一个汉字，取「字号 × 行距」与实测的较大值。
        // 中文实际渲染出来的行盒常常比名义值高一点点（标点、字形度量、系统字体回退），
        // 以前按名义行高算「这页能放几行」，最后一行下半截会掉到页面外被裁掉。
        val lineHeights = HashMap<TextStyle, Float>(8)
        fun lineHeightOf(s: TextStyle): Float {
            lineHeights[s]?.let { return it }
            val nominal = with(density) {
                val lh = if (s.lineHeight.isSp) s.lineHeight.toPx() else Float.NaN
                val font = if (s.fontSize.isSp) s.fontSize.toPx() else 19f * density.density
                if (!lh.isFinite() || lh <= 0f) font * 1.6f else lh
            }
            val probe = runCatching {
                val r = measurer.measure(
                    text = androidx.compose.ui.text.AnnotatedString("永"),
                    style = s,
                    constraints = constraints
                )
                if (r.lineCount > 0) r.getLineBottom(0) - r.getLineTop(0) else 0f
            }.getOrDefault(0f)
            val v = maxOf(nominal, probe).coerceAtLeast(1f)
            lineHeights[s] = v
            return v
        }

        // 再留 1dp 余量，防止舍入误差让最后一行压线
        val safeHeightPx = heightPx - density.density

        val pages = ArrayList<Page>()
        val items = ArrayList<Placed>()
        // 第一页先占掉标题的高度（章首页要画大标题）；flush 之后归零，后续页不受影响
        var used = firstPageInsetPx.coerceAtLeast(0f)

        /** @return false 表示页数已经够了，调用方应当立刻返回 */
        fun flush(): Boolean {
            if (items.isNotEmpty()) {
                pages += Page(
                    items = items.toList(),
                    startOffset = items.first().start,
                    endOffset = items.last().end
                )
                items.clear()
            }
            used = 0f
            return pages.size < maxPages
        }

        for (block in blocks) {
            if (block.text.isEmpty()) continue
            if (block.kind == RichKind.IMAGE) {
                cancellation.ensureActive()
                val reference = com.inkreader.parse.DocumentImages.reference(block.marker)
                var gap = if (items.isEmpty()) 0f else st.gapBetween(blocks.getOrNull(items.last().blockIndex), block)
                val natural = (reference?.heightAt(widthPx.toFloat(), safeHeightPx, density.density) ?: 48f * density.density)
                    .coerceAtLeast(36f * density.density)
                if (items.isNotEmpty() && natural + gap > safeHeightPx - used) {
                    if (!flush()) return pages
                    gap = 0f
                }
                val height = natural.coerceAtMost((safeHeightPx - used - gap).coerceAtLeast(1f))
                items += Placed(block.index, block.start, block.start + block.text.length, false, height)
                used += gap + height
                continue
            }
            val blockStyle = st.styleOf(block)
            val lineHeightPx = lineHeightOf(blockStyle)
            var cursor = 0
            var continuation = false
            while (cursor < block.text.length) {
                cancellation.ensureActive()
                // 段间距只在这一段的第一截、且页面上已经有内容时才留
                val gap = if (items.isEmpty() || continuation) {
                    0f
                } else {
                    st.gapBetween(blocks.getOrNull(items.last().blockIndex), block)
                }
                val avail = safeHeightPx - used - gap
                if (avail < lineHeightPx && items.isNotEmpty()) {
                    if (!flush()) return pages
                    continue
                }
                // 极小窗口至少推进一行；空页不能反复 flush 而不消耗字符。
                val maxLines = floor(avail / lineHeightPx).toInt().coerceAtLeast(1)
                // Shape only enough lookahead for this page. Passing the entire remaining
                // paragraph on every page made long, unbroken text quadratic in time/memory.
                val indent = st.indentForSlice(block, continuation)
                val measureStyle = if (indent != null) blockStyle.copy(textIndent = indent) else blockStyle
                var window = maxOf(2048, maxLines.coerceAtMost(256) * 256)
                var consumed: Int
                var result: TextLayoutResult
                var fittingLines: Int
                var sub: String
                while (true) {
                    cancellation.ensureActive()
                    var end = (cursor.toLong() + window).coerceAtMost(block.text.length.toLong()).toInt()
                    if (end < block.text.length && Character.isHighSurrogate(block.text[end - 1]) &&
                        Character.isLowSurrogate(block.text[end])) end--
                    sub = block.text.substring(cursor, end)
                    result = measurer.measure(
                        text = inlineText(sub, block.start + cursor, block), style = measureStyle,
                        maxLines = maxLines, constraints = constraints)
                    fittingLines = (0 until result.lineCount)
                        .takeWhile { result.getLineBottom(it) <= avail }.size.coerceAtLeast(1)
                    consumed = result.getLineEnd(fittingLines - 1, false).coerceIn(1, sub.length)
                    // If the lookahead fits completely, grow it until we find the real page end.
                    if (consumed < sub.length || end == block.text.length) break
                    window = (window.toLong() * 2).coerceAtMost(block.text.length.toLong()).toInt()
                }
                val fitsAll = cursor + consumed == block.text.length
                val consumedHeight = result.getLineBottom(fittingLines - 1)

                items += Placed(
                    blockIndex = block.index,
                    start = block.start + cursor,
                    end = block.start + cursor + consumed,
                    continuation = continuation
                )
                used += consumedHeight + gap
                cursor += consumed

                if (!fitsAll) {
                    if (!flush()) return pages
                    // 这一段是被切开跨到下一页的，下一页开头那截是「续排」
                    continuation = true
                } else {
                    continuation = false
                }
            }
        }
        flush()
        return pages
    }

    companion object {
        /**
         * 把正文切成段落块。
         * 中文 TXT 有两种常见形态：段间空行分隔、或每行就是一段。
         * 用空行占比来判断，避免把连续对话行错误地黏成一整段。
         */
        fun toBlocks(text: String): List<Block> {
            if (text.isEmpty()) return emptyList()
            val lines = text.split('\n')
            var blank = 0
            var nonBlank = 0
            for (l in lines) if (l.isBlank()) blank++ else nonBlank++
            val mergeWrapped = nonBlank > 0 && blank.toFloat() / nonBlank > 0.30f

            val out = ArrayList<Block>()
            var bufStart = -1
            var bufEnd = -1
            var offset = 0

            fun flushBuf() {
                if (bufStart >= 0 && bufEnd > bufStart) {
                    out += Block(out.size, bufStart, text.substring(bufStart, bufEnd))
                }
                bufStart = -1
                bufEnd = -1
            }

            for (line in lines) {
                val trimmed = line.trimStart('　', ' ', '\t').trimEnd()
                val lineStart = offset
                if (trimmed.isEmpty()) {
                    if (mergeWrapped) flushBuf() else if (bufStart >= 0) flushBuf()
                } else {
                    if (bufStart < 0) bufStart = lineStart + (line.length - line.trimStart('　', ' ', '\t').length)
                    // 保留原文连续区间，换行与行首空白也必须参与偏移计算。
                    bufEnd = lineStart + line.trimEnd().length
                    if (!mergeWrapped) flushBuf()
                }
                offset += line.length + 1
            }
            flushBuf()
            return out
        }

        /**
         * 带样式地把正文切成块。
         *
         * 样式覆盖里已经写好了「哪一段是标题、哪一段是列表」，这里只做两件事：
         * ① 校验覆盖和正文严丝合缝（对不上就退化成 [toBlocks]，绝不硬套）；
         * ② 把行内样式 / 链接按块分好，渲染时就不用每次翻全表。
         */
        fun richBlocks(text: String, rich: RichChapter?): List<Block> {
            if (rich == null || text.isEmpty()) return toBlocks(text)

            var cursor = 0
            for (b in rich.blocks) {
                if (b.start < cursor || b.end < b.start || b.end > text.length ||
                    !text.substring(cursor, b.start).isBlank()) return toBlocks(text)
                cursor = b.end
            }
            if (!text.substring(cursor).isBlank()) return toBlocks(text)

            val out = ArrayList<Block>(rich.blocks.size)
            for ((i, b) in rich.blocks.withIndex()) {
                out += Block(
                    index = i,
                    start = b.start,
                    text = text.substring(b.start, b.end),
                    kind = b.kind,
                    level = b.level,
                    marker = b.marker,
                    spans = rich.spans.filter { it.end > b.start && it.start < b.end },
                    links = rich.links.filter { it.end > b.start && it.start < b.end }
                )
            }
            return out
        }

        private fun isCjk(c: Char): Boolean {
            val code = c.code
            return code in 0x4E00..0x9FFF || code in 0x3000..0x303F ||
                code in 0xFF00..0xFFEF || code in 0x3400..0x4DBF
        }

        /** 找到包含指定字符偏移的页码 */
        fun pageForOffset(pages: List<Page>, offset: Int): Int {
            if (pages.isEmpty()) return 0
            if (offset <= pages.first().startOffset) return 0
            for (i in pages.indices) {
                if (offset < pages[i].endOffset) return i
            }
            return pages.lastIndex
        }

        /** 整本书的阅读进度（按字符偏移） */
        fun progressOf(pages: List<Page>, pageIndex: Int): Float {
            if (pages.isEmpty()) return 0f
            val p = pages[pageIndex.coerceIn(0, pages.lastIndex)]
            return p.endOffset.toFloat() / pages.last().endOffset.coerceAtLeast(1)
        }
    }
}
