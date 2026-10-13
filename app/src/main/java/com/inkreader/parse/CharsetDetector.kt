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

    data class Result(val charset: Charset, val bomLength: Int)

    fun detect(bytes: ByteArray): Result {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return Result(UTF8, 3)
        }
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return Result(Charsets.UTF_16LE, 2)
            if (b0 == 0xFE && b1 == 0xFF) return Result(Charsets.UTF_16BE, 2)
        }
        // UTF-16 无 BOM 的情况：奇偶位大量 0x00
        if (bytes.size >= 64) {
            var zerosEven = 0
            var zerosOdd = 0
            val n = minOf(bytes.size, 512)
            for (i in 0 until n) {
                if (bytes[i].toInt() == 0) {
                    if (i % 2 == 0) zerosEven++ else zerosOdd++
                }
            }
            val half = n / 2
            if (zerosEven > half * 0.7 && zerosOdd < half * 0.2) return Result(Charsets.UTF_16LE, 0)
            if (zerosOdd > half * 0.7 && zerosEven < half * 0.2) return Result(Charsets.UTF_16BE, 0)
        }
        return if (isValidUtf8(bytes)) Result(UTF8, 0) else Result(GB18030, 0)
    }

    /** 严格校验是否为合法 UTF-8 字节序列 */
    private fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        var multiByteSequences = 0
        val n = bytes.size
        while (i < n) {
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
            i += extra + 1
            multiByteSequences++
        }
        // 纯 ASCII 也算合法 UTF-8
        return multiByteSequences >= 0
    }

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val r = detect(bytes)
        val body = if (r.bomLength > 0 && bytes.size > r.bomLength)
            bytes.copyOfRange(r.bomLength, bytes.size) else bytes
        return runCatching { String(body, r.charset) }
            .getOrElse { String(bytes, Charsets.UTF_8) }
    }
}
