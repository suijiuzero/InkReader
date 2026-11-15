package com.inkreader.ui.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.inkreader.R
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette
import java.util.Date

internal data class ReaderFooterStatus(val time: String, val batteryPercent: Int? = null)

@Composable
internal fun rememberReaderFooterStatus(active: Boolean): ReaderFooterStatus {
    val context = LocalContext.current.applicationContext
    fun timeNow() = DateFormat.getTimeFormat(context).format(Date())
    var status by remember(context) { mutableStateOf(ReaderFooterStatus(timeNow())) }
    DisposableEffect(context, active) {
        if (!active) return@DisposableEffect onDispose {}
        status = status.copy(time = timeNow())
        fun updateBattery(intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0)
            if (level in 0..scale && scale > 0) {
                status = status.copy(batteryPercent = (level * 100L / scale).toInt())
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) updateBattery(intent)
                else status = status.copy(time = timeNow())
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.takeIf { it.action == Intent.ACTION_BATTERY_CHANGED }?.let(::updateBattery)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return status
}

@Composable
internal fun ReaderFooterClockAndBattery(status: ReaderFooterStatus) {
    val color = LocalInkPalette.current.textSecondary
    Text(text = status.time, style = InkType.Caption.copy(fontSize = 10.sp), color = color, maxLines = 1, softWrap = false)
    Spacer(Modifier.width(6.dp))
    val description = status.batteryPercent?.let { stringResource(R.string.reader_battery_level, it) }
        ?: stringResource(R.string.reader_battery_unknown)
    Canvas(Modifier.size(17.dp, 9.dp).semantics { contentDescription = description }) {
        val stroke = 1.dp.toPx()
        val radius = CornerRadius(1.5.dp.toPx())
        val bodyWidth = size.width - 2.5.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(bodyWidth - stroke, size.height - stroke),
            cornerRadius = radius,
            style = Stroke(stroke)
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(bodyWidth + .6.dp.toPx(), size.height / 3),
            size = Size(1.4.dp.toPx(), size.height / 3),
            cornerRadius = CornerRadius(stroke / 2)
        )
        val inset = stroke * 1.7f
        val fill = (bodyWidth - 2 * inset) * ((status.batteryPercent ?: 0) / 100f)
        if (fill > 0) {
            drawRoundRect(color, Offset(inset, inset), Size(fill, size.height - 2 * inset), CornerRadius(stroke / 2))
        }
    }
}
