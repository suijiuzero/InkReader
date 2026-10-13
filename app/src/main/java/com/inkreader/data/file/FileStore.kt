package com.inkreader.data.file

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * 正文文件仓库：章节正文以 UTF-8 纯文本存放在应用私有目录，
 * 数据库只保存文件名与索引，避免大字段拖慢查询。
 */
class FileStore(context: Context) {

    private val root: File = File(context.filesDir, "chapters").apply { mkdirs() }

    /** 自定义封面存放目录 */
    private val coverRoot: File = File(context.filesDir, "covers").apply { mkdirs() }

    fun newFileName(): String = "c_${UUID.randomUUID().toString().replace("-", "")}.txt"

    fun file(name: String): File = File(root, name)

    // ---- 自定义封面 ----

    fun coverFile(name: String): File = File(coverRoot, name)

    /** 一个书一本封面文件，重复设置直接覆盖 */
    fun coverNameFor(bookId: Long): String = "cover_$bookId.jpg"

    fun writeCover(name: String, bytes: ByteArray) {
        runCatching { coverFile(name).writeBytes(bytes) }
    }

    fun deleteCover(name: String) {
        if (name.isBlank()) return
        runCatching { coverFile(name).delete() }
    }

    fun write(name: String, text: String) {
        file(name).writeText(text, Charsets.UTF_8)
    }

    fun read(name: String): String =
        runCatching { file(name).readText(Charsets.UTF_8) }.getOrDefault("")

    fun delete(name: String) {
        runCatching { file(name).delete() }
    }

    // ---- 样式覆盖（Markdown 的格式化正文用） ----

    /**
     * 样式覆盖文件：和正文同名的 `.rich` 边车文件。
     * 正文照旧是纯文本，格式化信息全在这份旁挂数据里，两者偏移一一对应。
     */
    fun richNameOf(txtName: String): String =
        if (txtName.endsWith(".txt")) txtName.dropLast(4) + ".rich" else "$txtName.rich"

    fun writeRich(txtName: String, data: String) {
        runCatching { file(richNameOf(txtName)).writeText(data, Charsets.UTF_8) }
    }

    fun readRich(txtName: String): String =
        runCatching { file(richNameOf(txtName)).readText(Charsets.UTF_8) }.getOrDefault("")

    fun deleteRich(txtName: String) {
        runCatching { file(richNameOf(txtName)).delete() }
    }

    /** 正文连同它的样式覆盖一起删掉，别留孤儿文件 */
    fun deleteChapter(name: String) {
        delete(name)
        deleteRich(name)
    }

    fun totalSize(): Long =
        root.listFiles()?.sumOf { it.length() } ?: 0L
}
