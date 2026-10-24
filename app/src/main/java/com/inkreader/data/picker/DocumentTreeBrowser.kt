package com.inkreader.data.picker

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import com.inkreader.parse.DocumentFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class PickerDocument(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val size: Long? = null
)

data class PickerFolder(val uri: Uri, val name: String)

/** Browses metadata only within an explicitly granted document tree. */
class DocumentTreeBrowser(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val prefs = appContext.getSharedPreferences("ordered_picker", Context.MODE_PRIVATE)
    private val knownParents = HashMap<String, MutableSet<String>>()

    fun root(tree: Uri): Uri {
        requireTree(tree)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        synchronized(knownParents) { knownParents.getOrPut(tree.toString()) { hashSetOf() }.add(root.toString()) }
        return root
    }

    suspend fun children(tree: Uri, parent: Uri): List<PickerDocument> = withContext(Dispatchers.IO) {
        val root = root(tree)
        require(parent.authority == tree.authority && DocumentsContract.getTreeDocumentId(parent) == DocumentsContract.getTreeDocumentId(tree))
        val known = synchronized(knownParents) {
            parent == root || knownParents[tree.toString()]?.contains(parent.toString()) == true
        }
        // A restored UI may revisit a previously open directory after this instance was recreated.
        // Ask the provider to verify ancestry; never infer it from document ID string prefixes.
        val allowed = known || (Build.VERSION.SDK_INT >= 29 &&
            runCatching { DocumentsContract.isChildDocument(resolver, root, parent) }.getOrDefault(false))
        require(allowed) { "Folder is outside the selected tree" }
        synchronized(knownParents) { knownParents.getValue(tree.toString()).add(parent.toString()) }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE)
        val documents = ArrayList<PickerDocument>()
        val cursor = resolver.query(childrenUri, projection, null, null, null)
            ?: throw IOException("Unable to read the selected folder")
        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (it.moveToNext()) {
                coroutineContext.ensureActive()
                val id = it.getString(idColumn) ?: continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                val name = if (nameColumn >= 0) it.getString(nameColumn).orEmpty() else ""
                val directory = mimeColumn >= 0 && it.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR
                if (!directory && !supportedName(name)) continue
                val size = if (sizeColumn >= 0 && !it.isNull(sizeColumn)) it.getLong(sizeColumn).takeIf { value -> value >= 0 } else null
                documents += PickerDocument(uri, name.ifBlank { id.substringAfterLast('/') }, directory, size)
            }
        }
        synchronized(knownParents) {
            val parents = knownParents.getOrPut(tree.toString()) { hashSetOf(root.toString()) }
            documents.filter { it.isDirectory }.forEach { parents.add(it.uri.toString()) }
        }
        documents.distinctBy { it.uri }.sortedWith(compareBy<PickerDocument> { !it.isDirectory }
            .thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.name }.thenBy { it.uri.toString() })
    }

    suspend fun displayName(uri: Uri): String = withContext(Dispatchers.IO) {
        val document = if (DocumentsContract.isTreeUri(uri) && runCatching { DocumentsContract.getDocumentId(uri) }.isFailure) root(uri) else uri
        resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) return@withContext it.getString(0)
        }
        document.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':').orEmpty()
    }

    /** Call only after the UI has taken the system's persistent read grant. */
    fun rememberTree(uri: Uri, name: String? = null) {
        requireTree(uri)
        if (!hasReadGrant(uri)) throw SecurityException("Persistent folder read permission is required")
        synchronized(prefs) {
            val folders = readFolders().toMutableList()
            val index = folders.indexOfFirst { it.uri == uri }
            val label = name?.takeIf { it.isNotBlank() } ?: folders.getOrNull(index)?.name ?: fallbackName(uri)
            if (index >= 0) folders[index] = PickerFolder(uri, label) else folders += PickerFolder(uri, label)
            writeFolders(folders, uri)
        }
    }

    /** This is the picker's virtual home, not the reader's book shelf. No file is deleted. */
    fun savedFolders(): List<PickerFolder> = synchronized(prefs) {
        val folders = readFolders()
        if (!prefs.contains("folders_v1")) writeFolders(folders, folders.firstOrNull()?.uri)
        folders
    }

    fun updateFolderName(uri: Uri, name: String) {
        if (name.isBlank()) return
        synchronized(prefs) {
            val folders = readFolders()
            if (folders.none { it.uri == uri } || folders.any { it.uri == uri && it.name == name }) return
            writeFolders(folders.map { if (it.uri == uri) it.copy(name = name) else it }, savedTree())
        }
    }

    /** Remove shortcuts only. Keep read grants so files already in the selection can still import. */
    fun removeFolders(uris: Set<Uri>) = synchronized(prefs) {
        val remaining = readFolders().filterNot { it.uri in uris }
        val last = prefs.getString("tree_uri", null)?.let(Uri::parse)?.takeIf { it !in uris }
        writeFolders(remaining, last ?: remaining.firstOrNull()?.uri)
    }

    fun hasReadPermission(uri: Uri): Boolean = hasReadGrant(uri)

    fun treeOf(document: Uri): Uri = DocumentsContract.buildTreeDocumentUri(
        requireNotNull(document.authority), DocumentsContract.getTreeDocumentId(document))

    fun savedTree(): Uri? {
        val value = prefs.getString("tree_uri", null) ?: return null
        val uri = runCatching { Uri.parse(value).also(::requireTree) }.getOrNull()
        if (uri != null && hasReadGrant(uri)) return uri
        prefs.edit().remove("tree_uri").apply()
        return null
    }

    private fun readFolders(): List<PickerFolder> {
        if (!prefs.contains("folders_v1")) {
            val legacy = prefs.getString("tree_uri", null) ?: return emptyList()
            return runCatching { Uri.parse(legacy).also(::requireTree).let { listOf(PickerFolder(it, fallbackName(it))) } }.getOrDefault(emptyList())
        }
        val array = runCatching { JSONArray(prefs.getString("folders_v1", "[]")) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                val uri = Uri.parse(item.getString("uri")).also(::requireTree)
                PickerFolder(uri, item.optString("name").ifBlank { fallbackName(uri) })
            }.getOrNull()
        }.distinctBy { it.uri }
    }

    private fun writeFolders(folders: List<PickerFolder>, last: Uri?) {
        val array = JSONArray()
        folders.forEach { array.put(JSONObject().put("uri", it.uri.toString()).put("name", it.name)) }
        val edit = prefs.edit().putString("folders_v1", array.toString())
        if (last == null) edit.remove("tree_uri") else edit.putString("tree_uri", last.toString())
        check(edit.commit()) { "Unable to save folder shortcuts" }
    }

    private fun fallbackName(uri: Uri): String = DocumentsContract.getTreeDocumentId(uri).substringAfterLast('/').substringAfterLast(':').ifBlank { "Folder" }

    private fun requireTree(uri: Uri) {
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank() && DocumentsContract.isTreeUri(uri))
        require(DocumentsContract.getTreeDocumentId(uri).isNotBlank())
    }

    private fun hasReadGrant(uri: Uri): Boolean = runCatching {
        resolver.persistedUriPermissions.any { it.isReadPermission && it.uri == uri }
    }.getOrDefault(false)

    companion object {
        private val supportedExtensions = DocumentFormat.entries.filter { it.isSupported }.flatMap { it.extensions }.toSet()

        internal fun supportedName(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase(Locale.ROOT) in supportedExtensions
    }
}
