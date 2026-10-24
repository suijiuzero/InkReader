package com.inkreader.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 朗读全局状态。UI 只读，TtsService 写入。 */
data class TtsState(
    val active: Boolean = false,
    val playing: Boolean = false,
    val ready: Boolean = false,
    val bookId: Long = 0,
    val bookTitle: String = "",
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val totalChapters: Int = 0,
    val sentenceStart: Int = -1,
    val sentenceEnd: Int = -1,
    val chapterProgress: Float = 0f,
    val sleepRemainingMs: Long = 0L,
    val rate: Float = 1f,
    val pitch: Float = 1f,
    val engineMissing: Boolean = false,
    val computerVoice: Boolean = false,
    val computerModel: String = "自动",
    val buffering: Boolean = false
)

object TtsBus {
    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state.asStateFlow()

    fun update(block: (TtsState) -> TtsState) {
        _state.value = block(_state.value)
    }

    fun reset() {
        _state.value = TtsState()
    }
}

/** 定时关闭模式 */
enum class SleepMode(val minutes: Int) {
    OFF(0), MIN15(15), MIN30(30), MIN60(60), CHAPTER_END(-1)
}
