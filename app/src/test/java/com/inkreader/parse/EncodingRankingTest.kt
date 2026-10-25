package com.inkreader.parse

import com.inkreader.data.model.SplitOptions
import java.nio.charset.Charset
import org.junit.Assert.*
import org.junit.Test

class EncodingRankingTest {
    @Test fun traditionalBig5KeepsActualText() {
        val text = "第一章 歸來\n\n這是一個關於閱讀與回憶的故事。他們說，風起的時候，總會想起家鄉。".repeat(8)
        assertEquals(text, CharsetDetector.decode(text.toByteArray(Charset.forName("Big5"))))
    }

    @Test fun japaneseLegacyEncodingsKeepKanaAndKanji() {
        val text = "第一章 帰り道\n\nこんにちは。これは読書の物語です。彼女は静かな部屋で本を開きました。次の日、またここで会いましょう。".repeat(8)
        for (encoding in listOf("windows-31j", "EUC-JP")) {
            val bytes = text.toByteArray(Charset.forName(encoding))
            assertEquals(encoding + ": " + CharsetDetector.inspect(bytes).candidates, text, CharsetDetector.decode(bytes))
        }
    }

    @Test fun simplifiedChineseDoesNotTurnIntoJapanese() {
        val text = "第一章 风起\n\n这是一本关于生活和阅读的书。我们从这里开始，走过山川，寻找回家的道路。".repeat(10)
        for (encoding in listOf("GBK", "GB18030"))
            assertEquals(text, CharsetDetector.decode(text.toByteArray(Charset.forName(encoding))))
    }

    @Test fun explicitEncodingIsAppliedToTheWholeTxtPipeline() {
        val text = "第一章\n\nこんにちは。物語の始まりです。\n\n第二章\n\nまた会いましょう。"
        val parsed = TxtParser.parse(text.toByteArray(Charset.forName("windows-31j")), "test.txt",
            SplitOptions(cleanWatermark = false, textEncoding = "windows-31j"), diagnostics = true)
        assertEquals(2, parsed.parts.size)
        assertTrue(parsed.parts.first().content.contains("こんにちは"))
        assertEquals("windows-31j", parsed.encoding!!.charset.name())
    }

    @Test fun asciiAndAllUtf8LanguagesRemainUnambiguous() {
        for (text in listOf("plain ASCII", "مرحبا بالعالم", "नमस्ते दुनिया", "안녕하세요", "ภาษาไทย", "こんにちは 🐉 中文")) {
            val result = CharsetDetector.inspect(text.toByteArray())
            assertEquals(Charsets.UTF_8, result.charset)
            assertFalse(result.uncertain)
            assertEquals(text, CharsetDetector.decode(text.toByteArray(), result))
        }
    }

    @Test fun truncatedLegacySampleDoesNotDamageFullDecode() {
        val text = "こんにちは。これは日本語です。".repeat(6000)
        for (encoding in listOf("windows-31j", "EUC-JP"))
            assertEquals(text, CharsetDetector.decode(text.toByteArray(Charset.forName(encoding))))
    }

    @Test fun badManualEncodingCannotSilentlyFallback() {
        assertTrue(runCatching { CharsetDetector.decode(byteArrayOf(1), "not-an-encoding") }.isFailure)
    }
}
