package com.inkreader.ui.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkreader.R
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** 书封宽高比：和书架、详情页一致，裁出来不会被拉伸 */
private const val COVER_RATIO = 0.70f

/** 输出尺寸（宽 × 高） */
private const val OUT_W = 700
private val OUT_H = (OUT_W / COVER_RATIO).roundToInt()

/**
 * 封面裁切浮层，交互照手机设壁纸那样：
 *   · 图片可拖动平移、双指缩放；
 *   · 中间是一个固定 0.70 宽高比的裁切框，框内就是最终封面；
 *   · 平移范围被限制住，怎么拖都不会把图拖出框外露出黑边。
 */
@Composable
fun CoverCropDialog(
    source: Bitmap,
    onCancel: () -> Unit,
    onConfirm: (ByteArray) -> Unit
) {
    val p = LocalInkPalette.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var scale by remember { mutableFloatStateOf(1f) }
    var offX by remember { mutableFloatStateOf(0f) }
    var offY by remember { mutableFloatStateOf(0f) }
    var frameW by remember { mutableFloatStateOf(0f) }
    var frameH by remember { mutableFloatStateOf(0f) }
    var working by remember { mutableStateOf(false) }

    val bw = source.width.toFloat()
    val bh = source.height.toFloat()

    /** 把平移量夹在「图始终盖满裁切框」的范围内 */
    fun clampOffset() {
        if (frameW <= 0f || frameH <= 0f) return
        val k = max(frameW / bw, frameH / bh)
        val maxX = ((bw * k * scale - frameW) / 2f).coerceAtLeast(0f)
        val maxY = ((bh * k * scale - frameH) / 2f).coerceAtLeast(0f)
        offX = offX.coerceIn(-maxX, maxX)
        offY = offY.coerceIn(-maxY, maxY)
    }

    fun cropToJpeg(): ByteArray? {
        if (frameW <= 0f || frameH <= 0f) return null
        val k = max(frameW / bw, frameH / bh)
        // 裁切框内的屏幕坐标 → 原图像素坐标
        fun mapX(px: Float) = ((px - frameW / 2f - offX) / scale) / k + bw / 2f
        fun mapY(py: Float) = ((py - frameH / 2f - offY) / scale) / k + bh / 2f
        val src = RectF(
            mapX(0f).coerceIn(0f, bw),
            mapY(0f).coerceIn(0f, bh),
            mapX(frameW).coerceIn(0f, bw),
            mapY(frameH).coerceIn(0f, bh)
        )
        if (src.width() < 8f || src.height() < 8f) return null
        return runCatching {
            val out = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888)
            val matrix = Matrix().apply {
                setRectToRect(src, RectF(0f, 0f, OUT_W.toFloat(), OUT_H.toFloat()), Matrix.ScaleToFit.FILL)
            }
            Canvas(out).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            val bytes = java.io.ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 88, bytes)
            out.recycle()
            bytes.toByteArray()
        }.getOrNull()
    }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xF00A0C11))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    // 以裁切框中心为基准缩放，拖动直接累加
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offX += pan.x
                    offY += pan.y
                    clampOffset()
                }
            }
    ) {
        val fwDp = minOf(maxWidth * 0.72f, (maxHeight * 0.56f) * COVER_RATIO)
        val fhDp = fwDp / COVER_RATIO
        LaunchedEffect(fwDp, fhDp) {
            frameW = with(density) { fwDp.toPx() }
            frameH = with(density) { fhDp.toPx() }
            clampOffset()
        }

        Column(
            Modifier.fillMaxSize().padding(top = topInset, bottom = bottomInset),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.cover_title),
                style = InkType.Title.copy(fontSize = 17.sp),
                color = Color.White
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.cover_hint),
                style = InkType.Caption.copy(fontSize = 11.5.sp),
                color = Color(0x99FFFFFF)
            )
            Spacer(Modifier.height(18.dp))

            Box(
                Modifier
                    .size(fwDp, fhDp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black)
                    .border(2.dp, Color(0xCCFFFFFF), RoundedCornerShape(14.dp))
            ) {
                Image(
                    bitmap = source.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offX
                            translationY = offY
                        }
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.cover_ratio_hint, COVER_RATIO),
                style = InkType.Caption.copy(fontSize = 11.sp),
                color = Color(0x80FFFFFF)
            )
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = bottomInset + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CropButton(
                text = stringResource(R.string.action_cancel),
                filled = false,
                modifier = Modifier.weight(1f),
                onClick = { if (!working) onCancel() }
            )
            CropButton(
                text = if (working) stringResource(R.string.action_loading)
                else stringResource(R.string.action_confirm),
                filled = true,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (working) return@CropButton
                    working = true
                    scope.launch {
                        val bytes = withContext(Dispatchers.Default) { cropToJpeg() }
                        working = false
                        if (bytes != null) onConfirm(bytes) else onCancel()
                    }
                }
            )
        }
    }
}

@Composable
private fun CropButton(
    text: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val p = LocalInkPalette.current
    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (filled) p.accent else Color(0x1FFFFFFF))
            .border(1.dp, if (filled) Color.Transparent else Color(0x33FFFFFF), RoundedCornerShape(999.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = InkType.UiLabel.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = Color.White
        )
    }
}
