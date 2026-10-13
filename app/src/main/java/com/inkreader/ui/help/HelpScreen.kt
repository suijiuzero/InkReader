package com.inkreader.ui.help

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkreader.R
import com.inkreader.parse.RichBlock
import com.inkreader.parse.RichChapter
import com.inkreader.parse.RichKind
import com.inkreader.parse.RichMarkdown
import com.inkreader.ui.component.CardShape
import com.inkreader.ui.component.Hairline
import com.inkreader.ui.component.InkLoading
import com.inkreader.ui.component.InkTopBar
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用内「使用说明」。
 *
 * 手册以 Markdown 放在 `assets/help/`，**按设备语言挑文件**（zh / en / ja），
 * 再用工程里已有的 [RichMarkdown] 解析成「纯文本 + 样式覆盖」，在这里渲染。
 *
 * 为什么不走阅读器那套分页引擎：那是给「按页翻」服务的（页高、页码、进度都绑在
 * 正文偏移上），说明书要的是「一屏往下滚」，套用只会把两件事都搞复杂。这里只按
 * 块类型给一点排版，行内样式直接套 [SpanStyle]。
 *
 * 完整手册（含技术说明与已知限制）仍随安装包提供，见 `docs/使用说明.md`。
 */
private const val ASSET_DIR = "help"

private fun helpAsset(language: String): String = when (language) {
    "en" -> "$ASSET_DIR/help_en.md"
    "ja" -> "$ASSET_DIR/help_ja.md"
    else -> "$ASSET_DIR/help_zh.md"
}

/** 读 assets 里的说明；任何异常都退化成空串，由调用方显示「加载失败」 */
private fun readHelp(context: Context, language: String): String =
    runCatching {
        context.assets.open(helpAsset(language)).bufferedReader().use { it.readText() }
    }.getOrDefault("")

@Composable
fun HelpScreen(onBack: () -> Unit) {
    val p = LocalInkPalette.current
    val ctx = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language

    var raw by remember(language) { mutableStateOf<String?>(null) }
    LaunchedEffect(language) {
        raw = withContext(Dispatchers.IO) { readHelp(ctx, language) }
    }

    Column(Modifier.fillMaxSize().background(p.background)) {
        InkTopBar(title = stringResource(R.string.help_title), onBack = onBack)
        val body = raw
        if (body == null) {
            InkLoading(Modifier.weight(1f))
        } else {
            HelpBody(body, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HelpBody(markdown: String, modifier: Modifier) {
    val p = LocalInkPalette.current
    val rendered = remember(markdown) { RichMarkdown.render(markdown) }
    val text = rendered.text
    val rich = rendered.rich
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 2.dp, bottom = bottomInset + 28.dp)
    ) {
        when {
            text.isBlank() -> Text(
                text = stringResource(R.string.help_load_failed),
                style = InkType.Body,
                color = p.textFaint
            )

            // 样式没解析出来就退化成纯文本，至少内容还能看
            rich == null -> Text(text, style = InkType.Body, color = p.textPrimary)

            else -> rich.blocks.forEach { block -> HelpBlock(block, rich, text) }
        }
    }
}

@Composable
private fun HelpBlock(block: RichBlock, rich: RichChapter, full: String) {
    val p = LocalInkPalette.current
    val snippet = full.substring(
        block.start.coerceIn(0, full.length),
        block.end.coerceIn(0, full.length)
    )
    val text = remember(block, full) { inlineStyled(snippet, block, rich) }

    when (block.kind) {
        RichKind.HEADING -> {
            val size = when (block.level) {
                1 -> 20.sp
                2 -> 16.5.sp
                else -> 15.sp
            }
            val top = if (block.level == 1) 4.dp else 20.dp
            Text(
                text = text,
                style = InkType.Title.copy(fontSize = size, lineHeight = size * 1.4f),
                color = p.textPrimary,
                modifier = Modifier.padding(top = top, bottom = 10.dp)
            )
        }

        RichKind.LIST -> Row(
            Modifier
                .fillMaxWidth()
                .padding(start = (block.level * 16).dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = androidx.compose.ui.Alignment.Top
        ) {
            Text(
                text = block.marker.ifBlank { "·" },
                style = InkType.Body.copy(fontSize = 14.sp),
                color = p.textSecondary,
                modifier = Modifier.width(22.dp)
            )
            Text(
                text = text,
                style = InkType.Body.copy(fontSize = 14.sp, lineHeight = 22.sp),
                color = p.textPrimary
            )
        }

        RichKind.QUOTE -> Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(vertical = 8.dp)
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(CardShape)
                    .background(p.accent.copy(alpha = 0.45f))
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = text,
                style = InkType.Body.copy(fontSize = 13.sp, lineHeight = 20.sp),
                color = p.textSecondary
            )
        }

        RichKind.CODE -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 7.dp)
                .clip(CardShape)
                .background(p.surface)
                .border(1.dp, p.divider, CardShape)
                .padding(12.dp)
        ) {
            Text(
                text = text,
                style = InkType.Body.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp
                ),
                color = p.textSecondary
            )
        }

        RichKind.RULE -> Column(Modifier.padding(vertical = 14.dp)) { Hairline() }

        // 正文与表格行：内容不使用表格，这里给一个稳妥的兜底
        else -> Text(
            text = text,
            style = InkType.Body.copy(fontSize = 14.5.sp, lineHeight = 24.sp),
            color = p.textPrimary,
            modifier = Modifier.padding(bottom = 10.dp)
        )
    }
}

/** 把落在本块范围内的行内样式（粗体 / 斜体 / 删除线 / 行内代码）套到这段文字上 */
private fun inlineStyled(snippet: String, block: RichBlock, rich: RichChapter): AnnotatedString {
    val spans = rich.spans.filter { it.end > block.start && it.start < block.end }
    if (spans.isEmpty()) return AnnotatedString(snippet)
    return buildAnnotatedString {
        append(snippet)
        for (s in spans) {
            val from = (s.start - block.start).coerceIn(0, snippet.length)
            val to = (s.end - block.start).coerceIn(0, snippet.length)
            if (to <= from) continue
            addStyle(
                SpanStyle(
                    fontWeight = if (s.bold) FontWeight.SemiBold else null,
                    fontStyle = if (s.italic) FontStyle.Italic else null,
                    textDecoration = if (s.strike) TextDecoration.LineThrough else null,
                    fontFamily = if (s.code) FontFamily.Monospace else null
                ),
                from,
                to
            )
        }
    }
}
