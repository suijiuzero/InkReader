package com.inkreader.ui.bookdetail

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.inkreader.AppContainer
import com.inkreader.MainActivity
import com.inkreader.R
import com.inkreader.data.export.BookExporter
import com.inkreader.data.model.Book
import com.inkreader.data.model.Chapter
import com.inkreader.data.model.SplitMode
import com.inkreader.data.model.SplitOptions
import com.inkreader.ui.component.BookCover
import com.inkreader.ui.component.CardShape
import com.inkreader.ui.component.CoverCache
import com.inkreader.ui.component.CoverCropDialog
import com.inkreader.ui.component.InkButton
import com.inkreader.ui.component.InkDialogShell
import com.inkreader.ui.component.InkIcon
import com.inkreader.ui.component.InkIconButton
import com.inkreader.ui.component.InkLoading
import com.inkreader.ui.component.InkProgress
import com.inkreader.ui.component.InkTextButton
import com.inkreader.ui.component.InkTextField
import com.inkreader.ui.component.InkTopBar
import com.inkreader.ui.component.SectionLabel
import com.inkreader.ui.component.dangerColor
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 书籍详情 / 章节管理。
 *
 * 支持：
 *  · 长按拖动章节卡片排序（带浮层阴影与首项锚点防跳动）
 *  · 章节菜单新增「并入上一章 / 并入下一章」（自动平移书签、高亮、笔记的 offset）
 *  · 右上角菜单新增「整书重新分章」（支持切换策略、自定义前缀/正则与净网去噪，保留全书批注）
 */
@Composable
fun BookDetailScreen(
    container: AppContainer,
    bookId: Long,
    onBack: () -> Unit,
    onRead: (Int, Int) -> Unit,
    onEdit: (Long, String) -> Unit,
    onGoShelf: () -> Unit = onBack
) {
    val p = LocalInkPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var book by remember { mutableStateOf<Book?>(null) }
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    // 长按拖动排序：正在拖的章节、拖动开始时的顺序、手指在列表内的绝对 Y、按下时手指落在卡片内的高度（像素）
    var dragChapterId by remember { mutableStateOf<Long?>(null) }
    var dragStartIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var dragFingerY by remember { mutableFloatStateOf(0f) }
    var dragGrab by remember { mutableFloatStateOf(0f) }
    /** 换位后的滚动锚点修复是否正在跑（一次只允许一个，别和边缘自动滚打架） */
    var anchorFixInFlight by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val dragEdgeZone = with(LocalDensity.current) { 72.dp.toPx() }
    val dragMaxSpeed = with(LocalDensity.current) { 15.dp.toPx() }

    var editingInfo by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Chapter?>(null) }
    var deleting by remember { mutableStateOf<Chapter?>(null) }
    var confirmDeleteBook by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var newChapterTitle by remember { mutableStateOf("") }
    var showNewChapter by remember { mutableStateOf(false) }
    var showAutoSort by remember { mutableStateOf(false) }
    var showResplitDialog by remember { mutableStateOf(false) }

    // 导出
    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0f) }
    var showChapterPicker by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<Pair<List<Chapter>, Boolean>?>(null) }

    fun reload() {
        scope.launch {
            book = container.repo.book(bookId)
            chapters = container.repo.chapters(bookId)
            loading = false
        }
    }
    LaunchedEffect(bookId) { reload() }

    fun endChapterDrag() {
        val moved = dragChapterId
        dragChapterId = null
        dragFingerY = 0f
        dragGrab = 0f
        if (moved == null) return
        val ids = chapters.map { it.id }
        if (ids == dragStartIds) return
        scope.launch {
            container.repo.reorderChapters(bookId, ids)
            chapters = container.repo.chapters(bookId)
            book = container.repo.book(bookId)
        }
    }

    fun dragHitTest() {
        val moved = dragChapterId ?: return
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            val key = info.key
            key is Long && key != moved &&
                dragFingerY >= info.offset && dragFingerY <= info.offset + info.size
        } ?: return
        val list = chapters.toMutableList()
        val from = list.indexOfFirst { it.id == moved }
        val to = list.indexOfFirst { it.id == target.key }
        if (from < 0 || to < 0 || from == to) return
        // 换位前先记住「可视区第一行是谁、离视口顶多远」。换位后 LazyColumn 会按 key 重新定位，
        // 第一行若正好是被换走的章，画面会跳一下 —— 要用 **key** 找回它的新下标再滚回原位置；
        // 用下标去锚是错的：换完之后那个下标已经是别的章了。
        val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull()
        val anchorId = visible?.key as? Long
        val anchorOffset = listState.firstVisibleItemScrollOffset
        val anchorChapterIndex = if (anchorId != null) list.indexOfFirst { it.id == anchorId } else -1
        // 章列表前面还有若干个头部 item，换算回 LazyColumn 的下标时要补上
        val headerCount = if (visible != null && anchorChapterIndex >= 0) visible.index - anchorChapterIndex else 0
        list.add(to, list.removeAt(from))
        chapters = list
        // 一次只跑一个锚点修复：拖动帧循环里每帧都会调到这里，
        // 并发发起 scrollToItem 会和边缘自动滚的 scrollBy 互相取消。
        if (anchorId != null && !anchorFixInFlight) {
            val newChapterIndex = list.indexOfFirst { it.id == anchorId }
            if (newChapterIndex >= 0) {
                anchorFixInFlight = true
                scope.launch {
                    runCatching { listState.scrollToItem(headerCount + newChapterIndex, anchorOffset) }
                    anchorFixInFlight = false
                }
            }
        }
    }

    fun dragEdgeScrollSpeed(): Float {
        if (dragChapterId == null) return 0f
        val viewportH = listState.layoutInfo.viewportSize.height.toFloat()
        if (viewportH <= 0f || dragEdgeZone <= 0f) return 0f
        val bottomZoneStart = viewportH - dragEdgeZone
        return when {
            dragFingerY < dragEdgeZone ->
                -(dragEdgeZone - dragFingerY) / dragEdgeZone * dragMaxSpeed

            dragFingerY > bottomZoneStart ->
                (dragFingerY - bottomZoneStart) / dragEdgeZone * dragMaxSpeed

            else -> 0f
        }
    }

    LaunchedEffect(dragChapterId) {
        while (dragChapterId != null) {
            withFrameNanos { }
            val speed = dragEdgeScrollSpeed()
            if (speed != 0f) listState.scrollBy(speed)
            dragHitTest()
        }
    }

    // 追加文件：支持 MainActivity 级固定请求码返回与本地 fallback
    var appendUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showAppendDialog by remember { mutableStateOf(false) }
    var appendSplit by remember { mutableStateOf(SplitMode.SMART) }

    val appendPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = lenientPickedUris(result.data)
        if (uris.isNotEmpty()) {
            appendUris = uris
            showAppendDialog = true
        }
    }

    LaunchedEffect(container.pendingAppendUris) {
        val pend = container.pendingAppendUris
        if (pend.isNotEmpty()) {
            container.pendingAppendUris = emptyList()
            appendUris = pend
            showAppendDialog = true
        }
    }

    // 导入封面：选图 → 裁切 → 只把裁切结果存进应用私有目录
    var coverSource by remember { mutableStateOf<Bitmap?>(null) }
    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = lenientPickedUris(result.data).firstOrNull()
        if (uri != null) {
            scope.launch {
                val bmp = container.repo.decodeCoverSource(uri)
                if (bmp == null) {
                    Toast.makeText(ctx, ctx.getString(R.string.detail_cover_unreadable), Toast.LENGTH_LONG).show()
                } else {
                    coverSource = bmp
                }
            }
        }
    }

    LaunchedEffect(container.pendingCoverUri) {
        val uri = container.pendingCoverUri ?: return@LaunchedEffect
        container.pendingCoverUri = null
        val bmp = container.repo.decodeCoverSource(uri)
        if (bmp == null) {
            Toast.makeText(ctx, ctx.getString(R.string.detail_cover_unreadable), Toast.LENGTH_LONG).show()
        } else {
            coverSource = bmp
        }
    }

    fun launchAppendPicker() {
        val act = container.docPickerLauncher
        if (act != null) {
            act(MainActivity.REQ_PICK_APPEND, "*/*", true, null)
        } else {
            appendPicker.launch(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            )
        }
    }

    fun launchCoverPicker() {
        val act = container.docPickerLauncher
        if (act != null) {
            act(MainActivity.REQ_PICK_COVER, "image/*", false, null)
        } else {
            coverPicker.launch(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
            )
        }
    }

    fun runAppend(uris: List<Uri>, split: SplitMode) {
        busy = true
        scope.launch {
            val errors = container.repo.appendFilesAsChapters(bookId, uris, split) { _, _ -> }
            val after = container.repo.chapters(bookId).size
            val added = after - chapters.size
            busy = false
            reload()
            container.library.refresh()
            Toast.makeText(
                ctx,
                when {
                    errors.isEmpty() -> ctx.resources.getQuantityString(
                        R.plurals.detail_appended_chapters, added, added
                    )
                    added > 0 -> ctx.getString(
                        R.string.detail_appended_with_failures, added, errors.size
                    )
                    else -> errors.first()
                },
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun runExport(targets: List<Chapter>, includeHeader: Boolean) {
        val b = book ?: return
        if (targets.isEmpty()) return
        exporting = true
        exportProgress = 0f
        scope.launch {
            val outcome = runCatching {
                BookExporter.export(
                    context = ctx,
                    book = b,
                    chapters = targets,
                    includeHeader = includeHeader,
                    readChapter = { ch -> container.repo.chapterText(ch) },
                    onProgress = { f -> scope.launch { exportProgress = f } }
                )
            }
            exporting = false
            exportProgress = 1f
            outcome
                .onSuccess { r ->
                    val tip = if (r.downloadOk) {
                        ctx.getString(R.string.detail_exported_ok, r.fileName)
                    } else {
                        ctx.getString(R.string.detail_exported_no_download, r.fileName)
                    }
                    Toast.makeText(ctx, tip, Toast.LENGTH_LONG).show()
                    runCatching {
                        ctx.startActivity(
                            Intent.createChooser(r.shareIntent, ctx.getString(R.string.detail_share_txt))
                        )
                    }
                }
                .onFailure { e ->
                    Toast.makeText(
                        ctx,
                        ctx.getString(
                            R.string.detail_export_failed,
                            e.message ?: ctx.getString(R.string.action_unknown_error)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        val pending = pendingExport
        pendingExport = null
        if (pending != null) runExport(pending.first, pending.second)
    }

    fun startExport(targets: List<Chapter>, includeHeader: Boolean) {
        val needPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needPerm) {
            pendingExport = targets to includeHeader
            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            runExport(targets, includeHeader)
        }
    }

    val bottomPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(p.background)) {
        Column(Modifier.fillMaxSize()) {
            val subtitleText = book?.let {
                stringResource(
                    R.string.detail_subtitle,
                    it.title,
                    pluralStringResource(R.plurals.plural_chapters, it.totalChapters, it.totalChapters),
                    formatChars(ctx, it.totalChars)
                )
            }
            InkTopBar(
                title = stringResource(R.string.detail_chapter_management),
                subtitle = subtitleText,
                onBack = {
                    container.library.refresh()
                    onBack()
                },
                actions = {
                    Box {
                        InkIconButton(
                            R.drawable.ic_more,
                            stringResource(R.string.action_more),
                            { showMore = true },
                            tint = p.textPrimary
                        )
                        DropdownMenu(
                            expanded = showMore,
                            onDismissRequest = { showMore = false },
                            containerColor = p.surfaceHigh,
                            tonalElevation = 0.dp
                        ) {
                            MenuRow(R.drawable.ic_share, stringResource(R.string.detail_export_whole_txt)) {
                                showMore = false
                                startExport(chapters, includeHeader = true)
                            }
                            MenuRow(R.drawable.ic_page, stringResource(R.string.detail_export_single_chapter)) {
                                showMore = false
                                showChapterPicker = true
                            }
                            MenuRow(R.drawable.ic_edit, stringResource(R.string.detail_edit_title_author)) {
                                showMore = false; editingInfo = true
                            }
                            MenuRow(R.drawable.ic_import, stringResource(R.string.detail_append_files_as_chapters)) {
                                showMore = false; launchAppendPicker()
                            }
                            MenuRow(R.drawable.ic_page, stringResource(R.string.detail_import_cover)) {
                                showMore = false; launchCoverPicker()
                            }
                            if ((book?.coverPath ?: "").isNotBlank()) {
                                MenuRow(R.drawable.ic_theme, stringResource(R.string.detail_restore_default_cover)) {
                                    showMore = false
                                    scope.launch {
                                        container.repo.clearCover(bookId)
                                        CoverCache.invalidate()
                                        container.library.refresh()
                                        reload()
                                        Toast.makeText(ctx, ctx.getString(R.string.detail_cover_restored), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            MenuRow(R.drawable.ic_add, stringResource(R.string.detail_new_blank_chapter)) {
                                showMore = false; showNewChapter = true
                            }
                            MenuRow(R.drawable.ic_toc, stringResource(R.string.detail_resplit_book)) {
                                showMore = false; showResplitDialog = true
                            }
                            MenuRow(R.drawable.ic_sort, stringResource(R.string.detail_auto_sort_chapters)) {
                                showMore = false; showAutoSort = true
                            }
                            MenuRow(R.drawable.ic_delete, stringResource(R.string.detail_delete_whole_book), danger = true) {
                                showMore = false; confirmDeleteBook = true
                            }
                        }
                    }
                }
            )

            when {
                loading -> InkLoading()
                book == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.detail_book_not_found), style = InkType.UiLabel, color = p.textFaint)
                }

                else -> {
                    val b = book!!
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { pos ->
                                            val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                                val key = info.key
                                                key is Long && pos.y >= info.offset && pos.y <= info.offset + info.size
                                            } ?: return@detectDragGesturesAfterLongPress
                                            dragChapterId = hit.key as Long
                                            dragStartIds = chapters.map { it.id }
                                            dragFingerY = pos.y
                                            dragGrab = pos.y - hit.offset
                                        },
                                        onDrag = { change, amount ->
                                            if (dragChapterId == null) return@detectDragGesturesAfterLongPress
                                            change.consume()
                                            dragFingerY += amount.y
                                            dragHitTest()
                                        },
                                        onDragEnd = { endChapterDrag() },
                                        onDragCancel = { endChapterDrag() }
                                    )
                                },
                            userScrollEnabled = dragChapterId == null,
                            contentPadding = PaddingValues(
                                start = 20.dp,
                                end = 20.dp,
                                top = 4.dp,
                                bottom = bottomPad + 28.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(CardShape)
                                        .background(p.surface)
                                        .border(1.dp, p.divider, CardShape)
                                        .padding(16.dp)
                                ) {
                                    Row(Modifier.fillMaxWidth()) {
                                        BookCover(
                                            book = b,
                                            modifier = Modifier.width(96.dp).aspectRatio(0.70f)
                                        )
                                        Spacer(Modifier.width(16.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                text = b.title,
                                                fontFamily = FontFamily.SansSerif,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 18.sp,
                                                lineHeight = 25.sp,
                                                color = p.textPrimary,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (b.author.isNotBlank()) {
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    text = b.author,
                                                    style = InkType.Caption.copy(fontSize = 12.sp),
                                                    color = p.textSecondary
                                                )
                                            }
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                text = typeLabel(ctx, b) + "  ·  " + formatChars(ctx, b.totalChars),
                                                style = InkType.Caption.copy(fontSize = 11.sp),
                                                color = p.textFaint
                                            )
                                            Spacer(Modifier.height(12.dp))
                                            InkProgress(b.displayProgress, Modifier.fillMaxWidth())
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                text = stringResource(
                                                    R.string.label_read_percent,
                                                    (b.displayProgress * 100).toInt()
                                                ),
                                                style = InkType.Caption.copy(
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium
                                                ),
                                                color = p.accent
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(16.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        InkButton(
                                            text = if (b.displayProgress > 0f) stringResource(R.string.detail_continue_reading) else stringResource(R.string.detail_start_reading),
                                            onClick = { onRead(b.lastChapterIndex, b.lastCharOffset) },
                                            iconRes = R.drawable.ic_book,
                                            modifier = Modifier.weight(1f)
                                        )
                                        InkButton(
                                            text = stringResource(R.string.detail_read_from_start),
                                            onClick = { onRead(0, 0) },
                                            filled = false,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            item {
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SectionLabel(stringResource(R.string.detail_chapters_count, chapters.size), Modifier.weight(1f))
                                    Text(
                                        text = stringResource(R.string.detail_tap_title_to_read),
                                        style = InkType.Caption.copy(fontSize = 10.5.sp),
                                        color = p.textFaint
                                    )
                                }
                            }

                            itemsIndexed(chapters, key = { _, c -> c.id }) { i, c ->
                                val dragging = dragChapterId == c.id
                                ChapterCard(
                                    index = i,
                                    chapter = c,
                                    isFirst = i == 0,
                                    isLast = i == chapters.lastIndex,
                                    isCurrent = i == b.lastChapterIndex,
                                    busy = busy,
                                    onOpen = { onRead(i, 0) },
                                    onMove = { delta ->
                                        busy = true
                                        scope.launch {
                                            container.repo.moveChapter(bookId, c.id, delta)
                                            chapters = container.repo.chapters(bookId)
                                            book = container.repo.book(bookId)
                                            busy = false
                                        }
                                    },
                                    onMergePrev = {
                                        busy = true
                                        scope.launch {
                                            val ok = container.repo.mergeChapterWithPrev(bookId, c.id)
                                            chapters = container.repo.chapters(bookId)
                                            book = container.repo.book(bookId)
                                            container.library.refresh()
                                            busy = false
                                            if (ok) {
                                                Toast.makeText(ctx, ctx.getString(R.string.detail_merged_prev_toast), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    onMergeNext = {
                                        busy = true
                                        scope.launch {
                                            val ok = container.repo.mergeChapterWithNext(bookId, c.id)
                                            chapters = container.repo.chapters(bookId)
                                            book = container.repo.book(bookId)
                                            container.library.refresh()
                                            busy = false
                                            if (ok) {
                                                Toast.makeText(ctx, ctx.getString(R.string.detail_merged_next_toast), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    onRename = { renaming = c },
                                    onEditContent = { onEdit(c.id, c.title) },
                                    onExport = { startExport(listOf(c), includeHeader = false) },
                                    onDelete = { deleting = c },
                                    modifier = if (dragging) Modifier.graphicsLayer { alpha = 0f } else Modifier
                                )
                            }
                        }

                        // 跟手的浮层卡片
                        val draggedId = dragChapterId
                        if (draggedId != null) {
                            val dragIndex = chapters.indexOfFirst { it.id == draggedId }
                            if (dragIndex >= 0) {
                                ChapterCard(
                                    index = dragIndex,
                                    chapter = chapters[dragIndex],
                                    isFirst = dragIndex == 0,
                                    isLast = dragIndex == chapters.lastIndex,
                                    isCurrent = dragIndex == b.lastChapterIndex,
                                    busy = busy,
                                    onOpen = {},
                                    onMove = {},
                                    onMergePrev = {},
                                    onMergeNext = {},
                                    onRename = {},
                                    onEditContent = {},
                                    onExport = {},
                                    onDelete = {},
                                    modifier = Modifier
                                        .padding(horizontal = 20.dp)
                                        .offset { IntOffset(0, (dragFingerY - dragGrab).roundToInt()) }
                                        .shadow(10.dp, CardShape)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 封面裁切浮层
        coverSource?.let { bmp ->
            CoverCropDialog(
                source = bmp,
                onCancel = { coverSource = null },
                onConfirm = { bytes ->
                    coverSource = null
                    scope.launch {
                        container.repo.saveCover(bookId, bytes)
                        CoverCache.invalidate()
                        container.library.refresh()
                        reload()
                        Toast.makeText(ctx, ctx.getString(R.string.detail_cover_updated), Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        if (exporting) {
            Box(
                Modifier.fillMaxSize().background(p.scrim).clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier
                        .width(250.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(p.surfaceHigh)
                        .border(1.dp, p.divider, RoundedCornerShape(18.dp))
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    SectionLabel(stringResource(R.string.detail_exporting_txt))
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.detail_export_share_hint),
                        style = InkType.Caption.copy(fontSize = 12.sp),
                        color = p.textSecondary
                    )
                    Spacer(Modifier.height(16.dp))
                    InkProgress(exportProgress, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(9.dp))
                    Text(
                        text = "${(exportProgress * 100).toInt()}%",
                        style = InkType.Caption.copy(fontSize = 11.sp),
                        color = p.textFaint
                    )
                }
            }
        }
    }

    if (editingInfo && book != null) {
        var t by remember(book!!.title) { mutableStateOf(book!!.title) }
        var a by remember(book!!.author) { mutableStateOf(book!!.author) }
        InkDialogShell(onDismiss = { editingInfo = false }, title = stringResource(R.string.detail_book_info)) {
            SectionLabel(stringResource(R.string.detail_field_title))
            Spacer(Modifier.height(9.dp))
            InkTextField(t, { t = it }, stringResource(R.string.detail_field_title))
            Spacer(Modifier.height(15.dp))
            SectionLabel(stringResource(R.string.detail_field_author))
            Spacer(Modifier.height(9.dp))
            InkTextField(a, { a = it }, stringResource(R.string.detail_field_author))
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                InkTextButton(stringResource(R.string.action_cancel), color = LocalInkPalette.current.textSecondary) { editingInfo = false }
                InkTextButton(stringResource(R.string.action_save)) {
                    scope.launch {
                        container.repo.updateBookMeta(bookId, t.trim(), a.trim())
                        container.library.refresh()
                        reload()
                    }
                    editingInfo = false
                }
            }
        }
    }

    renaming?.let { ch ->
        var t by remember(ch.id) { mutableStateOf(ch.title) }
        InkDialogShell(onDismiss = { renaming = null }, title = stringResource(R.string.detail_rename_chapter)) {
            InkTextField(t, { t = it }, stringResource(R.string.detail_chapter_title_field))
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                InkTextButton(stringResource(R.string.action_cancel), color = LocalInkPalette.current.textSecondary) { renaming = null }
                InkTextButton(stringResource(R.string.action_save)) {
                    scope.launch {
                        container.repo.renameChapter(ch.id, t.trim())
                        reload()
                    }
                    renaming = null
                }
            }
        }
    }

    deleting?.let { ch ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = LocalInkPalette.current.surfaceHigh,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    stringResource(R.string.detail_delete_chapter),
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold,
                    color = LocalInkPalette.current.textPrimary
                )
            },
            text = {
                Text(
                    stringResource(R.string.detail_delete_chapter_confirm, ch.title),
                    style = InkType.Caption.copy(fontSize = 13.sp),
                    color = LocalInkPalette.current.textSecondary,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                InkTextButton(stringResource(R.string.action_delete), color = dangerColor()) {
                    val target = ch
                    deleting = null
                    scope.launch {
                        container.repo.deleteChapter(bookId, target.id)
                        container.library.refresh()
                        reload()
                    }
                }
            },
            dismissButton = {
                InkTextButton(stringResource(R.string.action_cancel), color = LocalInkPalette.current.textSecondary) { deleting = null }
            }
        )
    }

    if (confirmDeleteBook) {
        AlertDialog(
            onDismissRequest = { confirmDeleteBook = false },
            containerColor = LocalInkPalette.current.surfaceHigh,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    stringResource(R.string.detail_delete_whole_book),
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold,
                    color = LocalInkPalette.current.textPrimary
                )
            },
            text = {
                Text(
                    stringResource(R.string.detail_delete_book_confirm, book?.title.orEmpty()),
                    style = InkType.Caption.copy(fontSize = 13.sp),
                    color = LocalInkPalette.current.textSecondary,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                InkTextButton(stringResource(R.string.action_delete), color = dangerColor()) {
                    confirmDeleteBook = false
                    container.library.delete(bookId)
                    onGoShelf()
                }
            },
            dismissButton = {
                InkTextButton(stringResource(R.string.action_cancel), color = LocalInkPalette.current.textSecondary) { confirmDeleteBook = false }
            }
        )
    }

    if (showChapterPicker) {
        InkDialogShell(onDismiss = { showChapterPicker = false }, title = stringResource(R.string.detail_select_chapter_export)) {
            Text(
                text = stringResource(R.string.detail_export_single_hint),
                style = InkType.Caption.copy(fontSize = 11.5.sp),
                color = LocalInkPalette.current.textFaint
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(chapters, key = { _, c -> c.id }) { i, c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(LocalInkPalette.current.background)
                            .clickable {
                                showChapterPicker = false
                                startExport(listOf(c), includeHeader = false)
                            }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = String.format("%02d", i + 1),
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 11.sp,
                            color = LocalInkPalette.current.textFaint,
                            modifier = Modifier.width(26.dp)
                        )
                        Text(
                            text = c.title,
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 13.5.sp,
                            color = LocalInkPalette.current.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        InkIcon(
                            R.drawable.ic_share,
                            null,
                            tint = LocalInkPalette.current.accent,
                            size = 16
                        )
                    }
                }
            }
        }
    }

    if (showAppendDialog) {
        InkDialogShell(
            onDismiss = { showAppendDialog = false },
            title = pluralStringResource(
                R.plurals.detail_append_files_title,
                appendUris.size,
                appendUris.size
            ),
            buttons = {
                Row(horizontalArrangement = Arrangement.End) {
                    InkTextButton(stringResource(R.string.action_cancel), color = LocalInkPalette.current.textSecondary) {
                        showAppendDialog = false
                    }
                    InkTextButton(stringResource(R.string.detail_start_append)) {
                        val uris = appendUris
                        val split = appendSplit
                        showAppendDialog = false
                        appendUris = emptyList()
                        runAppend(uris, split)
                    }
                }
            }
        ) {
            Text(
                text = appendUris.mapNotNull { container.repo.displayName(it) }
                    .joinToString(ctx.getString(R.string.detail_file_separator))
                    .let { if (it.length > 90) it.take(90) + "…" else it },
                style = InkType.Caption.copy(fontSize = 11.5.sp),
                color = LocalInkPalette.current.textFaint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(18.dp))
            SectionLabel(stringResource(R.string.detail_chapter_split))
            Spacer(Modifier.height(10.dp))
            AppendChoice(
                checked = appendSplit == SplitMode.SMART,
                title = stringResource(R.string.detail_split_smart),
                desc = stringResource(R.string.detail_split_smart_desc)
            ) { appendSplit = SplitMode.SMART }
            Spacer(Modifier.height(8.dp))
            AppendChoice(
                checked = appendSplit == SplitMode.IN_FILE,
                title = stringResource(R.string.detail_split_in_file),
                desc = stringResource(R.string.detail_split_in_file_desc)
            ) { appendSplit = SplitMode.IN_FILE }
            Spacer(Modifier.height(8.dp))
            AppendChoice(
                checked = appendSplit == SplitMode.PER_FILE,
                title = stringResource(R.string.detail_split_per_file),
                desc = stringResource(R.string.detail_split_per_file_desc)
            ) { appendSplit = SplitMode.PER_FILE }
        }
    }

    if (showNewChapter) {
        InkDialogShell(onDismiss = { showNewChapter = false }, title = stringResource(R.string.detail_new_chapter)) {
            SectionLabel(stringResource(R.string.detail_chapter_title_field))
            Spacer(Modifier.height(9.dp))
            InkTextField(newChapterTitle, { newChapterTitle = it }, stringResource(R.string.detail_chapter_title_hint))
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                InkTextButton(stringResource(R.string.action_cancel), color = LocalInkPalette.current.textSecondary) { showNewChapter = false }
                InkTextButton(stringResource(R.string.action_create)) {
                    val title = newChapterTitle
                    newChapterTitle = ""
                    showNewChapter = false
                    scope.launch {
                        val id = container.repo.addEmptyChapter(bookId, title)
                        reload()
                        onEdit(id, title.ifBlank { ctx.getString(R.string.detail_untitled_chapter) })
                    }
                }
            }
        }
    }

    // 整书重新分章弹窗
    if (showResplitDialog) {
        var resplitMode by remember { mutableStateOf(SplitMode.SMART) }
        var customPattern by remember { mutableStateOf("") }
        var cleanWatermark by remember { mutableStateOf(true) }

        InkDialogShell(
            onDismiss = { showResplitDialog = false },
            title = stringResource(R.string.detail_resplit_book),
            buttons = {
                Row(horizontalArrangement = Arrangement.End) {
                    InkTextButton(stringResource(R.string.action_cancel), color = p.textSecondary) {
                        showResplitDialog = false
                    }
                    InkTextButton(stringResource(R.string.detail_resplit_confirm)) {
                        showResplitDialog = false
                        busy = true
                        scope.launch {
                            // 兜住异常：失败要么是「书太大」（仓库返回 -1），要么是仓库抛错，
                            // 两种都不能让 busy 卡住，也不能把异常漏到没有处理器的 Main 作用域里
                            val count = runCatching {
                                container.repo.resplitBook(
                                    bookId,
                                    SplitOptions(
                                        mode = resplitMode,
                                        cleanWatermark = cleanWatermark,
                                        customPattern = customPattern.trim()
                                    )
                                )
                            }.getOrElse { e ->
                                busy = false
                                Toast.makeText(
                                    ctx,
                                    ctx.getString(
                                        R.string.detail_resplit_failed,
                                        e.message ?: ctx.getString(R.string.action_unknown_error)
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                                return@launch
                            }
                            reload()
                            container.library.refresh()
                            busy = false
                            Toast.makeText(
                                ctx,
                                if (count < 0) {
                                    ctx.getString(R.string.detail_resplit_too_large)
                                } else {
                                    ctx.getString(R.string.detail_resplit_done_toast, count)
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        ) {
            Text(
                text = stringResource(R.string.detail_resplit_desc),
                style = InkType.Caption.copy(fontSize = 12.sp),
                color = p.textSecondary,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(14.dp))
            AppendChoice(
                checked = resplitMode == SplitMode.SMART,
                title = stringResource(R.string.shelf_split_smart_title),
                desc = stringResource(R.string.shelf_split_smart_desc)
            ) { resplitMode = SplitMode.SMART }
            Spacer(Modifier.height(8.dp))
            AppendChoice(
                checked = resplitMode == SplitMode.STRICT_CHAP,
                title = stringResource(R.string.shelf_split_strict_chap_title),
                desc = stringResource(R.string.shelf_split_strict_chap_desc)
            ) { resplitMode = SplitMode.STRICT_CHAP }
            Spacer(Modifier.height(8.dp))
            AppendChoice(
                checked = resplitMode == SplitMode.NUMBERED,
                title = stringResource(R.string.shelf_split_numbered_title),
                desc = stringResource(R.string.shelf_split_numbered_desc)
            ) { resplitMode = SplitMode.NUMBERED }
            Spacer(Modifier.height(8.dp))
            AppendChoice(
                checked = resplitMode == SplitMode.FIXED_SIZE,
                title = stringResource(R.string.shelf_split_fixed_title),
                desc = stringResource(R.string.shelf_split_fixed_desc)
            ) { resplitMode = SplitMode.FIXED_SIZE }
            Spacer(Modifier.height(8.dp))
            AppendChoice(
                checked = resplitMode == SplitMode.CUSTOM,
                title = stringResource(R.string.shelf_split_custom_title),
                desc = stringResource(R.string.shelf_split_custom_desc)
            ) { resplitMode = SplitMode.CUSTOM }

            if (resplitMode == SplitMode.CUSTOM) {
                Spacer(Modifier.height(8.dp))
                InkTextField(
                    value = customPattern,
                    onValueChange = { customPattern = it },
                    placeholder = stringResource(R.string.shelf_split_custom_placeholder)
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.background)
                    .border(1.dp, p.divider, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.shelf_clean_watermark_title),
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.5.sp,
                        color = p.textPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.shelf_clean_watermark_desc),
                        style = InkType.Caption.copy(fontSize = 11.sp),
                        color = p.textFaint
                    )
                }
                Spacer(Modifier.width(10.dp))
                Switch(
                    checked = cleanWatermark,
                    onCheckedChange = { cleanWatermark = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = p.accent,
                        uncheckedThumbColor = p.textFaint,
                        uncheckedTrackColor = p.background,
                        uncheckedBorderColor = p.divider
                    )
                )
            }
        }
    }

    if (showAutoSort) {
        InkDialogShell(onDismiss = { showAutoSort = false }, title = stringResource(R.string.detail_auto_sort_chapters)) {
            Text(
                text = stringResource(R.string.detail_auto_sort_desc, chapters.size),
                style = InkType.Caption.copy(fontSize = 12.sp),
                color = LocalInkPalette.current.textSecondary,
                lineHeight = 19.sp
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                InkTextButton(stringResource(R.string.action_cancel), color = LocalInkPalette.current.textSecondary) { showAutoSort = false }
                InkTextButton(stringResource(R.string.detail_start_sort)) {
                    showAutoSort = false
                    scope.launch {
                        val n = container.repo.autoSortChapters(bookId)
                        reload()
                        Toast.makeText(ctx, ctx.resources.getQuantityString(R.plurals.detail_sorted_chapters, n, n), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

private fun lenientPickedUris(data: Intent?): List<Uri> {
    if (data == null) return emptyList()
    val out = LinkedHashSet<Uri>()
    data.clipData?.let { clip ->
        for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let(out::add)
    }
    data.data?.let(out::add)
    @Suppress("DEPRECATION")
    data.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(out::add)
    return out.toList()
}

@Composable
private fun MenuRow(icon: Int, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val p = LocalInkPalette.current
    val tint = if (danger) dangerColor() else p.textSecondary
    DropdownMenuItem(
        leadingIcon = { InkIcon(icon, null, tint = tint, size = 18) },
        text = {
            Text(
                label,
                fontSize = 14.sp,
                fontFamily = FontFamily.SansSerif,
                color = if (danger) dangerColor() else p.textPrimary
            )
        },
        onClick = onClick
    )
}

@Composable
private fun ChapterCard(
    index: Int,
    chapter: Chapter,
    isFirst: Boolean,
    isLast: Boolean,
    isCurrent: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onMove: (Int) -> Unit,
    onMergePrev: () -> Unit,
    onMergeNext: () -> Unit,
    onRename: () -> Unit,
    onEditContent: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalInkPalette.current
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val fromSource = if (chapter.sourceName.isNotBlank())
        stringResource(R.string.detail_from_source, chapter.sourceName) else null

    Row(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(if (isCurrent) p.accentSoft else p.surface)
            .border(1.dp, if (isCurrent) p.accent.copy(alpha = 0.35f) else p.divider, CardShape)
            .clickable { onOpen() }
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (isCurrent) p.accent else p.background),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${index + 1}",
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = if (isCurrent) Color.White else p.textSecondary
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = chapter.title,
                fontFamily = FontFamily.SansSerif,
                fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                fontSize = 14.5.sp,
                lineHeight = 20.sp,
                color = if (isCurrent) p.accent else p.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    if (chapter.volume.isNotBlank()) {
                        append(chapter.volume)
                        append("  ·  ")
                    }
                    append(formatChars(ctx, chapter.charCount))
                    if (fromSource != null) append("  ·  $fromSource")
                },
                style = InkType.Caption.copy(fontSize = 10.5.sp),
                color = p.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box {
            InkIconButton(
                res = R.drawable.ic_more,
                contentDescription = stringResource(R.string.detail_chapter_actions),
                onClick = { menu = true },
                tint = p.textFaint,
                size = 18
            )
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                containerColor = p.surfaceHigh,
                tonalElevation = 0.dp
            ) {
                MenuRow(R.drawable.ic_book, stringResource(R.string.detail_read_this_chapter)) { menu = false; onOpen() }
                MenuRow(R.drawable.ic_share, stringResource(R.string.detail_export_this_chapter_txt)) { menu = false; onExport() }
                MenuRow(R.drawable.ic_edit, stringResource(R.string.detail_edit_content)) { menu = false; onEditContent() }
                MenuRow(R.drawable.ic_text_size, stringResource(R.string.action_rename)) { menu = false; onRename() }
                if (!isFirst) {
                    MenuRow(R.drawable.ic_merge, stringResource(R.string.detail_merge_prev)) {
                        menu = false
                        if (!busy) onMergePrev()
                    }
                }
                if (!isLast) {
                    MenuRow(R.drawable.ic_merge, stringResource(R.string.detail_merge_next)) {
                        menu = false
                        if (!busy) onMergeNext()
                    }
                }
                MenuRow(R.drawable.ic_up, stringResource(R.string.detail_move_up)) { menu = false; onMove(-1) }
                MenuRow(R.drawable.ic_down, stringResource(R.string.detail_move_down)) { menu = false; onMove(1) }
                MenuRow(R.drawable.ic_delete, stringResource(R.string.detail_delete_this_chapter), danger = true) { menu = false; onDelete() }
            }
        }
    }
}

private fun typeLabel(ctx: Context, b: Book): String = when (b.sourceType) {
    com.inkreader.data.model.SourceType.TXT -> ctx.getString(R.string.detail_type_txt)
    com.inkreader.data.model.SourceType.DOCX -> ctx.getString(R.string.detail_type_word)
    com.inkreader.data.model.SourceType.MERGED -> ctx.getString(R.string.detail_type_merged)
    com.inkreader.data.model.SourceType.MANUAL -> ctx.getString(R.string.detail_type_manual)
    com.inkreader.data.model.SourceType.MD -> ctx.getString(R.string.detail_type_markdown)
    com.inkreader.data.model.SourceType.PDF -> ctx.getString(R.string.detail_type_pdf)
    com.inkreader.data.model.SourceType.HTML -> ctx.getString(R.string.detail_type_html)
    com.inkreader.data.model.SourceType.EPUB -> ctx.getString(R.string.detail_type_epub)
}

private fun formatChars(ctx: Context, n: Int): String {
    val locale = Locale.getDefault()
    return when {
        n >= 100_000_000 ->
            String.format(locale, ctx.getString(R.string.detail_words_yi), n / 100_000_000f)
        n >= 10_000 ->
            String.format(locale, ctx.getString(R.string.detail_words_wan), n / 10_000f)
        else -> String.format(locale, ctx.getString(R.string.detail_words_ge), n)
    }
}

@Composable
private fun AppendChoice(checked: Boolean, title: String, desc: String, onClick: () -> Unit) {
    val p = LocalInkPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) p.accentSoft else p.background)
            .border(1.dp, if (checked) p.accent else p.divider, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .border(1.5.dp, if (checked) p.accent else p.textFaint, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Box(Modifier.size(9.dp).clip(CircleShape).background(p.accent))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = p.textPrimary
            )
            Spacer(Modifier.height(3.dp))
            Text(desc, style = InkType.Caption.copy(fontSize = 11.sp), color = p.textFaint)
        }
    }
}
