package com.inkreader.ui.editor

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkreader.AppContainer
import com.inkreader.R
import com.inkreader.data.model.Chapter
import com.inkreader.ui.component.InkDialogShell
import com.inkreader.ui.component.InkLoading
import com.inkreader.ui.component.InkTextButton
import com.inkreader.ui.component.InkTextField
import com.inkreader.ui.component.InkTopBar
import com.inkreader.ui.component.SectionLabel
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette
import kotlinx.coroutines.launch

/**
 * 章节编辑器：改标题、写正文，并支持「从光标处拆分为下一章」（批注无损迁移）。
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
    var bodyField by remember { mutableStateOf(TextFieldValue("")) }
    var loaded by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var showSplitDialog by remember { mutableStateOf(false) }
    var splitNewTitle by remember { mutableStateOf("") }

    val body = bodyField.text

    LaunchedEffect(chapterId) {
        val chs = container.repo.chapters(bookId)
        val ch = chs.firstOrNull { it.id == chapterId }
        chapter = ch
        if (ch != null) {
            title = ch.title
            val text = container.repo.chapterText(ch)
            bodyField = TextFieldValue(text, TextRange(0))
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
                InkTextButton(stringResource(R.string.editor_split_here), color = p.textSecondary) {
                    val cursor = bodyField.selection.start
                    if (cursor <= 0 || cursor >= body.length) {
                        Toast.makeText(ctx, ctx.getString(R.string.editor_split_cursor_hint), Toast.LENGTH_SHORT).show()
                    } else {
                        val after = body.substring(cursor).trimStart()
                        val firstLine = after.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                        splitNewTitle = if (firstLine.length in 1..34) firstLine else "${title.trim()}（续）"
                        showSplitDialog = true
                    }
                }
                InkTextButton(stringResource(R.string.action_save)) { saveNow() }
            }
        )

        if (!loaded) {
            InkLoading()
        } else {
            Column(Modifier.fillMaxSize().padding(bottom = bottomPad)) {
                Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    InkTextField(
                        value = title,
                        onValueChange = { title = it; dirty = true },
                        placeholder = stringResource(R.string.editor_title_hint)
                    )
                }

                Box(
                    Modifier
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(p.surface)
                        .border(1.dp, p.divider, RoundedCornerShape(16.dp))
                ) {
                    OutlinedTextField(
                        value = bodyField,
                        onValueChange = {
                            val textChanged = it.text != bodyField.text
                            bodyField = it
                            if (textChanged) dirty = true
                        },
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

    if (showSplitDialog) {
        InkDialogShell(
            onDismiss = { showSplitDialog = false },
            title = stringResource(R.string.editor_split_dialog_title),
            buttons = {
                Row(horizontalArrangement = Arrangement.End) {
                    InkTextButton(stringResource(R.string.action_cancel), color = p.textSecondary) {
                        showSplitDialog = false
                    }
                    InkTextButton(stringResource(R.string.editor_split_confirm)) {
                        val ch = chapter ?: return@InkTextButton
                        val splitAt = bodyField.selection.start
                        val newT = splitNewTitle.trim()
                        showSplitDialog = false
                        scope.launch {
                            // 先把当前编辑中的正文落盘，再按光标位置拆分
                            container.repo.saveChapterText(ch, body)
                            if (title.trim().isNotEmpty() && title != ch.title) {
                                container.repo.renameChapter(ch.id, title.trim())
                            }
                            val newId = container.repo.splitChapterAt(bookId, ch.id, splitAt, newT)
                            if (newId != null) {
                                val updatedText = container.repo.chapterText(ch)
                                bodyField = TextFieldValue(updatedText, TextRange(0))
                                dirty = false
                                container.library.refresh()
                                Toast.makeText(ctx, ctx.getString(R.string.editor_split_done_toast), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        ) {
            Text(
                text = stringResource(R.string.editor_split_dialog_desc),
                style = InkType.Caption.copy(fontSize = 12.sp),
                color = p.textSecondary,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(14.dp))
            SectionLabel(stringResource(R.string.editor_split_new_title_label))
            Spacer(Modifier.height(8.dp))
            InkTextField(
                value = splitNewTitle,
                onValueChange = { splitNewTitle = it },
                placeholder = stringResource(R.string.editor_title_hint)
            )
        }
    }
}
