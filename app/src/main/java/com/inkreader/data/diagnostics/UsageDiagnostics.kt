package com.inkreader.data.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in, bounded local metrics. No text, titles, IDs, URIs, queries or credentials. */
class UsageDiagnostics(private val context: Context, private val scope: CoroutineScope) {
    enum class Event { CHAPTER_READY, PAGINATION_READY, LAYOUT_CHANGED, FRAME_SAMPLE, SAVE_FAILURE }
    private val prefs = context.getSharedPreferences("ink_diagnostics", Context.MODE_PRIVATE)
    var enabled by mutableStateOf(prefs.getBoolean("enabled", false))
        private set
    private val generation = AtomicLong()
    private val gate = Mutex()
    private val storage = AtomicFile(File(context.cacheDir, "usage-metrics.json"))
    private val allowed = setOf("durationMs", "characters", "blocks", "pages", "widthPx", "heightPx",
        "fontSp", "density", "fontScale", "frames", "over50ms", "maxMs", "meanMs")

    fun changeEnabled(value: Boolean) {
        enabled = value
        generation.incrementAndGet()
        prefs.edit().putBoolean("enabled", value).apply()
    }

    fun record(event: Event, values: Map<String, Number> = emptyMap()) {
        if (!enabled) return
        val revision = generation.get()
        val timestamp = System.currentTimeMillis()
        val safe = values.filter { (key, value) -> key in allowed && value.toDouble().isFinite() && value.toDouble() >= 0 }
            .mapValues { (_, value) -> value.toDouble() }
        scope.launch(Dispatchers.IO) {
            gate.withLock {
                if (!enabled || generation.get() != revision) return@withLock
                val entries = read()
                entries.put(JSONObject().put("time", timestamp).put("event", event.name).put("metrics", JSONObject(safe)))
                val bounded = JSONArray()
                for (index in maxOf(0, entries.length() - 200) until entries.length()) bounded.put(entries.get(index))
                runCatching { write(bounded) }
            }
        }
    }

    private fun read(): JSONArray = runCatching {
        storage.openRead().use { input ->
            val data = input.readBytesLimited(128 * 1024)
            val entries = JSONArray(String(data, Charsets.UTF_8))
            JSONArray().also { bounded ->
                for (index in maxOf(0, entries.length() - 200) until entries.length()) bounded.put(entries.get(index))
            }
        }
    }.getOrElse { JSONArray() }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = read(buffer); if (n < 0) break
            require(output.size() + n <= limit)
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }

    private fun write(entries: JSONArray) {
        val stream = storage.startWrite()
        try { stream.write(entries.toString().toByteArray()); storage.finishWrite(stream) }
        catch (error: Throwable) { storage.failWrite(stream); throw error }
    }

    suspend fun report(): JSONObject = withContext(Dispatchers.IO) { gate.withLock {
        @Suppress("DEPRECATION")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        JSONObject().put("schema", 1).put("version", version)
            .put("androidApi", Build.VERSION.SDK_INT).put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("events", read())
    } }

    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val bytes = report().toString(2).toByteArray()
        (context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open diagnostic output")).use { it.write(bytes) }
    }

    suspend fun clear() {
        generation.incrementAndGet()
        withContext(Dispatchers.IO) { gate.withLock { storage.delete() } }
    }
}
