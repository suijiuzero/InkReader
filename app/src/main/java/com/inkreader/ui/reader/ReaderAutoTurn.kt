package com.inkreader.ui.reader

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkreader.R
import com.inkreader.data.model.AutoTurnOptions
import com.inkreader.ui.component.InkChip
import com.inkreader.ui.component.InkIcon
import com.inkreader.ui.component.InkDialogShell
import com.inkreader.ui.component.InkTextButton
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette
import kotlin.math.roundToInt

internal fun AutoTurnOptions.deadline(running: Boolean): Long? =
    if (running && minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else null

@Composable
internal fun AutoTurnLine(progress: FloatState, modifier: Modifier) {
    val palette = LocalInkPalette.current
    val color = palette.textSecondary
    val shade = if (palette.isDark) Color.Gray.copy(alpha = .045f) else Color.Black.copy(alpha = .045f)
    Canvas(modifier) {
        val y = size.height * progress.floatValue.coerceIn(0f, 1f)
        drawRect(color = shade, size = Size(size.width, y))
        val head = Offset(5.dp.toPx(), y)
        drawLine(
            brush = Brush.horizontalGradient(
                0f to color.copy(alpha = .55f),
                .12f to color.copy(alpha = .22f),
                1f to color.copy(alpha = .08f)
            ),
            start = head,
            end = Offset(size.width, y),
            strokeWidth = 1.dp.toPx()
        )
        drawCircle(color.copy(alpha = .65f), radius = 2.dp.toPx(), center = head)
    }
}

@Composable
internal fun AutoTurnFooterButton(modifier: Modifier, onClick: () -> Unit) {
    val p = LocalInkPalette.current
    Row(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tint = p.textSecondary.copy(alpha = .8f)
        Text(stringResource(R.string.reader_auto_turn), style = InkType.Caption.copy(fontSize = 11.sp), color = tint)
        Spacer(Modifier.width(4.dp))
        InkIcon(R.drawable.ic_up, null, tint = tint, size = 10)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AutoTurnSettingsDialog(
    options: AutoTurnOptions,
    onDismiss: () -> Unit,
    onStop: (() -> Unit)?,
    onApply: (AutoTurnOptions) -> Unit
) {
    var seconds by remember { mutableIntStateOf(options.seconds) }
    var minutes by remember { mutableIntStateOf(options.minutes) }
    val p = LocalInkPalette.current
    InkDialogShell(
        onDismiss = onDismiss,
        title = stringResource(R.string.reader_auto_turn_settings),
        buttons = {
            FlowRow(horizontalArrangement = Arrangement.End) {
                if (onStop != null) {
                    InkTextButton(stringResource(R.string.reader_auto_turn_stop), color = p.textSecondary, onClick = onStop)
                }
                InkTextButton(stringResource(R.string.action_done)) { onApply(AutoTurnOptions(seconds, minutes)) }
            }
        }
    ) {
        Text(stringResource(R.string.reader_auto_turn_interval, seconds), style = InkType.UiLabel, color = p.textPrimary)
        val range = AutoTurnOptions.intervals
        Slider(
            value = seconds.toFloat(),
            onValueChange = { seconds = it.roundToInt() },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent)
        )
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.reader_auto_turn_timer), style = InkType.UiLabel, color = p.textPrimary)
        Spacer(Modifier.height(8.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (duration in AutoTurnOptions.durations) {
                InkChip(
                    text = if (duration == 0) stringResource(R.string.reader_auto_turn_unlimited)
                        else stringResource(R.string.reader_auto_turn_minutes, duration),
                    selected = minutes == duration,
                    onClick = { minutes = duration }
                )
            }
        }
    }
}
