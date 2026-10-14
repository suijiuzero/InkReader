package com.inkreader.ui.component

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「选文件 → 导入」这条链路的落盘留痕。
 *
 * **为什么不用 Log**：实测这台 vivo 会压制第三方应用的日志 —— 连续抓 376KB 系统日志，
 * 我们自己的 `Log.i` 一行都没有，出问题时无法判断走到哪一步（是没发起选择器？结果没回来？
 * 还是导入阶段失败？）。所以关键节点同时写一份到 `filesDir/picker_trace.txt`，
 * 用 `adb shell run-as com.inkreader cat files/picker_trace.txt` 就能读出来（debug 包可读）。
 *
 * 定位完问题后可以直接删掉这个文件和调用点，或保留（每次都 append，只增不删，很小）。
 */
object PickerTrace {

    private const val FILE_NAME = "picker_trace.txt"
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * 只在 **debug 构建**下落盘：正式包里这里是空操作，既不写文件也不泄漏任何路径信息。
     * （用 `FLAG_DEBUGGABLE` 判断而不是 `BuildConfig`，免得为了它去开 buildConfig 开关。）
     */
    fun log(ctx: Context, msg: String) {
        val debuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        runCatching {
            File(ctx.filesDir, FILE_NAME).appendText("${fmt.format(Date())}  $msg\n")
        }
    }
}
