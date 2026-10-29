package com.inkreader.data.model

/**
 * 书籍来源类型。
 *
 * 新格式（MD / PDF / HTML / EPUB）直接追加在尾部：数据库存的是枚举名，
 * 老库里只可能有前四种，[InkDb] 遇到不认识的名字会回退成 TXT，向后兼容。
 */
enum class SourceType { TXT, DOCX, MERGED, MANUAL, MD, PDF, HTML, EPUB }

/** 阅读主题。MIST（月白）为默认的轻盈浅色主题，后四个为备选。 */
enum class ReaderTheme { MIST, PAPER, GREEN, INK, NIGHT }

/** 翻页模式 */
enum class PageMode { PAGE, SCROLL }

/**
 * 左右翻页时的翻页动画（只作用于 [PageMode.PAGE]）：
 * SLIDE=平移（两页一起动，默认）、COVER=覆盖（新页固定在下层，旧页整页平移离场）、
 * FOLD=仿真（折页覆盖）、NONE=无动画（立刻切换）。
 */
enum class PageAnimation { SLIDE, COVER, FOLD, NONE }

/** 字体选择 */
enum class FontChoice { SERIF, SANS }

/**
 * 标记类型。
 *
 * TAG  = v1.3.0 的「整页标签」：左右翻页模式下把页面往下拉即可打上，锚点是页首字符偏移。
 * MARK = v1.3.2 的「段落标签」：长按选中一段/一句后从操作条打上，正文下方会画一条
 *        蓝色下划线表示「这一段已经标记过了」（只加线，不改字色）。
 * 两者都属于同一套标记，都会出现在目录的「笔记」页签里。
 */
enum class MarkType { BOOKMARK, TAG, MARK, HIGHLIGHT, NOTE }

/** 书架排序 */
enum class ShelfSort { RECENT, TITLE, PROGRESS, ADDED }

/** 书架视图 */
enum class ShelfView { GRID, LIST }

/** 导入模式 */
enum class ImportMode { SEPARATE, MERGE }

/** 文件顺序独立于分章方式；SELECTION 保留传入顺序并禁止导入后的章号重排。 */
enum class ImportOrder { AUTO, SELECTION }

/**
 * 导入 / 追加文件 / 整书重分章时的「章节划分策略」。
 *
 * SMART（默认）：智能自动识别（同族模式投票 + 序号连续性校验 + 奇葩前缀自动归纳）。
 *   多文件合并时按文件内容逐个判断：有多个章节标题则拆成多章，否则整篇作为一章。
 * IN_FILE：一律解析文件内部章节（合集能拆，无标题时按字数兜底切分）。
 * PER_FILE：一律一个文件作为一章（不拆内部标题，章节名取文件名）。
 * STRICT_CHAP：仅按「第X章 / 第X回 / Chapter N」强章级规则切分，忽略数字序号与小节。
 * NUMBERED：按数字序号（`1.`、`01 `、`一、`、`（一）`）与章回标题切分。
 * FIXED_SIZE：不识别标题，直接按固定字数（默认约 1.2 万字）在段落边界切分。
 * CUSTOM：按用户输入的自定义前缀或正则表达式切分。
 */
enum class SplitMode {
    SMART,
    IN_FILE,
    PER_FILE,
    STRICT_CHAP,
    NUMBERED,
    FIXED_SIZE,
    CUSTOM
}

/**
 * 分章详细配置：承载切分策略、自定义前缀/正则、固定切分字数以及净网去噪开关。
 */
data class SplitOptions(
    val mode: SplitMode = SplitMode.SMART,
    val cleanWatermark: Boolean = true,
    val customPattern: String = "",
    val fixedChunkChars: Int = 12000,
    /** Empty means automatic detection. Applied to plain TXT only. */
    val textEncoding: String = "",
    /** One-based lines in normalized TXT. The import UI enables this for one file only. */
    val ignoredHeadingLines: Set<Int> = emptySet(),
    val forcedHeadingLines: Set<Int> = emptySet(),
    val rememberCorrections: Boolean = true,
    val useSavedCorrections: Boolean = true,
    /** 手动分章点绑定完整源字节；文件变化时拒绝套用旧行号。 */
    val sourceFingerprint: String = "",
    /** 重分章的源已经由书库正文重建，不能再把短前言当成重复书名删除。 */
    val stripLeadingTitle: Boolean = true,
    /** Opt-in evidence-based inference for unnumbered Chinese collection titles. */
    val detectUnnumberedTitles: Boolean = false,
    /** Existing first chapter metadata, supplied only while reconstructing a book for resplitting. */
    val reconstructedFirstTitle: String = "",
    /** Trained on-device model suggests extra boundaries for explicit review; never auto-cuts. */
    val modelSuggestions: Boolean = false
)

data class Book(
    val id: Long = 0,
    val title: String,
    val author: String = "",
    val sourceType: SourceType = SourceType.TXT,
    val coverHue: Int = 0,
    /** 自定义封面（应用私有目录里的文件名）；为空表示用书名自动生成的封面 */
    val coverPath: String = "",
    val totalChapters: Int = 0,
    val totalChars: Int = 0,
    val lastChapterIndex: Int = 0,
    val lastCharOffset: Int = 0,
    val lastReadAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val progress: Float = 0f,
    /** 所属分组；0 表示未分组。一本书最多属于一个分组 */
    val groupId: Long = 0L
) {
    val displayProgress: Float
        get() = if (totalChars <= 0) 0f else progress.coerceIn(0f, 1f)
}

/** 书架分组 */
data class BookGroup(
    val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0
)

data class Chapter(
    val id: Long = 0,
    val bookId: Long,
    val indexInBook: Int,
    val title: String,
    val fileName: String,
    val charCount: Int = 0,
    val sourceName: String = "",
    /** 所属分卷名（如「第一卷 风起」）；为空表示未分卷或默认卷 */
    val volume: String = ""
)

data class Mark(
    val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val type: MarkType = MarkType.HIGHLIGHT,
    val color: Int = 0,
    val note: String = "",
    val snippet: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/** 阅读偏好。全局唯一一套，所有书共用（键名见 [com.inkreader.data.prefs.PrefsStore]） */
data class ReaderPrefs(
    val fontSize: Float = 19f,          // sp
    val lineHeight: Float = 1.75f,      // 相对字号的倍数
    val paraSpacing: Float = 0.6f,      // 段间距，相对字号倍数
    val font: FontChoice = FontChoice.SANS,
    val theme: ReaderTheme = ReaderTheme.MIST,
    val pageMode: PageMode = PageMode.PAGE,
    val pageAnimation: PageAnimation = PageAnimation.SLIDE,
    val margin: Float = 20f,            // dp
    val firstLineIndent: Boolean = true,
    val brightness: Float = -1f,        // -1 表示跟随系统
    val letterSpacing: Float = 0.02f,   // em
    val volumeKeyTurn: Boolean = true,  // 音量键翻页（跟随全局，不按书区分）
    val tagSound: Boolean = true,       // 打标签的「咚」音效（跟随全局）
    val chapterFlash: Boolean = true,   // 切章时中央那张章节名卡片（跟随全局）
    val ttsRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsTimerMinutes: Int = 0        // 0 = 不定时
)

/** 全文搜索结果 */
data class SearchHit(
    val chapterIndex: Int,
    val chapterTitle: String,
    val offset: Int,
    val context: String
)
