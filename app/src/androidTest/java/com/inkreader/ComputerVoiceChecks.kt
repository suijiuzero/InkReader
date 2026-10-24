package com.inkreader

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.media.MediaPlayer
import android.os.Bundle
import com.inkreader.tts.ComputerVoiceClient
import com.inkreader.tts.ComputerVoiceModel
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
            val model = ComputerVoiceModel.fromStored(arguments.getString("voiceModel"))
            client.model = model
            check(ComputerVoiceClient(isolated).model == model)
            val voice = client.pair(code, arguments.getString("host").orEmpty()) // Broadcast by default; emulator NAT can use its host gateway.
            check(client.connect()?.id == voice.id)
            val japanese = arguments.getString("voiceLanguage") == "ja-JP"
            val sample = if (japanese) "こんにちは。墨阅につながりました。日本語の本を読みましょう。" else "你好，这是墨阅的局域网语音测试。晓晓已经连接成功。"
            val audio = client.synthesize(voice, sample, arguments.getString("voiceRate")?.toFloat() ?: 1.2f, 1f)
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
            val manual = !arguments.getString("host").isNullOrBlank()
            if (!manual) {
                // Only real LAN discovery can verify changed-IP recovery; ADB reverse covers all loopbacks.
                isolated.getSharedPreferences(testPrefs, 0).edit().putString("host", "127.0.0.2").commit()
                check(client.connect()?.id == voice.id)
            }
            check(runCatching { client.pair("0000000000000000", voice.host) }.isFailure)
            if (arguments.getString("configurePhone") == "true") {
                ComputerVoiceClient(target).pair(code, voice.host)
                // Instrumentation may exit before apply() reaches disk; flush before killing the test process.
                check(target.getSharedPreferences("computer_voice", 0).edit().putString("host", voice.host).commit())
            }
            client.forget()
            check(client.model == model && !client.paired)
            output.putString("stream", "PASS: model=${model.wireName}, authenticated pairing, local synthesis, Android audio completion, disabled fallback, wrong-key rejection, model retained on unpair" +
                (if (manual) "; explicit emulator endpoint (no discovery assertion)" else "; LAN discovery and changed-IP recovery") +
                if (arguments.getString("configurePhone") == "true") "; phone paired" else "")
            runner.finish(Activity.RESULT_OK, output)
        } catch (error: Throwable) {
            output.putString("stream", "FAIL: ${error.stackTraceToString()}")
            runner.finish(Activity.RESULT_CANCELED, output)
        } finally {
            runner.runOnMainSync { player?.release() }
            file.delete()
            isolated.getSharedPreferences(testPrefs, 0).edit().clear().commit()
        }
    }
}
