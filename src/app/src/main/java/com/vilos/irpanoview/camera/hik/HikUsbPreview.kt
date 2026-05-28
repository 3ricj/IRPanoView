package com.vilos.irpanoview.camera.hik

import android.hardware.usb.UsbDevice
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.vilos.irpanoview.camera.ThermalFrameView
import com.vilos.irpanoview.model.ThermalColorPalette
import kotlinx.coroutines.delay

/** Compose preview for MasterThermoDocs Hik protocol path. */
@Composable
fun HikUsbPreview(
    device: UsbDevice,
    palette: ThermalColorPalette,
    streamEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    onStatusChange: (String?) -> Unit = {},
) {
    val context = LocalContext.current
    val busPath = device.deviceName ?: return
    if (!streamEnabled) return
    var controllerRef by remember(busPath) { mutableStateOf<HikCameraController?>(null) }
    val frameViewRef = remember(busPath) { arrayOfNulls<ThermalFrameView>(1) }

    fun wireFrameDelivery(controller: HikCameraController) {
        controller.setPalette(palette)
        controller.onStatus = onStatusChange
        controller.onError = onStatusChange
        val view = frameViewRef[0]
        if (view != null) {
            view.setDebugStreamTag(busPath)
            controller.onFrameBitmap = { packet ->
                view.post {
                    view.updateBitmap(
                        newFrame = packet.bitmap,
                        frameSeq = packet.frameSeq,
                        frameFingerprint = packet.frameFingerprint,
                        postedAtMs = packet.publishedAtMs,
                    )
                }
            }
        }
    }

    DisposableEffect(busPath, device.deviceId) {
        if (HikCameraRegistry.isGlobalShutdownInFlight()) {
            onDispose { }
        } else {
            HikStartupMetrics.mark(context, busPath, "acquire")
            val controller = HikCameraRegistry.acquire(context, device)
            controllerRef = controller
            wireFrameDelivery(controller)
            controller.start()
            onDispose {
                HikCameraRegistry.release(busPath)
                controllerRef = null
            }
        }
    }

    SideEffect {
        val controller = controllerRef ?: return@SideEffect
        wireFrameDelivery(controller)
        controller.syncStatusToListener()
    }

    LaunchedEffect(busPath, device.deviceId) {
        while (true) {
            delay(1_500)
            if (HikCameraRegistry.isGlobalShutdownInFlight()) continue
            val controller = controllerRef ?: continue
            when {
                HikUsbStackRecovery.isFaulted() -> controller.syncStatusToListener()
                !controller.isWorkerRunning() -> controller.start()
                controller.isStartupStalled() &&
                    !HikUsbOpenGate.isOpenLocked() &&
                    !HikUsbStackRecovery.hasAnyInflight() -> controller.forceRestartWorker()
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                ThermalFrameView(ctx).also { view ->
                    view.setDebugStreamTag(busPath)
                    frameViewRef[0] = view
                    controllerRef?.let { wireFrameDelivery(it) }
                }
            },
        )
    }
}
