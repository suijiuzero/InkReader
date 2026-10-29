package com.inkreader.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.inkreader.AppContainer
import com.inkreader.R
import com.inkreader.ui.component.InkCard
import com.inkreader.ui.component.InkTextButton
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsControls(container: AppContainer) {
    val palette = LocalInkPalette.current
    val store = container.diagnostics
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val exported = stringResource(R.string.usage_exported)
    val cleared = stringResource(R.string.usage_cleared)
    val failed = stringResource(R.string.usage_failed)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            busy = true
            container.scope.launch {
                message = runCatching { store.export(uri); exported }.getOrElse { failed }
                busy = false
            }
        }
    }
    InkCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.usage_title), style = InkType.UiLabel, color = palette.textPrimary, modifier = Modifier.weight(1f))
                Switch(checked = store.enabled, onCheckedChange = { store.changeEnabled(it) })
            }
            Text(stringResource(R.string.usage_description), style = InkType.Caption, color = palette.textSecondary)
            if (!busy) Row {
                InkTextButton(stringResource(R.string.usage_export)) {
                    picker.launch("InkReader-usage-report.json")
                }
                InkTextButton(stringResource(R.string.usage_clear)) {
                    busy = true
                    container.scope.launch {
                        message = runCatching { store.clear(); cleared }.getOrElse { failed }
                        busy = false
                    }
                }
            }
            if (busy) Text(stringResource(R.string.action_loading), style = InkType.Caption, color = palette.textSecondary)
            if (message.isNotEmpty()) Text(message, style = InkType.Caption, color = palette.textSecondary)
        }
    }
}
