package com.inkreader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.inkreader.tts.TtsBus
import com.inkreader.ui.bookdetail.BookDetailScreen
import com.inkreader.ui.component.MiniTtsBar
import com.inkreader.ui.editor.EditorScreen
import com.inkreader.ui.help.HelpScreen
import com.inkreader.ui.marks.MarksScreen
import java.io.File
import com.inkreader.ui.reader.ReaderScreen
import com.inkreader.ui.shelf.GroupScreen
import com.inkreader.ui.shelf.SearchScreen
import com.inkreader.ui.settings.SettingsScreen
import com.inkreader.ui.shelf.ShelfScreen
import com.inkreader.ui.theme.InkTheme
import android.graphics.Color as AndroidColor

/** 界面栈目标 */
sealed class Dest {
    data object Shelf : Dest()
    data class Group(val groupId: Long) : Dest()
    data object Search : Dest()
    data class Detail(val bookId: Long) : Dest()
    data class Reader(val bookId: Long, val chapterIndex: Int, val offset: Int) : Dest()
    data class Editor(val bookId: Long, val chapterId: Long, val title: String) : Dest()
    data class Marks(val bookId: Long, val bookTitle: String) : Dest()
    data object Settings : Dest()
    data object Help : Dest()
}

class MainActivity : ComponentActivity() {

    companion object {
        const val REQ_PICK_SHELF = 1001
        const val REQ_PICK_APPEND = 1002
        const val REQ_PICK_COVER = 1003

        /** 发起选择器后，超过这个时间才重进 App 就不算「被打断」了 */
        const val PICKER_RETRY_WINDOW_MS = 5 * 60 * 1000L

        /** 拿不到（或保不住）Uri 授权时，把待导入文件抄进私有缓存的这个子目录 */
        private const val CACHE_DIR = "pending_import"

        /** 超过这个大小的文件不抄缓存，避免把 cacheDir 撑爆 */
        private const val CACHE_LIMIT_BYTES = 80L * 1024 * 1024

        private const val TAG = "InkImport"
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        val container = (application as InkApp).container
        // 冷启动时把「上次发起选择器」的标记**读出来再清掉**：
        //  · 留着不清，之后任何一次从其它通道回来的结果都可能被误判成书架导入（劫持别人的 result）；
        //  · 直接清掉又会让「开选择器时进程被杀」的现场恢复失去依据，
        //    所以把读出来的结论交给 container 带给界面（见 AppContainer.docPickerInterrupted）。
        val launchedAt = container.prefs.pickerLaunchedAt
        val retriedAt = container.prefs.pickerRetriedAt
        container.prefs.pickerLaunchedAt = 0L
        container.prefs.pickerRetriedAt = 0L
        container.docPickerInterrupted = launchedAt > 0L &&
            retriedAt < launchedAt &&
            System.currentTimeMillis() - launchedAt in 1..PICKER_RETRY_WINDOW_MS
        container.docPickerLauncher = { target, mimeType, multiple, mimeTypes ->
            com.inkreader.ui.component.PickerTrace.log(
                this, "launch req=$target mime=$mimeType multiple=$multiple mimeTypes=${mimeTypes?.joinToString()}"
            )
            // ⚠ 默认走 AOSP 文档选择器，**不走** vivo 自家文件管理器：
            //   实测留痕证明，vivo 文件管理器带「隐私密码 / 指纹」验证页时，那一步属于另一个任务栈，
            //   平台会直接把这个 startActivityForResult 的结果判成 CANCELED
            //   （19:16:49 第一次：指纹 → 选择器 → 4.9 秒后 res=0 uris=0 data=false；
            //     19:17:22 第二次：无密码页 → 2.4 秒后 res=-1 uris=1 data=true）。
            //   指纹记一次之后就不再弹，所以表现为「第一次必失败、第二次才成功」。
            //   AOSP 的 documentsui 没有这个验证环节，一次就能拿到结果。
            //   vivo 那个仅作为兜底（AOSP 组件缺失/被禁用时）。
            val primary = com.inkreader.ui.component.buildPickerIntent(
                this, mimeType, multiple, mimeTypes, preferAosp = true
            )
            val ok = runCatching {
                startActivityForResult(primary, target)
                true
            }.getOrDefault(false)
            com.inkreader.ui.component.PickerTrace.log(this, "launch result ok=$ok")
            if (ok) {
                Log.i(TAG, "picker launched: req=$target mime=$mimeType multiple=$multiple aosp=${primary.component != null}")
                true
            } else {
                runCatching {
                    val fallback = com.inkreader.ui.component.buildPickerIntent(
                        this, mimeType, multiple, mimeTypes, preferAosp = false
                    )
                    startActivityForResult(fallback, target)
                    true
                }.onFailure { e ->
                    Toast.makeText(
                        this,
                        getString(
                            R.string.shelf_picker_open_failed,
                            e.message ?: getString(R.string.action_unknown_error)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }.getOrDefault(false)
            }
        }
        collectIncoming(intent)
        // 顺手清掉过期的待导入缓存（抄进私有存储的那批文件，超过一天就没用了）
        runCatching {
            val dir = File(cacheDir, CACHE_DIR)
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        }
        setContent { InkRoot() }
    }

    override fun onDestroy() {
        // 无条件清掉：这个闭包持有 Activity 与 contentResolver，而 container 是应用级单例。
        // 只在 isFinishing 时清，非 finishing 的销毁（系统回收重建等）会把已销毁的 Activity 留在单例里。
        (application as? InkApp)?.container?.docPickerLauncher = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        collectIncoming(intent)
    }

    /**
     * Activity 级确定性结果接收：
     *
     * 为什么不用 Compose 内部的 `rememberLauncherForActivityResult`：
     * 1) `rememberLauncherForActivityResult` 依赖 `rememberSaveable { UUID.randomUUID() }` 和
     *    `DisposableEffect` 在首帧布局挂载后才向 `ActivityResultRegistry` 注册回调；
     * 2) 首次打开系统文件选择器时，系统冷启动 DocumentsUI 极易触发国产 ROM 的后台冻结（fast_freezer）
     *    或 Activity 重建；而 Android 的 `deliverResults`（即 `onActivityResult`）发生在 `onStart` 与
     *    `onResume` 之间、早于 `ComposeView` 挂载窗口或与 `LaunchedEffect(Unit)` 产生时序竞态，
     *    导致第一次选好文件点击「完成」后结果被丢弃，第二次由于选择器已在内存热启动才成功。
     * 3) 在 `MainActivity.onActivityResult` 中用固定请求码 `REQ_PICK_SHELF = 1001` 直接拦截并写入
     *    全局单例 `container.pendingUris`，即使进程被后台回收重启、无需等待 Compose 树挂载，也能
     *    100% 接住系统返回的 URI。
     */
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val container = (application as InkApp).container
        val uris = extractUrisFromIntent(data)
        com.inkreader.ui.component.PickerTrace.log(
            this, "onActivityResult req=$requestCode res=$resultCode uris=${uris.size} data=${data != null} clip=${data?.clipData?.itemCount ?: 0}"
        )
        Log.i(TAG, "MainActivity.onActivityResult: req=$requestCode, res=$resultCode, uris=${uris.size}")

        // 只认自己发起的三个请求码，不再用「pickerLaunchedAt > 0」这种启发式兜底 ——
        // 那会把其它通道（Compose 兜底启动器 / 追加章节 / 换封面）的结果劫持成书架导入，
        // 且因为标记可能陈留，命中时机不可预测。
        if (requestCode == REQ_PICK_SHELF) {
            container.prefs.pickerLaunchedAt = 0L
            container.prefs.pickerRetriedAt = 0L
            if (uris.isNotEmpty()) {
                // 先持久化授权，再把「保证读得到」这件事落实（必要时抄进私有缓存）
                val safe = uris.map { ensureReadableUri(it) }
                container.prefs.pendingImportUris = safe.map { it.toString() }
                container.pendingUris = safe
            } else {
                container.prefs.pendingImportUris = emptyList()
                val canceled = resultCode == Activity.RESULT_CANCELED && data == null
                if (!canceled) {
                    Toast.makeText(this, getString(R.string.shelf_picker_no_files), Toast.LENGTH_LONG).show()
                }
            }
            return
        }

        if (requestCode == REQ_PICK_APPEND) {
            if (uris.isNotEmpty()) {
                container.pendingAppendUris = uris.map { ensureReadableUri(it) }
            }
            return
        }

        if (requestCode == REQ_PICK_COVER) {
            val first = uris.firstOrNull()
            if (first != null) {
                container.pendingCoverUri = ensureReadableUri(first)
            }
            return
        }

        super.onActivityResult(requestCode, resultCode, data)
    }

    /**
     * 持久化 URI 授权。
     *
     * 刻意**同步**执行、不走后台线程：这条 binder 调用的意义就是「结果一到手就落盘」，
     * 进程随时可能在选择器返回后被杀（这正是「选完文件没反应」这一类问题的根），
     * 异步化会多出一个「授权还没落盘、进程就没了」的窗口。单次开销是毫秒级，可接受。
     */
    private fun persistUriGrants(uris: List<Uri>) {
        uris.forEach { u ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    u, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    /**
     * 保证这个 Uri「以后一定还读得到」，读不到就抄一份到私有缓存。
     *
     * 为什么需要：选择器返回的那一刻系统给的是**临时授权**；国产 ROM 上首次拉起选择器
     * 极易把本应用冻结/杀掉，进程重建后临时授权就没了 —— 于是「选好了文件、点开始导入却报
     * 读取失败」，再试一次（选择器已热、进程没被杀）就好了。这是「第一次导入失败、第二次成功」
     * 的另一半原因（第一半是结果被时序竞态冲掉，已在 ShelfScreen 的宽限期里处理后）。
     *
     * 做法：先试持久化授权 + 真读一次；两者任一失败就把内容抄进 cacheDir 换成 `file://`
     * 交回去（导入侧本来就同时支持 content:// 与 file://）。单文件超过 [CACHE_LIMIT_BYTES]
     * 不抄（避免把缓存撑爆），仍用原 Uri 试。
     */
    private fun ensureReadableUri(uri: Uri): Uri {
        val persisted = runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        }.getOrDefault(false)

        val readable = runCatching {
            contentResolver.openInputStream(uri)?.use { true } ?: false
        }.getOrDefault(false)
        if (readable && persisted) return uri

        return runCatching {
            if (uri.scheme == "file") return uri
            var name: String? = null
            var size = -1L
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
            if (size > CACHE_LIMIT_BYTES) {
                com.inkreader.ui.component.PickerTrace.log(this, "ensure too large ($size), keep uri")
                Log.i(TAG, "file too large to cache ($size bytes), keep original uri")
                return uri
            }
            val dir = File(cacheDir, CACHE_DIR).apply { mkdirs() }
            // 保留原文件名：合并导入的章节标题与「按文件名章号排序」都依赖它
            val safeName = (name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "import.bin")
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val target = File(dir, "${System.currentTimeMillis()}_$safeName")
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            } ?: return uri
            com.inkreader.ui.component.PickerTrace.log(
                this, "ensure cached ${target.name} (${target.length()}B, persisted=$persisted, readable=$readable)"
            )
            Log.i(TAG, "cached picked uri -> ${target.name} (${target.length()} bytes, persisted=$persisted)")
            Uri.fromFile(target)
        }.getOrDefault(uri)
    }

    @Suppress("DEPRECATION")
    private fun extractUrisFromIntent(data: Intent?): List<Uri> {
        if (data == null) return emptyList()
        val out = LinkedHashSet<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let(out::add)
        }
        data.data?.let(out::add)
        data.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { if (it != null) out.add(it) }
        data.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(out::add)
        return out.filter { it.scheme == "content" || it.scheme == "file" }
    }

    /** 上一颗被阅读器消费掉的音量键，用来把对应的抬手事件也一起吃掉 */
    private var consumedVolumeKey = -1

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handler = (application as? InkApp)?.container?.readerKeyHandler
        val code = event.keyCode
        val isVolume = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
        if (handler == null || !isVolume) return super.dispatchKeyEvent(event)

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    consumedVolumeKey = if (handler(code)) code else -1
                }
                if (consumedVolumeKey == code) return true
            }

            KeyEvent.ACTION_UP -> {
                val mine = consumedVolumeKey == code
                consumedVolumeKey = -1
                if (mine) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** 处理「用其他应用打开 / 分享到墨阅」的入口 */
    @Suppress("DEPRECATION")
    private fun collectIncoming(intent: Intent?) {
        if (intent == null) return
        val container = (application as InkApp).container
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri: Uri? = intent.data
                if (uri != null) {
                    persistUriGrants(listOf(uri))
                    container.pendingUris = listOf(uri)
                }
            }

            Intent.ACTION_SEND -> {
                val uri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                if (uri != null) {
                    persistUriGrants(listOf(uri))
                    container.pendingUris = listOf(uri)
                }
            }
        }
    }
}

@Composable
fun InkRoot() {
    val container = InkApp.instance.container
    val backStack = remember { mutableStateListOf<Dest>(Dest.Shelf) }
    val ttsState by TtsBus.state.collectAsState()

    fun push(d: Dest) = backStack.add(d)
    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    fun popToShelf() {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    BackHandler(enabled = backStack.size > 1) { pop() }

    val current = backStack.last()
    val depth = backStack.size

    InkTheme(readerTheme = container.library.uiTheme, barRevision = depth) {
        androidx.compose.foundation.layout.Box {
            AnimatedContent(
                targetState = current to depth,
                transitionSpec = {
                    val oldDepth = initialState.second
                    val newDepth = targetState.second
                    if (newDepth > oldDepth) {
                        (slideInHorizontally(tween(300)) { it / 3 } + fadeIn(tween(240))) togetherWith
                            (slideOutHorizontally(tween(300)) { -it / 4 } + fadeOut(tween(180)))
                    } else {
                        (slideInHorizontally(tween(300)) { -it / 4 } + fadeIn(tween(240))) togetherWith
                            (slideOutHorizontally(tween(300)) { it / 3 } + fadeOut(tween(180)))
                    }
                },
                label = "nav"
            ) { (dest, _) ->
                when (dest) {
                    is Dest.Shelf -> ShelfScreen(
                        container = container,
                        onOpenBook = { bookId, chapter, offset -> push(Dest.Reader(bookId, chapter, offset)) },
                        onOpenDetail = { push(Dest.Detail(it)) },
                        onOpenMarks = { id, title -> push(Dest.Marks(id, title)) },
                        onOpenGroup = { push(Dest.Group(it)) },
                        onOpenSearch = { push(Dest.Search) },
                        onOpenSettings = { push(Dest.Settings) }
                    )

                    is Dest.Settings -> SettingsScreen(
                        container = container,
                        onBack = { pop() },
                        onOpenHelp = { push(Dest.Help) }
                    )

                    is Dest.Help -> HelpScreen(onBack = { pop() })

                    is Dest.Search -> SearchScreen(
                        container = container,
                        onBack = { pop() },
                        onOpenBook = { bookId, chapter, offset -> push(Dest.Reader(bookId, chapter, offset)) },
                        onOpenDetail = { push(Dest.Detail(it)) }
                    )

                    is Dest.Group -> GroupScreen(
                        container = container,
                        groupId = dest.groupId,
                        onBack = { pop() },
                        onOpenBook = { bookId, chapter, offset -> push(Dest.Reader(bookId, chapter, offset)) },
                        onOpenDetail = { push(Dest.Detail(it)) }
                    )

                    is Dest.Detail -> BookDetailScreen(
                        container = container,
                        bookId = dest.bookId,
                        onBack = { pop() },
                        onRead = { chapter, offset -> push(Dest.Reader(dest.bookId, chapter, offset)) },
                        onEdit = { chapterId, title -> push(Dest.Editor(dest.bookId, chapterId, title)) },
                        onGoShelf = { popToShelf() }
                    )

                    is Dest.Reader -> ReaderScreen(
                        container = container,
                        bookId = dest.bookId,
                        initialChapter = dest.chapterIndex,
                        initialOffset = dest.offset,
                        onBack = { pop() },
                        onOpenMarks = { id, title -> push(Dest.Marks(id, title)) },
                        onOpenEditor = { chapterId, title -> push(Dest.Editor(dest.bookId, chapterId, title)) },
                        onOpenDetail = { push(Dest.Detail(it)) },
                        onGoShelf = { popToShelf() }
                    )

                    is Dest.Editor -> EditorScreen(
                        container = container,
                        bookId = dest.bookId,
                        chapterId = dest.chapterId,
                        initialTitle = dest.title,
                        onBack = { pop() }
                    )

                    is Dest.Marks -> MarksScreen(
                        container = container,
                        bookId = dest.bookId,
                        bookTitle = dest.bookTitle,
                        onBack = { pop() },
                        onJump = { chapter, offset -> push(Dest.Reader(dest.bookId, chapter, offset)) }
                    )
                }
            }

            // 朗读悬浮条：任何界面之上都可见，方便随时暂停
            if (ttsState.active) {
                MiniTtsBar(ttsState, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}
