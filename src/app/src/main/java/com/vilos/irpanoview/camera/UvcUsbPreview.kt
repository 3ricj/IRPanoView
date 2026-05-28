package com.vilos.irpanoview.camera

import android.hardware.usb.UsbDevice
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.vilos.irpanoview.model.ThermalColorPalette

@Composable
fun UvcUsbPreview(
    device: UsbDevice,
    palette: ThermalColorPalette,
    modifier: Modifier = Modifier,
    onStatusChange: (String?) -> Unit = {},
) {
    val context = LocalContext.current

    val controller = remember(device) {
        UvcCameraController(context, device).apply {
            setPalette(palette)
            onStatus = onStatusChange
            onError = onStatusChange
        }
    }

    SideEffect {
        controller.setPalette(palette)
        controller.onStatus = onStatusChange
        controller.onError = onStatusChange
    }

    DisposableEffect(device) {
        controller.start()
        onDispose { controller.release() }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                ThermalFrameView(ctx).also { view ->
                    controller.onFrameBitmap = { bmp ->
                        view.post { view.updateBitmap(bmp) }
                    }
                }
            },
        )
    }
}
