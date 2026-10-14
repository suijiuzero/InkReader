package com.inkreader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkreader.R
import com.inkreader.ui.component.InkChip
import com.inkreader.ui.component.InkIcon
import com.inkreader.ui.component.InkIconButton
import com.inkreader.ui.component.InkTextButton
import com.inkreader.ui.component.InkTextField
import com.inkreader.ui.theme.HighlightColors
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette
import kotlin.math.roundToInt

private val BarShape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
private val BarShapeTop = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

// ==================== 顶栏 ====================

@Composable
fun ReaderTopBar(
    bookTitle: String,
    chapterTitle: String,
    onBack: () -> Unit,
    onToc: () -> Unit,
    onMore: () -> Unit,
    onTitleClick: () -> Unit
) {
    val p = LocalInkPalette.current
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.background)
            .padding(top = topInset)
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InkIconButton(R.drawable.ic_arrow_back, stringResource(R.string.action_back_home), onBack, tint = p.textPrimary)
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onTitleClick() }
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = chapterTitle,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = p.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = bookTitle,
                    style = InkType.Caption.copy(fontSize = 11.sp),
                    color = p.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            InkIconButton(R.drawable.ic_toc, stringResource(R.string.reader_toc), onToc, tint = p.textSecondary)
            InkIconButton(R.drawable.ic_more, stringResource(R.string.reader_chapter_manage), onMore, tint = p.textSecondary)
        }
    }
}

// ==================== 底栏 ====================

@Composable
fun ReaderBottomBar(
    chapterIndex: Int,
    chapterCount: Int,
    chapterTitleOf: (Int) -> String,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeekChapter: (Int) -> Unit,
    onToc: () -> Unit,
    onTheme: () -> Unit,
    onSettings: () -> Unit,
    onTts: () -> Unit,
    onSearch: () -> Unit
) {
    val p = LocalInkPalette.current
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(BarShapeTop)
            .background(p.background)
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))
        ChapterScrubber(
            chapterIndex = chapterIndex,
            chapterCount = chapterCount,
            titleOf = chapterTitleOf,
            onPrev = onPrevChapter,
            onNext = onNextChapter,
            onSeek = onSeekChapter
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = bottomInset + 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BarItem(R.drawable.ic_toc, stringResource(R.string.reader_toc), Modifier.weight(1f), onToc)
            BarItem(R.drawable.ic_theme, stringResource(R.string.reader_theme), Modifier.weight(1f), onTheme)
            BarItem(R.drawable.ic_headphones, stringResource(R.string.reader_read_aloud), Modifier.weight(1f), onTts)
            BarItem(R.drawable.ic_search, stringResource(R.string.action_search), Modifier.weight(1f), onSearch)
            BarItem(R.drawable.ic_settings, stringResource(R.string.action_settings), Modifier.weight(1f), onSettings)
        }
    }
}

@Composable
private fun ChapterScrubber(
    chapterIndex: Int,
    chapterCount: Int,
    titleOf: (Int) -> String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Int) -> Unit
) {
    val p = LocalInkPalette.current
    val density = LocalDensity.current
    var trackWidth by remember { mutableFloatStateOf(0f) }
    var dragChapter by remember { mutableStateOf<Int?>(null) }

    val safeCount = chapterCount.coerceAtLeast(1)
    val shownIndex = dragChapter ?: chapterIndex
    val fraction = if (safeCount <= 1) 0f else shownIndex.toFloat() / (safeCount - 1)

    fun chapterAt(x: Float): Int {
        if (trackWidth <= 0f || safeCount <= 1) return chapterIndex
        val f = (x / trackWidth).coerceIn(0f, 1f)
        return (f * (safeCount - 1)).roundToInt().coerceIn(0, safeCount - 1)
    }

    Column(Modifier.fillMaxWidth()) {
        AnimatedVisibility(visible = dragChapter != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.reader_chapter_preview, shownIndex + 1, titleOf(shownIndex)),
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 11.5.sp,
                    color = p.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(p.surfaceHigh)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChapterStepButton(stringResource(R.string.reader_prev_chapter), enabled = chapterIndex > 0, onClick = onPrev)
            Box(
                Modifier
                    .weight(1f)
                    .height(34.dp)
                    .onSizeChanged { trackWidth = it.width.toFloat() }
                    .pointerInput(safeCount, chapterIndex) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            dragChapter = chapterAt(down.position.x)
                            drag(down.id) { change ->
                                change.consume()
                                dragChapter = chapterAt(change.position.x)
                            }
                            val target = dragChapter
                            dragChapter = null
                            if (target != null && target != chapterIndex) onSeek(target)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxWidth().height(34.dp)) {
                    val cy = size.height / 2f
                    val knobR = with(density) { 6.dp.toPx() }
                    val barH = with(density) { 3.dp.toPx() }
                    val startX = knobR
                    val endX = size.width - knobR
                    val usable = (endX - startX).coerceAtLeast(1f)
                    drawLine(
                        color = p.divider,
                        start = Offset(startX, cy),
                        end = Offset(endX, cy),
                        strokeWidth = barH,
                        cap = StrokeCap.Round
                    )
                    val x = startX + usable * fraction
                    drawLine(
                        color = p.accent,
                        start = Offset(startX, cy),
                        end = Offset(x, cy),
                        strokeWidth = barH,
                        cap = StrokeCap.Round
                    )
                    drawCircle(
                        color = p.accent,
                        radius = if (dragChapter != null) knobR * 1.5f else knobR,
                        center = Offset(x, cy)
                    )
                }
            }
            ChapterStepButton(stringResource(R.string.reader_next_chapter), enabled = chapterIndex < safeCount - 1, onClick = onNext)
        }
    }
}

@Composable
private fun ChapterStepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalInkPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.SansSerif,
            fontSize = 12.5.sp,
            color = if (enabled) p.textSecondary else p.textFaint.copy(alpha = 0.5f),
            maxLines = 1
        )
    }
}

@Composable
private fun BarItem(icon: Int, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalInkPalette.current
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        InkIcon(icon, label, tint = p.textSecondary, size = 21)
        Spacer(Modifier.height(5.dp))
        Text(
            text = label,
            style = InkType.Caption.copy(fontSize = 10.5.sp),
            color = p.textFaint
        )
    }
}

// ==================== 选中操作条（含「从此处拆分为下一章」） ====================

@Composable
fun SelectionBar(
    text: String,
    tagged: Boolean,
    activeColor: Int?,
    onExpandSentence: () -> Unit,
    onExpandParagraph: () -> Unit,
    onTag: () -> Unit,
    onReadAloud: () -> Unit,
    onHighlight: (Int) -> Unit,
    onNote: (String) -> Unit,
    onCancel: () -> Unit,
    onSplitHere: () -> Unit = {}
) {
    val p = LocalInkPalette.current
    var noteDialog by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(BarShape)
            .background(p.surfaceHigh)
            .padding(top = topInset)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.width(3.dp).height(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(p.accent)
            )
            Spacer(Modifier.width(11.dp))
            Text(
                text = text.ifBlank { stringResource(R.string.reader_selected) },
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
                color = p.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            InkChip(text = stringResource(R.string.reader_expand_sentence), onClick = onExpandSentence)
            Spacer(Modifier.width(5.dp))
            InkChip(text = stringResource(R.string.reader_expand_paragraph), onClick = onExpandParagraph)
            Spacer(Modifier.width(5.dp))
            InkChip(text = stringResource(R.string.reader_split_from_here), onClick = onSplitHere)
            InkIconButton(R.drawable.ic_close, stringResource(R.string.reader_cancel_selection), onCancel, tint = p.textFaint, size = 18)
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))

        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                HighlightColors.forEachIndexed { i, c ->
                    val on = activeColor == i
                    Box(
                        Modifier
                            .size(25.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(
                                width = if (on) 2.dp else 1.dp,
                                color = if (on) p.accent else p.divider,
                                shape = CircleShape
                            )
                            .clickable { onHighlight(i) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (on) InkIcon(R.drawable.ic_close, stringResource(R.string.reader_remove_highlight), tint = Color.White, size = 12)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                InkIconButton(
                    res = if (tagged) R.drawable.ic_bookmark else R.drawable.ic_bookmark_outline,
                    contentDescription = if (tagged) stringResource(R.string.reader_untag_range) else stringResource(R.string.reader_tag_range),
                    onClick = onTag,
                    tint = if (tagged) p.accent else p.textSecondary,
                    size = 19
                )
                InkIconButton(R.drawable.ic_headphones, stringResource(R.string.reader_read_from_here), onReadAloud, tint = p.textSecondary, size = 19)
                InkIconButton(R.drawable.ic_note, stringResource(R.string.reader_write_note), { noteDialog = true }, tint = p.textSecondary, size = 19)
            }
        }
    }

    if (noteDialog) {
        NoteDialog(
            quote = text,
            initial = noteText,
            onDismiss = { noteDialog = false },
            onConfirm = {
                noteText = it
                noteDialog = false
                onNote(it)
            }
        )
    }
}

@Composable
private fun NoteDialog(
    quote: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val p = LocalInkPalette.current
    var value by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surfaceHigh,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                stringResource(R.string.reader_write_note),
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = p.textPrimary
            )
        },
        text = {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(p.background)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.reader_note_quote,
                            quote.take(60) + if (quote.length > 60) "…" else ""
                        ),
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                        color = p.textSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(12.dp))
                InkTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = stringResource(R.string.reader_note_placeholder),
                    singleLine = false,
                    minLines = 3
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    InkTextButton(stringResource(R.string.action_cancel), color = p.textSecondary) { onDismiss() }
                    InkTextButton(stringResource(R.string.action_save)) { onConfirm(value) }
                }
            }
        },
        confirmButton = {}
    )
}
