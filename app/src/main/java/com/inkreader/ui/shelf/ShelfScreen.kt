package com.inkreader.ui.shelf

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import com.inkreader.MainActivity
import com.inkreader.R
import com.inkreader.data.model.Book
import com.inkreader.data.model.BookGroup
import com.inkreader.data.model.ImportMode
import com.inkreader.data.model.ImportOrder
import com.inkreader.ui.component.ImportAnalysisControls
import com.inkreader.ui.component.ImportOrderControls
import com.inkreader.data.model.ShelfSort
import com.inkreader.data.model.ShelfView
import com.inkreader.data.model.SplitMode
import com.inkreader.data.model.SplitOptions
import com.inkreader.data.repo.BookRepository
import com.inkreader.ui.component.BookCover
import com.inkreader.ui.component.CardShape
import com.inkreader.ui.component.EmptyState
import com.inkreader.ui.component.InkButton
import com.inkreader.ui.component.buildPickerIntent
import com.inkreader.ui.component.InkDialogShell
import com.inkreader.ui.component.InkIcon
import com.inkreader.ui.component.InkIconButton
import com.inkreader.ui.component.InkLoading
import com.inkreader.ui.component.PickerTrace
import com.inkreader.ui.component.InkProgress
import com.inkreader.ui.component.InkTextButton
import com.inkreader.ui.component.InkTextField
import com.inkreader.ui.component.InkTopBar
import com.inkreader.ui.component.SectionLabel
import com.inkreader.ui.component.dangerColor
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 选择器优先展示的 MIME 类型。
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

private const val TAG = "InkImport"

/**
 * 「选择器是不是被打断了」的判定宽限期。
 *
 * 系统派发 `onActivityResult` 的时机不确定（重建时在 onStart~onResume 之间，也可能更晚），
 * 所以不能在固定延时后就下结论说「被打断」—— 那样会把刚刚选好的结果冲掉，
 * 造成「第一次导入失败、第二次才成功」。这段时间里只要结果到了就立刻用结果，
 * 等满仍没有结果才替用户重开选择器。
 */
private const val PICKER_RESULT_GRACE_MS = 2500L

/** 兜底 ACTION_OPEN_DOCUMENT 意图：显式走 AOSP 文档选择器，找不到才退回系统默认 */
private fun documentPickerIntent(ctx: Context): Intent =
    buildPickerIntent(ctx, mimeType = "*/*", multiple = true, mimeTypes = arrayOf("*/*"), preferAosp = true)

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
    onOpenSettings: () -> Unit = {},
    onPickFiles: (() -> Unit)? = null
) {
    val lib = container.library
    val p = LocalInkPalette.current
    val ctx = LocalContext.current
    val selected = remember { mutableStateListOf<Long>() }

    var pendingUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingFileOrder by remember { mutableStateOf(ImportOrder.AUTO) }
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

    // ★ 选文件：**明确指定 AOSP 的文档选择器**（`com.android.documentsui`），
    //   并沿用「契约托管回调」这条路（`StartActivityForResult`），两者缺一不可：
    //
    //   ① 为什么不用 vivo 自家文件管理器：实测这台 vivo，走它时第一次会先弹
    //      「隐私密码 / 指纹」验证页（属于**另一个任务栈**），平台会把这次 activity result
    //      判成 CANCELED —— 落盘留痕：
    //        19:16:49 发起 → 指纹 → 选择器 → 4.93 秒后 res=0 uris=0 data=false   ❌
    //        19:17:22 发起 → （无密码页）→ 2.39 秒后 res=-1 uris=1 data=true     ✅
    //      AOSP 的 documentsui 没有这个验证环节，一次就能拿到结果。
    //   ② 为什么不用手写固定请求码：那种写法在跨任务栈时会丢结果；
    //      `rememberLauncherForActivityResult` 由 ActivityResultRegistry 托管回调，
    //      跨任务、Activity 重建都能把结果接住（SJopi / sjapi 就是这么做的，实测可用）。
    //
    //   空结果一律按「用户取消」处理，只写一条日志，**绝不自动重开选择器**。
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        container.prefs.pickerLaunchedAt = 0L
        container.prefs.pickerRetriedAt = 0L
        val uris = extractPickedUris(result.data)
        PickerTrace.log(ctx, "importPicker returned res=${result.resultCode} n=${uris.size}")
        if (uris.isEmpty()) {
            container.prefs.pendingImportUris = emptyList()
            Log.i(TAG, "import picker returned empty (treated as cancel)")
            return@rememberLauncherForActivityResult
        }
        uris.forEach { uri -> runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        container.prefs.pendingImportUris = uris.map { it.toString() }
        container.scope.launch {
            val safe = withContext(Dispatchers.IO) { uris.map { com.inkreader.ui.component.ensureReadableUri(ctx.applicationContext, it) } }
            container.prefs.pendingImportUris = safe.map { it.toString() }
            container.pendingUris = safe
        }
    }

    /**
     * 统一入口：用 AOSP 文档选择器选文件（意图显式指向 documentsui，找不到则退回系统默认）。
     */
    fun launchPicker(retry: Boolean = false) {
        if (!retry && onPickFiles != null) { onPickFiles(); return }
        val now = System.currentTimeMillis()
        container.prefs.pickerLaunchedAt = now
        container.prefs.pickerRetriedAt = if (retry) now else 0L
        PickerTrace.log(ctx, "shelf.launchPicker retry=$retry via=aosp")
        runCatching { importPicker.launch(documentPickerIntent(ctx)) }
            .onSuccess {
                PickerTrace.log(ctx, "shelf.launchPicker aosp launched")
            }
            .onFailure { e ->
                PickerTrace.log(
                    ctx, "shelf.launchPicker aosp FAILED ${e::class.java.simpleName}: ${e.message}"
                )
                container.prefs.pickerLaunchedAt = 0L
                container.prefs.pickerRetriedAt = 0L
                Toast.makeText(
                    ctx,
                    ctx.getString(
                        R.string.shelf_picker_open_failed,
                        e.message ?: ctx.getString(R.string.action_unknown_error)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    // 观察全局待导入队列（含 MainActivity.onActivityResult 投递的文件与外部「用其他应用打开」的文件）
    LaunchedEffect(container.pendingUris) {
        val pend = container.pendingUris
        if (pend.isNotEmpty()) {
            PickerTrace.log(ctx, "shelf.pendingUris observed n=${pend.size} -> show dialog")
            container.pendingUris = emptyList()
            pendingUris = pend
            pendingFileOrder = container.prefs.pendingImportOrder
            showImportDialog = true
        }
    }

    // 选择器回来的文件名：`contentResolver.query` 属于 IO，不能放在组合阶段做
    //（每重组一次就会对每个 URI 重新查一遍，界面会卡）。这里预取一次存下来。
    var pendingNames by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(pendingUris) {
        pendingNames = if (pendingUris.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { pendingUris.map { container.repo.displayName(it) ?: it.lastPathSegment.orEmpty() } }
        }
    }

    // 启动时的现场恢复：三种情况
    //  1) 上次刚点开选择器就被回收 → 结果永远不会回来，替用户重开一次；
    //  2) 上次选好了文件但还没点开始导入 → 把导入设置框原样恢复出来；
    //  「是否被打断」由 MainActivity 在 onCreate 里读标记后给出（见 AppContainer.docPickerInterrupted），
    //  所以陈留的标记不会再在每次启动时误判。
    //
    // ⚠ 这里**不能**用「固定延时之后下结论」：`onActivityResult` 的派发时机由系统决定，
    //   可能比任何固定延时都晚 —— 一旦先判成「被打断」并重开选择器，刚刚选好的结果就被冲掉了，
    //   表现就是「第一次导入失败、第二次才成功」。所以改成：只要结果到了就立刻收工；
    //   在宽限期内轮询等待，确认等不到才重开。
    LaunchedEffect(Unit) {
        com.inkreader.ui.component.PickerTrace.log(
            ctx,
            "shelf.recover start interrupted=${container.docPickerInterrupted} pendingPrefs=${container.prefs.pendingImportUris.size} pendingMem=${container.pendingUris.size} marker=${container.prefs.pickerLaunchedAt}"
        )
        // ① 先恢复「选完文件还没导」的现场（这段随时读都安全）
        val resumedUris = container.prefs.pendingImportUris
            .mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
            .filter { it.scheme == "content" || it.scheme == "file" }
        if (resumedUris.isNotEmpty()) {
            Log.i(TAG, "resume pending import: ${resumedUris.size} file(s)")
            container.docPickerInterrupted = false
            pendingUris = resumedUris
            pendingFileOrder = container.prefs.pendingImportOrder
            showImportDialog = true
            return@LaunchedEffect
        }

        // ② 消费「被打断」判定（只消费一次，避免之后某次回到书架又误重开选择器）
        val interrupted = container.docPickerInterrupted
        container.docPickerInterrupted = false
        if (!interrupted) return@LaunchedEffect

        // ③ 宽限期内轮询：结果随时可能还在路上
        var waited = 0L
        while (waited < PICKER_RESULT_GRACE_MS) {
            if (showImportDialog || container.pendingUris.isNotEmpty()) {
                Log.i(TAG, "picker result arrived within grace, no reopen")
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(100)
            waited += 100
        }
        if (showImportDialog || container.pendingUris.isNotEmpty()) return@LaunchedEffect

        Log.i(TAG, "picker was interrupted; reopening once")
        Toast.makeText(ctx, ctx.getString(R.string.shelf_picker_reopened), Toast.LENGTH_LONG).show()
        kotlinx.coroutines.delay(300)
        launchPicker(retry = true)
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
            if (selected.isEmpty() && lib.books.isNotEmpty()) {
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
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
            if (selected.isNotEmpty()) {
                Box(Modifier.align(Alignment.BottomCenter)) {
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
            }

            // 导入进度
            if (lib.importing) {
                ImportOverlay(lib.importStage, lib.importFraction, lib::pauseImport)
            }
        }
    }

    var showRecovery by remember { mutableStateOf(true) }
    LaunchedEffect(lib.recoveryAvailable, lib.importing) { if (lib.recoveryAvailable && !lib.importing) showRecovery = true }
    if (lib.recoveryAvailable && !lib.importing && showRecovery && !showImportDialog) {
        InkDialogShell(onDismiss = { showRecovery = false }, title = stringResource(R.string.recovery_title), buttons = {
            Row(horizontalArrangement = Arrangement.End) {
                InkTextButton(stringResource(R.string.recovery_discard)) { showRecovery = false; lib.discardImport() }
                InkTextButton(stringResource(R.string.recovery_resume)) { showRecovery = false; lib.resumeImport() }
            }
        }) { Text(stringResource(R.string.recovery_desc), color = p.textSecondary, style = InkType.Caption) }
    }

    if (showImportDialog) {
        ImportDialog(
            container = container,
            uris = pendingUris,
            fileCount = pendingUris.size,
            names = pendingNames,
            initialOrder = pendingFileOrder,
            onDismiss = {
                showImportDialog = false
                pendingUris = emptyList()
                container.prefs.pendingImportUris = emptyList()
            },
            onConfirm = { uris, mode, title, options, fileOrder ->
                showImportDialog = false
                pendingUris = emptyList()
                container.prefs.pendingImportUris = emptyList()
                lib.import(uris, mode, title, options.mode, options, fileOrder)
            }
        )
    }

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
private fun ImportOverlay(stage: String, fraction: Float, onPause: () -> Unit) {
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
            InkTextButton(stringResource(R.string.recovery_cancel), onClick = onPause)
        }
    }
}

@Composable
private fun ImportReportDialog(
    files: List<BookRepository.ImportFileResult>,
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

/**
 * 维度二：增强版导入弹窗 —— 实时预分章预览、切分档位切换、自定义前缀/正则与净网清洗开关。
 */
@Composable
private fun ImportDialog(
    container: AppContainer,
    uris: List<Uri>,
    fileCount: Int,
    names: List<String>,
    initialOrder: ImportOrder = ImportOrder.AUTO,
    onDismiss: () -> Unit,
    onConfirm: (List<Uri>, ImportMode, String?, SplitOptions, ImportOrder) -> Unit
) {
    val p = LocalInkPalette.current
    var mode by remember { mutableStateOf(ImportMode.SEPARATE) }
    var fileOrder by remember(uris) { mutableStateOf(initialOrder) }
    var selection by remember(uris) { mutableStateOf(uris.toList()) }
    val nameByUri = remember(uris, names) { uris.mapIndexed { i, uri -> uri to names.getOrElse(i) { uri.lastPathSegment.orEmpty() } }.toMap() }
    val effectiveUris = if (fileOrder == ImportOrder.SELECTION) selection else uris
    var title by remember { mutableStateOf("") }
    var splitMode by remember { mutableStateOf(SplitMode.SMART) }
    var customPattern by remember { mutableStateOf("") }
    var cleanWatermark by remember { mutableStateOf(true) }

    var textEncoding by remember(uris) { mutableStateOf("") }
    var rememberCorrections by remember(uris) { mutableStateOf(true) }
    var ignoredLines by remember(uris) { mutableStateOf(emptySet<Int>()) }
    var forcedLines by remember(uris) { mutableStateOf(emptySet<Int>()) }
    var sourceFingerprint by remember(uris) { mutableStateOf("") }
    var correctionCenter by remember { mutableStateOf(false) }

    var preview by remember { mutableStateOf<BookRepository.ImportPreview?>(null) }
    var previewLoading by remember { mutableStateOf(false) }

    val currentOptions = remember(splitMode, cleanWatermark, customPattern, textEncoding, ignoredLines, forcedLines, sourceFingerprint, rememberCorrections) {
        SplitOptions(
            mode = splitMode,
            cleanWatermark = cleanWatermark,
            customPattern = customPattern.trim(),
            textEncoding = textEncoding,
            ignoredHeadingLines = if (uris.size == 1) ignoredLines else emptySet(),
            rememberCorrections = rememberCorrections,
            forcedHeadingLines = if (uris.size == 1) forcedLines else emptySet(), sourceFingerprint = sourceFingerprint
        )
    }

    LaunchedEffect(effectiveUris, mode, currentOptions, fileOrder) {
        if (uris.isEmpty()) return@LaunchedEffect
        previewLoading = true
        preview = null
        kotlinx.coroutines.delay(300)
        try {
            preview = container.repo.previewImport(effectiveUris, mode, currentOptions, fileOrder)
        } finally {
            previewLoading = false
        }
    }

    if (correctionCenter && uris.size == 1) com.inkreader.ui.component.RecognitionCenter(
        container.repo, uris.single(), currentOptions, onDismiss = { correctionCenter = false }, onApply = {
            ignoredLines = it.ignoredHeadingLines; forcedLines = it.forcedHeadingLines
            sourceFingerprint = it.sourceFingerprint; correctionCenter = false
        })

    InkDialogShell(
        onDismiss = onDismiss,
        title = pluralStringResource(R.plurals.shelf_import_files_title, fileCount, fileCount),
        buttons = {
            Row(horizontalArrangement = Arrangement.End) {
                InkTextButton(stringResource(R.string.action_cancel), color = p.textSecondary) { onDismiss() }
                InkTextButton(stringResource(R.string.shelf_start_import)) {
                    onConfirm(effectiveUris, mode, title.takeIf { it.isNotBlank() }, currentOptions, fileOrder)
                }
            }
        }
    ) {
        if (names.isNotEmpty()) {
            Text(
                text = effectiveUris.take(6).mapNotNull { nameByUri[it] }.joinToString(stringResource(R.string.shelf_file_name_separator)) +
                    if (names.size > 6) stringResource(R.string.shelf_file_names_more) else "",
                style = InkType.Caption.copy(fontSize = 11.5.sp),
                color = p.textFaint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(14.dp))
        }

        // 实时预分章预览卡片
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(p.background)
                .border(1.dp, p.divider, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InkIcon(R.drawable.ic_toc, null, tint = p.accent, size = 16)
                Spacer(Modifier.width(8.dp))
                val pv = preview
                Text(
                    text = when {
                        previewLoading && pv == null -> stringResource(R.string.shelf_preview_scanning)
                        pv != null && pv.volumeCount > 0 -> stringResource(
                            R.string.shelf_preview_summary_volumes,
                            pv.estimatedChapters,
                            pv.volumeCount
                        )
                        pv != null -> stringResource(
                            R.string.shelf_preview_summary,
                            pv.estimatedChapters
                        )
                        else -> stringResource(R.string.shelf_preview_scanning)
                    },
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = p.accent,
                    modifier = Modifier.weight(1f)
                )
            }
            val samples = preview?.sampleTitles.orEmpty()
            if (samples.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                samples.take(3).forEachIndexed { idx, sTitle ->
                    Text(
                        text = "${idx + 1}. $sTitle",
                        style = InkType.Caption.copy(fontSize = 11.5.sp),
                        color = p.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 1.5.dp)
                    )
                }
            }
        }

        ImportAnalysisControls(
            preview = preview,
            singleFile = fileCount == 1,
            encoding = textEncoding,
            onEncodingChange = { textEncoding = it; ignoredLines = emptySet(); forcedLines = emptySet(); sourceFingerprint = "" },
            ignored = ignoredLines,
            rememberCorrections = rememberCorrections,
            onRememberChange = { rememberCorrections = it },
            onToggleHeading = { line -> ignoredLines = if (line in ignoredLines) ignoredLines - line else ignoredLines + line },
            onOpenFull = if (fileCount == 1 && splitMode !in listOf(SplitMode.CUSTOM, SplitMode.PER_FILE, SplitMode.FIXED_SIZE)) ({ correctionCenter = true }) else null
        )

        if (fileCount > 1) {
            Spacer(Modifier.height(16.dp))
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
        }

        if (mode == ImportMode.MERGE && fileCount > 1) {
            Spacer(Modifier.height(16.dp))
            SectionLabel(stringResource(R.string.shelf_book_title_label))
            Spacer(Modifier.height(9.dp))
            InkTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = stringResource(R.string.shelf_title_auto_placeholder)
            )
        }

        if (fileCount > 1) {
            Spacer(Modifier.height(16.dp))
            ImportOrderControls(
                order = fileOrder, names = selection.map { nameByUri[it].orEmpty() },
                onOrderChange = { fileOrder = it },
                onMove = { from, to -> selection = selection.toMutableList().apply { add(to, removeAt(from)) } }
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel(stringResource(R.string.shelf_chapter_split))
        Spacer(Modifier.height(10.dp))
        ChoiceRow(
            checked = splitMode == SplitMode.SMART,
            title = stringResource(R.string.shelf_split_smart_title),
            desc = stringResource(R.string.shelf_split_smart_desc)
        ) { splitMode = SplitMode.SMART }
        Spacer(Modifier.height(8.dp))
        ChoiceRow(
            checked = splitMode == SplitMode.STRICT_CHAP,
            title = stringResource(R.string.shelf_split_strict_chap_title),
            desc = stringResource(R.string.shelf_split_strict_chap_desc)
        ) { splitMode = SplitMode.STRICT_CHAP }
        Spacer(Modifier.height(8.dp))
        ChoiceRow(
            checked = splitMode == SplitMode.NUMBERED,
            title = stringResource(R.string.shelf_split_numbered_title),
            desc = stringResource(R.string.shelf_split_numbered_desc)
        ) { splitMode = SplitMode.NUMBERED }
        Spacer(Modifier.height(8.dp))
        ChoiceRow(
            checked = splitMode == SplitMode.FIXED_SIZE,
            title = stringResource(R.string.shelf_split_fixed_title),
            desc = stringResource(R.string.shelf_split_fixed_desc)
        ) { splitMode = SplitMode.FIXED_SIZE }
        Spacer(Modifier.height(8.dp))
        ChoiceRow(
            checked = splitMode == SplitMode.CUSTOM,
            title = stringResource(R.string.shelf_split_custom_title),
            desc = stringResource(R.string.shelf_split_custom_desc)
        ) { splitMode = SplitMode.CUSTOM }

        if (splitMode == SplitMode.CUSTOM) {
            Spacer(Modifier.height(8.dp))
            InkTextField(
                value = customPattern,
                onValueChange = { customPattern = it },
                placeholder = stringResource(R.string.shelf_split_custom_placeholder)
            )
        }

        if (fileCount > 1 && mode == ImportMode.MERGE) {
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                checked = splitMode == SplitMode.PER_FILE,
                title = stringResource(R.string.shelf_split_per_file_title),
                desc = stringResource(R.string.shelf_split_per_file_desc)
            ) { splitMode = SplitMode.PER_FILE }
        }

        Spacer(Modifier.height(14.dp))
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
