package com.inkreader.ui.reader

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.inkreader.data.diagnostics.UsageDiagnostics

/** Observes produced frames only, without requesting additional frames or logging book data. */
@Composable
internal fun ReaderDiagnostics(activity: Activity?, store: UsageDiagnostics) {
    DisposableEffect(activity, store.enabled) {
        val window = activity?.window
        if (!store.enabled || window == null) return@DisposableEffect onDispose { }
        val thread = HandlerThread("InkUsageFrames").apply { start() }
        val handler = Handler(thread.looper)
        var count = 0; var slow = 0; var total = 0.0; var maximum = 0.0
        fun flush() {
            if (count == 0) return
            store.record(UsageDiagnostics.Event.FRAME_SAMPLE, mapOf("frames" to count, "over50ms" to slow,
                "meanMs" to total / count, "maxMs" to maximum))
            count = 0; slow = 0; total = 0.0; maximum = 0.0
        }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val ms = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
            if (ms >= 0 && ms.isFinite()) {
                count++; if (ms > 50) slow++
                total += ms; maximum = maxOf(maximum, ms)
                if (count >= 120) flush()
            }
        }
        window.addOnFrameMetricsAvailableListener(listener, handler)
        onDispose {
            window.removeOnFrameMetricsAvailableListener(listener)
            handler.post { flush(); thread.quitSafely() }
        }
    }
}
