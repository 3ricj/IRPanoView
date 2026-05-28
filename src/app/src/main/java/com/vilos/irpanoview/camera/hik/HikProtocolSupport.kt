package com.vilos.irpanoview.camera.hik

import com.vilos.irpanoview.usb.SupportedUsbCameras

/**
 * Feature gate for MasterThermoDocs Hik protocol path vs legacy Infisense UVC stack.
 */
object HikProtocolSupport {
    /** When true, [SupportedUsbCameras.VID_TOPDON_UVC]:[PID_TOPDON_UVC] uses [HikCameraController]. */
    const val USE_HIK_PROTOCOL: Boolean = true

    /** Android [HikUsbConnection] bulk/control/stop. Set false (default) — libusb experiment rolled back. */
    val USE_NATIVE_LIBUSB: Boolean = com.vilos.irpanoview.BuildConfig.HIK_NATIVE_USB

    fun isHikThermalDevice(vendorId: Int, productId: Int): Boolean =
        USE_HIK_PROTOCOL &&
            vendorId == SupportedUsbCameras.VID_TOPDON_UVC &&
            productId == SupportedUsbCameras.PID_TOPDON_UVC
}
