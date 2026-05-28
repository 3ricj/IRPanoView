package com.vilos.irpanoview.camera

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.energy.iruvc.uvc.USBUVCCamera

/**
 * Opens a vendor libUSBUVCCamera handle from Android UsbManager primitives.
 */
object Tc001VendorSessionBridge {
    data class Session(
        val handle: Long,
        val connectResult: Int,
        val busNum: Int,
        val devNum: Int,
        val connection: UsbDeviceConnection,
    )

    data class OpenAttempt(
        val session: Session?,
        val reason: String,
    )

    fun open(context: Context, device: UsbDevice): OpenAttempt {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return OpenAttempt(null, "UsbManager unavailable")
        val connection = manager.openDevice(device) ?: return OpenAttempt(null, "openDevice returned null")
        val fd = connection.fileDescriptor
        if (fd <= 0) return OpenAttempt(null, "invalid fileDescriptor=$fd")

        val (busNum, devNum) = parseBusDev(device.deviceName)
        val handleResult = runCatching { USBUVCCamera.nativeCreate() }
        if (handleResult.isFailure) {
            return OpenAttempt(null, "nativeCreate exception=${handleResult.exceptionOrNull()?.javaClass?.simpleName}:${handleResult.exceptionOrNull()?.message}")
        }
        val handle = handleResult.getOrDefault(0L)
        if (handle == 0L) return OpenAttempt(null, "nativeCreate returned 0")
        val rc = runCatching {
            USBUVCCamera.nativeConnect(
                handle,
                device.vendorId,
                device.productId,
                fd,
                busNum,
                devNum,
                "/dev/bus/usb",
            )
        }
        if (rc.isFailure) {
            return OpenAttempt(
                session = Session(handle, Int.MIN_VALUE, busNum, devNum, connection),
                reason = "nativeConnect exception=${rc.exceptionOrNull()?.javaClass?.simpleName}:${rc.exceptionOrNull()?.message}",
            )
        }
        return OpenAttempt(
            session = Session(
                handle = handle,
                connectResult = rc.getOrDefault(Int.MIN_VALUE),
                busNum = busNum,
                devNum = devNum,
                connection = connection,
            ),
            reason = "ok",
        )
    }

    fun close(session: Session) {
        val handle = session.handle
        if (handle == 0L) return
        runCatching { USBUVCCamera.nativeRelease(handle) }
        runCatching { USBUVCCamera.nativeDestroy(handle) }
        runCatching { session.connection.close() }
    }

    fun closeFast(session: Session) {
        val handle = session.handle
        if (handle == 0L) return
        runCatching { USBUVCCamera.nativeDestroy(handle) }
        runCatching { session.connection.close() }
    }

    private fun parseBusDev(deviceName: String?): Pair<Int, Int> {
        if (deviceName.isNullOrBlank()) return 0 to 0
        val parts = deviceName.split('/')
        if (parts.size < 2) return 0 to 0
        val bus = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 0
        val dev = parts.lastOrNull()?.toIntOrNull() ?: 0
        return bus to dev
    }
}
