package com.inkreader.ui.reader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.inkreader.R
import com.inkreader.data.model.Chapter
import com.inkreader.data.model.FontChoice
import com.inkreader.data.model.Mark
import com.inkreader.data.model.PageAnimation
import com.inkreader.data.model.PageMode
import com.inkreader.data.model.ReaderPrefs
import com.inkreader.data.model.ReaderTheme
import com.inkreader.data.model.SearchHit
import com.inkreader.parse.ChapterSplitter
import com.inkreader.tts.SleepMode
import com.inkreader.tts.TtsState
import com.inkreader.ui.component.InkChip
import com.inkreader.ui.component.InkIcon
import com.inkreader.ui.component.InkIconButton
import com.inkreader.ui.component.InkProgress
import com.inkreader.ui.component.InkTextButton
import com.inkreader.ui.component.InkTextField
import com.inkreader.ui.component.SectionLabel
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette
import com.inkreader.ui.theme.paletteFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val SheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
private val CardShape = RoundedCornerShape(12.dp)

/** 五个阅读主题的展示顺序与中文名 */
val ReaderThemes: List<Pair<ReaderTheme, Int>> = listOf(
    ReaderTheme.MIST to R.string.sheets_theme_mist,
    ReaderTheme.PAPER to R.string.sheets_theme_paper,
    ReaderTheme.GREEN to R.string.sheets_theme_green,
    ReaderTheme.INK to R.string.sheets_theme_ink,
    ReaderTheme.NIGHT to R.string.sheets_theme_night
)

/** 目录抽屉的两个页签 */
private enum class TocTab { CHAPTERS, NOTES }

/** 二级目录中的「卷」分组结构 */
private data class VolumeBucket(
    val volumeTitle: String,
    val items: List<Pair<Int, Chapter>>
)

// ==================== 通用浮层外壳 ====================

@Composable
fun InkSheet(
    onDismiss: () -> Unit,
    title: String,
    maxHeightFraction: Float = 0.84f,
    content: @Composable ColumnScope.() -> Unit
) {
    val p = LocalInkPalette.current
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(p.scrim).clickable { onDismiss() })
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(maxHeightFraction)
                .clip(SheetShape)
                .background(p.surface)
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(p.divider)
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = p.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                InkIconButton(R.drawable.ic_close, stringResource(R.string.action_close), onDismiss, tint = p.textFaint, size = 19)
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).padding(bottom = bottomInset),
                content = content
            )
        }
    }
}

// ==================== 小控件 ====================

@Composable
fun InkSlider(
    min: Float,
    max: Float,
    value: Float,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit
) {
    val p = LocalInkPalette.current
    Slider(
        value = value.coerceIn(min, max),
        onValueChange = onValueChange,
        valueRange = min..max,
        modifier = modifier.fillMaxWidth().padding(top = 2.dp),
        colors = SliderDefaults.colors(
            thumbColor = p.accent,
            activeTrackColor = p.accent,
            inactiveTrackColor = p.divider
        )
    )
}

/** 胶囊分段控件 */
@Composable
fun <T> InkSegment(
    options: List<Pair<T, String>>,
    selected: T,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit
) {
    val p = LocalInkPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(p.background)
            .border(1.dp, p.divider, RoundedCornerShape(999.dp))
            .padding(3.dp)
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (on) p.accentSoft else Color.Transparent)
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    fontSize = 13.5.sp,
                    color = if (on) p.accent else p.textSecondary
                )
            }
        }
    }
}

@Composable
fun InkSwitchRow(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalInkPalette.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontFamily = FontFamily.SansSerif,
                fontSize = 14.5.sp,
                color = p.textPrimary
            )
            if (hint != null) {
                Spacer(Modifier.height(2.dp))
                Text(hint, style = InkType.Caption.copy(fontSize = 11.sp), color = p.textFaint)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
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

/** 设置面板里的分组卡片 */
@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    val p = LocalInkPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(p.background)
            .border(1.dp, p.divider, CardShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        content = content
    )
}

// ==================== 目录抽屉（维度四：支持「卷 → 章」二级折叠目录与快速定位） ====================

@Composable
fun TocDrawer(
    session: ReaderSession,
    onDismiss: () -> Unit,
    onJump: (Int, Int) -> Unit
) {
    val p = LocalInkPalette.current
    val scope = rememberCoroutineScope()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val chapterListState = rememberLazyListState()
    val tagListState = rememberLazyListState()
    var tab by remember { mutableStateOf(TocTab.CHAPTERS) }
    val tags = session.pageTags()

    val defaultVolLabel = stringResource(R.string.sheets_toc_default_volume)

    // 构建「卷 → 章」二级分组：当至少存在一章带有明确卷名时启用二级折叠目录
    val volumeBuckets = remember(session.chapters, defaultVolLabel) {
        val rawHasVolumes = session.chapters.any {
            it.volume.isNotBlank() || ChapterSplitter.extractVolumeTitle(it.title) != null
        }
        if (!rawHasVolumes) {
            emptyList()
        } else {
            val out = ArrayList<VolumeBucket>()
            var curVol = ""
            var curList = ArrayList<Pair<Int, Chapter>>()
            for ((idx, ch) in session.chapters.withIndex()) {
                val detected = ch.volume.ifBlank { ChapterSplitter.extractVolumeTitle(ch.title).orEmpty() }
                if (detected.isNotBlank() && detected != curVol) {
                    if (curList.isNotEmpty()) {
                        out += VolumeBucket(curVol.ifBlank { defaultVolLabel }, curList)
                        curList = ArrayList()
                    }
                    curVol = detected
                }
                curList += idx to ch
            }
            if (curList.isNotEmpty()) {
                out += VolumeBucket(curVol.ifBlank { defaultVolLabel }, curList)
            }
            if (out.size >= 1) out else emptyList()
        }
    }

    // 记录每个分卷是否被折叠（默认展开所有分卷，且自动展开当前阅读章节所在的卷）
    val collapsedVolumes = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(session.chapterIndex, session.chapters.size, tab, volumeBuckets.size) {
        if (tab == TocTab.CHAPTERS && session.chapters.isNotEmpty()) {
            if (volumeBuckets.isEmpty()) {
                chapterListState.scrollToItem(
                    (session.chapterIndex - 3).coerceIn(0, session.chapters.lastIndex)
                )
            } else {
                val activeBucket = volumeBuckets.firstOrNull { b ->
                    b.items.any { it.first == session.chapterIndex }
                }
                if (activeBucket != null) {
                    // 展开当前章所在卷，并把它滚进视野（否则长目录永远停在最顶部）
                    collapsedVolumes[activeBucket.volumeTitle] = false
                    // 卷头也是列表项：按「已展开」的最终形态算出当前章的下标
                    var target = 0
                    var found = false
                    for (b in volumeBuckets) {
                        target += 1 // 卷头项
                        if (b === activeBucket) {
                            target += b.items.indexOfFirst { it.first == session.chapterIndex }
                                .coerceAtLeast(0)
                            found = true
                            break
                        }
                        if (collapsedVolumes[b.volumeTitle] != true) target += b.items.size
                    }
                    if (found) {
                        // 等列表按展开后的形态完成布局（项数到位）再滚，避免按折叠态下标滚动
                        snapshotFlow { chapterListState.layoutInfo.totalItemsCount }
                            .first { it > target }
                        chapterListState.scrollToItem((target - 3).coerceAtLeast(0))
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(p.scrim).clickable { onDismiss() })
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(316.dp)
                .clip(RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp))
                .background(p.surface)
                .padding(top = topInset)
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    text = session.book?.title ?: "",
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    color = p.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.sheets_toc_summary,
                            session.chapters.size,
                            session.chapters.size,
                            (session.bookProgress() * 100).toInt()
                        ),
                        style = InkType.Caption.copy(fontSize = 11.sp),
                        color = p.textFaint,
                        modifier = Modifier.weight(1f)
                    )
                    if (tab == TocTab.CHAPTERS && session.chapters.size > 8) {
                        val anyCollapsed = volumeBuckets.isNotEmpty() &&
                            volumeBuckets.any { collapsedVolumes[it.volumeTitle] == true }
                        if (volumeBuckets.isNotEmpty()) {
                            InkChip(
                                text = if (anyCollapsed) stringResource(R.string.sheets_toc_expand_all)
                                else stringResource(R.string.sheets_toc_collapse_all),
                                onClick = {
                                    val target = !anyCollapsed
                                    volumeBuckets.forEach { collapsedVolumes[it.volumeTitle] = target }
                                }
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        InkIconButton(
                            res = R.drawable.ic_up,
                            contentDescription = stringResource(R.string.sheets_toc_jump_top),
                            onClick = { scope.launch { chapterListState.scrollToItem(0) } },
                            tint = p.textSecondary,
                            size = 15
                        )
                        InkIconButton(
                            res = R.drawable.ic_down,
                            contentDescription = stringResource(R.string.sheets_toc_jump_bottom),
                            onClick = {
                                scope.launch {
                                    val totalItems = chapterListState.layoutInfo.totalItemsCount
                                    if (totalItems > 0) chapterListState.scrollToItem(totalItems - 1)
                                }
                            },
                            tint = p.textSecondary,
                            size = 15
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                InkProgress(session.bookProgress(), Modifier.fillMaxWidth())
            }

            // 页签：章节 / 笔记
            InkSegment(
                options = listOf(
                    TocTab.CHAPTERS to pluralStringResource(
                        R.plurals.sheets_tab_chapters, session.chapters.size, session.chapters.size
                    ),
                    TocTab.NOTES to pluralStringResource(
                        R.plurals.sheets_tab_notes, tags.size, tags.size
                    )
                ),
                selected = tab,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp)
            ) { tab = it }

            when (tab) {
                TocTab.CHAPTERS -> if (session.chapters.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.sheets_toc_empty_chapters), style = InkType.UiLabel, color = p.textFaint)
                    }
                } else if (volumeBuckets.isNotEmpty()) {
                    // 二级折叠目录（卷 → 章）
                    LazyColumn(
                        state = chapterListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        volumeBuckets.forEachIndexed { vIdx, bucket ->
                            val isCollapsed = collapsedVolumes[bucket.volumeTitle] == true
                            val hasCurrent = bucket.items.any { it.first == session.chapterIndex }
                            item(key = "vol_${vIdx}_${bucket.volumeTitle}") {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = if (vIdx > 0) 6.dp else 0.dp)
                                        .clip(CardShape)
                                        .background(if (hasCurrent) p.accentSoft.copy(alpha = 0.65f) else p.background)
                                        .border(1.dp, if (hasCurrent) p.accent.copy(alpha = 0.3f) else p.divider, CardShape)
                                        .clickable {
                                            collapsedVolumes[bucket.volumeTitle] = !isCollapsed
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    InkIcon(
                                        res = if (isCollapsed) R.drawable.ic_right else R.drawable.ic_down,
                                        contentDescription = null,
                                        tint = if (hasCurrent) p.accent else p.textSecondary,
                                        size = 15
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = bucket.volumeTitle,
                                        fontFamily = FontFamily.SansSerif,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = if (hasCurrent) p.accent else p.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = pluralStringResource(
                                            R.plurals.plural_chapters,
                                            bucket.items.size,
                                            bucket.items.size
                                        ),
                                        style = InkType.Caption.copy(fontSize = 10.5.sp),
                                        color = p.textFaint
                                    )
                                }
                            }
                            if (!isCollapsed) {
                                itemsIndexed(
                                    items = bucket.items,
                                    key = { _, pair -> pair.second.id }
                                ) { _, (chapIdx, c) ->
                                    val current = chapIdx == session.chapterIndex
                                    TocChapterRow(
                                        index = chapIdx,
                                        title = c.title,
                                        current = current,
                                        indented = true,
                                        onClick = { onJump(chapIdx, 0) }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // 普通单层目录
                    LazyColumn(
                        state = chapterListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 20.dp)
                    ) {
                        itemsIndexed(session.chapters, key = { _, c -> c.id }) { i, c ->
                            val current = i == session.chapterIndex
                            TocChapterRow(
                                index = i,
                                title = c.title,
                                current = current,
                                indented = false,
                                onClick = { onJump(i, 0) }
                            )
                        }
                    }
                }

                TocTab.NOTES -> if (tags.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            Modifier.size(64.dp).clip(CircleShape).background(p.accentSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            InkIcon(R.drawable.ic_bookmark_outline, null, tint = p.accent, size = 26)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.sheets_toc_empty_tags_title), style = InkType.Title, color = p.textPrimary)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (session.prefs.pageMode == PageMode.PAGE)
                                stringResource(R.string.sheets_toc_tags_hint_page)
                            else
                                stringResource(R.string.sheets_toc_tags_hint_scroll),
                            style = InkType.Caption.copy(fontSize = 12.5.sp),
                            color = p.textFaint,
                            lineHeight = 20.sp
                        )
                    }
                } else {
                    LazyColumn(
                        state = tagListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(tags, key = { _, m -> m.id }) { _, m ->
                            TagCard(
                                mark = m,
                                chapterTitle = session.chapterTitleAt(m.chapterIndex),
                                onOpen = { onJump(m.chapterIndex, m.startOffset) },
                                onDelete = { session.deleteMark(m.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TocChapterRow(
    index: Int,
    title: String,
    current: Boolean,
    indented: Boolean,
    onClick: () -> Unit
) {
    val p = LocalInkPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(if (current) p.accentSoft else Color.Transparent)
            .clickable { onClick() }
            .padding(
                start = if (indented) 22.dp else 12.dp,
                end = 12.dp,
                top = 11.dp,
                bottom = 11.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = String.format("%02d", index + 1),
            fontFamily = FontFamily.SansSerif,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 11.sp,
            color = if (current) p.accent else p.textFaint,
            modifier = Modifier.width(28.dp)
        )
        Text(
            text = title,
            fontFamily = FontFamily.SansSerif,
            fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            color = if (current) p.accent else p.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (current) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(p.accent))
        }
    }
}

/** 下滑打的按页标签，在目录「笔记」页签里显示的卡片 */
@Composable
private fun TagCard(
    mark: Mark,
    chapterTitle: String,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val p = LocalInkPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(p.background)
            .border(1.dp, p.divider, CardShape)
            .clickable { onOpen() }
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.label_chapter_n, mark.chapterIndex + 1),
                style = InkType.Caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                color = p.accent
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = chapterTitle,
                style = InkType.Caption.copy(fontSize = 11.sp),
                color = p.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            InkIconButton(
                res = R.drawable.ic_delete,
                contentDescription = stringResource(R.string.sheets_tag_delete),
                onClick = onDelete,
                tint = p.textFaint,
                size = 15
            )
        }
        if (mark.snippet.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = mark.snippet,
                style = InkType.Body.copy(fontSize = 13.5.sp, lineHeight = 20.sp),
                color = p.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    }
}

// ==================== 排版设置 ====================

@Composable
internal fun ReaderSettingsContent(
    prefs: ReaderPrefs,
    onUpdate: ((ReaderPrefs) -> ReaderPrefs) -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable ColumnScope.() -> Unit)? = null
) {
    val p = LocalInkPalette.current
    Column(
        modifier
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(2.dp))

        SectionLabel(stringResource(R.string.sheets_section_theme))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderThemes.forEach { (t, label) ->
                val pal = paletteFor(t)
                val on = prefs.theme == t
                Column(
                    Modifier
                        .weight(1f)
                        .clip(CardShape)
                        .background(pal.background)
                        .border(
                            if (on) 2.dp else 1.dp,
                            if (on) p.accent else p.divider,
                            CardShape
                        )
                        .clickable { onUpdate({ it.copy(theme = t) }) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.sheets_theme_sample), fontFamily = FontFamily.SansSerif, fontSize = 16.sp, color = pal.textPrimary)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = stringResource(label),
                        style = InkType.Caption.copy(fontSize = 10.sp),
                        color = if (on) p.accent else p.textFaint
                    )
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(p.background)
                .border(1.dp, p.divider, CardShape)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                text = stringResource(R.string.sheets_preview_text),
                fontFamily = if (prefs.font == FontChoice.SANS) FontFamily.SansSerif else FontFamily.Serif,
                fontSize = prefs.fontSize.sp,
                lineHeight = (prefs.fontSize * prefs.lineHeight).sp,
                letterSpacing = (prefs.fontSize * prefs.letterSpacing).sp,
                color = paletteFor(prefs.theme).textPrimary
            )
        }

        SettingsGroup {
            SectionLabel(stringResource(R.string.sheets_label_font_size, prefs.fontSize.toInt()))
            InkSlider(12f, 32f, prefs.fontSize) { v -> onUpdate({ it.copy(fontSize = v) }) }
            Spacer(Modifier.height(6.dp))
            SectionLabel(stringResource(R.string.sheets_label_line_height, String.format("%.2f", prefs.lineHeight)))
            InkSlider(1.1f, 2.8f, prefs.lineHeight) { v -> onUpdate({ it.copy(lineHeight = v) }) }
            Spacer(Modifier.height(6.dp))
            SectionLabel(stringResource(R.string.sheets_label_para_spacing, String.format("%.1f", prefs.paraSpacing)))
            InkSlider(0f, 2f, prefs.paraSpacing) { v -> onUpdate({ it.copy(paraSpacing = v) }) }
            Spacer(Modifier.height(6.dp))
            SectionLabel(stringResource(R.string.sheets_label_letter_spacing, String.format("%.2f", prefs.letterSpacing)))
            InkSlider(0f, 0.10f, prefs.letterSpacing) { v -> onUpdate({ it.copy(letterSpacing = v) }) }
            Spacer(Modifier.height(6.dp))
            SectionLabel(stringResource(R.string.sheets_label_margin, prefs.margin.toInt()))
            InkSlider(10f, 36f, prefs.margin) { v -> onUpdate({ it.copy(margin = v) }) }

            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                InkTextButton(stringResource(R.string.sheets_action_reset_typography), color = p.textSecondary) {
                    onUpdate({
                        it.copy(
                            fontSize = 19f,
                            lineHeight = 1.75f,
                            paraSpacing = 0.6f,
                            letterSpacing = 0.02f,
                            margin = 20f
                        )
                    })
                }
            }
        }

        SectionLabel(stringResource(R.string.sheets_section_font))
        InkSegment(
            options = listOf(FontChoice.SANS to stringResource(R.string.sheets_font_sans), FontChoice.SERIF to stringResource(R.string.sheets_font_serif)),
            selected = prefs.font
        ) { v -> onUpdate({ it.copy(font = v) }) }

        SectionLabel(stringResource(R.string.sheets_section_page_mode))
        InkSegment(
            options = listOf(PageMode.PAGE to stringResource(R.string.sheets_page_mode_page), PageMode.SCROLL to stringResource(R.string.sheets_page_mode_scroll)),
            selected = prefs.pageMode
        ) { v -> onUpdate({ it.copy(pageMode = v) }) }
        Text(
            text = if (prefs.pageMode == PageMode.PAGE)
                stringResource(R.string.sheets_page_mode_hint_page)
            else
                stringResource(R.string.sheets_page_mode_hint_scroll),
            style = InkType.Caption.copy(fontSize = 11.sp),
            color = p.textFaint
        )

        if (prefs.pageMode == PageMode.PAGE) {
            SectionLabel(stringResource(R.string.sheets_section_page_animation))
            InkSegment(
                options = listOf(
                    PageAnimation.SLIDE to stringResource(R.string.sheets_anim_slide),
                    PageAnimation.COVER to stringResource(R.string.sheets_anim_cover),
                    PageAnimation.FOLD to stringResource(R.string.sheets_anim_fold),
                    PageAnimation.NONE to stringResource(R.string.sheets_anim_none)
                ),
                selected = prefs.pageAnimation
            ) { v -> onUpdate({ it.copy(pageAnimation = v) }) }
            Text(
                text = stringResource(R.string.sheets_page_anim_hint),
                style = InkType.Caption.copy(fontSize = 11.sp),
                color = p.textFaint,
                lineHeight = 17.sp
            )
        }

        SettingsGroup {
            InkSwitchRow(
                label = stringResource(R.string.sheets_switch_indent),
                hint = stringResource(R.string.sheets_switch_indent_hint),
                checked = prefs.firstLineIndent
            ) { v -> onUpdate({ it.copy(firstLineIndent = v) }) }
            Spacer(Modifier.height(4.dp))
            InkSwitchRow(
                label = stringResource(R.string.sheets_switch_volume_keys),
                hint = stringResource(R.string.sheets_switch_volume_keys_hint),
                checked = prefs.volumeKeyTurn
            ) { v -> onUpdate({ it.copy(volumeKeyTurn = v) }) }
            Spacer(Modifier.height(4.dp))
            InkSwitchRow(
                label = stringResource(R.string.sheets_switch_tag_sound),
                hint = stringResource(R.string.sheets_switch_tag_sound_hint),
                checked = prefs.tagSound
            ) { v -> onUpdate({ it.copy(tagSound = v) }) }
            Spacer(Modifier.height(4.dp))
            InkSwitchRow(
                label = stringResource(R.string.sheets_switch_chapter_flash),
                hint = stringResource(R.string.sheets_switch_chapter_flash_hint),
                checked = prefs.chapterFlash
            ) { v -> onUpdate({ it.copy(chapterFlash = v) }) }
        }

        SectionLabel(stringResource(R.string.sheets_section_brightness))
        Row(verticalAlignment = Alignment.CenterVertically) {
            InkIcon(R.drawable.ic_brightness, null, tint = p.textFaint, size = 16)
            Spacer(Modifier.width(10.dp))
            InkSlider(
                min = 0.02f,
                max = 1f,
                value = if (prefs.brightness < 0f) 0.5f else prefs.brightness,
                modifier = Modifier.weight(1f)
            ) { v ->
                onUpdate({ it.copy(brightness = v) })
            }
            InkTextButton(if (prefs.brightness < 0f) stringResource(R.string.sheets_brightness_follow) else stringResource(R.string.sheets_brightness_follow_back), color = p.textSecondary) {
                onUpdate({ it.copy(brightness = -1f) })
            }
        }

        footer?.invoke(this)
    }
}

@Composable
fun ReaderSettingsSheet(
    session: ReaderSession,
    onDismiss: () -> Unit,
    onOpenMarks: () -> Unit,
    onOpenDetail: () -> Unit,
    onEditChapter: () -> Unit
) {
    val p = LocalInkPalette.current
    InkSheet(onDismiss = onDismiss, title = stringResource(R.string.sheets_reader_settings_title)) {
        ReaderSettingsContent(
            prefs = session.prefs,
            onUpdate = { t -> session.updatePrefs(t) },
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = stringResource(R.string.sheets_reader_settings_note),
                style = InkType.Caption.copy(fontSize = 11.sp),
                color = p.textFaint
            )

            SectionLabel(stringResource(R.string.sheets_section_this_book))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                InkTextButton(stringResource(R.string.sheets_action_notes_bookmarks), modifier = Modifier.weight(1f)) { onOpenMarks() }
                InkTextButton(stringResource(R.string.sheets_action_edit_chapter), modifier = Modifier.weight(1f)) { onEditChapter() }
                InkTextButton(stringResource(R.string.sheets_action_manage_chapters), modifier = Modifier.weight(1f), color = p.textSecondary) { onOpenDetail() }
            }
            Spacer(Modifier.height(26.dp))
        }
    }
}

// ==================== 搜索 ====================

@Composable
fun SearchSheet(
    session: ReaderSession,
    onDismiss: () -> Unit,
    onJump: (SearchHit) -> Unit
) {
    val p = LocalInkPalette.current
    var keyword by remember { mutableStateOf("") }

    InkSheet(onDismiss = onDismiss, title = stringResource(R.string.sheets_search_title), maxHeightFraction = 0.88f) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InkTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    placeholder = stringResource(R.string.sheets_search_placeholder),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                InkTextButton(stringResource(R.string.action_search)) { session.search(keyword) }
            }
            Spacer(Modifier.height(10.dp))
            when {
                session.searching -> {
                    Text(
                        stringResource(R.string.sheets_search_searching, (session.searchProgress * 100).toInt()),
                        style = InkType.Caption.copy(fontSize = 11.sp),
                        color = p.textFaint
                    )
                    Spacer(Modifier.height(6.dp))
                    InkProgress(session.searchProgress, Modifier.fillMaxWidth())
                }

                session.searchHits.isEmpty() && keyword.isNotBlank() -> Text(
                    stringResource(R.string.sheets_search_no_result, keyword),
                    style = InkType.UiLabel,
                    color = p.textFaint,
                    modifier = Modifier.padding(vertical = 10.dp)
                )

                else -> Text(
                    pluralStringResource(
                        R.plurals.sheets_search_result_count,
                        session.searchHits.size,
                        session.searchHits.size
                    ),
                    style = InkType.Caption.copy(fontSize = 11.sp),
                    color = p.textFaint
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(session.searchHits, key = { i, h -> "${h.chapterIndex}_${h.offset}_$i" }) { _, hit ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(CardShape)
                        .background(p.background)
                        .border(1.dp, p.divider, CardShape)
                        .clickable { onJump(hit) }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = hit.chapterTitle,
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                            color = p.accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(R.string.label_chapter_n, hit.chapterIndex + 1),
                            style = InkType.Caption.copy(fontSize = 10.5.sp),
                            color = p.textFaint
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = highlightKeyword(hit.context, keyword, p.accent),
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 13.5.sp,
                        lineHeight = 20.sp,
                        color = p.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun highlightKeyword(text: String, keyword: String, color: Color): AnnotatedString {
    if (keyword.isBlank()) return AnnotatedString(text)
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val idx = text.indexOf(keyword, i, ignoreCase = true)
            if (idx < 0) {
                append(text.substring(i)); break
            }
            append(text.substring(i, idx))
            withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) {
                append(text.substring(idx, idx + keyword.length))
            }
            i = idx + keyword.length
        }
    }
}

// ==================== 朗读（在此处按需申请通知权限，不在书架冷启动时抢占） ====================

@Composable
fun TtsSheet(
    session: ReaderSession,
    state: TtsState,
    onDismiss: () -> Unit,
    onStart: (Int, SleepMode) -> Unit,
    onStop: () -> Unit,
    onRate: (Float, Float) -> Unit,
    onSleep: (SleepMode) -> Unit
) {
    val p = LocalInkPalette.current
    val context = LocalContext.current
    val prefs = session.prefs
    var rate by remember { mutableStateOf(prefs.ttsRate) }
    var pitch by remember { mutableStateOf(prefs.ttsPitch) }
    var sleep by remember { mutableStateOf(SleepMode.OFF) }
    var fromPageStart by remember { mutableStateOf(true) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        // 已授权就别再弹：每次打开朗读面板都申请会打扰用户
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    val active = state.active && state.bookId == session.bookId

    InkSheet(onDismiss = onDismiss, title = stringResource(R.string.sheets_tts_title), maxHeightFraction = 0.8f) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(2.dp))

            ComputerVoiceSettings()

            if (state.engineMissing && !state.computerVoice) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(CardShape)
                        .background(if (p.isDark) Color(0x26E06C5A) else Color(0x1FC0392B))
                        .padding(14.dp)
                ) {
                    Text(
                        stringResource(R.string.sheets_tts_engine_missing),
                        style = InkType.Caption.copy(fontSize = 12.sp),
                        color = p.textPrimary,
                        lineHeight = 19.sp
                    )
                }
            }

            if (active) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(CardShape)
                        .background(p.background)
                        .border(1.dp, p.divider, CardShape)
                        .padding(horizontal = 15.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = if (state.playing && state.buffering) "正在准备晓晓语音…" else if (state.playing) stringResource(R.string.sheets_tts_status_playing) else stringResource(R.string.sheets_tts_status_paused),
                        style = InkType.Caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
                        color = p.accent
                    )
                    Spacer(Modifier.height(7.dp))
                    Text(if (state.computerVoice) "电脑 · 本地晓晓" else "手机语音", style = InkType.Caption, color = p.textSecondary)
                    Text(
                        text = state.chapterTitle,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        color = p.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.sheets_tts_chapter_progress,
                            state.chapterIndex + 1,
                            state.totalChapters
                        ) +
                            if (state.sleepRemainingMs > 0)
                                stringResource(
                                    R.string.sheets_tts_sleep_suffix,
                                    state.sleepRemainingMs / 60000L + 1
                                ) else "",
                        style = InkType.Caption.copy(fontSize = 11.sp),
                        color = p.textFaint
                    )
                    Spacer(Modifier.height(12.dp))
                    InkProgress(state.chapterProgress, Modifier.fillMaxWidth())
                }
            }

            SectionLabel(stringResource(R.string.sheets_tts_start_position))
            InkSegment(
                options = listOf(true to stringResource(R.string.sheets_tts_from_page), false to stringResource(R.string.sheets_tts_from_chapter)),
                selected = fromPageStart
            ) { fromPageStart = it }

            SectionLabel(stringResource(R.string.sheets_tts_rate, String.format("%.1f", rate)))
            InkSlider(0.5f, 2.5f, rate) { v ->
                rate = v
                session.updatePrefs({ it.copy(ttsRate = v) })
                onRate(v, pitch)
            }

            SectionLabel(stringResource(R.string.sheets_tts_pitch, String.format("%.1f", pitch)))
            InkSlider(0.5f, 2f, pitch) { v ->
                pitch = v
                session.updatePrefs({ it.copy(ttsPitch = v) })
                onRate(rate, v)
            }

            SectionLabel(stringResource(R.string.sheets_tts_timer))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(
                    SleepMode.OFF to stringResource(R.string.sheets_tts_sleep_off),
                    SleepMode.MIN15 to stringResource(R.string.sheets_tts_sleep_15),
                    SleepMode.MIN30 to stringResource(R.string.sheets_tts_sleep_30),
                    SleepMode.MIN60 to stringResource(R.string.sheets_tts_sleep_60),
                    SleepMode.CHAPTER_END to stringResource(R.string.sheets_tts_sleep_chapter)
                ).forEach { (mode, label) ->
                    val on = sleep == mode
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(CardShape)
                            .background(if (on) p.accentSoft else p.background)
                            .border(1.dp, if (on) p.accent else p.divider, CardShape)
                            .clickable {
                                sleep = mode
                                session.updatePrefs({ it.copy(ttsTimerMinutes = mode.minutes) })
                                onSleep(mode)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = InkType.Caption.copy(
                                fontSize = 11.sp,
                                fontWeight = if (on) FontWeight.Medium else FontWeight.Normal
                            ),
                            color = if (on) p.accent else p.textSecondary
                        )
                    }
                }
            }

            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                if (active) {
                    InkTextButton(
                        stringResource(R.string.sheets_tts_stop),
                        modifier = Modifier.weight(1f),
                        color = if (p.isDark) Color(0xFFE06C5A) else Color(0xFFC0392B)
                    ) { onStop() }
                } else {
                    InkTextButton(stringResource(R.string.sheets_tts_start), modifier = Modifier.weight(1f)) {
                        val offset = if (fromPageStart) session.currentOffset() else 0
                        onStart(offset, sleep)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}
