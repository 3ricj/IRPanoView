package com.vilos.irpanoview.camera.hik

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.vilos.irpanoview.util.UvcDebugLogger

/**
 * Opens [HikUsbLink] — [HikUsbConnection] (Android USB) by default.
 * Opt-in libusb: `./gradlew :app:assembleDebug -PhikNativeUsb=true`
 */
object HikUsbLinks {
    fun open(
        manager: UsbManager,
        device: UsbDevice,
        timeoutMs: Long = HikUsbConnection.OPEN_TIMEOUT_MS,
        stopCheck: () -> Boolean = { false },
    ): HikUsbLink {
        if (HikProtocolSupport.USE_NATIVE_LIBUSB) {
            HikNativeUsb.ensureLoaded()
            return LibusbHikConnection.open(manager, device, timeoutMs, stopCheck)
        }
        return HikUsbConnection.open(manager, device, timeoutMs, stopCheck)
    }
}
