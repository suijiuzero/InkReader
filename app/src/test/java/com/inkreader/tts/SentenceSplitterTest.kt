package com.inkreader.tts

import org.junit.Assert.*
import org.junit.Test

class SentenceSplitterTest {
    @Test fun hardLimitCannotCutAnEmojiOrSupplementaryHanCharacter() {
        for (character in listOf("🐉", "𠀀")) {
            val text = "a".repeat(219) + character + "b".repeat(300)
            val ranges = SentenceSplitter.split(text)
            assertEquals(text, ranges.joinToString("") { text.substring(it.first, it.last + 1) })
            for (range in ranges) {
                assertFalse(Character.isHighSurrogate(text[range.last]))
                assertFalse(Character.isLowSurrogate(text[range.first]))
            }
        }
    }

    @Test fun sentenceOffsetsStayContiguousForMultilingualText() {
        val text = "中文。こんにちは！Hello?🐉继续。"
        val ranges = SentenceSplitter.split(text)
        assertEquals(text, ranges.joinToString("") { text.substring(it.first, it.last + 1) })
        assertTrue(ranges.zipWithNext().all { it.first.last + 1 == it.second.first })
    }
}
