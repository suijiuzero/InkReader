package com.inkreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.inkreader.R
import com.inkreader.tts.ComputerVoiceModel
import com.inkreader.ui.component.InkButton
import com.inkreader.ui.component.InkIcon
import com.inkreader.ui.component.InkIconButton
import com.inkreader.ui.component.InkTextButton
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette

@Composable
fun VoiceModelDialog(
    selected: ComputerVoiceModel,
    onDismiss: () -> Unit,
    onSave: (ComputerVoiceModel) -> Unit
) {
    val p = LocalInkPalette.current
    var draft by remember(selected) { mutableStateOf(selected) }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.88f
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = 24.dp).widthIn(max = 420.dp).fillMaxWidth()
                .heightIn(max = maxHeight).clip(RoundedCornerShape(26.dp)).background(p.surface)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(p.accentSoft),
                    contentAlignment = Alignment.Center) {
                    InkIcon(R.drawable.ic_headphones, null, tint = p.accent, size = 23)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("电脑语音", style = InkType.Caption, color = p.textSecondary)
                    Text("选择朗读模型", style = InkType.Title, color = p.textPrimary)
                }
                InkIconButton(R.drawable.ic_close, "关闭模型选择", onDismiss, tint = p.textFaint, size = 18)
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ComputerVoiceModel.entries.forEach { model ->
                    val isSelected = model == draft
                    val shape = RoundedCornerShape(16.dp)
                    Row(
                        Modifier.fillMaxWidth().clip(shape)
                            .background(if (isSelected) p.accentSoft else p.background)
                            .border(1.dp, if (isSelected) p.accent else p.divider, shape)
                            .selectable(selected = isSelected, role = Role.RadioButton, onClick = { draft = model })
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) p.surface else p.accentSoft), contentAlignment = Alignment.Center) {
                            Text(if (model == ComputerVoiceModel.AUTO) "A" else model.title.take(1),
                                style = InkType.Title, color = p.accent)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(model.title, style = InkType.Body.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp), color = p.textPrimary)
                                if (model == ComputerVoiceModel.AUTO) Text("推荐", style = InkType.Caption.copy(fontSize = 10.sp),
                                    color = p.accent, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(p.accentSoft)
                                        .padding(horizontal = 6.dp, vertical = 3.dp))
                            }
                            Text(when (model) {
                                ComputerVoiceModel.AUTO -> "随文字语言切换声音"
                                ComputerVoiceModel.XIAOXIAO -> "温柔女声 · 中文 / 英语"
                                ComputerVoiceModel.NANAMI -> "自然女声 · 日语"
                            }, style = InkType.Caption, color = p.textSecondary)
                        }
                        RadioButton(selected = isSelected, onClick = null, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.height(2.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.background).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("使用提示", style = InkType.Caption.copy(fontWeight = FontWeight.SemiBold), color = p.textPrimary)
                    Text(when (draft) {
                        ComputerVoiceModel.AUTO -> "自动识别章节语言。中文、英文使用晓晓，日语优先使用七海。"
                        ComputerVoiceModel.XIAOXIAO -> "固定使用晓晓，适合中文和英文。日文读音可能不完整，建议改用七海或自动。"
                        ComputerVoiceModel.NANAMI -> "固定使用七海，适合日语。中文、英文可能不自然，请先在电脑上安装七海音色。"
                    }, style = InkType.Caption.copy(lineHeight = 19.sp), color = p.textSecondary)
                }
                Text("选择会保存，试听立即使用。正在听书时，请停止后重新开始，让新模型生效。",
                    style = InkType.Caption.copy(lineHeight = 18.sp), color = p.textSecondary,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 3.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                InkTextButton("取消", modifier = Modifier.weight(1f), color = p.textSecondary, onClick = onDismiss)
                InkButton("保存选择", onClick = { onSave(draft) }, modifier = Modifier.weight(2f),
                    contentColor = if (p.isDark) p.background else null)
            }
        }
    }
}
