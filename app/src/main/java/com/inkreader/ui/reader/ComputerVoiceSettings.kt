package com.inkreader.ui.reader

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.inkreader.tts.ComputerVoiceClient
import com.inkreader.tts.ComputerVoiceModel
import com.inkreader.tts.TtsBus
import com.inkreader.ui.component.InkTextButton
import com.inkreader.ui.component.InkTextField
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ComputerVoiceSettings(previewLanguage: String = "zh-CN") {
    val context = LocalContext.current
    val client = remember { ComputerVoiceClient(context) }
    val scope = rememberCoroutineScope()
    val palette = LocalInkPalette.current
    var paired by remember { mutableStateOf(client.paired) }
    var enabled by remember { mutableStateOf(client.enabled) }
    var code by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(client.model) }
    var showModels by remember { mutableStateOf(false) }
    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var previewFile by remember { mutableStateOf<File?>(null) }
    fun releasePreview() {
        runCatching { previewPlayer?.release() }; previewPlayer = null
        previewFile?.delete(); previewFile = null
    }
    fun startPreview() {
        if (TtsBus.state.value.playing) { message = "请先暂停听书，再试听"; return }
        if (busy) return
        scope.launch {
            busy = true
            message = "正在准备试听…"
            val chosen = model
            val japanese = chosen == ComputerVoiceModel.NANAMI || (chosen == ComputerVoiceModel.AUTO && previewLanguage == "ja-JP")
            val name = if (chosen == ComputerVoiceModel.AUTO) "自动" else chosen.title
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val voice = client.connect() ?: error("电脑未连接，请开启电脑语音服务并检查同一 Wi-Fi")
                    val sample = if (japanese) "こんにちは。墨阅につながりました。日本語の本を読みましょう。" else "你好，我是晓晓。墨阅已经连接到电脑，现在可以用我的声音为你读书了。"
                    client.synthesize(voice, sample, 1f, 1f, if (japanese) "ja-JP" else "zh-CN", chosen)
                }
                releasePreview()
                val file = File.createTempFile("computer-preview-", ".wav", context.cacheDir)
                previewFile = file
                file.writeBytes(bytes)
                previewPlayer = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    setDataSource(file.path)
                    setOnPreparedListener { it.start(); message = "正在试听$name，请检查手机媒体音量" }
                    setOnCompletionListener { releasePreview(); busy = false; message = "试听完成 · $name" }
                    setOnErrorListener { _, _, _ -> releasePreview(); busy = false; message = "试听播放失败，请重试"; true }
                    prepareAsync()
                }
            } catch (cancelled: CancellationException) { releasePreview(); throw cancelled
            } catch (error: Exception) { releasePreview(); busy = false; message = error.message ?: "试听失败" }
        }
    }
    DisposableEffect(Unit) { onDispose { releasePreview() } }
    if (showModels) VoiceModelDialog(selected = model, onDismiss = { showModels = false }, onSave = {
        model = it; client.model = it; showModels = false
        message = "已选择${it.title}，试听立即使用；听书请停止后重新开始"
    })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("电脑语音", style = InkType.Body, color = palette.textPrimary)
            if (paired) Switch(checked = enabled, onCheckedChange = {
                enabled = it; client.enabled = it
                message = "下次开始听书时生效"
            })
        }
        Text("同一 Wi-Fi 下自动连接，电脑离线时用手机语音。首次请启动电脑上的墨阅语音服务。", style = InkType.Caption, color = palette.textSecondary)
        Text("当前模型：${model.title}", style = InkType.Caption, color = palette.textSecondary)
        if (!paired) {
            InkTextField(value = code, onValueChange = { code = it.take(24) }, placeholder = "电脑页面上的 16 位配对码")
            InkTextField(value = host, onValueChange = { host = it.take(15) }, placeholder = "电脑 IP（可留空，自动查找）")
            InkTextButton(if (busy) "正在连接…" else "连接电脑语音") {
                if (!busy) scope.launch {
                    busy = true
                    try {
                        withContext(Dispatchers.IO) { client.pair(code, host) }
                        paired = true; enabled = true; code = ""
                        message = "已配对，下次开始听书时自动使用电脑语音"
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (error: Exception) { message = error.message ?: "连接失败"
                    } finally { busy = false }
                }
            }
        } else {
            Text(if (enabled) "已配对 · 自动连接已开启" else "已配对 · 自动连接已关闭", style = InkType.Caption, color = palette.accent)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InkTextButton(if (busy) "请稍候…" else "测试连接", modifier = Modifier.weight(1f)) {
                    if (!busy) scope.launch {
                        busy = true
                        try {
                            val voice = withContext(Dispatchers.IO) { client.connect() }
                            message = if (voice != null) {
                                if ("ja-JP" in voice.languages) "已连接电脑 · 晓晓和日语音色可用" else "已连接电脑 · 晓晓可用；日语请更新电脑服务并安装七海音色"
                            } else if (!enabled) "请先开启电脑语音" else "电脑未连接，请检查电脑服务和同一 Wi-Fi"
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (_: Exception) { message = "连接失败，请检查电脑语音服务"
                        } finally { busy = false }
                    }
                }
                InkTextButton("试听语音", modifier = Modifier.weight(1f)) {
                    startPreview()
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InkTextButton("取消配对", modifier = Modifier.weight(1f)) {
                    if (!busy) { releasePreview(); client.forget(); paired = false; enabled = false; message = "已取消配对" }
                }
                InkTextButton("选择朗读模型", modifier = Modifier.weight(1f)) {
                    if (!busy) showModels = true
                }
            }
        }
        if (message.isNotEmpty()) Text(message, style = InkType.Caption, color = palette.accent)
    }
}
