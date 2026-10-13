package com.inkreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
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
import com.inkreader.ui.marks.MarksScreen
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
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        collectIncoming(intent)
        setContent { InkRoot() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        collectIncoming(intent)
    }

    /** 上一颗被阅读器消费掉的音量键，用来把对应的抬手事件也一起吃掉 */
    private var consumedVolumeKey = -1

    /**
     * 音量键翻页。
     *
     * 在 dispatchKeyEvent 里拦：事件先经过这里，返回 true 就不会再被系统当成音量调节。
     * 只有阅读器在前台并且注册了处理器时才拦，其它界面音量键照常工作。
     */
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
                // 长按不重复翻页：已经认领的按键一路吃掉
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
                if (uri != null) container.pendingUris = listOf(uri)
            }

            Intent.ACTION_SEND -> {
                val uri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                if (uri != null) container.pendingUris = listOf(uri)
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
                        onBack = { pop() }
                    )

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
