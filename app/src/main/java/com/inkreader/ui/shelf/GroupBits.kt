@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.inkreader.ui.shelf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkreader.R
import com.inkreader.data.model.Book
import com.inkreader.data.model.BookGroup
import com.inkreader.ui.component.BookCover
import com.inkreader.ui.component.CardShape
import com.inkreader.ui.component.InkDialogShell
import com.inkreader.ui.component.InkIcon
import com.inkreader.ui.component.InkTextField
import com.inkreader.ui.component.InkTextButton
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette

/**
 * 分组封面：最多摊 4 本（2×2）；不足 4 本时剩下的格子留空底色。
 * 书超过 4 本也只显示前 4 本 —— 分组卡片是「一瞥」，不是清单。
 */
@Composable
fun GroupCover(books: List<Book>, modifier: Modifier = Modifier) {
    val p = LocalInkPalette.current
    val shown = books.take(4)
    Column(
        modifier
            .clip(CardShape)
            .background(p.surfaceHigh)
    ) {
        repeat(2) { row ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                repeat(2) { col ->
                    val idx = row * 2 + col
                    val b = shown.getOrNull(idx)
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        if (b != null) {
                            BookCover(book = b, modifier = Modifier.fillMaxSize())
                        } else {
                            Box(Modifier.fillMaxSize().background(p.surfaceHigh))
                        }
                    }
                    if (col == 0) Spacer(Modifier.width(2.dp))
                }
            }
            if (row == 0) Spacer(Modifier.height(2.dp))
        }
    }
}

/** 网格里的分组卡片：尺寸和书籍卡片一致 */
@Composable
fun GroupCard(
    group: BookGroup,
    books: List<Book>,
    onClick: () -> Unit
) {
    val p = LocalInkPalette.current
    Column(
        Modifier
            .clip(CardShape)
            .background(p.surface)
            .border(1.dp, p.divider, CardShape)
            .combinedClickable(onClick = onClick, onLongClick = onClick)
            .padding(6.dp)
    ) {
        GroupCover(books, Modifier.fillMaxWidth().aspectRatio(0.70f))
        Column(Modifier.padding(start = 3.dp, end = 3.dp, top = 8.dp, bottom = 3.dp)) {
            Text(
                text = group.name,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 12.5.sp,
                color = p.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = pluralStringResource(R.plurals.shelf_group_books_count, books.size, books.size),
                style = InkType.Caption.copy(fontSize = 10.sp),
                color = p.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 列表里的分组行：左侧小封面拼图 + 名字 + 本数 */
@Composable
fun GroupRow(group: BookGroup, books: List<Book>, onClick: () -> Unit) {
    val p = LocalInkPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(p.surface)
            .border(1.dp, p.divider, CardShape)
            .combinedClickable(onClick = onClick, onLongClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GroupCover(books, Modifier.width(46.dp).aspectRatio(0.70f))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = group.name,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = p.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = pluralStringResource(R.plurals.shelf_group_row_hint, books.size, books.size),
                style = InkType.Caption.copy(fontSize = 11.sp),
                color = p.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        InkIcon(R.drawable.ic_right, null, tint = p.textFaint, size = 16)
    }
}

/** 新建 / 重命名分组的输入框 */
@Composable
fun GroupNameDialog(
    title: String,
    initial: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    InkDialogShell(title = title, onDismiss = onDismiss) {
        InkTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = stringResource(R.string.shelf_group_name_hint),
            singleLine = true
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            InkTextButton(stringResource(R.string.action_cancel), color = LocalInkPalette.current.textSecondary, onClick = onDismiss)
            Spacer(Modifier.width(6.dp))
            InkTextButton(confirmText, color = LocalInkPalette.current.accent) {
                onConfirm(name.trim())
            }
        }
    }
}

/** 选择目标分组（用于「移动到分组」）；list 里不含「当前所在分组」 */
@Composable
fun GroupPickerDialog(
    groups: List<BookGroup>,
    count: Int,
    onDismiss: () -> Unit,
    onCreateNew: () -> Unit,
    onPick: (Long) -> Unit
) {
    val p = LocalInkPalette.current
    InkDialogShell(title = pluralStringResource(R.plurals.shelf_move_to_group, count, count), onDismiss = onDismiss) {
        if (groups.isEmpty()) {
            Text(
                text = stringResource(R.string.shelf_no_groups),
                style = InkType.Caption.copy(fontSize = 12.sp),
                color = p.textFaint
            )
            Spacer(Modifier.height(12.dp))
        } else {
            Column(Modifier.fillMaxWidth()) {
                groups.forEach { g ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .combinedClickable(onClick = { onPick(g.id) }, onLongClick = { onPick(g.id) })
                            .padding(vertical = 11.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        InkIcon(R.drawable.ic_folder, null, tint = p.textSecondary, size = 17)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = g.name,
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 14.sp,
                            color = p.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            InkTextButton(stringResource(R.string.shelf_new_group), color = p.accent, onClick = onCreateNew)
            Spacer(Modifier.width(6.dp))
            InkTextButton(stringResource(R.string.action_cancel), color = p.textSecondary, onClick = onDismiss)
        }
    }
}

/** 一小块透明占位，避免 import 未使用告警 */
