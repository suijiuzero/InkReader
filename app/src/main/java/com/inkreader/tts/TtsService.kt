package com.inkreader.tts

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.media.MediaPlayer
import android.media.AudioAttributes
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import android.widget.Toast
import java.io.File
import java.util.Locale

/**
 * 朗读服务：持有 TextToSpeech 引擎，逐句朗读并广播当前句区间，
 * UI 据此做高亮跟随与自动翻页。前台运行 + MediaSession，
 * 因此支持后台朗读、通知栏控制与耳机线控。
 */
class TtsService : Service(), TextToSpeech.OnInitListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate +
        CoroutineExceptionHandler { _, error ->
            Toast.makeText(this, error.localizedMessage ?: getString(R.string.action_unknown_error), Toast.LENGTH_LONG).show()
            stopEverything()
        })

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
    private var wantPlaying = false
    private var contentReady = false
    private var playbackGeneration = 0L
    private var requestGeneration = 0L
    private var queuedThrough = -1
    private var bookJob: Job? = null
    private var chapterJob: Job? = null
    private var sleepDeadline = 0L
    private val computerClient by lazy { ComputerVoiceClient(this) }
    private var computer: ComputerVoice? = null
    private var computerJob: Job? = null
    private var nextAudio: Pair<Int, Deferred<ByteArray>>? = null
    private var player: MediaPlayer? = null
    private var playerFile: File? = null
    private var computerProbeJob: Job? = null
    private var nextComputerProbeAt = 0L

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
        if (started && contentReady && wantPlaying) speakCurrent()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                bookJob?.cancel()
                chapterJob?.cancel()
                requestGeneration++
                invalidateQueue()
                contentReady = false
                wantPlaying = true
                bookId = intent.getLongExtra(EXTRA_BOOK, 0L)
                chapters = emptyList()
                text = ""
                sentences = emptyList()
                sentencePos = 0
                val requestedChapter = intent.getIntExtra(EXTRA_CHAPTER, 0)
                val offset = intent.getIntExtra(EXTRA_OFFSET, 0)
                val modeName = intent.getStringExtra(EXTRA_SLEEP)
                sleepMode = runCatching { SleepMode.valueOf(modeName ?: "OFF") }.getOrDefault(SleepMode.OFF)
                if (bookId <= 0L) {
                    stopEverything()
                    return START_NOT_STICKY
                }
                startAsForeground()
                started = true
                applySleepTimer(reset = true)
                beginBook(requestedChapter, offset)
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
                if (computer != null && started && wantPlaying) {
                    invalidateQueue()
                    speakCurrent()
                }
            }

            ACTION_SET_SLEEP -> {
                val modeName = intent.getStringExtra(EXTRA_SLEEP)
                sleepMode = runCatching { SleepMode.valueOf(modeName ?: "OFF") }.getOrDefault(SleepMode.OFF)
                applySleepTimer(reset = true)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseResources()
        scope.cancel()
        TtsBus.reset()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    // ==================== 播放控制 ====================

    private fun beginBook(chapterIndex: Int, offset: Int) {
        val requestedBook = bookId
        val generation = requestGeneration
        bookJob = scope.launch {
            val bk = repo.book(requestedBook)
            val chs = repo.chapters(requestedBook)
            if (!isActive || generation != requestGeneration || !started) return@launch
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
        chapterJob?.cancel()
        computerProbeJob?.cancel(); computerProbeJob = null
        invalidateQueue()
        contentReady = false
        val generation = ++requestGeneration
        val ch = chapters[index]
        chapterJob = scope.launch {
            val body = repo.chapterText(ch)
            val connected = withContext(Dispatchers.IO) { computerClient.connect() }
            if (!isActive || generation != requestGeneration || !started) return@launch
            nextComputerProbeAt = SystemClock.elapsedRealtime() + 30_000
            computer = connected
            TtsBus.update { it.copy(computerVoice = connected != null) }
            chapterPos = index
            text = body
            sentences = SentenceSplitter.split(body)
            sentencePos = if (offset < 0) sentences.lastIndex.coerceAtLeast(0) else if (offset > 0) {
                sentences.indexOfFirst { it.last >= offset }.let { if (it < 0) 0 else it }
            } else 0
            contentReady = true
            queuedThrough = sentencePos - 1
            TtsBus.update {
                it.copy(
                    active = true, playing = wantPlaying, bookId = bookId, bookTitle = bookTitle,
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
            if (wantPlaying) speakCurrent()
        }
    }

    private fun speakCurrent() {
        if (!contentReady || !wantPlaying || !started) { return }
        if (sentencePos !in sentences.indices) {
            nextChapter(); return
        }
        if (computer != null) {
            speakComputer()
            return
        }
        if (!ttsReady) return
        enqueueAhead()
        if (!wantPlaying) return
        publishState()
        startTicker()
    }

    private fun invalidateQueue() {
        playbackGeneration++
        queuedThrough = sentencePos - 1
        tts?.stop()
        clearComputerPlayback()
    }

    private fun clearComputerPlayback() {
        computerJob?.cancel(); computerJob = null
        nextAudio?.second?.cancel(); nextAudio = null
        runCatching { player?.release() }; player = null
        playerFile?.delete(); playerFile = null
    }

    /** One playing sentence and one bounded prefetch. Every callback belongs to one generation. */
    private fun speakComputer() {
        val voice = computer ?: return
        if (computerJob?.isActive == true || player != null) return
        val generation = playbackGeneration
        val index = sentencePos
        val range = sentences[index]
        val piece = text.substring(range.first, range.last + 1)
        val settings = prefs.load()
        val cached = nextAudio?.takeIf { it.first == index }?.second
        if (cached != null) nextAudio = null
        TtsBus.update { it.copy(buffering = true) }
        publishState()
        startTicker()
        computerJob = scope.launch {
            try {
                val bytes = cached?.await() ?: withContext(Dispatchers.IO) {
                    computerClient.synthesize(voice, piece, settings.ttsRate, settings.ttsPitch)
                }
                if (!isActive || generation != playbackGeneration || !started || !wantPlaying) return@launch
                val file = File.createTempFile("computer-voice-", ".wav", cacheDir)
                playerFile = file
                file.writeBytes(bytes)
                val audio = MediaPlayer()
                player = audio
                audio.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                audio.setDataSource(file.path)
                audio.setOnPreparedListener {
                    if (generation == playbackGeneration && started && wantPlaying && player === audio) {
                        audio.start()
                        TtsBus.update { it.copy(buffering = false) }
                        publishState()
                        startTicker()
                        prefetchComputer(index + 1, voice, settings.ttsRate, settings.ttsPitch)
                    }
                }
                audio.setOnCompletionListener {
                    if (generation == playbackGeneration && started && wantPlaying && player === audio) {
                        audio.release(); player = null
                        playerFile?.delete(); playerFile = null
                        sentencePos = index + 1
                        speakCurrent()
                    }
                }
                audio.setOnErrorListener { _, _, _ ->
                    if (generation == playbackGeneration && player === audio) computerFailed()
                    true
                }
                audio.prepareAsync()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (generation == playbackGeneration && started && wantPlaying) computerFailed()
            }
        }
    }

    private fun prefetchComputer(index: Int, voice: ComputerVoice, rate: Float, pitch: Float) {
        if (index !in sentences.indices || nextAudio != null) return
        val range = sentences[index]
        val piece = text.substring(range.first, range.last + 1)
        // async is supervised by the service scope; a failure is consumed at the sentence boundary.
        nextAudio = index to scope.async(Dispatchers.IO) { computerClient.synthesize(voice, piece, rate, pitch) }
    }

    private fun computerFailed() {
        computer = null
        nextComputerProbeAt = SystemClock.elapsedRealtime() + 30_000
        invalidateQueue()
        TtsBus.update { it.copy(computerVoice = false, buffering = false) }
        if (ttsReady) speakCurrent() else pauseSpeaking()
    }

    private fun enqueueAhead() {
        val engine = tts ?: return
        val end = minOf(sentences.lastIndex, sentencePos + PREFETCH)
        while (queuedThrough < end) {
            val index = queuedThrough + 1
            val range = sentences[index]
            val piece = text.substring(range.first, range.last + 1)
            if (engine.speak(piece, TextToSpeech.QUEUE_ADD, null, utteranceId(index)) == TextToSpeech.ERROR) {
                pauseSpeaking()
                return
            }
            queuedThrough = index
        }
    }

    private fun pauseSpeaking() {
        wantPlaying = false
        invalidateQueue()
        stopTicker()
        TtsBus.update { it.copy(playing = false, buffering = false) }
        updateNotification()
        updatePlaybackState(false)
    }

    private fun resumeSpeaking() {
        if (!TtsBus.state.value.active) return
        wantPlaying = true
        queuedThrough = sentencePos - 1
        TtsBus.update { it.copy(playing = true) }
        speakCurrent()
        updateNotification()
        updatePlaybackState(true)
    }

    private fun jumpSentence(delta: Int) {
        if (!TtsBus.state.value.active) return
        invalidateQueue()
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
        queuedThrough = sentencePos - 1
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
        started = false
        wantPlaying = false
        contentReady = false
        requestGeneration++
        playbackGeneration++
        clearComputerPlayback()
        computer = null
        computerProbeJob?.cancel(); computerProbeJob = null
        bookJob?.cancel(); bookJob = null
        chapterJob?.cancel(); chapterJob = null
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
            scope.launch {
                val idx = parseId(utteranceId) ?: return@launch
                if (!started || !wantPlaying) return@launch
                sentencePos = idx
                publishState()
            }
        }

        override fun onDone(utteranceId: String?) {
            scope.launch {
                val idx = parseId(utteranceId) ?: return@launch
                if (!started || !wantPlaying) return@launch
                if (idx < sentencePos) return@launch
                val nextIdx = idx + 1
                if (nextIdx < sentences.size) {
                    sentencePos = nextIdx
                    enqueueAhead()
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
                if (idx == null || !started || !wantPlaying) return@launch
                pauseSpeaking()
            }
        }
    }

    private fun publishState() {
        val range = sentences.getOrNull(sentencePos)
        TtsBus.update {
            it.copy(
                active = true,
                playing = wantPlaying,
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
        updatePlaybackState(wantPlaying)
    }

    private fun progressOf(): Float {
        if (text.isEmpty()) { return 0f }
        val r = sentences.getOrNull(sentencePos) ?: return 0f
        return (r.first.toFloat() / text.length).coerceIn(0f, 1f)
    }

    // ==================== 定时关闭 ====================

    private fun applySleepTimer(reset: Boolean = false) {
        if (!reset && sleepJob?.isActive == true) return
        sleepJob?.cancel()
        if (sleepMode == SleepMode.OFF || sleepMode == SleepMode.CHAPTER_END) {
            sleepDeadline = 0L
            TtsBus.update { it.copy(sleepRemainingMs = 0L) }
            return
        }
        val ms = sleepMode.minutes * 60_000L
        if (reset || sleepDeadline == 0L) sleepDeadline = SystemClock.elapsedRealtime() + ms
        val endAt = sleepDeadline
        sleepJob = scope.launch {
            while (isActive) {
                val remain = endAt - SystemClock.elapsedRealtime()
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
                if (computer == null && computerClient.enabled && wantPlaying && contentReady &&
                    computerProbeJob?.isActive != true && SystemClock.elapsedRealtime() >= nextComputerProbeAt) {
                    val generation = requestGeneration
                    nextComputerProbeAt = SystemClock.elapsedRealtime() + 30_000
                    computerProbeJob = scope.launch {
                        val connected = withContext(Dispatchers.IO) { computerClient.connect() }
                        if (connected != null && isActive && generation == requestGeneration && started && wantPlaying && computer == null) {
                            computer = connected
                            invalidateQueue()
                            TtsBus.update { it.copy(computerVoice = true) }
                            speakCurrent()
                        }
                    }
                }
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
        val savedBookId = bookId
        val token = repo.newProgressToken(savedBookId)
        (application as InkApp).container.scope.launch {
            repo.updateProgress(savedBookId, idx, off, progress, token)
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

    private fun utteranceId(index: Int) = "ink_${playbackGeneration}_$index"
    private fun parseId(id: String?): Int? =
        id?.takeIf { it.startsWith("ink_${playbackGeneration}_") }
            ?.substringAfterLast('_')?.toIntOrNull()?.takeIf { it in sentences.indices }

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
