package com.inkreader

import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import com.inkreader.data.model.*
import com.inkreader.ui.reader.ReaderScreen
import com.inkreader.ui.reader.ReaderSession
import com.inkreader.ui.theme.InkTheme
import kotlinx.coroutines.runBlocking

object ReaderUiChecks {
    fun run(runner: Instrumentation, activity: MainActivity, container: AppContainer): List<String> {
        val passed = ArrayList<String>()
        val book = runBlocking {
            val id = container.repo.createManualBook("阅读界面隔离测试", "")
            val chapter = container.repo.chapters(id).single()
            container.repo.renameChapter(chapter.id, "第一章 测试")
            container.repo.saveChapterText(chapter, (1..80).joinToString("\n\n") { "第${it}段测试锚点，阅读界面保留文字并验证正常翻页。" })
            val next = container.repo.addEmptyChapter(id, "第二章 测试")
            container.repo.saveChapterText(container.repo.chapters(id).first { it.id == next }, "第二章测试锚点。".repeat(300))
            id
        }
        fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
            if (root == null) return emptyList()
            val output = arrayListOf(root)
            for (index in 0 until root.childCount) output += nodes(root.getChild(index))
            return output
        }
        fun find(text: String): AccessibilityNodeInfo? {
            val visible = nodes(runner.uiAutomation.rootInActiveWindow).filter {
                it.isVisibleToUser && it.packageName?.toString() == runner.targetContext.packageName
            }
            return visible.firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
                ?: visible.firstOrNull { it.text?.toString()?.contains(text) == true }
        }
        fun await(message: String, timeout: Long = 15000, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + timeout
            while (!condition()) {
                check(SystemClock.elapsedRealtime() < end) {
                    val state = runBlocking { container.repo.book(book) }
                    val visible = nodes(runner.uiAutomation.rootInActiveWindow).filter { it.isVisibleToUser }
                        .joinToString(" | ") { "${it.text ?: it.contentDescription} clickable=${it.isClickable}" }
                    var sessionState = "no session"
                    runner.runOnMainSync {
                        val handler = container.readerKeyHandler
                        val sessionField = handler?.javaClass?.declaredFields?.firstOrNull { it.type == ReaderSession::class.java }
                        val session = sessionField?.apply { isAccessible = true }?.get(handler) as? ReaderSession
                        if (session != null) {
                            val internals = listOf("pendingWidth", "pendingHeight", "paginationSerial", "chapterGeneration", "layoutGeneration", "paginationJob", "chapterLoadJob").joinToString {
                                val field = ReaderSession::class.java.getDeclaredField(it).apply { isAccessible = true }
                                "$it=${field.get(session)}"
                            }
                            sessionState = "text=${session.chapterText.length}, blocks=${session.blocks.size}, pages=${session.pages.size}, entries=${session.pagerEntries.size}; $internals"
                        }
                    }
                    "$message; chapter=${state?.lastChapterIndex}, offset=${state?.lastCharOffset}; $sessionState; $visible"
                }
                Thread.sleep(80)
            }
        }
        fun click(text: String) {
            await("Missing control: $text") { find(text) != null }
            var clicked = false
            await("Click failed: $text") {
                var node = find(text)
                while (node != null && !node.isClickable && node.parent != null) node = node.parent
                clicked = node?.refresh() == true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                clicked
            }
            Thread.sleep(250)
        }
        fun tap(x: Float, y: Float) {
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, now + 50, action, x, y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                runner.uiAutomation.injectInputEvent(event, true); event.recycle()
            }
        }
        fun clickReaderControl(label: String) {
            var lastToggle = SystemClock.elapsedRealtime() - 1200
            await("Reader control did not become actionable: $label") {
                var node = find(label)
                while (node != null && !node.isClickable && node.parent != null) node = node.parent
                if (node?.refresh() == true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) true
                else {
                    if (SystemClock.elapsedRealtime() - lastToggle >= 1200) {
                        val area = Rect().also { runner.uiAutomation.rootInActiveWindow.getBoundsInScreen(it) }
                        tap(area.exactCenterX(), area.exactCenterY())
                        lastToggle = SystemClock.elapsedRealtime()
                    }
                    false
                }
            }
        }
        fun mount(mode: PageMode, chapter: Int = 0, offset: Int = 0) {
            runner.runOnMainSync {
                activity.setContent { InkTheme(readerTheme = ReaderTheme.MIST) { androidx.compose.material3.Text("prepare-reader") } }
                container.prefs.save(ReaderPrefs(pageMode = mode, pageAnimation = PageAnimation.NONE, volumeKeyTurn = true))
                activity.setContent { InkTheme(readerTheme = ReaderTheme.MIST) {
                    androidx.compose.runtime.key(mode, chapter, offset) {
                        ReaderScreen(container, book, chapter, offset, {}, { _, _ -> }, { _, _ -> }, {}, {})
                    }
                } }
            }
            Thread.sleep(700)
            await("Reader did not mount") { container.readerKeyHandler != null }
        }
        mount(PageMode.PAGE)
        var lastTurn = 0L
        await("Page turn did not persist an offset") {
            if (SystemClock.elapsedRealtime() - lastTurn > 600) {
                runner.runOnMainSync { container.readerKeyHandler?.invoke(KeyEvent.KEYCODE_VOLUME_DOWN) }
                lastTurn = SystemClock.elapsedRealtime()
            }
            runBlocking { container.repo.book(book)!!.lastCharOffset > 0 }
        }
        passed += "actual reader page rendering and page-turn progress persistence"
        val bounds = Rect().also { runner.uiAutomation.rootInActiveWindow.getBoundsInScreen(it) }
        clickReaderControl(runner.targetContext.getString(R.string.reader_toc))
        click("第二章 测试")
        await("TOC selection did not change chapter") { runBlocking { container.repo.book(book)!!.lastChapterIndex == 1 } }
        passed += "reader toolbar and actual table-of-contents chapter selection"

        // The sheet and toolbar animate independently. A single visibility probe can race
        // the exiting sheet and accidentally hide an already-open toolbar with a blind tap.
        val listen = runner.targetContext.getString(R.string.reader_read_aloud)
        clickReaderControl(listen)
        await("Missing computer voice panel") { find("电脑语音") != null }
        if (find("选择朗读模型") != null) {
            click("选择朗读模型")
            await("Missing model options") { find("七海") != null && find("晓晓") != null && find("自动") != null }
            click("关闭模型选择")
            click(runner.targetContext.getString(R.string.action_close))
        } else {
            // Pairing is external state. Render the same model dialog without altering real credentials.
            click(runner.targetContext.getString(R.string.action_close))
            runner.runOnMainSync { activity.setContent { InkTheme(readerTheme = ReaderTheme.MIST) {
                com.inkreader.ui.reader.VoiceModelDialog(com.inkreader.tts.ComputerVoiceModel.AUTO,
                    onDismiss = { activity.setContent { androidx.compose.material3.Text("model-test-complete") } }, onSave = {})
            } } }
            await("Missing offline model options") { find("七海") != null && find("晓晓") != null && find("自动") != null }
            click("关闭模型选择")
        }
        passed += "listening sheet and automatic/Xiaoxiao/Nanami model dialog render without a LAN dependency"

        mount(PageMode.SCROLL)
        val down = SystemClock.uptimeMillis()
        val x = bounds.exactCenterX()
        val start = bounds.height() * .75f
        val end = bounds.height() * .30f
        for (step in 0..12) {
            val action = if (step == 0) MotionEvent.ACTION_DOWN else if (step == 12) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
            val event = MotionEvent.obtain(down, down + step * 25L, action, x, start + (end - start) * step / 12, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            runner.uiAutomation.injectInputEvent(event, true); event.recycle()
            Thread.sleep(25)
        }
        await("Scroll did not update reading position") { runBlocking { container.repo.book(book)!!.lastCharOffset > 0 } }
        val saved = runBlocking { container.repo.book(book)!!.lastCharOffset }
        mount(PageMode.SCROLL, 0, saved)
        Thread.sleep(1000)
        val restored = runBlocking { container.repo.book(book)!!.lastCharOffset }
        check(kotlin.math.abs(restored - saved) < 100) { "Scroll restore drift: $saved -> $restored" }
        passed += "actual touch scrolling and paragraph-offset restoration"
        fun currentSession(): ReaderSession {
            var found: ReaderSession? = null
            runner.runOnMainSync {
                val visited = java.util.IdentityHashMap<Any, Boolean>()
                fun locate(value: Any?, depth: Int): ReaderSession? {
                    if (value is ReaderSession) return value
                    if (value == null || depth > 6 || visited.put(value, true) != null) return null
                    if (value is androidx.compose.runtime.State<*>) return locate(value.value, depth + 1)
                    if (value !is Function<*> && !value.javaClass.name.startsWith("kotlin.jvm.internal.Ref")) return null
                    for (field in value.javaClass.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                        val child = runCatching { field.isAccessible = true; field.get(value) }.getOrNull()
                        locate(child, depth + 1)?.let { return it }
                    }
                    return null
                }
                found = locate(container.readerKeyHandler, 0)
            }
            return found ?: error("Missing live reader session in test callback graph")
        }
        fun offset(): Int {
            val live = currentSession()
            var value = 0
            runner.runOnMainSync { value = live.currentOffset() }
            return value
        }
        val layoutAnchor = offset()
        val live = currentSession()
        runner.runOnMainSync { live.updatePrefs({ it.copy(fontSize = it.fontSize + 5f) }, persist = false) }
        Thread.sleep(1200)
        await("Font resize drifted away from the reading anchor") { kotlin.math.abs(offset() - layoutAnchor) < 120 }
        passed += "scroll font resize keeps the original text anchor"
        val originalOrientation = activity.requestedOrientation
        try {
            runner.runOnMainSync { activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await("Landscape layout not applied") {
                val box = Rect().also { runner.uiAutomation.rootInActiveWindow.getBoundsInScreen(it) }
                box.width() > box.height()
            }
            Thread.sleep(1200)
            check(kotlin.math.abs(offset() - layoutAnchor) < 180) { "Landscape changed reading anchor: $layoutAnchor -> ${offset()}" }
            runner.runOnMainSync { activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await("Portrait layout not restored") {
                val box = Rect().also { runner.uiAutomation.rootInActiveWindow.getBoundsInScreen(it) }
                box.width() < box.height()
            }
            Thread.sleep(1200)
            check(kotlin.math.abs(offset() - layoutAnchor) < 180) { "Portrait changed reading anchor" }
            passed += "landscape and portrait reflow retain the reading anchor"
        } finally { runner.runOnMainSync { activity.requestedOrientation = originalOrientation } }
        val backgroundAnchor = offset()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(runner.uiAutomation.executeShellCommand("input keyevent 3")).use { it.readBytes() }
        Thread.sleep(500)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(runner.uiAutomation.executeShellCommand(
            "am start --user 0 -f 0x10000000 -n ${runner.targetContext.packageName}/.MainActivity")).use { it.readBytes() }
        Thread.sleep(800)
        check(kotlin.math.abs(offset() - backgroundAnchor) < 120) { "Background/resume changed reading anchor" }
        passed += "background and foreground preserve the live reading position"
        val hits = runBlocking { container.repo.searchInBook(book, "测试锚点") }
        check(hits.any { it.chapterIndex == 0 } && hits.any { it.chapterIndex == 1 })
        passed += "whole-book search returns anchors in both chapters"
        runBlocking { container.repo.addEmptyChapter(book, "第三章 空白") }
        mount(PageMode.PAGE, 2)
        await("Empty chapter did not open") {
            find(runner.targetContext.getString(R.string.reader_empty_chapter)) != null &&
                runBlocking { container.repo.book(book)!!.lastChapterIndex == 2 }
        }
        clickReaderControl(runner.targetContext.getString(R.string.reader_toc))
        click("第一章 测试")
        await("Empty chapter trapped toolbar navigation") {
            runBlocking { container.repo.book(book)!!.lastChapterIndex == 0 }
        }
        passed += "empty chapter keeps reader toolbar and chapter navigation reachable"
        return passed
    }
}
