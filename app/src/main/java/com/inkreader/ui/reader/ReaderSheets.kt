package com.inkreader.ui.reader

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.inkreader.R
import com.inkreader.data.model.FontChoice
import com.inkreader.data.model.Mark
import com.inkreader.data.model.PageAnimation
import com.inkreader.data.model.PageMode
import com.inkreader.data.model.ReaderPrefs
import com.inkreader.data.model.ReaderTheme
import com.inkreader.data.model.SearchHit
import com.inkreader.tts.SleepMode
import com.inkreader.tts.TtsState
import com.inkreader.ui.component.InkChip
import com.inkreader.ui.component.InkIcon
import com.inkreader.ui.component.InkIconButton
import com.inkreader.ui.component.InkProgress
import com.inkreader.ui.component.InkTextField
import com.inkreader.ui.component.InkTextButton
import com.inkreader.ui.component.SectionLabel
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette
import com.inkreader.ui.theme.paletteFor

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
            // 拖动提示条
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

// ==================== 目录抽屉 ====================

@Composable
fun TocDrawer(
    session: ReaderSession,
    onDismiss: () -> Unit,
    onJump: (Int, Int) -> Unit
) {
    val p = LocalInkPalette.current
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val chapterListState = rememberLazyListState()
    val tagListState = rememberLazyListState()
    var tab by remember { mutableStateOf(TocTab.CHAPTERS) }
    val tags = session.pageTags()

    LaunchedEffect(session.chapterIndex, session.chapters.size, tab) {
        if (tab == TocTab.CHAPTERS && session.chapters.isNotEmpty()) {
            chapterListState.scrollToItem(
                (session.chapterIndex - 3).coerceIn(0, session.chapters.lastIndex)
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(p.scrim).clickable { onDismiss() })
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(310.dp)
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
                Text(
                    text = pluralStringResource(
                        R.plurals.sheets_toc_summary,
                        session.chapters.size,
                        session.chapters.size,
                        (session.bookProgress() * 100).toInt()
                    ),
                    style = InkType.Caption.copy(fontSize = 11.sp),
                    color = p.textFaint
                )
                Spacer(Modifier.height(12.dp))
                InkProgress(session.bookProgress(), Modifier.fillMaxWidth())
            }

            // 页签：章节 / 笔记（笔记里只列下滑打的「按页标签」）
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
                } else {
                    LazyColumn(
                        state = chapterListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 20.dp)
                    ) {
                        itemsIndexed(session.chapters, key = { _, c -> c.id }) { i, c ->
                            val current = i == session.chapterIndex
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(CardShape)
                                    .background(if (current) p.accentSoft else Color.Transparent)
                                    .clickable { onJump(i, 0) }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = String.format("%02d", i + 1),
                                    fontFamily = FontFamily.SansSerif,
                                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                                    fontSize = 11.sp,
                                    color = if (current) p.accent else p.textFaint,
                                    modifier = Modifier.width(26.dp)
                                )
                                Text(
                                    text = c.title,
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

/**
 * 设置项本体。
 *
 * 阅读器里那个底部面板（[ReaderSettingsSheet]）与书架三点菜单进来的全屏设置页
 * （`com.inkreader.ui.settings.SettingsScreen`）共用这一段，所以这里只认 [prefs] 与 [onUpdate]，
 * 不碰 ReaderSession。[footer] 是阅读器专属的尾巴（「这本书」那几个按钮），全屏时传 null。
 */
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

        // 实时预览
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

        // 翻页动画只在左右翻页时有意义，滚动模式用不到
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

/** 阅读器里的排版设置：底部弹层，末尾接一段本书专属操作（笔记 / 编辑本章 / 章节管理） */
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

// ==================== 朗读 ====================

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
    val prefs = session.prefs
    var rate by remember { mutableStateOf(prefs.ttsRate) }
    var pitch by remember { mutableStateOf(prefs.ttsPitch) }
    var sleep by remember { mutableStateOf(SleepMode.OFF) }
    var fromPageStart by remember { mutableStateOf(true) }

    val active = state.active && state.bookId == session.bookId

    InkSheet(onDismiss = onDismiss, title = stringResource(R.string.sheets_tts_title), maxHeightFraction = 0.8f) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(2.dp))

            if (state.engineMissing) {
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
                        text = if (state.playing) stringResource(R.string.sheets_tts_status_playing) else stringResource(R.string.sheets_tts_status_paused),
                        style = InkType.Caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
                        color = p.accent
                    )
                    Spacer(Modifier.height(7.dp))
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
