package com.inkreader.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkreader.R
import com.inkreader.data.model.Book
import com.inkreader.tts.TtsService
import com.inkreader.tts.TtsState
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette

/**
 * 通用控件。
 *
 * v1.2.0 全面改版为「轻盈现代」：无衬线为主、圆角 12dp、卡片承载内容、
 * 分区靠留白而不是金线，强调色统一用墨蓝 accent。
 */

/** 卡片圆角：全局统一 */
val CardShape = RoundedCornerShape(12.dp)
private val SheetShape = RoundedCornerShape(20.dp)
private val PillShape = RoundedCornerShape(999.dp)

/** 浅底用深红、深底用亮红，保证危险操作在两种主题下都看得清 */
@Composable
fun dangerColor(): Color =
    if (LocalInkPalette.current.isDark) Color(0xFFE06C5A) else Color(0xFFC0392B)

// ==================== 基础控件 ====================

@Composable
fun InkIcon(
    res: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalInkPalette.current.textSecondary,
    size: Int = 22
) {
    Image(
        painter = painterResource(id = res),
        contentDescription = contentDescription,
        modifier = modifier.size(size.dp),
        colorFilter = ColorFilter.tint(tint)
    )
}

@Composable
fun InkIconButton(
    res: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalInkPalette.current.textSecondary,
    enabled: Boolean = true,
    size: Int = 22
) {
    val p = LocalInkPalette.current
    Box(
        modifier = modifier
            .size((size + 22).dp)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        InkIcon(
            res = res,
            contentDescription = contentDescription,
            tint = if (enabled) tint else p.textFaint,
            size = size
        )
    }
}

/** 页面卡片：纯白底 + 12dp 圆角 + 极淡描边 */
@Composable
fun InkCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScopeCompat.() -> Unit
) {
    val p = LocalInkPalette.current
    var base = modifier
        .clip(CardShape)
        .background(p.surface)
        .border(1.dp, p.divider, CardShape)
    if (onClick != null) {
        base = base.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) { onClick() }
    }
    Box(base) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** 只是为了少 import 一个 ColumnScope */
typealias ColumnScopeCompat = androidx.compose.foundation.layout.ColumnScope

/** 胶囊标签 */
@Composable
fun InkChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    color: Color? = null,
    onClick: (() -> Unit)? = null
) {
    val p = LocalInkPalette.current
    val fg = color ?: if (selected) p.accent else p.textSecondary
    Row(
        modifier
            .clip(PillShape)
            .background(if (selected || color != null) p.accentSoft else Color.Transparent)
            .then(if (selected || color != null) Modifier else Modifier.border(1.dp, p.divider, PillShape))
            .then(
                if (onClick != null) Modifier.clickable { onClick() } else Modifier
            )
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = InkType.Caption.copy(
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
            ),
            color = fg,
            maxLines = 1
        )
    }
}

/** 顶栏：无衬线半粗标题 + 副标题，纯底色不加分隔线（靠留白分区） */
@Composable
fun InkTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}
) {
    val p = LocalInkPalette.current
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(p.background)
            .padding(top = topInset)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                InkIconButton(R.drawable.ic_arrow_back, stringResource(R.string.action_back), onBack, tint = p.textPrimary)
                Spacer(Modifier.width(2.dp))
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = InkType.DisplayTitle.copy(fontSize = 20.sp),
                    color = p.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = subtitle,
                        style = InkType.Caption.copy(fontSize = 11.5.sp),
                        color = p.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            actions()
        }
    }
}

/** 分区小标签：小号无衬线 + 微宽字距 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val p = LocalInkPalette.current
    Text(
        text = text,
        style = InkType.SectionLabel,
        color = p.textFaint,
        modifier = modifier
    )
}

/** 极淡分隔线（新版尽量用留白替代，仅在确实需要时使用） */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = LocalInkPalette.current.divider) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
fun InkProgress(
    value: Float,
    modifier: Modifier = Modifier,
    color: Color = LocalInkPalette.current.accent
) {
    LinearProgressIndicator(
        progress = { value.coerceIn(0f, 1f) },
        modifier = modifier.height(5.dp).clip(PillShape),
        color = color,
        trackColor = LocalInkPalette.current.divider,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {}
    )
}

@Composable
fun InkLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color = LocalInkPalette.current.accent,
            strokeWidth = 2.5.dp,
            modifier = Modifier.size(32.dp)
        )
    }
}

// ==================== 自动生成封面 ====================

/**
 * 没有封面图时用书名生成一张现代风封面：
 * 色相由书名稳定哈希得出，明快的莫兰迪色渐变 + 居中的大号首字（无衬线半粗），
 * 底部压书名与作者。字号随卡片实际宽度缩放，网格 / 列表 / 详情页共用一套。
 */
@Composable
fun BookCover(book: Book, modifier: Modifier = Modifier) {
    // 有自定义封面就直接用图，否则回落到按书名生成的封面
    val ctx = LocalContext.current
    val coverVersion = CoverCache.version
    val custom = produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, book.coverPath, coverVersion) {
        value = withContext(Dispatchers.IO) { CoverCache.load(ctx, book.coverPath) }
    }.value

    if (custom != null) {
        Box(
            modifier
                .clip(CardShape)
                .background(Color(0xFF1B1E24))
                .border(1.dp, Color(0x1AFFFFFF), CardShape)
        ) {
            Image(
                bitmap = custom,
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        return
    }

    val hue = book.coverHue.toFloat().mod(360f)
    val base = Color.hsv(hue, 0.40f, 0.68f)
    val deep = Color.hsv(hue, 0.54f, 0.44f)
    val firstChar = book.title.trim().firstOrNull()?.toString() ?: stringResource(R.string.cover_fallback_char)

    BoxWithConstraints(
        modifier = modifier
            .clip(CardShape)
            .background(Brush.linearGradient(listOf(base, deep)))
            .border(1.dp, Color(0x1AFFFFFF), CardShape)
    ) {
        val w = maxWidth
        // 右上角柔光，避免整块纯色发闷
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0x33FFFFFF), Color.Transparent),
                        radius = w.value * 1.1f
                    )
                )
        )
        Column(Modifier.fillMaxSize().padding(w * 0.10f)) {
            Text(
                text = firstChar,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = (w.value * 0.34f).sp,
                lineHeight = (w.value * 0.42f).sp,
                color = Color(0xF2FFFFFF)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = book.title,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = (w.value * 0.105f).sp,
                lineHeight = (w.value * 0.15f).sp,
                color = Color(0xE6FFFFFF),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (book.author.isNotBlank()) {
                Spacer(Modifier.height(w * 0.035f))
                Text(
                    text = book.author,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = (w.value * 0.085f).sp,
                    color = Color(0x99FFFFFF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ==================== 空状态 ====================

@Composable
fun EmptyState(
    iconRes: Int,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    val p = LocalInkPalette.current
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(84.dp).clip(CircleShape).background(p.accentSoft),
            contentAlignment = Alignment.Center
        ) {
            InkIcon(iconRes, null, tint = p.accent, size = 32)
        }
        Spacer(Modifier.height(22.dp))
        Text(
            text = title,
            style = InkType.Title,
            color = p.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = hint,
            style = InkType.Caption.copy(fontSize = 12.5.sp),
            color = p.textFaint,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            action()
        }
    }
}

/** 主按钮：实心墨蓝 / 描边次级，12dp 圆角，无衬线中粗 */
@Composable
fun InkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    filled: Boolean = true,
    enabled: Boolean = true
) {
    val p = LocalInkPalette.current
    val bg = if (filled) p.accent else Color.Transparent
    val fg = if (filled) Color.White else p.textPrimary
    Row(
        modifier = modifier
            .clip(CardShape)
            .background(if (enabled) bg else p.divider)
            .then(
                if (filled) Modifier
                else Modifier.border(1.dp, p.divider, CardShape)
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (iconRes != null) {
            InkIcon(iconRes, null, tint = if (enabled) fg else p.textFaint, size = 17)
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = text,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 14.5.sp,
            color = if (enabled) fg else p.textFaint
        )
    }
}

/** 次级文字按钮 */
@Composable
fun InkTextButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalInkPalette.current.accent,
    onClick: () -> Unit
) {
    Text(
        text = text,
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

// ==================== 朗读悬浮条 ====================

@Composable
fun MiniTtsBar(state: TtsState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val p = LocalInkPalette.current
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    AnimatedVisibility(
        visible = state.active,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(bottom = bottomInset + 12.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .shadow(10.dp, SheetShape, clip = false)
                    .clip(SheetShape)
                    .background(p.surfaceHigh)
                    .border(1.dp, p.divider, SheetShape)
            ) {
                InkProgress(
                    value = state.chapterProgress,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 12.dp)
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InkIcon(R.drawable.ic_headphones, null, tint = p.accent, size = 17)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        val playLabel = stringResource(
                            if (state.playing) R.string.notif_playing else R.string.tts_paused
                        )
                        val sleepMin =
                            if (state.sleepRemainingMs > 0) (state.sleepRemainingMs / 60000L).toInt() + 1 else 0
                        val stopSuffix =
                            if (sleepMin > 0) stringResource(R.string.tts_stop_after_min, sleepMin) else ""
                        Text(
                            text = state.chapterTitle.ifBlank { state.bookTitle },
                            style = InkType.Body.copy(
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = p.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildString {
                                append(playLabel)
                                append("  ·  ${state.chapterIndex + 1}/${state.totalChapters}")
                                if (stopSuffix.isNotEmpty()) append("  ·  $stopSuffix")
                            },
                            style = InkType.Caption.copy(fontSize = 11.sp),
                            color = p.textFaint,
                            maxLines = 1
                        )
                    }
                    InkIconButton(R.drawable.ic_prev, stringResource(R.string.tts_prev_sentence), { TtsService.send(ctx, TtsService.ACTION_PREV) }, size = 19)
                    InkIconButton(
                        res = if (state.playing) R.drawable.ic_pause else R.drawable.ic_play,
                        contentDescription = stringResource(
                            if (state.playing) R.string.tts_pause else R.string.tts_resume
                        ),
                        onClick = {
                            TtsService.send(
                                ctx,
                                if (state.playing) TtsService.ACTION_PAUSE else TtsService.ACTION_RESUME
                            )
                        },
                        tint = p.accent,
                        size = 22
                    )
                    InkIconButton(R.drawable.ic_next, stringResource(R.string.tts_next_sentence), { TtsService.send(ctx, TtsService.ACTION_NEXT) }, size = 19)
                    InkIconButton(R.drawable.ic_close, stringResource(R.string.tts_stop), { TtsService.send(ctx, TtsService.ACTION_STOP) }, size = 18)
                }
            }
        }
    }
}

// ==================== 对话框与输入框 ====================

@Composable
fun InkDialogShell(
    onDismiss: () -> Unit,
    title: String,
    /** 底部按钮：放进 AlertDialog 的按钮槽，**永远可见**，不会被长内容挤出去 */
    buttons: (@Composable () -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val p = LocalInkPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surfaceHigh,
        tonalElevation = 0.dp,
        shape = SheetShape,
        title = {
            Text(
                text = title,
                style = InkType.Title.copy(fontSize = 17.sp),
                color = p.textPrimary
            )
        },
        text = {
            // 内容超长时可滚动，不至于把底部按钮顶出屏幕
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                content = content
            )
        },
        confirmButton = { buttons?.invoke() }
    )
}

@Composable
fun InkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1
) {
    val p = LocalInkPalette.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = minLines,
        placeholder = {
            Text(placeholder, style = InkType.UiLabel, color = p.textFaint)
        },
        textStyle = androidx.compose.ui.text.TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 15.sp,
            color = p.textPrimary
        ),
        shape = CardShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = p.accent,
            unfocusedBorderColor = p.divider,
            focusedContainerColor = p.background,
            unfocusedContainerColor = p.background,
            cursorColor = p.accent,
            focusedTextColor = p.textPrimary,
            unfocusedTextColor = p.textPrimary
        )
    )
}
