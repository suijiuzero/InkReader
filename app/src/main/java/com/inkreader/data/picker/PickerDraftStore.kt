package com.inkreader.data.picker

import android.content.Context
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonWriter
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Immutable local snapshots. Only a small token crosses Android's saved-state Binder limit. */
class PickerDraftStore(context: Context, session: String) {
    private val directory: File

    init {
        require(session.matches(Regex("[a-f0-9-]{36}")))
        directory = File(File(context.filesDir, "picker-drafts"), session)
    }

    fun save(values: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (value in values) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(), bytes.size.toByte()))
            digest.update(bytes)
        }
        val token = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val atomic = AtomicFile(File(directory, "$token.json"))
        if (atomic.baseFile.isFile) return token
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot save file selection")
        val output = atomic.startWrite()
        try {
            // Do not close the wrapped stream before AtomicFile can sync it.
            val writer = JsonWriter(output.writer(Charsets.UTF_8))
            writer.beginArray()
            values.forEach(writer::value)
            writer.endArray()
            writer.flush()
            atomic.finishWrite(output)
        } catch (t: Throwable) { atomic.failWrite(output); throw t }
        return token
    }

    fun restore(token: String): List<String> {
        if (!token.matches(Regex("[a-f0-9]{64}"))) return emptyList()
        return try {
            val values = ArrayList<String>()
            var characters = 0L
            JsonReader(AtomicFile(File(directory, "$token.json")).openRead().reader(Charsets.UTF_8)).use { reader ->
                reader.beginArray()
                while (reader.hasNext()) {
                    val value = reader.nextString()
                    characters += value.length
                    if (values.size >= 1_000_000 || characters > 32_000_000) throw IOException("Selection snapshot too large")
                    values += value
                }
                reader.endArray()
            }
            values
        } catch (_: Exception) { emptyList() }
    }

    /** Called only when this picker finishes; no source documents or grants are removed. */
    fun clear() { directory.deleteRecursively() }
}
