package com.inkreader.parse

import java.nio.charset.Charset

/**
 * 编码嗅探。TXT 书籍的编码非常杂，UTF-8 / GBK / GB18030 / UTF-16 都会遇到。
 * 策略：先看 BOM，再做 UTF-8 严格校验，不通过则退回 GB18030（GBK 的超集，兼容更好）。
 */
object CharsetDetector {

    private val UTF8: Charset = Charsets.UTF_8
    private val GB18030: Charset by lazy {
        runCatching { Charset.forName("GB18030") }.getOrDefault(Charsets.UTF_8)
    }

    data class Candidate(val name: String, val score: Double, val preview: String)
    data class Result(
        val charset: Charset,
        val bomLength: Int,
        val uncertain: Boolean = false,
        val candidates: List<Candidate> = emptyList()
    )

    val selectableNames = listOf("UTF-8", "GB18030", "Big5", "windows-31j", "EUC-JP", "UTF-16LE", "UTF-16BE")
    private val legacyNames = listOf("GB18030", "windows-31j", "EUC-JP", "Big5")
    private val frequentHan = ("的一是不了在人有我他這这個个們们中來来上大為为和國国地到以說说時时要就出會会可也你對对生能而子那得於于著着下自之年過过發发後后作裡里用道行所然家種种事成方多經经麼么去法學学如都同現现當当沒没動动面起看定天分還还進进好小部其些主樣样理心她本前開开但因只從从想實实日軍军者意無无力它與与長长把機机十民第公此已工使情明性知全三又關关點点正業业外將将兩两高間间由問问很最重並并物手應应戰战向頭头文體体政美相見见被利什二等產产或新己制身果加西斯月話话合回特代內内信表化老給给世位次度門门任常先海通教兒儿原東东聲声提立及比員员解水名真論论處处走義义各入幾几口認认條条平系氣气題题活爾尔更別别打女變变四神總总何電电數数安少報报才結结反受目太量再感建務务做接必場场件計计管期市直德資资命山金指克許许統统區区保至隊队形社便空決决治展馬马科司五基眼書书非則则聽听白卻却界達达光放強强即像難难且權权思王象完設设式色路記记南品住告類类求據据程北邊边死張张該该交規规萬万取拉格望覺觉術术領领共確确傳传師师觀观清今切院讓让識识候帶带導导爭争運运笑飛飞風风步改收根干造言聯联持組组每濟济車车親亲極极林服快辦办議议往元英士證证近失轉转夫令準准布始怎呢存未遠远叫台單单影具羅罗字愛爱讀读章節节卷夢梦歸归曉晓龍龙語语終终緒绪緣缘歡欢寫写數数陸陆壹貳叁參肆伍柒捌玖拾佰仟零〇").toSet()

    fun detect(bytes: ByteArray): Result = inspect(bytes)

    /** UTF-8 is checked over the whole input. Legacy ranking is bounded to 64 KiB. */
    fun inspect(bytes: ByteArray, overrideName: String = "", utf8Valid: Boolean? = null): Result {
        ParseCancellation.checkpoint()
        if (overrideName.isNotBlank()) {
            require(overrideName in selectableNames) { "Unsupported text encoding" }
            val charset = Charset.forName(overrideName)
            val bom = bom(bytes)
            return Result(charset, if (bom?.charset == charset) bom.bomLength else 0)
        }
        bom(bytes)?.let { return it }
        if (bytes.size >= 64) {
            var even = 0
            var odd = 0
            val n = minOf(bytes.size, 512)
            for (i in 0 until n) if (bytes[i] == 0.toByte()) {
                if (i % 2 == 0) even++ else odd++
            }
            val half = n / 2
            if (even > half * 0.7 && odd < half * 0.2) return Result(Charsets.UTF_16BE, 0)
            if (odd > half * 0.7 && even < half * 0.2) return Result(Charsets.UTF_16LE, 0)
        }
        if (utf8Valid ?: isValidUtf8(bytes)) return Result(UTF8, 0)
        val size = minOf(bytes.size, 65536)
        val ranked = legacyNames.mapNotNull { name ->
            runCatching {
                val charset = Charset.forName(name)
                // Decode with endOfInput=false: a sample ending inside a character is not corruption.
                val decoder = charset.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
                val output = java.nio.CharBuffer.allocate(size * 2 + 2)
                decoder.decode(java.nio.ByteBuffer.wrap(bytes, 0, size), output, size == bytes.size)
                output.flip()
                val text = output.toString()
                Candidate(name, languageScore(text) + if (name == "GB18030") 0.02 else 0.0,
                    text.take(180))
            }.getOrNull()
        }.sortedByDescending { it.score }
        ParseCancellation.checkpoint()
        val best = ranked.firstOrNull() ?: return Result(GB18030, 0, true)
        val margin = best.score - (ranked.getOrNull(1)?.score ?: -100.0)
        return Result(Charset.forName(best.name), 0,
            uncertain = margin < 0.35 || size < 24 || best.score < 0.2,
            candidates = ranked)
    }

    private fun bom(bytes: ByteArray): Result? {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return Result(UTF8, 3)
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Result(Charsets.UTF_16LE, 2)
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Result(Charsets.UTF_16BE, 2)
        }
        return null
    }

    private fun languageScore(text: String): Double {
        var score = 0.0
        var count = 0
        for ((index, c) in text.withIndex()) {
            if (index and 4095 == 0) ParseCancellation.checkpoint()
            if (c.isWhitespace() || c in ' '..'~') continue
            count++
            score += when {
                c == '\uFFFD' -> -16.0
                c.isISOControl() || c in '\uE000'..'\uF8FF' -> -8.0
                c in '\u3041'..'\u3096' -> 4.0
                c in '\u30A1'..'\u30FA' -> 2.8
                c in '\uFF61'..'\uFF9F' -> -0.8
                c in frequentHan -> 2.6
                c in '\u3400'..'\u9FFF' -> -0.7
                c in "。、，：；！？「」『』（）《》【】“”‘’…—・" -> 0.8
                else -> -0.3
            }
        }
        return score / count.coerceAtLeast(1)
    }

    /** 严格校验是否为合法 UTF-8 字节序列 */
    private fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        var multiByteSequences = 0
        val n = bytes.size
        while (i < n) {
            if (i and 4095 == 0) ParseCancellation.checkpoint()
            val b = bytes[i].toInt() and 0xFF
            val extra: Int
            when {
                b <= 0x7F -> { i++; continue }
                b in 0xC2..0xDF -> extra = 1
                b in 0xE0..0xEF -> extra = 2
                b in 0xF0..0xF4 -> extra = 3
                else -> return false
            }
            if (i + extra >= n) return false
            for (k in 1..extra) {
                val c = bytes[i + k].toInt() and 0xFF
                if (c !in 0x80..0xBF) return false
            }
            // 排除过长编码
            if (extra == 2 && b == 0xE0 && (bytes[i + 1].toInt() and 0xFF) < 0xA0) return false
            if (extra == 3 && b == 0xF0 && (bytes[i + 1].toInt() and 0xFF) < 0x90) return false
            if (extra == 2 && b == 0xED && (bytes[i + 1].toInt() and 0xFF) >= 0xA0) return false
            if (extra == 3 && b == 0xF4 && (bytes[i + 1].toInt() and 0xFF) >= 0x90) return false
            i += extra + 1
            multiByteSequences++
        }
        // 纯 ASCII 也算合法 UTF-8
        return multiByteSequences >= 0
    }

    fun decode(bytes: ByteArray, overrideName: String = ""): String = decode(bytes, inspect(bytes, overrideName))

    fun decode(bytes: ByteArray, result: Result): String {
        ParseCancellation.checkpoint()
        if (bytes.isEmpty()) return ""
        return String(bytes, result.bomLength, bytes.size - result.bomLength, result.charset)
    }
}
