package com.inkreader

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import com.inkreader.data.model.ShelfSort
import com.inkreader.data.model.ShelfView
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

/** Real navigation in a disposable library; never changes the installed user's books. */
object NavigationChecks {
    fun run(runner: Instrumentation, context: Context): List<String> {
        val passed = arrayListOf<String>()
        val activityRef = AtomicReference<MainActivity>()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { if (activity is MainActivity) activityRef.set(activity) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        val app = runner.targetContext.applicationContext as Application
        runner.runOnMainSync { app.registerActivityLifecycleCallbacks(callbacks) }
        val activity = try {
            ParcelFileDescriptor.AutoCloseInputStream(runner.uiAutomation.executeShellCommand(
                "am start --user 0 -f 0x10008000 -n ${runner.targetContext.packageName}/.MainActivity"
            )).use { it.readBytes() }
            val deadline = SystemClock.elapsedRealtime() + 10000
            while (activityRef.get() == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            activityRef.get() ?: error("Navigation activity did not start")
        } finally { runner.runOnMainSync { app.unregisterActivityLifecycleCallbacks(callbacks) } }
        lateinit var container: AppContainer
        runner.runOnMainSync { container = AppContainer(context) }
        fun nodes(root: AccessibilityNodeInfo? = runner.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> {
            if (root == null) return emptyList()
            return listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
        }
        fun visible(): List<AccessibilityNodeInfo> {
            if (android.os.Build.VERSION.SDK_INT >= 33) runner.uiAutomation.clearCache()
            return nodes().filter { it.isVisibleToUser && it.packageName?.toString() == runner.targetContext.packageName }
        }
        fun settle() {
            Thread.sleep(500)
            runCatching { runner.uiAutomation.waitForIdle(400, 5000) }
        }
        fun find(label: String) = visible().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 12000
            while (!condition()) {
                check(SystemClock.elapsedRealtime() < deadline) { message + "; " + visible().mapNotNull { it.text ?: it.contentDescription }.joinToString(" | ") }
                Thread.sleep(100)
            }
        }
        fun click(label: String, long: Boolean = false) {
            await("Click failed: $label") {
                var node = find(label)
                while (node != null && (if (long) !node.isLongClickable else !node.isClickable) && node.parent != null) node = node.parent
                node?.refresh() == true && node.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK)
            }
            settle()
        }
        fun back() { runner.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }; Thread.sleep(850) }
        fun scroll(times: Int = 1) {
            repeat(times) {
                val target = visible().firstOrNull { it.isScrollable } ?: error("No scrollable page")
                check(target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) { "Page did not scroll" }
                settle()
            }
        }
        fun positions(prefix: String): Map<String, String> = visible().filter { it.text?.startsWith(prefix) == true }.associate {
            it.text.toString() to Rect().also(it::getBoundsInScreen).toShortString()
        }
        fun same(expected: Map<String, String>, prefix: String) {
            check(expected.isNotEmpty())
            await("Position changed for $prefix: expected=$expected") { positions(prefix) == expected }
        }
        fun openAndReturn(prefix: String) {
            val before = positions(prefix)
            val label = before.keys.elementAt(before.size / 2)
            click(label)
            await("Reader not rendered") { visible().any { it.text?.contains("NAVIGATION_BODY") == true } }
            back()
            same(before, prefix)
        }
        try {
            runner.runOnMainSync { container.diagnostics.changeEnabled(true) }
            runBlocking {
                val group = container.repo.createGroup("Nav group")
                repeat(72) { i ->
                    val id = container.repo.createManualBook("Nav book %03d".format(i), "")
                    container.repo.saveChapterText(container.repo.chapters(id).single(), "NAVIGATION_BODY $i. ".repeat(100))
                    if (i >= 36) container.repo.moveBooksToGroup(listOf(id), group)
                }
            }
            runner.runOnMainSync {
                container.library.applySort(ShelfSort.TITLE)
                container.library.applyView(ShelfView.GRID)
                activity.setContent { InkRoot(container) }
            }
            await("Shelf not loaded") { positions("Nav book").isNotEmpty() }
            scroll(2)
            val grid = positions("Nav book")
            repeat(3) {
                openAndReturn("Nav book")
                runner.sendStatus(0, Bundle().apply { putString("stream", "grid return ${it + 1} passed\n") })
            }
            passed += "shelf grid returns to identical item bounds after three reader visits"
            await("No real reader frame metrics were recorded") {
                val events = runBlocking { container.diagnostics.report().getJSONArray("events") }
                (0 until events.length()).any { events.getJSONObject(it).getString("event") == "FRAME_SAMPLE" }
            }
            passed += "opt-in diagnostics observe actual reader frames"

            click(context.getString(R.string.shelf_toggle_view))
            scroll(2)
            val list = positions("Nav book")
            openAndReturn("Nav book")
            passed += "shelf list returns to identical item bounds"
            click(context.getString(R.string.shelf_toggle_view)); same(grid, "Nav book")
            click(context.getString(R.string.shelf_toggle_view)); same(list, "Nav book")
            passed += "grid and list retain independent positions when switched"

            click(context.getString(R.string.action_more))
            click(context.getString(R.string.action_settings))
            while (find(context.getString(R.string.help_title)) == null) scroll()
            val settings = positions(context.getString(R.string.help_title))
            repeat(2) { i ->
                click(context.getString(R.string.help_title))
                await("Help not loaded") { visible().any { it.isScrollable } }
                if (i == 0) back() else click(context.getString(R.string.action_back))
                same(settings, context.getString(R.string.help_title))
            }
            passed += "settings/help preserves footer bounds with both system and toolbar back"
            back(); same(list, "Nav book")
            passed += "returning from settings also restores shelf list position"

            click(context.getString(R.string.shelf_search_books))
            await("Search field missing") { visible().any { it.isEditable } }
            val field = visible().first { it.isEditable }
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Nav book")
            }))
            runner.uiAutomation.executeShellCommand("input keyevent 4").close()
            Thread.sleep(700)
            scroll(2)
            val search = positions("Nav book")
            click(search.keys.elementAt(search.size / 2), long = true)
            await("Detail not loaded") { find(context.getString(R.string.detail_chapter_management)) != null }
            back(); same(search, "Nav book")
            check(visible().any { it.isEditable && it.text?.toString() == "Nav book" })
            passed += "search retains query and result bounds after opening book details"
            val changedField = visible().first { it.isEditable }
            check(changedField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Nav book 00")
            }))
            await("Changed query retained the old result offset") { "Nav book 000" in positions("Nav book") }
            passed += "changing search terms resets results to the beginning"
            back()
            click(context.getString(R.string.shelf_search_books))
            await("Fresh search reused a closed page") { visible().any { it.isEditable && it.text.isNullOrEmpty() } }
            runner.uiAutomation.executeShellCommand("input keyevent 4").close()
            Thread.sleep(500)
            back()
            passed += "a newly opened search starts empty after the previous entry was closed"

            // Rebuild the composition from its Android-saveable registry snapshot. This
            // exercises saved back-stack IDs together with each page's retained state.
            fun mountSavedRoot(state: SaveableStateRegistry) {
                // Use the same composition call site before and after recreation:
                // rememberSaveable keys include the enclosing composition identity.
                runner.runOnMainSync { activity.setContent { CompositionLocalProvider(LocalSaveableStateRegistry provides state) { InkRoot(container) } } }
            }
            var registry = SaveableStateRegistry(null) { true }
            mountSavedRoot(registry)
            Thread.sleep(1000)
            click("Nav group")
            scroll(2)
            openAndReturn("Nav book")
            val group = positions("Nav book")
            click(context.getString(R.string.action_detail))
            await("Group detail not loaded") { find(context.getString(R.string.detail_chapter_management)) != null }
            back(); same(group, "Nav book")
            passed += "group list preserves item bounds after reading and chapter management"
            back()
            scroll(2)
            val savedShelf = positions("Nav book")
            click(context.getString(R.string.action_more)); click(context.getString(R.string.action_settings))
            while (find(context.getString(R.string.help_title)) == null) scroll()
            val savedSettings = positions(context.getString(R.string.help_title))
            lateinit var saved: Map<String, List<Any?>>
            runner.runOnMainSync {
                saved = registry.performSave()
                check(saved.isNotEmpty()) { "No navigation state was registered" }
                activity.setContent { Text("navigation-rebuild") }
            }
            Thread.sleep(600)
            // Parcel round-trip verifies that custom navigation entries can really be
            // placed in savedInstanceState rather than merely retained in memory.
            val parcel = android.os.Parcel.obtain()
            val restored = try {
                parcel.writeMap(saved)
                parcel.setDataPosition(0)
                @Suppress("DEPRECATION", "UNCHECKED_CAST")
                (parcel.readHashMap(NavigationChecks::class.java.classLoader) as Map<String, List<Any?>>)
            } finally { parcel.recycle() }
            registry = SaveableStateRegistry(restored) { true }
            mountSavedRoot(registry)
            same(savedSettings, context.getString(R.string.help_title))
            back(); same(savedShelf, "Nav book")
            passed += "serialized navigation stack restores settings and shelf scroll after composition recreation"
            val modelFile = java.io.File(context.cacheDir, "model-ui.txt").apply {
                writeBytes(runner.context.assets.open("real-source-v3/C19A.txt").use { it.readBytes() })
            }
            val modelUri = android.net.Uri.fromFile(modelFile)
            val source = modelFile.readText()
            val gold = com.inkreader.parse.SourceGold.read(runner.context.assets.open("real-source-v3/C19A.gold").bufferedReader().use { it.readText() }, source)
            val normalized = com.inkreader.parse.SourceGold.normalizeContract(source)
            val options = com.inkreader.data.model.SplitOptions(cleanWatermark = false, modelSuggestions = true)
            val plan = com.inkreader.parse.ChapterSplitter.plan(normalized.lines(), normalized.length, options)
            val suggestedLine = plan.modelLines.first { it + 1 in gold.lines }
            val suggestedTitle = normalized.lines()[suggestedLine].trim()
            val modelBook = runBlocking {
                container.repo.importDocuments(listOf(modelUri), com.inkreader.data.model.ImportMode.SEPARATE,
                    customTitle = null,
                    splitOptions = options.copy(modelSuggestions = false)) { _, _ -> }.bookIds.single()
            }
            runner.runOnMainSync { activity.setContent {
                com.inkreader.ui.theme.InkTheme {
                    com.inkreader.ui.bookdetail.BookDetailScreen(container, modelBook, {}, { _, _ -> }, { _, _ -> })
                }
            } }
            await("Model detail not loaded") { find(context.getString(R.string.detail_chapter_management)) != null }
            click(context.getString(R.string.action_more)); click(context.getString(R.string.detail_resplit_book))
            val modelLabel = context.getString(R.string.analysis_model_title)
            repeat(10) { if (find(modelLabel) == null) scroll() }
            fun modelSwitch(): AccessibilityNodeInfo? {
                var node = find(modelLabel)
                while (node != null && !node.isCheckable) node = node.parent
                return node
            }
            check(modelSwitch()?.isChecked == false) { "Model assistance must default off" }
            click(modelLabel)
            check(modelSwitch()?.isChecked == true) { "Model switch did not enable" }
            passed += "real resplit dialog exposes a default-off model switch"
            repeat(10) {
                if (find(context.getString(R.string.center_title)) == null) {
                    check(visible().first { it.isScrollable }.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
                    settle()
                }
            }
            click(context.getString(R.string.center_title))
            val rowLabel = "${suggestedLine + 1} · $suggestedTitle"
            await("Model suggestion absent from correction UI") { find(rowLabel) != null }
            check(runBlocking { container.repo.chapters(modelBook) }.none { it.title == suggestedTitle })
            click(rowLabel); click(context.getString(R.string.center_apply))
            click(context.getString(R.string.detail_resplit_confirm))
            await("Confirmed model suggestion was not used") {
                runBlocking { container.repo.chapters(modelBook) }.any { it.title == suggestedTitle }
            }
            passed += "model suggestion remains unchecked until user confirms it in the actual review UI"
            return passed
        } finally {
            runner.runOnMainSync { container.diagnostics.changeEnabled(false) }
            runner.runOnMainSync { activity.setContent { InkRoot() } }
            runner.waitForIdleSync()
            runner.runOnMainSync { container.scope.cancel() }
            container.repo.db.close()
        }
    }
}
