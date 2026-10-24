package com.inkreader.ui.picker

import android.app.Activity
import android.content.Intent
import android.content.ComponentName
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkreader.R
import com.inkreader.data.picker.DocumentTreeBrowser
import com.inkreader.data.picker.PickerDocument
import com.inkreader.data.picker.PickerFolder
import com.inkreader.data.picker.PickerDraftStore
import com.inkreader.data.picker.SelectionBatch
import com.inkreader.data.picker.OrderedSelection
import com.inkreader.ui.component.*
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette
import kotlinx.coroutines.CancellationException
import java.util.Locale

private data class ChosenDocument(val document: PickerDocument, val location: String)

private val PathSaver = listSaver<List<PickerDocument>, String>(
    save = { path -> path.flatMap { listOf(it.uri.toString(), it.name) } },
    restore = { saved -> saved.chunked(2).mapNotNull { values ->
        if (values.size != 2) null else PickerDocument(Uri.parse(values[0]), values[1], true)
    } }
)
private fun chosenSaver(drafts: PickerDraftStore) = Saver<List<ChosenDocument>, Any>(
    save = { items -> runCatching { drafts.save(items.flatMap { listOf(it.document.uri.toString(), it.document.name,
        it.document.size?.toString().orEmpty(), it.location) }) }.getOrNull() },
    restore = { saved -> (when (saved) {
        is String -> drafts.restore(saved)
        is List<*> -> saved.filterIsInstance<String>() // Existing small drafts from 1.11.22.
        else -> emptyList()
    }).chunked(4).mapNotNull { values ->
        if (values.size != 4) null else ChosenDocument(
            PickerDocument(Uri.parse(values[0]), values[1], false, values[2].toLongOrNull()), values[3])
    } }
)

/** Owns only read access to the chosen tree. File selection is a draft until onConfirm. */
@Composable
fun OrderedFilePickerScreen(
    onBack: () -> Unit,
    onConfirm: (List<Uri>) -> Unit,
    onSystemPicker: () -> Unit
) {
    val context = LocalContext.current
    val p = LocalInkPalette.current
    val keyboard = LocalSoftwareKeyboardController.current
    val browser = remember(context) { DocumentTreeBrowser(context) }
    var folders by remember(browser) { mutableStateOf(browser.savedFolders()) }
    var treeText by rememberSaveable { mutableStateOf("") }
    var path by rememberSaveable(stateSaver = PathSaver) { mutableStateOf(emptyList<PickerDocument>()) }
    val draftSession by rememberSaveable { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    val drafts = remember(context, draftSession) { PickerDraftStore(context, draftSession) }
    val selectionSaver = remember(drafts) { chosenSaver(drafts) }
    var chosen by rememberSaveable(stateSaver = selectionSaver) { mutableStateOf(emptyList<ChosenDocument>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var descending by rememberSaveable { mutableStateOf(false) }
    var showChosen by rememberSaveable { mutableStateOf(false) }
    var managingFolders by rememberSaveable { mutableStateOf(false) }
    var selectedFolders by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var removingFolders by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var cancelRemovedFiles by rememberSaveable { mutableStateOf(false) }
    var batchFiles by rememberSaveable(stateSaver = selectionSaver) { mutableStateOf(emptyList<ChosenDocument>()) }
    var batchAnchor by rememberSaveable { mutableStateOf("") }
    var choosingRangeEnd by rememberSaveable { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf(emptyList<PickerDocument>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var permissionLost by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    // A restored deep path is walked once, so providers need not implement isChildDocument.
    val validatedTrees = remember(browser) { mutableSetOf<String>() }
    fun toggleDocument(document: PickerDocument) {
        val key = document.uri.toString()
        val order = OrderedSelection(chosen.map { it.document.uri.toString() })
        order.toggle(key)
        val previous = chosen.associateBy { it.document.uri.toString() }
        val newItem = ChosenDocument(document, path.joinToString(" / ") { it.name })
        chosen = order.items.map { previous[it] ?: newItem }
    }
    fun moveChosen(from: Int, to: Int) {
        val order = OrderedSelection(chosen.map { it.document.uri.toString() })
        if (order.move(from, to)) {
            val previous = chosen.associateBy { it.document.uri.toString() }
            chosen = order.items.mapNotNull { previous[it] }
        }
    }

    fun closeBatch() { batchFiles = emptyList(); batchAnchor = ""; choosingRangeEnd = false }
    fun appendBatch(ids: List<String>) {
        val order = OrderedSelection(chosen.map { it.document.uri.toString() })
        val added = order.append(ids)
        val documents = (batchFiles + chosen).associateBy { it.document.uri.toString() }
        chosen = order.items.mapNotNull { documents[it] }
        closeBatch()
        android.widget.Toast.makeText(context, context.resources.getQuantityString(R.plurals.picker_batch_added, added, added), android.widget.Toast.LENGTH_SHORT).show()
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val tree = data?.data
        if (result.resultCode == Activity.RESULT_OK && data != null && tree != null) {
            try {
                val readFlags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                check(readFlags != 0) { "Read permission missing" }
                context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val root = browser.root(tree)
                browser.rememberTree(tree)
                treeText = tree.toString()
                path = listOf(PickerDocument(root, "", true))
                folders = browser.savedFolders()
                managingFolders = false
                selectedFolders = emptyList()
                showChosen = false
                query = ""
                error = null
                permissionLost = false
                refresh++
            } catch (_: Exception) {
                error = context.getString(R.string.ordered_picker_grant_failed)
            }
        }
    }
    fun openFolderPicker(initial: Uri? = treeText.takeIf { it.isNotBlank() }?.let(Uri::parse)) {
        keyboard?.hide()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            if (initial != null && Build.VERSION.SDK_INT >= 26) putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
        }
        val aosp = ComponentName(AOSP_DOCUMENTS_PKG, AOSP_DOCUMENTS_PICKER)
        if (runCatching { context.packageManager.getActivityInfo(aosp, 0) }.isSuccess) intent.component = aosp
        try { folderPicker.launch(intent) }
        catch (_: Exception) {
            try { folderPicker.launch(Intent(intent).setComponent(null)) }
            catch (_: Exception) { error = context.getString(R.string.ordered_picker_grant_failed) }
        }
    }
    fun requestFolder() {
        openFolderPicker()
    }
    fun folderHome() {
        keyboard?.hide()
        treeText = ""; path = emptyList(); query = ""; entries = emptyList()
        loading = false; error = null; permissionLost = false
    }
    fun toggleFolder(uri: String) {
        selectedFolders = if (uri in selectedFolders) selectedFolders - uri else selectedFolders + uri
    }
    fun openFolder(folder: PickerFolder) {
        if (!browser.hasReadPermission(folder.uri)) { openFolderPicker(folder.uri); return }
        treeText = folder.uri.toString(); path = listOf(PickerDocument(browser.root(folder.uri), folder.name, true))
        query = ""; error = null; permissionLost = false; managingFolders = false; selectedFolders = emptyList()
    }
    fun goBack() {
        keyboard?.hide()
        when {
            managingFolders -> { managingFolders = false; selectedFolders = emptyList() }
            showChosen -> showChosen = false
            path.size > 1 -> { path = path.dropLast(1); query = "" }
            treeText.isNotBlank() -> folderHome()
            else -> { drafts.clear(); onBack() }
        }
    }
    BackHandler { goBack() }

    val currentUri = path.lastOrNull()?.uri?.toString().orEmpty()
    LaunchedEffect(treeText, currentUri, refresh) {
        if (treeText.isBlank()) return@LaunchedEffect
        loading = true
        error = null
        entries = emptyList()
        try {
            val tree = Uri.parse(treeText)
            val root = browser.root(tree)
            val savedPath = path
            if (treeText !in validatedTrees) {
                var parent = root
                for (step in savedPath.drop(1)) {
                    val child = browser.children(tree, parent).firstOrNull { it.isDirectory && it.uri == step.uri }
                        ?: throw IllegalArgumentException("Saved directory no longer belongs to this tree")
                    parent = child.uri
                }
                validatedTrees += treeText
            }
            val directory = savedPath.lastOrNull()?.uri ?: root
            if (savedPath.isEmpty() || savedPath.first().name.isBlank()) {
                val name = runCatching { browser.displayName(root) }.getOrDefault("")
                    .ifBlank { context.getString(R.string.ordered_picker_folder) }
                path = if (savedPath.isEmpty()) listOf(PickerDocument(root, name, true))
                    else savedPath.toMutableList().apply { this[0] = first().copy(name = name) }
                browser.updateFolderName(tree, name)
                folders = browser.savedFolders()
            }
            entries = browser.children(tree, directory)
            permissionLost = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (failure is SecurityException) permissionLost = true
            error = context.getString(if (failure is SecurityException)
                R.string.ordered_picker_permission_lost else R.string.ordered_picker_load_failed)
        } finally { loading = false }
    }

    if (removingFolders.isNotEmpty()) {
        val affected = chosen.count { runCatching { browser.treeOf(it.document.uri).toString() in removingFolders }.getOrDefault(false) }
        InkDialogShell(onDismiss = { removingFolders = emptyList() },
            title = stringResource(R.string.picker_home_remove_title, removingFolders.size), buttons = {
                Row {
                    InkTextButton(stringResource(R.string.action_cancel), color = p.textSecondary) { removingFolders = emptyList() }
                    InkTextButton(stringResource(R.string.picker_home_remove)) {
                        try {
                            browser.removeFolders(removingFolders.map(Uri::parse).toSet())
                            if (cancelRemovedFiles) chosen = chosen.filterNot {
                                runCatching { browser.treeOf(it.document.uri).toString() in removingFolders }.getOrDefault(false)
                            }
                            folders = browser.savedFolders(); removingFolders = emptyList()
                            selectedFolders = emptyList(); managingFolders = false; permissionLost = false
                        } catch (_: Exception) { error = context.getString(R.string.ordered_picker_load_failed); removingFolders = emptyList() }
                    }
                }
            }) {
            Text(stringResource(R.string.picker_home_remove_hint), style = InkType.Body, color = p.textSecondary)
            if (affected > 0) {
                Row(Modifier.fillMaxWidth().clickable { cancelRemovedFiles = !cancelRemovedFiles }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = cancelRemovedFiles, onCheckedChange = { cancelRemovedFiles = it })
                    Text(stringResource(R.string.picker_home_cancel_selected, affected), Modifier.weight(1f), style = InkType.Caption, color = p.textPrimary)
                }
            }
        }
    }

    val filtered = remember(entries, query, descending) {
        val matching = entries.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        if (!descending) matching else matching.filter { it.isDirectory }.reversed() + matching.filterNot { it.isDirectory }.reversed()
    }
    if (batchFiles.isNotEmpty()) {
        val batch = remember(batchFiles, batchAnchor) { SelectionBatch(batchFiles.map { it.document.uri.toString() }, batchAnchor) }
        val anchorName = batchFiles.firstOrNull { it.document.uri.toString() == batchAnchor }?.document?.name.orEmpty()
        // Each step starts at the top, including after scrolling the menu in landscape.
        key(choosingRangeEnd) {
            InkDialogShell(onDismiss = { closeBatch() },
                title = stringResource(if (choosingRangeEnd) R.string.picker_batch_end_title else R.string.picker_batch_title),
                buttons = {
                    Row {
                        if (choosingRangeEnd) InkTextButton(stringResource(R.string.picker_batch_back)) { choosingRangeEnd = false }
                        InkTextButton(stringResource(R.string.action_cancel), color = p.textSecondary) { closeBatch() }
                    }
                }) {
                Text(anchorName, style = InkType.Body, color = p.accent, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(if (choosingRangeEnd) R.string.picker_batch_end_hint else R.string.picker_batch_hint),
                    style = InkType.Caption, color = p.textSecondary)
                Spacer(Modifier.height(12.dp))
                if (choosingRangeEnd) {
                    LazyColumn(Modifier.fillMaxWidth().height(260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(batchFiles, key = { _, item -> item.document.uri.toString() }) { _, item ->
                            val ids = batch.through(item.document.uri.toString())
                            BatchAction(item.document.name, pluralStringResource(R.plurals.picker_batch_count, ids.size, ids.size)) { appendBatch(ids) }
                        }
                    }
                } else {
                    BatchAction(stringResource(R.string.picker_batch_forward), pluralStringResource(R.plurals.picker_batch_count, batch.fromAnchor().size, batch.fromAnchor().size)) { appendBatch(batch.fromAnchor()) }
                    BatchAction(stringResource(R.string.picker_batch_reverse), pluralStringResource(R.plurals.picker_batch_count, batch.fromAnchor(true).size, batch.fromAnchor(true).size)) { appendBatch(batch.fromAnchor(true)) }
                    BatchAction(stringResource(R.string.picker_batch_all), pluralStringResource(R.plurals.picker_batch_count, batch.all().size, batch.all().size)) { appendBatch(batch.all()) }
                    BatchAction(stringResource(R.string.picker_batch_all_reverse), pluralStringResource(R.plurals.picker_batch_count, batch.all(true).size, batch.all(true).size)) { appendBatch(batch.all(true)) }
                    BatchAction(stringResource(R.string.picker_batch_range), stringResource(R.string.picker_batch_range_hint)) { choosingRangeEnd = true }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(p.background).imePadding()) {
        InkTopBar(title = stringResource(R.string.ordered_picker_title),
            subtitle = stringResource(R.string.ordered_picker_subtitle), onBack = { goBack() }, actions = {
                InkTextButton(stringResource(R.string.ordered_picker_system)) { keyboard?.hide(); drafts.clear(); onSystemPicker() }
            })
        if (treeText.isBlank()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("*", style = InkType.Title, color = p.accent)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.picker_home_title), Modifier.weight(1f), style = InkType.Body, color = p.textPrimary)
                if (!managingFolders) InkTextButton(stringResource(R.string.picker_home_add)) { requestFolder() }
            }
            if (managingFolders) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.picker_home_selected, selectedFolders.size), Modifier.weight(1f), style = InkType.Caption, color = p.accent)
                        InkTextButton(stringResource(R.string.picker_home_select_all)) { selectedFolders = folders.map { it.uri.toString() } }
                        InkTextButton(stringResource(R.string.action_cancel)) { managingFolders = false; selectedFolders = emptyList() }
                    }
                    InkButton(stringResource(R.string.picker_home_remove), {
                        removingFolders = selectedFolders.filter { key -> folders.any { it.uri.toString() == key } }
                        cancelRemovedFiles = false
                    }, Modifier.fillMaxWidth(), filled = false, enabled = selectedFolders.isNotEmpty())
                }
            } else if (folders.isNotEmpty()) {
                Text(stringResource(R.string.picker_home_manage_hint), Modifier.padding(horizontal = 18.dp, vertical = 8.dp), style = InkType.Caption, color = p.textSecondary)
            }
            if (error != null) Text(error.orEmpty(), Modifier.padding(horizontal = 18.dp), style = InkType.Caption, color = p.accent)
        }
        if (treeText.isNotBlank()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                InkTextButton("*", onClick = { folderHome() })
                InkIconButton(R.drawable.ic_up, stringResource(R.string.ordered_picker_parent), {
                    if (path.size > 1) { path = path.dropLast(1); query = "" } else folderHome()
                }, size = 20)
                Text(path.joinToString(" / ") { it.name }.ifBlank { stringResource(R.string.ordered_picker_folder) },
                    Modifier.weight(1f), style = InkType.Caption, color = p.textSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                InkIconButton(R.drawable.ic_folder, stringResource(R.string.picker_home_add), { requestFolder() }, size = 20)
            }
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f),
                    textStyle = InkType.Caption, placeholder = { Text(stringResource(R.string.ordered_picker_search), style = InkType.Caption) },
                    leadingIcon = { InkIcon(R.drawable.ic_search, null, size = 18) },
                    trailingIcon = { if (query.isNotEmpty()) InkIconButton(R.drawable.ic_close,
                        stringResource(R.string.ordered_picker_clear_search), { query = "" }, size = 17) },
                    singleLine = true, shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }))
                InkIconButton(R.drawable.ic_sort, stringResource(if (descending) R.string.ordered_picker_sort_desc else R.string.ordered_picker_sort_asc),
                    { descending = !descending }, size = 20)
            }
            Text(stringResource(R.string.picker_batch_long_hint), Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp),
                style = InkType.Caption, color = p.textFaint)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                treeText.isBlank() -> if (folders.isEmpty()) PickerEmptyState(
                    title = stringResource(R.string.ordered_picker_welcome),
                    body = stringResource(R.string.ordered_picker_folder_hint),
                    action = stringResource(R.string.ordered_picker_choose_folder), onAction = { requestFolder() })
                else FolderHomeGrid(folders, managingFolders, selectedFolders, browser,
                    onOpen = { folder -> if (managingFolders) toggleFolder(folder.uri.toString()) else openFolder(folder) },
                    onLongPress = { folder -> showChosen = false; managingFolders = true; toggleFolder(folder.uri.toString()) })
                loading -> InkLoading()
                error != null -> PickerEmptyState(
                    title = error.orEmpty(), body = stringResource(R.string.ordered_picker_error_hint),
                    action = stringResource(R.string.ordered_picker_retry), onAction = { refresh++ },
                    secondary = stringResource(R.string.picker_home_title),
                    onSecondary = { folderHome() })
                filtered.isEmpty() -> PickerEmptyState(
                    title = stringResource(if (query.isBlank()) R.string.ordered_picker_empty else R.string.ordered_picker_no_matches),
                    body = stringResource(if (query.isBlank()) R.string.ordered_picker_supported else R.string.ordered_picker_search_hint),
                    action = stringResource(if (query.isBlank()) R.string.ordered_picker_refresh else R.string.ordered_picker_clear_search),
                    onAction = { if (query.isBlank()) refresh++ else query = "" })
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(filtered, key = { _, doc -> doc.uri.toString() }) { _, document ->
                        val selectedIndex = chosen.indexOfFirst { it.document.uri == document.uri }
                        PickerFileRow(document, selectedIndex, onClick = {
                            if (document.isDirectory) { path = path + document; query = "" }
                            else toggleDocument(document)
                        }, onLongClick = {
                            if (!document.isDirectory) {
                                keyboard?.hide()
                                val location = path.joinToString(" / ") { it.name }
                                batchFiles = filtered.filterNot { it.isDirectory }.map { ChosenDocument(it, location) }
                                batchAnchor = document.uri.toString()
                                choosingRangeEnd = false
                            }
                        })
                    }
                }
            }
        }
        // A separate footer owns its height; list rows never cover order controls or confirmation.
        Column(Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(p.surfaceHigh)
            .navigationBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(enabled = chosen.isNotEmpty()) { showChosen = !showChosen }) {
                    Text(stringResource(R.string.ordered_picker_order), style = InkType.Body, color = p.textPrimary)
                    Text(stringResource(R.string.ordered_picker_selected, chosen.size), style = InkType.Caption, color = p.accent)
                }
                if (chosen.isNotEmpty()) {
                    InkTextButton(stringResource(R.string.ordered_picker_clear_all)) { chosen = emptyList(); showChosen = false }
                    InkIconButton(if (showChosen) R.drawable.ic_down else R.drawable.ic_up,
                        stringResource(if (showChosen) R.string.ordered_picker_collapse else R.string.ordered_picker_expand),
                        { showChosen = !showChosen }, size = 20)
                }
            }
            if (showChosen && chosen.isNotEmpty()) {
                val previewHeight = minOf(220, LocalConfiguration.current.screenHeightDp / 3).dp
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = previewHeight), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(chosen, key = { _, item -> item.document.uri.toString() }) { index, item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}", Modifier.width(24.dp), style = InkType.Caption, color = p.accent)
                            Column(Modifier.weight(1f)) {
                                Text(item.document.name, style = InkType.Caption, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.location, style = InkType.Caption.copy(fontSize = 10.sp), color = p.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            InkIconButton(R.drawable.ic_up, stringResource(R.string.ordered_picker_move_up, index + 1), {
                                moveChosen(index, index - 1)
                            }, enabled = index > 0, size = 18)
                            InkIconButton(R.drawable.ic_down, stringResource(R.string.ordered_picker_move_down, index + 1), {
                                moveChosen(index, index + 1)
                            }, enabled = index < chosen.lastIndex, size = 18)
                            InkIconButton(R.drawable.ic_close, stringResource(R.string.ordered_picker_remove, index + 1), {
                                chosen = chosen.filterNot { it.document.uri == item.document.uri }
                            }, size = 18)
                        }
                    }
                }
            } else {
                Text(if (chosen.isEmpty()) stringResource(R.string.ordered_picker_no_selection)
                    else chosen.take(3).mapIndexed { index, item -> "${index + 1}. ${item.document.name}" }.joinToString("  →  ") +
                        if (chosen.size > 3) "  …" else "",
                    style = InkType.Caption, color = p.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            InkButton(stringResource(R.string.ordered_picker_continue), {
                if (chosen.isNotEmpty() && !confirming) {
                    if (chosen.any { item -> runCatching { !browser.hasReadPermission(browser.treeOf(item.document.uri)) }.getOrDefault(true) }) {
                        permissionLost = true
                        error = context.getString(R.string.ordered_picker_permission_lost)
                    } else {
                        confirming = true; keyboard?.hide(); drafts.clear(); onConfirm(chosen.map { it.document.uri })
                    }
                }
            }, Modifier.fillMaxWidth(), enabled = chosen.isNotEmpty() && !confirming && !permissionLost && !loading && !managingFolders,
                contentColor = if (p.isDark) p.background else null)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderHomeGrid(
    folders: List<PickerFolder>, managing: Boolean, selected: List<String>, browser: DocumentTreeBrowser,
    onOpen: (PickerFolder) -> Unit, onLongPress: (PickerFolder) -> Unit
) {
    val p = LocalInkPalette.current
    LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(folders, key = { it.uri.toString() }) { folder ->
            val checked = folder.uri.toString() in selected
            val readable = browser.hasReadPermission(folder.uri)
            val shape = RoundedCornerShape(18.dp)
            Column(Modifier.fillMaxWidth().defaultMinSize(minHeight = 132.dp).clip(shape)
                .background(if (checked) p.accentSoft else p.surface)
                .border(1.dp, if (checked) p.accent else p.divider, shape)
                .combinedClickable(onClick = { onOpen(folder) }, onLongClick = { onLongPress(folder) })
                .padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    InkIcon(R.drawable.ic_folder, null, tint = p.accent, size = 30)
                    if (managing) Box(Modifier.size(26.dp).clip(CircleShape)
                        .background(if (checked) p.accent else p.background).border(1.dp, if (checked) p.accent else p.divider, CircleShape),
                        contentAlignment = Alignment.Center) {
                        if (checked) InkIcon(R.drawable.ic_check, null, tint = if (p.isDark) p.background else androidx.compose.ui.graphics.Color.White, size = 17)
                    }
                }
                Text(folder.name, style = InkType.Body, color = p.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(runCatching { DocumentsContract.getTreeDocumentId(folder.uri).replace(':', '/') }.getOrDefault(folder.name),
                    style = InkType.Caption.copy(fontSize = 10.sp), color = p.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(if (managing) R.string.picker_home_toggle_hint else if (readable) R.string.picker_home_open_hint else R.string.picker_home_grant_hint),
                    style = InkType.Caption, color = if (readable) p.textSecondary else p.accent)
            }
        }
    }
}

@Composable
private fun PickerEmptyState(title: String, body: String, action: String, onAction: () -> Unit,
    secondary: String? = null, onSecondary: () -> Unit = {}) {
    val p = LocalInkPalette.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(p.accentSoft), contentAlignment = Alignment.Center) {
            InkIcon(R.drawable.ic_folder, null, tint = p.accent, size = 30)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = InkType.Body.copy(fontWeight = FontWeight.SemiBold), color = p.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(body, style = InkType.Caption, color = p.textSecondary)
        Spacer(Modifier.height(16.dp))
        InkButton(action, onAction, contentColor = if (p.isDark) p.background else null)
        if (secondary != null) InkTextButton(secondary, onClick = onSecondary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PickerFileRow(document: PickerDocument, selectedIndex: Int, onClick: () -> Unit, onLongClick: () -> Unit) {
    val p = LocalInkPalette.current
    val context = LocalContext.current
    val selected = selectedIndex >= 0
    val shape = RoundedCornerShape(15.dp)
    val selectionLabel = stringResource(if (selected) R.string.ordered_picker_selected_item else R.string.ordered_picker_unselected_item,
        selectedIndex + 1)
    // Consume directory holds without navigating. Only a normal tap opens a directory.
    val clickModifier = if (document.isDirectory) Modifier.combinedClickable(onClick = onClick, onLongClick = {})
        else Modifier.combinedClickable(role = Role.Checkbox, onClick = onClick, onLongClick = onLongClick,
            onLongClickLabel = stringResource(R.string.picker_batch_title)).semantics {
                this.selected = selected
                stateDescription = selectionLabel
            }
    Row(Modifier.fillMaxWidth().clip(shape).background(if (selected) p.accentSoft else p.surface)
        .border(1.dp, if (selected) p.accent.copy(alpha = .5f) else p.divider, shape)
        .then(clickModifier).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) p.surface else p.background),
            contentAlignment = Alignment.Center) {
            if (document.isDirectory) InkIcon(R.drawable.ic_folder, null, tint = p.accent, size = 23)
            else Text(document.name.substringAfterLast('.', "TXT").uppercase(Locale.ROOT).take(4),
                style = InkType.Caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold), color = p.accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(document.name, style = InkType.Body.copy(fontSize = 14.sp), color = p.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(if (document.isDirectory) stringResource(R.string.ordered_picker_folder)
                else document.size?.let { Formatter.formatShortFileSize(context, it) } ?: stringResource(R.string.ordered_picker_unknown_size),
                style = InkType.Caption.copy(fontSize = 10.5.sp), color = p.textFaint)
        }
        Spacer(Modifier.width(10.dp))
        if (document.isDirectory) InkIcon(R.drawable.ic_right, null, tint = p.textFaint, size = 18)
        else Box(Modifier.size(32.dp).clip(CircleShape).background(if (selected) p.accent else p.background)
            .border(1.dp, if (selected) p.accent else p.divider, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Text("${selectedIndex + 1}", style = InkType.Caption.copy(fontWeight = FontWeight.SemiBold),
                color = if (p.isDark) p.background else androidx.compose.ui.graphics.Color.White)
        }
    }
}

@Composable
private fun BatchAction(title: String, detail: String, onClick: () -> Unit) {
    val p = LocalInkPalette.current
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp))
        .background(p.background).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = InkType.Body.copy(fontSize = 14.sp), color = p.textPrimary)
            Spacer(Modifier.height(3.dp))
            Text(detail, style = InkType.Caption, color = p.textSecondary)
        }
        InkIcon(R.drawable.ic_right, null, tint = p.accent, size = 18)
    }
}
