package com.inkreader.ui.marks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkreader.AppContainer
import com.inkreader.R
import com.inkreader.data.model.Mark
import com.inkreader.data.model.MarkType
import com.inkreader.ui.component.EmptyState
import com.inkreader.ui.component.InkIconButton
import com.inkreader.ui.component.InkLoading
import com.inkreader.ui.component.InkTextButton
import com.inkreader.ui.component.InkTopBar
import com.inkreader.ui.component.dangerColor
import com.inkreader.ui.theme.HighlightColors
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class MarkFilter {
    ALL, TAG, BOOKMARK, HIGHLIGHT, NOTE
}

/** 筛选分段的文案（随语言切换） */
@Composable
private fun filterLabel(filter: MarkFilter): String = when (filter) {
    MarkFilter.ALL -> stringResource(R.string.marks_type_all)
    MarkFilter.TAG -> stringResource(R.string.marks_type_tag)
    MarkFilter.BOOKMARK -> stringResource(R.string.marks_type_bookmark)
    MarkFilter.HIGHLIGHT -> stringResource(R.string.marks_type_highlight)
    MarkFilter.NOTE -> stringResource(R.string.marks_type_note)
}

/** 条目类型徽标的文案（随语言切换） */
@Composable
private fun markTypeLabel(type: MarkType): String = when (type) {
    MarkType.BOOKMARK -> stringResource(R.string.marks_type_bookmark)
    MarkType.TAG, MarkType.MARK -> stringResource(R.string.marks_type_tag)
    MarkType.HIGHLIGHT -> stringResource(R.string.marks_type_highlight)
    MarkType.NOTE -> stringResource(R.string.marks_type_note)
}

/** 卡片与控件的统一圆角 */
private val CardShape = RoundedCornerShape(12.dp)

/** 胶囊分段控件 / 徽标 */
private val PillShape = RoundedCornerShape(999.dp)

/** 笔记 / 书签 / 高亮汇总页，按章节聚合，点击跳回原文 */
@Composable
fun MarksScreen(
    container: AppContainer,
    bookId: Long,
    bookTitle: String,
    onBack: () -> Unit,
    onJump: (Int, Int) -> Unit
) {
    val p = LocalInkPalette.current
    val scope = rememberCoroutineScope()

    var marks by remember { mutableStateOf<List<Mark>>(emptyList()) }
    var chapterTitles by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf(MarkFilter.ALL) }

    fun reload() {
        scope.launch {
            marks = container.repo.marks(bookId)
            chapterTitles = container.repo.chapters(bookId).associate { it.indexInBook to it.title }
            loading = false
        }
    }
    LaunchedEffect(bookId) { reload() }

    val filtered = marks.filter {
        when (filter) {
            MarkFilter.ALL -> true
            MarkFilter.TAG -> it.type == MarkType.TAG || it.type == MarkType.MARK
            MarkFilter.BOOKMARK -> it.type == MarkType.BOOKMARK
            MarkFilter.HIGHLIGHT -> it.type == MarkType.HIGHLIGHT
            MarkFilter.NOTE -> it.type == MarkType.NOTE
        }
    }

    val bottomPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(Modifier.fillMaxSize().background(p.background)) {
        InkTopBar(
            title = stringResource(R.string.marks_title),
            subtitle = bookTitle,
            onBack = onBack
        )

        // 胶囊分段控件：整行一个浅底描边容器，内部每段等宽
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .clip(PillShape)
                .background(p.background)
                .border(1.dp, p.divider, PillShape)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            MarkFilter.values().forEach { f ->
                val on = filter == f
                val count = marks.count {
                    f == MarkFilter.ALL || it.type.name == f.name
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(PillShape)
                        .background(if (on) p.accentSoft else Color.Transparent)
                        .clickable { filter = f },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.marks_filter_count, filterLabel(f), count),
                        style = InkType.UiLabel.copy(
                            fontSize = 11.5.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        color = if (on) p.accent else p.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        when {
            loading -> InkLoading()
            filtered.isEmpty() -> EmptyState(
                iconRes = R.drawable.ic_bookmark_outline,
                title = when (filter) {
                    MarkFilter.ALL -> stringResource(R.string.marks_empty_all)
                    MarkFilter.TAG -> stringResource(R.string.marks_empty_tag)
                    MarkFilter.BOOKMARK -> stringResource(R.string.marks_empty_bookmark)
                    MarkFilter.HIGHLIGHT -> stringResource(R.string.marks_empty_highlight)
                    MarkFilter.NOTE -> stringResource(R.string.marks_empty_note)
                },
                hint = stringResource(R.string.marks_empty_hint)
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 4.dp,
                    bottom = bottomPad + 20.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filtered, key = { it.id }) { m ->
                    MarkRow(
                        mark = m,
                        chapterTitle = chapterTitles[m.chapterIndex]
                            ?: stringResource(R.string.label_chapter_n, m.chapterIndex + 1),
                        onOpen = { onJump(m.chapterIndex, m.startOffset) },
                        onDelete = {
                            scope.launch {
                                container.repo.deleteMark(m.id)
                                reload()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkRow(mark: Mark, chapterTitle: String, onOpen: () -> Unit, onDelete: () -> Unit) {
    val p = LocalInkPalette.current
    var confirmDelete by remember { mutableStateOf(false) }
    val barColor = if (mark.type == MarkType.HIGHLIGHT || mark.type == MarkType.NOTE)
        HighlightColors[mark.color.mod(HighlightColors.size)] else p.accent

    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(p.surface)
            .border(1.dp, p.divider, CardShape)
            .clickable { onOpen() }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        // 第一行：章节名 + 类型徽标
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = chapterTitle,
                style = InkType.Caption.copy(fontSize = 11.5.sp),
                color = p.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .clip(PillShape)
                    .background(p.accentSoft)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = markTypeLabel(mark.type),
                    style = InkType.Caption.copy(fontSize = 11.sp),
                    color = p.accent
                )
            }
        }

        if (mark.snippet.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            // 高亮条目：左侧一条与文字等高、随文字换行自适应的色条
            val snippetModifier = if (mark.type == MarkType.HIGHLIGHT || mark.type == MarkType.TAG) {
                Modifier
                    .drawBehind {
                        val w = 3.dp.toPx()
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(0f, 0f),
                            size = Size(w, size.height),
                            cornerRadius = CornerRadius(w / 2f)
                        )
                    }
                    .padding(start = 13.dp)
            } else {
                Modifier
            }
            Text(
                text = mark.snippet,
                style = InkType.Body.copy(fontSize = 13.5.sp, lineHeight = 21.sp),
                color = p.textPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = snippetModifier
            )
        }

        if (mark.note.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            val noteLabel = stringResource(R.string.marks_type_note)
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = p.accent)) { append(noteLabel) }
                    append("  ")
                    append(mark.note)
                },
                style = InkType.Caption,
                color = p.textSecondary
            )
        }

        if (mark.type == MarkType.BOOKMARK && mark.snippet.isBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(
                    R.string.marks_bookmark_position,
                    mark.chapterIndex + 1,
                    mark.startOffset
                ),
                style = InkType.Caption.copy(fontSize = 11.sp),
                color = p.textFaint
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(mark.createdAt)),
                style = InkType.Caption.copy(fontSize = 11.sp),
                color = p.textFaint,
                modifier = Modifier.weight(1f)
            )
            InkIconButton(
                res = R.drawable.ic_delete,
                contentDescription = stringResource(R.string.marks_delete_title),
                onClick = { confirmDelete = true },
                tint = p.textFaint,
                size = 16
            )
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
                    stringResource(R.string.marks_delete_title),
                    style = InkType.Title.copy(fontSize = 17.sp),
                    color = p.textPrimary
                )
            },
            text = {
                Text(
                    stringResource(R.string.marks_delete_message),
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
            dismissButton = {
                InkTextButton(stringResource(R.string.action_cancel), color = p.textSecondary) { confirmDelete = false }
            }
        )
    }
}
