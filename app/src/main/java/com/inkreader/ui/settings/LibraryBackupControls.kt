package com.inkreader.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.inkreader.AppContainer
import com.inkreader.R
import com.inkreader.ui.component.InkDialogShell
import com.inkreader.ui.component.InkTextButton
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun LibraryBackupControls(container: AppContainer, onRestored: () -> Unit) {
    val p = LocalInkPalette.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var task by remember { mutableStateOf<Job?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    val success = stringResource(R.string.backup_success)
    val failure = stringResource(R.string.backup_failure)
    val cancelled = stringResource(R.string.backup_cancelled)
    fun launch(uri: Uri, restore: Boolean) {
        busy = true; message = ""
        task = scope.launch {
            try {
                if (restore) { container.repo.restoreBackup(uri); container.library.refresh(); container.library.syncUiTheme(); onRestored() }
                else container.repo.writeBackup(uri)
                message = success
            } catch (e: CancellationException) { message = cancelled; throw e }
            catch (_: Exception) { message = failure }
            finally { busy = false }
        }
    }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) launch(uri, false) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.backup_title), color = p.textPrimary)
    Text(stringResource(R.string.backup_description), color = p.textSecondary, style = InkType.Caption)
    if (busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        InkTextButton(stringResource(R.string.action_cancel)) { task?.cancel() }
    } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        InkTextButton(stringResource(R.string.backup_export)) { create.launch("InkReader-library-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())}.inkbackup") }
        InkTextButton(stringResource(R.string.backup_restore)) { open.launch(arrayOf("*/*")) }
    }
    if (message.isNotEmpty()) Text(message, color = p.accent, style = InkType.Caption)
    restoreUri?.let { uri -> InkDialogShell(onDismiss = { restoreUri = null }, title = stringResource(R.string.backup_restore), buttons = {
        Row {
            InkTextButton(stringResource(R.string.action_cancel)) { restoreUri = null }
            InkTextButton(stringResource(R.string.backup_restore)) { restoreUri = null; launch(uri, true) }
        }
    }) { Text(stringResource(R.string.backup_restore_explanation), color = p.textSecondary) } }
}
