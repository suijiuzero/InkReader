package com.inkreader.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechLanguageTest {
    @Test fun japaneseChapterRetainsKanjiOnlyContext() {
        val language = SpeechLanguage.chapter("今日はいい天気です。東京へ戻りました。")
        assertEquals("ja-JP", language)
        assertEquals("ja-JP", SpeechLanguage.sentence("東京。", language))
        assertEquals("zh-CN", SpeechLanguage.sentence("Hello world.", language))
    }

    @Test fun chineseEnglishAndMixedSentencesRoutePredictably() {
        assertEquals("zh-CN", SpeechLanguage.chapter("这是中文正文。".repeat(50) + "こんにちは"))
        assertEquals("ja-JP", SpeechLanguage.sentence("彼女说：こんにちは。"))
        assertEquals("ja-JP", SpeechLanguage.sentence("ｺﾝﾆﾁﾊ"))
        assertEquals("zh-CN", SpeechLanguage.sentence("普通中文。"))
        assertEquals("zh-CN", SpeechLanguage.chapter("Hello world."))
        assertEquals("zh-CN", SpeechLanguage.chapter(""))
    }
}
