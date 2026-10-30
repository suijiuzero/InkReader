package com.inkreader.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.inkreader.R
import com.inkreader.parse.DocumentImages
import com.inkreader.ui.theme.LocalInkPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private object ReaderImageCache {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, bitmap: Bitmap) = bitmap.allocationByteCount
    }
    fun load(file: File, side: Int): Bitmap? {
        val key = "${file.path}:${file.lastModified()}:${file.length()}:$side"
        cache.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val budget = if (side > 1024) 8L * 1024 * 1024 else 1024L * 1024
        while (bounds.outWidth / sample > side || bounds.outHeight / sample > side ||
            bounds.outWidth.toLong() * bounds.outHeight / sample / sample > budget) sample *= 2
        var bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        if (bounds.outMimeType == "image/jpeg") {
            val orientation = file.inputStream().use { com.inkreader.parse.DocumentImageOrientation.read(it) }
            val matrix = com.inkreader.parse.DocumentImageOrientation.matrix(orientation)
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) bitmap.recycle()
                bitmap = rotated
            }
        }
        cache.put(key, bitmap)
        // Do not recycle evicted images: a visible Compose frame may still reference them.
        return bitmap
    }
}

private data class ImageLoad(val bitmap: Bitmap? = null, val done: Boolean = false)

@Composable
internal fun ReaderImageBlock(marker: String, reservedHeightPx: Float, imageFile: ((String) -> File)?) {
    val reference = remember(marker) { DocumentImages.reference(marker) }
    val file = remember(reference?.key, imageFile) { reference?.let { imageFile?.invoke(it.key) } }
    val loaded by produceState(ImageLoad(), file?.path, file?.lastModified()) {
        value = ImageLoad(withContext(Dispatchers.IO) { file?.let { runCatching { ReaderImageCache.load(it, 1024) }.getOrNull() } }, true)
    }
    val bitmap = loaded.bitmap
    val palette = LocalInkPalette.current
    val density = LocalDensity.current
    var expanded by remember(marker) { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val width = minOf(maxWidth, (reference?.width ?: 240).dp)
        val height = if (reservedHeightPx > 0) with(density) { reservedHeightPx.toDp() }
            else minOf(800.dp, width * ((reference?.height ?: 180).toFloat() / (reference?.width ?: 240)))
        Box(Modifier.width(width).height(if (reservedHeightPx > 0) height.coerceAtLeast(1.dp) else height.coerceAtLeast(36.dp)).clipToBounds().background(Color.White)
            .clickable(enabled = bitmap != null) { expanded = true }, contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.reader_image), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                ?: Text(stringResource(if (loaded.done) R.string.reader_image_unavailable else R.string.reader_image_loading), color = palette.textSecondary)
        }
    }
    if (expanded && file != null) {
        val full by produceState<Bitmap?>(bitmap, file.path) {
            value = withContext(Dispatchers.IO) { runCatching { ReaderImageCache.load(file, 4096) }.getOrNull() ?: bitmap }
        }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Dialog({ expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clipToBounds()) {
                full?.let { picture ->
                    Image(picture.asImageBitmap(), stringResource(R.string.reader_image),
                        Modifier.fillMaxSize().pointerInput(file.path) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 8f)
                                val limitX = size.width * (scale - 1) / 2
                                val limitY = size.height * (scale - 1) / 2
                                offset = if (scale <= 1f) Offset.Zero else Offset(
                                    (offset.x + pan.x).coerceIn(-limitX, limitX), (offset.y + pan.y).coerceIn(-limitY, limitY))
                            }
                        }.graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                        .drawBehind {
                            val fit = minOf(size.width / picture.width, size.height / picture.height)
                            val width = picture.width * fit; val height = picture.height * fit
                            drawRect(Color.White, Offset((size.width - width) / 2, (size.height - height) / 2), Size(width, height))
                        },
                        contentScale = ContentScale.Fit)
                }
                Text(stringResource(R.string.reader_image_close), color = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp)
                        .background(Color.Black.copy(alpha = .6f)).clickable { expanded = false }.padding(12.dp))
            }
        }
    }
}
