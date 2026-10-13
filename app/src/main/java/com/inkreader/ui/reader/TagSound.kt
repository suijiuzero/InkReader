package com.inkreader.ui.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.util.Log
import com.inkreader.R

/**
 * 打标签时那一声「咚」。
 *
 * 用 SoundPool 预加载（0.42s / 18KB 的 wav，解码一次常驻）。
 * 走的是**媒体音量**：静音模式（铃声静音）下照常出声，和番茄这类阅读 App 的行为一致；
 * 只有把媒体音量拖到 0 才没声。
 */
object TagSound {

    private const val TAG = "InkTag"

    private var pool: SoundPool? = null
    private var soundId = 0
    private var ready = false

    /** 在 Application.onCreate 里预加载，免得第一次打标签是哑的 */
    fun preload(context: Context) {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        p.setOnLoadCompleteListener { _, _, status ->
            ready = status == 0
            Log.i(TAG, "tag sound loaded, status=$status ready=$ready")
        }
        soundId = p.load(context.applicationContext, R.raw.tag_dong, 1)
        pool = p
    }

    fun play(context: Context) {
        if (pool == null) preload(context)
        val p = pool ?: return
        if (!ready) {
            Log.i(TAG, "skip sound: not loaded yet")
            return
        }
        val am = context.getSystemService(AudioManager::class.java)
        val vol = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 1
        Log.i(TAG, "play tag sound, mediaVolume=$vol")
        runCatching { p.play(soundId, 0.95f, 0.95f, 1, 0, 1f) }
    }
}
