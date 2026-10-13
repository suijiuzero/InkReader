package com.inkreader.ui.editor

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkreader.AppContainer
import com.inkreader.R
import com.inkreader.data.model.Chapter
import com.inkreader.ui.component.InkLoading
import com.inkreader.ui.component.InkTextField
import com.inkreader.ui.component.InkTextButton
import com.inkreader.ui.component.InkTopBar
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette
import kotlinx.coroutines.launch

/**
 * 章节编辑器：改标题、写正文。
 * 停顿 1.2 秒自动保存，也可以手动保存，退出前一定会落盘。
 */
@Composable
fun EditorScreen(
    container: AppContainer,
    bookId: Long,
    chapterId: Long,
    initialTitle: String,
    onBack: () -> Unit
) {
    val p = LocalInkPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var chapter by remember { mutableStateOf<Chapter?>(null) }
    var title by remember { mutableStateOf(initialTitle) }
    var body by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(chapterId) {
        val chs = container.repo.chapters(bookId)
        val ch = chs.firstOrNull { it.id == chapterId }
        chapter = ch
        if (ch != null) {
            title = ch.title
            body = container.repo.chapterText(ch)
        }
        loaded = true
    }

    // 停顿后自动保存
    LaunchedEffect(body, title, loaded) {
        if (!loaded || !dirty) return@LaunchedEffect
        kotlinx.coroutines.delay(1200)
        val ch = chapter ?: return@LaunchedEffect
        saving = true
        container.repo.saveChapterText(ch, body)
        if (title.trim().isNotEmpty() && title != ch.title) {
            container.repo.renameChapter(ch.id, title.trim())
            chapter = ch.copy(title = title.trim())
        }
        saving = false
        dirty = false
    }

    fun saveNow() {
        val ch = chapter ?: return
        scope.launch {
            container.repo.saveChapterText(ch, body)
            if (title.trim().isNotEmpty() && title != ch.title) {
                container.repo.renameChapter(ch.id, title.trim())
            }
            container.library.refresh()
            dirty = false
            Toast.makeText(ctx, ctx.getString(R.string.editor_saved), Toast.LENGTH_SHORT).show()
        }
    }

    val bottomPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(Modifier.fillMaxSize().background(p.background)) {
        InkTopBar(
            title = stringResource(R.string.editor_title),
            subtitle = stringResource(
                R.string.editor_subtitle,
                body.length,
                when {
                    saving -> stringResource(R.string.editor_status_saving)
                    dirty -> stringResource(R.string.editor_status_dirty)
                    else -> stringResource(R.string.editor_saved)
                }
            ),
            onBack = {
                saveNow()
                onBack()
            },
            actions = {
                InkTextButton(stringResource(R.string.action_save)) { saveNow() }
            }
        )

        if (!loaded) {
            InkLoading()
        } else {
            Column(Modifier.fillMaxSize().padding(bottom = bottomPad)) {
                // 标题框：靠留白与正文框分隔，不再画分隔线
                Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    InkTextField(
                        value = title,
                        onValueChange = { title = it; dirty = true },
                        placeholder = stringResource(R.string.editor_title_hint)
                    )
                }

                // 正文框：白色圆角卡片，内部输入框自身无边框、无底色
                Box(
                    Modifier
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(p.surface)
                        .border(1.dp, p.divider, RoundedCornerShape(16.dp))
                ) {
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it; dirty = true },
                        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 16.sp,
                            lineHeight = 28.sp,
                            letterSpacing = 0.2.sp,
                            color = p.textPrimary
                        ),
                        placeholder = {
                            Text(
                                stringResource(R.string.editor_body_hint),
                                style = InkType.UiLabel.copy(fontSize = 13.sp),
                                color = p.textFaint,
                                lineHeight = 24.sp
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            disabledBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            cursorColor = p.accent,
                            focusedTextColor = p.textPrimary,
                            unfocusedTextColor = p.textPrimary
                        )
                    )
                }
            }
        }

    }
}
