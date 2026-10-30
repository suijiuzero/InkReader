package com.inkreader

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import com.inkreader.data.model.ImportMode
import com.inkreader.data.model.ImportOrder
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicReference

object ImportBackgroundChecks {
    fun run(runner: Instrumentation, context: Context): List<String> {
        val reference = AtomicReference<MainActivity>()
        val app = runner.targetContext.applicationContext as Application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { if (a is MainActivity) reference.set(a) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        runner.runOnMainSync { app.registerActivityLifecycleCallbacks(callbacks) }
        val activity = try {
            ParcelFileDescriptor.AutoCloseInputStream(runner.uiAutomation.executeShellCommand(
                "am start --user 0 -f 0x10008000 -n ${runner.targetContext.packageName}/.MainActivity"
            )).use { it.readBytes() }
            val deadline = SystemClock.elapsedRealtime() + 10000
            while (reference.get() == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            reference.get() ?: error("No foreground activity")
        } finally { runner.runOnMainSync { app.unregisterActivityLifecycleCallbacks(callbacks) } }
        lateinit var container: AppContainer
        runner.runOnMainSync { container = AppContainer(context) }
        fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (root == null) emptyList()
            else listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
        fun visible(): List<AccessibilityNodeInfo> {
            if (android.os.Build.VERSION.SDK_INT >= 33) runner.uiAutomation.clearCache()
            return nodes(runner.uiAutomation.rootInActiveWindow).filter { it.isVisibleToUser }
        }
        fun find(label: String) = visible().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
        fun await(message: String, timeout: Long = 15000, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (!condition()) { check(SystemClock.elapsedRealtime() < deadline) { message }; Thread.sleep(50) }
        }
        fun click(label: String) {
            await("Missing button $label") {
                var node = find(label)
                while (node != null && !node.isClickable && node.parent != null) node = node.parent
                node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            Thread.sleep(150)
        }
        fun screenshot(name: String) {
            runner.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(runner.targetContext.cacheDir, "large-import-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        try {
            val paragraph = "这是一段用于后台导入测试的普通正文。人物继续沿道路前行，在城门前看见老朋友。".repeat(15)
            val body = List(6) { paragraph.chunked(25).joinToString("\n") }.joinToString("\n\n")
            val file = File(context.cacheDir, "background-1320.txt")
            file.bufferedWriter().use { out -> for (n in 1..1320) out.write("第${n}章 后台测试\n\n$body\n\n") }
            runner.runOnMainSync { activity.setContent { InkRoot(container) } }
            runner.waitForIdleSync()
            runner.runOnMainSync { container.library.import(listOf(Uri.fromFile(file)), ImportMode.SEPARATE, null, fileOrder = ImportOrder.SELECTION) }
            val background = context.getString(R.string.import_background)
            await("Import overlay absent") { find(background) != null }
            screenshot("overlay")
            click(background)
            await("Import did not move to compact progress") {
                container.library.importing && container.library.importInBackground && find(background) == null &&
                    visible().any { it.text?.toString()?.contains("%") == true }
            }
            screenshot("compact")
            val progress = visible().first { it.text?.toString()?.contains("%") == true }.text.toString()
            click(progress)
            await("Could not reopen progress") { !container.library.importInBackground && find(background) != null }
            click(background)
            click(context.getString(R.string.recovery_cancel))
            await("Background pause did not stop the import", 30000) { !container.library.importing && container.library.recoveryAvailable }
            check(!container.library.importInBackground)
            runner.runOnMainSync { container.library.resumeImport() }
            check(!container.library.importInBackground)
            await("Resumed import failed", 180000) { !container.library.importing }
            check(container.library.importErrors.isEmpty())
            val books = runBlocking { container.repo.books(com.inkreader.data.model.ShelfSort.ADDED) }
            check(books.size == 1 && runBlocking { container.repo.chapters(books.single().id) }.size == 1320)
            return listOf("real overlay background button hides modal without cancelling job", "compact progress reopens overlay",
                "compact pause cancels and keeps journal", "resume starts foreground progress and imports exactly 1320 chapters")
        } finally {
            runner.runOnMainSync { container.library.pauseImport() }
            Thread.sleep(200)
            runner.runOnMainSync { activity.setContent { InkRoot() }; container.scope.cancel() }
            runner.waitForIdleSync()
            container.repo.db.close()
        }
    }
}
