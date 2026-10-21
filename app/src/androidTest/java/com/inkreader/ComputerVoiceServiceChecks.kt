package com.inkreader

import android.app.Activity
import android.app.Instrumentation
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import com.inkreader.tts.SleepMode
import com.inkreader.tts.TtsBus
import com.inkreader.tts.TtsService
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Runs the real playback service against a disposable repository, never the user's library. */
object ComputerVoiceServiceChecks {
    fun run(runner: Instrumentation) {
        val target = runner.targetContext
        runner.runOnMainSync { }
        val app = target.applicationContext as InkApp
        val original = app.container
        val field = InkApp::class.java.getDeclaredField("container").apply { isAccessible = true }
        val root = File(target.cacheDir, "voice-service-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("audit_service_$name", mode)
            override fun getDatabasePath(name: String) = File(root, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?) =
                SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        }
        val container = AppContainer(isolated)
        var activity: Activity? = null
        val output = Bundle()
        try {
            runner.runOnMainSync { field.set(app, container) }
            val bookId = runBlocking {
                val id = container.repo.createManualBook("语音服务隔离测试", "")
                val first = container.repo.chapters(id).single()
                val sentence = "这是独立的测试内容，用于验证晓晓语音的暂停、继续、跳句以及电脑断线后的自动回退。"
                container.repo.saveChapterText(first, (1..20).joinToString("\n") { "$it$sentence" })
                val secondId = container.repo.addEmptyChapter(id, "第二章")
                val second = container.repo.chapters(id).first { it.id == secondId }
                container.repo.saveChapterText(second, (1..20).joinToString("\n") { "$it$sentence" })
                id
            }
            val activityRef = AtomicReference<Activity?>(null)
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(value: Activity, state: Bundle?) { if (value is MainActivity) activityRef.set(value) }
                override fun onActivityStarted(value: Activity) = Unit
                override fun onActivityResumed(value: Activity) = Unit
                override fun onActivityPaused(value: Activity) = Unit
                override fun onActivityStopped(value: Activity) = Unit
                override fun onActivitySaveInstanceState(value: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(value: Activity) = Unit
            }
            runner.runOnMainSync { app.registerActivityLifecycleCallbacks(callbacks) }
            try {
                ParcelFileDescriptor.AutoCloseInputStream(runner.uiAutomation.executeShellCommand(
                    "am start --user 0 -f 0x10008000 -n ${target.packageName}/.MainActivity"
                )).use { it.readBytes() }
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (activityRef.get() == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(40)
                activity = activityRef.get() ?: error("Test activity launch blocked")
            } finally { runner.runOnMainSync { app.unregisterActivityLifecycleCallbacks(callbacks) } }
            fun command(action: String) = runner.runOnMainSync { TtsService.send(target, action) }
            fun start(chapter: Int) = runner.runOnMainSync { TtsService.startReading(target, bookId, chapter, 0, SleepMode.OFF) }
            fun await(message: String, timeout: Long = 25000, condition: () -> Boolean) {
                val end = SystemClock.elapsedRealtime() + timeout
                while (!condition()) {
                    check(SystemClock.elapsedRealtime() < end) { message }
                    Thread.sleep(80)
                }
            }
            start(0)
            await("Computer playback did not start") { TtsBus.state.value.let { it.active && it.computerVoice && it.playing && !it.buffering } }
            command(TtsService.ACTION_PAUSE)
            await("Pause failed") { !TtsBus.state.value.playing }
            val paused = TtsBus.state.value.sentenceStart
            Thread.sleep(1200)
            check(TtsBus.state.value.sentenceStart == paused)
            command(TtsService.ACTION_NEXT)
            command(TtsService.ACTION_RESUME)
            await("Skip or resume failed") { TtsBus.state.value.let { it.computerVoice && it.playing && !it.buffering && it.sentenceStart > paused } }
            command(TtsService.ACTION_PREV)
            await("Previous sentence failed") { TtsBus.state.value.let { it.computerVoice && !it.buffering && it.sentenceStart == paused } }
            start(1)
            await("Chapter switch failed") { TtsBus.state.value.let { it.chapterIndex == 1 && it.computerVoice && !it.buffering } }
            runner.sendStatus(0, Bundle().apply { putString("stream", "CONTROLS_PASS; WAIT_FOR_BRIDGE_OFF") })
            await("Did not fall back when computer disconnected", 60000) { TtsBus.state.value.let { it.active && !it.computerVoice && it.playing } }
            runner.sendStatus(0, Bundle().apply { putString("stream", "FALLBACK_PASS; WAIT_FOR_BRIDGE_ON") })
            // The test driver restarts the PC bridge before this new listening session.
            Thread.sleep(6500)
            start(0)
            await("Computer reconnect failed", 30000) { TtsBus.state.value.let { it.chapterIndex == 0 && it.computerVoice && !it.buffering } }
            command(TtsService.ACTION_STOP)
            await("Stop failed") { !TtsBus.state.value.active }
            output.putString("stream", "PASS: real service playback, pause stability, next/resume, previous, chapter switch, disconnect fallback, reconnect, stop; disposable library only")
            runner.finish(Activity.RESULT_OK, output)
        } catch (error: Throwable) {
            output.putString("stream", "FAIL: ${error.javaClass.simpleName}: ${error.message}")
            runner.finish(Activity.RESULT_CANCELED, output)
        } finally {
            runner.runOnMainSync {
                target.stopService(Intent(target, TtsService::class.java))
                activity?.finish()
            }
            runner.runOnMainSync { field.set(app, original) }
            container.scope.cancel()
            container.repo.db.close()
            root.deleteRecursively()
        }
    }
}
