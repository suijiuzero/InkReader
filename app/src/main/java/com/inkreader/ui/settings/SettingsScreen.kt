package com.inkreader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkreader.AppContainer
import com.inkreader.R
import com.inkreader.ui.component.InkCard
import com.inkreader.ui.component.InkIcon
import com.inkreader.ui.component.InkTopBar
import com.inkreader.ui.reader.ReaderSettingsContent
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette

/**
 * 书架右上角三点菜单进来的「设置」页：整屏，带返回。
 *
 * 设置项与阅读器里那个底部弹层是同一份（[ReaderSettingsContent]），改的也是同一套
 * 全局偏好 —— 在这里调完字号/主题/翻页动画，回书架再打开任何一本书都是新的这一套。
 *
 * 主题要额外同步一次书架外观 —— 书架自己不会因为偏好落盘就重组。
 */
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onOpenHelp: () -> Unit) {
    val p = LocalInkPalette.current
    var prefs by remember { mutableStateOf(container.prefs.load()) }
    Column(Modifier.fillMaxSize().background(p.background)) {
        InkTopBar(title = stringResource(R.string.action_settings), onBack = onBack)
        ReaderSettingsContent(
            prefs = prefs,
            onUpdate = { transform ->
                val prev = prefs
                val next = transform(prev)
                prefs = next
                container.prefs.save(next)
                if (next.theme != prev.theme) container.library.syncUiTheme()
            },
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = stringResource(R.string.settings_global_note),
                style = InkType.Caption.copy(fontSize = 11.sp),
                color = p.textFaint
            )
            InkCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenHelp
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InkIcon(R.drawable.ic_book, contentDescription = null, tint = p.accent, size = 19)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.help_title),
                        style = InkType.UiLabel.copy(fontWeight = FontWeight.Medium),
                        color = p.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    InkIcon(R.drawable.ic_right, contentDescription = null, tint = p.textFaint, size = 18)
                }
            }
            LibraryBackupControls(container) { prefs = container.prefs.load() }
            // 设置内容没有底部留白，这里给「使用说明」和屏幕底边（含导航栏）之间留出距离
            Spacer(
                Modifier.height(
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
                )
            )
        }
    }
}
