package com.inkreader

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inkreader.data.model.Book
import com.inkreader.data.model.BookGroup
import com.inkreader.data.model.ImportMode
import com.inkreader.data.model.ShelfSort
import com.inkreader.data.model.ShelfView
import com.inkreader.data.model.SplitMode
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

    /** 外部通过 Intent 打开文件时，先把待导入的 Uri 放在这里（可观察，书架会立刻接住） */
    var pendingUris by mutableStateOf<List<android.net.Uri>>(emptyList())

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
     * 书架 / 详情 / 编辑器 / 笔记页都用它渲染；阅读器里换主题后由
     * [ReaderSession.updatePrefs] 调 [syncUiTheme] 同步过来。
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
        uris: List<android.net.Uri>,
        mode: ImportMode,
        title: String?,
        splitMode: SplitMode = SplitMode.SMART,
        onDone: (Long?) -> Unit = {}
    ) {
        if (uris.isEmpty()) return
        importing = true
        importErrors = emptyList()
        importReport = emptyList()
        importFraction = 0f
        importStage = appContext.getString(R.string.repo_stage_start_import)
        scope.launch {
            val result = repo.importDocuments(uris, mode, title, splitMode) { stage, f ->
                importStage = stage
                importFraction = f
            }
            importing = false
            importFraction = 1f
            importStage = ""
            val errors = result.errors(appContext)
            importErrors = errors
            // 失败清单交给界面弹明细（哪些成功、哪些失败、为什么），
            // toast 只做一句话概括。
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
