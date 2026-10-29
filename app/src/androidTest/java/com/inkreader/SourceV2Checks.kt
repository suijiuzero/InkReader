package com.inkreader

import android.app.Instrumentation
import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.inkreader.data.model.*
import com.inkreader.data.repo.BookRepository
import com.inkreader.parse.*
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

object SourceV2Checks {
    suspend fun run(runner: Instrumentation, context: Context, repo: BookRepository, root: String = "real-source-v2", unnumbered: Boolean = false): JSONObject {
        val assets = runner.context.assets
        val hashes = JSONObject(assets.open("$root/frozen-sha256.json").bufferedReader().use { it.readText() })
        val rows = assets.open("$root/manifest.tsv").bufferedReader().use { it.readLines() }.drop(1).filter { it.isNotBlank() }
        val output = JSONArray(); val cohorts = JSONObject()
        var failed = 0; var passed = 0; var capabilities = 0
        for (row in rows) {
            val values = row.split('\t'); val id = values[0]; val contract = values[5]
            val report = JSONObject().put("case", id).put("partition", values[1]).put("language", values[2]).put("contract", contract)
            var imported = emptyList<Long>()
            try {
                fun bytes(name: String): ByteArray {
                    val data = assets.open("$root/$name").use { it.readBytes() }
                    val digest = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
                    check(digest == hashes.getString(name)) { "Bundled frozen file changed: $name" }
                    return data
                }
                val source = bytes("$id.txt")
                val gold = SourceGold.read(String(bytes("$id.gold"), Charsets.UTF_8), String(source, Charsets.UTF_8))
                val options = SplitOptions(cleanWatermark = false, detectUnnumberedTitles = unnumbered)
                val expected = ChapterSplitter.analyze(ChapterSplitter.normalize(String(source, Charsets.UTF_8)), options = options)
                val score = SourceGold.score(expected, gold, root.endsWith("-v3"))
                val file = File(context.cacheDir, "$id.txt").apply { writeBytes(source) }
                val preview = repo.previewImport(listOf(Uri.fromFile(file)), ImportMode.SEPARATE, options, ImportOrder.SELECTION)
                val result = repo.importDocuments(listOf(Uri.fromFile(file)), ImportMode.SEPARATE, null,
                    splitOptions = options, fileOrder = ImportOrder.SELECTION) { _, _ -> }
                imported = result.bookIds
                check(result.failed.isEmpty() && imported.size == 1) { "Import failed" }
                val chapters = repo.chapters(imported.single())
                check(preview.estimatedChapters == chapters.size && chapters.map { it.title } == expected.parts.map { it.title }) { "Preview/in-memory/disk import differs" }
                val body = chapters.joinToString("\n\n") { repo.files.read(it.fileName) }
                val actualBodyErrors = if (root.endsWith("-v3")) SourceGold.blockErrors(chapters.map { repo.files.read(it.fileName) }, gold)
                    else SourceGold.bodyErrors(body, gold)
                report.put("TP", score.tp).put("FP", score.fp).put("FN", score.fn).put("bodyErrors", actualBodyErrors)
                val key = "${values[1]}/${values[2]}/$contract"
                val aggregate = cohorts.optJSONObject(key) ?: JSONObject().put("cases", 0).put("TP", 0).put("FP", 0).put("FN", 0).put("bodyErrors", 0).also { cohorts.put(key, it) }
                for ((name, n) in listOf("cases" to 1, "TP" to score.tp, "FP" to score.fp, "FN" to score.fn, "bodyErrors" to actualBodyErrors)) aggregate.put(name, aggregate.getInt(name) + n)
                check(actualBodyErrors == 0 && score.bodyErrors == 0) { "Full source paragraphs lost, reordered or duplicated" }
                if (unnumbered && contract == "capability") {
                    repo.prepareResplitSource(imported.single(), options).use { prepared ->
                        val resplitPreview = repo.previewImport(listOf(prepared.uri), ImportMode.SEPARATE, prepared.options, ImportOrder.SELECTION)
                        check(resplitPreview.estimatedChapters == chapters.size) { "Resplit preview differs" }
                    }
                    check(repo.resplitBook(imported.single(), options) == chapters.size) { "Resplit count differs" }
                    val updated = repo.chapters(imported.single())
                    check(updated.map { it.title } == chapters.map { it.title }) { "Resplit titles differ" }
                    check(SourceGold.blockErrors(updated.map { repo.files.read(it.fileName) }, gold) == 0) { "Resplit changed body blocks" }
                    report.put("resplit", "PASS")
                }
                if (contract == "required" || unnumbered) { check(score.exact) { "Boundary recognition differs from frozen labels: $score" }; passed++; report.put("status", "PASS") }
                else { capabilities++; report.put("status", if (score.exact) "CAPABILITY_MATCH" else "CAPABILITY_LIMIT") }
            } catch (error: Throwable) { failed++; report.put("status", "FAIL").put("error", error.stackTraceToString()) }
            finally { imported.forEach { repo.deleteBook(it) } }
            output.put(report)
            runner.sendStatus(0, Bundle().apply { putString("stream", "$report\n") })
        }
        return JSONObject().put("passedRequired", passed).put("capabilityCases", capabilities).put("failed", failed)
            .put("cohorts", cohorts).put("checks", output)
    }
}
