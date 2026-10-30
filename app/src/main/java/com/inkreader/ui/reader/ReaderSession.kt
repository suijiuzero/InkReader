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
import kotlinx.coroutines.CoroutineExceptionHandler
import android.widget.Toast
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
    internal fun imageFile(key: String): java.io.File = repo.files.imageFile(key)
    private val errors = CoroutineExceptionHandler { _, error ->
        loading = false
        pageCountSignature = ""
        Toast.makeText(container.appContext, error.localizedMessage ?: container.appContext.getString(R.string.action_unknown_error), Toast.LENGTH_LONG).show()
    }

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
    private var pendingDirection = androidx.compose.ui.unit.LayoutDirection.Ltr
    private var pendingOffset: Int? = null
    private var paginationJob: Job? = null
    private var paginationSerial = 0L
    private var workerMeasurerFactory: (() -> TextMeasurer)? = null
    private val neighborRequests = HashSet<Pair<Long, Int>>()
    private var chapterLoadJob: Job? = null
    private var chapterGeneration = 0L
    private var layoutGeneration = 0L
    private val neighborJobs = ArrayList<Job>()

    private fun invalidateNeighbors() {
        layoutGeneration++
        neighborJobs.forEach { it.cancel() }
        neighborJobs.clear()
        neighborRequests.clear()
        prevChapter = null
        nextChapter = null
    }

    /** 全书页码：总页数 + 「每章之前累计多少页」的前缀和（下标 = 章节序号 + 1） */
    var bookTotalPages by mutableIntStateOf(0)
        private set
    var pagesBefore by mutableStateOf<IntArray?>(null)
        private set
    private var pageCountSignature = ""
    private var pageCountJob: kotlinx.coroutines.Job? = null
    private val pageCountCache = com.inkreader.data.repo.PageCountCache(java.io.File(container.appContext.cacheDir, "page-counts"))

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
            // 不使用 TextStyle/字体对象的运行期hash，保证进程重启后仍可复用。
            append(style.fontSize).append('|').append(style.lineHeight).append('|').append(style.letterSpacing).append('|')
            append(style.fontWeight?.weight).append('|').append(style.fontStyle).append('|')
            append(style.textAlign).append('|').append(style.textDirection).append('|').append(pendingDirection).append('|')
            append(android.os.LocaleList.getDefault().toLanguageTags()).append('|')
            append(d.density).append('|').append(d.fontScale)
            chapters.forEach { append('|').append(it.id).append(':').append(it.fileName).append(':').append(it.charCount).append(':').append(it.title) }
        }
        if (sig == pageCountSignature) return
        pageCountSignature = sig
        val w = pendingWidth
        val h = pendingHeight
        val gap = paraGapPx(d)
        val indent = if (prefs.firstLineIndent) prefs.fontSize * 2 else 0f
        pageCountJob?.cancel()
        pagesBefore = null
        bookTotalPages = 0
        val countingChapters = chapters.toList()
        val styler = inkBlockStyler(style, prefs, d, gap)
        val counter = workerMeasurerFactory?.invoke() ?: m
        pageCountJob = scope.launch(errors) {
            val diskSignature = withContext(Dispatchers.IO) {
                buildString {
                    append("pages-v2|").append(android.os.Build.FINGERPRINT).append('|').append(sig)
                    for (chapter in countingChapters) {
                        val body = repo.files.file(chapter.fileName)
                        val rich = repo.files.file(repo.files.richNameOf(chapter.fileName))
                        append('|').append(body.length()).append(':').append(body.lastModified())
                        append(':').append(rich.length()).append(':').append(rich.lastModified())
                    }
                }
            }
            val cached = withContext(Dispatchers.IO) { pageCountCache.load(diskSignature, countingChapters.size) }
            if (cached != null) { pagesBefore = cached; bookTotalPages = cached.last(); return@launch }
            // 前台当前章优先；低优先级逐章计数，避免全书统计抢占翻页。
            val counts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val acc = IntArray(countingChapters.size + 1)
                for (i in countingChapters.indices) {
                    kotlinx.coroutines.yield()
                    kotlinx.coroutines.delay(10)
                    if (!isActive) return@withContext null
                    val text = repo.chapterText(countingChapters[i])
                    val pages = if (text.isBlank()) 1 else Paginator(counter).paginate(
                        blocks = Paginator.richBlocks(text, repo.chapterRich(countingChapters[i], text.length)),
                        style = style,
                        widthPx = w,
                        heightPx = h,
                        paraGapPx = gap,
                        density = d,
                        firstLineIndentSp = indent,
                        firstPageInsetPx = with(d) { chapterHeadingHeightDp(countingChapters[i].title).dp.toPx() },
                        styler = styler,
                        cancellation = kotlin.coroutines.coroutineContext
                    ).size.coerceAtLeast(1)
                    if (acc[i].toLong() + pages > Int.MAX_VALUE) return@withContext null
                    acc[i + 1] = acc[i] + pages
                }
                acc
            } ?: return@launch
            withContext(Dispatchers.IO) { pageCountCache.save(diskSignature, counts) }
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
        // Reserve progress priority before IO; later user navigation must take precedence.
        val progressToken = repo.newProgressToken(bookId)
        scope.launch(errors) {
            loading = true
            val b = repo.book(bookId)
            val chs = repo.chapters(bookId)
            val loadedMarks = repo.marks(bookId)
            // Publish the initial state atomically on Main. Exposing chapters before this
            // suspension let a fast TOC request race with loadChapter(initialChapter).
            book = b
            chapters = chs
            marks = loadedMarks
            if (b == null || chs.isEmpty()) {
                loading = false
                return@launch
            }
            // 外部没指定位置时用书籍记忆的进度
            val target = if (initialChapter >= 0) initialChapter else b.lastChapterIndex
            val off = if (initialChapter >= 0) initialOffset.coerceAtLeast(0) else b.lastCharOffset
            loadChapter(target.coerceIn(0, chs.lastIndex), off)
            // 打开即算读过，书架排序会前移
            repo.updateProgress(bookId, target.coerceIn(0, chs.lastIndex), off, b.progress, progressToken)
            loading = false
        }
    }

    /**
     * 加载第 [index] 章。[discardOldCache] 为 true 时不把刚离开的章缓存成邻章——
     * 拆章后旧章正文已被改写，缓存会让往回滑时读到拆分前的整章内容。
     */
    fun loadChapter(
        index: Int,
        offset: Int = 0,
        andPaginate: Boolean = true,
        discardOldCache: Boolean = false
    ) {
        val chs = chapters
        if (chs.isEmpty() || index !in chs.indices) return
        chapterLoadJob?.cancel()
        paginationJob?.cancel()
        val generation = ++chapterGeneration
        chapterLoadJob = scope.launch(errors) {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val ch = chs[index]
            val loadLayout = layoutGeneration

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
            val loadedMarks = repo.marks(bookId)
            if (!isActive || generation != chapterGeneration || chapters !== chs) return@launch

            if (oldIndex != index) { pages = emptyList(); pageIndex = 0 }
            chapterIndex = index
            chapterText = text
            blocks = blks
            scrollOffset = offset.coerceAtLeast(0)
            jumpRequest = offset.coerceAtLeast(0)
            marks = loadedMarks
            pendingOffset = offset.coerceAtLeast(0)

            if (cached != null && loadLayout == layoutGeneration && cached.pagesComplete && cached.pages.isNotEmpty()) {
                pages = cached.pages
                pageIndex = Paginator.pageForOffset(cached.pages, offset.coerceIn(0, text.length))
                    .coerceIn(0, cached.pages.lastIndex)
                pendingOffset = null
            } else if (andPaginate) {
                repaginate(keepOffset = offset.coerceAtLeast(0))
            }

            // 把刚离开的章节直接变成邻章缓存：来回翻章都不用重新解析
            // discardOldCache=true 时不缓存旧章——拆章后旧章正文已变，缓存会导致往回滑时串正文
            if (!discardOldCache && loadLayout == layoutGeneration && oldPages.isNotEmpty() && oldIndex != index) {
                val old = CachedChapter(oldIndex, oldTitle, oldText, oldBlocks, oldPages, true)
                if (index == oldIndex + 1) prevChapter = old
                else if (index == oldIndex - 1) nextChapter = old
            }
            if (prevChapter?.index != index - 1) prevChapter = null
            if (nextChapter?.index != index + 1) nextChapter = null

            rebuildPager()
            rebuildFlow()
            loadNeighbors()
            container.diagnostics.record(com.inkreader.data.diagnostics.UsageDiagnostics.Event.CHAPTER_READY,
                mapOf("durationMs" to (android.os.SystemClock.elapsedRealtime() - startedAt), "characters" to text.length, "blocks" to blks.size))
            saveProgress()
        }
    }

    /**
     * 重新分页。尺寸或排版参数还没就绪时直接返回，等 onSizeChanged 再触发。
     * keepOffset 缺省时沿用「待处理偏移」，若没有则保持当前阅读位置——
     * 这样字号/行距变化引起的二次分页不会把读者甩回第一章开头。
     */
    fun repaginate(keepOffset: Int = pendingOffset ?: currentOffset()) {
        val original = measurer ?: run { rebuildPager(); rebuildFlow(); return }
        val style = pendingStyle ?: return
        val d = pendingDensity ?: return
        if (pendingWidth <= 0 || pendingHeight <= 0) return
        paginationJob?.cancel()
        val serial = ++paginationSerial
        val chapter = chapterGeneration
        val layout = layoutGeneration
        val inputBlocks = blocks
        val w = pendingWidth
        val h = pendingHeight
        val requestedPrefs = prefs
        val gap = paraGapPx(d)
        val inset = with(d) { chapterHeadingHeightDp(currentChapter?.title.orEmpty()).dp.toPx() }
        val worker = workerMeasurerFactory?.invoke() ?: original
        pendingOffset = keepOffset
        paginationJob = scope.launch(errors) {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.Default) {
                Paginator(worker).paginate(inputBlocks, style, w, h, gap, d,
                    firstLineIndentSp = if (requestedPrefs.firstLineIndent) requestedPrefs.fontSize * 2 else 0f,
                    firstPageInsetPx = inset, styler = inkBlockStyler(style, requestedPrefs, d, gap),
                    cancellation = kotlin.coroutines.coroutineContext)
            }
            if (!isActive || serial != paginationSerial || chapter != chapterGeneration ||
                layout != layoutGeneration || inputBlocks !== blocks) return@launch
            pages = result
            val restoredOffset = pendingOffset ?: keepOffset
            pageIndex = Paginator.pageForOffset(result, restoredOffset)
                .coerceIn(0, (result.size - 1).coerceAtLeast(0))
            pendingOffset = null
            if (prefs.pageMode == PageMode.SCROLL) {
                scrollOffset = restoredOffset
                jumpRequest = restoredOffset
            }
            rebuildPager()
            rebuildFlow()
            loadNeighbors()
            container.diagnostics.record(com.inkreader.data.diagnostics.UsageDiagnostics.Event.PAGINATION_READY,
                mapOf("durationMs" to (android.os.SystemClock.elapsedRealtime() - startedAt), "pages" to result.size, "characters" to chapterText.length))
            saveProgress()
        }
    }

    fun setPaginationContext(
        measurer: TextMeasurer,
        style: TextStyle,
        widthPx: Int,
        heightPx: Int,
        density: Density,
        direction: androidx.compose.ui.unit.LayoutDirection = androidx.compose.ui.unit.LayoutDirection.Ltr,
        workerFactory: (() -> TextMeasurer)? = null
    ) {
        val changed = this.pendingWidth != widthPx || this.pendingHeight != heightPx ||
            this.pendingStyle != style || this.measurer !== measurer || this.pendingDensity != density || this.pendingDirection != direction
        val anchor = currentOffset()
        this.workerMeasurerFactory = workerFactory
        this.measurer = measurer
        this.pendingStyle = style
        this.pendingWidth = widthPx
        this.pendingHeight = heightPx
        this.pendingDensity = density
        this.pendingDirection = direction
        if (changed) {
            // 排版上下文变了，邻章缓存里的页码全部作废
            invalidateNeighbors()
            if (prefs.pageMode == PageMode.SCROLL) { scrollOffset = anchor; jumpRequest = anchor }
            container.diagnostics.record(com.inkreader.data.diagnostics.UsageDiagnostics.Event.LAYOUT_CHANGED,
                mapOf("widthPx" to widthPx, "heightPx" to heightPx, "fontSp" to prefs.fontSize,
                    "density" to density.density, "fontScale" to density.fontScale))
            repaginate(keepOffset = anchor)
        }
        startPageCount()
    }

    private fun paraGapPx(d: Density): Float = with(d) { (prefs.fontSize * prefs.paraSpacing).sp.toPx() }

    /** 预排前后章：上一章取末页（要整章排）、下一章取首页（只排一页） */
    private fun loadNeighbors() {
        neighborJobs.removeAll { !it.isActive }
        val m = measurer
        val style = pendingStyle
        val d = pendingDensity
        if (m == null || style == null || d == null || pendingWidth <= 0 || pendingHeight <= 0) return
        val gap = paraGapPx(d)
        val w = pendingWidth
        val h = pendingHeight
        val generation = layoutGeneration

        val neighborFactory = workerMeasurerFactory
        val prevIdx = chapterIndex - 1
        val nextIdx = chapterIndex + 1

        if (prevIdx in chapters.indices) {
            val cur = prevChapter
            if ((cur == null || cur.index != prevIdx || !cur.pagesComplete) && neighborRequests.add(generation to prevIdx)) {
                val worker = neighborFactory?.invoke() ?: m
                neighborJobs += scope.launch(errors) {
                    try {
                    val c = buildChapter(prevIdx, worker, style, d, gap, w, h, full = true)
                    if (isActive && generation == layoutGeneration && chapterIndex - 1 == prevIdx) {
                        prevChapter = c
                        rebuildPager()
                        rebuildFlow()
                    }
                    } finally { neighborRequests.remove(generation to prevIdx) }
                }
            }
        } else {
            prevChapter = null
        }

        if (nextIdx in chapters.indices) {
            val cur = this.nextChapter
            if ((cur == null || cur.index != nextIdx) && neighborRequests.add(generation to nextIdx)) {
                val worker = neighborFactory?.invoke() ?: m
                neighborJobs += scope.launch(errors) {
                    try {
                    val c = buildChapter(nextIdx, worker, style, d, gap, w, h, full = false)
                    if (isActive && generation == layoutGeneration && chapterIndex + 1 == nextIdx) {
                        nextChapter = c
                        rebuildPager()
                        rebuildFlow()
                    }
                    } finally { neighborRequests.remove(generation to nextIdx) }
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
        val requestedPrefs = prefs
        val inset = with(d) { chapterHeadingHeightDp(ch.title).dp.toPx() }
        val pgs = withContext(Dispatchers.Default) { Paginator(m).paginate(
            blocks = blks,
            style = style,
            widthPx = width,
            heightPx = height,
            paraGapPx = gapPx,
            density = d,
            maxPages = if (full) Int.MAX_VALUE else 1,
            firstLineIndentSp = if (requestedPrefs.firstLineIndent) requestedPrefs.fontSize * 2 else 0f,
            firstPageInsetPx = inset,
            styler = inkBlockStyler(style, requestedPrefs, d, gapPx),
            cancellation = kotlin.coroutines.coroutineContext
        ) }
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
            scope.launch(errors) {
                repo.deleteMark(existing.id)
                marks = repo.marks(bookId)
            }
            return false
        }
        val snippet = pageExcerpt(page.startOffset)
        scope.launch(errors) {
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
            scope.launch(errors) {
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
        scope.launch(errors) {
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
        if (pages.isEmpty()) return
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
        if (offset < 0) return
        if (prefs.pageMode == PageMode.SCROLL) {
            scrollOffset = offset
            jumpRequest = offset
        } else {
            if (paginationJob?.isActive == true) pendingOffset = offset
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
        else pendingOffset ?: (pages.getOrNull(pageIndex)?.startOffset ?: 0)

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
        // 用「每章字数前缀和」代替组合期的两次 O(n) 求和；chapters 真正换新时才重算
        val prefix = chapterCharPrefix()
        val total = prefix[chs.size].coerceAtLeast(1)
        val done = prefix.getOrElse(chapterIndex) { 0 }
        val cur = chs.getOrNull(chapterIndex)
        val inChapter = if (cur != null && cur.charCount > 0)
            (cur.charCount * chapterProgress()).toLong() else 0L
        return ((done + inChapter) / total.toFloat()).coerceIn(0f, 1f)
    }

    // 全书字数前缀和缓存（下标 = 章节序号 + 1，前缀和末位即全书总字数）
    private var charPrefixCache: LongArray? = null
    private var charPrefixSource: List<Chapter>? = null

    /** chapters 列表实例没换就复用缓存，换新（导入/拆章等）才重算一次 */
    private fun chapterCharPrefix(): LongArray {
        val chs = chapters
        val cached = charPrefixCache
        if (cached != null && cached.size == chs.size + 1 && charPrefixSource === chs) return cached
        val acc = LongArray(chs.size + 1)
        for (i in chs.indices) acc[i + 1] = acc[i] + chs[i].charCount.toLong()
        charPrefixCache = acc
        charPrefixSource = chs
        return acc
    }

    private data class ProgressPosition(val chapter: Int, val offset: Int, val progress: Float)
    private data class ProgressRequest(val position: ProgressPosition, val token: Long)
    private val progressWriter = CoalescingWriter<ProgressRequest>(container.scope,
        onError = { error ->
            container.diagnostics.record(com.inkreader.data.diagnostics.UsageDiagnostics.Event.SAVE_FAILURE)
            Toast.makeText(container.appContext,
                error.localizedMessage ?: container.appContext.getString(R.string.action_unknown_error),
                Toast.LENGTH_LONG).show()
        }
    ) { request ->
        val (idx, off, progress) = request.position
        repo.updateProgress(bookId, idx, off, progress, request.token)
        if (idx == chapterIndex && off == currentOffset()) {
            book = book?.copy(lastChapterIndex = idx, lastCharOffset = off, progress = progress)
        }
    }

    fun saveProgress() {
        if (book == null || chapters.isEmpty()) return
        val position = ProgressPosition(chapterIndex, currentOffset(), bookProgress())
        val token = repo.newProgressToken(bookId)
        progressWriter.submit(ProgressRequest(position, token))
    }

    // ==================== 偏好 ====================

    fun updatePrefs(transform: (ReaderPrefs) -> ReaderPrefs, persist: Boolean = true) {
        val next = transform(prefs)
        val modeChanged = next.pageMode != prefs.pageMode
        val themeChanged = next.theme != prefs.theme
        // 换翻页方式前先记下旧模式下的位置，否则两套坐标会互相打架
        val anchor = currentOffset()
        prefs = next
        invalidateNeighbors()
        // 偏好全局一套：改完当场落盘，其他书与书架外观一起跟着变
        if (persist) container.prefs.save(next)
        if (themeChanged) container.library.syncUiTheme()
        if (modeChanged || next.pageMode == PageMode.SCROLL) {
            scrollOffset = anchor
            jumpRequest = anchor
        }
        repaginate(keepOffset = anchor)
        startPageCount()
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
        scope.launch(errors) {
            repo.toggleBookmark(bookId, chapterIndex, offset)
            marks = repo.marks(bookId)
            saveProgress()
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
        scope.launch(errors) {
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
        scope.launch(errors) {
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
        scope.launch(errors) {
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
        scope.launch(errors) {
            repo.deleteMark(id)
            marks = repo.marks(bookId)
        }
    }

    fun refreshMarks() {
        scope.launch(errors) { marks = repo.marks(bookId) }
    }

    /**
     * 在阅读器内直接从 [offset] 处把当前章拆分为两章（批注无损迁移），
     * 拆分完成后自动刷新章节列表并跳转到新拆出来的下一章开头。
     */
    fun splitCurrentChapterAt(offset: Int, onResult: (Boolean) -> Unit = {}) {
        val ch = currentChapter ?: run { onResult(false); return }
        if (offset <= 0 || offset >= chapterText.length) {
            onResult(false)
            return
        }
        scope.launch(errors) {
            val newId = repo.splitChapterAt(bookId, ch.id, offset)
            if (newId == null) {
                onResult(false)
                return@launch
            }
            book = repo.book(bookId)
            chapters = repo.chapters(bookId)
            marks = repo.marks(bookId)
            invalidateNeighbors()
            pageCountSignature = ""
            // 章节数变了，页码前缀和整体错位：先清空再用新章节重算，避免页脚显示陈旧页码
            pagesBefore = null
            bookTotalPages = 0
            val nextIdx = (chapterIndex + 1).coerceAtMost(chapters.lastIndex)
            // 旧章正文已被拆成两半，禁止把拆分前的整章缓存成上一章
            loadChapter(nextIdx, 0, discardOldCache = true)
            // 拆章不改变排版尺寸，不会触发 setPaginationContext 里的重算，这里显式补一次
            startPageCount()
            container.library.refresh()
            onResult(true)
        }
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
        searchJob = scope.launch(errors) {
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
