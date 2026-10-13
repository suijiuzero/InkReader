@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.inkreader.ui.shelf

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkreader.AppContainer
import com.inkreader.R
import com.inkreader.data.model.Book
import com.inkreader.data.model.ShelfView
import com.inkreader.ui.component.BookCover
import com.inkreader.ui.component.CardShape
import com.inkreader.ui.component.EmptyState
import com.inkreader.ui.component.InkIcon
import com.inkreader.ui.component.InkIconButton
import com.inkreader.ui.component.InkProgress
import com.inkreader.ui.component.InkTextButton
import com.inkreader.ui.component.dangerColor
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette
import kotlinx.coroutines.launch

/**
 * 分组详情：一行三本的网格（不设上限），点书直接读。
 * 右上角 ⋮：编辑分组（多选移出 / 移动到别的分组）、重命名、添加书籍、解散分组。
 * 列表模式下列表呈现，随书架的视图设置。
 */
@Composable
fun GroupScreen(
    container: AppContainer,
    groupId: Long,
    onBack: () -> Unit,
    onOpenBook: (Long, Int, Int) -> Unit,
    onOpenDetail: (Long) -> Unit
) {
    val lib = container.library
    val p = LocalInkPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val group = lib.groups.firstOrNull { it.id == groupId }
    val books = lib.booksOf(groupId)
    val selected = remember { mutableStateListOf<Long>() }

    var showMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showMovePicker by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var confirmDeleteBooks by remember { mutableStateOf(false) }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(Modifier.fillMaxSize().background(p.background)) {
        // 顶栏
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = topInset)
                .height(56.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InkIconButton(R.drawable.ic_arrow_back, stringResource(R.string.action_back_shelf), onBack, tint = p.textPrimary)
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    text = if (editing) pluralStringResource(R.plurals.group_selected_books, selected.size, selected.size) else (group?.name ?: stringResource(R.string.group_fallback_name)),
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = p.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = pluralStringResource(R.plurals.plural_books, books.size, books.size),
                    style = InkType.Caption.copy(fontSize = 11.sp),
                    color = p.textFaint
                )
            }
            if (editing) {
                InkTextButton(stringResource(R.string.action_done), color = p.accent) { editing = false; selected.clear() }
            } else {
                Box {
                    InkIconButton(R.drawable.ic_more, stringResource(R.string.action_more), { showMenu = true }, tint = p.textSecondary)
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = p.surfaceHigh,
                        tonalElevation = 0.dp
                    ) {
                        DropdownMenuItem(
                            leadingIcon = { InkIcon(R.drawable.ic_check, null, tint = p.textSecondary, size = 18) },
                            text = { Text(stringResource(R.string.group_edit), color = p.textPrimary, fontSize = 14.sp) },
                            onClick = { showMenu = false; editing = true; selected.clear() }
                        )
                        DropdownMenuItem(
                            leadingIcon = { InkIcon(R.drawable.ic_edit, null, tint = p.textSecondary, size = 18) },
                            text = { Text(stringResource(R.string.action_rename), color = p.textPrimary, fontSize = 14.sp) },
                            onClick = { showMenu = false; showRename = true }
                        )
                        DropdownMenuItem(
                            leadingIcon = { InkIcon(R.drawable.ic_add, null, tint = p.textSecondary, size = 18) },
                            text = { Text(stringResource(R.string.group_add_books), color = p.textPrimary, fontSize = 14.sp) },
                            onClick = { showMenu = false; showAdd = true }
                        )
                        DropdownMenuItem(
                            leadingIcon = { InkIcon(R.drawable.ic_delete, null, tint = dangerColor(), size = 18) },
                            text = { Text(stringResource(R.string.group_disband), color = dangerColor(), fontSize = 14.sp) },
                            onClick = { showMenu = false; showDelete = true }
                        )
                    }
                }
            }
        }

        if (books.isEmpty()) {
            EmptyState(
                iconRes = R.drawable.ic_folder,
                title = stringResource(R.string.group_empty_title),
                hint = stringResource(R.string.group_empty_hint)
            )
        } else if (lib.view == ShelfView.GRID) {
            // 一行三本（固定三列，不随屏幕宽度变化）。
            // 注意用 weight(1f) 而不是 fillMaxSize：底部还要放「编辑分组」的操作条，
            // 用 fillMaxSize 会把操作条挤出屏幕（之前就是这个 bug）。
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                items(books, key = { it.id }) { b ->
                    GroupBookCard(
                        book = b,
                        selected = selected.contains(b.id),
                        editing = editing,
                        onClick = {
                            if (editing) {
                                if (selected.contains(b.id)) selected.remove(b.id) else selected.add(b.id)
                            } else if (selected.isEmpty()) {
                                onOpenBook(b.id, b.lastChapterIndex, b.lastCharOffset)
                            }
                        },
                        onLongClick = {
                            editing = true
                            if (!selected.contains(b.id)) selected.add(b.id)
                        }
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                items(books, key = { it.id }) { b ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(CardShape)
                            .background(p.surface)
                            .border(1.dp, if (selected.contains(b.id)) p.accent else p.divider, CardShape)
                            .combinedClickable(
                                onClick = {
                                    if (editing) {
                                        if (selected.contains(b.id)) selected.remove(b.id) else selected.add(b.id)
                                    } else {
                                        onOpenBook(b.id, b.lastChapterIndex, b.lastCharOffset)
                                    }
                                },
                                onLongClick = {
                                    editing = true
                                    if (!selected.contains(b.id)) selected.add(b.id)
                                }
                            )
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BookCover(book = b, modifier = Modifier.width(46.dp).aspectRatio(0.70f))
                        Spacer(Modifier.width(12.dp))
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
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = pluralStringResource(R.plurals.group_book_meta, b.totalChapters, b.totalChapters, (b.displayProgress * 100).toInt()),
                                style = InkType.Caption.copy(fontSize = 11.sp),
                                color = p.textFaint
                            )
                        }
                        InkIconButton(R.drawable.ic_more, stringResource(R.string.action_detail), { onOpenDetail(b.id) }, size = 19)
                    }
                }
            }
        }

        // 编辑分组时的底部操作条
        if (editing) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(p.surfaceHigh)
                    .padding(bottom = bottomPad)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pluralStringResource(R.plurals.group_selected_books, selected.size, selected.size),
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 14.sp,
                        color = p.textPrimary,
                        modifier = Modifier.weight(1f).padding(start = 6.dp)
                    )
                    InkTextButton(stringResource(R.string.action_cancel), color = p.textSecondary) { editing = false; selected.clear() }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InkTextButton(stringResource(R.string.group_remove), color = dangerColor()) {
                        val ids = selected.toList()
                        editing = false
                        selected.clear()
                        scope.launch {
                            container.repo.moveBooksToGroup(ids, 0L)
                            lib.refresh()
                            Toast.makeText(ctx, ctx.getString(R.string.group_toast_removed), Toast.LENGTH_SHORT).show()
                        }
                    }
                    InkTextButton(stringResource(R.string.group_move_other), color = p.accent) { showMovePicker = true }
                    InkTextButton(stringResource(R.string.action_delete), color = dangerColor()) { confirmDeleteBooks = true }
                }
            }
        }
    }

    if (showRename && group != null) {
        GroupNameDialog(
            title = stringResource(R.string.group_rename_title),
            initial = group.name,
            confirmText = stringResource(R.string.action_save),
            onDismiss = { showRename = false }
        ) { name ->
            showRename = false
            scope.launch {
                container.repo.renameGroup(groupId, name)
                lib.refresh()
            }
        }
    }

    if (showMovePicker) {
        GroupPickerDialog(
            groups = lib.groups.filter { it.id != groupId },
            count = selected.size,
            onDismiss = { showMovePicker = false },
            onCreateNew = { showMovePicker = false }
        ) { target ->
            val ids = selected.toList()
            showMovePicker = false
            editing = false
            selected.clear()
            scope.launch {
                container.repo.moveBooksToGroup(ids, target)
                lib.refresh()
                Toast.makeText(ctx, ctx.getString(R.string.group_toast_moved), Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showAdd) {
        AddBooksDialog(
            books = lib.books.filter { it.groupId != groupId },
            onDismiss = { showAdd = false }
        ) { ids ->
            showAdd = false
            scope.launch {
                container.repo.moveBooksToGroup(ids, groupId)
                lib.refresh()
                Toast.makeText(ctx, ctx.resources.getQuantityString(R.plurals.group_toast_added, ids.size, ids.size), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 多选删除书籍（会真删书，所以要二次确认）
    if (confirmDeleteBooks) {
        val ids = selected.toList()
        AlertDialog(
            onDismissRequest = { confirmDeleteBooks = false },
            containerColor = p.surfaceHigh,
            title = { Text(pluralStringResource(R.plurals.group_delete_books_title, ids.size, ids.size), color = p.textPrimary, fontSize = 17.sp) },
            text = {
                Text(
                    stringResource(R.string.group_delete_books_message),
                    color = p.textSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                InkTextButton(stringResource(R.string.action_delete), color = dangerColor()) {
                    confirmDeleteBooks = false
                    editing = false
                    selected.clear()
                    ids.forEach { lib.delete(it) }
                }
            },
            dismissButton = {
                InkTextButton(stringResource(R.string.action_cancel), color = p.textSecondary) { confirmDeleteBooks = false }
            }
        )
    }

    if (showDelete && group != null) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            containerColor = p.surfaceHigh,
            title = { Text(stringResource(R.string.group_disband_title), color = p.textPrimary, fontSize = 17.sp) },
            text = {
                Text(
                    pluralStringResource(R.plurals.group_disband_message, books.size, group.name, books.size),
                    color = p.textSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                InkTextButton(stringResource(R.string.group_disband_button), color = dangerColor()) {
                    showDelete = false
                    scope.launch {
                        container.repo.deleteGroup(groupId)
                        lib.refresh()
                        onBack()
                    }
                }
            },
            dismissButton = {
                InkTextButton(stringResource(R.string.action_cancel), color = p.textSecondary) { showDelete = false }
            }
        )
    }
}

@Composable
private fun GroupBookCard(
    book: Book,
    selected: Boolean,
    editing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val p = LocalInkPalette.current
    Column(
        Modifier
            .clip(CardShape)
            .background(p.surface)
            .border(1.dp, if (selected) p.accent else p.divider, CardShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(5.dp)
    ) {
        Box {
            BookCover(book = book, modifier = Modifier.fillMaxWidth().aspectRatio(0.70f))
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(17.dp)
                        .clip(CircleShape)
                        .background(p.accent),
                    contentAlignment = Alignment.Center
                ) {
                    InkIcon(R.drawable.ic_check, null, tint = Color.White, size = 10)
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = book.title,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            color = p.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        Spacer(Modifier.height(5.dp))
        InkProgress(book.displayProgress, Modifier.fillMaxWidth().padding(horizontal = 2.dp))
        Spacer(Modifier.height(3.dp))
        Text(
            text = stringResource(R.string.label_read_percent, (book.displayProgress * 100).toInt()),
            style = InkType.Caption.copy(fontSize = 9.5.sp),
            color = p.textFaint,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        Spacer(Modifier.height(4.dp))
    }
}

/** 添加书籍：从「不在本组」的书里多选 */
@Composable
private fun AddBooksDialog(
    books: List<Book>,
    onDismiss: () -> Unit,
    onConfirm: (List<Long>) -> Unit
) {
    val p = LocalInkPalette.current
    val picked = remember { mutableStateListOf<Long>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surfaceHigh,
        title = { Text(stringResource(R.string.group_add_dialog_title), color = p.textPrimary, fontSize = 17.sp) },
        text = {
            if (books.isEmpty()) {
                Text(stringResource(R.string.group_add_dialog_empty), color = p.textSecondary, fontSize = 14.sp)
            } else {
                LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                    items(books, key = { it.id }) { b ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (picked.contains(b.id)) picked.remove(b.id) else picked.add(b.id)
                                    },
                                    onLongClick = {
                                        if (picked.contains(b.id)) picked.remove(b.id) else picked.add(b.id)
                                    }
                                )
                                .padding(vertical = 8.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BookCover(book = b, modifier = Modifier.width(30.dp).aspectRatio(0.70f))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = b.title,
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 14.sp,
                                color = p.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (picked.contains(b.id)) {
                                InkIcon(R.drawable.ic_check, null, tint = p.accent, size = 17)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            InkTextButton(stringResource(R.string.group_add_confirm, picked.size), color = p.accent) { onConfirm(picked.toList()) }
        },
        dismissButton = {
            InkTextButton(stringResource(R.string.action_cancel), color = p.textSecondary, onClick = onDismiss)
        }
    )
}
