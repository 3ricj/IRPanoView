package com.vilos.irpanoview.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice

object SupportedUsbCameras {
    /** Current hardware (observations): VID 0x2BDF PID 0x0102 */
    const val VID_TOPDON_UVC = 0x2BDF
    const val PID_TOPDON_UVC = 0x0102

    /** TC001Max INF / stream_win.conf family */
    const val VID_INFRAY = 0x3474
    const val PID_TC001_MAX = 0x4962

    fun matches(device: UsbDevice): Boolean {
        val vid = device.vendorId
        val pid = device.productId
        return (vid == VID_TOPDON_UVC && pid == PID_TOPDON_UVC) ||
            (vid == VID_INFRAY && pid == PID_TC001_MAX) ||
            isVideoClassUsbDevice(device)
    }

    /**
     * Fallback for devices that enumerate with a bridge/generic UVC VID:PID
     * (for example "USB3 Video" on some Android host stacks).
     */
    private fun isVideoClassUsbDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        for (idx in 0 until device.interfaceCount) {
            val iface = device.getInterface(idx)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        }
        return false
    }
}
