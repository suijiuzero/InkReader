package com.inkreader.ui.component

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.inkreader.R
import com.inkreader.data.model.SplitOptions
import com.inkreader.data.repo.BookRepository
import com.inkreader.parse.RecognitionBrowser
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 搜索与分批展示全书分章；原文行模式允许补充未识别的标题。 */
@Composable
fun RecognitionCenter(repo: BookRepository, uri: Uri, initial: SplitOptions,
    onDismiss: () -> Unit, onApply: (SplitOptions) -> Unit) {
    val p = LocalInkPalette.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var doubts by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }
    var ignored by remember { mutableStateOf(initial.ignoredHeadingLines) }
    var forced by remember { mutableStateOf(initial.forcedHeadingLines) }
    var snapshot by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<RecognitionBrowser.Page?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var renderedQuery by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    val options = initial.copy(ignoredHeadingLines = ignored, forcedHeadingLines = forced)
    LaunchedEffect(query, doubts, page, ignored, forced) {
        loading = true; failed = false
        try {
            result = repo.recognitionPage(uri, options, query, page, doubts, snapshot).also { snapshot = it.fingerprint }
            val key = "$query|$doubts|$page"
            if (key != renderedQuery) { listState.scrollToItem(0); renderedQuery = key }
        }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = p.surface, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).padding(18.dp)) {
                Text(stringResource(R.string.center_title), color = p.textPrimary, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = query, onValueChange = { query = it; page = 0 }, singleLine = true,
                    label = { Text(stringResource(R.string.center_search)) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.center_hint), color = p.textSecondary, style = InkType.Caption)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.center_doubts), color = p.textSecondary, modifier = Modifier.weight(1f))
                    Switch(checked = doubts, onCheckedChange = { doubts = it; page = 0 })
                }
                result?.let { Text(stringResource(R.string.center_summary, it.chapters, it.total, it.sourceLines), color = p.accent, style = InkType.Caption) }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed) Text(stringResource(R.string.center_error), color = MaterialTheme.colorScheme.error, style = InkType.Caption)
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(min = 120.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(result?.rows.orEmpty(), key = { it.line }) { row ->
                        val canEdit = row.editable && !loading && (row.line in ignored || row.line in forced || ignored.size + forced.size < 10000)
                        Column(Modifier.fillMaxWidth().background(p.background, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                            .toggleable(row.selected, enabled = canEdit, role = Role.Checkbox) { keep ->
                                if (keep) { ignored = ignored - row.line; forced = forced + row.line }
                                else { forced = forced - row.line; ignored = ignored + row.line }
                            }.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = row.selected, onCheckedChange = null, enabled = canEdit)
                                Text("${row.line} · ${row.title}", color = p.textPrimary, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            }
                            if (row.modelSuggestion && !row.selected) Text(stringResource(R.string.analysis_model_pending), color = p.accent, style = InkType.Caption)
                            if (row.context.isNotBlank()) Text(row.context, color = p.textSecondary, style = InkType.Caption,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (ignored.size + forced.size >= 10000) Text(stringResource(R.string.center_limit), color = p.accent, style = InkType.Caption)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { page-- }, enabled = !loading && page > 0 && result?.sourceView == false) { Text(stringResource(R.string.center_previous)) }
                    TextButton(onClick = { page++ }, enabled = !loading && result?.let { !it.sourceView && (it.page + 1) * 40 < it.total } == true) { Text(stringResource(R.string.center_next)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    TextButton(enabled = !loading && !failed && result != null, onClick = {
                        loading = true
                        scope.launch {
                            try {
                                val bound = options.copy(sourceFingerprint = snapshot)
                                repo.recognitionPage(uri, bound, query, page, doubts)
                                onApply(bound)
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { failed = true }
                            finally { loading = false }
                        }
                    }) { Text(stringResource(R.string.center_apply)) }
                }
            }
        }
    }
}
