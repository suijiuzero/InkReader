package com.inkreader

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inkreader.data.model.Book
import com.inkreader.data.model.BookGroup
import com.inkreader.data.model.ImportMode
import com.inkreader.data.model.ShelfSort
import com.inkreader.data.model.ShelfView
import com.inkreader.data.model.SplitMode
import com.inkreader.data.model.SplitOptions
import com.inkreader.data.prefs.PrefsStore
import com.inkreader.data.repo.BookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 全局依赖容器。
 * 这里刻意不引入 DI 框架与 ViewModel：应用只有 5 个界面，
 * 用「全局单例仓库 + 界面级状态持有者」更直观，也少一层构建期风险。
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext
    val repo = BookRepository(appContext)
    val prefs = PrefsStore(appContext)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val library = LibraryStore(repo, prefs, scope, appContext)

    init {
        // pdfbox 需要一次全局初始化（加载字型资源），之后才能解析 PDF
        com.inkreader.parse.DocumentParsers.init(appContext)
    }

    /**
     * 书架待导入的文档 URI 列表（可观察）。
     * 无论是系统文件选择器返回（由 MainActivity.onActivityResult 直接投递）、
     * 进程被回收后从 PrefsStore 恢复，还是外部「用其他应用打开 / 分享」进来，
     * 统一汇聚到这里，彻底消除 Compose 子树重组或 UUID 丢失导致的「首次点完成无反应」。
     */
    var pendingUris by mutableStateOf<List<Uri>>(emptyList())

    /** 详情页「追加文件为章节」选回来的文件 URI 列表 */
    var pendingAppendUris by mutableStateOf<List<Uri>>(emptyList())

    /** 详情页「导入封面」选回来的图片 URI */
    var pendingCoverUri by mutableStateOf<Uri?>(null)

    /** 由 MainActivity 注册的固定请求码文件选择器启动器 */
    var docPickerLauncher: ((target: Int, mimeType: String, multiple: Boolean, mimeTypes: Array<String>?) -> Boolean)? = null

    /**
     * 「上次发起过选择器、结果却没回来」（多半是开选择器期间进程被系统回收）。
     * MainActivity 在 onCreate 里把落盘的标记读出来、随即清掉，把结论放这里；
     * 界面据此替用户重开一次选择器，同时不会因为标记陈留而在每次启动时误判。
     */
    var docPickerInterrupted by mutableStateOf(false)

    /**
     * 阅读器在前台时注册的音量键处理器：keyCode -> 是否已被消费。
     * MainActivity 在 dispatchKeyEvent 里转发，返回 true 就不再把事件交给系统（音量不变）。
     */
    var readerKeyHandler: ((Int) -> Boolean)? = null
}

/** 书架状态 */
class LibraryStore(
    private val repo: BookRepository,
    private val prefs: PrefsStore,
    private val scope: CoroutineScope,
    private val appContext: Context
) {
    var books by mutableStateOf<List<Book>>(emptyList())
        private set
    /** 书架分组（按 sort_order / 创建时间排序） */
    var groups by mutableStateOf<List<BookGroup>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var sort by mutableStateOf(prefs.shelfSort)
        private set
    var view by mutableStateOf(prefs.shelfView)
        private set

    /**
     * 全站外观跟随阅读主题（出厂默认是月白浅色）。
     */
    var uiTheme by mutableStateOf(prefs.load().theme)
        private set

    // 导入进度
    var importing by mutableStateOf(false)
        private set
    var importStage by mutableStateOf("")
        private set
    var importFraction by mutableStateOf(0f)
        private set
    var importErrors by mutableStateOf<List<String>>(emptyList())
        private set

    /** 本次导入的逐文件结果（批量导入后弹明细用） */
    var importReport by mutableStateOf<List<BookRepository.ImportFileResult>>(emptyList())
        private set
    var toast by mutableStateOf<String?>(null)

    init {
        refresh()
    }

    fun refresh() {
        scope.launch {
            loading = true
            books = repo.books(sort)
            groups = repo.groups()
            syncUiTheme()
            loading = false
        }
    }

    /** 未分组的书（书架首页只列这些 + 分组卡片） */
    val ungrouped: List<Book> get() = books.filter { it.groupId == 0L }

    fun booksOf(groupId: Long): List<Book> = books.filter { it.groupId == groupId }

    /** 重新读取阅读主题（在阅读器里改完主题、回到书架时调用） */
    fun syncUiTheme() {
        uiTheme = prefs.load().theme
    }

    fun applySort(s: ShelfSort) {
        sort = s
        prefs.shelfSort = s
        refresh()
    }

    fun applyView(v: ShelfView) {
        view = v
        prefs.shelfView = v
    }

    fun import(
        uris: List<Uri>,
        mode: ImportMode,
        title: String?,
        splitMode: SplitMode = SplitMode.SMART,
        splitOptions: SplitOptions = SplitOptions(mode = splitMode),
        onDone: (Long?) -> Unit = {}
    ) {
        if (uris.isEmpty()) return
        com.inkreader.ui.component.PickerTrace.log(
            appContext,
            "import.start n=${uris.size} mode=$mode split=${splitOptions.mode} uris=${uris.joinToString { it.scheme + ":" + it.lastPathSegment?.takeLast(24) }}"
        )
        importing = true
        importErrors = emptyList()
        importReport = emptyList()
        importFraction = 0f
        importStage = appContext.getString(R.string.repo_stage_start_import)
        scope.launch {
            // 导入链路上任何一环抛异常（磁盘写失败、SQLite 异常…）都不能让它冒到
            // 这个没有 CoroutineExceptionHandler 的 Main 作用域上（那会直接崩），
            // 也不能把 importing 留在 true 让进度遮罩永久卡死。这里统一兜住并给出可读原因。
            val result = runCatching {
                repo.importDocuments(
                    uris = uris,
                    mode = mode,
                    customTitle = title,
                    splitMode = splitOptions.mode,
                    splitOptions = splitOptions
                ) { stage, f ->
                    importStage = stage
                    importFraction = f
                }
            }.getOrElse { e ->
                importing = false
                importFraction = 1f
                importStage = ""
                importErrors = listOf(e.message ?: appContext.getString(R.string.action_unknown_error))
                toast = appContext.getString(
                    R.string.repo_import_failed,
                    e.message ?: appContext.getString(R.string.action_unknown_error)
                )
                refresh()
                onDone(null)
                return@launch
            }
            importing = false
            importFraction = 1f
            importStage = ""
            val errors = result.errors(appContext)
            com.inkreader.ui.component.PickerTrace.log(
                appContext,
                "import.done books=${result.bookIds.size} failed=${result.failed.size} errors=${errors.joinToString(" | ").take(300)}"
            )
            importErrors = errors
            importReport = if (result.failed.isEmpty()) emptyList() else result.files
            refresh()
            when {
                result.bookIds.isEmpty() ->
                    toast = errors.firstOrNull()
                        ?: appContext.getString(R.string.repo_import_failed_plain)
                errors.isNotEmpty() -> toast = appContext.getString(
                    R.string.repo_imported_partial, result.bookIds.size, errors.size
                )
                else -> {
                    val n = result.bookIds.size
                    toast = appContext.resources.getQuantityString(R.plurals.repo_imported_books, n, n)
                }
            }
            onDone(result.bookIds.firstOrNull())
        }
    }

    /** 明细对话框关闭后清掉报告，避免旋转屏幕又弹一次 */
    fun consumeImportReport() {
        importReport = emptyList()
    }

    fun createBook(title: String, author: String, onDone: (Long) -> Unit) {
        scope.launch {
            val id = repo.createManualBook(title, author)
            refresh()
            onDone(id)
        }
    }

    fun delete(bookId: Long) {
        scope.launch {
            repo.deleteBook(bookId)
            refresh()
            toast = appContext.getString(R.string.repo_deleted)
        }
    }

    fun rename(bookId: Long, title: String, author: String) {
        scope.launch {
            repo.updateBookMeta(bookId, title, author)
            refresh()
        }
    }

    fun consumeToast() {
        toast = null
    }
}
