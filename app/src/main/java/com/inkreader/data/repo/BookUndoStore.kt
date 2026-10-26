package com.inkreader.data.repo

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.Closeable
import java.util.UUID
import java.util.zip.ZipFile

/** 每本书保留最近一次结构编辑前的完整逻辑快照，跨进程有效。 */
class BookUndoStore(context: Context, private val archive: LibraryArchive) {
    private val root = File(context.filesDir, "book-undo")
    private fun candidates(bookId: Long): List<File> = root.listFiles().orEmpty().filter {
        it.name == "book_$bookId.zip" || it.name.startsWith("book_${bookId}_pending_") && it.name.endsWith(".zip")
    }.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { "_pending_" in it.name }).filter { file ->
        runCatching { ZipFile(file).use { it.getEntry("library.json") != null } }.getOrDefault(false)
    }
    fun available(bookId: Long) = candidates(bookId).isNotEmpty()
    /** 先暂存；只有结构事务成功才替换旧撤销点。失败或取消保留上一次可用记录。 */
    fun prepare(bookId: Long): Pending {
        check(root.isDirectory || root.mkdirs())
        val pending = File(root, "book_${bookId}_pending_${UUID.randomUUID()}.zip")
        val atomic = AtomicFile(pending); val output = atomic.startWrite()
        try {
            // 关闭ZIP只释放包装层，AtomicFile统一完成或回滚。
            archive.write(object : java.io.FilterOutputStream(output) { override fun close() = flush() }, bookId)
            atomic.finishWrite(output)
        } catch (t: Throwable) { atomic.failWrite(output); throw t }
        return Pending(bookId, pending)
    }
    inner class Pending(private val bookId: Long, private val file: File) : Closeable {
        private var committed = false
        fun commit() {
            committed = true
            // 同目录原子替换；极少数文件系统失败时保留完整暂存，重启后仍能恢复。
            val target = File(root, "book_$bookId.zip")
            val promoted = runCatching { android.system.Os.rename(file.path, target.path) }.isSuccess
            if (promoted) candidates(bookId).filter { it != target }.forEach { AtomicFile(it).delete() }
        }
        override fun close() { if (!committed) AtomicFile(file).delete() }
    }
    fun restore(bookId: Long): Int {
        val atomic = AtomicFile(candidates(bookId).firstOrNull() ?: error("No undo snapshot"))
        val count = atomic.openRead().use { archive.restore(it, bookId) }
        clear(bookId); return count
    }
    fun clear(bookId: Long) {
        root.listFiles().orEmpty().filter {
            it.name == "book_$bookId.zip" || it.name == "book_$bookId.zip.bak" ||
                it.name.startsWith("book_${bookId}_pending_")
        }.forEach { AtomicFile(it).delete() }
    }
}
