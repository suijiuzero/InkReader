package com.inkreader.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkreader.AppContainer
import com.inkreader.R
import com.inkreader.data.model.Book
import com.inkreader.data.model.Chapter
import com.inkreader.data.model.Mark
import com.inkreader.data.model.MarkType
import com.inkreader.data.model.PageMode
import com.inkreader.data.model.ReaderPrefs
import com.inkreader.data.model.SearchHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 分页模式下 pager 的一个「页槽」。
 *
 * 槽位 = 上一章末页 + 本章各页 + 下一章首页，这样左右滑动就能直接滑进邻章正文，
 * 不需要先点目录。槽位数量只由「前后是否存在邻章 + 本章页数」决定，
 * 邻章内容还没排好时 [page] 为 null（渲染成占位），因此槽位下标始终稳定，不会跳页。
 */
data class PagerEntry(
    val chapterIndex: Int,
    val chapterTitle: String,
    val text: String,
    val page: Page?,
    val localPageIndex: Int,
    val isCurrentChapter: Boolean,
    /** 本章带样式块；渲染端按 [Placed.blockIndex] 取块来套用样式与装饰 */
    val blocks: List<Block> = emptyList()
)

/** 滚动模式下的一个段落块，带上它属于哪一章 */
data class FlowBlock(
    val chapterIndex: Int,
    val chapterTitle: String,
    val text: String,
    val block: Block,
    val isChapterHead: Boolean
)

/**
 * 一次阅读会话的状态持有者。
 * 负责：加载书籍与章节、正文分块与分页、跨章连续阅读、进度记忆、标记增删、全文搜索。
 */
class ReaderSession(
    val container: AppContainer,
    val bookId: Long,
    val scope: CoroutineScope
) {
    private val repo = container.repo

    var book by mutableStateOf<Book?>(null)
        private set
    var chapters by mutableStateOf<List<Chapter>>(emptyList())
        private set
    var chapterIndex by mutableStateOf(0)
        private set
    var chapterText by mutableStateOf("")
        private set
    var loading by mutableStateOf(true)
        private set
    var prefs by mutableStateOf(container.prefs.load())
        private set
    var marks by mutableStateOf<List<Mark>>(emptyList())
        private set

    var blocks by mutableStateOf<List<Block>>(emptyList())
        private set

    /** 滚动模式下的阅读位置 */
    var scrollOffset by mutableStateOf(0)
        private set

    /** 滚动/分页模式的跳转请求，由界面消费后清空 */
    var jumpRequest by mutableStateOf<Int?>(null)
        private set

    var pages by mutableStateOf<List<Page>>(emptyList())
        private set
    var pageIndex by mutableStateOf(0)
        private set

    // ==================== 跨章连续阅读 ====================

    /** 已解析好的邻章缓存 */
    private data class CachedChapter(
        val index: Int,
        val title: String,
        val text: String,
        val blocks: List<Block>,
        val pages: List<Page>,
        val pagesComplete: Boolean
    )

    private var prevChapter: CachedChapter? = null
    private var nextChapter: CachedChapter? = null

    /** 分页模式的页槽（含邻章衔接页） */
    var pagerEntries by mutableStateOf<List<PagerEntry>>(emptyList())
        private set

    /** 滚动模式的三章连续块序列 */
    var flowBlocks by mutableStateOf<List<FlowBlock>>(emptyList())
        private set

    /** 正文区宽度（像素）：点击左右边缘翻页时用来算边缘带宽度 */
    val pageWidthPx: Int get() = pendingWidth

    /** 当前章首块在 [flowBlocks] 中的位置 */
    var flowCurrentStart by mutableStateOf(0)
        private set

    /** 当前章末块之后的位置 */
    var flowCurrentEnd by mutableStateOf(0)
        private set

    // 分页所需的上下文，设置变化时用它重排
    private var measurer: TextMeasurer? = null
    private var pendingStyle: TextStyle? = null
    private var pendingWidth = 0
    private var pendingHeight = 0
    private var pendingDensity: Density? = null
    private var pendingOffset = 0

    /** 全书页码：总页数 + 「每章之前累计多少页」的前缀和（下标 = 章节序号 + 1） */
    var bookTotalPages by mutableIntStateOf(0)
        private set
    var pagesBefore by mutableStateOf<IntArray?>(null)
        private set
    private var pageCountSignature = ""
    private var pageCountJob: kotlinx.coroutines.Job? = null

    /**
     * 当前页在全书的页码（从 1 开始）。还没统计出来时返回 -1。
     * 注意用的是「每章页数前缀和」，所以翻到第 3 章第 2 页时是 前两章页数 + 2。
     */
    val bookPageNo: Int
        get() {
            val pre = pagesBefore ?: return -1
            if (chapterIndex !in pre.indices - 1) return -1
            val base = pre.getOrElse(chapterIndex) { return -1 }
            return base + pageIndex + 1
        }

    /**
     * 后台把整本书按当前排版排一遍，数出每章多少页，供页脚显示「当前页/总页数」。
     * 排版参数或正文区尺寸一变就要重算，用签名去重；算完之前页脚先不显示数字。
     */
    private fun startPageCount() {
        val m = measurer ?: return
        val style = pendingStyle ?: return
        val d = pendingDensity ?: return
        if (pendingWidth <= 0 || pendingHeight <= 0 || chapters.isEmpty()) return
        val sig = buildString {
            append(prefs.fontSize).append('|').append(prefs.lineHeight).append('|')
            append(prefs.paraSpacing).append('|').append(prefs.letterSpacing).append('|')
            append(prefs.margin).append('|').append(prefs.font).append('|')
            append(prefs.firstLineIndent).append('|')
            append(pendingWidth).append('x').append(pendingHeight).append('|')
            append(chapters.size)
        }
        if (sig == pageCountSignature) return
        pageCountSignature = sig
        val w = pendingWidth
        val h = pendingHeight
        val gap = paraGapPx(d)
        val indent = if (prefs.firstLineIndent) prefs.fontSize * 2 else 0f
        pageCountJob?.cancel()
        val styler = inkBlockStyler(style, prefs, d, gap)
        pageCountJob = scope.launch {
            // 排整本书是纯计算，放到后台线程，别卡住翻页
            val counts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val acc = IntArray(chapters.size + 1)
                for (i in chapters.indices) {
                    if (!isActive) return@withContext null
                    val text = runCatching { repo.chapterText(chapters[i]) }.getOrDefault("")
                    val pages = if (text.isBlank()) 1 else Paginator(m).paginate(
                        blocks = Paginator.richBlocks(text, repo.chapterRich(chapters[i], text.length)),
                        style = style,
                        widthPx = w,
                        heightPx = h,
                        paraGapPx = gap,
                        density = d,
                        firstLineIndentSp = indent,
                        styler = styler
                    ).size.coerceAtLeast(1)
                    acc[i + 1] = acc[i] + pages
                }
                acc
            } ?: return@launch
            pagesBefore = counts
            bookTotalPages = counts.last()
        }
    }

    var searching by mutableStateOf(false)
        private set
    var searchHits by mutableStateOf<List<SearchHit>>(emptyList())
        private set
    var searchProgress by mutableStateOf(0f)
        private set
    private var searchJob: Job? = null

    val hasPrevNeighbor: Boolean get() = chapters.isNotEmpty() && chapterIndex > 0
    val hasNextNeighbor: Boolean get() = chapterIndex < chapters.lastIndex

    /** 本章第一页在 [pagerEntries] 中的下标（前面可能有上一章末页） */
    val currentChapterEntryStart: Int get() = if (hasPrevNeighbor) 1 else 0

    /** 当前页在 [pagerEntries] 中的下标 */
    val currentEntryIndex: Int get() = currentChapterEntryStart + pageIndex

    // ==================== 加载 ====================

    fun open(initialChapter: Int, initialOffset: Int) {
        scope.launch {
            loading = true
            val b = repo.book(bookId)
            val chs = repo.chapters(bookId)
            book = b
            chapters = chs
            marks = repo.marks(bookId)
            if (b == null || chs.isEmpty()) {
                loading = false
                return@launch
            }
            // 外部没指定位置时用书籍记忆的进度
            val target = if (initialChapter >= 0) initialChapter else b.lastChapterIndex
            val off = if (initialChapter >= 0 && initialOffset > 0) initialOffset else b.lastCharOffset
            loadChapter(target.coerceIn(0, chs.lastIndex), off)
            // 打开即算读过，书架排序会前移
            repo.updateProgress(bookId, target.coerceIn(0, chs.lastIndex), off, b.progress)
            loading = false
        }
    }

    fun loadChapter(index: Int, offset: Int = 0, andPaginate: Boolean = true) {
        val chs = chapters
        if (chs.isEmpty() || index !in chs.indices) return
        scope.launch {
            val ch = chs[index]

            // 邻章缓存命中就直接复用正文与分块，跨章切换才不会卡顿
            val cached = when (index) {
                prevChapter?.index -> prevChapter
                nextChapter?.index -> nextChapter
                else -> null
            }
            val oldIndex = chapterIndex
            val oldTitle = currentChapter?.title ?: ""
            val oldText = chapterText
            val oldBlocks = blocks
            val oldPages = pages

            val text = cached?.text ?: repo.chapterText(ch)
            val blks = cached?.blocks ?: Paginator.richBlocks(
                text,
                if (text.isEmpty()) null else repo.chapterRich(ch, text.length)
            )

            chapterIndex = index
            chapterText = text
            blocks = blks
            scrollOffset = offset.coerceAtLeast(0)
            jumpRequest = offset.coerceAtLeast(0)
            marks = repo.marks(bookId)
            pendingOffset = offset.coerceAtLeast(0)

            if (cached != null && cached.pagesComplete && cached.pages.isNotEmpty()) {
                pages = cached.pages
                pageIndex = Paginator.pageForOffset(cached.pages, offset.coerceIn(0, text.length))
                    .coerceIn(0, cached.pages.lastIndex)
                pendingOffset = 0
            } else if (andPaginate) {
                repaginate(keepOffset = offset.coerceAtLeast(0))
            }

            // 把刚离开的章节直接变成邻章缓存：来回翻章都不用重新解析
            if (oldPages.isNotEmpty() && oldIndex != index) {
                val old = CachedChapter(oldIndex, oldTitle, oldText, oldBlocks, oldPages, true)
                if (index == oldIndex + 1) prevChapter = old
                else if (index == oldIndex - 1) nextChapter = old
            }
            if (prevChapter?.index != index - 1) prevChapter = null
            if (nextChapter?.index != index + 1) nextChapter = null

            rebuildPager()
            rebuildFlow()
            loadNeighbors()
            saveProgress()
        }
    }

    /**
     * 重新分页。尺寸或排版参数还没就绪时直接返回，等 onSizeChanged 再触发。
     * keepOffset 缺省时沿用「待处理偏移」，若没有则保持当前阅读位置——
     * 这样字号/行距变化引起的二次分页不会把读者甩回第一章开头。
     */
    fun repaginate(keepOffset: Int = if (pendingOffset > 0) pendingOffset else currentOffset()) {
        val m = measurer ?: run { rebuildPager(); rebuildFlow(); return }
        val style = pendingStyle ?: run { rebuildPager(); rebuildFlow(); return }
        val d = pendingDensity ?: run { rebuildPager(); rebuildFlow(); return }
        if (pendingWidth <= 0 || pendingHeight <= 0) {
            rebuildPager(); rebuildFlow(); return
        }

        val result = Paginator(m).paginate(
            blocks = blocks,
            style = style,
            widthPx = pendingWidth,
            heightPx = pendingHeight,
            paraGapPx = paraGapPx(d),
            density = d,
            firstLineIndentSp = if (prefs.firstLineIndent) prefs.fontSize * 2 else 0f,
            firstPageInsetPx = with(d) {
                chapterHeadingHeightDp(chapters.getOrNull(chapterIndex)?.title ?: "").dp.toPx()
            },
            styler = inkBlockStyler(style, prefs, d, paraGapPx(d))
        )
        pages = result
        pageIndex = Paginator.pageForOffset(result, keepOffset).coerceIn(0, (result.size - 1).coerceAtLeast(0))
        pendingOffset = 0
        rebuildPager()
        rebuildFlow()
        loadNeighbors()
    }

    fun setPaginationContext(
        measurer: TextMeasurer,
        style: TextStyle,
        widthPx: Int,
        heightPx: Int,
        density: Density
    ) {
        val changed = this.pendingWidth != widthPx || this.pendingHeight != heightPx ||
            this.pendingStyle != style || this.measurer !== measurer
        this.measurer = measurer
        this.pendingStyle = style
        this.pendingWidth = widthPx
        this.pendingHeight = heightPx
        this.pendingDensity = density
        if (changed) {
            // 排版上下文变了，邻章缓存里的页码全部作废
            prevChapter = null
            nextChapter = null
            repaginate(keepOffset = currentOffset())
        }
        startPageCount()
    }

    private fun paraGapPx(d: Density): Float = with(d) { (prefs.fontSize * prefs.paraSpacing).sp.toPx() }

    /** 预排前后章：上一章取末页（要整章排）、下一章取首页（只排一页） */
    private fun loadNeighbors() {
        val m = measurer
        val style = pendingStyle
        val d = pendingDensity
        if (m == null || style == null || d == null || pendingWidth <= 0 || pendingHeight <= 0) return
        val gap = paraGapPx(d)
        val w = pendingWidth
        val h = pendingHeight

        val prevIdx = chapterIndex - 1
        val nextIdx = chapterIndex + 1

        if (prevIdx in chapters.indices) {
            val cur = prevChapter
            if (cur == null || cur.index != prevIdx || !cur.pagesComplete) {
                scope.launch {
                    val c = buildChapter(prevIdx, m, style, d, gap, w, h, full = true)
                    if (chapterIndex - 1 == prevIdx) {
                        prevChapter = c
                        rebuildPager()
                        rebuildFlow()
                    }
                }
            }
        } else {
            prevChapter = null
        }

        if (nextIdx in chapters.indices) {
            val cur = nextChapter
            if (cur == null || cur.index != nextIdx) {
                scope.launch {
                    val c = buildChapter(nextIdx, m, style, d, gap, w, h, full = false)
                    if (chapterIndex + 1 == nextIdx) {
                        nextChapter = c
                        rebuildPager()
                        rebuildFlow()
                    }
                }
            }
        } else {
            nextChapter = null
        }
    }

    private suspend fun buildChapter(
        index: Int,
        m: TextMeasurer,
        style: TextStyle,
        d: Density,
        gapPx: Float,
        width: Int,
        height: Int,
        full: Boolean
    ): CachedChapter {
        val ch = chapters[index]
        val text = repo.chapterText(ch)
        val blks = Paginator.richBlocks(
            text,
            if (text.isEmpty()) null else repo.chapterRich(ch, text.length)
        )
        val pgs = Paginator(m).paginate(
            blocks = blks,
            style = style,
            widthPx = width,
            heightPx = height,
            paraGapPx = gapPx,
            density = d,
            maxPages = if (full) Int.MAX_VALUE else 1,
            firstLineIndentSp = if (prefs.firstLineIndent) prefs.fontSize * 2 else 0f,
            firstPageInsetPx = with(d) {
                chapterHeadingHeightDp(chapters.getOrNull(index)?.title ?: "").dp.toPx()
            },
            styler = inkBlockStyler(style, prefs, d, gapPx)
        )
        return CachedChapter(index, ch.title, text, blks, pgs, full)
    }

    private fun rebuildPager() {
        val out = ArrayList<PagerEntry>(pages.size + 2)
        if (hasPrevNeighbor) {
            val idx = chapterIndex - 1
            val c = prevChapter?.takeIf { it.index == idx }
            out += PagerEntry(
                chapterIndex = idx,
                chapterTitle = c?.title ?: chapters.getOrNull(idx)?.title.orEmpty(),
                text = c?.text.orEmpty(),
                page = c?.pages?.lastOrNull(),
                localPageIndex = ((c?.pages?.size ?: 1) - 1).coerceAtLeast(0),
                isCurrentChapter = false,
                blocks = c?.blocks.orEmpty()
            )
        }
        val title = currentChapter?.title.orEmpty()
        pages.forEachIndexed { i, pg ->
            out += PagerEntry(
                chapterIndex = chapterIndex,
                chapterTitle = title,
                text = chapterText,
                page = pg,
                localPageIndex = i,
                isCurrentChapter = true,
                blocks = blocks
            )
        }
        if (hasNextNeighbor) {
            val idx = chapterIndex + 1
            val c = nextChapter?.takeIf { it.index == idx }
            out += PagerEntry(
                chapterIndex = idx,
                chapterTitle = c?.title ?: chapters.getOrNull(idx)?.title.orEmpty(),
                text = c?.text.orEmpty(),
                page = c?.pages?.firstOrNull(),
                localPageIndex = 0,
                isCurrentChapter = false,
                blocks = c?.blocks.orEmpty()
            )
        }
        pagerEntries = out
    }

    private fun rebuildFlow() {
        val out = ArrayList<FlowBlock>(blocks.size + 512)
        val prev = prevChapter?.takeIf { it.index == chapterIndex - 1 }
        if (prev != null) {
            prev.blocks.forEachIndexed { i, b -> out += FlowBlock(prev.index, prev.title, prev.text, b, i == 0) }
        }
        val start = out.size
        val title = currentChapter?.title.orEmpty()
        blocks.forEachIndexed { i, b -> out += FlowBlock(chapterIndex, title, chapterText, b, i == 0) }
        val next = nextChapter?.takeIf { it.index == chapterIndex + 1 }
        if (next != null) {
            next.blocks.forEachIndexed { i, b -> out += FlowBlock(next.index, next.title, next.text, b, i == 0) }
        }
        flowCurrentStart = start
        flowCurrentEnd = start + blocks.size
        flowBlocks = out
    }

    // ==================== 按页标签 ====================

    /** 当前页上的标签。用「页区间包含标签偏移」判断，所以换字号重排后照样找得到 */
    private fun tagOnPage(page: Page): Mark? =
        marks.firstOrNull {
            it.type == MarkType.TAG && it.chapterIndex == chapterIndex &&
                it.startOffset >= page.startOffset && it.startOffset < page.endOffset
        }

    fun currentPageTag(): Mark? = pages.getOrNull(pageIndex)?.let { tagOnPage(it) }

    /** 取该页开头的一小段正文，作为标签卡片上的摘要 */
    fun pageExcerpt(offset: Int, maxChars: Int = 42): String {
        val text = chapterText
        if (text.isEmpty()) return ""
        var i = offset.coerceIn(0, text.length)
        while (i < text.length && (text[i].isWhitespace() || text[i] == '　')) i++
        if (i >= text.length) return ""
        val end = (i + maxChars).coerceAtMost(text.length)
        val raw = text.substring(i, end)
            .replace('\n', ' ')
            .replace('　', ' ')
            .trim()
        return if (end < text.length) "$raw…" else raw
    }

    /**
     * 给当前页打标签 / 取消标签（同一个动作来回切）。
     * @return true 表示这次是「新增」，false 表示「取消」或当前不可用
     */
    fun togglePageTag(): Boolean {
        if (prefs.pageMode != PageMode.PAGE) return false
        val page = pages.getOrNull(pageIndex) ?: return false
        val existing = tagOnPage(page)
        if (existing != null) {
            scope.launch {
                repo.deleteMark(existing.id)
                marks = repo.marks(bookId)
            }
            return false
        }
        val snippet = pageExcerpt(page.startOffset)
        scope.launch {
            repo.addMark(
                Mark(
                    bookId = bookId,
                    chapterIndex = chapterIndex,
                    startOffset = page.startOffset,
                    endOffset = page.endOffset,
                    type = MarkType.TAG,
                    snippet = snippet
                )
            )
            marks = repo.marks(bookId)
        }
        return true
    }

    /** 全书所有标签：整页标签 + 选中段落打的标签，DB 已按章节 + 位置排序 */
    fun pageTags(): List<Mark> =
        marks.filter { it.type == MarkType.TAG || it.type == MarkType.MARK }

    /**
     * 给选中范围打「段落标签」/ 取消。同一段再打一次就是取消。
     * @return true 表示新增，false 表示取消
     */
    fun toggleRangeTag(start: Int, end: Int): Boolean {
        val s = start.coerceIn(0, chapterText.length)
        val e = end.coerceIn(s, chapterText.length)
        if (e <= s) return false
        val existing = marks.firstOrNull {
            it.type == MarkType.MARK && it.chapterIndex == chapterIndex &&
                it.startOffset == s && it.endOffset == e
        }
        if (existing != null) {
            scope.launch {
                repo.deleteMark(existing.id)
                marks = repo.marks(bookId)
            }
            return false
        }
        val snippet = chapterText.substring(s, e)
            .replace('\n', ' ')
            .replace('　', ' ')
            .trim()
            .take(60)
        scope.launch {
            repo.addMark(
                Mark(
                    bookId = bookId,
                    chapterIndex = chapterIndex,
                    startOffset = s,
                    endOffset = e,
                    type = MarkType.MARK,
                    snippet = snippet
                )
            )
            marks = repo.marks(bookId)
        }
        return true
    }

    /** 当前选区是否已经是「段落标签」 */
    fun isRangeTagged(start: Int, end: Int): Boolean =
        marks.any {
            it.type == MarkType.MARK && it.chapterIndex == chapterIndex &&
                it.startOffset == start.coerceIn(0, chapterText.length) &&
                it.endOffset == end.coerceIn(start.coerceIn(0, chapterText.length), chapterText.length)
        }

    fun chapterTitleAt(index: Int): String =
        chapters.getOrNull(index)?.title ?: container.appContext.getString(R.string.label_chapter_n, index + 1)

    // ==================== 翻页 / 跨章 ====================

    fun onUserPage(index: Int) {
        if (index == pageIndex) return
        pageIndex = index
        saveProgress()
    }

    /**
     * 分页模式：pager 停在了某个页槽。
     * 落在本章页上就是普通翻页；落在邻章衔接页上则直接切章，落点就是这一页，
     * 所以视觉上「下一章正文直接滑进来」，没有跳转感。
     */
    fun onPagerSettled(entryIndex: Int) {
        val e = pagerEntries.getOrNull(entryIndex) ?: return
        if (e.isCurrentChapter) {
            onUserPage(e.localPageIndex)
            return
        }
        val pg = e.page ?: return
        if (e.chapterIndex == chapterIndex) return
        loadChapter(e.chapterIndex, pg.startOffset)
    }

    /**
     * 滚动模式：视口已经整体越过当前章，直接切到邻章并保持阅读位置。
     */
    fun onFlowCrossed(targetChapter: Int, anchorOffset: Int) {
        if (targetChapter == chapterIndex) return
        if (targetChapter !in chapters.indices) return
        loadChapter(targetChapter, anchorOffset)
    }

    fun gotoChapter(index: Int, offset: Int = 0) {
        if (index !in chapters.indices) return
        if (index == chapterIndex && offset == 0) return
        loadChapter(index, offset)
    }

    /**
     * 「用户主动翻页」的请求序号（点击左右边缘、音量键）。
     * 每翻一次自增，pager 看到它变了就用带动画的翻页，而不是直接对齐 —— 跳转、重排仍然直接对齐。
     */
    var turnSerial by mutableIntStateOf(0)
        private set

    /**
     * 翻一页（点击屏幕左右边缘、音量键用）。跨章节时自动接上：往后翻到下一章开头，
     * 往前翻回到上一章最后一页——和手势滑动是同一套逻辑。
     */
    fun turnPage(delta: Int) {
        if (prefs.pageMode == PageMode.SCROLL) {
            requestScrollScreens(delta * 0.85f)
            return
        }
        if (pages.isEmpty()) return
        val target = pageIndex + delta
        turnSerial++
        when {
            target in pages.indices -> {
                pageIndex = target
                saveProgress()
            }

            target > pages.lastIndex -> if (hasNextNeighbor) loadChapter(chapterIndex + 1, 0)

            else -> {
                val prev = prevChapter
                if (prev != null && prev.index == chapterIndex - 1 && prev.pages.isNotEmpty()) {
                    loadChapter(prev.index, prev.pages.last().startOffset)
                } else if (hasPrevNeighbor) {
                    loadChapter(chapterIndex - 1, 0)
                }
            }
        }
    }

    /** 滚动模式下的「按屏滚动」请求，单位是屏（由列表按视口高度换算成像素） */
    var scrollByScreens by mutableStateOf<Float?>(null)
        private set

    fun requestScrollScreens(screens: Float) {
        scrollByScreens = screens
    }

    fun consumeScrollScreens() {
        scrollByScreens = null
    }

    fun nextChapter() = gotoChapter(chapterIndex + 1)
    fun prevChapter() = gotoChapter(chapterIndex - 1)

    fun jumpToOffset(offset: Int) {
        if (offset <= 0) return
        if (prefs.pageMode == PageMode.SCROLL) {
            scrollOffset = offset
            jumpRequest = offset
        } else {
            pageIndex = Paginator.pageForOffset(pages, offset)
                .coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        }
        saveProgress()
    }

    /** 滚动模式下的跳转请求，由列表消费后清空 */
    fun consumeJumpRequest() {
        jumpRequest = null
    }

    fun updateScrollOffset(offset: Int) {
        scrollOffset = offset
    }

    fun currentOffset(): Int =
        if (prefs.pageMode == PageMode.SCROLL) scrollOffset
        else pages.getOrNull(pageIndex)?.startOffset ?: 0

    fun chapterProgress(): Float {
        val len = chapterText.length
        if (len <= 0) return 0f
        if (prefs.pageMode == PageMode.SCROLL) {
            return (scrollOffset.toFloat() / len).coerceIn(0f, 1f)
        }
        val end = pages.getOrNull(pageIndex)?.endOffset ?: 0
        return (end.toFloat() / len).coerceIn(0f, 1f)
    }

    fun bookProgress(): Float {
        val chs = chapters
        if (chs.isEmpty()) return 0f
        val total = chs.sumOf { it.charCount.toLong() }.coerceAtLeast(1)
        val done = chs.take(chapterIndex).sumOf { it.charCount.toLong() }
        val cur = chs.getOrNull(chapterIndex)
        val inChapter = if (cur != null && cur.charCount > 0)
            (cur.charCount * chapterProgress()).toLong() else 0L
        return ((done + inChapter) / total.toFloat()).coerceIn(0f, 1f)
    }

    fun saveProgress() {
        if (book == null || chapters.isEmpty()) return
        val idx = chapterIndex
        val off = currentOffset()
        val p = bookProgress()
        scope.launch {
            withContext(Dispatchers.IO) { repo.updateProgress(bookId, idx, off, p) }
            book = book?.copy(lastChapterIndex = idx, lastCharOffset = off, progress = p)
        }
    }

    // ==================== 偏好 ====================

    fun updatePrefs(transform: (ReaderPrefs) -> ReaderPrefs, persist: Boolean = true) {
        val next = transform(prefs)
        val modeChanged = next.pageMode != prefs.pageMode
        val themeChanged = next.theme != prefs.theme
        // 换翻页方式前先记下旧模式下的位置，否则两套坐标会互相打架
        val anchor = currentOffset()
        prefs = next
        // 偏好全局一套：改完当场落盘，其他书与书架外观一起跟着变
        if (persist) container.prefs.save(next)
        if (themeChanged) container.library.syncUiTheme()
        if (modeChanged) {
            scrollOffset = anchor
            jumpRequest = anchor
        }
        repaginate(keepOffset = anchor)
    }

    fun setWindowBrightness(v: Float) {
        prefs = prefs.copy(brightness = v)
    }

    // ==================== 标记 ====================

    fun highlightsForChapter(): List<Mark> = marks.filter { it.chapterIndex == chapterIndex }

    /** 在当前页首加/取消书签 */
    fun toggleBookmark() {
        toggleBookmarkAt(currentOffset())
    }

    /** 在指定偏移处加/取消书签 */
    fun toggleBookmarkAt(offset: Int) {
        scope.launch {
            repo.toggleBookmark(bookId, chapterIndex, offset)
            marks = repo.marks(bookId)
        }
    }

    /** 与选区**有重叠**的高亮：完全相等匹配太脆，用户手抖一点就变成「又加了一条」 */
    private fun highlightNear(start: Int, end: Int): Mark? =
        marks.firstOrNull {
            it.type == MarkType.HIGHLIGHT && it.chapterIndex == chapterIndex &&
                it.startOffset < end && it.endOffset > start
        }

    /**
     * 高亮的「点色即切换」：这一段没有高亮就新建；已有同色高亮就取消；
     * 已有别的颜色就改成新颜色——这样操作条上点一下已经生效的那个颜色就等于取消。
     */
    fun toggleHighlight(start: Int, end: Int, color: Int) {
        val s = start.coerceIn(0, chapterText.length)
        val e = end.coerceIn(s, chapterText.length)
        if (e <= s) return
        val snippet = chapterText.substring(s, e).take(200)
        scope.launch {
            val same = highlightNear(s, e)
            when {
                same == null -> repo.addMark(
                    Mark(
                        bookId = bookId, chapterIndex = chapterIndex,
                        startOffset = s, endOffset = e,
                        type = MarkType.HIGHLIGHT, color = color, snippet = snippet
                    )
                )

                same.color == color -> repo.deleteMark(same.id)
                else -> repo.updateMark(same.copy(color = color))
            }
            marks = repo.marks(bookId)
        }
    }

    /** 当前选区已经生效的高亮颜色（没有则 null） */
    fun highlightColorAt(start: Int, end: Int): Int? {
        if (end <= start) return null
        return highlightNear(start, end)?.color
    }

    fun addHighlight(start: Int, end: Int, color: Int, note: String = "", asNote: Boolean = false) {
        val snippet = chapterText.substring(
            start.coerceIn(0, chapterText.length),
            end.coerceIn(0, chapterText.length)
        )
        scope.launch {
            repo.addMark(
                Mark(
                    bookId = bookId,
                    chapterIndex = chapterIndex,
                    startOffset = start,
                    endOffset = end,
                    type = if (asNote) MarkType.NOTE else MarkType.HIGHLIGHT,
                    color = color,
                    note = note,
                    snippet = snippet.take(200)
                )
            )
            marks = repo.marks(bookId)
        }
    }

    fun updateMark(mark: Mark, note: String? = null, color: Int? = null, type: MarkType? = null) {
        scope.launch {
            repo.updateMark(
                mark.copy(
                    note = note ?: mark.note,
                    color = color ?: mark.color,
                    type = type ?: mark.type
                )
            )
            marks = repo.marks(bookId)
        }
    }

    fun deleteMark(id: Long) {
        scope.launch {
            repo.deleteMark(id)
            marks = repo.marks(bookId)
        }
    }

    fun refreshMarks() {
        scope.launch { marks = repo.marks(bookId) }
    }

    // ==================== 搜索 ====================

    fun search(keyword: String) {
        searchJob?.cancel()
        if (keyword.isBlank()) {
            searchHits = emptyList()
            searching = false
            return
        }
        searching = true
        searchProgress = 0f
        searchJob = scope.launch {
            val hits = repo.searchInBook(bookId, keyword.trim()) { searchProgress = it }
            searchHits = hits
            searching = false
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searching = false
        searchHits = emptyList()
    }

    // ==================== 朗读跟随 ====================

    /** TTS 报到新的句子起点时，把阅读器翻到包含该偏移的那一页 */
    fun followTts(offset: Int) {
        if (offset < 0) return
        if (prefs.pageMode == PageMode.SCROLL) return
        val idx = Paginator.pageForOffset(pages, offset)
        if (idx != pageIndex && idx in pages.indices) {
            pageIndex = idx
        }
    }

    val currentChapter: Chapter?
        get() = chapters.getOrNull(chapterIndex)
}
