package com.inkreader

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.media.MediaPlayer
import android.os.Bundle
import com.inkreader.tts.ComputerVoiceClient
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Fixed synthetic sentences only; no access to the user's books or reading progress. */
object ComputerVoiceChecks {
    fun run(runner: Instrumentation, arguments: Bundle) {
        val target = runner.targetContext
        val testPrefs = "audit_computer_voice"
        val isolated = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(testPrefs, mode)
        }
        val output = Bundle()
        val file = File(target.cacheDir, "voice-bridge-test.wav")
        var player: MediaPlayer? = null
        try {
            val code = arguments.getString("pairCode") ?: error("Missing pairing code")
            val client = ComputerVoiceClient(isolated)
            client.forget()
            val voice = client.pair(code) // Actual broadcast discovery, no manual host.
            check(client.connect()?.id == voice.id)
            val audio = client.synthesize(voice, "你好，这是墨阅的局域网语音测试。晓晓已经连接成功。", 1.2f, 1f)
            check(audio.size > 1000)
            file.writeBytes(audio)
            val completed = CountDownLatch(1)
            val failure = AtomicReference<String?>(null)
            runner.runOnMainSync {
                player = MediaPlayer().apply {
                    setDataSource(file.path)
                    setVolume(0f, 0f)
                    setOnPreparedListener { check(it.duration > 500); it.start() }
                    setOnCompletionListener { completed.countDown() }
                    setOnErrorListener { _, what, extra -> failure.set("MediaPlayer $what/$extra"); completed.countDown(); true }
                    prepareAsync()
                }
            }
            check(completed.await(30, TimeUnit.SECONDS)) { "Audio playback timed out" }
            check(failure.get() == null) { failure.get().orEmpty() }
            client.enabled = false
            check(client.connect() == null)
            client.enabled = true
            // An obsolete numeric endpoint must recover through discovery of the same computer identity.
            isolated.getSharedPreferences(testPrefs, 0).edit().putString("host", "127.0.0.2").commit()
            check(client.connect()?.id == voice.id)
            check(runCatching { client.pair("0000000000000000", voice.host) }.isFailure)
            if (arguments.getString("configurePhone") == "true") {
                ComputerVoiceClient(target).pair(code, voice.host)
            }
            output.putString("stream", "PASS: LAN discovery, authenticated pairing, local Xiaoxiao synthesis, Android audio completion, disabled fallback, changed-IP recovery, wrong-key rejection" +
                if (arguments.getString("configurePhone") == "true") "; phone paired" else "")
            runner.finish(Activity.RESULT_OK, output)
        } catch (error: Throwable) {
            output.putString("stream", "FAIL: ${error.javaClass.simpleName}: ${error.message}")
            runner.finish(Activity.RESULT_CANCELED, output)
        } finally {
            runner.runOnMainSync { player?.release() }
            file.delete()
            isolated.getSharedPreferences(testPrefs, 0).edit().clear().commit()
        }
    }
}
