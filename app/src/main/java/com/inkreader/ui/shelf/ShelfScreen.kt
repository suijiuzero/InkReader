package com.inkreader.ui.shelf

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkreader.AppContainer
import com.inkreader.R
import com.inkreader.data.model.Book
import com.inkreader.data.model.BookGroup
import com.inkreader.data.model.ImportMode
import com.inkreader.data.model.ShelfSort
import com.inkreader.data.model.ShelfView
import com.inkreader.data.model.SplitMode
import com.inkreader.ui.component.BookCover
import com.inkreader.ui.component.CardShape
import com.inkreader.ui.component.EmptyState
import com.inkreader.ui.component.InkButton
import com.inkreader.ui.component.InkIcon
import com.inkreader.ui.component.InkIconButton
import com.inkreader.ui.component.InkLoading
import com.inkreader.ui.component.InkProgress
import com.inkreader.ui.component.InkTextButton
import com.inkreader.ui.component.InkDialogShell
import com.inkreader.ui.component.InkTextField
import com.inkreader.ui.component.InkTopBar
import com.inkreader.ui.component.SectionLabel
import com.inkreader.ui.component.dangerColor
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette
import kotlinx.coroutines.launch

/**
 * 选择器优先展示的 MIME 类型。
 * 这里只影响「默认筛选」，实际能不能导入由解析器决定 ——
 * 所以末尾保留 application/octet-stream 与通配类型，
 * 免得某些 ROM 把 .md / .epub 报成通用二进制而选不中。
 */
private val SUPPORTED_MIME = arrayOf(
    "text/plain",
    "text/markdown",
    "text/x-markdown",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/pdf",
    "application/epub+zip",
    "text/html",
    "application/xhtml+xml",
    "application/octet-stream",
    "*/*"
)

/** 发起选择器后，超过这个时间才重进 App 就不算「被打断」了 */
private const val PICKER_RETRY_WINDOW_MS = 5 * 60 * 1000L

private const val TAG = "InkImport"

/** 自己拼 ACTION_OPEN_DOCUMENT 意图 */
private fun documentPickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = "*/*"
    putExtra(Intent.EXTRA_MIME_TYPES, SUPPORTED_MIME)
    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
}

/**
 * 从选择器返回的 Intent 里尽力把 Uri 抠出来。
 *
 * 这里刻意**不用** `ActivityResultContracts.OpenMultipleDocuments`：它的 parseResult
 * 在 `resultCode != RESULT_OK` 时直接返回空列表，而部分 ROM 的选择器（实测 vivo 自家的
 * 文件管理器选择器）会在用户已经选好文件的情况下回一个非 OK 的 resultCode，
 * 于是 App 拿到空列表就静默什么都不做——用户看到的就是「按了确定却毫无反应」。
 * 所以这里不看 resultCode，只要 data 里有 Uri 就认。
 */
private fun extractPickedUris(data: Intent?): List<Uri> {
    if (data == null) return emptyList()
    val out = LinkedHashSet<Uri>()
    data.clipData?.let { clip ->
        for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let(out::add)
    }
    data.data?.let(out::add)
    @Suppress("DEPRECATION")
    data.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { if (it != null) out.add(it) }
    @Suppress("DEPRECATION")
    data.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(out::add)
    return out.filter { it.scheme == "content" || it.scheme == "file" }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfScreen(
    container: AppContainer,
    onOpenBook: (Long, Int, Int) -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenMarks: (Long, String) -> Unit,
    onOpenGroup: (Long) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val lib = container.library
    val p = LocalInkPalette.current
    val ctx = LocalContext.current
    val selected = remember { mutableStateListOf<Long>() }

    var showImportPicker by remember { mutableStateOf(false) }
    var pendingUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showNewBookDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    // 分组相关
    var showCreateGroup by remember { mutableStateOf(false) }
    var showGroupPicker by remember { mutableStateOf(false) }
    var creatingFor by remember { mutableStateOf<List<Long>>(emptyList()) }
    var movingIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // 结果回来了（哪怕是取消），标记先清掉，避免下次启动误判成「被打断」
        container.prefs.pickerLaunchedAt = 0L
        container.prefs.pickerRetriedAt = 0L
        val uris = extractPickedUris(result.data)
        Log.i(TAG, "picker returned: resultCode=${result.resultCode}, uris=${uris.size}")
        if (uris.isEmpty()) {
            // 用户主动取消；若其实是结果被系统回收，进程已经重启，启动时会自动重开选择器
            container.prefs.pendingImportUris = emptyList()
            return@rememberLauncherForActivityResult
        }
        // 尽力把授权持久化，这样即使进程随即被系统回收，重进 App 也还读得到文件
        uris.forEach { u ->
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    u, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        val list = uris.toList()
        container.prefs.pendingImportUris = list.map { it.toString() }
        pendingUris = list
        showImportDialog = true
    }

    /** 统一入口：先落盘「我要发起选择器了」，再打开系统选择器 */
    fun launchPicker() {
        container.prefs.pickerLaunchedAt = System.currentTimeMillis()
        runCatching { picker.launch(documentPickerIntent()) }.onFailure { e ->
            container.prefs.pickerLaunchedAt = 0L
            Toast.makeText(ctx, ctx.getString(R.string.shelf_picker_open_failed, e.message ?: ctx.getString(R.string.action_unknown_error)), Toast.LENGTH_LONG).show()
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // 启动时的现场恢复：两种情况
    //  1) 上次选完文件还没导，进程就被回收了 → 把「导入设置框」原样恢复出来
    //  2) 上次刚点开选择器就被回收 → 结果永远不会回来，替用户重新打开一次
    LaunchedEffect(Unit) {
        val launchedAt = container.prefs.pickerLaunchedAt
        container.prefs.pickerLaunchedAt = 0L

        val resumedUris = container.prefs.pendingImportUris
            .mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
            .filter { it.scheme == "content" || it.scheme == "file" }
        if (resumedUris.isNotEmpty()) {
            Log.i(TAG, "resume pending import: ${resumedUris.size} file(s)")
            pendingUris = resumedUris
            showImportDialog = true
            return@LaunchedEffect
        }

        val now = System.currentTimeMillis()
        val age = now - launchedAt
        val retriedAt = container.prefs.pickerRetriedAt
        val interrupted = launchedAt > 0L && age in 1..PICKER_RETRY_WINDOW_MS &&
            now - retriedAt > PICKER_RETRY_WINDOW_MS
        if (interrupted) {
            Log.i(TAG, "picker was interrupted after ${age}ms, reopening")
            container.prefs.pickerRetriedAt = now
            Toast.makeText(ctx, ctx.getString(R.string.shelf_picker_reopened), Toast.LENGTH_LONG).show()
            kotlinx.coroutines.delay(300)
            launchPicker()
        }
    }

    // 首次进入时申请通知权限（朗读的后台控制条需要）
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    // 外部「用其他应用打开 / 分享」进来的文件，随时可能被投递
    LaunchedEffect(container.pendingUris) {
        val pend = container.pendingUris
        if (pend.isNotEmpty()) {
            container.pendingUris = emptyList()
            pendingUris = pend
            showImportDialog = true
        }
    }

    // 导入结果提示
    LaunchedEffect(lib.toast) {
        lib.toast?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show()
            lib.consumeToast()
        }
    }

    Column(Modifier.fillMaxSize().background(p.background)) {
        InkTopBar(
            title = stringResource(R.string.app_name),
            subtitle = if (lib.books.isEmpty()) stringResource(R.string.shelf_empty_subtitle)
            else pluralStringResource(R.plurals.shelf_count_hint, lib.books.size, lib.books.size),
            actions = {
                InkIconButton(R.drawable.ic_search, stringResource(R.string.shelf_search_books), { onOpenSearch() })
                InkIconButton(
                    res = if (lib.view == ShelfView.GRID) R.drawable.ic_list else R.drawable.ic_grid,
                    contentDescription = stringResource(R.string.shelf_toggle_view),
                    onClick = {
                        lib.applyView(if (lib.view == ShelfView.GRID) ShelfView.LIST else ShelfView.GRID)
                    }
                )
                Box {
                    InkIconButton(R.drawable.ic_sort, stringResource(R.string.action_sort), { showSortMenu = true })
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                        containerColor = p.surfaceHigh,
                        tonalElevation = 0.dp
                    ) {
                        listOf(
                            ShelfSort.RECENT to stringResource(R.string.shelf_sort_recent),
                            ShelfSort.ADDED to stringResource(R.string.shelf_sort_added),
                            ShelfSort.TITLE to stringResource(R.string.shelf_sort_title),
                            ShelfSort.PROGRESS to stringResource(R.string.shelf_sort_progress)
                        ).forEach { (s, label) ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        label,
                                        color = if (lib.sort == s) p.accent else p.textPrimary,
                                        fontSize = 14.sp,
                                        fontFamily = FontFamily.SansSerif
                                    )
                                },
                                trailingIcon = {
                                    if (lib.sort == s) InkIcon(R.drawable.ic_check, null, tint = p.accent, size = 16)
                                },
                                onClick = {
                                    lib.applySort(s); showSortMenu = false
                                }
                            )
                        }
                    }
                }
                Box {
                    InkIconButton(R.drawable.ic_more, stringResource(R.string.action_more), { showMoreMenu = true })
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                        containerColor = p.surfaceHigh,
                        tonalElevation = 0.dp
                    ) {
                        DropdownMenuItem(
                            leadingIcon = { InkIcon(R.drawable.ic_import, null, tint = p.textSecondary, size = 18) },
                            text = { Text(stringResource(R.string.shelf_import_docs), color = p.textPrimary, fontSize = 14.sp) },
                            onClick = { showMoreMenu = false; launchPicker() }
                        )
                        DropdownMenuItem(
                            leadingIcon = { InkIcon(R.drawable.ic_edit, null, tint = p.textSecondary, size = 18) },
                            text = { Text(stringResource(R.string.shelf_new_book), color = p.textPrimary, fontSize = 14.sp) },
                            onClick = { showMoreMenu = false; showNewBookDialog = true }
                        )
                        DropdownMenuItem(
                            leadingIcon = { InkIcon(R.drawable.ic_settings, null, tint = p.textSecondary, size = 18) },
                            text = { Text(stringResource(R.string.action_settings), color = p.textPrimary, fontSize = 14.sp) },
                            onClick = { showMoreMenu = false; onOpenSettings() }
                        )
                    }
                }
            }
        )

        Box(Modifier.fillMaxSize()) {
            when {
                lib.loading && lib.books.isEmpty() -> InkLoading()
                lib.books.isEmpty() -> EmptyState(
                    iconRes = R.drawable.ic_book,
                    title = stringResource(R.string.shelf_empty_title),
                    hint = stringResource(R.string.shelf_empty_hint),
                    action = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            InkButton(stringResource(R.string.shelf_import_docs), { launchPicker() }, iconRes = R.drawable.ic_import)
                            Spacer(Modifier.height(6.dp))
                            InkTextButton(stringResource(R.string.shelf_new_blank_book)) { showNewBookDialog = true }
                        }
                    }
                )

                lib.view == ShelfView.GRID -> BookGrid(
                    books = lib.ungrouped,
                    selected = selected,
                    onOpen = { b -> onOpenBook(b.id, b.lastChapterIndex, b.lastCharOffset) },
                    onLongPress = { b ->
                        if (selected.contains(b.id)) selected.remove(b.id) else selected.add(b.id)
                    },
                    onDetail = { onOpenDetail(it) },
                    groups = lib.groups,
                    groupBooks = { gid -> lib.booksOf(gid) },
                    onOpenGroup = onOpenGroup
                )

                else -> BookList(
                    books = lib.ungrouped,
                    selected = selected,
                    onOpen = { b -> onOpenBook(b.id, b.lastChapterIndex, b.lastCharOffset) },
                    onLongPress = { b ->
                        if (selected.contains(b.id)) selected.remove(b.id) else selected.add(b.id)
                    },
                    onDetail = { onOpenDetail(it) },
                    groups = lib.groups,
                    groupBooks = { gid -> lib.booksOf(gid) },
                    onOpenGroup = onOpenGroup
                )
            }

            // 浮动操作
            androidx.compose.animation.AnimatedVisibility(
                visible = selected.isEmpty() && lib.books.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomEnd)
            ) {
                Row(
                    Modifier.padding(
                        end = 18.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 18.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FloatingAction(R.drawable.ic_edit, stringResource(R.string.action_new)) { showNewBookDialog = true }
                    Spacer(Modifier.width(10.dp))
                    FloatingAction(R.drawable.ic_import, stringResource(R.string.action_import), primary = true) { launchPicker() }
                }
            }

            // 多选操作条
            androidx.compose.animation.AnimatedVisibility(
                visible = selected.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                SelectionBar(
                    count = selected.size,
                    onClear = { selected.clear() },
                    onNewGroup = {
                        creatingFor = selected.toList()
                        showCreateGroup = true
                    },
                    onMoveToGroup = { movingIds = selected.toList(); showGroupPicker = true },
                    onRemoveFromGroup = {
                        val ids = selected.toList()
                        selected.clear()
                        scope.launch {
                            container.repo.moveBooksToGroup(ids, 0L)
                            lib.refresh()
                            Toast.makeText(ctx, ctx.getString(R.string.shelf_removed_from_group), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onDelete = {
                        val ids = selected.toList()
                        selected.clear()
                        ids.forEach { lib.delete(it) }
                    }
                )
            }

            // 导入进度
            if (lib.importing) {
                ImportOverlay(lib.importStage, lib.importFraction)
            }
        }
    }

    if (showImportDialog) {
        ImportDialog(
            fileCount = pendingUris.size,
            names = pendingUris.mapNotNull { container.repo.displayName(it) },
            onDismiss = {
                showImportDialog = false
                pendingUris = emptyList()
                // 用户主动放弃，现场保护也一起清掉
                container.prefs.pendingImportUris = emptyList()
            },
            onConfirm = { mode, title, split ->
                showImportDialog = false
                val uris = pendingUris
                pendingUris = emptyList()
                container.prefs.pendingImportUris = emptyList()
                lib.import(uris, mode, title, split)
            }
        )
    }

    // 批量导入明细：有文件失败时一定要说清楚「哪些成了、哪些没成、为什么」
    if (lib.importReport.isNotEmpty()) {
        ImportReportDialog(
            files = lib.importReport,
            onDismiss = { lib.consumeImportReport() }
        )
    }

    if (showNewBookDialog) {
        NewBookDialog(
            onDismiss = { showNewBookDialog = false },
            onCreate = { title, author ->
                showNewBookDialog = false
                lib.createBook(title, author) { id -> onOpenDetail(id) }
            }
        )
    }

    // 新建分组（把选中的书放进去）
    if (showCreateGroup) {
        GroupNameDialog(
            title = stringResource(R.string.shelf_new_group),
            initial = "",
            confirmText = stringResource(R.string.action_create),
            onDismiss = { showCreateGroup = false }
        ) { name ->
            showCreateGroup = false
            val ids = creatingFor
            creatingFor = emptyList()
            selected.clear()
            scope.launch {
                val gid = container.repo.createGroup(name)
                container.repo.moveBooksToGroup(ids, gid)
                lib.refresh()
                Toast.makeText(ctx, ctx.getString(R.string.shelf_group_created, name), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 移动到分组
    if (showGroupPicker) {
        GroupPickerDialog(
            groups = lib.groups,
            count = movingIds.size,
            onDismiss = { showGroupPicker = false },
            onCreateNew = {
                creatingFor = movingIds
                movingIds = emptyList()
                showGroupPicker = false
                showCreateGroup = true
            }
        ) { gid ->
            val ids = movingIds
            showGroupPicker = false
            movingIds = emptyList()
            selected.clear()
            scope.launch {
                container.repo.moveBooksToGroup(ids, gid)
                lib.refresh()
                Toast.makeText(ctx, ctx.getString(R.string.shelf_moved_to_group), Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun FloatingAction(
    icon: Int,
    label: String,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    val p = LocalInkPalette.current
    val shape = RoundedCornerShape(999.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .shadow(if (primary) 12.dp else 6.dp, shape, clip = false)
            .clip(shape)
            .background(if (primary) p.accent else p.surface)
            .border(1.dp, if (primary) Color.Transparent else p.divider, shape)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        InkIcon(icon, label, tint = if (primary) Color.White else p.accent, size = 17)
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            color = if (primary) Color.White else p.textPrimary
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookGrid(
    books: List<Book>,
    selected: List<Long>,
    onOpen: (Book) -> Unit,
    onLongPress: (Book) -> Unit,
    onDetail: (Long) -> Unit,
    groups: List<BookGroup> = emptyList(),
    groupBooks: (Long) -> List<Book> = { emptyList() },
    onOpenGroup: (Long) -> Unit = {}
) {
    val p = LocalInkPalette.current
    val bottomPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 92.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = bottomPad + 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // 分组卡片排在书本前面：分组也是一张卡片，尺寸和书卡一致
        items(groups, key = { "g${it.id}" }) { g ->
            GroupCard(group = g, books = groupBooks(g.id)) { onOpenGroup(g.id) }
        }
        items(books, key = { it.id }) { b ->
            val isSelected = selected.contains(b.id)
            Column(
                Modifier
                    .clip(CardShape)
                    .background(p.surface)
                    .border(
                        1.dp,
                        if (isSelected) p.accent else p.divider,
                        CardShape
                    )
                    .combinedClickable(
                        onClick = { if (selected.isNotEmpty()) onLongPress(b) else onOpen(b) },
                        onLongClick = { onLongPress(b) }
                    )
                    .padding(6.dp)
            ) {
                Box {
                    BookCover(
                        book = b,
                        modifier = Modifier.fillMaxWidth().aspectRatio(0.70f)
                    )
                    if (isSelected) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(5.dp)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(p.accent),
                            contentAlignment = Alignment.Center
                        ) {
                            InkIcon(R.drawable.ic_check, null, tint = Color.White, size = 11)
                        }
                    }
                }
                Column(Modifier.padding(start = 3.dp, end = 3.dp, top = 8.dp, bottom = 3.dp)) {
                    // 书名只占一行，超出用省略号
                    Text(
                        text = b.title,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.5.sp,
                        color = p.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(7.dp))
                    InkProgress(b.displayProgress, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = stringResource(R.string.shelf_chapter_progress, b.totalChapters, (b.displayProgress * 100).toInt()),
                        style = InkType.Caption.copy(fontSize = 10.sp),
                        color = p.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookList(
    books: List<Book>,
    selected: List<Long>,
    onOpen: (Book) -> Unit,
    onLongPress: (Book) -> Unit,
    onDetail: (Long) -> Unit,
    groups: List<BookGroup> = emptyList(),
    groupBooks: (Long) -> List<Book> = { emptyList() },
    onOpenGroup: (Long) -> Unit = {}
) {
    val p = LocalInkPalette.current
    val bottomPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = bottomPad + 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // 列表模式下分组也用一行表示（左侧迷你封面拼图）
        items(groups, key = { "g${it.id}" }) { g ->
            GroupRow(group = g, books = groupBooks(g.id)) { onOpenGroup(g.id) }
        }
        items(books, key = { it.id }) { b ->
            val isSelected = selected.contains(b.id)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(CardShape)
                    .background(p.surface)
                    .border(1.dp, if (isSelected) p.accent else p.divider, CardShape)
                    .combinedClickable(
                        onClick = { if (selected.isNotEmpty()) onLongPress(b) else onOpen(b) },
                        onLongClick = { onLongPress(b) }
                    )
                    .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    BookCover(
                        book = b,
                        modifier = Modifier.width(50.dp).aspectRatio(0.70f)
                    )
                    if (isSelected) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(p.accent),
                            contentAlignment = Alignment.Center
                        ) {
                            InkIcon(R.drawable.ic_check, null, tint = Color.White, size = 11)
                        }
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = b.title,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        color = p.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    val chapterMeta = pluralStringResource(R.plurals.plural_chapters, b.totalChapters, b.totalChapters)
                    val source = sourceLabel(b)
                    Text(
                        text = buildString {
                            append(source)
                            append("  ·  $chapterMeta")
                            if (b.author.isNotBlank()) append("  ·  ${b.author}")
                        },
                        style = InkType.Caption.copy(fontSize = 11.sp),
                        color = p.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(9.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InkProgress(b.displayProgress, Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "${(b.displayProgress * 100).toInt()}%",
                            style = InkType.Caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                            color = p.accent
                        )
                    }
                }
                InkIconButton(R.drawable.ic_more, stringResource(R.string.action_detail), { onDetail(b.id) }, size = 19)
            }
        }
    }
}

@Composable
private fun sourceLabel(b: Book): String = when (b.sourceType) {
    com.inkreader.data.model.SourceType.TXT -> "TXT"
    com.inkreader.data.model.SourceType.DOCX -> "WORD"
    com.inkreader.data.model.SourceType.MERGED -> stringResource(R.string.shelf_source_merged)
    com.inkreader.data.model.SourceType.MANUAL -> stringResource(R.string.shelf_source_manual)
    com.inkreader.data.model.SourceType.MD -> "MD"
    com.inkreader.data.model.SourceType.PDF -> "PDF"
    com.inkreader.data.model.SourceType.HTML -> "HTML"
    com.inkreader.data.model.SourceType.EPUB -> "EPUB"
}

@Composable
private fun SelectionBar(
    count: Int,
    onClear: () -> Unit,
    onNewGroup: () -> Unit,
    onMoveToGroup: () -> Unit,
    onRemoveFromGroup: () -> Unit,
    onDelete: () -> Unit
) {
    val p = LocalInkPalette.current
    val bottomPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(p.surfaceHigh)
            .padding(bottom = bottomPad)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = pluralStringResource(R.plurals.shelf_selected_count, count, count),
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 14.5.sp,
                color = p.textPrimary,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
            InkTextButton(stringResource(R.string.action_cancel), color = p.textSecondary) { onClear() }
            InkTextButton(stringResource(R.string.action_delete), color = dangerColor()) { confirmDelete = true }
        }
        Spacer(Modifier.height(4.dp))
        // 分组相关操作
        Row(verticalAlignment = Alignment.CenterVertically) {
            InkTextButton(stringResource(R.string.shelf_new_group), color = p.accent) { onNewGroup() }
            InkTextButton(stringResource(R.string.shelf_move_to_group_action), color = p.accent) { onMoveToGroup() }
            InkTextButton(stringResource(R.string.shelf_remove_from_group_action), color = p.accent) { onRemoveFromGroup() }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = p.surfaceHigh,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    pluralStringResource(R.plurals.shelf_delete_books, count, count),
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold,
                    color = p.textPrimary
                )
            },
            text = {
                Text(
                    stringResource(R.string.shelf_delete_books_desc),
                    style = InkType.Caption.copy(fontSize = 13.sp),
                    color = p.textSecondary,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                InkTextButton(stringResource(R.string.action_delete), color = dangerColor()) {
                    confirmDelete = false
                    onDelete()
                }
            },
            dismissButton = { InkTextButton(stringResource(R.string.action_cancel), color = p.textSecondary) { confirmDelete = false } }
        )
    }
}

@Composable
private fun ImportOverlay(stage: String, fraction: Float) {
    val p = LocalInkPalette.current
    Box(
        Modifier.fillMaxSize().background(p.scrim).clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .width(260.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(p.surfaceHigh)
                .border(1.dp, p.divider, RoundedCornerShape(18.dp))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SectionLabel(stringResource(R.string.shelf_importing))
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (stage.isBlank()) stringResource(R.string.action_loading) else stage,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 13.5.sp,
                color = p.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(16.dp))
            InkProgress(fraction, Modifier.fillMaxWidth())
            Spacer(Modifier.height(9.dp))
            Text(
                text = "${(fraction * 100).toInt()}%",
                style = InkType.Caption.copy(fontSize = 11.sp),
                color = p.textFaint
            )
        }
    }
}

@Composable
private fun ImportReportDialog(
    files: List<com.inkreader.data.repo.BookRepository.ImportFileResult>,
    onDismiss: () -> Unit
) {
    val p = LocalInkPalette.current
    val ok = files.filter { it.ok }
    val bad = files.filter { !it.ok }

    InkDialogShell(
        onDismiss = onDismiss,
        title = stringResource(R.string.shelf_import_report_title),
        buttons = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                InkTextButton(stringResource(R.string.shelf_got_it), color = p.accent) { onDismiss() }
            }
        }
    ) {
        Text(
            text = if (bad.isNotEmpty())
                stringResource(R.string.shelf_import_result, ok.size, bad.size)
            else stringResource(R.string.shelf_import_result_ok, ok.size),
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 13.5.sp,
            color = p.textPrimary
        )

        if (bad.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionLabel(stringResource(R.string.shelf_failed_files))
            Spacer(Modifier.height(8.dp))
            bad.forEach { f ->
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(
                        text = f.name,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = p.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = f.reason.ifBlank { stringResource(R.string.shelf_import_failed_item) },
                        style = InkType.Caption.copy(fontSize = 11.5.sp),
                        color = dangerColor(),
                        lineHeight = 17.sp
                    )
                }
            }
        }

        if (ok.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionLabel(stringResource(R.string.shelf_imported))
            Spacer(Modifier.height(8.dp))
            ok.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = f.name,
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 13.sp,
                        color = p.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (f.chapterCount > 0) pluralStringResource(R.plurals.plural_chapters, f.chapterCount, f.chapterCount) else stringResource(R.string.shelf_imported),
                        style = InkType.Caption.copy(fontSize = 11.sp),
                        color = p.textFaint
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportDialog(
    fileCount: Int,
    names: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (ImportMode, String?, SplitMode) -> Unit
) {
    val p = LocalInkPalette.current
    var mode by remember { mutableStateOf(ImportMode.SEPARATE) }
    var title by remember { mutableStateOf("") }
    var splitMode by remember { mutableStateOf(SplitMode.SMART) }

    InkDialogShell(
        onDismiss = onDismiss,
        title = pluralStringResource(R.plurals.shelf_import_files_title, fileCount, fileCount),
        buttons = {
            Row(horizontalArrangement = Arrangement.End) {
                InkTextButton(stringResource(R.string.action_cancel), color = p.textSecondary) { onDismiss() }
                InkTextButton(stringResource(R.string.shelf_start_import)) {
                    onConfirm(mode, title.takeIf { it.isNotBlank() }, splitMode)
                }
            }
        }
    ) {
        if (names.isNotEmpty()) {
            Text(
                text = names.take(6).joinToString(stringResource(R.string.shelf_file_name_separator)) + if (names.size > 6) stringResource(R.string.shelf_file_names_more) else "",
                style = InkType.Caption.copy(fontSize = 11.5.sp),
                color = p.textFaint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(18.dp))
        }

        if (fileCount > 1) {
            SectionLabel(stringResource(R.string.shelf_import_mode))
            Spacer(Modifier.height(10.dp))
            ChoiceRow(
                checked = mode == ImportMode.SEPARATE,
                title = stringResource(R.string.shelf_mode_separate_title),
                desc = stringResource(R.string.shelf_mode_separate_desc)
            ) { mode = ImportMode.SEPARATE }
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                checked = mode == ImportMode.MERGE,
                title = stringResource(R.string.shelf_mode_merge_title),
                desc = stringResource(R.string.shelf_mode_merge_desc)
            ) { mode = ImportMode.MERGE }
        } else {
            // 单个文件：不显示导入方式，但排序照样生效，说明一下免得以为不会排
            Text(
                text = stringResource(R.string.shelf_single_file_hint),
                style = InkType.Caption.copy(fontSize = 11.5.sp),
                color = p.textFaint,
                lineHeight = 17.sp
            )
        }

        if (mode == ImportMode.MERGE) {
            Spacer(Modifier.height(18.dp))
            SectionLabel(stringResource(R.string.shelf_book_title_label))
            Spacer(Modifier.height(9.dp))
            InkTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = stringResource(R.string.shelf_title_auto_placeholder)
            )
            Spacer(Modifier.height(18.dp))
            SectionLabel(stringResource(R.string.shelf_chapter_split))
            Spacer(Modifier.height(10.dp))
            ChoiceRow(
                checked = splitMode == SplitMode.SMART,
                title = stringResource(R.string.shelf_split_smart_title),
                desc = stringResource(R.string.shelf_split_smart_desc)
            ) { splitMode = SplitMode.SMART }
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                checked = splitMode == SplitMode.IN_FILE,
                title = stringResource(R.string.shelf_split_in_file_title),
                desc = stringResource(R.string.shelf_split_in_file_desc)
            ) { splitMode = SplitMode.IN_FILE }
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                checked = splitMode == SplitMode.PER_FILE,
                title = stringResource(R.string.shelf_split_per_file_title),
                desc = stringResource(R.string.shelf_split_per_file_desc)
            ) { splitMode = SplitMode.PER_FILE }
        }
    }
}

@Composable
private fun NewBookDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    val p = LocalInkPalette.current
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    InkDialogShell(onDismiss = onDismiss, title = stringResource(R.string.shelf_new_book)) {
        SectionLabel(stringResource(R.string.shelf_book_title_label))
        Spacer(Modifier.height(9.dp))
        InkTextField(title, { title = it }, stringResource(R.string.shelf_book_title_example))
        Spacer(Modifier.height(16.dp))
        SectionLabel(stringResource(R.string.shelf_author_optional))
        Spacer(Modifier.height(9.dp))
        InkTextField(author, { author = it }, stringResource(R.string.shelf_author_placeholder))
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            InkTextButton(stringResource(R.string.action_cancel), color = p.textSecondary) { onDismiss() }
            InkTextButton(stringResource(R.string.action_create)) { onCreate(title.trim(), author.trim()) }
        }
    }
}

@Composable
private fun ChoiceRow(checked: Boolean, title: String, desc: String, onClick: () -> Unit) {
    val p = LocalInkPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) p.accentSoft else p.background)
            .border(
                1.dp,
                if (checked) p.accent else p.divider,
                RoundedCornerShape(12.dp)
            )
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
                text = title,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = p.textPrimary
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = desc,
                style = InkType.Caption.copy(fontSize = 11.sp),
                color = p.textFaint
            )
        }
    }
}
