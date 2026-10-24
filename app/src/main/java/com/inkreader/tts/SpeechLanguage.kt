package com.inkreader.tts

/** Chapter context keeps short Kanji-only Japanese sentences from reverting to Chinese. */
object SpeechLanguage {
    private fun isKana(c: Char) = c in '\u3041'..'\u3096' || c in "ゝゞヽヾ" ||
        c in '\u30a1'..'\u30fa' || c in '\uff66'..'\uff9d'

    fun chapter(text: String): String {
        val sample = text.take(20000)
        val kana = sample.count(::isKana)
        val han = sample.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        return if (kana >= 3 && kana * 10 >= kana + han) "ja-JP" else "zh-CN"
    }

    fun sentence(text: String, chapter: String = "zh-CN"): String = when {
        text.any(::isKana) -> "ja-JP"
        chapter == "ja-JP" && text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } -> "ja-JP"
        else -> "zh-CN" // Xiaoxiao handles both Chinese and English as before.
    }
}
