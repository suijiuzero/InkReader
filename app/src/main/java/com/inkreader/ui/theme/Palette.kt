package com.inkreader.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 五套阅读主题：月白 / 纸白 / 护眼 / 深墨 / 纯黑。
 *
 * v1.2.0 全面改版：配色从「深色杂志」转向「轻盈现代」——
 * 浅色主题以极浅的中性灰打底、纯白卡片承载内容、墨蓝 indigo 作为唯一强调色；
 * 深色主题保留给夜间阅读，但去掉了原来的棕褐色偏，改用中性冷灰，
 * 并补上 accentSoft（选中态淡底）与更清晰的层级对比。
 */
@Immutable
data class InkPalette(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textFaint: Color,
    val accent: Color,
    val accentSoft: Color,
    val divider: Color,
    val scrim: Color,
    val selection: Color,
    val isDark: Boolean
)

/** 月白：默认主题。极浅灰底 + 纯白卡片 + 墨蓝强调色 */
val InkMist = InkPalette(
    background = Color(0xFFF4F5F7),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF15171C),
    textSecondary = Color(0xFF5C6373),
    textFaint = Color(0xFF9AA1AE),
    accent = Color(0xFF3E63DD),
    accentSoft = Color(0x1F3E63DD),
    divider = Color(0xFFE7E9EE),
    scrim = Color(0x660F1116),
    selection = Color(0x333E63DD),
    isDark = false
)

/** 纸白：暖调浅色，长阅读更柔和 */
val InkPaper = InkPalette(
    background = Color(0xFFF7F4EE),
    surface = Color(0xFFFFFDF9),
    surfaceHigh = Color(0xFFFFFDF9),
    textPrimary = Color(0xFF1F1C16),
    textSecondary = Color(0xFF60594B),
    textFaint = Color(0xFF9C9586),
    accent = Color(0xFF9A6B2F),
    accentSoft = Color(0x1F9A6B2F),
    divider = Color(0xFFEAE3D6),
    scrim = Color(0x661F1C16),
    selection = Color(0x339A6B2F),
    isDark = false
)

/** 护眼：低刺激的浅绿底 */
val InkGreen = InkPalette(
    background = Color(0xFFEDF3EA),
    surface = Color(0xFFF8FBF6),
    surfaceHigh = Color(0xFFF8FBF6),
    textPrimary = Color(0xFF182214),
    textSecondary = Color(0xFF4D5A45),
    textFaint = Color(0xFF8B9681),
    accent = Color(0xFF3F7A46),
    accentSoft = Color(0x1F3F7A46),
    divider = Color(0xFFDCE6D6),
    scrim = Color(0x66182214),
    selection = Color(0x333F7A46),
    isDark = false
)

/** 深墨：夜间主题，中性冷灰而非棕黑 */
val InkDark = InkPalette(
    background = Color(0xFF131519),
    surface = Color(0xFF1B1E24),
    surfaceHigh = Color(0xFF23272F),
    textPrimary = Color(0xFFE9EBF0),
    textSecondary = Color(0xFFA3AAB8),
    textFaint = Color(0xFF6F7787),
    accent = Color(0xFF8AA0F0),
    accentSoft = Color(0x338AA0F0),
    divider = Color(0xFF2A2E37),
    scrim = Color(0xCC0A0C10),
    selection = Color(0x4D7C93E8),
    isDark = true
)

/** 纯黑：OLED 省电，纯黑底 */
val InkNight = InkPalette(
    background = Color(0xFF000000),
    surface = Color(0xFF0B0C0F),
    surfaceHigh = Color(0xFF16181D),
    textPrimary = Color(0xFFDEE2E9),
    textSecondary = Color(0xFF9098A6),
    textFaint = Color(0xFF606774),
    accent = Color(0xFF7E93EA),
    accentSoft = Color(0x2E7E93EA),
    divider = Color(0xFF1F2127),
    scrim = Color(0xE6000000),
    selection = Color(0x4D6D82D8),
    isDark = true
)

val LocalInkPalette = staticCompositionLocalOf { InkMist }

/** 高亮批注的四种颜色，浅底深底都够清楚 */
val HighlightColors = listOf(
    Color(0x66F2C14E),  // 暖金
    Color(0x596FA8E8),  // 雾蓝
    Color(0x59C08BE0),  // 藕紫
    Color(0x5985C878)   // 苔绿
)
