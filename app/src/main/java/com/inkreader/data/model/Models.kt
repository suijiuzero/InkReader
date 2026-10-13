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
 *
 * 曾经有过 COVER（覆盖）和 CURL（仿真）并删掉过：那两种要「旧页压在新页上面」，
 * 而 `HorizontalPager` 的绘制顺序是页号递增、页号大的在上，靠改位移做不出来 ——
 * 得把旧页单独提到 pager 之上再画一层，逻辑和状态都变复杂，还各自带出一串边界问题。
 * 实测收益不值这个复杂度，已删掉。
 *
 * 现在 [COVER] / [FOLD] 重做走的是另一条路：完全不走 `HorizontalPager`，而是自己搭叠层
 * （① 露出页在下 + ② 承载页在上，在自己的 draw pass 里决定怎么动），
 * 绘制顺序由我们自己控制，因此旧页压在新页之上是天然成立的，不需要额外的提层。
 * 两者共用同一套叠层与手势，只有「承载页怎么离开」不同：覆盖=整页平移动画，
 * 仿真=按折线裁剪 + 纸背。切到这两个档只在设置里换一个值，分页与阅读进度完全不受影响。
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

/**
 * 合并导入 / 追加文件时的「章节划分」方式。
 *
 * SMART（默认）：**按文件内容逐个判断** —— 文件里有多个「第X章」就拆成多章；
 *   只有一个（或压根没有）就整篇作为一章、章名取文件名。
 *   典型场景：一次选了「1~9 章合集.txt」和「第10章 xxx.txt」，
 *   合集拆成 1~9 章，单独的第十二章整篇成为一章，不需要你分别导两次。
 * IN_FILE：一律解析文件内部章节（合集能拆，单章文件若没有内部标题会被按字数兜底切分）。
 * PER_FILE：一律一个文件一章（合集就会变成一整章）。
 */
enum class SplitMode { SMART, IN_FILE, PER_FILE }

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
    val sourceName: String = ""
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
