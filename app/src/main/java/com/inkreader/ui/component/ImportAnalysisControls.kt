package com.inkreader.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkreader.R
import com.inkreader.data.repo.BookRepository
import com.inkreader.parse.CharsetDetector
import com.inkreader.parse.HeadingReason
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette

@Composable
fun ImportAnalysisControls(
    preview: BookRepository.ImportPreview?,
    singleFile: Boolean,
    encoding: String,
    onEncodingChange: (String) -> Unit,
    ignored: Set<Int>,
    rememberCorrections: Boolean,
    onRememberChange: (Boolean) -> Unit,
    onToggleHeading: (Int) -> Unit,
    onOpenFull: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var encodingMenu by remember { mutableStateOf(false) }
    val p = LocalInkPalette.current
    val pv = preview ?: return
    val analysis = pv.analysis
    val detected = pv.encoding
    Spacer(Modifier.height(10.dp))
    if (pv.totalFiles > pv.sampledFiles) {
        Text(stringResource(R.string.analysis_sampled, pv.sampledFiles, pv.totalFiles, pv.failedFiles),
            style = InkType.Caption, color = p.textSecondary)
    }
    if (analysis == null && detected == null) return
    InkTextButton(stringResource(if (expanded) R.string.analysis_hide else R.string.analysis_show)) { expanded = !expanded }
    if (!expanded) return
    Column(Modifier.fillMaxWidth().background(p.background, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (detected != null) {
            Text(stringResource(R.string.analysis_encoding, detected.charset.displayName()),
                style = InkType.UiLabel, color = p.textPrimary)
            if (detected.uncertain) Text(stringResource(R.string.analysis_encoding_uncertain),
                style = InkType.Caption, color = p.accent)
            if (singleFile) {
                Box {
                    InkTextButton(stringResource(R.string.analysis_choose_encoding)) { encodingMenu = true }
                    DropdownMenu(expanded = encodingMenu, onDismissRequest = { encodingMenu = false }) {
                        val labels = listOf("" to stringResource(R.string.analysis_auto_encoding)) +
                            CharsetDetector.selectableNames.map { it to it }
                        labels.forEach { (value, label) ->
                            DropdownMenuItem(text = { Text((if (encoding == value) "✓ " else "") + label) },
                                onClick = { encodingMenu = false; onEncodingChange(value) })
                        }
                    }
                }
            } else Text(stringResource(R.string.analysis_single_encoding), style = InkType.Caption, color = p.textSecondary)
            if (pv.textPreview.isNotBlank()) Text(pv.textPreview, style = InkType.Caption,
                color = p.textSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        if (analysis != null) {
            if (singleFile && onOpenFull != null) InkTextButton(stringResource(R.string.center_title), onClick = onOpenFull)
            if (analysis.skippedTocEntries > 0) Text(stringResource(R.string.analysis_toc, analysis.skippedTocEntries),
                style = InkType.Caption, color = p.textSecondary)
            if (analysis.usedSizeFallback) Text(stringResource(R.string.analysis_fallback),
                style = InkType.Caption, color = p.textSecondary)
            analysis.gaps.take(5).forEach { gap ->
                Text(stringResource(R.string.analysis_gap, gap.after, gap.before, gap.missing.joinToString("、")),
                    style = InkType.Caption, color = p.accent)
            }
            val decisions = analysis.decisions.filter { it.accepted || HeadingReason.USER_IGNORED in it.reasons }
            if (decisions.isNotEmpty()) {
                Text(stringResource(if (singleFile) R.string.analysis_edit_hint else R.string.analysis_first_file_hint),
                    style = InkType.Caption, color = p.textSecondary)
                if (singleFile) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.analysis_remember), style = InkType.Caption, color = p.textSecondary, modifier = Modifier.weight(1f))
                        Switch(checked = rememberCorrections, onCheckedChange = onRememberChange)
                    }
                }
                decisions.take(40).forEach { decision ->
                    Row(Modifier.fillMaxWidth().then(if (singleFile) Modifier.toggleable(
                        value = decision.line !in ignored, role = Role.Checkbox,
                        onValueChange = { onToggleHeading(decision.line) }
                    ) else Modifier),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (singleFile) {
                            Checkbox(checked = decision.line !in ignored, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(Modifier.weight(1f).padding(vertical = 5.dp)) {
                            Text(decision.title, style = InkType.UiLabel, color = p.textPrimary,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val reason = when {
                                HeadingReason.USER_IGNORED in decision.reasons -> R.string.analysis_reason_ignored
                                HeadingReason.NUMBER_RECOVERY in decision.reasons -> R.string.analysis_reason_recovery
                                HeadingReason.UNNUMBERED_TITLE in decision.reasons -> R.string.analysis_reason_unnumbered
                                HeadingReason.LOCAL_STYLE in decision.reasons -> R.string.analysis_reason_local
                                HeadingReason.NUMBER_SEQUENCE in decision.reasons -> R.string.analysis_reason_sequence
                                else -> R.string.analysis_reason_shape
                            }
                            Text(stringResource(reason), style = InkType.Caption, color = p.textSecondary)
                        }
                    }
                }
                if (decisions.size > 40 || analysis.truncated) Text(stringResource(R.string.analysis_limited),
                    style = InkType.Caption, color = p.textSecondary)
            }
        }
    }
}

@Composable
fun UnnumberedTitleControls(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val p = LocalInkPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch,
            onValueChange = onCheckedChange), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.analysis_unnumbered_title), modifier = Modifier.weight(1f),
                style = InkType.UiLabel, color = p.textPrimary)
            Switch(checked = checked, onCheckedChange = null)
        }
        Text(stringResource(R.string.analysis_unnumbered_hint), style = InkType.Caption, color = p.textSecondary)
    }
}
