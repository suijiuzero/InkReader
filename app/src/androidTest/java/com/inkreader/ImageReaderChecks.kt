package com.inkreader

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import com.inkreader.data.model.PageMode
import com.inkreader.data.model.PageAnimation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

object ImageReaderChecks {
    fun run(runner: Instrumentation, context: Context): JSONObject {
        val reference = AtomicReference<MainActivity>()
        val app = runner.targetContext.applicationContext as Application
        val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { if (a is MainActivity) reference.set(a) }
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
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (reference.get() == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            reference.get() ?: error("Image test activity did not open")
        } finally { runner.runOnMainSync { app.unregisterActivityLifecycleCallbacks(callback) } }
        lateinit var container: AppContainer
        runner.runOnMainSync { container = AppContainer(context) }
        fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (root == null) emptyList() else listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
        fun visible(): List<AccessibilityNodeInfo> {
            if (android.os.Build.VERSION.SDK_INT >= 33) runner.uiAutomation.clearCache()
            return nodes(runner.uiAutomation.rootInActiveWindow).filter { it.isVisibleToUser && it.packageName?.toString() == runner.targetContext.packageName }
        }
        fun find(text: String) = visible().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 25000
            while (!condition()) { check(SystemClock.elapsedRealtime() < deadline) { message }; Thread.sleep(100) }
        }
        fun click(text: String) {
            await("Image UI target absent: $text") {
                var node = find(text)
                while (node != null && !node.isClickable && node.parent != null) node = node.parent
                node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            Thread.sleep(300)
        }
        fun capture(name: String): Bitmap {
            val bitmap = runner.uiAutomation.takeScreenshot() ?: error("No image UI screenshot")
            File(runner.targetContext.cacheDir, "document-images-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return bitmap
        }
        fun centerHash(bitmap: Bitmap): Long {
            var hash = 1125899906842597L
            for (y in bitmap.height / 2 - 150 until bitmap.height / 2 + 150 step 3)
                for (x in bitmap.width / 2 - 150 until bitmap.width / 2 + 150 step 3) hash = hash * 31 + bitmap.getPixel(x, y)
            return hash
        }
        fun pinch() {
            val metrics = activity.resources.displayMetrics; val x = metrics.widthPixels / 2f; val y = metrics.heightPixels / 2f
            val down = SystemClock.uptimeMillis()
            fun event(action: Int, distance: Float, pointers: Int) {
                val props = Array(pointers) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                val coords = Array(pointers) { i -> MotionEvent.PointerCoords().apply { this.x = x + if (i == 0) -distance else distance; this.y = y; pressure = 1f; size = 1f } }
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, pointers, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { check(runner.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            event(MotionEvent.ACTION_DOWN, 70f, 1)
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 70f, 2)
            for (i in 1..12) { Thread.sleep(25); event(MotionEvent.ACTION_MOVE, 70f + i * 15f, 2) }
            event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 250f, 2)
            event(MotionEvent.ACTION_UP, 250f, 1)
            Thread.sleep(500)
        }
        fun swipe(forward: Boolean) {
            val metrics = activity.resources.displayMetrics; val width = metrics.widthPixels.toFloat(); val y = metrics.heightPixels * .7f
            val start = if (forward) width * .85f else width * .15f; val end = width - start
            val began = SystemClock.uptimeMillis()
            fun event(action: Int, x: Float) {
                val e = MotionEvent.obtain(began, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { check(runner.uiAutomation.injectInputEvent(e, true)) } finally { e.recycle() }
            }
            event(MotionEvent.ACTION_DOWN, start)
            for (i in 1..12) { Thread.sleep(20); event(MotionEvent.ACTION_MOVE, start + (end - start) * i / 12) }
            event(MotionEvent.ACTION_UP, end); Thread.sleep(1000)
        }
        try {
            val data = runBlocking { DocumentImageChecks.run(runner, context, container.repo) }
            runner.runOnMainSync {
                container.prefs.save(container.prefs.load().copy(pageMode = PageMode.PAGE, pageAnimation = PageAnimation.SLIDE))
                container.library.refresh(); activity.setContent { InkRoot(container) }
            }
            await("Image book absent from shelf") { find("Image DOCX") != null }
            click("Image DOCX")
            val image = context.getString(R.string.reader_image)
            await("Reader image did not render") { find(image) != null }
            Thread.sleep(500); capture("page").recycle()
            click(image)
            val close = context.getString(R.string.reader_image_close)
            await("Fullscreen image did not open") { find(close) != null }
            Thread.sleep(500)
            val before = capture("fullscreen"); val beforeHash = centerHash(before); before.recycle()
            pinch()
            val after = capture("zoomed"); check(centerHash(after) != beforeHash) { "Pinch did not change visible image pixels" }; after.recycle()
            click(close)
            await("Reader did not return from image") { find(close) == null && find(image) != null }
            swipe(true)
            await("Paged image did not leave on next page") { find(image) == null }
            swipe(false)
            await("Image missing on previous page") { find(image) != null }
            runner.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
            await("Reader did not return to shelf") { find("Image DOCX") != null }
            runner.runOnMainSync { container.prefs.save(container.prefs.load().copy(pageMode = PageMode.SCROLL)) }
            click("Image DOCX")
            await("Image missing in scroll mode") { find(image) != null }
            capture("scroll").recycle()
            for ((title, name) in listOf("Image PDF" to "pdf", "Image Scan" to "scan", "Image Rotated" to "rotated")) {
                runner.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
                await("Image shelf did not return") { find(title) != null }
                runner.runOnMainSync { container.prefs.save(container.prefs.load().copy(pageMode = PageMode.PAGE)) }
                click(title)
                await("PDF image did not render") { find(image) != null }
                val frame = capture(name)
                if (name == "rotated") {
                    val bounds = Rect(); find(image)!!.getBoundsInScreen(bounds)
                    val pixel = frame.getPixel(bounds.left + 10, bounds.top + 10)
                    check(android.graphics.Color.green(pixel) > android.graphics.Color.red(pixel)) { "JPEG EXIF rotation was not drawn" }
                }
                frame.recycle()
                click(image); await("PDF image did not expand") { find(close) != null }
                click(close); await("PDF image did not close") { find(close) == null }
            }
            return data.report.put("readerUi", org.json.JSONArray(listOf("page rendering", "fullscreen open/close", "pinch changes image pixels", "forward/back page gestures", "scroll rendering", "PDF picture and scanned-page reader/fullscreen")))
        } finally {
            runner.runOnMainSync { activity.setContent { InkRoot() }; container.scope.cancel() }
            runner.waitForIdleSync(); container.repo.db.close()
        }
    }
}
