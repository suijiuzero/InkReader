package com.inkreader.ui.theme

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.inkreader.data.model.ReaderTheme

/**
 * Material 配色直接由当前阅读主题派生，
 * 这样没被显式指定颜色的控件（Switch / Slider / Menu 等）也不会出现「浅底深字」的错配。
 */
private fun schemeOf(p: InkPalette) = if (p.isDark) {
    darkColorScheme(
        primary = p.accent,
        onPrimary = Color(0xFF0C0E13),
        primaryContainer = p.accentSoft,
        onPrimaryContainer = p.textPrimary,
        secondary = p.textSecondary,
        background = p.background,
        onBackground = p.textPrimary,
        surface = p.surface,
        onSurface = p.textPrimary,
        surfaceVariant = p.surfaceHigh,
        onSurfaceVariant = p.textSecondary,
        outline = p.textFaint,
        outlineVariant = p.divider,
        error = Color(0xFFFF8A80),
        onError = Color(0xFF3A0A08)
    )
} else {
    lightColorScheme(
        primary = p.accent,
        onPrimary = Color.White,
        primaryContainer = p.accentSoft,
        onPrimaryContainer = p.textPrimary,
        secondary = p.textSecondary,
        background = p.background,
        onBackground = p.textPrimary,
        surface = p.surface,
        onSurface = p.textPrimary,
        surfaceVariant = p.surfaceHigh,
        onSurfaceVariant = p.textSecondary,
        outline = p.textFaint,
        outlineVariant = p.divider,
        error = Color(0xFFC0392B),
        onError = Color.White
    )
}

/**
 * 排版规范：全面转向无衬线。
 * DisplayTitle / Title 收字距、加字重，SectionLabel 用小字＋微宽字距做层级，
 * 取代旧版「衬线细体 + 大幅字距」的杂志味。
 */
object InkType {
    val DisplayTitle: TextStyle
        @Composable get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            letterSpacing = (-0.3).sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )

    val Title: TextStyle
        @Composable get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            letterSpacing = (-0.1).sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )

    val Body: TextStyle
        @Composable get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 14.5.sp,
            letterSpacing = 0.1.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )

    val SectionLabel: TextStyle
        @Composable get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            letterSpacing = 1.1.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )

    val UiLabel: TextStyle
        @Composable get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            letterSpacing = 0.1.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )

    val Caption: TextStyle
        @Composable get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 11.5.sp,
            letterSpacing = 0.2.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )
}

@Composable
fun paletteFor(theme: ReaderTheme): InkPalette = when (theme) {
    ReaderTheme.MIST -> InkMist
    ReaderTheme.PAPER -> InkPaper
    ReaderTheme.GREEN -> InkGreen
    ReaderTheme.INK -> InkDark
    ReaderTheme.NIGHT -> InkNight
}

/**
 * 让状态栏 / 导航栏图标颜色跟随当前主题（浅底用深色图标）。
 * revision 用于「界面层级变化时强制重刷」——嵌套主题（阅读器里再套一层）退出后，
 * 外层不重新跑一次的话系统栏会停在阅读器的配色上。
 */
@Composable
fun SyncSystemBars(dark: Boolean, revision: Any? = null) {
    val ctx = LocalContext.current
    LaunchedEffect(dark, revision) {
        val activity = ctx as? ComponentActivity ?: return@LaunchedEffect
        activity.enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )
    }
}

/**
 * 全站主题入口。深浅由所选阅读主题决定（浅色主题配浅色 Material 方案），
 * 不再跟随系统，避免「浅底 + 深色控件」的错配。
 */
@Composable
fun InkTheme(
    readerTheme: ReaderTheme = ReaderTheme.MIST,
    barRevision: Any? = null,
    content: @Composable () -> Unit
) {
    val palette = paletteFor(readerTheme)
    SyncSystemBars(palette.isDark, barRevision)
    CompositionLocalProvider(LocalInkPalette provides palette) {
        MaterialTheme(
            colorScheme = schemeOf(palette),
            typography = MaterialTheme.typography,
            content = content
        )
    }
}

/** 正文字体：无衬线为默认，也可以在设置里切回衬线 */
fun bodyFontFamily(sans: Boolean): FontFamily =
    if (sans) FontFamily.SansSerif else FontFamily.Serif

/**
 * 正文样式。
 *
 * color 必须显式传入：BasicText 在 color 未指定时会回退到 LocalContentColor，
 * 而深色主题下这个回退值可能仍是深色，导致「深墨 / 纯黑主题看不清字」。
 */
fun readingTextStyle(
    fontSizeSp: Float,
    lineHeightMultiple: Float,
    letterSpacingEm: Float,
    sans: Boolean,
    color: Color
): TextStyle = TextStyle(
    fontFamily = bodyFontFamily(sans),
    fontWeight = FontWeight.Normal,
    fontSize = TextUnit(fontSizeSp, androidx.compose.ui.unit.TextUnitType.Sp),
    lineHeight = TextUnit(fontSizeSp * lineHeightMultiple, androidx.compose.ui.unit.TextUnitType.Sp),
    letterSpacing = TextUnit(fontSizeSp * letterSpacingEm, androidx.compose.ui.unit.TextUnitType.Sp),
    color = color,
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)
