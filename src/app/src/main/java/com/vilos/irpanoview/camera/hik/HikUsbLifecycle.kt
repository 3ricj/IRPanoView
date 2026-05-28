package com.vilos.irpanoview.camera.hik

import android.app.Application
import android.content.Context
import com.vilos.irpanoview.util.UvcDebugLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ensures Hik USB sessions are disarmed on app leave.
 *
 * Default path is reference leave ([pauseAll]): cancel + join, keep fd (Android USB).
 * libusb adds alt-0 + release when [HikProtocolSupport.USE_NATIVE_LIBUSB].
 * [shutdownAll] remains for stale-camera removal and legacy full-exit recipes (E9).
 */
object HikUsbLifecycle {

    private val installed = AtomicBoolean(false)

    fun install(app: Application) {
        if (!installed.compareAndSet(false, true)) return
        Runtime.getRuntime().addShutdownHook(
            Thread(
                {
                    runCatching {
                        HikCameraRegistry.pauseAll(reason = "shutdown hook")
                    }
                },
                "hik-usb-shutdown",
            ),
        )
    }

    fun pauseAll(context: Context, reason: String): Int {
        UvcDebugLogger.log(context, "hik-lifecycle", "Hik USB reference pause: $reason")
        return HikCameraRegistry.pauseAll(context, reason)
    }

    fun shutdownAll(context: Context, reason: String): Int {
        UvcDebugLogger.log(context, "hik-lifecycle", "Hik USB shutdown: $reason")
        return HikCameraRegistry.shutdownAll(context, reason)
    }
}
