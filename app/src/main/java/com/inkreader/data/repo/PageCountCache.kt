package com.inkreader.data.repo

import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/** 缓存可丢弃的页数，不保存页面正文；书籍文件或完整排版签名变化自动失效。 */
class PageCountCache(private val root: File) {
    private fun file(signature: String) = File(root, MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) } + ".pages")
    @Synchronized fun load(signature: String, chapters: Int): IntArray? = runCatching {
        val file = file(signature)
        require(chapters in 1..100000 && file.length() == 12L + chapters * 4L)
        val bytes = file.readBytes()
        val payload = bytes.copyOfRange(8, bytes.size)
        DataInputStream(bytes.inputStream()).use { input ->
            require(input.readLong() == CRC32().also { it.update(payload) }.value)
            require(input.readInt() == chapters)
            val acc = IntArray(chapters + 1)
            for (i in 0 until chapters) {
                val count = input.readInt(); require(count > 0 && count.toLong() + acc[i] <= Int.MAX_VALUE)
                acc[i + 1] = acc[i] + count
            }
            acc
        }
    }.getOrNull()
    @Synchronized fun save(signature: String, prefix: IntArray) {
        runCatching {
            require(prefix.size in 2..100001 && prefix.first() == 0 && (1 until prefix.size).all { prefix[it] > prefix[it - 1] })
            check(root.isDirectory || root.mkdirs())
            val bytes = java.io.ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { output ->
                output.writeInt(prefix.size - 1)
                for (i in 1 until prefix.size) output.writeInt(prefix[i] - prefix[i - 1])
            } }.toByteArray()
            val target = file(signature); val temp = File.createTempFile("pages-", ".tmp", root)
            try {
                DataOutputStream(temp.outputStream()).use { it.writeLong(CRC32().also { crc -> crc.update(bytes) }.value); it.write(bytes) }
                if (!temp.renameTo(target)) { target.delete(); check(temp.renameTo(target)) }
            } finally { temp.delete() }
            root.listFiles().orEmpty().sortedByDescending { it.lastModified() }.drop(32).forEach { it.delete() }
        }
    }
}
