package com.inkreader

import android.content.Context
import android.net.Uri
import com.inkreader.data.model.*
import com.inkreader.data.repo.BookRepository
import com.inkreader.data.prefs.PrefsStore
import com.inkreader.data.picker.DocumentTreeBrowser
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Actual parsing, preview and SQLite order checks in the runner's disposable storage. */
object ImportOrderChecks {
    suspend fun run(context: Context, repo: BookRepository): List<String> {
        val passed = ArrayList<String>()
        val folderPrefs = context.getSharedPreferences("ordered_picker", Context.MODE_PRIVATE)
        val trees = listOf("first", "second", "third").map { DocumentsContract.buildTreeDocumentUri("com.example.docs", it) }
        folderPrefs.edit().clear().putString("tree_uri", trees[0].toString()).commit()
        val folders = DocumentTreeBrowser(context)
        check(folders.savedFolders().map { it.uri } == listOf(trees[0]))
        check(DocumentTreeBrowser(context).savedFolders().single().uri == trees[0])
        passed += "legacy picker folder migrates to the virtual folder home without losing its shortcut"
        folders.removeFolders(setOf(trees[0]))
        check(DocumentTreeBrowser(context).savedFolders().isEmpty() && !folderPrefs.contains("tree_uri"))
        passed += "removing the last shortcut survives reopening and cannot resurrect via legacy preferences"
        val stored = JSONArray()
        for ((index, uri) in trees.withIndex()) stored.put(JSONObject().put("uri", uri.toString()).put("name", "Folder $index"))
        stored.put(JSONObject().put("uri", trees[0].toString()).put("name", "Duplicate"))
        stored.put(JSONObject().put("uri", "https://example.org/invalid").put("name", "Invalid"))
        folderPrefs.edit().putString("folders_v1", stored.toString()).commit()
        check(folders.savedFolders().map { it.uri } == trees)
        folders.removeFolders(setOf(trees[0], trees[2]))
        check(DocumentTreeBrowser(context).savedFolders().map { it.uri } == listOf(trees[1]))
        folders.updateFolderName(trees[0], "Late metadata result")
        check(folders.savedFolders().map { it.uri } == listOf(trees[1]))
        passed += "bulk removal keeps untouched shortcuts and delayed name updates do not re-add removed folders"
        check(runCatching { folders.rememberTree(trees[2]) }.isFailure)
        check(folders.savedFolders().map { it.uri } == listOf(trees[1]))
        folderPrefs.edit().clear().commit()
        passed += "adding a folder without persistent read permission is rejected"
        val prefs = PrefsStore(context)
        val queued = listOf("content://example/doc/zeta", "content://example/doc/alpha")
        prefs.savePendingImport(queued, ImportOrder.SELECTION)
        val reopened = PrefsStore(context)
        check(reopened.pendingImportUris == queued && reopened.pendingImportOrder == ImportOrder.SELECTION)
        reopened.pendingImportUris = queued.reversed()
        check(prefs.pendingImportOrder == ImportOrder.AUTO)
        prefs.savePendingImport(queued, ImportOrder.SELECTION)
        prefs.pendingImportUris = emptyList()
        check(prefs.pendingImportOrder == ImportOrder.AUTO && prefs.pendingImportUris.isEmpty())
        passed += "ordered-picker queue restores URI order and policy together; legacy and cleared queues reset policy"
        prefs.orderedPickerTarget = MainActivity.REQ_PICK_APPEND
        prefs.orderedPickerBookId = 42L
        check(PrefsStore(context).orderedPickerTarget == MainActivity.REQ_PICK_APPEND &&
            PrefsStore(context).orderedPickerBookId == 42L)
        prefs.orderedPickerTarget = 0; prefs.orderedPickerBookId = 0L
        passed += "folder authorization route can restore the append destination after process recreation"
        prefs.pendingAppendBookId = 73L
        check(PrefsStore(context).pendingAppendBookId == 73L)
        prefs.pendingAppendBookId = 0L
        passed += "system-picker append destination survives clearing the app-owned picker route"
        val body = "这是一篇没有章节标题的独立散文，保留人物对白与全部正文。".repeat(25)
        fun file(name: String, text: String = body) = File(context.cacheDir, name).apply { writeText(text) }
        fun uris(files: List<File>) = files.map(Uri::fromFile)
        suspend fun merge(files: List<File>, order: ImportOrder, split: SplitMode = SplitMode.SMART): Long {
            val result = repo.importDocuments(uris(files), ImportMode.MERGE, "order-check",
                splitMode = split, fileOrder = order) { _, _ -> }
            check(result.failed.isEmpty()) { result.failed.toString() }
            return result.bookIds.single()
        }
        suspend fun names(book: Long) = repo.chapters(book).map { it.sourceName }
        val loose = listOf(file("zeta.txt"), file("alpha.txt"), file("middle.txt"))
        val selected = merge(loose, ImportOrder.SELECTION)
        check(names(selected) == loose.map { it.name })
        check(repo.chapters(selected).all { repo.chapterText(it).contains(body) })
        passed += "unnumbered files without headings retain selection order and complete text"

        val preview = repo.previewImport(uris(loose), ImportMode.MERGE, SplitOptions(), ImportOrder.SELECTION)
        check(preview.estimatedChapters == 3)
        check(preview.sampleTitles == repo.chapters(selected).map { it.title })
        passed += "selection preview exactly matches final merged chapter order"

        val perFile = merge(loose, ImportOrder.SELECTION, SplitMode.PER_FILE)
        check(names(perFile) == loose.map { it.name })
        check(repo.chapters(perFile).map { it.title } == listOf("zeta", "alpha", "middle"))
        passed += "one-file-per-chapter preserves order and uses file names as titles"

        val automatic = merge(loose, ImportOrder.AUTO)
        check(names(automatic) == listOf("alpha.txt", "middle.txt", "zeta.txt"))
        passed += "existing automatic filename sorting still works"

        val numbered = listOf(file("第3章 归来.txt"), file("第1章 初见.txt"), file("第2章 重逢.txt"))
        check(names(merge(numbered, ImportOrder.SELECTION)) == numbered.map { it.name })
        check(names(merge(numbered, ImportOrder.AUTO)) == listOf(numbered[1].name, numbered[2].name, numbered[0].name))
        passed += "explicit file numbers never override selection; automatic mode still sorts them"

        val inText = listOf(file("gamma-piece.txt", "第三章 归来\n\n$body"),
            file("beta-piece.txt", "第一章 初见\n\n$body"), file("delta-piece.txt", "第二章 重逢\n\n$body"))
        check(names(merge(inText, ImportOrder.SELECTION)) == inText.map { it.name })
        check(names(merge(inText, ImportOrder.AUTO)) == listOf(inText[1].name, inText[2].name, inText[0].name))
        passed += "post-import chapter-number sorting cannot override selected file order"

        val appendBook = repo.createManualBook("append-selected", "")
        val original = repo.chapters(appendBook).single()
        repo.renameChapter(original.id, "第三章 原章节")
        repo.saveChapterText(original, body)
        val additions = listOf(numbered[2], numbered[1])
        check(repo.appendFilesAsChapters(appendBook, uris(additions), fileOrder = ImportOrder.SELECTION).isEmpty())
        check(repo.chapters(appendBook).first().id == original.id)
        check(repo.chapters(appendBook).drop(1).map { it.sourceName } == additions.map { it.name })
        check(repo.chapterText(original) == body)
        passed += "append selection goes to the end; existing chapter and text stay intact"

        val autoAppend = repo.createManualBook("append-auto", "")
        repo.renameChapter(repo.chapters(autoAppend).single().id, "第三章 原章节")
        check(repo.appendFilesAsChapters(autoAppend, uris(additions)).isEmpty())
        check(repo.chapters(autoAppend).take(2).map { it.sourceName } == additions.reversed().map { it.name })
        passed += "default append still inserts by chapter number"

        val empty = file("empty-piece.txt", "")
        val result = repo.importDocuments(uris(listOf(loose[0], empty, loose[1])), ImportMode.MERGE, "partial-order",
            fileOrder = ImportOrder.SELECTION) { _, _ -> }
        check(result.failed.size == 1)
        val survivors = repo.chapters(result.bookIds.single())
        check(survivors.map { it.sourceName } == listOf("zeta.txt", "alpha.txt"))
        check(survivors.map { it.indexInBook } == listOf(0, 1))
        passed += "failed middle file is skipped without scrambling successful files or indices"

        val separate = repo.importDocuments(uris(loose), ImportMode.SEPARATE, null,
            fileOrder = ImportOrder.SELECTION) { _, _ -> }
        check(separate.files.map { it.name } == loose.map { it.name })
        passed += "separate-book batch processes and reports files in selection order"
        return passed
    }
}
