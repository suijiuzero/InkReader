package com.inkreader.tts

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.inkreader.InkApp
import com.inkreader.MainActivity
import com.inkreader.R
import com.inkreader.data.model.Chapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 朗读服务：持有 TextToSpeech 引擎，逐句朗读并广播当前句区间，
 * UI 据此做高亮跟随与自动翻页。前台运行 + MediaSession，
 * 因此支持后台朗读、通知栏控制与耳机线控。
 */
class TtsService : Service(), TextToSpeech.OnInitListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var mediaSession: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sleepJob: Job? = null
    private var tickJob: Job? = null

    private var bookId = 0L
    private var bookTitle = ""
    private var chapters: List<Chapter> = emptyList()
    private var chapterPos = 0
    private var text = ""
    private var sentences: List<IntRange> = emptyList()
    private var sentencePos = 0
    private var sleepMode = SleepMode.OFF
    private var started = false

    private val repo get() = (application as InkApp).container.repo
    private val prefs get() = (application as InkApp).container.prefs

    // ==================== 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        val session = MediaSessionCompat(this, "InkReaderTTS").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resumeSpeaking()
                override fun onPause() = pauseSpeaking()
                override fun onStop() = stopEverything()
                override fun onSkipToNext() = jumpSentence(1)
                override fun onSkipToPrevious() = jumpSentence(-1)
            })
            setActive(true)
        }
        mediaSession = session
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            TtsBus.update { it.copy(engineMissing = true, ready = false) }
            return
        }
        val engine = tts ?: return
        val result = engine.setLanguage(Locale.CHINESE)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.getDefault())
        }
        engine.setOnUtteranceProgressListener(progressListener)
        val p = prefs.load()
        engine.setSpeechRate(p.ttsRate.coerceIn(0.5f, 2.5f))
        engine.setPitch(p.ttsPitch.coerceIn(0.5f, 2f))
        ttsReady = true
        TtsBus.update { it.copy(ready = true, engineMissing = false) }
        if (started) speakCurrent()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                bookId = intent.getLongExtra(EXTRA_BOOK, 0L)
                chapterPos = intent.getIntExtra(EXTRA_CHAPTER, 0)
                val offset = intent.getIntExtra(EXTRA_OFFSET, 0)
                val modeName = intent.getStringExtra(EXTRA_SLEEP)
                sleepMode = runCatching { SleepMode.valueOf(modeName ?: "OFF") }.getOrDefault(SleepMode.OFF)
                if (bookId <= 0L) {
                    stopEverything()
                    return START_NOT_STICKY
                }
                startAsForeground()
                started = true
                beginBook(chapterPos, offset)
            }

            ACTION_PAUSE, ACTION_RESUME, ACTION_NEXT, ACTION_PREV -> {
                if (!started) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                when (intent.action) {
                    ACTION_PAUSE -> pauseSpeaking()
                    ACTION_RESUME -> resumeSpeaking()
                    ACTION_NEXT -> jumpSentence(1)
                    ACTION_PREV -> jumpSentence(-1)
                }
            }

            ACTION_STOP -> stopEverything()
            ACTION_SET_RATE -> {
                val r = intent.getFloatExtra(EXTRA_RATE, 1f)
                val p = intent.getFloatExtra(EXTRA_PITCH, 1f)
                tts?.setSpeechRate(r.coerceIn(0.5f, 2.5f))
                tts?.setPitch(p.coerceIn(0.5f, 2f))
                val saved = prefs.load()
                prefs.save(saved.copy(ttsRate = r, ttsPitch = p))
                TtsBus.update { it.copy(rate = r, pitch = p) }
            }

            ACTION_SET_SLEEP -> {
                val modeName = intent.getStringExtra(EXTRA_SLEEP)
                sleepMode = runCatching { SleepMode.valueOf(modeName ?: "OFF") }.getOrDefault(SleepMode.OFF)
                applySleepTimer()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseResources()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    // ==================== 播放控制 ====================

    private fun beginBook(chapterIndex: Int, offset: Int) {
        scope.launch {
            val bk = withContext(Dispatchers.IO) { repo.book(bookId) }
            val chs = withContext(Dispatchers.IO) { repo.chapters(bookId) }
            if (bk == null || chs.isEmpty()) {
                stopEverything(); return@launch
            }
            bookTitle = bk.title
            chapters = chs
            chapterPos = chapterIndex.coerceIn(0, chs.lastIndex)
            loadChapter(chapterPos, offset)
        }
    }

    private fun loadChapter(index: Int, offset: Int = 0) {
        if (index !in chapters.indices) {
            stopEverything()
            return
        }
        scope.launch {
            val ch = chapters[index]
            val body = withContext(Dispatchers.IO) { repo.chapterText(ch) }
            chapterPos = index
            text = body
            sentences = SentenceSplitter.split(body)
            sentencePos = if (offset > 0) {
                sentences.indexOfFirst { it.last >= offset }.let { if (it < 0) 0 else it }
            } else 0
            TtsBus.update {
                it.copy(
                    active = true, playing = true, bookId = bookId, bookTitle = bookTitle,
                    chapterIndex = index, chapterTitle = ch.title, totalChapters = chapters.size,
                    sentenceStart = sentences.getOrNull(sentencePos)?.first ?: -1,
                    sentenceEnd = sentences.getOrNull(sentencePos)?.last ?: -1,
                    chapterProgress = progressOf()
                )
            }
            updateMetadata(ch.title)
            if (sentences.isEmpty()) {
                // 空章节，直接下一章
                nextChapter()
                return@launch
            }
            applySleepTimer()
            saveProgress()
            speakCurrent()
        }
    }

    private fun speakCurrent() {
        if (!ttsReady) return
        val engine = tts ?: return
        if (sentencePos !in sentences.indices) {
            nextChapter(); return
        }
        val range = sentences[sentencePos]
        val piece = text.substring(range.first.coerceIn(0, text.length), range.last.coerceAtMost(text.length - 1) + 1)
        if (piece.isBlank()) {
            sentencePos++
            speakCurrent()
            return
        }
        // 首次进入用 FLUSH 清掉旧队列，后续用 ADD 排队，避免断句间隙
        val queueMode = if (sentencePos == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(piece, queueMode, null, utteranceId(sentencePos))
        publishState()
        // 预排后面几句，减少衔接停顿
        var ahead = sentencePos + 1
        var count = 0
        while (ahead < sentences.size && count < PREFETCH) {
            val r = sentences[ahead]
            val s = text.substring(r.first, (r.last.coerceAtMost(text.length - 1)) + 1)
            if (s.isNotBlank()) {
                engine.speak(s, TextToSpeech.QUEUE_ADD, null, utteranceId(ahead))
                count++
            }
            ahead++
        }
        startTicker()
    }

    private fun pauseSpeaking() {
        tts?.stop()
        stopTicker()
        TtsBus.update { it.copy(playing = false) }
        updateNotification()
        updatePlaybackState(false)
    }

    private fun resumeSpeaking() {
        if (!TtsBus.state.value.active) return
        TtsBus.update { it.copy(playing = true) }
        speakCurrent()
        updateNotification()
        updatePlaybackState(true)
    }

    private fun jumpSentence(delta: Int) {
        if (!TtsBus.state.value.active) return
        tts?.stop()
        val target = sentencePos + delta
        if (target < 0) {
            // 回上一章末尾
            if (chapterPos > 0) {
                loadChapter(chapterPos - 1, -1)
                return
            }
            sentencePos = 0
        } else if (target >= sentences.size) {
            nextChapter(); return
        } else {
            sentencePos = target
        }
        speakCurrent()
    }

    private fun nextChapter() {
        saveProgress()
        val next = chapterPos + 1
        if (next >= chapters.size) {
            stopEverything()
            return
        }
        if (sleepMode == SleepMode.CHAPTER_END) {
            stopEverything()
            return
        }
        loadChapter(next, 0)
    }

    private fun stopEverything() {
        saveProgress()
        releaseResources()
        TtsBus.reset()
        stopSelf()
    }

    private fun releaseResources() {
        stopTicker()
        sleepJob?.cancel(); sleepJob = null
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
        runCatching { mediaSession?.release() }
        mediaSession = null
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
        started = false
        stopForegroundCompat()
    }

    // ==================== TTS 回调 ====================

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val idx = parseId(utteranceId) ?: return
            scope.launch {
                sentencePos = idx
                publishState()
            }
        }

        override fun onDone(utteranceId: String?) {
            val idx = parseId(utteranceId) ?: return
            scope.launch {
                if (idx < sentencePos) return@launch
                val nextIdx = idx + 1
                if (nextIdx < sentences.size) {
                    sentencePos = nextIdx
                    publishState()
                } else {
                    nextChapter()
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            scope.launch {
                val idx = parseId(utteranceId)
                if (idx == null) return@launch
                val nextIdx = idx + 1
                if (nextIdx < sentences.size) {
                    sentencePos = nextIdx
                    publishState()
                } else nextChapter()
            }
        }
    }

    private fun publishState() {
        val range = sentences.getOrNull(sentencePos)
        TtsBus.update {
            it.copy(
                active = true,
                playing = true,
                bookId = bookId,
                bookTitle = bookTitle,
                chapterIndex = chapterPos,
                chapterTitle = chapters.getOrNull(chapterPos)?.title ?: "",
                totalChapters = chapters.size,
                sentenceStart = range?.first ?: -1,
                sentenceEnd = range?.last ?: -1,
                chapterProgress = progressOf()
            )
        }
        updateNotification()
        updatePlaybackState(true)
    }

    private fun progressOf(): Float {
        if (text.isEmpty()) return 0f
        val r = sentences.getOrNull(sentencePos) ?: return 0f
        return (r.first.toFloat() / text.length).coerceIn(0f, 1f)
    }

    // ==================== 定时关闭 ====================

    private fun applySleepTimer() {
        sleepJob?.cancel()
        if (sleepMode == SleepMode.OFF || sleepMode == SleepMode.CHAPTER_END) {
            TtsBus.update { it.copy(sleepRemainingMs = 0L) }
            return
        }
        val ms = sleepMode.minutes * 60_000L
        val endAt = System.currentTimeMillis() + ms
        sleepJob = scope.launch {
            while (isActive) {
                val remain = endAt - System.currentTimeMillis()
                if (remain <= 0) {
                    stopEverything()
                    break
                }
                TtsBus.update { it.copy(sleepRemainingMs = remain) }
                delay(1000)
            }
        }
    }

    private fun startTicker() {
        if (tickJob?.isActive == true) return
        acquireWakeLock()
        tickJob = scope.launch {
            while (isActive) {
                delay(3000)
                saveProgress()
            }
        }
    }

    private fun stopTicker() {
        tickJob?.cancel(); tickJob = null
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InkReader::tts").apply {
                setReferenceCounted(false)
                acquire(2 * 60 * 60 * 1000L)
            }
        }
    }

    private fun saveProgress() {
        if (bookId <= 0L || chapters.isEmpty()) return
        val idx = chapterPos
        val off = sentences.getOrNull(sentencePos)?.first ?: 0
        val ch = chapters[idx]
        // 整本书进度：已完成章节字数 + 当前章内偏移
        val doneChars = chapters.take(idx).sumOf { it.charCount.toLong() }
        val total = chapters.sumOf { it.charCount.toLong() }.coerceAtLeast(1)
        val progress = ((doneChars + off) / total.toFloat()).coerceIn(0f, 1f)
        scope.launch {
            withContext(Dispatchers.IO) { repo.updateProgress(bookId, idx, off, progress) }
        }
        TtsBus.update { it.copy(chapterIndex = idx) }
    }

    // ==================== 前台通知 ====================

    private fun startAsForeground() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    private fun buildNotification(): android.app.Notification {
        val s = TtsBus.state.value
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_BOOK, bookId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, InkApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle(s.chapterTitle.ifBlank { bookTitle })
            .setContentText(bookTitle)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_prev, getString(R.string.tts_prev_sentence),
                actionIntent(ACTION_PREV, 11)
            )
            .addAction(
                if (s.playing) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (s.playing) R.string.tts_pause else R.string.tts_resume),
                actionIntent(if (s.playing) ACTION_PAUSE else ACTION_RESUME, 12)
            )
            .addAction(
                R.drawable.ic_next, getString(R.string.tts_next_sentence),
                actionIntent(ACTION_NEXT, 13)
            )
            .addAction(
                R.drawable.ic_close, getString(R.string.tts_stop),
                actionIntent(ACTION_STOP, 14)
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        return builder.build()
    }

    private fun actionIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, TtsService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun updateNotification() {
        if (!started) return
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        }
    }

    private fun updateMetadata(chapterTitle: String) {
        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, chapterTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, bookTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getString(R.string.app_name))
                .build()
        )
    }

    private fun updatePlaybackState(playing: Boolean) {
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    sentencePos.toLong(), 1f
                )
                .build()
        )
    }

    private fun utteranceId(index: Int) = "ink_$index"
    private fun parseId(id: String?): Int? =
        id?.takeIf { it.startsWith("ink_") }?.removePrefix("ink_")?.toIntOrNull()

    companion object {
        const val ACTION_START = "com.inkreader.tts.START"
        const val ACTION_PAUSE = "com.inkreader.tts.PAUSE"
        const val ACTION_RESUME = "com.inkreader.tts.RESUME"
        const val ACTION_STOP = "com.inkreader.tts.STOP"
        const val ACTION_NEXT = "com.inkreader.tts.NEXT"
        const val ACTION_PREV = "com.inkreader.tts.PREV"
        const val ACTION_SET_RATE = "com.inkreader.tts.SET_RATE"
        const val ACTION_SET_SLEEP = "com.inkreader.tts.SET_SLEEP"

        const val EXTRA_BOOK = "book_id"
        const val EXTRA_CHAPTER = "chapter_index"
        const val EXTRA_OFFSET = "offset"
        const val EXTRA_RATE = "rate"
        const val EXTRA_PITCH = "pitch"
        const val EXTRA_SLEEP = "sleep"
        const val EXTRA_OPEN_BOOK = "open_book"

        private const val NOTIF_ID = 20261008
        private const val PREFETCH = 2

        fun startReading(context: Context, bookId: Long, chapterIndex: Int, offset: Int, sleep: SleepMode) {
            val i = Intent(context, TtsService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_BOOK, bookId)
                putExtra(EXTRA_CHAPTER, chapterIndex)
                putExtra(EXTRA_OFFSET, offset)
                putExtra(EXTRA_SLEEP, sleep.name)
            }
            startServiceCompat(context, i)
        }

        fun send(context: Context, action: String) {
            startServiceCompat(context, Intent(context, TtsService::class.java).setAction(action))
        }

        fun setVoice(context: Context, rate: Float, pitch: Float) {
            val i = Intent(context, TtsService::class.java).apply {
                action = ACTION_SET_RATE
                putExtra(EXTRA_RATE, rate)
                putExtra(EXTRA_PITCH, pitch)
            }
            startServiceCompat(context, i)
        }

        fun setSleep(context: Context, mode: SleepMode) {
            val i = Intent(context, TtsService::class.java).apply {
                action = ACTION_SET_SLEEP
                putExtra(EXTRA_SLEEP, mode.name)
            }
            startServiceCompat(context, i)
        }

        /**
         * 只有「开始朗读」需要把服务提升为前台服务；
         * 暂停/上下句/停止这类控制动作用普通 startService——
         * 服务此时已在前台，进程不受后台启动限制，
         * 同时避免每次控制都触发「5 秒内必须再次 startForeground」的约束。
         */
        private fun startServiceCompat(context: Context, intent: Intent) {
            runCatching {
                val needForeground = intent.action == ACTION_START &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                if (needForeground) context.startForegroundService(intent)
                else context.startService(intent)
            }
        }
    }
}
