package com.inkreader.ui.reader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkreader.AppContainer
import com.inkreader.R
import android.view.KeyEvent
import com.inkreader.data.model.Mark
import com.inkreader.data.model.MarkType
import com.inkreader.data.model.PageAnimation
import com.inkreader.data.model.PageMode
import com.inkreader.parse.ChapterSplitter
import com.inkreader.parse.RichKind
import com.inkreader.tts.TtsBus
import com.inkreader.ui.component.InkIcon
import com.inkreader.ui.component.InkIconButton
import com.inkreader.ui.component.InkLoading
import com.inkreader.ui.component.InkProgress
import com.inkreader.ui.component.InkTextButton
import com.inkreader.ui.theme.HighlightColors
import com.inkreader.ui.theme.InkPalette
import com.inkreader.ui.theme.InkTheme
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette
import com.inkreader.ui.theme.readingTextStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 长按选中的一段文字（偏移相对当前章正文） */
private data class Selection(val start: Int, val end: Int)

/** 常驻页眉的高度（不含状态栏）。正文区与页内自带的页眉都用它，保证排版一致。 */
private val ReaderHeadBarHeight = 30.dp

/**
 * 「覆盖」和「仿真」这两种翻页不走 `HorizontalPager`，而是自己搭两层绘制
 * （因为需要「旧页压在新页上面」，pager 的绘制顺序压不住，见 CurlPageView.kt）。
 */
private val PageAnimation.isLayeredFlip: Boolean
    get() = this == PageAnimation.COVER || this == PageAnimation.FOLD

@Composable
fun ReaderScreen(
    container: AppContainer,
    bookId: Long,
    initialChapter: Int,
    initialOffset: Int,
    onBack: () -> Unit,
    onOpenMarks: (Long, String) -> Unit,
    onOpenEditor: (Long, String) -> Unit,
    onOpenDetail: (Long) -> Unit,
    onGoShelf: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val session = remember(bookId) { ReaderSession(container, bookId, scope) }
    val tts by TtsBus.state.collectAsState()
    val ctx = LocalContext.current

    var chrome by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showTts by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Selection?>(null) }
    // 长按建立选区时的「锚点词范围」，拖动扩选时以它为基准调整另一
    var selAnchor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var flashChapter by remember { mutableStateOf(false) }

    // 仿真折页的翻页状态。放在这一层是为了让「点击左右边缘」和「音量键」也能直接驱动它
    val curlState = rememberCurlState()

    /**
     * 翻一页的统一入口：「覆盖 / 仿真」交给 [curlState] 做动画，其余模式仍走原本的平移 / 无动画。
     * 点击左右边缘、音量键都从这里进。
     */
    fun turn(delta: Int) {
        val layered = session.prefs.pageMode == PageMode.PAGE &&
            session.prefs.pageAnimation.isLayeredFlip
        if (layered) curlState.animateTurn(delta) else session.turnPage(delta)
    }

    // 音量键翻页：阅读器在前台、且工具栏是收起状态时才接管
    // 呼出顶栏/底栏说明用户正在「操作」，这时音量键应该照常调音量
    val chromeNow = rememberUpdatedState(chrome)
    DisposableEffect(session) {
        container.readerKeyHandler = { code ->
            if (!session.prefs.volumeKeyTurn || chromeNow.value) {
                false
            } else {
                when (code) {
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        turn(1); true
                    }

                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        turn(-1); true
                    }

                    else -> false
                }
            }
        }
        onDispose { container.readerKeyHandler = null }
    }

    // 按页标签：下滑添/ 再下滑取消，用底部一句短提示 + 轻震动给反馈
    var tagHint by remember { mutableStateOf<String?>(null) }
    var tagHintToken by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current

    /**
     * 点击正文：左右两条边缘带翻页，中间唤出/收起工具栏。
     * x 是点击位置在正文区里的横向像素（正文块是 fillMaxWidth，和分页宽度同一套坐标）。
     */
    fun onPageTap(x: Float) {
        if (selection != null) {
            selection = null
            return
        }
        val w = session.pageWidthPx
        val edge = w * 0.28f
        when {
            w > 0 && x < edge -> turn(-1)
            w > 0 && x > w - edge -> turn(1)
            else -> chrome = !chrome
        }
    }

    fun onTagGesture() {
        if (session.loading || session.book == null) return
        if (session.prefs.pageMode != PageMode.PAGE || session.pages.isEmpty()) return
        val added = session.togglePageTag()
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (session.prefs.tagSound) TagSound.play(ctx)
        tagHint = if (added) ctx.getString(R.string.reader_tag_added) else ctx.getString(R.string.reader_tag_removed)
        tagHintToken++
    }

    LaunchedEffect(tagHintToken) {
        if (tagHintToken > 0) {
            kotlinx.coroutines.delay(1400)
            tagHint = null
        }
    }

    LaunchedEffect(Unit) { session.open(initialChapter, initialOffset) }

    // 切章（含跨章滑动）时中央淡入淡出显示新章节名
    LaunchedEffect(session.chapterIndex, session.prefs.chapterFlash) {
        if (session.book != null && session.prefs.chapterFlash) {
            flashChapter = true
            kotlinx.coroutines.delay(1500)
            flashChapter = false
        } else {
            flashChapter = false
        }
    }

    // 朗读跟随：自动翻到正在朗读的那一
    LaunchedEffect(tts.sentenceStart, tts.chapterIndex, tts.active) {
        if (!tts.active || tts.bookId != bookId) return@LaunchedEffect
        if (tts.chapterIndex != session.chapterIndex) {
            session.loadChapter(tts.chapterIndex, tts.sentenceStart.coerceAtLeast(0))
        } else {
            session.followTts(tts.sentenceStart)
        }
    }

    // 屏幕亮度
    val activity = ctx as? Activity
    LaunchedEffect(session.prefs.brightness, activity) {
        val w = activity?.window ?: return@LaunchedEffect
        val b = session.prefs.brightness
        w.attributes = w.attributes.also {
            it.screenBrightness = if (b < 0f) android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else b
        }
    }

    InkTheme(readerTheme = session.prefs.theme) {
        val p = LocalInkPalette.current
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val prefs = session.prefs
        val bodyColor = p.textPrimary
        val bodyStyle = remember(
            prefs.fontSize, prefs.lineHeight, prefs.letterSpacing, prefs.font, bodyColor
        ) {
            readingTextStyle(
                fontSizeSp = prefs.fontSize,
                lineHeightMultiple = prefs.lineHeight,
                letterSpacingEm = prefs.letterSpacing,
                sans = prefs.font == com.inkreader.data.model.FontChoice.SANS,
                color = bodyColor
            )
        }

        val marginDp = prefs.margin.dp
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // 常驻页眉的高度：正文区永远给它留出位置（无论页眉是否正在显示），
        // 这样点开/收起工具栏不会改变正文区尺寸、也就不会触发重新分页。
        val headBarHeight = ReaderHeadBarHeight

        Box(Modifier.fillMaxSize().background(p.background)) {
            if (session.loading) {
                InkLoading()
            } else if (session.book == null) {
                // 这本书已经被删掉了（比如在章节管理里删的整本）——
                // 不能一直转圈，给个明确提示 + 回书架的出口。
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.reader_book_deleted), style = InkType.UiLabel, color = p.textFaint)
                        Spacer(Modifier.height(12.dp))
                        InkTextButton(stringResource(R.string.action_back_shelf), color = p.accent) { onGoShelf() }
                    }
                }
            } else {
                // 常驻页眉（收起工具栏时可见）：返回首页 + 第几章 章节名
                // 分页模式下页眉画在每一页里（翻页时跟着整页走），这里不再重复画。
                AnimatedVisibility(
                    visible = !chrome && prefs.pageMode != PageMode.PAGE,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(180)),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    ReadingHeaderBar(
                        // 章首页（第一页）显示书名，正文区已经有大标题了；
                        // 从第二页起显示章节名，方便随时知道在读哪一章。
                        title = if (prefs.pageMode == PageMode.PAGE && session.pageIndex == 0) {
                            session.book?.title ?: ""
                        } else {
                            session.currentChapter?.title ?: (session.book?.title ?: "")
                        },
                        onBack = {
                            session.saveProgress()
                            container.library.refresh()
                            onGoShelf()
                        }
                    )
                }

                val gapPx = with(density) { (prefs.fontSize * prefs.paraSpacing).sp.toPx() }
                val paraGapDp = with(density) { gapPx.toDp() }
                // Markdown 的逐块样式表：和分页器用的是同一份规则，
                // 这样量高度和画正文永远一致（加粗跨页、代码块缩进才不会丢）
                val blockStyler = remember(bodyStyle, prefs, density, gapPx) {
                    inkBlockStyler(bodyStyle, prefs, density, gapPx, mutedColor = p.textSecondary)
                }
                val openLink: (String) -> Unit = remember(ctx) {
                    { url: String ->
                        runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                        Unit
                    }
                }
                val ttsForCurrent = tts.active && tts.bookId == bookId
                val ttsChapterArg = if (ttsForCurrent) tts.chapterIndex else -1
                val ttsStartArg = if (ttsForCurrent) tts.sentenceStart else -1
                val ttsEndArg = if (ttsForCurrent) tts.sentenceEnd else -1
                val onTapCb: (Float) -> Unit = { x -> onPageTap(x) }
                val onSelectCb: (Int, Int) -> Unit = { s, e ->
                    selAnchor = s to e
                    selection = Selection(s, e)
                }
                val onExtendCb: (Int) -> Unit = { off ->
                    val a = selAnchor
                    if (a != null) {
                        val len = session.chapterText.length
                        selection = if (off < a.first) {
                            Selection(off.coerceIn(0, len), a.second)
                        } else {
                            Selection(a.first, off.coerceIn(a.first, len))
                        }
                    }
                }
                val onHandleDragCb: (Int, Int) -> Unit = { which, off ->
                    val sel = selection
                    if (sel != null) {
                        val len = session.chapterText.length
                        selection = if (which == 0) {
                            Selection(off.coerceIn(0, (sel.end - 1).coerceAtLeast(0)), sel.end)
                        } else {
                            Selection(sel.start, off.coerceIn(sel.start + 1, len))
                        }
                    }
                }
                val backToShelf: () -> Unit = {
                    session.saveProgress()
                    container.library.refresh()
                    onGoShelf()
                }

                // 正文区域：这一层只负责「量出正文区尺寸」（分页按它算），不负责画。
                // 画在全屏层里 —— 分页模式下每一页自带页眉页脚，翻页时整屏一起走，
                // 而不是正文在页眉页脚之间挪动。
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(
                            start = marginDp,
                            end = marginDp,
                            top = topInset + headBarHeight + 10.dp,
                            bottom = bottomInset + 40.dp
                        )
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .onSizeChanged { sz ->
                                session.setPaginationContext(measurer, bodyStyle, sz.width, sz.height, density)
                            }
                    ) {
                        if (prefs.pageMode != PageMode.PAGE) {
                            ScrollingBody(
                                session = session,
                                style = bodyStyle,
                                blockStyler = blockStyler,
                                measurer = measurer,
                                onOpenLink = openLink,
                                paraGapDp = paraGapDp,
                                selection = selection,
                                ttsChapter = ttsChapterArg,
                                ttsStart = ttsStartArg,
                                ttsEnd = ttsEndArg,
                                onTap = onTapCb,
                                onSelect = onSelectCb,
                                onExtend = onExtendCb,
                                onHandleDrag = onHandleDragCb
                            )
                        }
                    }
                }

                // 全屏分页层：铺满整屏，每一页自带页眉页脚
                if (prefs.pageMode == PageMode.PAGE) {
                    PagedBody(
                        session = session,
                        curlState = curlState,
                        style = bodyStyle,
                        blockStyler = blockStyler,
                        measurer = measurer,
                        onOpenLink = openLink,
                        paraGapDp = paraGapDp,
                        selection = selection,
                        ttsChapter = ttsChapterArg,
                        ttsStart = ttsStartArg,
                        ttsEnd = ttsEndArg,
                        onTap = onTapCb,
                        onSelect = onSelectCb,
                        onExtend = onExtendCb,
                        onHandleDrag = onHandleDragCb,
                        onTag = { onTagGesture() },
                        barsVisible = !chrome,
                        onBack = backToShelf
                    )
                }

                // 页脚（分页模式下页脚同样画在每一页里）
                ReaderFooter(
                    session = session,
                    visible = !chrome && selection == null && prefs.pageMode != PageMode.PAGE,
                    tagged = session.currentPageTag() != null,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )

                // 打标/ 取消标签后的短暂提示
                tagHint?.let { hint ->
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = bottomInset + 56.dp)
                    ) {
                        Text(
                            text = hint,
                            style = InkType.Caption.copy(fontSize = 12.sp),
                            color = p.textPrimary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(p.surfaceHigh.copy(alpha = 0.96f))
                                .border(1.dp, p.divider, RoundedCornerShape(999.dp))
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }

                // 切章提示
                AnimatedVisibility(
                    visible = flashChapter && !chrome && prefs.chapterFlash,
                    enter = fadeIn(tween(240)),
                    exit = fadeOut(tween(520)),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    ChapterFlash(
                        index = session.chapterIndex,
                        title = session.currentChapter?.title ?: "",
                        total = session.chapters.size
                    )
                }

                // 顶栏
                AnimatedVisibility(
                    visible = chrome && selection == null,
                    enter = fadeIn(tween(160)),
                    exit = fadeOut(tween(160)),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    ReaderTopBar(
                        bookTitle = session.book?.title ?: "",
                        chapterTitle = session.currentChapter?.title ?: "",
                        onTitleClick = {
                            session.saveProgress()
                            container.library.refresh()
                            onOpenDetail(bookId)
                        },
                        onBack = {
                            // 顶栏这个「<」是「返回首页」（书架），不是回上一级
                            session.saveProgress()
                            container.library.refresh()
                            onGoShelf()
                        },
                        onToc = { showToc = true },
                        // 右上角三点：直接进章节管
                        onMore = {
                            session.saveProgress()
                            container.library.refresh()
                            onOpenDetail(bookId)
                        }
                    )
                }

                AnimatedVisibility(
                    visible = chrome && selection == null,
                    enter = fadeIn(tween(160)),
                    exit = fadeOut(tween(160)),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    ReaderBottomBar(
                        chapterIndex = session.chapterIndex,
                        chapterCount = session.chapters.size,
                        chapterTitleOf = { i -> session.chapters.getOrNull(i)?.title ?: "" },
                        onPrevChapter = {
                            // 是「跳章节」不是「翻一页」：直接到上一章开头
                            val prev = session.chapterIndex - 1
                            if (prev >= 0) session.loadChapter(prev, 0)
                        },
                        onNextChapter = {
                            val next = session.chapterIndex + 1
                            if (next <= session.chapters.lastIndex) session.loadChapter(next, 0)
                        },
                        onSeekChapter = { idx ->
                            // 跳到某一章：整章从头开始排（和目录里点章节同一条路径）
                            if (idx != session.chapterIndex) session.loadChapter(idx, 0)
                        },
                        onToc = { showToc = true },
                        onTheme = {
                            val order = com.inkreader.data.model.ReaderTheme.values()
                            val next = order[(order.indexOf(prefs.theme) + 1) % order.size]
                            session.updatePrefs({ it.copy(theme = next) })
                        },
                        onSettings = { showSettings = true },
                        onTts = { showTts = true },
                        onSearch = { showSearch = true }
                    )
                }

                // 选中操作
                AnimatedVisibility(
                    visible = selection != null,
                    enter = fadeIn(tween(140)),
                    exit = fadeOut(tween(140)),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    SelectionBar(
                        text = selection?.let {
                            session.chapterText.substring(
                                it.start.coerceIn(0, session.chapterText.length),
                                it.end.coerceIn(0, session.chapterText.length)
                            )
                        } ?: "",
                        tagged = selection?.let { session.isRangeTagged(it.start, it.end) } == true,
                        activeColor = selection?.let { session.highlightColorAt(it.start, it.end) },
                        onExpandSentence = {
                            val s = selection ?: return@SelectionBar
                            val range = sentenceRange(session.chapterText, s.start)
                            selection = Selection(range.first, range.last + 1)
                        },
                        onExpandParagraph = {
                            val s = selection ?: return@SelectionBar
                            val range = paragraphRange(session.chapterText, s.start)
                            selection = Selection(range.first, range.last + 1)
                        },
                        onTag = {
                            val s = selection ?: return@SelectionBar
                            session.toggleRangeTag(s.start, s.end)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (session.prefs.tagSound) TagSound.play(ctx)
                        },
                        onReadAloud = {
                            val s = selection ?: return@SelectionBar
                            selection = null
                            com.inkreader.tts.TtsService.startReading(
                                ctx, bookId, session.chapterIndex, s.start,
                                com.inkreader.tts.SleepMode.OFF
                            )
                        },
                        onHighlight = { colorIdx ->
                            selection?.let {
                                session.toggleHighlight(it.start, it.end, colorIdx)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            selection = null
                        },
                        onNote = { note ->
                            selection?.let { session.addHighlight(it.start, it.end, 0, note, asNote = true) }
                            selection = null
                        },
                        onCancel = { selection = null }
                    )
                }
            }
        }

        // ------- 浮层 -------
        if (showToc) {
            TocDrawer(
                session = session,
                onDismiss = { showToc = false },
                onJump = { idx, off ->
                    showToc = false
                    // 同一章内跳转不必重载章节，直接翻到那一
                    if (idx == session.chapterIndex && off > 0) session.jumpToOffset(off)
                    else session.gotoChapter(idx, off)
                }
            )
        }
        if (showSettings) {
            ReaderSettingsSheet(
                session = session,
                onDismiss = { showSettings = false },
                onOpenMarks = {
                    showSettings = false
                    onOpenMarks(bookId, session.book?.title ?: "")
                },
                onOpenDetail = {
                    showSettings = false
                    session.saveProgress()
                    container.library.refresh()
                    onOpenDetail(bookId)
                },
                onEditChapter = {
                    val ch = session.currentChapter ?: return@ReaderSettingsSheet
                    showSettings = false
                    onOpenEditor(ch.id, ch.title)
                }
            )
        }
        if (showSearch) {
            SearchSheet(
                session = session,
                onDismiss = { showSearch = false; session.clearSearch() },
                onJump = { hit ->
                    showSearch = false
                    if (hit.chapterIndex != session.chapterIndex) {
                        session.gotoChapter(hit.chapterIndex, hit.offset)
                    } else {
                        session.jumpToOffset(hit.offset)
                    }
                }
            )
        }
        if (showTts) {
            TtsSheet(
                session = session,
                state = tts,
                onDismiss = { showTts = false },
                onStart = { offset, sleep ->
                    showTts = false
                    com.inkreader.tts.TtsService.startReading(
                        ctx, bookId, session.chapterIndex, offset, sleep
                    )
                },
                onStop = { com.inkreader.tts.TtsService.send(ctx, com.inkreader.tts.TtsService.ACTION_STOP) },
                onRate = { r, pi -> com.inkreader.tts.TtsService.setVoice(ctx, r, pi) },
                onSleep = { com.inkreader.tts.TtsService.setSleep(ctx, it) }
            )
        }
    }
}

// ==================== 分页模式（含跨章衔接页） ====================

@Composable
private fun PagedBody(
    session: ReaderSession,
    curlState: CurlState,
    style: TextStyle,
    blockStyler: BlockStyler,
    measurer: TextMeasurer,
    onOpenLink: (String) -> Unit,
    paraGapDp: Dp,
    selection: Selection?,
    ttsChapter: Int,
    ttsStart: Int,
    ttsEnd: Int,
    onTap: (Float) -> Unit,
    onSelect: (Int, Int) -> Unit,
    onExtend: (Int) -> Unit,
    onHandleDrag: (Int, Int) -> Unit,
    onTag: () -> Unit,
    /** 页内自带的页眉页脚是否可见（工具栏展开时让位给完整顶栏/底栏） */
    barsVisible: Boolean,
    onBack: () -> Unit
) {
    val p = LocalInkPalette.current
    val entryCount = session.pagerEntries.size
    if (entryCount == 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = if (session.chapterText.isBlank()) stringResource(R.string.reader_empty_chapter) else stringResource(R.string.reader_paginating),
                style = InkType.Caption,
                color = p.textFaint
            )
        }
        return
    }

    // 「覆盖」「仿真」：不走 HorizontalPager，绘制顺序自己控（见 CurlPageView.kt）。
    // 平移 / 无动画的路径完全不受影响。
    if (session.prefs.pageAnimation.isLayeredFlip) {
        CurlPagedBody(
            session = session,
            curlState = curlState,
            style = style,
            blockStyler = blockStyler,
            measurer = measurer,
            onOpenLink = onOpenLink,
            paraGapDp = paraGapDp,
            selection = selection,
            ttsChapter = ttsChapter,
            ttsStart = ttsStart,
            ttsEnd = ttsEnd,
            onTap = onTap,
            onSelect = onSelect,
            onExtend = onExtend,
            onHandleDrag = onHandleDrag,
            onTag = onTag,
            barsVisible = barsVisible,
            onBack = onBack
        )
        return
    }

    // 切章时重建 pager：initialPage 直接落在当前页槽上，
    // 而该槽正是用户刚刚滑进来的那一页，所以视觉上完全接得住。
    key(session.chapterIndex) {
        val pagerState = rememberPagerState(
            initialPage = session.currentEntryIndex,
            pageCount = { session.pagerEntries.size }
        )

        // 滑到本章页 = 普通翻页；滑到邻章衔接页 = 直接进下一章 / 回上一章末尾
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }.distinctUntilChanged().collect {
                session.onPagerSettled(it)
            }
        }

        // 排版参数变化、跳转、章节重排后把 pager 对齐到会话认定的位置
        LaunchedEffect(session.currentEntryIndex, session.pagerEntries.size) {
            val target = session.currentEntryIndex.coerceIn(0, (session.pagerEntries.size - 1).coerceAtLeast(0))
            if (pagerState.currentPage != target) pagerState.scrollToPage(target)
        }

        val anim = session.prefs.pageAnimation

        // 对齐 pager 与会话认定的位置：
        // · 用户主动翻页（点击左右边缘 / 音量键）→ 带动画滑过去；
        // · 跳转、改排版重排 → 直接对齐，不做动画。
        var animatedSerial by remember { mutableIntStateOf(session.turnSerial) }
        LaunchedEffect(session.currentEntryIndex, session.pagerEntries.size, session.turnSerial) {
            val target = session.currentEntryIndex.coerceIn(0, (session.pagerEntries.size - 1).coerceAtLeast(0))
            if (pagerState.currentPage != target) {
                val userTurn = session.turnSerial != animatedSerial
                animatedSerial = session.turnSerial
                if (userTurn && anim != PageAnimation.NONE) {
                    pagerState.animateScrollToPage(target)
                } else {
                    pagerState.scrollToPage(target)
                }
            } else {
                animatedSerial = session.turnSerial
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 0.dp,
            beyondViewportPageCount = 1,
            // 无动画 = 手指松开立刻落到目标页，不做缓动
            flingBehavior = if (anim == PageAnimation.NONE) {
                androidx.compose.foundation.pager.PagerDefaults.flingBehavior(
                    state = pagerState,
                    snapAnimationSpec = androidx.compose.animation.core.snap()
                )
            } else {
                androidx.compose.foundation.pager.PagerDefaults.flingBehavior(state = pagerState)
            }
        ) { i ->
            val entry = session.pagerEntries.getOrNull(i) ?: return@HorizontalPager
            // 只有「会话认定的当前页」才接受下拉打标签，避免滑动过程中误判到邻页
            val tagEnabled = entry.isCurrentChapter && entry.localPageIndex == session.pageIndex
            // 整屏一页：页眉页脚画在页里，滑动时跟着一起走
            PageSheet(
                session = session,
                entry = entry,
                style = style,
                blockStyler = blockStyler,
                measurer = measurer,
                onOpenLink = onOpenLink,
                paraGapDp = paraGapDp,
                selection = selection,
                ttsChapter = ttsChapter,
                ttsStart = ttsStart,
                ttsEnd = ttsEnd,
                onTap = onTap,
                onSelect = onSelect,
                onExtend = onExtend,
                onHandleDrag = onHandleDrag,
                onTag = onTag,
                headerVisible = barsVisible,
                footerVisible = barsVisible,
                onBack = onBack,
                withPull = true,
                tagEnabled = tagEnabled
            )
        }
    }
}

/**
 * 一整屏「纸页」：不透明实底 + 页内自带的页眉页脚 + 正文，可选下拉打标签。
 *
 * 之所以把页眉页脚也画进来：翻页（平移 / 仿真）时整屏一起动，页眉页脚跟着页走，
 * 视觉上才是「翻一页纸」；正文区尺寸仍与分页时量的一致（页眉页脚占的位置用等高占位补上），
 * 所以文字位置分毫不动。
 *
 * 实底必须有 —— 翻页过程中两页会同时可见，没有实底就会互相透字。
 */
@Composable
private fun PageSheet(
    session: ReaderSession,
    entry: PagerEntry,
    style: TextStyle,
    blockStyler: BlockStyler,
    measurer: TextMeasurer,
    onOpenLink: (String) -> Unit,
    paraGapDp: Dp,
    selection: Selection?,
    ttsChapter: Int,
    ttsStart: Int,
    ttsEnd: Int,
    onTap: (Float) -> Unit,
    onSelect: (Int, Int) -> Unit,
    onExtend: (Int) -> Unit,
    onHandleDrag: (Int, Int) -> Unit,
    onTag: () -> Unit,
    headerVisible: Boolean,
    footerVisible: Boolean,
    onBack: () -> Unit,
    withPull: Boolean,
    tagEnabled: Boolean
) {
    val p = LocalInkPalette.current
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val marginDp = session.prefs.margin.dp
    val isCurrent = entry.isCurrentChapter && entry.localPageIndex == session.pageIndex
    val tagged = isCurrent && session.currentPageTag() != null

    Box(Modifier.fillMaxSize().background(p.background)) {
        Column(Modifier.fillMaxSize()) {
            if (headerVisible) {
                ReadingHeaderBar(
                    // 章首页沿用书名（正文区已经有大标题了），其余页显示章节名
                    title = if (entry.localPageIndex == 0) {
                        session.book?.title ?: entry.chapterTitle
                    } else {
                        entry.chapterTitle
                    },
                    onBack = onBack,
                    // 邻页的页眉只是画面的一部分，不接受点击，免得翻页时误触「返回首页」
                    backEnabled = isCurrent
                )
            } else {
                Spacer(Modifier.height(topInset + ReaderHeadBarHeight))
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = marginDp)
            ) {
                val content: @Composable () -> Unit = {
                    PageContent(
                        session = session,
                        entry = entry,
                        style = style,
                        blockStyler = blockStyler,
                        measurer = measurer,
                        onOpenLink = onOpenLink,
                        paraGapDp = paraGapDp,
                        selection = if (entry.isCurrentChapter) selection else null,
                        ttsStart = if (entry.chapterIndex == ttsChapter) ttsStart else -1,
                        ttsEnd = if (entry.chapterIndex == ttsChapter) ttsEnd else -1,
                        onTap = onTap,
                        onSelect = onSelect,
                        onExtend = onExtend,
                        onHandleDrag = onHandleDrag
                    )
                }
                if (withPull) {
                    PullablePage(
                        enabled = tagEnabled && isCurrent,
                        alreadyTagged = tagEnabled && tagged,
                        onTag = onTag
                    ) { content() }
                } else {
                    content()
                }
            }
            // 页脚区域占位：与分页时正文区的下边距等量，保证正文高度不变
            Spacer(Modifier.height(bottomInset + 40.dp))
        }
        if (footerVisible) {
            PageFooterBar(
                pageNo = entryPageNo(session, entry),
                total = session.bookTotalPages,
                fallbackProgress = session.bookProgress(),
                tagged = tagged,
                marginDp = marginDp,
                accent = p.accent,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/** 某个页槽（可能是邻章衔接页）对应的全书页码；统计还没完成时返回 -1 */
private fun entryPageNo(session: ReaderSession, entry: PagerEntry): Int {
    if (entry.isCurrentChapter) return session.bookPageNo
    val pre = session.pagesBefore ?: return -1
    val base = pre.getOrNull(entry.chapterIndex) ?: return -1
    return base + entry.localPageIndex + 1
}

/**
 * 「仿真」折页的分页正文。
 *
 * 与 [PagedBody] 里的 pager 路径并列，但完全不依赖 `HorizontalPager`：
 * [CurlHost] 负责「露出页 + 承载页」两层，横向拖动 / 点击边缘 / 音量键都走 [CurlState]；
 * 提交时调用 [ReaderSession.onPagerSettled]（跨章语义与手势滑动完全一致），
 * 拖动期间一个分页状态都不碰，因此不可能触发重排。
 */
@Composable
private fun CurlPagedBody(
    session: ReaderSession,
    curlState: CurlState,
    style: TextStyle,
    blockStyler: BlockStyler,
    measurer: TextMeasurer,
    onOpenLink: (String) -> Unit,
    paraGapDp: Dp,
    selection: Selection?,
    ttsChapter: Int,
    ttsStart: Int,
    ttsEnd: Int,
    onTap: (Float) -> Unit,
    onSelect: (Int, Int) -> Unit,
    onExtend: (Int) -> Unit,
    onHandleDrag: (Int, Int) -> Unit,
    onTag: () -> Unit,
    barsVisible: Boolean,
    onBack: () -> Unit
) {
    val p = LocalInkPalette.current
    val entries = session.pagerEntries
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = if (session.chapterText.isBlank()) stringResource(R.string.reader_empty_chapter) else stringResource(R.string.reader_paginating),
                style = InkType.Caption,
                color = p.textFaint
            )
        }
        return
    }

    val curIdx = session.currentEntryIndex
    val curEntry = entries.getOrNull(curIdx)
    val entryKey = session.chapterIndex to curIdx

    // 组合期只读数据：这两个回调是给绘制/手势层用的副作用，放进 SideEffect 提交，
    // 避免在组合阶段直接写外部状态（重组时可能读到半更新的值）
    SideEffect {
        curlState.canTurn = { d -> entries.getOrNull(curIdx + d)?.page != null }
        curlState.onCommit = { d ->
            curlState.pendingTarget = entries.getOrNull(curIdx + d)
            session.onPagerSettled(curIdx + d)
        }
    }

    DisposableEffect(curlState) { onDispose { curlState.cancel() } }

    // 提交后等会话真正切到目标页再复位：跨章是异步换页的，
    // 这段时间先把目标页当承载页画出来，避免闪回旧页。
    val pending = curlState.pendingTarget
    val caughtUp = pending != null && curEntry != null &&
        curEntry.chapterIndex == pending.chapterIndex &&
        (curEntry.page?.startOffset ?: -1) == (pending.page?.startOffset ?: -1)
    LaunchedEffect(entryKey, pending) {
        if (pending == null) return@LaunchedEffect
        // 还没追上的通常只有跨章那一帧（下一页数据异步换上去）。
        // 900ms 兜底：万一一直没追上也必须收尾，否则 busy 卡死，
        // 之后所有手势都被吞掉 —— 表现就是「翻一页之后再也翻不动了」。
        if (!caughtUp) delay(900L)
        curlState.finishCommit()
    }

    val waitingTarget = pending != null && !caughtUp
    // 方向与进度都以「取值函数」交给 CurlHost，在绘制阶段才读：
    // 拖动时进度每帧都在变，组合期读取会每帧重组整棵子树（含两整页正文），
    // 那正是之前「翻起来卡卡的」的根因。
    val dirOf: () -> Int = { if (waitingTarget || caughtUp) 0 else curlState.dir }
    val progressOf: () -> Float = { if (waitingTarget || caughtUp) 0f else curlState.progress }
    val carrier: PagerEntry? = if (waitingTarget) pending else curEntry
    if (carrier == null) return

    // 上一页 / 下一页都常驻组合（起拖那一帧不会才首次组合整页正文），
    // 但只有当前翻动方向对应的那一页会真正绘制，空闲时零绘制开销。
    val prevEntry = entries.getOrNull(curIdx - 1)
    val nextEntry = entries.getOrNull(curIdx + 1)

    val dragModifier = Modifier.foldDragGesture(
        state = curlState,
        widthPx = { session.pageWidthPx.toFloat() },
        enabled = selection == null
    )

    CurlHost(
        direction = dirOf,
        progress = progressOf,
        cover = session.prefs.pageAnimation == PageAnimation.COVER,
        colors = remember(p.background) { foldColors(p.background) },
        modifier = dragModifier,
        reveal = {
            // 承载页往左走（翻下一页）时露出的是下一页；往右走露出上一页
            Box(
                Modifier.fillMaxSize().drawWithContent {
                    if (dirOf() < 0) drawContent()
                }
            ) {
                val rv = prevEntry
                if (rv != null) {
                    PageSheet(
                        session = session, entry = rv, style = style, blockStyler = blockStyler,
                        measurer = measurer, onOpenLink = onOpenLink, paraGapDp = paraGapDp,
                        selection = selection, ttsChapter = ttsChapter, ttsStart = ttsStart,
                        ttsEnd = ttsEnd, onTap = onTap, onSelect = onSelect, onExtend = onExtend,
                        onHandleDrag = onHandleDrag, onTag = onTag, withPull = false, tagEnabled = false,
                        headerVisible = barsVisible, footerVisible = barsVisible, onBack = onBack
                    )
                }
            }
            Box(
                Modifier.fillMaxSize().drawWithContent {
                    if (dirOf() > 0) drawContent()
                }
            ) {
                val rv = nextEntry
                if (rv != null) {
                    PageSheet(
                        session = session, entry = rv, style = style, blockStyler = blockStyler,
                        measurer = measurer, onOpenLink = onOpenLink, paraGapDp = paraGapDp,
                        selection = selection, ttsChapter = ttsChapter, ttsStart = ttsStart,
                        ttsEnd = ttsEnd, onTap = onTap, onSelect = onSelect, onExtend = onExtend,
                        onHandleDrag = onHandleDrag, onTag = onTag, withPull = false, tagEnabled = false,
                        headerVisible = barsVisible, footerVisible = barsVisible, onBack = onBack
                    )
                }
            }
        },
        content = {
            PageSheet(
                session = session, entry = carrier, style = style, blockStyler = blockStyler,
                measurer = measurer, onOpenLink = onOpenLink, paraGapDp = paraGapDp,
                selection = selection, ttsChapter = ttsChapter, ttsStart = ttsStart,
                ttsEnd = ttsEnd, onTap = onTap, onSelect = onSelect, onExtend = onExtend,
                onHandleDrag = onHandleDrag, onTag = onTag, withPull = true,
                tagEnabled = !curlState.busy,
                headerVisible = barsVisible, footerVisible = barsVisible && selection == null,
                onBack = onBack
            )
        }
    )
}

/**
 * 「把整页往下拉」的那一层。
 *
 * 手指往下拖时整页内容跟着下移（下层被裁掉一部分），拉开的空隙里从上方滑出标签提示；
 * 松手时如果拉过阈值就执行 onTag（打标签或取消），然后回弹。这样动作是「看得见」的，
 * 而不是一个摸不着的手势。
 */
@Composable
private fun PullablePage(
    enabled: Boolean,
    alreadyTagged: Boolean,
    onTag: () -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val thresholdPx = with(density) { 72.dp.toPx() }
    val maxPull = thresholdPx * 1.6f

    var pull by remember { mutableFloatStateOf(0f) }
    val armed by remember { derivedStateOf { pull >= thresholdPx } }
    // 只有真的拉开了才把提示放进组合里，免得看不见的东西留在无障碍树上
    val pulled by remember { derivedStateOf { pull > 0.5f } }

    suspend fun springBack() {
        animate(
            initialValue = pull,
            targetValue = 0f,
            animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
        ) { value, _ -> pull = value }
    }

    Box(Modifier.fillMaxSize().clipToBounds()) {
        // 拉开的空隙里浮出来的标签提示：始终待在空隙正中，从上方滑
        if (pulled) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        val f = (pull / thresholdPx).coerceIn(0f, 1f)
                        alpha = f
                        translationY = pull / 2f - size.height / 2f
                        val s = 0.78f + 0.22f * f
                        scaleX = s
                        scaleY = s
                    }
            ) {
                TagPullBadge(armed = armed, alreadyTagged = alreadyTagged)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = pull }
                .then(
                    if (enabled) {
                        Modifier.pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    scope.launch {
                                        if (pull >= thresholdPx) onTag()
                                        springBack()
                                    }
                                },
                                onDragCancel = { scope.launch { springBack() } }
                            ) { change, dragAmount ->
                                change.consume()
                                pull = (pull + dragAmount).coerceIn(0f, maxPull)
                            }
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            content()
        }
    }
}

/** 下拉时浮出来的标签提*/
@Composable
private fun TagPullBadge(armed: Boolean, alreadyTagged: Boolean) {
    val p = LocalInkPalette.current
    val label = when {
        armed && alreadyTagged -> stringResource(R.string.reader_tag_release_cancel)
        armed -> stringResource(R.string.reader_tag_release_add)
        alreadyTagged -> stringResource(R.string.reader_tag_keep_pull_cancel)
        else -> stringResource(R.string.reader_tag_keep_pull_add)
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (armed) p.accent else p.surfaceHigh)
            .border(1.dp, if (armed) p.accent else p.divider, RoundedCornerShape(999.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        InkIcon(
            res = if (alreadyTagged) R.drawable.ic_bookmark else R.drawable.ic_bookmark_outline,
            contentDescription = null,
            tint = if (armed) Color.White else p.accent,
            size = 16
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = InkType.Caption.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
            color = if (armed) Color.White else p.textSecondary
        )
    }
}

// ==================== 章首页大标题 ====================

/**
 * 每章第一页标题块的基准高度（dp，单行标题时）。
 *
 * 分页时必须按同一个值给首页少排这么多，否则大标题会压住正文 ——
 * 所以下面这个函数同时被 [ReaderSession] 的分页和 [ChapterHeading] 的渲染调用。
 */
const val CHAPTER_HEADING_DP = 68f

/** 标题块实际高度：标题长到要折两行（约 15 个字以上）就多给一行的高度 */
fun chapterHeadingHeightDp(title: String): Float =
    if (title.length > 15) CHAPTER_HEADING_DP + 30f else CHAPTER_HEADING_DP

/**
 * 章首页的大标题（番茄那种）：小字「第 N 章」+ 大字章节名，**贴着页眉**摆放。
 * 章节名里已经带了章号（中 / 英 / 日任何写法），或本身就是序章 / 番外 / 幕間 这类
 * 无编号章节时，就不显示那行小字 —— 免得把序章标成「第 1 章」。
 */
@Composable
private fun ChapterHeading(number: Int, title: String, modifier: Modifier = Modifier) {
    val p = LocalInkPalette.current
    val showOrdinal = remember(title) { ChapterSplitter.showsOrdinalLabel(title) }
    Column(
        modifier
            .fillMaxWidth()
            .height(chapterHeadingHeightDp(title).dp)
            .padding(top = 2.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.Top
    ) {
        if (showOrdinal) {
            Text(
                text = stringResource(R.string.label_chapter_n, number),
                fontFamily = FontFamily.SansSerif,
                fontSize = 12.sp,
                color = p.accent,
                maxLines = 1
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            text = title,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 21.sp,
            lineHeight = 29.sp,
            color = p.textPrimary,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

// ==================== 常驻页眉 ====================

/**
 * 阅读页顶部常驻的一条窄页眉：`<` 返回首页 + 「第几章 章节名」。
 * 只在工具栏收起时显示；工具栏展开时它让位给完整顶栏（否则信息重复）。
 */
@Composable
private fun ReadingHeaderBar(title: String, onBack: () -> Unit, backEnabled: Boolean = true) {
    val p = LocalInkPalette.current
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Row(
        Modifier
            .fillMaxWidth()
            .background(p.background)
            .padding(top = topInset)
            .height(ReaderHeadBarHeight)
            .padding(start = 2.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(ReaderHeadBarHeight)
                .clip(RoundedCornerShape(9.dp))
                .then(
                    // 邻页（翻页时露出来的那一页）的页眉只是画面的一部分，不接受点击，
                    // 否则折页时手指落在上面会误触「返回首页」。
                    if (backEnabled) Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = { onBack() })
                    } else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            InkIcon(R.drawable.ic_arrow_back, stringResource(R.string.action_back_home), tint = p.textSecondary, size = 17)
        }
        Spacer(Modifier.width(2.dp))
        Text(
            text = title,
            fontFamily = FontFamily.SansSerif,
            fontSize = 12.sp,
            color = p.textFaint,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PageContent(
    session: ReaderSession,
    entry: PagerEntry,
    style: TextStyle,
    blockStyler: BlockStyler,
    measurer: TextMeasurer,
    onOpenLink: (String) -> Unit,
    paraGapDp: Dp,
    selection: Selection?,
    ttsStart: Int,
    ttsEnd: Int,
    onTap: (Float) -> Unit,
    onSelect: (Int, Int) -> Unit,
    onExtend: (Int) -> Unit,
    onHandleDrag: (Int, Int) -> Unit
) {
    val p = LocalInkPalette.current
    val density = LocalDensity.current
    val page = entry.page
    if (page == null) {
        // 邻章衔接页还没排好（极少见，通常已经预排完）
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.reader_joining_next_chapter), style = InkType.Caption, color = p.textFaint)
        }
        return
    }
    val marks = remember(session.marks, entry.chapterIndex) {
        session.marks.filter { it.chapterIndex == entry.chapterIndex }
    }
    Box(Modifier.fillMaxSize()) {
        // 背景点击层：正文以外的空白区域也能唤出工具栏
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { pos -> onTap(pos.x) }) }
        )
        Column(Modifier.fillMaxSize()) {
            // 章首页：顶部画大标题（分页时已经给它留好了高度）
            if (page.startOffset == 0) {
                ChapterHeading(
                    number = entry.chapterIndex + 1,
                    title = entry.chapterTitle.ifBlank { session.chapters.getOrNull(entry.chapterIndex)?.title ?: "" }
                )
            }
            page.items.forEachIndexed { idx, placed ->
                val block = entry.blocks.getOrNull(placed.blockIndex)
                ParagraphText(
                    text = entry.text,
                    placed = placed,
                    style = style,
                    block = block,
                    styler = blockStyler,
                    measurer = measurer,
                    marks = marks,
                    selection = selection,
                    ttsStart = ttsStart,
                    ttsEnd = ttsEnd,
                    fontSize = session.prefs.fontSize,
                    firstLineIndent = session.prefs.firstLineIndent,
                    onTap = onTap,
                    onSelect = onSelect,
                    onExtend = onExtend,
                    onHandleDrag = onHandleDrag,
                    onOpenLink = onOpenLink
                )
                // 段间距按「前一块想留的」和「后一块想要的」取大的；同一段被切开的两截之间不留
                val nextPlaced = page.items.getOrNull(idx + 1)
                if (nextPlaced != null && !nextPlaced.continuation) {
                    val nextBlock = entry.blocks.getOrNull(nextPlaced.blockIndex)
                    val gapPx = if (block != null && nextBlock != null) {
                        blockStyler.gapBetween(block, nextBlock)
                    } else {
                        with(density) { paraGapDp.toPx() }
                    }
                    if (gapPx > 0f) Spacer(Modifier.height(with(density) { gapPx.toDp() }))
                }
            }
        }
    }
}

// ==================== 滚动模式（三章连续） ====================

@Composable
private fun ScrollingBody(
    session: ReaderSession,
    style: TextStyle,
    blockStyler: BlockStyler,
    measurer: TextMeasurer,
    onOpenLink: (String) -> Unit,
    paraGapDp: Dp,
    selection: Selection?,
    ttsChapter: Int,
    ttsStart: Int,
    ttsEnd: Int,
    onTap: (Float) -> Unit,
    onSelect: (Int, Int) -> Unit,
    onExtend: (Int) -> Unit,
    onHandleDrag: (Int, Int) -> Unit
) {
    val p = LocalInkPalette.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val flow = session.flowBlocks

    if (flow.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.reader_empty_chapter), style = InkType.Caption, color = p.textFaint)
        }
        return
    }

    // 记录当前阅读位置（只认本章的块，邻章区域不写进度
    LaunchedEffect(listState, session.chapterIndex) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { idx ->
                val item = session.flowBlocks.getOrNull(idx) ?: return@collect
                if (item.chapterIndex == session.chapterIndex) {
                    session.updateScrollOffset(item.block.start)
                    session.saveProgress()
                }
            }
    }

    // 滚到底自动接下一章；往回滚过头则回到上一章末
    LaunchedEffect(listState, session.chapterIndex, flow.size) {
        snapshotFlow {
            val info = listState.layoutInfo
            Triple(
                info.visibleItemsInfo.firstOrNull()?.index ?: -1,
                info.visibleItemsInfo.lastOrNull()?.index ?: -1,
                listState.isScrollInProgress
            )
        }.distinctUntilChanged().collect { (first, last, scrolling) ->
            if (scrolling || first < 0 || last < 0) return@collect
            val arr = session.flowBlocks
            if (first >= session.flowCurrentEnd && first < arr.size) {
                val item = arr[first]
                if (item.chapterIndex != session.chapterIndex) {
                    session.onFlowCrossed(item.chapterIndex, item.block.start)
                }
            } else if (last < session.flowCurrentStart) {
                val item = arr.getOrNull(first) ?: return@collect
                if (item.chapterIndex != session.chapterIndex) {
                    session.onFlowCrossed(item.chapterIndex, item.block.start)
                }
            }
        }
    }

    // 跳到指定偏移（跨章时下标要加上上一章的块数
    LaunchedEffect(session.jumpRequest, session.chapterIndex, flow.size) {
        val req = session.jumpRequest ?: return@LaunchedEffect
        val local = session.blocks.indexOfFirst { req >= it.start && req < it.start + it.text.length }
            .let { if (it < 0) session.blocks.indexOfLast { b -> b.start <= req } else it }
        if (local >= 0) listState.scrollToItem(session.flowCurrentStart + local)
        session.consumeJumpRequest()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(
            flow,
            key = { _, item -> (item.chapterIndex.toLong() shl 32) or item.block.start.toLong() }
        ) { index, item ->
            val marks = remember(session.marks, item.chapterIndex) {
                session.marks.filter { it.chapterIndex == item.chapterIndex }
            }
            // 段间距按块类型算：标题前后更空、列表项之间更紧（和分页模式同一套规则）
            val prevItem = if (index > 0) flow[index - 1] else null
            val gapPx = if (prevItem != null && prevItem.chapterIndex == item.chapterIndex) {
                blockStyler.gapBetween(prevItem.block, item.block)
            } else {
                with(density) { paraGapDp.toPx() }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = with(density) { gapPx.toDp() })
            ) {
                if (item.isChapterHead) {
                    ChapterHead(title = item.chapterTitle)
                }
                ParagraphText(
                    text = item.text,
                    placed = Placed(
                        blockIndex = item.block.index,
                        start = item.block.start,
                        end = item.block.start + item.block.text.length,
                        continuation = false
                    ),
                    style = style,
                    block = item.block,
                    styler = blockStyler,
                    measurer = measurer,
                    marks = marks,
                    selection = if (item.chapterIndex == session.chapterIndex) selection else null,
                    ttsStart = if (item.chapterIndex == ttsChapter) ttsStart else -1,
                    ttsEnd = if (item.chapterIndex == ttsChapter) ttsEnd else -1,
                    fontSize = session.prefs.fontSize,
                    firstLineIndent = session.prefs.firstLineIndent,
                    onTap = onTap,
                    onSelect = if (item.chapterIndex == session.chapterIndex) onSelect else { _, _ -> },
                    onExtend = if (item.chapterIndex == session.chapterIndex) onExtend else { _ -> },
                    onHandleDrag = if (item.chapterIndex == session.chapterIndex) onHandleDrag else { _, _ -> },
                    onOpenLink = onOpenLink
                )
            }
        }
    }
}

/** 滚动模式下章首的标题条：让「下一章滑进来」这件事看得*/
@Composable
private fun ChapterHead(title: String) {
    val p = LocalInkPalette.current
    Column(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 12.dp)) {
        Text(
            text = title,
            style = InkType.Title,
            color = p.textPrimary,
            maxLines = 2
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .width(30.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(p.accent)
        )
    }
}

// ==================== 段落渲染 ====================

@Composable
private fun ParagraphText(
    text: String,
    placed: Placed,
    style: TextStyle,
    block: Block?,
    styler: BlockStyler?,
    measurer: TextMeasurer,
    marks: List<Mark>,
    selection: Selection?,
    ttsStart: Int,
    ttsEnd: Int,
    fontSize: Float,
    firstLineIndent: Boolean,
    onTap: (Float) -> Unit,
    onSelect: (Int, Int) -> Unit,
    onExtend: (Int) -> Unit,
    onHandleDrag: (Int, Int) -> Unit,
    onOpenLink: (String) -> Unit
) {
    val start = placed.start.coerceIn(0, text.length)
    val end = placed.end.coerceIn(start, text.length)
    val sub = text.substring(start, end)
    if (sub.isEmpty()) return

    var layout by remember(placed.start, placed.end) { mutableStateOf<TextLayoutResult?>(null) }

    val rich = block != null && styler != null
    // 有块样式就按块来（标题更大、代码块等宽、列表/引用缩进），否则沿用整章统一样式。
    // 这套规则和分页器用的是同一份，所以切到第二页的那截仍然是同一个块的样式。
    val blockStyle = if (rich) styler!!.styleOf(block!!) else style
    val indent = if (rich) {
        styler!!.indentForSlice(block!!, placed.continuation)
    } else if (!placed.continuation && firstLineIndent) {
        TextIndent(firstLine = (fontSize * 2).sp)
    } else null

    val palette = LocalInkPalette.current
    val codeBg = palette.textPrimary.copy(alpha = 0.06f)
    val annotated = remember(
        sub, marks, selection, ttsStart, ttsEnd, palette.accent, palette.selection, block, codeBg
    ) {
        buildBodyAnnotated(
            sub, start, marks, selection, ttsStart, ttsEnd,
            ttsColor = palette.accent,
            selectionColor = palette.selection,
            block = block,
            linkColor = palette.accent,
            codeBg = codeBg
        )
    }

    // 这一段里能点的链接（转成本段局部偏移 + 目标地址）
    val linkRanges = remember(block, start, sub.length) {
        block?.links?.mapNotNull { lk ->
            val s = (lk.start - start).coerceIn(0, sub.length)
            val e = (lk.end - start).coerceIn(0, sub.length)
            if (e > s) Triple(s, e, lk.url) else null
        }.orEmpty()
    }

    // 段落标签：这一段正文底下画一条蓝线，表示已经标记过（不动字色
    val underlineRanges = remember(marks, start, end) {
        marks.filter { it.type == MarkType.MARK }.mapNotNull { m ->
            val s = (m.startOffset - start).coerceIn(0, sub.length)
            val e = (m.endOffset - start).coerceIn(0, sub.length)
            if (e > s) s to e else null
        }
    }
    val underlineColor = palette.accent

    // 选区在本段内的那一段（选区可能跨段，本段只画自己这截）
    val selLocal = selection?.let {
        val s = (it.start - start).coerceIn(0, sub.length)
        val e = (it.end - start).coerceIn(0, sub.length)
        if (e > s) s to e else null
    }
    val selColor = palette.accent

    /** 手指是不是按在左右两端的拖动手柄上：0=左手柄，1=右手柄，-1=不是 */
    val density = LocalDensity.current
    // 选区放在 rememberUpdatedState 里而不是当成手势的 key
    // 否则拖动每改一帧选区，pointerInput 就重建一次，手势被反复取消，拖起来「卡卡的」。
    val selNow = rememberUpdatedState(selLocal)
    fun handleAt(pos: Offset): Int {
        val lr = layout ?: return -1
        val sel = selNow.value ?: return -1
        val touch = with(density) { 28.dp.toPx() }
        val pad = with(density) { 22.dp.toPx() }
        fun hit(offset: Int): Boolean {
            val line = lr.getLineForOffset(offset)
            val x = lr.getHorizontalPosition(offset, usePrimaryDirection = true)
            val top = lr.getLineTop(line) - pad
            val bottom = lr.getLineBottom(line) + pad
            return pos.y in top..bottom && kotlin.math.abs(pos.x - x) <= touch
        }
        return when {
            hit(sel.first) -> 0
            hit(sel.second) -> 1
            else -> -1
        }
    }

    BasicText(
        text = annotated,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val lr = layout
                // 块级装饰画在最底下：代码块底色 / 引用竖线 / 列表项目符号 / 分隔线
                if (lr != null && rich) {
                    drawBlockDecorations(block!!, styler!!, measurer, palette, !placed.continuation)
                }
                if (underlineRanges.isNotEmpty()) {
                    drawTagUnderlines(lr, underlineRanges, underlineColor)
                }
                // 选区左右两端各画一根可拖的竖条（带下面一个小圆点，好抓）
                if (lr != null && selLocal != null) {
                    drawSelectionHandles(lr, selLocal.first, selLocal.second, selColor)
                }
            }
            .pointerInput(placed.start, placed.end, layout) {
                // 按在手柄上就是「拖这一头扩收缩」；按在别处不消费，
                // 手势照旧留给翻页，不会误拦左右滑
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val which = handleAt(down.position)
                    val lr = layout
                    if (which >= 0 && lr != null) {
                        down.consume()
                        drag(down.id) { change ->
                            change.consume()
                            onHandleDrag(which, start + lr.getOffsetForPosition(change.position))
                        }
                    }
                }
            }
            .pointerInput(placed.start, placed.end, layout) {
                // 长按选词 + 继续拖动扩选，全在这一个手势循环里完成
                // 长按超时前抬起 = 点击；长按成功 = 建选区并跟随手指改边界。
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val longPress = awaitLongPressOrCancellation(down.id)
                    val lr = layout
                    if (longPress != null && lr != null) {
                        val off = lr.getOffsetForPosition(longPress.position)
                        val word = lr.getWordBoundary(off)
                        val ws = start + word.start
                        val we = start + word.end
                        onSelect(ws, if (we > ws) we else ws + 1)
                        drag(longPress.id) { change ->
                            change.consume()
                            onExtend(start + lr.getOffsetForPosition(change.position))
                        }
                    } else if (currentEvent.changes.all { !it.pressed }) {
                        // 手指已经抬起且没长按 → 先看落点是不是链接；
                        // 不是链接才当点击（左右边缘翻页 / 中间唤出工具栏 / 取消选区）
                        val pos = currentEvent.changes.firstOrNull()?.position
                        var opened = false
                        if (pos != null && lr != null && linkRanges.isNotEmpty()) {
                            val off = lr.getOffsetForPosition(pos)
                            val hit = linkRanges.firstOrNull { off >= it.first && off < it.second }
                            if (hit != null) {
                                onOpenLink(hit.third)
                                opened = true
                            }
                        }
                        if (!opened) onTap(pos?.x ?: 0f)
                    }
                }
            },
        style = if (indent != null) blockStyle.copy(textIndent = indent) else blockStyle,
        onTextLayout = { layout = it },
        softWrap = true
    )
}

/**
 * 块级装饰：画在正文底下、不参与排版，所以不会顶动文字，也就不会破坏分页。
 * 列表项目符号 / 引用竖线画在缩进留出的空档里，代码块底色铺满这一截，分隔线居中一条横线。
 */
private fun DrawScope.drawBlockDecorations(
    block: Block,
    styler: BlockStyler,
    measurer: TextMeasurer,
    palette: InkPalette,
    firstSlice: Boolean
) {
    when (block.kind) {
        RichKind.CODE -> {
            drawRoundRect(
                color = palette.textPrimary.copy(alpha = 0.06f),
                topLeft = Offset(0f, 0f),
                size = Size(size.width, size.height),
                cornerRadius = CornerRadius(6.dp.toPx())
            )
        }

        RichKind.QUOTE -> {
            val w = 2.5.dp.toPx()
            drawRoundRect(
                color = palette.divider,
                topLeft = Offset(2.dp.toPx(), 0f),
                size = Size(w, size.height),
                cornerRadius = CornerRadius(w / 2f)
            )
        }

        RichKind.RULE -> {
            val y = size.height / 2f
            drawLine(
                color = palette.divider,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        RichKind.TABLE_HEAD -> {
            val y = size.height - 1.dp.toPx()
            drawLine(
                color = palette.divider,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        RichKind.LIST -> {
            // 项目符号只画在整段的第一截，跨页后的续排不重复画
            if (!firstSlice || block.marker.isEmpty()) return
            val gutterPx = styler.gutterDp(block).dp.toPx()
            val markerStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = (styler.baseFontSizeSp * 0.95f).sp,
                color = palette.textSecondary
            )
            val laid = measurer.measure(block.marker, markerStyle)
            val x = (gutterPx - laid.size.width - 4.dp.toPx()).coerceAtLeast(0f)
            drawText(laid, topLeft = Offset(x, 0f))
        }

        else -> Unit
    }
}

// ==================== 正文着====================

/**
 * 给「段落标签」画下划线：按行逐段画，所以跨行的选区也只在有字的地方有线。
 * 直接画在文字下方而不是用 TextDecoration，是为了保住字色不变、只加一条蓝线。
 */
/**
 * 选区两端的拖动手柄：一根圆头竖条 + 底端一个小圆点，手指按在这附近就能拖。
 * 直接画在文字层上，不参与排版，所以不会顶动正文。
 */
private fun DrawScope.drawSelectionHandles(
    lr: TextLayoutResult,
    s: Int,
    e: Int,
    color: Color
) {
    val w = 2.5.dp.toPx()
    val r = 4.dp.toPx()
    val textLen = lr.layoutInput.text.length
    listOf(s, e).forEach { offset ->
        val o = offset.coerceIn(0, textLen)
        val line = lr.getLineForOffset(o)
        val x = lr.getHorizontalPosition(o, usePrimaryDirection = true)
        val top = lr.getLineTop(line)
        val bottom = lr.getLineBottom(line)
        drawRoundRect(
            color = color,
            topLeft = Offset(x - w / 2f, top),
            size = Size(w, (bottom - top).coerceAtLeast(1f)),
            cornerRadius = CornerRadius(w / 2f)
        )
        drawCircle(color = color, radius = r, center = Offset(x, bottom))
    }
}

private fun DrawScope.drawTagUnderlines(
    layout: TextLayoutResult?,
    ranges: List<Pair<Int, Int>>,
    color: Color
) {
    val lr = layout ?: return
    if (lr.layoutInput.text.isEmpty()) return
    val stroke = 2.dp.toPx()
    ranges.forEach { (s, e) ->
        val firstLine = lr.getLineForOffset(s.coerceIn(0, lr.layoutInput.text.length))
        val lastLine = lr.getLineForOffset((e - 1).coerceIn(0, lr.layoutInput.text.length))
        for (line in firstLine..lastLine) {
            val lineStart = lr.getLineStart(line)
            val lineEnd = lr.getLineEnd(line, visibleEnd = true)
            val from = maxOf(s, lineStart)
            val to = minOf(e, lineEnd)
            if (to <= from) continue
            val x1 = lr.getHorizontalPosition(from, usePrimaryDirection = true)
            val x2 = lr.getHorizontalPosition(to, usePrimaryDirection = true)
            val y = lr.getLineBottom(line)
            drawLine(
                color = color,
                start = Offset(x1, y),
                end = Offset(x2, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

private fun buildBodyAnnotated(
    sub: String,
    baseOffset: Int,
    marks: List<Mark>,
    selection: Selection?,
    ttsStart: Int,
    ttsEnd: Int,
    ttsColor: Color,
    selectionColor: Color,
    block: Block? = null,
    linkColor: Color = Color.Unspecified,
    codeBg: Color = Color.Unspecified
): AnnotatedString = buildAnnotatedString {
    append(sub)
    val len = sub.length

    // Markdown 行内样式（粗体 / 斜体 / 删除线 / 行内代码）——绝对偏移裁到本段内。
    // 一段被分页切开时，两截各自裁各自的区间，所以加粗跨页两边都对。
    block?.spans?.forEach { sp ->
        val s = (sp.start - baseOffset).coerceIn(0, len)
        val e = (sp.end - baseOffset).coerceIn(0, len)
        if (e <= s) return@forEach
        addStyle(
            SpanStyle(
                fontWeight = if (sp.bold) FontWeight.Bold else null,
                fontStyle = if (sp.italic) FontStyle.Italic else null,
                textDecoration = if (sp.strike) TextDecoration.LineThrough else null,
                fontFamily = if (sp.code) FontFamily.Monospace else null,
                background = if (sp.code && codeBg != Color.Unspecified) codeBg else Color.Unspecified
            ),
            s, e
        )
    }
    // 链接：着色 + 下划线，点按由渲染层识别后交给系统打开
    block?.links?.forEach { lk ->
        val s = (lk.start - baseOffset).coerceIn(0, len)
        val e = (lk.end - baseOffset).coerceIn(0, len)
        if (e > s && linkColor != Color.Unspecified) {
            addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), s, e)
        }
    }

    marks.forEach { m ->
        // 书签、整页标签、段落标签都不参与底色着
        //（标签记的是整页/整段区间，涂上去会把大片正文刷成高亮）
        if (m.type == MarkType.BOOKMARK || m.type == MarkType.TAG || m.type == MarkType.MARK) return@forEach
        val s = (m.startOffset - baseOffset).coerceIn(0, len)
        val e = (m.endOffset - baseOffset).coerceIn(0, len)
        if (e > s) {
            val c = HighlightColors[(m.color).mod(HighlightColors.size)]
            addStyle(SpanStyle(background = c), s, e)
        }
    }
    // 正在朗读的句
    if (ttsStart >= 0 && ttsEnd > ttsStart) {
        val s = (ttsStart - baseOffset).coerceIn(0, len)
        val e = (ttsEnd + 1 - baseOffset).coerceIn(0, len)
        if (e > s) addStyle(SpanStyle(color = ttsColor), s, e)
    }
    // 当前选中
    if (selection != null) {
        val s = (selection.start - baseOffset).coerceIn(0, len)
        val e = (selection.end - baseOffset).coerceIn(0, len)
        if (e > s) addStyle(SpanStyle(background = selectionColor), s, e)
    }
}

/** 从某个位置向两侧扩展到完整句子 */
private fun sentenceRange(text: String, from: Int): IntRange {
    if (text.isEmpty()) return 0..0
    val idx = from.coerceIn(0, text.lastIndex)
    val enders = "。！？!?；;\n…"
    var s = idx
    while (s > 0 && enders.indexOf(text[s - 1]) < 0) s--
    var e = idx
    while (e < text.lastIndex && enders.indexOf(text[e]) < 0) e++
    return s..e
}

/** 从某个位置向两侧扩展到整段（以换行分段） */
private fun paragraphRange(text: String, from: Int): IntRange {
    if (text.isEmpty()) return 0..0
    val idx = from.coerceIn(0, text.lastIndex)
    var s = idx
    while (s > 0 && text[s - 1] != '\n') s--
    var e = idx
    while (e < text.lastIndex && text[e] != '\n') e++
    return s..e
}

// ==================== 页脚 ====================

@Composable
private fun ReaderFooter(
    session: ReaderSession,
    visible: Boolean,
    tagged: Boolean,
    modifier: Modifier = Modifier
) {
    val p = LocalInkPalette.current
    val pageNo = session.bookPageNo
    val total = session.bookTotalPages
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
        modifier = modifier
    ) {
        // 页脚：页码 + 进度百分比。tagged 时给一个小标签角。
        PageFooterBar(
            pageNo = pageNo,
            total = total,
            fallbackProgress = session.bookProgress(),
            tagged = tagged,
            marginDp = session.prefs.margin.dp,
            accent = p.accent
        )
    }
}

/**
 * 页脚内容（页码 / 百分比）。
 *
 * 抽出来是因为分页模式下每一页都要自带一条页脚 —— 翻页时页眉页脚跟着整页一起走，
 * 这样看起来才是「整屏翻页」而不是正文区在页眉页脚之间挪动。
 */
@Composable
private fun PageFooterBar(
    pageNo: Int,
    total: Int,
    fallbackProgress: Float,
    tagged: Boolean,
    marginDp: Dp,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val p = LocalInkPalette.current
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Row(
        modifier
            .fillMaxWidth()
            .padding(bottom = bottomInset + 12.dp)
            .padding(horizontal = marginDp + 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (tagged) {
            InkIcon(
                R.drawable.ic_bookmark,
                stringResource(R.string.reader_page_tagged),
                tint = accent.copy(alpha = 0.85f),
                size = 11
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = if (pageNo > 0 && total > 0) "$pageNo/$total" else "—/—",
            style = InkType.Caption.copy(fontSize = 10.sp),
            color = p.textFaint,
            maxLines = 1
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (pageNo > 0 && total > 0) {
                "${((pageNo.toFloat() / total) * 100).toInt()}%"
            } else {
                "${(fallbackProgress * 100).toInt()}%"
            },
            style = InkType.Caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
            color = accent
        )
    }
}

// ==================== 切章提示 ====================

@Composable
private fun ChapterFlash(index: Int, title: String, total: Int) {
    val p = LocalInkPalette.current
    Column(
        Modifier
            .width(250.dp)
            .shadow(18.dp, RoundedCornerShape(18.dp), clip = false)
            .clip(RoundedCornerShape(18.dp))
            .background(p.surfaceHigh)
            .padding(vertical = 26.dp, horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = String.format("%02d", index + 1),
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 26.sp,
            letterSpacing = 1.sp,
            color = p.accent
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .width(30.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(p.accent.copy(alpha = 0.5f))
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            color = p.textPrimary,
            maxLines = 2
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = pluralStringResource(R.plurals.reader_total_chapters, total, total),
            style = InkType.Caption.copy(fontSize = 10.sp),
            color = p.textFaint
        )
    }
}
