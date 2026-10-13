@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.inkreader.ui.shelf

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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkreader.AppContainer
import com.inkreader.R
import com.inkreader.data.model.Book
import com.inkreader.ui.component.BookCover
import com.inkreader.ui.component.CardShape
import com.inkreader.ui.component.EmptyState
import com.inkreader.ui.component.InkIcon
import com.inkreader.ui.component.InkIconButton
import com.inkreader.ui.component.InkProgress
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette

/**
 * 搜索书籍：输入书名（也匹配作者）→ 结果以网格卡片呈现，点卡片直接开始读。
 * 只在内存里过滤 —— 书架本来就一次性读完所有书，不用为搜索再查一次库。
 */
@Composable
fun SearchScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenBook: (Long, Int, Int) -> Unit,
    onOpenDetail: (Long) -> Unit
) {
    val lib = container.library
    val p = LocalInkPalette.current
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    val key = query.trim()
    val results = remember(key, lib.books) {
        if (key.isEmpty()) {
            emptyList()
        } else {
            lib.books.filter {
                it.title.contains(key, ignoreCase = true) ||
                    it.author.contains(key, ignoreCase = true)
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
    }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(Modifier.fillMaxSize().background(p.background)) {
        // 顶栏：返回 + 搜索框
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = topInset)
                .height(56.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InkIconButton(R.drawable.ic_arrow_back, stringResource(R.string.action_back), onBack, tint = p.textPrimary)
            Row(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.surfaceHigh)
                    .padding(horizontal = 12.dp)
                    .height(40.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InkIcon(R.drawable.ic_search, null, tint = p.textFaint, size = 17)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(stringResource(R.string.search_placeholder), style = InkType.Caption.copy(fontSize = 13.sp), color = p.textFaint)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 14.sp,
                            color = p.textPrimary
                        ),
                        cursorBrush = SolidColor(p.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    )
                }
                if (query.isNotEmpty()) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .combinedClickable(onClick = { query = "" }, onLongClick = { query = "" })
                            .padding(3.dp)
                    ) {
                        InkIcon(R.drawable.ic_close, stringResource(R.string.search_clear), tint = p.textFaint, size = 14)
                    }
                }
            }
        }

        when {
            key.isEmpty() -> EmptyState(
                iconRes = R.drawable.ic_search,
                title = stringResource(R.string.search_empty_title),
                hint = stringResource(R.string.search_empty_hint)
            )

            results.isEmpty() -> EmptyState(
                iconRes = R.drawable.ic_search,
                title = stringResource(R.string.search_no_result, key),
                hint = stringResource(R.string.search_no_result_hint)
            )

            else -> {
                Text(
                    text = pluralStringResource(R.plurals.search_result_count, results.size, results.size),
                    style = InkType.Caption.copy(fontSize = 11.sp),
                    color = p.textFaint,
                    modifier = Modifier.padding(start = 18.dp, top = 2.dp, bottom = 4.dp)
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 92.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPad + 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    items(results, key = { it.id }) { b ->
                        SearchResultCard(
                            book = b,
                            onClick = { onOpenBook(b.id, b.lastChapterIndex, b.lastCharOffset) },
                            onLongClick = { onOpenDetail(b.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(book: Book, onClick: () -> Unit, onLongClick: () -> Unit) {
    val p = LocalInkPalette.current
    Column(
        Modifier
            .clip(CardShape)
            .background(p.surface)
            .border(1.dp, p.divider, CardShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp)
    ) {
        BookCover(book = book, modifier = Modifier.fillMaxWidth().aspectRatio(0.70f))
        Column(Modifier.padding(start = 3.dp, end = 3.dp, top = 8.dp, bottom = 3.dp)) {
            Text(
                text = book.title,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 12.5.sp,
                color = p.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(7.dp))
            InkProgress(book.displayProgress, Modifier.fillMaxWidth())
            Spacer(Modifier.height(5.dp))
            Text(
                text = pluralStringResource(R.plurals.search_book_meta, book.totalChapters, book.totalChapters, (book.displayProgress * 100).toInt()),
                style = InkType.Caption.copy(fontSize = 10.sp),
                color = p.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
