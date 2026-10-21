package com.inkreader.ui.component

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import java.io.IOException

/**
 * 选文件用的 `ACTION_OPEN_DOCUMENT` 意图。
 *
 * **刻意与 `ActivityResultContracts.OpenMultipleDocuments` 完全一致** —— 因为同机上的 SJopi / sjcaht
 * 用 vivo 自家文件管理器上传文件一直是正常的，它们用的就是这个契约，构造出的意图等价于：
 *
 * ```
 * Intent(ACTION_OPEN_DOCUMENT)
 *     .putExtra(EXTRA_ALLOW_MULTIPLE, true)
 *     .setType(通配 MIME)
 *     .putExtra(EXTRA_MIME_TYPES, arrayOf(通配 MIME))
 * ```
 *
 * 差别就在这个 `EXTRA_MIME_TYPES`：墨阅以前传的是**二十多个具体类型**（text/plain、application/pdf…），
 * 实测这台 vivo 的选择器面对这个长过滤列表会直接返回 `RESULT_CANCELED`、甚至什么都不返回
 * （日志：`res=0, uris=0` / 无回调），用户点了「完成」却毫无反应。改成只传通配就与能用的写法一致了。
 *
 * 另外：不再加 `CATEGORY_OPENABLE` 与任何 `FLAG_GRANT_*`（与契约一致）。代价是拿不到"可持久化授权"，
 * 所以结果回来时会由 `MainActivity.ensureReadableUri` 把文件抄进私有缓存（保留原文件名）——
 * 与 SJopi 的做法一致，也顺带解决了"进程被杀后临时授权失效导致读取失败"。格式校验放在导入阶段做。
 *
 * @param preferAosp true = 显式指定 AOSP 文档选择器（仅在系统默认那步 launch 失败时兜底用）
 */
fun buildPickerIntent(
    ctx: Context,
    mimeType: String,
    multiple: Boolean,
    mimeTypes: Array<String>?,
    preferAosp: Boolean = false
): Intent {
    val base = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        type = mimeType
        putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes ?: arrayOf(mimeType))
    }
    if (!preferAosp) return base
    val cn = ComponentName(AOSP_DOCUMENTS_PKG, AOSP_DOCUMENTS_PICKER)
    val available = runCatching { ctx.packageManager.getActivityInfo(cn, 0) }.isSuccess
    return if (available) Intent(base).setComponent(cn) else base
}

/** AOSP 文档选择器（DocumentsUI）的应用包名 */
const val AOSP_DOCUMENTS_PKG = "com.android.documentsui"
/** AOSP 文档选择器的「选择文件」Activity（OPEN_DOCUMENT / GET_CONTENT 都由它处理） */
const val AOSP_DOCUMENTS_PICKER = "com.android.documentsui.picker.PickActivity"

/**
 * 让选好的文件「以后一定还读得到」：拿不到持久授权 / 读不到，就抄一份进私有缓存换成 `file://`。
 *
 * 与 SJopi 的做法一致（它拿到 Uri 立即把内容落到自己的存储里）。**必须保留原文件名** ——
 * 合并导入时的章节标题、以及「按文件名章号排序」都依赖它。超过 [limitBytes] 的文件不抄（避免撑爆缓存）。
 */
fun ensureReadableUri(ctx: Context, uri: Uri, limitBytes: Long = 80L * 1024 * 1024): Uri {
    val cr = ctx.contentResolver
    val persisted = runCatching {
        cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    }.getOrDefault(false)
    val readable = runCatching { cr.openInputStream(uri)?.use { true } ?: false }.getOrDefault(false)
    if (readable && persisted) return uri

    return runCatching {
        if (uri.scheme == "file") return uri
        var name: String? = null
        var size = -1L
        cr.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
            }
        }
        if (size > limitBytes) return uri
        val dir = File(ctx.cacheDir, "pending_import").apply { mkdirs() }
        val safeName = (name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "import.bin")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_").trim('.').ifBlank { "import.bin" }
        val entryDir = File(dir, UUID.randomUUID().toString()).apply { check(mkdirs()) }
        val target = File(entryDir, safeName)
        try {
            val input = cr.openInputStream(uri) ?: throw IOException("无法读取所选文件")
            input.use {
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val n = it.read(buffer)
                        if (n < 0) break
                        copied += n
                        if (copied > limitBytes) throw IOException("文件超过缓存大小上限")
                        out.write(buffer, 0, n)
                    }
                }
            }
        } catch (t: Throwable) {
            target.delete()
            entryDir.delete()
            throw t
        }
        PickerTrace.log(ctx, "ensure cached ${target.name} (${target.length()}B, persisted=$persisted, readable=$readable)")
        Uri.fromFile(target)
    }.getOrDefault(uri)
}

