package com.inkreader

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import com.inkreader.data.model.PageMode
import com.inkreader.data.repo.BookWriteGate
import com.inkreader.ui.reader.ReaderSession
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Lifecycle and high-frequency input checks in disposable storage. */
object DailyUsageChecks {
    suspend fun run(runner: Instrumentation, context: Context): JSONObject {
        val rows = JSONArray()
        lateinit var container: AppContainer
        runner.runOnMainSync { container = AppContainer(context) }
        suspend fun test(name: String, block: suspend () -> JSONObject) {
            val row = JSONObject().put("name", name)
            try { row.put("details", block()).put("status", "PASS") }
            catch (t: Throwable) { row.put("status", "FAIL").put("error", t.stackTraceToString()) }
            rows.put(row)
            runner.sendStatus(0, Bundle().apply { putString("stream", "$row\n") })
        }
        try {
            test("reader final position survives page cancellation behind a busy book write gate") {
                val id = container.repo.createManualBook("daily-progress", "")
                container.repo.saveChapterText(container.repo.chapters(id).single(), "daily original paragraph. ".repeat(300))
                val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                lateinit var session: ReaderSession
                runner.runOnMainSync {
                    session = ReaderSession(container, id, pageScope)
                    session.updatePrefs({ it.copy(pageMode = PageMode.SCROLL) }, persist = false)
                    session.open(0, 0)
                }
                try {
                    withTimeout(10000) {
                        while (!withContext(Dispatchers.Main) { session.chapterText.isNotEmpty() }) delay(20)
                    }
                    delay(250)
                    val field = container.repo.javaClass.getDeclaredField("writes").apply { isAccessible = true }
                    val gate = field.get(container.repo) as BookWriteGate
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val blocker = container.scope.launch(Dispatchers.IO) { gate.write(id) { entered.complete(Unit); release.await() } }
                    entered.await()
                    try {
                        runner.runOnMainSync {
                            session.consumeJumpRequest()
                            session.updateScrollOffset(731)
                            session.saveProgress()
                        }
                        delay(80)
                        runner.runOnMainSync { pageScope.cancel() }
                    } finally { release.complete(Unit); blocker.join() }
                    withTimeout(3000) { while (container.repo.book(id)!!.lastCharOffset != 731) delay(30) }
                    JSONObject().put("finalOffset", 731).put("pageScopeCancelled", true)
                } finally { pageScope.cancel() }
            }
            test("rapid scroll saves are coalesced while the final exact position is retained") {
                val id = container.repo.createManualBook("daily-scroll", "")
                container.repo.saveChapterText(container.repo.chapters(id).single(), "scroll paragraph. ".repeat(400))
                val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                lateinit var session: ReaderSession
                runner.runOnMainSync {
                    session = ReaderSession(container, id, pageScope)
                    session.updatePrefs({ it.copy(pageMode = PageMode.SCROLL) }, persist = false)
                    session.open(0, 0)
                }
                try {
                    withTimeout(10000) { while (!withContext(Dispatchers.Main) { session.chapterText.isNotEmpty() }) delay(20) }
                    delay(250)
                    container.repo.db.writableDatabase.execSQL("CREATE TABLE daily_progress_writes (n INTEGER)")
                    container.repo.db.writableDatabase.execSQL("CREATE TRIGGER daily_count AFTER UPDATE OF last_offset ON books WHEN NEW.id=$id BEGIN INSERT INTO daily_progress_writes VALUES(1); END")
                    repeat(60) { i ->
                        runner.runOnMainSync { session.consumeJumpRequest(); session.updateScrollOffset((i + 1) * 10); session.saveProgress() }
                        delay(8)
                    }
                    withTimeout(4000) { while (container.repo.book(id)!!.lastCharOffset != 600) delay(30) }
                    delay(200)
                    val writes = container.repo.db.readableDatabase.rawQuery("SELECT COUNT(*) FROM daily_progress_writes", null).use { it.moveToFirst(); it.getInt(0) }
                    check(writes <= 16) { "60 scroll updates wrote $writes times" }
                    JSONObject().put("requests", 60).put("writes", writes).put("finalOffset", 600)
                } finally {
                    pageScope.cancel()
                    container.repo.db.writableDatabase.execSQL("DROP TRIGGER IF EXISTS daily_count")
                    container.repo.db.writableDatabase.execSQL("DROP TABLE IF EXISTS daily_progress_writes")
                }
            }
            test("opt-in local diagnostics are bounded exportable clearable and exclude private fields") {
                val store = container.diagnostics
                store.changeEnabled(false); store.clear()
                store.record(com.inkreader.data.diagnostics.UsageDiagnostics.Event.CHAPTER_READY, mapOf("durationMs" to 12))
                delay(100)
                check(store.report().getJSONArray("events").length() == 0)
                store.changeEnabled(true)
                repeat(250) { store.record(com.inkreader.data.diagnostics.UsageDiagnostics.Event.LAYOUT_CHANGED,
                    mapOf("durationMs" to it, "bookId" to 731, "token" to 123, "widthPx" to 1080)) }
                withTimeout(10000) { while (store.report().getJSONArray("events").length() < 200) delay(30) }
                val report = store.report()
                check(report.getJSONArray("events").length() == 200)
                check(!report.toString().contains("bookId") && !report.toString().contains("token"))
                val export = java.io.File(context.cacheDir, "usage-export.json")
                store.export(android.net.Uri.fromFile(export))
                check(JSONObject(export.readText()).getJSONArray("events").length() <= 200)
                store.clear(); delay(100)
                check(store.report().getJSONArray("events").length() == 0)
                store.changeEnabled(false)
                JSONObject().put("maxEvents", 200).put("defaultOff", true).put("privateFieldsExcluded", true)
            }
        } finally {
            container.diagnostics.changeEnabled(false)
            container.scope.cancel()
            container.repo.db.close()
        }
        val failed = (0 until rows.length()).count { rows.getJSONObject(it).getString("status") == "FAIL" }
        return JSONObject().put("passed", rows.length() - failed).put("failed", failed).put("checks", rows)
    }
}
