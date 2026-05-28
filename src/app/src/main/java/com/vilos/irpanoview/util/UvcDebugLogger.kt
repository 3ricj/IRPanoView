package com.vilos.irpanoview.util

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only UVC debug log under app external storage (pull with adb, no run-as).
 *
 * `adb pull /sdcard/Android/data/com.vilos.irpanoview/files/logs/ .`
 */
object UvcDebugLogger {

    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun log(context: Context, tag: String, message: String) {
        runCatching {
            val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
            val line = "${ts.format(Date())} [$tag] $message\n"
            File(dir, "uvc-debug.log").appendText(line)
            File(dir, "uvc-debug-latest.txt").writeText(
                buildString {
                    appendLine("IRPanoView UVC log (latest session)")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}")
                    appendLine("Log dir: ${dir.absolutePath}")
                    appendLine("Pull: adb pull \"${dir.absolutePath}\" \"./irpanoview-logs/\"")
                    appendLine("---")
                    append(line)
                },
            )
        }
    }

    fun logDevice(context: Context, deviceName: String, vendorId: Int, productId: Int) {
        log(context, deviceName, "USB device vid=${"%04X".format(vendorId)} pid=${"%04X".format(productId)}")
    }
}
