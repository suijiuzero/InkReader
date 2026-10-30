package com.inkreader.data.file

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID

/**
 * 正文文件仓库：章节正文以 UTF-8 纯文本存放在应用私有目录，
 * 数据库只保存文件名与索引，避免大字段拖慢查询。
 */
class FileStore(context: Context) {
    companion object { private val mediaGate = Any() }
    private var mediaDirty = false
    fun <T> withMediaWrite(block: () -> T): T = synchronized(mediaGate) { block() }

    private val root: File = File(context.filesDir, "chapters").apply { mkdirs() }

    /** 自定义封面存放目录 */
    private val coverRoot: File = File(context.filesDir, "covers").apply { mkdirs() }

    private val imageRoot = File(context.filesDir, "images").apply { mkdirs() }
    fun imageFile(key: String): File {
        require(com.inkreader.parse.DocumentImages.validKey(key))
        return File(imageRoot, "$key.img")
    }
    fun writeImage(image: com.inkreader.parse.DocumentImage) = withMediaWrite {
        require(com.inkreader.parse.DocumentImages.hash(image.bytes) == image.key)
        val target = imageFile(image.key)
        if (!target.isFile || target.length() != image.bytes.size.toLong()) atomicWrite(target, image.bytes)
        mediaDirty = true
    }
    /** Called after a completed/rolled-back media operation; staging and reference writes share this lock. */
    fun pruneImagesIfDirty() = withMediaWrite { if (mediaDirty) pruneImages() }
    fun pruneImages() = withMediaWrite {
        if (imageRoot.listFiles()?.isEmpty() != false) { mediaDirty = false; return@withMediaWrite }
        val live = HashSet<String>()
        var budget = 0L
        val metadata = root.listFiles { file -> file.name.endsWith(".rich") || file.name.endsWith(".rich.bak") }
            .orEmpty().map { File(it.path.removeSuffix(".bak")) }.distinctBy { it.path }
        for (file in metadata) {
            val bytes = maxOf(file.length(), File(file.path + ".bak").length())
            budget += bytes
            if (bytes > 16L * 1024 * 1024 || budget > 512L * 1024 * 1024) return@withMediaWrite
            val raw = runCatching { AtomicFile(file).openRead().bufferedReader().use { it.readText() } }.getOrNull() ?: return@withMediaWrite
            val rich = com.inkreader.parse.RichChapter.decode(raw)
            if (raw.isNotBlank() && rich == null) return@withMediaWrite
            live += com.inkreader.parse.DocumentImages.references(rich)
        }
        imageRoot.listFiles()?.forEach { file ->
            val key = file.name.removeSuffix(".img").removeSuffix(".img.bak")
            if (com.inkreader.parse.DocumentImages.validKey(key) && key !in live) AtomicFile(imageFile(key)).delete()
        }
        mediaDirty = false
    }

    fun newFileName(): String = "c_${UUID.randomUUID().toString().replace("-", "")}.txt"

    fun file(name: String): File = File(root, name)

    // ---- 自定义封面 ----

    fun coverFile(name: String): File = File(coverRoot, name)

    /** 一个书一本封面文件，重复设置直接覆盖 */
    fun coverNameFor(bookId: Long): String = "cover_$bookId.jpg"

    fun writeCover(name: String, bytes: ByteArray) {
        atomicWrite(coverFile(name), bytes)
    }

    fun deleteCover(name: String) {
        if (name.isBlank()) return
        runCatching { coverFile(name).delete() }
    }

    fun write(name: String, text: String) {
        atomicWrite(file(name), text.toByteArray(Charsets.UTF_8))
    }

    fun write(name: String, reader: java.io.Reader): Int {
        val atomic = AtomicFile(file(name))
        val output = atomic.startWrite()
        try {
            val writer = output.writer(Charsets.UTF_8)
            val buffer = CharArray(16384)
            var total = 0
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                val count = reader.read(buffer); if (count < 0) break
                writer.write(buffer, 0, count); total += count
            }
            writer.flush(); atomic.finishWrite(output)
            return total
        } catch (t: Throwable) { atomic.failWrite(output); throw t }
    }

    fun read(name: String): String =
        AtomicFile(file(name)).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (t: Throwable) {
            atomic.failWrite(output)
            throw t
        }
    }

    fun delete(name: String) {
        runCatching { AtomicFile(file(name)).delete() }
    }

    // ---- 样式覆盖（Markdown 的格式化正文用） ----

    /**
     * 样式覆盖文件：和正文同名的 `.rich` 边车文件。
     * 正文照旧是纯文本，格式化信息全在这份旁挂数据里，两者偏移一一对应。
     */
    fun richNameOf(txtName: String): String =
        if (txtName.endsWith(".txt")) txtName.dropLast(4) + ".rich" else "$txtName.rich"

    fun writeRich(txtName: String, data: String) = withMediaWrite {
        val decoded = com.inkreader.parse.RichChapter.decode(data)
        val safe = if (decoded?.blocks?.any { it.kind == com.inkreader.parse.RichKind.IMAGE } == true)
            com.inkreader.parse.RichTransforms.reconcile(read(txtName), decoded)?.encode().orEmpty() else data
        atomicWrite(file(richNameOf(txtName)), safe.toByteArray(Charsets.UTF_8))
    }

    fun readRich(txtName: String): String =
        runCatching { AtomicFile(file(richNameOf(txtName))).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrDefault("")

    fun deleteRich(txtName: String) {
        runCatching { AtomicFile(file(richNameOf(txtName))).delete() }
    }

    /** 正文连同它的样式覆盖一起删掉，别留孤儿文件 */
    fun deleteChapter(name: String) = withMediaWrite {
        val rich = file(richNameOf(name))
        if (rich.isFile || File(rich.path + ".bak").isFile) mediaDirty = true
        delete(name)
        deleteRich(name)
    }

    fun totalSize(): Long =
        root.listFiles()?.sumOf { it.length() } ?: 0L
}
