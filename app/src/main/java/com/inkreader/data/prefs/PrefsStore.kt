package com.inkreader.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.inkreader.data.model.FontChoice
import com.inkreader.data.model.PageAnimation
import com.inkreader.data.model.PageMode
import com.inkreader.data.model.ReaderPrefs
import com.inkreader.data.model.ReaderTheme
import com.inkreader.data.model.ShelfSort
import com.inkreader.data.model.ShelfView
import com.inkreader.data.model.ImportOrder

/**
 * 阅读偏好与书架偏好的持久化。
 *
 * 阅读偏好**全局唯一一套**：字号、行距、字体、主题、翻页方式、翻页动画、页边距、
 * 首行缩进、亮度等，不管是从书架三点菜单进的设置页改，还是在阅读器里改，
 * 改的都是同一套，所有书共用。
 *
 * 曾经做过「全局默认 + 单书覆盖」（键名加 `b{bookId}_` 前缀），用起来是割裂的：
 * 在 A 书调好的排版换到 B 书就变回旧的，还得手动「设为全局默认」才推得过去。
 * 老版本残留的 `b{id}_*` 键不再被读取（留着不影响，键名对不上）。
 */
class PrefsStore(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("ink_prefs", Context.MODE_PRIVATE)

    fun load(): ReaderPrefs = ReaderPrefs(
        fontSize = sp.getFloat(K_SIZE, 19f),
        lineHeight = sp.getFloat(K_LH, 1.75f),
        paraSpacing = sp.getFloat(K_PARA, 0.6f),
        font = enumOrDefault(sp.getString(K_FONT, null), FontChoice.SANS),
        theme = enumOrDefault(sp.getString(K_THEME, null), ReaderTheme.MIST),
        pageMode = enumOrDefault(sp.getString(K_MODE, null), PageMode.PAGE),
        pageAnimation = enumOrDefault(sp.getString(K_ANIM, null), PageAnimation.SLIDE),
        margin = sp.getFloat(K_MARGIN, 20f),
        firstLineIndent = sp.getBoolean(K_INDENT, true),
        brightness = sp.getFloat(K_BRIGHT, -1f),
        letterSpacing = sp.getFloat(K_LS, 0.02f),
        volumeKeyTurn = sp.getBoolean(K_VOL_KEY, true),
        tagSound = sp.getBoolean(K_TAG_SOUND, true),
        chapterFlash = sp.getBoolean(K_CHAPTER_FLASH, true),
        ttsRate = sp.getFloat(K_TTS_RATE, 1.0f),
        ttsPitch = sp.getFloat(K_TTS_PITCH, 1.0f),
        ttsTimerMinutes = sp.getInt(K_TTS_TIMER, 0)
    )

    fun save(prefs: ReaderPrefs) {
        sp.edit()
            .putFloat(K_SIZE, prefs.fontSize)
            .putFloat(K_LH, prefs.lineHeight)
            .putFloat(K_PARA, prefs.paraSpacing)
            .putString(K_FONT, prefs.font.name)
            .putString(K_THEME, prefs.theme.name)
            .putString(K_MODE, prefs.pageMode.name)
            .putString(K_ANIM, prefs.pageAnimation.name)
            .putFloat(K_MARGIN, prefs.margin)
            .putBoolean(K_INDENT, prefs.firstLineIndent)
            .putFloat(K_BRIGHT, prefs.brightness)
            .putFloat(K_LS, prefs.letterSpacing)
            .putFloat(K_TTS_RATE, prefs.ttsRate)
            .putFloat(K_TTS_PITCH, prefs.ttsPitch)
            .putInt(K_TTS_TIMER, prefs.ttsTimerMinutes)
            .putBoolean(K_VOL_KEY, prefs.volumeKeyTurn)
            .putBoolean(K_TAG_SOUND, prefs.tagSound)
            .putBoolean(K_CHAPTER_FLASH, prefs.chapterFlash)
            .apply()
    }

    // ---- 书架偏好 ----
    var shelfSort: ShelfSort
        get() = enumOrDefault(sp.getString(K_SHELF_SORT, null), ShelfSort.RECENT)
        set(v) {
            sp.edit().putString(K_SHELF_SORT, v.name).apply()
        }

    var shelfView: ShelfView
        get() = enumOrDefault(sp.getString(K_SHELF_VIEW, null), ShelfView.GRID)
        set(v) {
            sp.edit().putString(K_SHELF_VIEW, v.name).apply()
        }

    // ---- 导入流程的现场保护 ----
    //
    // 系统文件选择器是独立界面，打开它时本 App 在后台，国产 ROM
    //（实测 vivo 的 fast_freezer / single-cleaner）会冻结甚至杀掉进程，
    // 导致「选完文件按确定却毫无反应」。这里把现场落盘，进程没了也能续上。

    /** 发起文件选择器的时间戳；结果回来后清零 */
    var pickerLaunchedAt: Long
        get() = sp.getLong(K_PICKER_AT, 0L)
        set(v) {
            // 必须同步落盘：进程随时可能在选择器打开期间被杀
            sp.edit().putLong(K_PICKER_AT, v).commit()
        }

    /** 上次自动重开选择器的时间戳，避免反复重开 */
    var pickerRetriedAt: Long
        get() = sp.getLong(K_PICKER_RETRY_AT, 0L)
        set(v) {
            sp.edit().putLong(K_PICKER_RETRY_AT, v).commit()
        }

    /** 已经选好、但还没导完的文件（进程被杀后重进 App 可以接着导） */
    var pendingImportUris: List<String>
        get() = sp.getString(K_PENDING_URIS, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        set(v) {
            savePendingImport(v, ImportOrder.AUTO)
        }

    val pendingImportOrder: ImportOrder
        get() = enumOrDefault(sp.getString(K_PENDING_ORDER, null), ImportOrder.AUTO)

    /** 文件列表与顺序策略一起落盘，恢复确认页时不能再退回自动排序。 */
    fun savePendingImport(uris: List<String>, order: ImportOrder) {
        sp.edit().putString(K_PENDING_URIS, uris.joinToString("\n"))
            .putString(K_PENDING_ORDER, if (uris.isEmpty()) ImportOrder.AUTO.name else order.name).commit()
    }

    /** 目录授权属于系统界面，回来时重建对应的墨阅选择页。 */
    var orderedPickerTarget: Int
        get() = sp.getInt("ordered_picker_target", 0)
        set(value) { sp.edit().putInt("ordered_picker_target", value).commit() }
    var orderedPickerBookId: Long
        get() = sp.getLong("ordered_picker_book", 0L)
        set(value) { sp.edit().putLong("ordered_picker_book", value).commit() }
    var pendingAppendBookId: Long
        get() = sp.getLong("pending_append_book", 0L)
        set(value) { sp.edit().putLong("pending_append_book", value).commit() }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, def: T): T =
        if (value == null) def else runCatching { enumValueOf<T>(value) }.getOrDefault(def)

    companion object {
        private const val K_SIZE = "font_size"
        private const val K_LH = "line_height"
        private const val K_PARA = "para_spacing"
        private const val K_FONT = "font"
        private const val K_THEME = "theme"
        private const val K_MODE = "page_mode"
        private const val K_ANIM = "page_animation"
        private const val K_MARGIN = "margin"
        private const val K_VOL_KEY = "volume_key_turn"
        private const val K_TAG_SOUND = "tag_sound"
        private const val K_CHAPTER_FLASH = "chapter_flash"
        private const val K_INDENT = "indent"
        private const val K_BRIGHT = "brightness"
        private const val K_LS = "letter_spacing"
        private const val K_TTS_RATE = "tts_rate"
        private const val K_TTS_PITCH = "tts_pitch"
        private const val K_TTS_TIMER = "tts_timer"
        private const val K_SHELF_SORT = "shelf_sort"
        private const val K_SHELF_VIEW = "shelf_view"
        private const val K_PICKER_AT = "picker_launched_at"
        private const val K_PICKER_RETRY_AT = "picker_retried_at"
        private const val K_PENDING_URIS = "pending_import_uris"
        private const val K_PENDING_ORDER = "pending_import_order"
    }
}
