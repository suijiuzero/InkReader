package com.inkreader.ui.component

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * 自定义封面的内存缓存。
 *
 * 封面图存在 `filesDir/covers/<名字>`，这里按「文件 + 最后修改时间」缓存解码结果，
 * 避免每次重组、每次滚动都把 JPEG 重新解一遍。
 * 换封面/恢复默认封面后调 [invalidate]，version 变化会让所有封面重新读一次。
 */
object CoverCache {

    /** 封面发生变更时自增；BookCover 把它当 remember 的 key */
    var version by mutableStateOf(0)
        private set

    private val map = object : android.util.LruCache<String, Pair<Long, ImageBitmap>>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Pair<Long, ImageBitmap>): Int =
            value.second.width * value.second.height * 4
    }

    fun invalidate() {
        map.evictAll()
        version++
    }

    fun load(context: Context, name: String): ImageBitmap? {
        if (name.isBlank()) return null
        val f = File(File(context.filesDir, "covers"), name)
        if (!f.isFile) return null
        val key = f.absolutePath
        val mtime = f.lastModified()
        map[key]?.let { (t, bmp) -> if (t == mtime) return bmp }
        val decoded = runCatching {
            BitmapFactory.Options().let { bounds ->
                bounds.inJustDecodeBounds = true
                BitmapFactory.decodeFile(key, bounds)
                val options = BitmapFactory.Options()
                while (bounds.outWidth / options.inSampleSize > 800 || bounds.outHeight / options.inSampleSize > 1200)
                    options.inSampleSize *= 2
                BitmapFactory.decodeFile(key, options)?.asImageBitmap()
            }
        }.getOrNull() ?: return null
        map.put(key, mtime to decoded)
        return decoded
    }
}
