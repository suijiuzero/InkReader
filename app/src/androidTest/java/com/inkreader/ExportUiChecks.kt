package com.inkreader

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import com.inkreader.ui.bookdetail.BookDetailScreen
import com.inkreader.ui.theme.InkTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import com.inkreader.data.model.*

object ExportUiChecks {
    fun run(runner: Instrumentation, context: android.content.Context): List<String> {
        val ref = AtomicReference<MainActivity>()
        val app = runner.targetContext.applicationContext as Application
        val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { if (a is MainActivity) ref.set(a) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        runner.runOnMainSync { app.registerActivityLifecycleCallbacks(callback) }
        val activity = try {
            ParcelFileDescriptor.AutoCloseInputStream(runner.uiAutomation.executeShellCommand("am start --user 0 -f 0x10008000 -n ${runner.targetContext.packageName}/.MainActivity")).use { it.readBytes() }
            val end = SystemClock.elapsedRealtime() + 15000
            while (ref.get() == null && SystemClock.elapsedRealtime() < end) Thread.sleep(50)
            ref.get() ?: error("Export UI activity missing")
        } finally { runner.runOnMainSync { app.unregisterActivityLifecycleCallbacks(callback) } }
        lateinit var container: AppContainer
        runner.runOnMainSync { container = AppContainer(context) }
        fun nodes(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { nodes(n.getChild(it)) }
        fun visible(): List<AccessibilityNodeInfo> {
            if (android.os.Build.VERSION.SDK_INT >= 33) runner.uiAutomation.clearCache()
            return nodes(runner.uiAutomation.rootInActiveWindow).filter { it.isVisibleToUser }
        }
        fun find(text: String) = visible().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
        fun click(text: String) {
            val end = SystemClock.elapsedRealtime() + 15000
            while (true) {
                var node = find(text)
                while (node != null && !node.isClickable && node.parent != null) node = node.parent
                if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) break
                check(SystemClock.elapsedRealtime() < end) { "Missing export UI: $text" }; Thread.sleep(100)
            }
            Thread.sleep(300)
        }
        try {
            val file = File(context.cacheDir, "export-ui.txt").apply { writeText("第一章 开始\n\nPublic export UI fixture.\n\n第二章 继续\n\nMore public text.") }
            val book = runBlocking { container.repo.importResumable(listOf(android.net.Uri.fromFile(file)), ImportMode.SEPARATE, null, SplitOptions(cleanWatermark = false), ImportOrder.SELECTION) { _, _ -> }.bookIds.single() }
            runner.runOnMainSync { activity.setContent { InkTheme { BookDetailScreen(container, book, {}, { _, _ -> }, { _, _ -> }) } } }
            runner.waitForIdleSync(); Thread.sleep(600)
            click(context.getString(R.string.action_more)); click(context.getString(R.string.export_whole_file))
            val end = SystemClock.elapsedRealtime() + 10000
            while (find("PDF") == null && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
            for (format in com.inkreader.data.export.ExportFormat.entries) check(find(format.name) != null) { "Format choice not visible" }
            runner.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(runner.targetContext.cacheDir, "export-format-dialog.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            runner.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            Thread.sleep(400)
            check(find(context.getString(R.string.export_choose_format)) == null)
            return listOf("whole-book menu opens all four formats with descriptions", "format dialog dismisses without exporting")
        } finally {
            runner.runOnMainSync { activity.setContent { InkRoot() }; container.scope.cancel() }
            runner.waitForIdleSync(); container.repo.db.close()
        }
    }
}
